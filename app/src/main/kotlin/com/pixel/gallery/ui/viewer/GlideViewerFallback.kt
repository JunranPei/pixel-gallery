package com.pixel.gallery.ui.viewer

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey

@Composable
internal fun GlideViewerFallback(
    imagePath: String,
    width: Int,
    height: Int,
    orientationDegrees: Int,
    dateModifiedMillis: Long,
    isVisiblePage: Boolean,
    modifier: Modifier = Modifier,
    onContentReadyChanged: (Boolean) -> Unit = {},
    transformStateStore: ViewerTransformStateStore? = null,
    onClick: () -> Unit = {}
) {
    val memoryKey = "fallback:$imagePath:$dateModifiedMillis"
    val initialTransform = remember(memoryKey, transformStateStore) { transformStateStore?.get(memoryKey) }
    var lastTransform by remember(memoryKey, transformStateStore) { mutableStateOf(initialTransform) }
    var decodedSize by remember(memoryKey) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    DisposableEffect(memoryKey, transformStateStore) {
        onDispose { lastTransform?.let { transformStateStore?.save(memoryKey, it, "composition-dispose") } }
    }
    BoxWithConstraints(modifier = modifier) {
        val containerWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val swapped = orientationDegrees == 90 || orientationDegrees == 270
        val imageWidth = (if (width > 0 && height > 0) { if (swapped) height else width }
            else decodedSize.width).toFloat().coerceAtLeast(1f)
        val imageHeight = (if (width > 0 && height > 0) { if (swapped) width else height }
            else decodedSize.height).toFloat().coerceAtLeast(1f)
        val fitScale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
            .coerceAtLeast(0.0001f)
        val scaleToOriginal = 1f / fitScale
        val minScale = minOf(scaleToOriginal / 3f, 1f / 3f).coerceAtLeast(0.01f)
        val maxScale = maxOf(scaleToOriginal * 3f, 3f).coerceAtMost(60f)
        val fitWidthFraction = (imageWidth * fitScale / containerWidth).coerceIn(0f, 1f)
        val fitHeightFraction = (imageHeight * fitScale / containerHeight).coerceIn(0f, 1f)
        var imageView by remember { mutableStateOf<ImageView?>(null) }
        val currentOnContentReadyChanged by rememberUpdatedState(onContentReadyChanged)

        DisposableEffect(imagePath, dateModifiedMillis, isVisiblePage, imageView) {
            val view = imageView
            if (view != null && isVisiblePage) {
                currentOnContentReadyChanged(false)
                val request = Glide.with(view)
                    .load(imagePath)
                    .withViewerTaskCompression()
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .fitCenter()
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean,
                        ): Boolean {
                            currentOnContentReadyChanged(false)
                            return false
                        }

                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean,
                        ): Boolean {
                            decodedSize = androidx.compose.ui.unit.IntSize(resource.intrinsicWidth.coerceAtLeast(1),
                                resource.intrinsicHeight.coerceAtLeast(1))
                            currentOnContentReadyChanged(true)
                            return false
                        }
                    })
                val signedRequest = if (dateModifiedMillis > 0L) {
                    request.signature(ObjectKey(dateModifiedMillis))
                } else {
                    request
                }
                signedRequest.into(view)
            } else if (view != null) {
                Glide.with(view).clear(view)
                currentOnContentReadyChanged(false)
            }
            onDispose {
                if (view != null) Glide.with(view).clear(view)
            }
        }

        ZoomableContainer(
            modifier = Modifier.fillMaxSize(),
            minScale = minScale,
            maxScale = maxScale,
            scaleToOriginal = scaleToOriginal,
            initialScale = initialTransform?.let {
                if (it.scale <= it.baseFitScale * 1.02f) it.scale / it.baseFitScale else it.scale / fitScale
            } ?: 1f,
            initialOffsetX = initialTransform?.let { (it.sourceWidth / 2f - it.sourceCenter.x) * it.scale } ?: 0f,
            initialOffsetY = initialTransform?.let { (it.sourceHeight / 2f - it.sourceCenter.y) * it.scale } ?: 0f,
            imageFitScaleX = fitWidthFraction,
            imageFitScaleY = fitHeightFraction,
            onTap = onClick,
            onTransformChanged = { scale, x, y ->
                val actualScale = scale * fitScale
                val state = com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.ViewState(
                    actualScale, fitScale, android.graphics.PointF(imageWidth / 2f - x / actualScale, imageHeight / 2f - y / actualScale),
                    imageWidth.toInt(), imageHeight.toInt(), 0.0)
                lastTransform = state
                transformStateStore?.save(memoryKey, state, "preview-gesture")
            },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    ImageView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        imageView = this
                    }
                },
                update = { imageView = it }
            )
        }
    }
}
