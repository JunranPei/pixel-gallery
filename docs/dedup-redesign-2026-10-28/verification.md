# 实现与验证记录

日期：2026-10-29。分支：`codex/dedup-redesign-toast`。

## 最终实现

- 统一新顶栏、返回箭头、标题字重与 20dp 主体圆角边缘。
- 每组横向滚动照片；组间留白，无分隔横线；标题收成一行。
- 删除 review 页面上的 All photos、说明性小字与重复状态标签。顶部只保留组数；卡片保留文件信息，保留标记在图片内单行显示。
- 未选中不显示选择图标；选中显示 26dp primary 实心圆、onPrimary 勾与主题色卡片底，无外层白色底座、圈线或方框。
- 全选键沿用 RevealGalleryNavigation 选中胶囊的 primaryContainer/onPrimaryContainer 配色并加粗文字；底栏沿用 SelectionBottomSheet 的 surfaceContainerLow；圆形选中标记沿用现有选中控件的 primary/onPrimary。
- 全局全选／取消全选移入底部胶囊，位于 Move to bin 左侧；顶部入口删除。胶囊阴影降为 1dp，容量只显示数值。
- 06 悬浮底栏：四周透明，列表能从下面滚过；末尾滚动余量随实际底栏高度和导航栏变化。
- 全局 toast 实际约上移 16dp；柔和主题表面色，无叉号，双向滑动关闭，保留自动消失与队列。

## 构建

最终应用与测试 APK 构建成功：`scratch/dedup-palette-aligned-build-20261029.log`，`BUILD SUCCESSFUL in 3m 8s`。

应用输出：`scratch/dedup-toast-build-output/app/outputs/apk/debug/app-debug.apk`。

测试使用独立 app 输出与项目缓存、单 worker 和 Serial GC，避开当前工作站的构建文件占用及内存不足。没有修改项目的全局构建配置。

## 模拟器实际操作

设备：`emulator-5554`。独立测试包：`com.pixel.gallery.multitask.test`。通过正式 Compose 组件渲染；仅使用 app 缓存中的生成图片，不提交真实文件移动或删除。

| 检查 | 结果 |
| --- | --- |
| 未选中保留项为 unchecked；点选、取消点选正确切换 | PASS |
| 全局全选在胶囊内 Move to bin 左侧，顶部入口不存在 | PASS |
| 实际点击底部取消全选／全选，数量和选择状态正确切换 | PASS |
| review 页不显示 All photos | PASS |
| 照片横向滑动后能看到最后一份副本 | PASS |
| 横向滑动时悬浮底栏位置不变 | PASS |
| 最后一组文件能滚到悬浮栏上方 | PASS |
| 空选时实际点击不会打开移入回收站确认框 | PASS |
| 浅色、深色截图检查；深色组标题亮色文字像素检查 | PASS |
| toast 左滑、右滑关闭；短拖不关闭 | PASS |
| 同文连续提示不继承上一条的滑动状态 | PASS |
| 浅色根宿主、深色 modal 宿主；无 close action | PASS |
| 短 toast 自动消失 | PASS |

完整设备结果：`device-checks.txt`；原始日志：`scratch/dedup-palette-aligned-device-20261029.log`。最终应用与测试包均安装成功后运行检查。

## 实际界面截图

- `palette-native-light.png`：浅色去重页。
- `palette-native-dark.png`：深色去重页及横向滑动后的照片。
- `palette-native-scrolled.png`：最后一组完整滚到悬浮栏上方。
- `toast-light.png`、`toast-dark-modal.png`：最终 toast。

截图里的文件、容量与日期属于隔离测试样例。此次验证范围为界面和提示交互，没有将 UI 检查宣称为重新验证整个扫描、系统授权和回收站流程。
