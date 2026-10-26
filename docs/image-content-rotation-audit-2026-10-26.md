# 图片非适屏旋转调查

## 基线与范围

用户要求先提交上一版，再查明图片非适屏旋转闪烁、放大后中心偏移及手势失效原因，并搜索开发经验。上一版提交 `a3a004db`，未推送。下方调查记录以该提交为基线；用户随后授权修改，修复及复测记录见文末。

## 已确认的实现问题

1. `ViewerContentRotation.kt:116–135`：外层按角度计算适屏倍率；90° 松手吸附后交换子组件宽高。`SubsamplingScaleImageView.kt:430–444` 在放大时保持内部绝对像素倍率，和外层在交换宽高时变回 1 的倍率不一致。适屏补偿成立不代表放大时也连续。
   - 数学例：1080×2400 视口、2048×1536 图片，90° 吸附前外层倍率为 4/3，交换子布局后为 1。内层深度放大的像素倍率保持不变时，最终画面尺度会突然减少 25%。尺寸变化还会重新限制可移动范围，可能强制居中。
2. **中心突跳的直接实现缺陷**：SSIV `onSizeChanged` 写入 `pendingScale/sPendingCenter`，但 `onDraw` 没调用应用这些状态的 `preDraw()`。本地 `preDraw()` 只在首次 ready/loaded 时调用，已有图片改变布局尺寸后不会执行待恢复状态。上游 SSIV 每次 onDraw 在绘制前调用它。模拟器确认强制 invalidate 后待恢复值仍非空，中心仍越界。
3. `ViewerRotationGesture.kt:27,123` 只将角度传给外层；`RotatingMediaLayer` 绕组件中心旋转，没有传递双指中心和原图锚点。故无法保证偏心双指旋转时手指下的内容不漂移。
4. **输入区域与显示区域脱节**：外层 Compose graphicsLayer 旋转和原生 AndroidView 混用。90° 后 SSIV 原生全局可见性返回 false，区域为 `Rect(1080,0–3480,1080)`（屏幕宽 1080）。此后实际拖动、捏合没有 SSIV 触摸记录，状态不变。不能把单独 getGlobalVisibleRect 的结果当作完整命中机制证明，但它与实际触摸丢失相符；应在固定视口里统一处理变换及触点逆变换，不能只让外层旋转原生交互组件。
5. `ViewerRotationGesture.kt:110–116` 接管时给原输入链发送 CANCEL；原生 SSIV 的内部状态机只在 UP 分支清除拖动/缩放/指针计数，缺少 CANCEL 分支。外部 CANCEL 处理调用 `onUpEvent`，不等于清除了内部状态。本次日志中 CANCEL 后 maxTouchCount 一直为 2。
6. `SimpleSubsamplingImageView.kt:1462–1537` 将 CANCEL 也作为抬手触发 220ms 延迟回调，可能在旋转中依据变化后的适屏比例重新居中并触发 TILES→PREVIEW。回调只校验图片会话，未校验旋转/新触摸的世代。本轮复现并没有发生这次换层，故它是另一个风险，不是本轮死区的已证实原因。
7. `ZoomableContainer.kt:549` 取消路径 finally 只清除 pointer stroke 标记，缩放生命周期结束主要在正常路径；这是一处状态残留风险，不能直接认定为用户全部手势失效的唯一原因。

## 开发经验与修正方向

- [AndroidX 官方变换示例](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/foundation/foundation/samples/src/main/java/androidx/compose/foundation/samples/TransformGestureSamples.kt)：联合计算缩放、位移和旋转，补偿位移以保持双指中心下的内容位置。应使用统一的原图到屏幕变换及其逆变换处理触点。
- [Android 触摸接管说明](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup)：接管后子组件收到 CANCEL，后续事件交给父级；不能等子组件再收到 UP 才清理。
- [SSIV 上游绘制流程](https://android.googlesource.com/platform/external/subsampling-scale-image-view/+/refs/heads/main/library/src/main/java/com/davemorrissey/labs/subscaleview/SubsamplingScaleImageView.java)：onDraw 在 ready 检查后调用 preDraw，应用待恢复中心/缩放。
- [AndroidX PointerInteropUtils 源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/input/pointer/PointerInteropUtils.android.kt)：MotionEvent 转换路径通过 offsetLocation 平移坐标。这是上游当前实现的参考，未将 main 分支当作项目锁定版本的逐字证据。
- [ZoomImage 旋转文档](https://github.com/panpf/zoomimage/blob/main/docs/rotate.zh.md)：基础变换、用户变换、触点和原图坐标转换分开表达；它的公开旋转接口限制为 90° 倍数，不能作为现成的任意角度跟手方案直接替换。
- [ZoomImage 子采样经验](https://raw.githubusercontent.com/panpf/zoomimage/main/docs/subsampling.zh.md)：连续变换时可按类型暂停加载图块，保留已有图块；空闲时无需持续刷新。

建议：图片的缩放/移动/旋转共用同一份坐标状态，锁住双指锚点；解除“90° 完成就交换图片组件宽高”的隐式联动。预览与高清图层必须使用同一变换，旋转期间不因 CANCEL 进行层间交接；结束后只补一次必要的可见图块刷新。取消、换页、失焦及新手势开始均完整释放旧手势状态和过期回调。保留现有解码器/原文件索引，手势旋转只改变显示变换。

## 验证矩阵

| 项目 | 层级 | 结果 |
|---|---|---|
| ~~缩小后旋转~~ | 模拟器、生产图片组件隔离宿主 | 执行：0.65 倍保持预览层，旋转后仍可捏合至 0.52；没有逐帧量化瞬时闪烁 |
| ~~1.2 倍放大后偏移并旋转~~ | 同上 | 复现缺陷：中心 (853.33,768) → (1896.30,-274.96)；尺寸 1080×2400 → 2400×1080；待恢复中心未应用；后续拖动/捏合不进入 SSIV |
| ~~3 倍放大后旋转，再缩放移动~~ | 同上 | 复现缺陷：中心 (954.47,768) → (1371.65,350.81)，后续拖动/捏合无效。强制重绘无效；外层反向旋转仍可回到 0° |
| 用户手机严重时全部手势失效 | 真实详情页 | 未复现，不能将静态风险当作全部根因 |

诊断脚本 `ImageRotationAudit.kt` 使用独立棋盘格 JPEG、真实触摸事件、生产旋转仲裁及 SimpleSubsamplingImageView；未包含 Pager、全套详情页和用户手机环境。

调查证据：`scratch/image-rotation-audit-first-reproduction.txt`、`scratch/image-rotation-audit-device.txt`、`scratch/image-rotation-audit-evidence/`。调查版脚本没有结果断言，不能仅凭 instrumentation code -1 认定功能通过。

## 修复与复测

图片使用固定屏幕视口的 `RotatingImageHost`，在原生子容器里旋转预览与图块，Android 的触摸分发负责逆变换。预览的 Compose 手势也使用同一几何关系转换触点、双击位置和位移。GIF 与视频沿用各自显示路径。

- 尺寸交换时按新旧适屏比例补偿缩放，绘制前应用待恢复的原图中心；深度放大的可见像素尺度连续。
- 预览保持参与测量，尺寸变化时更新缩放中心；旋转期间暂停图层交接，旧触摸的延迟回调失效。
- CANCEL 清理拖动、捏合、快速缩放和指针计数，保留当前变换，并安排一次稳定图块刷新；预览取消路径也结束缩放生命周期。
- 两层原生容器均允许子内容超出未旋转的矩形，最外面的屏幕视口裁切。旋转期间复用已加载预览填补图块外露区域，结束后隐藏预览；没有新增持续计时器、整图重解码或索引重建。

旋转继续绕视口中心，未实现双指重心平移与旋转的联合自由变换。轻度放大时，某一轴在新方向已完全容纳图片，现有边界规则仍会将该轴居中；深度放大的合法原图中心应保持不变。

| 编号 | 前置与操作 | 预期 | 层级 | 结果 / 证据 |
|---|---|---|---|---|
| ~~R1~~ | 0.65 倍缩小，旋转 90°、继续捏合、反向旋转 | 比例正确、预览缩放中心正确、手势可继续 | 模拟器、生产组件与 HorizontalPager 隔离宿主 | 通过，`scratch/image-rotation-pager-edge-after.txt` 中 `PASS underfit` |
| ~~R2~~ | 1.2 倍放大及移动，旋转、缩小回预览 | 松手不丢缩放比例，待恢复状态清空，触摸区域可用 | 同上 | 通过，`PASS zoomed` |
| ~~R3~~ | 3 倍放大及移动，旋转、拖动、捏合、反向旋转 | 合法原图中心误差小于 1 px，捏合实际改变倍率，翻页位置不变 | 同上 | 通过，`PASS deep`；原图中心约 `(954.469,768)` 在首次旋转前后保持 |
| ~~R4~~ | 备用 Glide 图片路径，3 倍放大后执行同样手势 | 原生预览同样可旋转与继续交互 | 同上 | 通过，`PASS fallback` |
| ~~R5~~ | 深度放大，双指保持接近 90°，尚未松手 | 屏幕四角应为夹具图像，不能露出灰色裁切边 | 同上，真实屏幕像素检查 | 修复前失败：`scratch/image-rotation-pager-edge-before.txt` 的 `blank rotating edge 24,80`；修复后通过 |
| ~~R6~~ | 0/45/90/180/270° 触点往返及不同倍率下的尺寸交换 | 坐标往返一致、像素尺度连续；已有手势仲裁/交接规则成立 | JVM 单元测试 | 19 项 RotationGestureTrackerTest + 3 项 ViewerGestureHandoffTest，通过 |
| R7 | 用户手机、完整详情页、全部图片格式与功耗测量 | 手机实际操作验收 | 真机 | 未测；不能将隔离宿主结果当作完整应用或真机功耗结论 |

R5 的截图对照：`scratch/image-rotation-held-90.png`（中途有灰色裁切边）与 `scratch/image-rotation-held-90-fixed.png`（修复后）。最终设备测试包含图片组件、真实双指事件、旋转仲裁和两页 HorizontalPager，未包含全部详情页按钮及半页面。最终脚本包含上述断言，四组均输出 PASS，instrumentation 返回 -1。

最终调试构建：`scratch/image-rotation-edge-fix-build.log`，BUILD SUCCESSFUL。22 项单测结果位于 `app/build/test-results/testDebugUnitTest/`。用户验收后授权发布 4.3.5.6-auto-multitask，基础 versionCode 2073，arm64 APK versionCode 4073。

发布构建：`scratch/image-rotation-release-build.log`，BUILD SUCCESSFUL（含 release 检查）。安装包为 `released_apks/4.3.5.5-auto-multitask/image-rotation-fix/PixelGallery-4.3.5.5-image-rotation-fix-arm64-v8a.apk`；包名 `com.pixel.gallery.multitask`，versionCode 4072，仅 arm64-v8a，testOnly 未设置（默认 false），签名验证通过且与上一安装包一致。

- 发布 APK SHA-256：`58301a33aca7a1963c5d1e48db847a6d9d3ebbb9a5aa722f7af74e86966eb580`。
- 模拟器实际安装的 debug APK SHA-256 与本次构建文件一致：`7a57829a7edc49c29f7c0114346f2f0c32b36522cb45682a7f88d3fea4284e51`。测试设备为隔离的 emulator-5558，未在用户手机上安装。
