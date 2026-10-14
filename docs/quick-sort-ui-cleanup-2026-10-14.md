# Quick sort 与说明文字精简（2026-10-14）

## 布局

- 相册胶囊随目标面板向上平移，不再使用不断增大的底部 padding 挤压测量高度。
- PhotosScreen 裁切超出图片区域的内容；胶囊进入标题区域时自然被遮住。
- 无可撤回操作时使用不透明的中性底色和 HistoryToggleOff 图标；保留圆形按钮尺寸，禁用点击，并提供无可撤回的辅助功能状态。
- All 直接显示完整相册列表，不再插入 Frequent 分区；常用相册仍在自己的 tab 中。

## 说明文字审查

审查范围：设置、主页面与相册、收藏/隐藏/锁定/回收站、标签、快速分类、复制/移动、查重、查看器索引弹窗，以及权限与确认弹窗。文件路径、数量、错误、进度和许可声明属于实际内容，予以保留。

| 位置 | 处理 |
| --- | --- |
| All 中的 Frequent 标题与分区 | 删除；相册按完整列表原有次序显示 |
| Frequent 最近 30 天、8 个等排序规则说明 | 删除；空状态改为“暂无常用相册” |
| 设置入口 Themes / Display / Performance 描述 | 删除 |
| Theme mode / 利手标题下面的解释 | 删除；选项本身已表达含义 |
| Start at Albums / Excluded Folders / Clear Cache 副说明 | 删除 |
| 解码线程和普通缓存说明 | 仅保留需要重启 |
| Persistent Grid Limit 说明 | 仅保留适用超过 5 MB 文件的缩略图 |
| 收藏、隐藏相册、锁定文件夹、标签空页面解释 | 删除；保留空状态标题和已有操作 |
| 排除文件夹空页面 | 精简为不扫描这些文件夹 |
| 快速分类空相册页面 | 删除“先新建一个”等重复操作说明 |
| 移动队列说明 | 仅在有未结束任务时提示退出分类后队列继续 |
| 同名冲突正文 | 保留数量和目标，删除“请选择处理方式” |
| Copy / Move 的 Skip 与 Replace 副说明 | 删除同义描述；保留编号命名、替换所需权限 |
| Move 确认中的“移动到所选目标” | 删除；目标名称和路径仍显示 |
| 查重扫描长段说明 | 替换为原有简短的精确匹配与确认前不改文件提示 |
| 查重默认保留规则 | 精简为默认保留最早媒体时间，可手动更换 |
| 8 种索引格式的解码/金字塔/扫描行实现段落 | 删除；保留原图不变、电量/空间和格式限制提示 |
| 回收站确认重复提问 | 替换为可以恢复的提示；永久删除保留不可撤销警告 |

保留的操作信息还包括：标签删除不删除文件、失败后的处理位置与权限要求、替换的本次会话撤回范围、复制保留源文件、缓存清理后需重新解码、索引删除后缩放行为变化、查重所选项和保留原件、回收站期限、创建相册的父目录。

## 验证

- 扩展现有模拟器检查：对生产 PhotosScreen 在不同底部面板高度下比较胶囊尺寸和位移，检查标题边界裁切。
- 检查禁用撤回按钮忽略点击，截图像素与不透明中性底色一致。
- 检查 All 只有 tab 中的 Frequent 文本，四列相册首行完整；Frequent 正常筛选且没有规则说明。
- 回归利手、Copy / Move、Persistent Grid Limit、同名三选项和索引确认操作。

验证结果：101 项 JVM 测试全部通过；undo-handedness、redesign、confirmations 三组模拟器检查全部通过。已查看胶囊被标题裁切、禁用撤回、All/Frequent 和索引确认截图。Debug、AndroidTest、Release 构建通过。

版本：4.3.4.6-auto-multitask，基础 versionCode 2056；实际普通 Release APK versionCode **4056**，仅 **arm64-v8a**。aapt 核实 APK 内字段，apksigner 验签通过，签名证书与上一包 vc4055 相同。

证据位于 `scratch/quick-sort-layout-final-build.log`、`scratch/quick-sort-layout-ui-result.log`、`scratch/quick-sort-layout-redesign-result.log`、`scratch/quick-sort-layout-confirmations-result.log` 与 `scratch/quick-sort-layout-qa/`。

## 后续修补：4.3.4.7

- 补齐 Copy/Move、Quick Sort 队列进度条的端点亮点设置；同时关闭视频播放滑条和设置滑条的 stop indicator。查重原有设置保持一致。
- 选择模式仍绘制视频时长和播放图标，移动到左上角，避免与右上角勾选重叠。图片和视频同时选中、只选图片而视频未选中均在模拟器实际网格中查看。
- 正常加入队列、撤回请求不再额外弹出成功提示。拒绝入队改用共享 GalleryDialog；没有队列记录的拒绝不再要求用户检查队列位置。
- 冲突提示采用共享的目标相册名称/路径信息区和三选项组件。
- 冲突、失败弹窗统一在其他面板之后绘制，队列模态面板在警告期间暂时隐藏；冲突与失败警告依次显示，避免彼此遮挡。

验证：101 项 JVM 测试通过；Debug、AndroidTest、Release 构建通过；undo-handedness 界面检查通过，覆盖展开目标面板上的冲突/失败提示与可见操作按钮，并回归 Persistent Grid Limit 和利手控件。截图已查看，证据在 `scratch/selection-notice-qa/`、`scratch/selection-notice-4.3.4.7-ui-result.log` 与 `scratch/selection-feedback-4.3.4.7-build.log`。

新 APK：4.3.4.7-auto-multitask，基础 versionCode 2057，实际 versionCode **4057**，仅 **arm64-v8a**；APK 内版本/ABI 和签名已核实，证书与 vc4056 相同。

## 删除任务界面与失败重试：4.3.4.8

用户要求失败直接告知结果，取消移动任务和失败重试流程。本次删除 QuickSortQueueSheet、各屏幕的任务入口参数、ViewModel 和 Repository 的失败重试方法，以及任务、详情、重试、历史状态的专用文案。成功接受移动仍保持静默。

失败完成后使用共享 GalleryDialog，显示失败数量和可识别的简短原因，仅有“知道了”。活动不可见时保留进程内结果，回到前台后显示；结果关闭后清空，不增加已读状态、失败管理入口或重试记录。这个结果不跨进程保存，也不自动重新执行失败文件。

磁盘记录只保留未完成的操作、本次分拣可撤回的成功操作，以及文件已经移动但记账未完成或替换原件尚未释放的必要恢复证据。普通已结束失败不再作为待处理任务保存在磁盘；现存普通失败记录在加载时清除。执行状态的内存保留上限继续是最近 30 个已结束操作，普通失败不再绕过上限永久累加。未明确完成的文件恢复证据不参与普通历史淘汰，避免删除唯一副本或丢失替换原件。

网络核查与本地对照：

- Android 官方文档确认更新 MediaStore 的 RELATIVE_PATH / DISPLAY_NAME 可以移动文件。现有普通移动和 Quick Sort 已使用此接口，本次没有增加新的文件移动引擎：[Access media files](https://developer.android.com/training/data-storage/shared/media#update-item)。
- Aves 维护者在 2026-09-08 说明优先 MediaStore、必要时回退 SAF；同时说明跨存储卷及目标目录限制：[Aves #2144](https://github.com/deckerst/aves/issues/2144)。
- 原生 rename 默认可能覆盖目标，同名处理必须显式防覆盖，跨文件系统无法直接 rename：[Linux rename](https://man7.org/linux/man-pages/man2/rename.2.html)。因此保留现有“保留两份 / 跳过 / 替换”选择和已验证复制后的删源保护。

验证：107 项 JVM 测试通过；Debug、AndroidTest、Release 构建通过。新增磁盘筛选测试覆盖普通失败清除、未完成操作保留、移动已提交但记账未完成、当前会话撤回、替换原件保护和未安排的撤回。模拟器文件测试覆盖 SHA-256、时间、标签、重启恢复、冲突三选项和撤回，新增检查失败没有落成保存的重试任务，关闭失败提示及清除冲突后也不自动重新移动。界面检查覆盖失败数量、权限原因、只有“知道了”的操作，以及在相册面板上方可见；失败截图已查看。

证据：`scratch/move-simplify-final-build.log`、`scratch/move-simplify-final-safety.log`、`scratch/move-simplify-final-ui.log`、`scratch/move-simplify-qa/`。

APK：4.3.4.8-auto-multitask，基础 versionCode 2058，实际 versionCode **4058**，仅 **arm64-v8a**，签名证书与 vc4057 相同。没有推送。

## 普通移动、复制失败提示：2026-10-15，4.3.4.9

原有普通移动/复制会报错，但全失败仅显示第一条错误的短 Snackbar，部分失败则退出相册选择页后显示英文成功/失败数量，后台时容易错过。现改用共享 GalleryDialog，显示已成功数量、未成功数量和可识别的简短原因，只有“知道了”。部分失败在确认结果之后才返回；全失败留在相册选择页。权限错误和操作异常也使用同一弹窗，未知异常显示简短的移动/复制失败文字。

结果在活动恢复到 RESUMED 时才展示或消费，后台完成不会提前清掉结果；普通传输执行中拦截系统返回，与原有禁用返回按钮一致，避免离开页面后漏掉结果。关闭结果后清除原有内存状态，不增加任务、失败历史或重试入口。Quick Sort 与普通传输共用简短原因映射。全部跳过同名文件属于正常结束，不再误报为没有传输成功。

验证：107 项 JVM 测试通过；Debug、AndroidTest、Release 构建通过。新增模拟器检查覆盖复制和移动的部分失败、移动全失败、权限错误、未知异常、后台暂不显示及回到前台后显示、全部跳过，确认在关闭之前结果没有被清理，操作按钮只有“知道了”；回归 Quick Sort 提示、冲突三选项、撤回、利手、Grid 缓存滑条。移动/复制失败截图已查看。证据在 `scratch/transfer-failure-4.3.4.9-build.log`、`scratch/transfer-failure-4.3.4.9-ui.log`、`scratch/transfer-failure-4.3.4.9-regression.log` 和 `scratch/transfer-failure-4.3.4.9-qa/`。

APK：4.3.4.9-auto-multitask，实际 versionCode **4059**，仅 **arm64-v8a**，签名证书与 vc4058 相同。没有推送。
