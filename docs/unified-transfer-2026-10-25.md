# 统一文件转移

修改前基线：`42dbb9b8`。使用 functional-edge-testing skill。

## 依据与取舍

- [Android 共享媒体](https://developer.android.com/training/data-storage/shared/media)：同卷媒体可通过更新 RELATIVE_PATH / DISPLAY_NAME 移动；复制需要完成写入后发布 IS_PENDING。
- [DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)：文档提供器能力、返回 URI 和错误语义不同于本地 MediaStore，保留单独的适配路径。
- 以快速分类的已验证移动流程为基础。保留 SHA-256、时间、文件身份校验、取消边界、检查点及索引迁移；不把减少安全校验作为提速手段。
- 普通移动此前在 MediaStore update 成功后直接返回；快速分类则验证目标、保留时间、迁移索引。普通操作完成后强制全库扫描，ViewModel 又请求刷新；快速分类立即反馈入队并后台执行，因此不能直接将体感差异当作文件吞吐差异。
- 普通复制与同名覆盖的既有事务保护、SAF 适配、快速分类队列和撤销语义继续保留。共用本地移动执行器和完成后的局部数据库更新。

## 验证矩阵

全部文件写入仅使用测试运行 UUID 所属目录、隔离数据库及设置；历史 GalleryQA 样本只读取作为输入。

| 编号 | 前置条件、操作或故障 | 预期持久状态 | 层级 | 状态 | 证据 |
| --- | --- | --- | --- | --- | --- |
| ~~U01~~ | 既有普通复制/移动 32 组基线 | 内容、时间、标签、冲突策略与取消符合旧契约 | 设备文件/DB | 通过 | scratch/unified-transfer-baseline-device2.log |
| ~~U02~~ | 两入口移动与真实 PNG 索引；插入无关哨兵记录检测全库扫描 | 内容、标签、时间正确；PNG 索引仍 Ready；只更新受影响行 | 设备文件/DB | 通过 | scratch/unified-transfer-delta-before.log（旧版失败）；scratch/unified-transfer-index-device.log（新版通过） |
| ~~U03~~ | 普通复制/移动原 32 组回归 | SAF 故障、覆盖恢复、取消与部分失败无退化 | 设备文件/DB | 通过 | scratch/unified-transfer-device.log |
| ~~U04~~ | 快速分类、撤销、同名替换与恢复；真实视频首试 | 队列持久化、时间与关联保持正确 | 设备文件/DB | 通过 | scratch/unified-transfer-quicksort-device.log；scratch/unified-transfer-video-device.log（22 组） |
| ~~U05~~ | 普通移动中断、数据库提交故障、重复恢复与双副本 | 目标核实后修复关联；数据库事务失败不丢文件；双副本不盲删 | 设备文件/DB | 通过 | scratch/unified-transfer-final-device.log |
| ~~U06~~ | 并发两入口、过期路径、规划后外部创建同名目标 | 串行提交，无覆盖、死锁或错误索引 | 设备文件/DB | 通过 | scratch/unified-transfer-device.log |
| ~~U07~~ | 转移、队列、恢复模型测试 | 既有契约保持通过 | JVM | 通过 | scratch/unified-transfer-build.log；51 项、0 失败 |
| U08 | 真 SD 卡跨卷、云文档、写满、断电、GB 文件 | 实际环境验证才可通过 | 外部环境 | 未测 | 当前模拟器不能替代 |
| U09 | 实际操作体感与界面 | 由用户手测；本轮不自动操作界面 | UI | 未测 | 待用户验证 |

## 测试工具修复

全套设备测试首次编译被旧 `GalleryPageCheckHost` 对已移除 `snackbarHost` 字段的引用阻挡；以空槽替代失效引用，属于测试宿主兼容修复，不是转移产品缺陷。初次日志：`scratch/unified-transfer-baseline-build.log`。

第一次基线设备运行中，独立测试 APK 的文档提供器缺少文件访问授权，无法列出另一 UID 创建的同名文件；给测试包恢复 MANAGE_EXTERNAL_STORAGE 后，原产品代码的 32 组全部通过。不得把该夹具权限问题记为产品修复。

## 实现边界

- `moveLocalMedia` 是普通本地移动和快速分类共同执行器。快速分类继续把阶段写入现有队列；普通移动用 AtomicFile 写入同结构检查点，启动时先恢复再清理缺失记录。读取和写入日志互斥，避免全库同步读取正在提交的原子文件。
- 旧 `moveEntry` 的独立 MediaStore/rename/copy-delete 路径已删除。共用流程保留内容摘要、源身份、实际目标路径、时间回读、无覆盖创建和源删除前检查。
- 已验证目标的标签重映射、媒体行更新、旧行删除在一个 Room 事务完成。成功的本地移动不再等待全库扫描；快速分类原成功批次扫描和普通 ViewModel 重复刷新已去掉。特殊复制、覆盖和 SAF 事务仍保留原全库对账，失败也保留兜底对账。
- 文件验证后的数据库/索引失败不会再盲目把物理文件搬回原位；保留已验证目标与检查点，供恢复修复关联。未完成普通日志保护原行和源查看索引，避免清理抢先删除恢复依据。
- 没有修改普通操作的等待完成界面，也没有修改快速分类的即时入队反馈；二者体感仍可能不同。未宣称文件读写吞吐提高；本轮优化的是成功后必经的全库扫描，内容校验没有减少。

## 数据核对

设备测试只修改运行专属 UUID 媒体目录和隔离数据库；历史 GalleryQA 目录只读。对历史 318 文件清单复核时，当前存在的 317 文件全部摘要一致，`QA 01 Small/photo-003.jpg` 不在原目录；没有把它补回或修改其他文件。这是与旧清单的差异，没有本轮修改前的全量清单，不能据此确定它何时缺失。原始记录：`scratch/unified-transfer-retained-media-verification.json`。

## 最终结果

- 最终构建：`scratch/unified-transfer-final-build.log`，成功；51 项相关 JVM 用例，0 失败。
- 最终设备文件/数据库回归：`scratch/unified-transfer-final-device.log`，39 组通过，包含真实 PNG 索引迁移、并发、规划后同名竞争、普通移动中断恢复与 SQLite 提交故障注入。
- 快速分类队列、撤销、替换备份及恢复：`scratch/unified-transfer-final-quicksort-device.log`，通过。
- 真实视频首试：`scratch/unified-transfer-video-device.log`，22 组通过，包括 MOV/MP4、日期元数据、原名、撤销及目标损坏注入。该运行与最终构建之间只增加普通移动日志互斥和数据库故障用例，没有改变视频执行分支。
- 安装包 `app/build/outputs/apk/debug/app-debug.apk` 已安装到 emulator-5556 的 `com.pixel.gallery.multitask.test`；设备 base.apk 与本地产物 SHA-256 一致：`d57fa4c9c3c554d154b7b32c74c91d856b59a594a30fd593793089e3b1ae1a0a`。
- 测试过程中模拟器退出后，使用原测试 AVD `GalleryAlbumSearchQA20261019` 重新启动继续验证，没有清空数据。未执行界面点击、滑动等自动操作。界面、体感速度及表中真实外部环境仍未验收。
