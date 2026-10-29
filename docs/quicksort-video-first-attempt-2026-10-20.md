# Quick Sort 视频第一次失败调查

用户复测：视频转移可能第一次失败、第二次成功；已明确与转移数量无关。本轮按单个真实视频首试处理。此前普通 Copy/Move 路径的通过结果不能替代此路径。

## 依据与范围

- [Android MediaColumns](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns)：DATE_TAKEN 来自视频 METADATA_KEY_DATE，DATE_MODIFIED 来自文件修改时间；索引值与文件内时间应分别核实。
- [Android 媒体扫描实现](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/master/src/com/android/providers/media/scan/ModernMediaScanner.java)：视频扫描读取真实媒体元数据，不能用随机字节加视频扩展名代替。
- 现有 VideoBatchInstrumentation 的文件不是可播放 MP4，只能检查二进制移动；不将它作为本问题的视频验收证据。新增用例使用 FFmpeg 生成并实际解码验证的 H.264/H.265 MP4 与 MOV。
- 只修改 UUID 目录下新建样本和隔离数据库/设置。保留原 318 个样本。

| 编号 | 情况 | 验证与状态 |
| --- | --- | --- |
| V01 | 单个已完成扫描的真实视频第一次转移 | 通过：真实文件名、SHA-256、毫秒修改时间、拍摄时间与收藏关联 |
| V02 | 刚发布、未额外等待扫描的视频第一次转移 | 通过：发布后不额外等扫描，单个视频首试成功 |
| V03 | 移动前媒体元数据刷新 | 通过：计划阶段显式刷新源索引，首试成功；并未将此前时间字段假设认定为根因 |
| V04 | 单个视频走持久化 Quick Sort 队列 | 通过：真实持久队列一次仅提交一个视频，五种输入均首试成功 |
| V05 | 冷进程首试重复验证 | 通过：5 个独立应用进程，共 25 个单视频队列用例全部首试成功 |
| V06 | 失败安全与原有回归 | 通过：MOV 保留两份及撤销保持内容/原名/时间/关联；目标损坏时拒绝删源，已有安全与标签迁移回归通过 |

用户设备的首次失败提示尚待补充；在没有证据时不认定具体根因。

用户后续补充：目前暂时无法复现，最近几次转移都成功。本轮继续完成已准备的单视频验证；未捕获失败时保留“暂未复现”，不据此宣称修复，也不盲目增加重试或修改生产逻辑。

## 已捕获的独立缺陷

- 五个有效视频各跑四种首试状态，19 组通过，单个 MOV 的持久队列首试失败：`scratch/quicksort-video-first-valid-baseline-device-result.log`。其原始媒体库 MIME 实际为 `video/mp4`，没有使用伪造 MIME；失败后内容完整，但回滚文件变为 `Source/h264-date.mov.mp4`。
- Android 会按媒体库 MIME 规范化文件名，即使仅更新 RELATIVE_PATH 也会把 `.mov` 补为 `.mov.mp4`。独立系统操作已验证：`scratch/quicksort-video-provider-relative-only-probe.json`；[AOSP FileUtils 规则](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/os/FileUtils.java)。
- Quick Sort 正常队列没有冲突策略时要求目标名完全一致，因此报错；回滚时同样的规范化又改变了源文件名。第一次失败后刷新得到已规范化的名称，后续操作可能成功。这一分支已复现，但不能认定用户那次一定是同一原因。
- 修复范围：发现文件后缀与媒体库 MIME 的命名规则不一致时，复用现有完整校验的文件转移路径，保留原文件名；兼容的普通 MP4 继续使用原路径。校验、同名防覆盖、标签迁移及时间保留要求不降低。22 个场景及 5 轮冷进程复测通过。
- 测试工具首次仅检查原名和目标名，误把系统改名后的回滚文件漏算为“没有完整文件”；已改为检查该用例专属目录内所有实际文件，并在修复前完整复核，原内容仍在。

## 最终验证与范围

- `scratch/quicksort-video-first-fixed-device-result.log`：22 个真实视频场景通过；`scratch/quicksort-video-first-cold-results.json` 和五份 cold 日志：5 个独立应用进程，各跑 5 个单视频队列任务，全部首次成功。每次使用新的 UUID 文件，不把第二次成功算首次通过。
- `scratch/quicksort-video-first-fix-build.log`：完整 Debug、AndroidTest、JVM 构建成功；209 项单测失败/错误/跳过均为 0。原 Quick Sort 安全/撤销回归与标签迁移回归通过。
- 最新 APK 已安装，设备与构建 SHA-256 一致，详见 `scratch/quicksort-video-first-final-verification.json`。此前复制/移动报告中的安装校验是其验收阶段原始记录，当前版本以此 JSON 为准。
- 修改仅限 Quick Sort 的命名兼容分支；复用已有校验复制路径，没有放宽内容、时间或防覆盖校验。此分支要复制并校验字节，大文件可能比同盘原地移动慢；Android 可能分配新的入库时间，文件修改时间和拍摄时间已验证保持。
- 平台为当前 API 36 模拟器。未覆盖用户手机系统、所有视频容器与编码、GB 级文件、实际 SD 卡/跨卷及异常断电。用户原来那次失败没有提示或日志，是否与本次 MOV 分支同因仍未确认；不能宣称所有偶发首试失败均已解决。
