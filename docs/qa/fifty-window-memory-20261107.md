# 五十窗口内存验证（进行中）

用户最终要求：50 个窗口，不要求每个都是超大图。沿用 131,014 条图库记录；50 个不同文件、不同 URI 的真实详情窗口，其中 4 个 JPEG 超过 100 MB，另开一个主 grid。设备 emulator-5562，应用堆上限 576 MiB；只使用隔离包 memory4332 / memorypatched。手机实时采集保持停止。

## 已完成的首轮

| 运行 | 结果 | Java 峰值 | 实际保留情况 |
|---|---|---:|---|
| fifty-4332-mixed1 | 第 39 个详情创建期间出现 low-mem；逐窗返回失败 | 495.3 MiB | 第 36 个起同时出现最近任务清理，结束打开时仅剩 14 个详情 |
| fifty-patched-mixed1 | 打开及 50 次真实群组开合未出现 low-mem；图库一条记录改写/还原期间回收 | 494.5 MiB（改写阶段） | 第 36 个起最近任务清理，grid 打开时共存 35 个主 Activity；更新期间发生 34 个 low-mem 任务销毁 |

上述两轮均不能记作“同时保留 50 个窗口通过”。`window-retention.json` 按 Activity 创建身份和销毁时间计算每个阶段仍然存活的数量；同时保留系统销毁原因。

## 测试入口修正

原驱动每次打开窗口先回桌面。Android 的 `RecentTasks.onActivityIdle` 在 Home 恢复时调用 `removeUnreachableHiddenTasks`，隐藏任务超过阈值会被以 `remove-hidden-task` 原因删除。来源：[Android 16 QPR2 RecentTasks.java](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/RecentTasks.java)。这与 `low-mem` 分开统计。

`fifty-4332-direct1` 直接从 MainActivity 发起下一窗口，被 singleTop 复用（系统 START result=3），停在第一个详情；操作者终止，不能记作产品崩溃或内存测试结果。

隔离 debug 包追加空白 `MemoryLaunchAnchorActivity`，每次先把同一个辅助任务移到前台，再按原 ACTION_VIEW 打开 MainActivity。此辅助 Activity 不修改产品窗口、不修改内存阈值，也不触发 Home 清理。两个版本使用相同入口；最终仍需核对是否实际保留全部 50 个详情并逐一返回原对象。源码只在 scratch 的隔离树，正式 app 无该入口。

## 首轮后的产品候选

- 标签成员按图片的索引移到 SharedLabelCollections，所有窗口共用；详情需要的标签名称按当前图片解析，移除 MainScaffold 每窗口两份全库分组映射。
- 新增 MediaPhotoListCache，使用图库对象身份、隐藏目录、排序方式和隐藏浏览模式作为键。弱引用最多保留 4 个槽，不强制延长旧图库或关闭窗口的寿命；同一数据代的筛选/排序结果在窗口之间复用。
- 原详情空结果缓冲和恢复逻辑不变；grid 的原排序比较器不变；详情 DATE_DESC 仍沿用仓库顺序。
- 新增 50 个并发调用共享同一列表、所有排序方式等价、隐藏状态及元数据变更测试；标签共享测试扩到 50 个订阅者。候选正在编译验证，尚未安装手机。

运行原始记录位于 `scratch/version-memory-comparison/<run>/`；包括 APK SHA256、图片大小/尺寸/SHA256、每 10 ms Java 峰值、每阶段 native/PSS、每 2 秒进程采样、系统事件、Activity 身份与真实绘制监听。没有使用截图识别。

## 使用辅助入口的结果

- `fifty-4332-anchor1`：无 remove-hidden-task。36 个详情均存活，之后打开约第 39 个时发生 36 个 low-mem；Java 峰值 486.4 MiB。开完 50 个时仅剩 14 个存活，返回原窗口失败。
- `fifty-patched-cache1`：确实保留 50 个详情，加 grid 后 51 个 MainActivity 共存；51 个浏览索引为同一对象。50 次群组开合和 8 次无变化刷新均未回收。一条记录改写/还原期间 50 个详情被 low-mem 销毁，Java 峰值 489.4 MiB，返回原窗口失败。开完 50 个时 Java 356.7 MiB；群组开合后 native 411.5 MiB、PSS 997.6 MiB。
- `fifty-patched-dump1`：同一 app APK（已核对 SHA256），50 个详情均曾存活。尚未修改图库，Quick Sort 目标设置期间便发生 50 个 low-mem。开完 50 个时 Java 418.3 MiB；目标修改阶段峰值 446.3 MiB。未到末尾 HPROF 步骤，未获得有效堆文件。证明在该负载下，回收触发点有波动，不仅限于图库改写。

后续把诊断 HPROF 移到打开全部窗口之后、操作之前；该诊断运行会主动 GC 并提前结束，不作为正常操作通过结果。

## 分页读取候选

原 allEntries 在完整读出 131,014 个新对象之后，才执行 reuseUnchangedMediaEntries。即使最终保留旧对象，读取期间仍暂存一整份新对象及字符串。

新增 `readMediaSnapshot`：同一 Room 读事务内每页 256 条，按 ID 对齐旧记录，所有字段相等即立即复用；旧记录用排序指针数组和顺序游标查找，避免每条记录一个 Long/HashMap 节点。读完后保持原 bestTimestamp/contentId 降序，完全相同时返回原列表。新增、删除、修改和还原仍由数据库内容决定。正在编译及运行单元测试，尚未测得运行时峰值改善。

### 分页候选实测与堆诊断（11 月 8 日）

`fifty-patched-paged1` 完成 50 详情 + grid、50 次群组开合、8 次无变化刷新，前述阶段 51 个 MainActivity 均存活。一条图库记录修改/还原时再次出现 50 个 low-mem（任务 1662–1711），返回原任务失败。该阶段 Java 峰值 440.1 MiB；此前同驱动 `fifty-patched-cache1` 为 489.4 MiB。单轮比较显示峰值下降，但本轮仍失败。

`fifty-patched-open-dump2` 是单独堆诊断：50 个详情打开后主动 GC 三次并导出 HPROF，随后退出，没有执行后续压力步骤。GC 后 Java 414.3 MiB、native 404.0 MiB、PSS 1019.9 MiB；不可当作完整压力通过。

HPROF `windows.counts.json`：MediaEntry 131,064（包含 50 个外部详情）、BrowseRecord 131,014、GridItem.Photo 131,014、PhotosViewModel 50、MediaBrowseIndex 2（包括空索引），表明本轮媒体记录与浏览索引没有按 50 窗重复。另一方面 java.lang.Long 达 **6,578,354 个**，只计字段载荷为 105,253,664 字节，不含对象头和引用数组。

代码定位到 HomeScreenComponents 的日期标题：每个保留的照片 grid 会为同日全部照片创建 `List<Long>`，随后每次组合又 filterNot 成另一列表。测试图库同日 131,014 张，50 个窗口即约 655 万个编号对象；旧 4.3.3.2 也有前一份列表。`git blame` 显示编号列表来自 fb684fdfc（2026-04-26），reserved 过滤来自 93d4e6072（2026-10-11）。这处旧有开销与近期新增开销分别记账。

候选 `PhotoGridHeaderRange` 只保留共享 grid 的起止索引，进入选择状态时才检查成员，点击日期全选时才建立实际选中集合。Quick Sort 的编号索引也改为 `PhotoGridItemIds` 按位置读取。日期边界、占用项排除、全选/取消及滑动连选行为保持；正在编译及测量。

该候选编译完成，10 项 JVM 测试全部通过（日期/连选 3、分页读 3、共享照片列表 3、共享标签 1）。日期标题用 131,014 条记录重复 50 次，累计分配 362,125,736 → 2,168 字节；此为 JVM 分配量，不是 Android 持有堆。22 个产品源文件与隔离构建树的 SHA256 完全一致，见 `fifty-header-source-verification.json`。首轮 Android `fifty-patched-header1` 正在执行，APK 与 instrumentation 均另存到该运行目录以固定身份。

追加引用分析 `windows.header-owners.json`：`HomeScreenComponentsKt$PhotosScreen$12$6$1$4$$ExternalSyntheticLambda0.f$4` 的 50 个实例分别引用 50 个不同 ArrayList，每个底层数组容量 160,065；相邻的 grid `$items` / `$displayedItems` 等字段则是 50 个回调共用同一列表。与日期全选回调捕获 `selectableHeaderPhotos` 的代码一致。

`fifty-patched-header1` 已结束：50 个详情加 grid 全部存活，打开 50 个时 Java 197.2 MiB，grid 199.7 MiB；Quick Sort 目标操作峰值 227.7 MiB。无 low-mem、无隐藏任务删除。此轮随后测试驱动报“Missing Albums tab after Quick Sort”，没有执行群组/图库更新/逐窗返回，不能记作完整通过。

原驱动固定返回两次、各等待 700 ms。改为读取无障碍节点，只有半页面标题仍在时才继续返回，等待实际关闭后再点 Albums；保存关闭前后文本节点，未用截图。`header-driver-crud2` 在 2 个详情下验证了完整驱动路径：50 次真实群组开合、8 次无变化发布、元数据改写/还原、追加一条记录再移除、删除既有记录再恢复、逐窗返回；无 error/low-mem/意外销毁，峰值 131.7 MiB。产品 APK SHA256 与 header1 完全相同（f40104a627b742385fa1cd2756e3d9dc6b058c51a9412523f8eb9e281c18e655）。`fifty-patched-header2` 正用该 APK 和修正驱动执行 50 窗口及新增/删除步骤。

`fifty-patched-header2` 完整结束：error=null、low-mem=0、意外销毁=0；每个操作阶段到逐窗返回结束均保留 51 个 MainActivity。50 个详情都切回原 Activity 并通过焦点/图片就绪检查；51 个 VM 的浏览索引仍为同一身份 41066764。全程 Java 峰值 **272.6 MiB**（元数据改写/还原），返回结束 Java **200.7 MiB**、native **400.6 MiB**、PSS **817.1 MiB**。同一图库更新阶段此前 paged1 为 440.1 MiB 并回收 50 个详情，本轮未回收。

绘制证据范围：header2 旧监听仅在 displayed 布尔值变化时记录，共 54 个事件，逐窗返回没有新变化事件；其逐窗就绪检查和原 Activity 保留已通过，但不能把“没有记录变化”写成逐窗首帧验证。因此后续监听加上 phase 变化记录，检查图片 View 的实际屏内可见区域（排除屏外预加载 View），切回后请求一次真实 draw 并要求每个任务都有正向绘制记录。没有修改产品渲染逻辑。

`fifty-patched-labels-draw3` 在相同 131,014 条图库记录上增加 **131,014 条标签成员关系**，继续 50 窗口和完整更新/返回步骤；只在全部操作和返回后主动 GC、导出 HPROF。正常压力阶段不做额外 GC。该轮正在执行。

该轮已完整通过：**51 个 MainActivity 始终保留，0 low-mem、0 隐藏任务删除、0 意外销毁**。50 个原详情逐一切回，实际屏内、获得焦点的图片 draw 记录 **95 条、覆盖 50 个任务、空图片 0 条**。图库元数据修改/还原峰值 347.9 MiB，新增记录再移除峰值 **372.2 MiB**（全程最高）；逐窗返回结束 Java 231.4 MiB。完成全部操作后 GC 诊断为 Java **229.0 MiB**、native **418.0 MiB**、PSS **855.3 MiB**。

最终堆 `fifty-patched-labels-draw3/windows.hprof` 为 358,543,115 字节。即使新增 131,014 条标签关系，Long 也从旧诊断 **6,578,354 → 287,051**，载荷 105,253,664 → 4,592,816 字节；Object[] 载荷 91,239,836 → 32,377,768 字节。不同负载/阶段的两份堆不作等条件总内存比较，只用于验证旧编号列表不再按窗口数保留。MediaEntry 131,118、BrowseRecord 131,014、MediaBrowseIndex 2，仍无 50 份全库媒体记录或浏览索引。

当前明确结果：同一产品候选已通过一轮无标签成员、另一轮 131,014 标签成员的 50 详情 + grid 完整压力步骤；未进行手机实时采集，手机已安装包仍是前一轮修复，尚不含本页新增候选。主 grid 及内部来源整进程恢复、手机表现和厂商 4010 的剩余事项继续列在持续目标中。

### 系统后台窗口释放阈值

读取 Android 16 QPR2 官方源码：ActivityThread 的 GC watcher 在 `mSomeActivitiesChanged` 且 Java 已用堆超过 maxMemory 的 3/4 时调用 releaseSomeActivities；ActivityTaskManagerService 传入原因 low-mem，WindowProcessController 选择已停止、不可见、有保存状态且可销毁的 Activity 执行销毁。576 MiB 堆对应 432 MiB，与这些失败轮次的内存及系统原因吻合；未记录 GC watcher 的实际调用栈。

来源：[ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java)、[ActivityTaskManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)、[WindowProcessController](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/WindowProcessController.java)。没有修改系统阈值；厂商 4010 进程结束另行保留，不能据此归因。

最终 grid 加载状态与 sessionSlots 清理后的静默构建 `fifty-patched-grid-final4` 通过。产品 APK SHA-256：8b52f1b876ae95843a6edd2bc3d561b5586463f6adc3871607c8bbb116eae69a；已随运行归档 app-tested.apk 和 test-tested.apk。131,014 条图库记录及同数标签关系、50 个详情（四个大图）+ grid，全套 Quick Sort、50 次群组开合、8 次刷新、元数据修改/还原、记录增删/恢复及逐窗返回完成。51 个 MainActivity 全程存活，0 low-mem、0 隐藏任务清理、0 意外销毁；Java 峰值 348.9 MiB，全部返回后 228.7 MiB。50 个原详情的返回阶段获得 85 条实际屏内图片绘制，空图片 0。window-retention.json、result.json、summary.json 及 events.txt 保留原始证据。此次尚不包含后续工具栏和视频状态修改。

### 状态恢复与弹窗动画加入后的同负载回归

`fifty-patched-state-popup5` 完成上述相同负载。产品 APK 为 ea5aab34ab9aec1638099d1baca54713fe0a6d23a9b0b13185874ebd712489e0，包含每窗工具栏/视频状态保存及弹出层非线性动画；诊断开关关闭。对照脚本检查两轮 50 份图片的字节数/尺寸/大图标记一致，均有 131,014 条图库记录及标签关系，并逐项核对 Quick Sort、50 次群组开合、刷新、增删改恢复及返回阶段。

| 指标（MiB） | 加入前 final4 | 加入后 popup5 | 差值 |
| --- | ---: | ---: | ---: |
| Java 采样峰值 | 348.91 | 330.18 | -18.73 |
| 逐窗返回后 Java | 228.66 | 232.03 | +3.37 |
| 逐窗返回后 native | 418.65 | 423.86 | +5.21 |
| 逐窗返回后总 PSS | 843.49 | 849.66 | +6.17 |

两轮所有操作阶段均保留 51 个 MainActivity，0 low-mem、0 隐藏任务清理、0 意外销毁。新轮逐窗返回获得 84 条屏内图片绘制，覆盖全部 50 个详情，空图片 0。最后 PSS 增幅约 0.73%；本轮没有出现原有数百 MiB 的峰值回退。单轮差值按实记录，未把不同时间的 GC/分配器波动归因于某一项代码。

比较产物：scratch/version-memory-comparison/fifty-patched-state-popup5/memory-comparison.json。原 APK、运行结果、事件日志、每阶段内存及任务存活量均随运行目录保留。本轮未安装手机，未开启手机实时监听。

### 固定跨进程页面状态键后的最终回归

`fifty-patched-state-stable6` 使用相同 50 详情（四个超过 100 MB 的大图）+ 主 grid、131,014 条媒体及标签关系和完整操作流程。APK SHA-256 b0c54200517b66b8467d9ceb3ea5acd378d55e95066065ad1d07a69e4b1a9b74；两项诊断均 false，产物、BuildConfig 和 46 个当前修复文件的源哈希随运行保存。

| 指标（MiB） | 状态/动画前 final4 | 最终 stable6 | 差值 |
| --- | ---: | ---: | ---: |
| Java 各阶段采样峰值的最大值 | 348.91 | 351.36 | +2.45 |
| 逐窗返回后 Java | 228.66 | 233.73 | +5.07 |
| 逐窗返回后 native | 418.65 | 425.17 | +6.52 |
| 逐窗返回后总 PSS | 843.49 | 852.42 | +8.93 |
| 宿主每两秒采样 PSS 最大值 | 1063.80 | 1092.00 | +28.20 |

全程 Java 最高阶段是移除新增图库记录后，351.3565 MiB。过程进度里曾将“返回首窗”阶段的 304.49 MiB 写作本轮峰值，现按全部阶段更正。最后 PSS 相对 final4 增加约 1.06%，宿主采样 PSS 最大值增加约 2.65%；单轮数值均如实记录，没有把分配器/GC 波动归因于新状态键。

所有操作阶段实际存活数等于已打开数，达到 51 后始终保持 51；0 low-mem、0 隐藏任务移除、0 意外销毁。返回阶段 85 条实际屏内绘制覆盖全部 50 个详情，空图片 0。测试结束主动 finish 的 51 条系统销毁记录属于清理，均在操作阶段之后。比较脚本核验同负载并生成 memory-comparison.json，window-retention.json 保留逐阶段存活和完整销毁原因。没有运行其他编译任务干扰本轮压力采样。
