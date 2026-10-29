# 文件复制与转移补测

使用 functional-edge-testing skill：先检索、审查现有覆盖，再对隔离文件和数据库执行真实操作。沿用现有界面与冲突策略。

## 依据与现有覆盖

- [Android 共享媒体](https://developer.android.com/training/data-storage/shared/media)：媒体权限、挂起发布、路径/名称更新及索引同步。
- [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)：树 URI、创建/重命名/删除及提供器错误。
- [Kotlin 取消](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html)：阻塞文件操作需要主动检查取消，异常处理不能吞掉取消。
- 现有 MediaTransferTest 覆盖同目录判断、命名、搜索和恢复阶段决策；TransferFailureTouchChecks 使用模拟结果检查提示，不能证明真实复制/移动完成。QuickSortSafetyInstrumentation 默认套件主要覆盖 Quick Sort 安全移动与撤销，不能替代普通复制/移动路径。

## 矩阵

| 编号 | 操作/故障 | 预期结果 | 状态与证据 |
| --- | --- | --- | --- |
| T01 | 真实 COPY 多格式、较大文件、中文/多点文件名 | 源保留，目标字节/时间正确，索引可用，源标签保留 | 通过：32 个真实文件/隔离数据库用例组 |
| T02 | 真实 MOVE 多格式与标签 | 源移除，目标字节/时间正确，标签收藏跟随 | 通过：32 个真实文件/隔离数据库用例组 |
| T03 | COPY/MOVE × Keep both/Skip/Replace | 各策略计数正确，不误覆盖，Replace 结果正确 | 通过：32 个真实文件/隔离数据库用例组 |
| T04 | 同目录及路径别名 | 全策略跳过，内容和文件数不变 | 通过：32 个真实文件/隔离数据库用例组 |
| T05 | 不同目录同名源、已有编号占位 | 生成不冲突名称，各文件内容正确 | 通过：32 个真实文件/隔离数据库用例组 |
| T06 | 空批、重复项、源缺失及部分失败 | 有效项可完成，逐项计数与进度准确，无误删 | 通过：32 个真实文件/隔离数据库用例组 |
| T07 | 目标不可用、缓存路径过期、零字节、同名目录 | 失败安全，原文件与原目标不受损 | 通过：32 个真实文件/隔离数据库用例组 |
| T08 | 操作开始前及项间取消 | 未开始项保持不变，已完成项按提交边界保留 | 通过：32 个真实文件/隔离数据库用例组 |
| T09 | SAF 成功、同名及注入写入/重开/重命名失败 | 目标验证后才删源；失败清理自有目标，源保持 | 通过：32 个真实文件/隔离数据库用例组 |
| T10 | 普通 Replace 回滚与日志恢复 | 旧目标及源在失败后保留；恢复不盲目删除 | 通过：32 个真实文件/隔离数据库用例组 |
| T11 | 实际复制/移动界面、取消与失败反馈 | 操作与数据结果一致，失败不冒充成功 | 通过：生产目标页 + ViewModel + 真实仓库界面操作；另有既有失败提示回归 |
| E01 | 真 SD 卡跨卷、云提供器、磁盘写满、断电、多系统版本 | 只有真实环境执行才可通过 | 未测：当前模拟器无对应硬件/服务；故障注入另列 |

## 数据边界

本轮所有可修改媒体位于运行专属 UUID 目录；数据库和设置隔离。原 24 相册、318 文件仅作只读完整性校验。COPY 的新副本标签是否复制遵循现有产品语义；不得在测试中擅自新增语义。

## 已复现缺陷

| 问题 | 修复前证据 | 处理与复测 |
| --- | --- | --- |
| 首项开始前及项间取消，仍创建副本或移动后续源文件 | `scratch/copy-move-edge-baseline-details.txt`：4 个取消用例失败 | 增加边界取消检查；已开始的单项完成或回滚并同步索引/标签，再停止后续项；设备复测通过 |
| 替换恢复所需备份缺失时仍删除剩余目标 | `scratch/copy-move-recovery-baseline-device-result.log`；所有正常恢复阶段通过，缺备份用例失败 | 无备份时保留剩余文件及恢复记录，设备复测通过 |
| Replace 将同名目录当文件替换，原文件夹被改成隐藏备份名 | `scratch/copy-move-cancellation-fix-device-result.log`：目录用例失败；其余 31 组通过 | 替换前拒绝非普通文件目标，保留原文件夹和源；最终设备复测通过 |

真实 SAF 路径使用测试 APK 中兼容 DocumentsContract 调用协议的隔离 ContentProvider；真实读写/重命名/重新打开会执行，但不是云端服务或实际 SD 卡。故障包括打开写入失败、同大小内容损坏、重命名失败、未知大小及权限拒绝。

## 最终验收

- 完整构建成功，209 项单元测试通过；复制/移动专项 32 个设备用例组全部通过，其中覆盖 JPEG、PNG、WebP、GIF、MP4、8 MiB 以上文件、中文/emoji/多点名称、六种模式/冲突组合、目录别名、编号占位、真实部分失败、六个取消用例、文档故障及替换恢复。
- 生产 TransferDestinationScreen、PhotosViewModel、QuickSortViewModel 和 MediaRepository 实际连接，使用隔离数据库与自有媒体验证确认取消、Copy Keep both、Move Skip、Move Keep both、标签跟随、缺源失败及真实部分成功。截图为真实组件的隔离宿主，不代表完整应用导航路径；`scratch/copy-move-ui-edge-qa/`。
- 原有失败反馈回归、Quick Sort 安全移动/撤销/替换恢复回归、标签备份原迁移回归通过。日志与最终安装摘要见 `scratch/copy-move-final-verification.json`。
- 修复仅涉及取消边界、恢复缺备份保护、拒绝替换目录；没有修改复制/移动界面或新增复制标签语义。取消采用安全单项边界：当前已开始项完成或回滚，并同步关联后停止下一项，不承诺撤销已经提交的文件。
- 保留的 24 相册、318 文件全部 SHA-256 相同，0 个变化；本轮 UUID 样本由测试自行清理。新 APK 已安装到可见模拟器，设备 APK 和构建 SHA-256 一致，全部对应源码早于产物。
- 未实测：实际 SD 卡跨卷、云网络/远程提供器、其他 Android 版本、真实磁盘写满/断电/进程强杀、GB 级文件、外部进程并发修改及真实源删除权限失败。提供器权限/写入/内容/提交故障与日志阶段重建只是相应条件的可控模拟，不能替代这些环境。
- `functional-edge-testing` skill 已通过结构校验并用于本轮专项，独立矩阵、失败基线、最小修复、真实界面、数据完整性与未测范围均按流程记录。

## 复跑入口

- `scratch/seed_gallery_regression_media.py`：生成并推送设备测试需要的 24 相册、318 个专用样本。依赖 Python/Pillow、FFmpeg 和 adb；本机工具路径在脚本中定义。
- `scratch/seed_quicksort_video_first_inputs.py`：生成并解码验证五个真实视频输入；`scratch/run_quicksort_video_cold_checks.py`：重复执行五轮独立应用进程的单视频首试。
- 构建 `:app:assembleDebugAndroidTest` 并安装测试 APK 后，使用 `am instrument -w -e suite <suite> com.pixel.gallery.multitask.test.test/com.pixel.gallery.integration.QuickSortSafetyInstrumentation`。对应 suite 为 `label-backup-edges`、`label-backup-ui`、`transfer-edges`、`transfer-ui-edges`、`video-first-attempt`；不指定 suite 时执行原 Quick Sort 安全回归。
- `scratch/verify_retained_gallery_media.py`：逐个比较保留样本的实际 SHA-256。测试报告中的 scratch 日志和安装包属于本机原始证据，未作为源码提交。
