# 窗口恢复后的工具栏与视频状态

## 已定位的原因

ViewerScreen 的 showUI 原来使用 remember，初始值 true；Activity 重建后必然显示工具栏。VideoPlaybackSession 的 wantsPlay 原来直接取 motionPhoto 或 initialSettings.autoplay，initialPosition 只遵循全局 rememberPosition；暂停、精确位置、临时倍速及静音没有作为任务状态保存。先前首次绘制修复保留了恢复期间的图片显示，交互状态仍走这些默认值。

历史核对：4.3.3.2 对照源码 ViewerScreen 第 363 行已有 showUI 的相同初始逻辑。视频 wantsPlay / initialPosition 的 blame 指向 4ff54de56（2026-10-25）。ViewerScreen 更早的完整 blame 因本地缺少 promisor 对象且代理 127.0.0.1:7626 不可达失败，未据此推断更早引入日期。

## 实际对照

隔离包 com.pixel.gallery.memorypatched，真实视频任务、Activity.recreate、播放器回调及原生界面节点；未使用图像识别。两个窗口全局 autoplay=true、rememberPosition=false、rememberSpeed=false，先暂停并分别设置位置、速度和静音，再等待正常工具栏隐藏。

| 检查项 | 原版本 baseline5 | 修复 fixed1 |
| --- | --- | --- |
| 原任务恢复 saved=true | 两窗均是 | 两窗均是 |
| 工具栏重新出现 | 两窗均出现 | 两窗均保持隐藏 |
| 暂停后自动播放 | 两窗均播放 | 两窗均保持暂停 |
| 窗口一位置 | 896 → 1507 ms，原为 666 ms | 666 → 666 ms |
| 窗口二位置 | 793 → 1399 ms，原为 1333 ms | 1333 → 1333 ms |
| 倍速 | 两窗均重置 1× | 保持 0.75× / 1.25× |
| 窗口一静音 | 被取消 | 保持静音 |

fixed1 APK SHA-256：f0205e7354fb43ab47142dc0c6873967fa7a156ea7c7c1a1202de4b2b623fbdb。两个窗口的前后记录、APK 与测试 APK 均在 scratch/version-memory-comparison/window-state-baseline5 和 window-state-fixed1。

修复使用每窗 rememberSaveable 与小型 VideoWindowState，在保存时读取当前播放器状态，并在释放前脱离播放器。保存内容仅 URI、位置、播放意图、倍速及静音；无播放器或图像对象进入 Bundle。四项 JVM 测试通过，涵盖同 URI 两窗状态独立、当前值保存、释放引用、过期释放回调及缺失/无效快照。

测试工具失败保留：baseline1 注册的是压力测试 runner，无法启动恢复 runner；baseline2 的同步启动等待超时；baseline3/4 的记录证实 singleTop 复用了第一个任务，第二个任务未创建。改用已有隔离启动 Activity 后，baseline5 得到有效双窗复现。这些准备失败未记作产品通过或失败。

追加源记录版本变化测试（baseline6 / fixed2）：真实 VideoPlayer 保持同一 URI、同一 Activity，更新 sourceVersion，分别在前台以及后台切回时重建播放器。测试输入模拟 MediaEntry 的修改时间变化；未将此组件用例称为真实文件转移操作。

| 阶段 | baseline6 | fixed2 |
| --- | --- | --- |
| 两个实际任务重建 | 工具栏出现、视频播放、速度回到 1× | 工具栏隐藏、暂停、原位置/速度/静音保留 |
| 前台播放器替换 | 开始播放，900 ms 被改成 654 ms，0.5× 被改成 1×，静音取消 | 暂停在 900 ms，0.5×，静音 |
| 后台播放器替换后返回 | 开始播放，900 ms 被改成 802 ms，速度 1×，无静音 | 暂停在 900 ms，0.5×，静音 |

因此除 Activity 重建外，ViewerScreen 中以 sourceVersion 为键的 DisposableEffect 也可重建 VideoPlaybackSession 并触发原先的默认值。修复在相同 URI 的会话替换前保存当前小型状态，释放旧播放器后仍可恢复。窗口内部状态与全局的“记忆位置/倍速”设置独立；后者只影响重新打开文件的默认行为。

用户追加内存约束：只保存少量标量及 URI，播放器 reader 在 dispose 时解除；不保存帧、位图或播放器对象。八项相关 JVM 测试通过（窗口状态四项、OverflowMotion 四项）。完整 50 窗口回归等待本次弹窗检查后执行；不能用上一轮 348.9 MiB 数字代替新候选的实测。本轮未安装手机。

补充释放检查 `window-state-lifetime3` 通过：明确等待新组件播放器替代原页面播放器后再设置状态；前台、后台各替换一次，两个旧播放器会话回收后均不再存活（retiredSessions=2，retainedDisposedSessions=0）。两窗真实重建与两次组件替换的暂停/位置/速度/静音结果仍全部通过。产品 APK 未因这几次检查调整，SHA-256 为 ea5aab34ab9aec1638099d1baca54713fe0a6d23a9b0b13185874ebd712489e0。

相同最终测试 APK 回测原版 `window-state-baseline7`：两次 Activity 重建仍显露工具栏并开始播放；前台/后台组件替换后分别播放到 666 / 634 ms，0.5× 均回到 1×且静音取消。该版两个替换会话也都被回收，因而未将释放检查的早期误报归因于新版状态保存。

测试修正证据：fixed3/lifetime1/lifetime2 曾报告一个旧会话未回收。HPROF 识别出测试替换整页后保留的旧 VideoPipHost/无障碍缓存，以及 retired[1] 仅有弱引用、没有强入边的对象。测试原先在 GC 前对 WeakReference.get() 求值，可能使临时强引用留在 ART 栈寄存器；改为先无条件完成 GC 周期，再读弱引用。lifetime3 消除了误报，未为此修改产品释放逻辑。组件更新的最终有效结果采用 lifetime3，前面两窗 Activity 重建结果不受该准备时序影响。

依据：Android [Compose 状态保存](https://developer.android.com/develop/ui/compose/state-saving) 与 [状态机制](https://developer.android.com/develop/ui/compose/state)。

完整 50 窗口内存回归已结束：`fifty-patched-state-popup5` 与加入状态/动画前的 `fifty-patched-grid-final4` 图片、标签和操作负载一致。Java 峰值 348.91 → 330.18 MiB，返回后 Java 228.66 → 232.03 MiB、native 418.65 → 423.86 MiB、PSS 843.49 → 849.66 MiB。51 窗始终存活、0 low-mem；50 个详情的 84 条返回绘制无空图。详见 fifty-window-memory-20261107.md 与运行目录 memory-comparison.json。

## 实际文件转移与完整进程恢复补测

| 编号 | 操作和预期 | 验证层级 | 状态与证据 |
| --- | --- | --- | --- |
| WS-T1 | 两个视频窗口暂停，第三个主 grid 窗口前台；移动第一个正在查看的视频到另一相册，两个窗口原状态保持 | 真实 MainActivity、生产 MediaRepository.transferMedia、文件和 MediaStore/Room | 通过，window-state-transfer1 |
| WS-T2 | 将该视频移回原相册，再返回两个原窗口；文件内容和索引一致，窗口状态保持 | 同上 | 通过，window-state-transfer1 |
| WS-P1 | 结束已保存的后台应用进程，从最近任务恢复同一视频任务；隐藏工具栏及暂停/进度/速度/静音保留 | Android 整进程恢复及实际播放器回调/语义节点 | 通过，修复前 process-trace5 失败，修复后 process-stable6 通过 |
| WS-PHONE | 手机验证当前完整修复 | 实机 | 未测，当前仅模拟器连接；未安装新包、未开始实时监听 |
| WS-I-P1 | 真实图片详情缩放和平移后结束进程，恢复同一任务；渲染缩放、中心位置和隐藏工具栏保持 | 真实 MainActivity 触摸事件、原生渲染器数值、进程生命周期 | 通过，image-state-process-zoom1 |

WS-T1/T2 使用独立 UUID 目录和两个真实 MediaStore 视频，调用生产转移方法，未模拟结果，也未操作转移选择面板。移动和移回后，分别核对源文件消失、目标文件 SHA256、Room 路径和原 MediaStore URI 对应路径。四条窗口记录均为同一原 Activity：任务 2056 暂停于 666 ms、0.75×、静音；任务 2058 暂停于 1333 ms、1.25×、未静音；工具栏全程保持隐藏。第二个视频未被移动。产品 APK 与 window-state-lifetime3 / fifty-patched-state-popup5 相同，未为此改变产品源码。

过程依据：[Android 媒体存储](https://developer.android.com/training/data-storage/shared/media) 描述通过 ContentResolver 更新媒体记录；[Compose 状态保存](https://developer.android.com/develop/ui/compose/state-saving) 区分 Activity 与进程重建。WS-P1 追加完整进程检查，补齐之前只有 Activity.recreate 与组件替换的覆盖。

进程补测过程记录：process1 的语义采样未过滤透明/未放置节点，准备阶段一直认为返回按钮存在，超时；随后补上布局放置、范围和透明度检查。process2 的任务 2062 在 PID 27898 → 28063 后保存状态为 true，666 ms、0.75×、静音、暂停均恢复，但早期采样报告工具栏存在。process3 同样出现早期返回节点，约数秒后采样及系统 XML 均无该按钮。正在追加保存 Bundle、原生触摸和实际 Viewer 帧状态记录，区分状态重置与隐藏节点的采样问题；没有据此修改产品代码或登记整体通过。

追加诊断 `window-state-process-trace5` 确认：任务 2065，PID 29423 → 29581，恢复后实际 `UI_AUTO_HIDE_SCHEDULE showUI=true`，期间没有原生触摸事件。视频的暂停、666 ms、0.75×和静音恢复，工具栏先显示再在约三秒后自动隐藏。因此属于完整进程恢复时工具栏状态丢失，不是透明节点误报。trace4 构建时选错了 trace 包名，不作产品证据；该次新建的模拟器 trace 包已清理，宿主脚本增加包名检查。

定位到 `MainScaffold` 的 Compose `key(retainedViewer)` 和 `key(screenIndex, screen)`：Screen 的数据类哈希包含枚举、单例对象身份，跨进程不固定；Compose 根据该哈希生成保存状态键。候选 `ScreenStateKey.kt` 改用路由名称、枚举名称和既有参数的值生成稳定键，每个路由仅计算一次。转移页沿用原 ID 数组引用，不复制选择列表；该修改不持有图片或播放器。三处调用点已同步隔离构建，等待同一失败用例复测。

`window-state-process-stable6` 完成同一用例：任务 2066，PID 29914 → 30073，后台进程消失后从系统最近任务恢复，saved=true。18 次恢复采样全部保持工具栏隐藏、暂停于 666 ms、0.75×和静音；首次真实 `UI_AUTO_HIDE_SCHEDULE` 已为 showUI=false，原生节点也没有返回按钮。APK SHA-256 145e953e4f448bfc35fb2f5a163ea71bc9608dcc056a4342ca446ad1bd11c90d。ScreenStateKey 三个 JVM 用例通过，46 个当前修复产品文件与隔离副本一致（stable-state-changes-verification.json）。正在补受影响的 grid 进程恢复与关闭诊断后的 50 窗口内存回归。

受影响路由补测：`recovery-process-stable-albums7` 以八个相册夹具恢复主相册 grid，38 条实际绘制、第一帧九个渲染器全部就绪、零初始空帧/不完整帧、布局始终同一份；`recovery-process-stable-label7` 从标签相册打开详情后结束进程，恢复同一任务，50 条实际绘制、零初始空帧/不完整帧。前一轮 albums6 错误组合了只支持单图的标签夹具和八相册参数，在建立页面前被测试校验拒绝；已为宿主脚本加入提前检查，未据此改产品。

固定状态键后的静默包通过 `fifty-patched-state-stable6`：51 窗全程存活，0 low-mem / 意外销毁，返回 50 个详情的 85 条绘制无空图。与状态/动画前 final4 比较，Java 峰值 348.91 → 351.36 MiB，返回后 PSS 843.49 → 852.42 MiB（+1.06%）；记录了宿主每两秒采样峰值 1063.80 → 1092.00 MiB。详见 fifty-window-memory-20261107.md；未复用旧版本的内存测量。

正式包已生成：`build_apks/flicker-state-release-20261108/Pixel-Gallery-4.3.6.4-arm64-v8a-state-fix-release.apk`，27,975,098 字节，SHA-256 `eb9db26d06bcd7b034d1d54e2d723fd5b724d2cde3d81046b00bed1ae8153852`。实际 APK 校验为仅 arm64-v8a、release 非 debuggable、两项诊断关闭、签名与手机此前正式包一致；版本 4081 / 4.3.6.4-auto-multitask。源文件清单与构建前哈希一致。未安装手机，安装确认已发出，实时监听仍等待用户明确指令。

后续完成审查发现：既有 ImageZoomMemoryChecks 覆盖真实渲染器及保存仓库的重新打开，但不覆盖真实整进程恢复。新增 WS-I-P1，使用已有独立 2048×1536 JPEG 夹具，真实 MainActivity 中分阶段派发双指缩放和单指平移；恢复后不调用任何变换设定，只读取原生 ImageView/SSIV 可见缩放和中心位置。全部采集只在 emulator-5562，手机仍未连接，产品源文件与已交付 release 未修改。

`image-state-process-zoom1` 通过：任务 2124 保持，PID 1757 → 2025，恢复 saved=true；旧进程确已消失。触摸操作后实际瓦片渲染器缩放 2.5000079、源图中心（909.3122，768），工具栏隐藏。恢复后 21 条读数（含 9 次实际 draw）均保持这些数值，缩放和中心坐标最大差值均为 0，工具栏持续隐藏。恢复过程没有重新施加缩放或平移。测试 APK d1f7fc9db5274a5bbe1f3459cb383990f254ef07f51dfc886645e3c63457f5c6；与已交付包相同的 46 个本轮修复产品源文件、正式构建清单的全部 519 个文件及正式 APK SHA-256 已重新核对一致。
