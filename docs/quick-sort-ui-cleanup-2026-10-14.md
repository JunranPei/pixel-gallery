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
