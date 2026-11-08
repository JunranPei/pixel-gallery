# 群组相关内存开销回归检查

## 范围与证据

用户补充触发操作：相册位置移动后移回；开启 Quick Sort 后选择几个目标相册。重复尝试并非每次出现。真机监听已确认一次 low-mem Activity 回收后的重建空档，尚未捕获该回收发生前的分配峰值。

与引入 inline groups 前的 740ca312 对比，确认以下新增路径；`git log -S` 将完整群组候选列表、标签手动封面全库 URI 表的引入定位到 4985a319（4.3.6.0）：

- MainScaffold 为每个窗口生成完整群组封面候选表，遍历媒体并临时按路径分组，即使未打开封面选择器。
- LabelsViewModel 新增全库 URI 映射和全部标签成员 Set，用于手动封面；未设置手动封面时也构建。
- applyAlbumCovers 只要 covers Map 非空，就为全库建 URI 表，包括 covers 里只有别的集合设置的情况。
- 共享 DataStore 的群组、Quick Sort JSON 投影未在解析前区分相应键是否变化。

这些是确定存在的额外分配；本轮不把它们直接等同于真机系统回收的全部原因。

## 修改

- 群组完整候选列表仅为正在操作的集合生成。复查时发现旧版手动群组封面仍会触发完整候选计算，已改成只核验指定 URI 的群组归属。普通浏览不再生成该列表及其过滤副本；保留标签归属、可见相册边界和旧封面行为。
- 手动封面索引只保留指定 URI；没有相关封面时不遍历图库。
- 标签手动封面的归属核验只针对命中的图片，不再先建全体成员 Set。
- 群组、Quick Sort 各自按原始设置键去重后解析，阻止无关写入触发解析与下游通知。

## 验证矩阵

| 用例 | 级别 | 状态 |
| --- | --- | --- |
| 未请求群组封面时不得读取图片列表；其他类型封面不得触发相册索引 | JVM 产品函数测试 | 通过 |
| 普通群组排除隐藏/未展示相册，标签群组与默认标签组归属正确 | JVM 产品函数测试 | 通过 |
| 删除、回收站、跨相册图片不能成为手动封面 | JVM 回归测试 | 通过 |
| 131014 项、4 次调用，对比旧算法与修改后累计分配 | JVM 线程分配计数 | 通过，见下表 |
| 7 次 Quick Sort 目标写入不通知群组；10 次群组写入不通知 Quick Sort | 模拟器真实 DataStore | 旧包失败，修改后通过 |
| 实际群组、封面、隐藏及排除项修改仍准确通知 | 模拟器真实 DataStore | 通过 |
| 手机多窗口连续操作不再出现 low-mem 回收与显示空档 | 真机监听 | 未测，未开始新一轮监听 |

JVM 对比是算法级累计临时分配，不是 Android 整进程常驻量或真机峰值。模拟器设置测试使用测试包，保存并恢复原设置。

## 结果

| JVM 工作负载 | 旧算法分配字节 | 修改后分配字节 |
| --- | ---: | ---: |
| 131014 条记录、8 个普通群组、4 份候选准备；无封面操作 | 43691960 | 48 |
| 同一图库、4 份手动封面索引、每份 2 个目标 URI | 32842192 | 1288 |

旧候选算法保留其全库分组及各普通群组合并操作，未额外包含标签筛选等工作。因此本表不是整个 MainScaffold 的分配总量。

- 20 项 JVM 测试通过：AlbumGroupingTest 13、CollectionCoverTest 3、CollectionCoverMemoryTest 4。结果位于 app/build/test-results/testDebugUnitTest；构建日志 scratch/cover-memory-verify.log。
- 首次编译时，测试编译环境没有 java.management API；改用反射读取宿主 JVM 的公开线程分配计数后运行成功。这是测试工具修正，不是产品缺陷。
- 修改前：在模拟器原有 4.3.6.4 测试包上只安装新测试驱动。7 次目标设置写入使群组订阅计数从 1 变成 8，断言按预期失败。日志 scratch/cover-settings-before.log。
- 修改后：安装本次 debug 测试包后同一测试通过；7 次目标写入不通知群组、封面及可见性；10 次群组实际写入各通知一次且不通知 Quick Sort；真实封面、隐藏、排除设置的新增和删除准确通知。日志 scratch/cover-settings-after.log。
- 仅操作 emulator-5562 的 com.pixel.gallery.multitask.test 测试包，未向手机安装或开始监听。未提交、推送、提升版本号或生成交付用 release 包。

## 平台回收路径

Android 16 QPR2 ActivityThread 的 GC watcher 在 mSomeActivitiesChanged 且应用堆 used > 3/4 max 时调用 releaseSomeActivities。该 AOSP 路径说明后台 Activity 回收可由应用自身堆占用触发，并非只有整机可用内存不足一种来源。手机为厂商系统，本轮没有记录回收前的堆占用及此调用，不能据此声称该阈值已在手机上被测得。

## 追加复查：累计分配是否足以解释 low-mem

已运行模拟器 ART 隔离测量：共享 131014 个合成媒体对象，按 4 / 12 份保留封面候选结构；每组旧/新算法交替执行三轮。后台线程每 1 ms 采样堆占用，计算期间允许自然 GC，结束后确认显式请求的 GC 实际运行，再测持有量。没有打开真实详情 Activity，也没有加载缩略图或大图。

| 份数、算法 | 操作前堆 MiB（中位） | 采样峰值 MiB（中位） | GC 后堆 MiB（中位） | GC 后新增 MiB（中位） |
| --- | ---: | ---: | ---: | ---: |
| 4、旧 | 25.47 | 48.45 | 30.35 | 4.87 |
| 4、新 | 25.49 | 25.55 | 25.49 | 0.00 |
| 12、旧 | 25.49 | 57.93 | 40.08 | 14.59 |
| 12、新 | 25.49 | 25.56 | 25.49 | 0.00 |

新算法 GC 后新增三轮中只有一轮为 4096 字节，其余为零，受 Runtime 堆统计精度影响；不是声称代码不分配对象。模拟器堆上限 576 MiB。这两段算法在隔离运行时远未到应用堆回收阈值。不能用先前累计约 73 MiB 的 JVM 分配量，推断真实设备同时增加了 73 MiB 常驻内存，更不能宣布已找到全部闪烁根因。也不能用本次隔离结果排除其在真实应用接近阈值时成为触发因素。

证据：scratch/collection-heap-device.log、scratch/collection-heap-results.json；测试驱动 CollectionHeapChecks.kt。运行在 emulator-5562 测试包，未触碰手机状态。

追加核对：MediaBrowseIndex、ViewerSessionPhotos、PixelGalleryApp 相比 740ca312 无差异；群组背景路径缓存按现存群组清理，实际几何仅取可见 grid 单元，未发现全图库尺寸的离屏 bitmap 分配。后续防闪烁修改在 MediaRepository 新增一个 lastNonEmptyViewerMediaSnapshot 强引用，但仅凭代码不能量化其实际保留的旧图库代数；整体多窗口常驻对象与回收前峰值仍需证据。

旧版群组照片封面稀疏核验补测后，21 项 JVM 测试通过（13 + 5 + 3），日志 scratch/collection-memory-recheck.log。构建曾遇 Kotlin daemon 退出，Gradle 回退编译后成功；不是产品测试失败。

## 参考

- https://developer.android.com/topic/performance/memory — 内存分配、生命周期与测量入口。
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/distinct-until-changed.html — 连续相等值过滤；过滤位置必须在昂贵的解析之前。
- https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java — GC watcher 对应用堆占用的检查与后台 Activity 释放请求。
