# MD3 短提示迁移（2026-10-25）

修改前提交：`28c48be0`（用户确认的 Lock / Hidden 底部导航）。

## 检索范围

检查 settings.gradle.kts 接入的全部模块：app、ssiv-pixel、indexed-jpeg/png/tiff/webp/raw/heif/bmp/jxl。检索 Kotlin、Java、XML 中的 Toast、Snackbar 和旧 Material 提示实现。

third_party/TabulaV3 是未接入当前构建的独立第三方参考项目；其原始源码保留，不作为 Pixel Gallery 的运行代码迁移或删除。

## 逐项结果

| 触发位置 | 原实现 | 本轮处理 |
| --- | --- | --- |
| 详情页收藏操作反馈 | 系统 Toast，长提示 | 统一 MD3 Snackbar，长提示 |
| 详情页删除 Lock 文件失败 | 系统 Toast，长提示 | 统一 MD3 Snackbar，长提示 |
| 详情页索引创建/删除结果 | 系统 Toast，长提示 | 统一 MD3 Snackbar，长提示 |
| Move/Copy 目标与源相册相同 | 系统 Toast，短提示 | 统一 MD3 Snackbar，短提示 |
| Quick Sort 已在相册/正在移动 | 系统 Toast，短提示 | 统一 MD3 Snackbar，短提示 |
| Move/Copy 选择不可用目标目录 | 独立 Snackbar 状态，没有显示宿主 | 接入统一队列，在半页面可见区域显示 |
| 设置中缓存清理完成 | 页面独立 MD3 Snackbar | 接入统一队列，页面离开后回调仍可显示 |
| 批量移出 Lock 结果 | 主容器 MD3 Snackbar | 接入统一队列 |
| Move/Copy 完成结果 | 主容器 MD3 Snackbar | 接入统一队列 |
| 批量删除 Lock 结果 | 主容器 MD3 Snackbar | 接入统一队列 |

共移除 5 处系统 Toast 调用，10 处短提示发送点共用一个队列。长提示提供关闭按钮；配色和圆角读取现有 MD3 主题，使用官方 Material3 Snackbar 的计时与无障碍支持。

## 删除与保留

- 删除全部运行模块的系统 Toast 调用与导入。
- 删除 Main、PerformanceSettings、TransferDestination 中分散的 SnackbarHostState。
- 删除 GalleryPage / GalleryPageContent 不再使用的页面提示宿主槽。
- 全部提示统一由 GalleryMessages 管理；主窗口和模态半页面只在最上层窗口渲染一次。
- 半页面只新增提示覆盖层，不修改其高度、拖动、展开状态、返回或确认逻辑。
- 已有 MD3 确认、错误、冲突弹窗及其业务处理保留。
- 单一顶部栏、永久主体及导航动画保持现有实现。

## 验证状态

源码检索确认参与构建的模块没有 Toast 或旧 Material Snackbar 引用。只编译与覆盖安装当前 Debug，不自行运行测试或操作应用验收。实际效果由用户测试。

Debug 构建成功（1m26s），覆盖安装原模拟器返回 Success；设备安装包与构建产物 SHA256 均为 `a357c049ed206fa29b59db2b5485d81af6b7011f210a4ab7445155c259ce879f`。未运行任何测试。
