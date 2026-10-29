# 最终方案：扫描 02 + 结果 03

入口：index.html?final=1 。final-pair-light.png / final-pair-dark.png 为最终浅深色对照。

- 结果沿用 03 的中心构图，只保留照片插图和「没有重复」。
- 插图修正为恰好两张 136 × 136 的圆角方形照片，前后统一 28 的圆角；后层只做刚性旋转，前层不旋转，不使用第三层矩形或非等比缩放。
- 前后使用主题主色与主色容器调出的深浅两色。山顶、山谷和底部转角全部使用二次曲线，太阳保持圆形。
- 扫描沿用 02 的左对齐排版；使用同一个 pairedPhotos 图形函数，隐藏结果圆勾，其余照片结构和比例完全相同。
- 进度条对齐现有 Material 圆头样式：活动段和剩余段均为圆角，段间保留 4px 间隔，没有右端深色停止点。准备中继续显示不定进度。
- 既有二十案保留，已选择的两案和相应总览同步更新；最终页可统一切换主题和预览进度。

预览检查：两案照片节点一致、各只有两个等宽高矩形、正常结果仅有「没有重复」、0/50/100 进度均无停止点、412px 宽度无溢出，全部通过；已查看最终浅深色对照。

同时修正原生标签相册的取消标签图标：MainScaffold 中的选择工具栏和 LabelPhotosScreen 中的移除菜单均改为 Icons.Outlined.LabelOff，添加和编辑仍使用 Label。此修改只替换图标，回调、可用状态和操作文案不变。

原生图标修改构建通过：scratch/label-remove-icon-build-20261029.log，BUILD SUCCESSFUL in 4m 48s。此次未进行设备安装。

后续接入：最终扫描与结果方案均已写入 DuplicateStatusContent.kt 和 DuplicateReviewDialog.kt，两个正常无重复入口共用同一结果组件。取消、失败及有未检查文件的状态不冒充无重复。最终应用和测试 APK 构建及 emulator-5556 界面检查通过，见 native/verification.md；native/label-icons.png 是应用真实 Material 图标的对照截图。
