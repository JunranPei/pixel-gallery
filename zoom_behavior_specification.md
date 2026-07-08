# Pixel Gallery 缩放行为规则文档 (Zoom Behavior Specification)

本文档记录了 Pixel Gallery 图片查看器中自定义缩放组件 `ZoomableContainer` 的完整行为规则。该文档作为永久规格说明保留在项目根目录下。

> [!IMPORTANT]
> 本项目已废弃 telephoto 库的 zoomable 手势处理。所有媒体缩放手势均由自研的 `ZoomableContainer` 组件统一负责（包含普通图片、GIF 及视频）。
> telephoto 库仅用于普通大图的瓦片分割渲染（sub-sampling image），其内部手势不被启用。

---

## 1. 缩小下限 (calculatedMinZoom)

* **大图**（`scaleToOriginal > 1.0f`）：允许缩小到屏幕尺寸的 **1/3**（`0.333f`）
* **小图**（`scaleToOriginal <= 1.0f`）：允许缩小到原始尺寸的 **1/3**。原图越小，能缩到的尺寸就越小

$$\text{calculatedMinZoom} = \min(\text{scaleToOriginal} \times 0.333, \ 0.333)$$

```kotlin
val calculatedMinZoom = remember(scaleToOriginal) {
    minOf(scaleToOriginal * 0.333f, 0.333f)
}
```

## 2. 放大上限 (calculatedMaxZoom)

* 无硬编码上限，支持超大图片查看原始像素细节
* 上限为原始像素比例的 3 倍与屏幕尺寸 3 倍中较大者

$$\text{calculatedMaxZoom} = \max(\text{scaleToOriginal} \times 3.0, \ 3.0)$$

```kotlin
val calculatedMaxZoom = remember(scaleToOriginal) {
    maxOf(scaleToOriginal * 3.0f, 3.0f)
}
```

## 3. 双击行为

双击手势作为纯粹的二态切换：**屏幕适配尺寸 ↔ 100% 原始像素尺寸**

* 当前不在适配尺寸 → 双击回到适配尺寸（scale = 1.0）
* 当前在适配尺寸 → 双击放大到原始像素尺寸（scale = scaleToOriginal）
* 双击放大时，缩放中心为用户点击位置

## 4. 平移边界约束

| 缩放状态 | 平移行为 |
|---|---|
| `scale ≤ 1.0`（未放大，含缩小状态） | 平移锁定 `(0, 0)`，图片固定居中，不允许拖动 |
| `scale > 1.0`（已放大） | 允许在图片可见内容范围内拖动，图片边缘不得离开屏幕视口 |

**放大状态下的边界公式**：

$$\text{maxX} = \frac{\text{containerWidth} \times (\text{scale} - 1)}{2}$$
$$\text{maxY} = \frac{\text{containerHeight} \times (\text{scale} - 1)}{2}$$

平移偏移量 `offsetX` 严格限制在 `[-maxX, maxX]` 之间，`offsetY` 限制在 `[-maxY, maxY]` 之间。

## 5. 手势冲突解决（与 HorizontalPager 翻页协调）

为了让用户在已放大图片边缘滑动时依旧能够流畅切图，手势消费逻辑如下：

| 状态条件 | 滑动方向及边界状态 | 行为机制 |
|---|---|---|
| `scale ≤ 1.0`（未放大） | 任意滑动方向 | 不消费单指水平滑动事件 → 直接交由 HorizontalPager 翻页 |
| `scale > 1.0`（已放大） | 图片未滑倒左右边缘，或向非边缘方向滑动 | 消费单指滑动事件 → 在边界限制内平移图片 |
| `scale > 1.0`（已放大） | 图片已滑倒左边缘，且继续向右滑动（试图看前一张） | **不消费事件** → 透传给 HorizontalPager 切换到上一张 |
| `scale > 1.0`（已放大） | 图片已滑倒右边缘，且继续向左滑动（试图看后一张） | **不消费事件** → 透传给 HorizontalPager 切换到下一张 |
| 任意缩放状态 | 双指捏合手势 | **始终消费** → 处理多点触控缩放 |

---

## 技术实现架构

### 核心组件
* **[ZoomableContainer.kt](file:///D:/workplace/antigravity/pixel-gallery/app/src/main/kotlin/com/pixel/gallery/ui/viewer/ZoomableContainer.kt)**：
  自研手势缩放容器。通过 Compose `pointerInput` 检测单指及多指事件，通过 `awaitEachGesture` 实时分发并按边界条件动态控制事件消费（`change.consume()`）。

### 多媒体适配
* **普通图片**：由 `ZoomableContainer` 嵌套 `SubSamplingImage`，并向后者提供自定义 `ZoomableContentTransformation`（包含通过宽高计算的居中起始偏移，确保图片在加载与缩放时均处于屏幕中央，且缩放中心不发生偏移偏移）。
* **GIF 图片**：由 `ZoomableContainer` 嵌套 `GlideImage`，以 `autoApplyTransformations = true` 自动通过 `graphicsLayer` 进行矩阵变换缩放。
* **视频播放器**：由 `ZoomableContainer` 嵌套底层的 `ExoPlayer AndroidView` 实现，视频控制栏置于外部，避免控制栏在视频缩放时变形。
