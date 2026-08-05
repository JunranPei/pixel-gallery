# 媒体转移技术设计

## 1. 架构边界

采用 UI、状态编排、传输引擎、存储适配四层：

1. `TransferDestinationScreen`：只负责目标浏览、搜索、选择与动作提交。
2. `PhotosViewModel`：维护页面状态、权限续接、进度和一次性结果事件。
3. `MediaRepository` / `MediaTransferService`：生成传输计划并逐项执行。
4. MediaStore、SAF 和直接文件适配器：根据 Android 版本、存储卷与授权选择安全路径。

Compose 不直接调用 `File.renameTo()`、`copyTo()` 或 `ContentResolver.update()`。

## 2. 核心模型

```kotlin
enum class TransferMode { COPY, MOVE }

enum class ConflictPolicy { KEEP_BOTH, SKIP, REPLACE }

data class TransferDestination(
    val stableKey: String,
    val displayName: String,
    val path: String,
    val documentUri: String?,
)

data class TransferRequest(
    val entries: List<MediaEntry>,
    val destination: TransferDestination,
    val mode: TransferMode,
    val conflictPolicy: ConflictPolicy,
)

data class TransferResult(
    val succeeded: List<TransferItemResult>,
    val skipped: List<TransferItemResult>,
    val failed: List<TransferItemResult>,
)
```

所有相册、导航和选择均以完整路径或 tree URI 形成的 `stableKey` 标识，不能使用文件夹名称作为主键。

## 3. 目标数据源

### 已识别相册

从 `photos` 按规范化父目录完整路径分组，生成目标相册列表。封面和数量沿用现有 `Album` 数据；目标列表不得复用当前按名称分组的逻辑。

### 搜索

在 `Dispatchers.Default` 上对预计算的目标列表进行去重、规范化和匹配。匹配字段包括显示名、父路径和完整路径。

### 其他文件夹

通过 `ACTION_OPEN_DOCUMENT_TREE` 获取持久目录授权。External Storage Provider tree URI 同时保留映射路径和真实 document URI；创建文件、枚举冲突及新建子目录均通过 `DocumentsContract`，映射路径只用于 MediaScanner 建立图库索引。内容先写入隐藏临时文档，完整校验后再重命名为最终名称；超过 24 小时的残留临时文档在再次访问目录时清理。

### 新建文件夹

- MediaStore 目标：创建目录或在首次写入时使用目标 `RELATIVE_PATH`。
- SAF 目标：通过 `DocumentFile.createDirectory()`。
- 创建后立即加入页面临时目标集合并选中，等待 MediaStore 同步后再由正式相册数据接管。

## 4. 传输策略

### Android 10 及以上，同一 MediaStore 存储卷移动

优先更新原 URI 的 `MediaStore.MediaColumns.RELATIVE_PATH`。这会移动底层文件，并尽可能保留 `contentId`、URI 和收藏关联。

对非本应用拥有的媒体，在 Android 11 以上先合并 URI 使用 `MediaStore.createWriteRequest()` 获取一次批量写权限。授权成功后恢复挂起的 `TransferRequest`，拒绝则回到目标页。

### 复制

1. 在正确的目标集合和存储卷插入新 MediaStore 项。
2. 写入 `DISPLAY_NAME`、`MIME_TYPE`、`RELATIVE_PATH` 和 `IS_PENDING=1`。
3. 通过 `ParcelFileDescriptor` 流式复制，关闭前执行 `fsync`。
4. 校验目标字节数与源文件完全一致。
5. 检查 `IS_PENDING=0` 更新确实成功。
6. 失败时删除未发布的目标项，源项不变。

### 跨卷移动或无法更新路径

使用“安全复制 + 提交 + 删除源”策略：

1. 按复制流程完整创建目标。
2. 确认目标字节数一致、已发布并获得有效 MediaStore URI。
3. 删除源 MediaStore 项或源 DocumentFile。
4. 将 Pixel 收藏关联从旧 `contentId` 迁移到新 `contentId`。

任何目标提交前的失败都不得删除源。

### Android 8–9

在已获得存储写权限且位于可写文件系统时使用同卷原子重命名；跨卷使用临时目标文件复制、校验、原子改名和源删除，最后由 MediaScanner 更新旧、新路径。

## 5. 文件冲突

执行前按目标文件名检测冲突：

- `KEEP_BOTH`：按 `name (1).ext`、`name (2).ext` 生成可用名称。
- `SKIP`：记录跳过，不修改源和目标。
- `REPLACE`：先完成新内容的临时写入，再替换旧目标；每个阶段写入应用私有事务日志。启动时若发现中断事务，在源仍存在时恢复旧目标，源已删除时保留已提交目标并清理备份。

SAF 目标不提供 `REPLACE`，只允许 `KEEP_BOTH` 或 `SKIP`，避免依赖文档提供器不一致的重命名与原子替换语义。

冲突检测与最终创建之间仍可能出现竞争，因此适配器需要处理插入/创建时的二次冲突。

## 6. 权限续接

当前 `MainActivity` 的 IntentSender 回调只负责刷新，不能表达“授权后继续传输”。实现时改为可注册的一次性结果续接：

- ViewModel 保存不含 Activity 引用的挂起请求。
- Activity launcher 把 `RESULT_OK` / 取消回传 ViewModel。
- ViewModel 在授权成功后重新校验目标并继续。
- 不在后台线程阻塞等待 Activity 结果，不使用 `CompletableFuture.join()`。

`MANAGE_EXTERNAL_STORAGE` 可作为当前安装环境的快速路径，但传输功能不得把它作为唯一前提。

## 7. 数据一致性

- 同卷路径更新成功后，立即查询更新后的 URI 并 upsert Room，随后触发完整同步。
- 复制成功后 upsert 新 MediaStore 项。
- 跨卷移动时在数据库事务内迁移收藏关联并移除旧条目。
- 操作完成后使 MediaStore generation 缓存失效或确保下一次同步读取新 generation。
- Viewer 单项移动成功后返回网格，避免继续显示失效路径。

## 8. 页面导航状态

- 多选入口：来源选择保留在 `MainScaffold`，目标页关闭或权限取消时恢复原选择。
- Viewer 入口：传入当前 `MediaEntry` 快照；成功移动后退出 Viewer，成功复制后可返回 Viewer 并保持原项。
- 转移执行期间阻止返回导致重复提交；系统返回只关闭冲突/新建目录对话框，不丢弃已完成结果。

## 9. 性能

- 目标相册分组和搜索不在主线程执行。
- 封面使用现有 Glide 缩略图模型、稳定 signature 和 200px 级请求，不触发 Viewer 大图解码。
- 文件内容按流复制，不把整张图片载入内存。
- 批量操作默认串行，避免同时占满存储 I/O；后续可针对同卷小文件评估有限并发。

## 10. 安全与日志

- 日志只记录 contentId、模式、目标 stableKey、耗时与结果，不记录用户搜索词。
- 禁止将目标解析到 `Android/data`、应用私有目录或系统限制目录。
- 所有路径在使用前规范化，并验证目标仍位于用户授权的树或允许的 MediaStore 顶级目录。
- 目标未完成落盘、字节校验、发布和索引前，禁止删除源文件。
- 源删除失败时尽量清理目标副本并重新扫描源路径，确保回到操作前状态。
- MediaStore 完整同步失败不得把已经完成的文件操作误报为整体失败。
