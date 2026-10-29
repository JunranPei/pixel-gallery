# 原生界面验证

2026-10-29，分支 codex/dedup-scan-result-design。

实现：扫描 02 的左对齐插图、阶段、计数及进度条；结果 03 的深浅双层圆角照片与简短结果。两个尺寸共用同一 Canvas 矢量绘制，照片均为 136 × 136，旋转保持比例，山形使用圆润曲线。颜色取 MaterialTheme，支持动态主题。进度条使用现有 Material3 组件，8dp 高、4dp 段间隔、圆端，停止点为空。

扫描完成无重复和清理后无重复共用 DuplicateResultContent，只有图形和本地化的 No duplicates / 没有重复。移除范围、扫描说明、容量、完成数量和恢复提示。取消、失败与检查未完成保留各自真实状态。

标签相册的选择工具栏及移除菜单均使用 Icons.Outlined.LabelOff，添加/编辑使用 Label。label-icons.png 从实际 Compose 图标对照截图中裁取，展示的为应用使用的矢量图标，不是另画的示意。

构建：scratch/dedup-status-native-build-20261029.log，BUILD SUCCESSFUL in 3m 23s，应用和 androidTest APK 均成功。

设备：GalleryAlbumSearchQA20261019 / emulator-5556。原有默认软件图形初始化失败，最终通过仅针对本次进程的 host 渲染并禁用 Vulkan 启动，未修改模拟器配置或擦除数据。测试仅安装独立包 com.pixel.gallery.multitask.test，使用隔离界面状态，不扫描、移动或删除用户文件。

| 检查 | 结果 |
| --- | --- |
| 浅深色扫描页能显示真实阶段和进度 | PASS |
| 准备阶段不显示计数 | PASS |
| 进度条末端像素为浅色轨道，没有深色停止点 | PASS |
| 无重复扫描与清理后结果的内容区像素完全一致 | PASS |
| 正常结果不出现清理完成和移入数量文案 | PASS |
| 取消、错误及检查未完成不显示 No duplicates | PASS |
| Label 与 LabelOff 原生对照截图 | PASS |

原始结果：scratch/dedup-status-native-device-20261029.log。已查看 scan-light.png、result-scan-light.png、result-dark.png、label-icons.png。扫描和回收站业务逻辑没有改动；此检查不宣称重新验证整个文件扫描或删除流程。
