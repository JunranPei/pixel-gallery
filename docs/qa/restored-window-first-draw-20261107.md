# 恢复窗口首次绘制验证

## 已确认的触发路径

手机 `live-4364-20261107-212046` 的两次闪烁，对应既有任务 29899 和 29903 被重新创建。原始日志有实际绘制 vsync 对应的空图片层，标签来源在恢复时还先发出 0 项、随后 4 项。详见 `two-flickers-20261107-212046.md`。

本项处理恢复时的首次绘制交接；此前已经验证的共享索引、媒体记录复用、分组设置去重和监听生命周期修复继续保留。OEM o-kill 的触发规则仍单独追查。

官方依据：[Android 首次绘制与数据准备](https://developer.android.com/develop/ui/views/launch/splash-screen#suspend-drawing)。Android 在首次绘制时移除启动窗口，可以在内容就绪前延后首次绘制。当前实现使用 ViewTreeObserver 的 pre-draw 回调，不增加自建截图或启动页。

## 独立绘制监听的修正

`ViewerRecoveryFrameChecks` 通过 Instrumentation 打开自己创建的 MediaStore JPEG，清除隔离应用的 Glide 内存缓存，调用真实 `Activity.recreate()`。通过原生 ViewTreeObserver 记录实际绘制时 ImageView / 瓦片视图的内容状态，不用截图识别。

旧记录的 focusedDraws=0 不能当作通过证据。核查发现 onActivityCreated 从 super.onCreate 中派发；之后应用再次设置通知权限弹窗。测试改在 onActivityPostCreated 关闭无关弹窗，等待真实窗口获得焦点，保留获得焦点之前的第一帧；销毁 Activity 时移除绘制监听，避免旧监听跟随保留窗口继续记录。

有效基线：`scratch/version-memory-comparison/recovery-baseline-focus2-frames.json`。

| 恢复轮次 | 显示中的绘制数 | 获得焦点的绘制数 | 首部无图片绘制数 | 到图片就绪的间隔 |
| --- | ---: | ---: | ---: | ---: |
| recreate-0 | 5 | 5 | 1 | 96 ms |
| recreate-1 | 5 | 5 | 1 | 100 ms |
| recreate-2 | 5 | 5 | 1 | 101 ms |

该基线确认恢复缺口；调用 recreate 是故障注入，不描述为复现厂商杀进程。

## 修复和验收矩阵

本地候选使用每个 Activity 独立的 RestoredWindowDrawGate：只有 savedInstanceState 非空时启用，恢复图片路由等待当前页解码完成，再放行首次绘制。普通冷打开及已有窗口重新聚焦不安装该等待。当前页加载失败允许错误路径显示；5 秒兜底避免数据缺失或异常加载使窗口永久不能绘制。正常或错误回调到达就立即放行。非图片路由目前立即放行，grid 的恢复验收仍待完成。

| 编号 | 用例 | 状态 | 证据 |
| --- | --- | --- | --- |
| R1 | 真实外部图片页面连续重建，检查第一张实际绘制已有图片 | 修复前失败；候选通过 | recovery-baseline-focus2、recovery-gate3 |
| R2 | 相册来源、标签来源保存路由后重建 | 候选通过，各重建 3 次 | recovery-gate-album2 / recovery-gate-label1 |
| R3 | 已有详情窗口切回不重建 | 内存修复版通过；恢复候选待测 | patched-ui-stress4/5/6 |
| R4 | 文件删除或加载失败后仍能显示并退出 | 候选通过 | recovery-missing3 |
| R5 | 主 grid 被销毁后的数据、缩略图恢复 | 待测 | 原六次闪烁记录 |
| R6 | 多窗口的就绪事件不能放行另一个窗口 | 待测 | — |
| R7 | 整个进程结束后恢复同一个任务 | 外部图片路径通过；内部来源待测 | recovery-process-2 |

手机上目前安装的是先前 memory-fix 包，不包含这次候选首次绘制处理。先在隔离包 com.pixel.gallery.memorypatched 完成验证。

首轮候选 `recovery-gate1`：三次重建均采到 3 个有焦点绘制、首部空图片帧 0；但是三次 `RESTORE_DRAW_RELEASE` 均为 timeout，等待了约 2 秒。因此该候选失败。修正为由当前页图片就绪回调直接放行，并在组合中订阅就绪状态，验收同时检查实际绘制与 image-ready 原因。

`recovery-gate2`：三次重建各有 4 个有焦点绘制、首部空图片帧 0；三次均由 image-ready 放行。

删除文件边界的第一轮 `recovery-missing1` 无效：销毁前 clearMemory 没能清除尚在使用的 Glide 图像，销毁后旧图又回到了内存缓存。测试增加销毁后 clearMemory，确认已删 URI 不能读取。第二轮 `recovery-missing2` 得到真实 FileNotFoundException，但错误回调比 2 秒兜底晚 138 ms，候选提前按 timeout 放行，验收失败。将兜底上限改为 5 秒后复测，保留前两次失败证据。

最新候选结果（同一应用 APK，日志与帧文件位于 scratch/version-memory-comparison）：

| 运行 | 重建数 | 每次有焦点绘制 | 首部空图片帧 | 放行原因 | 结果 |
| --- | ---: | --- | --- | --- | --- |
| recovery-gate3 | 3 | 4 / 4 / 4 | 0 / 0 / 0 | 三次 image-ready | 通过 |
| recovery-missing3 | 1 | 3 | 3（文件已删除，预期无图） | image-error | 通过，返回后日志确认 Home |

gate3 从请求重建到第一帧为 1135 / 941 / 887 ms，未依赖 5 秒超时。missing3 为 1230 ms，错误回调放行后实际窗口获得焦点、Back 能回到 Home。上述延迟包含 Activity 与界面的恢复过程，不等同于图片解码耗时。

内部路由的夹具通过 MediaStore 新建独立图片；普通相册从主界面点击 Albums → 自己创建的相册 → 缩略图；标签路径创建独立标签及成员关系，再点击 Albums → Labels → 测试标签 → 缩略图。之后真实 recreate，测试不调用 setContent，不重新注入目的页面。

- recovery-gate-album2：3 次重建，每次 4 个有焦点实际绘制，首部无图片帧均为 0，三次 image-ready 放行。重建到首帧 700 / 451 / 351 ms。
- recovery-gate-label1：3 次重建，每次 4 个有焦点实际绘制，首部无图片帧均为 0，三次 image-ready 放行。重建到首帧 515 / 429 / 402 ms。
- 内部路径首轮 recovery-gate-album1 在准备阶段失败：之前 131014 条合成数据库夹具与 MediaStore 不符，sync 清理 obsoleteIds 的 SQL 参数超过上限，尚未进入图片页面。已将隔离包数据库及文件备份为 pre-internal-recovery-fixture.tar（371 个条目、46476800 字节），再清空唯一隔离包 com.pixel.gallery.memorypatched，重新运行得到上面的有效结果。记录的批量参数边界仍需单独处理，未改动手机数据。

## 进程退出后恢复既有任务

2026-11-08 补充主 grid 进程恢复：`recovery-process-photos-baseline1` 的准备通过测试框架退出，后续 create 的 saved=false，不能作为恢复证据。改为准备结束后从普通入口再次进入目标页，退到后台、结束进程，再点击原任务。

`recovery-process-photos-baseline2`：同一任务 1879，PID 4126 → 4318，saved=true，52 个实际绘制中前 3 帧无缩略图。探针直接读取已放置、处于屏幕内的 Glide 绘制节点，反射错误 0。此处确认现有 `non-image-route` 放行分支遗漏主 grid；照片及相册 grid 的数据和可见缩略图等待修复正在复测。保留失败 result.json、frames.json、logcat.txt 和前后原生节点 XML。

候选失败记录：`recovery-process-photos-gridgate1` 已区分加载前与真正空图库，但在缩略图注册前按 grid-ready 提前放行，前 2 帧无缩略图。`recovery-process-photos-gridgate2` 增加 lazy-grid 布局条件后以 timeout 放行，前 2 帧仍无缩略图。两轮均未通过，不作为修复完成证据；继续核查阻止首次 draw 对 Compose 待处理布局的影响。

`gridgate3` 的监听确认循环等待：data=true、matched=true、layout=null、registered=0，直到 timeout 才出现布局及图片。当前修复在此条件下向 Compose 所在 View 请求正常布局，不依赖 dispatchDraw 才创建 lazy-grid 子项；加载中与已加载空结果保持区分，数据及实际 grid 采用同一份引用，并只等待屏幕内图片。放行或超时时清理两页登记及数据引用。

`recovery-process-photos-gridgate4` 通过：任务 1887，PID 10642 → 10802，saved=true；54 个实际 draw，首帧已有 Success 缩略图，首部空白/不完整帧均 0；唯一放行原因 grid-ready。逐条件记录由 layout=null/registered=0 进入 layout=1/registered=1，再到 complete=1；未触发 timeout。应用 SHA-256 `8f7daf9f9b29d1ca9b7d1f1a7997a004f647cfe8c35e4204d1571ec3926c0b29`。

同一产品 APK 的补充结果：

| 运行 | 实际 draw | 初始无内容帧 | 放行 | 结果 |
| --- | ---: | ---: | --- | --- |
| recovery-process-albums-gridgate1 | 52 | 0 | grid-ready | 通过，首帧起缩略图坐标一致 |
| recovery-process-photos-empty2 | 47 | 空图库预期无图片 | grid-ready | 通过，未超时 |
| recovery-process-albums-empty1 | 46 | 空图库预期无图片 | grid-ready | 通过，未超时 |
| recovery-process-album-gridgate2 | 120 | 0 | image-ready | 通过，原任务与保存状态均核对 |
| recovery-process-label-gridgate2 | 116 | 0 | image-ready | 通过，标签路径在 NAV_STATE 中核对 |
| recovery-process-albums-labels1 | 64 | 0 | grid-ready | 通过，2 张缩略图首帧齐全、位置恒定 |
| recovery-process-photos-eight2 | 41 | 0 | grid-ready | 通过，8 张缩略图首帧齐全、位置恒定 |

测试工具修正记录：photos-empty1 对已删除 URI 申请授权，启动被系统拒绝；空图库 ACTION_MAIN 去掉读 URI 授权后得到 empty2。album-gridgate1 点击文本未进入相册，改成对可点击父节点执行操作得到 gridgate2。label-gridgate1 的准备标签与普通相册同名，普通入口重走时错选普通相册，且监听在进入大图前耗尽 120 条上限；改成独立标签名、检查是否已展开，再将测试记录上限增至 2000。失败轮次均保留，未记为产品恢复失败或通过。

photos-eight1 误把已选中的 Photos 导航项逐级查找至无 bounds 的根节点；找不到可点击祖先时保留原节点后得到 eight2。eight2 产品包含普通浏览时不保留 Quick Sort 初始 sessionSlots，以及等待全部可见缩略图登记；标签加载尚未完成/已完成空结果的区分为随后追加，仍需在该最终候选上回归。

最终加载状态候选 SHA-256 `0b117735be772ee819ed8797deaf109463b7333c49410b84f4c680e36a5cacd3` 已补上标签输入尚未加载的区分。`grid-restore-final-source-verification.json` 核对 25 个产品文件与主工作树一致，15 项相关 JVM 测试通过（含延迟标签成员输入）。最终四项整进程恢复结果：

| 运行后缀 | 实际 draw | 首帧缩略图 | 初始无内容帧 | 布局种类 | 放行 |
| --- | ---: | ---: | --- | ---: | --- |
| photos-final8 | 59 | 8/8 | 0 | 1 | grid-ready |
| albums-final8 | 49 | 8/8 | 0 | 1 | grid-ready |
| albums-final-label | 55 | 2/2 | 0 | 1 | grid-ready |
| albums-final-empty | 47 | 0，真实空图库 | 预期无图片 | 1 | grid-ready |

四项均确认旧进程退出、原任务恢复、saved=true、没有探针错误。空图库的原始计数 initialEmptyDraws=47 是预期内容状态，passed 的条件为无需图片且未超时，未将其计作 47 次闪烁。下一项使用关闭绘制诊断的同源 APK 做 50 窗口回归。

`run_process_recovery.py` 在 emulator-5562 上新建自己的真实 MediaStore JPEG，以 ACTION_VIEW 打开隔离应用，取消无关通知权限对话框；返回桌面后执行 `am kill com.pixel.gallery.memorypatched`，确认 PID 消失。再通过最近任务的原生节点位置点击旧任务卡片，不重新发送图片地址或新建 Activity。

第一轮 recovery-process-1 的工具只识别 mResumedActivity 字段，Android 35 输出 topResumedActivity，尚未结束进程就超时；修正解析并保留该记录。

第二轮 recovery-process-2：原 PID 17791 结束，恢复 PID 17938；任务 ID 始终 1210。ACTIVITY_CREATE 的 saved=true，唯一放行事件是 image-ready。35 个不同 vsync 的实际绘制全部匹配 FRAME_STATE，均有预期 URI 的就绪预览；其中唯一 firstDraw=1 同样已就绪，没有未就绪绘制。匹配按 entry + vsync，排除重复计数；未使用图像识别。

证据：`scratch/version-memory-comparison/recovery-process-2/result.json`、`frame-validation.json`、`restored-logcat.txt`、前后任务状态及原生节点 XML。`analyze_process_recovery.py` 独立核对首次实际绘制与图片状态。此处人为结束隔离包进程，不代表已经复现厂商 o-kill(4010) 的触发规则。
