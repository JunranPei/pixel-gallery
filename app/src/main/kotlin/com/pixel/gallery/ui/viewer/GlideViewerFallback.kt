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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey

@Composable
fun GlideViewerFallback(
    imagePath: String,
    width: Int,
    height: Int,
    orientationDegrees: Int,
    dateModifiedMillis: Long,
    isVisiblePage: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier) {
        val containerWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val swapped = orientationDegrees == 90 || orientationDegrees == 270
        val imageWidth = (if (swapped) height else width).toFloat().coerceAtLeast(1f)
        val imageHeight = (if (swapped) width else height).toFloat().coerceAtLeast(1f)
        val fitScale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
            .coerceAtLeast(0.0001f)
        val scaleToOriginal = 1f / fitScale
        val minScale = minOf(scaleToOriginal / 3f, 1f / 3f).coerceAtLeast(0.01f)
        val maxScale = maxOf(scaleToOriginal * 3f, 3f).coerceAtMost(60f)
        val fitWidthFraction = (imageWidth * fitScale / containerWidth).coerceIn(0f, 1f)
        val fitHeightFraction = (imageHeight * fitScale / containerHeight).coerceIn(0f, 1f)
        var imageView by remember { mutableStateOf<ImageView?>(null) }

        DisposableEffect(imagePath, dateModifiedMillis, isVisiblePage, imageView) {
            val view = imageView
            if (view != null && isVisiblePage) {
                val request = Glide.with(view)
                    .load(imagePath)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .fitCenter()
                val signedRequest = if (dateModifiedMillis > 0L) {
                    request.signature(ObjectKey(dateModifiedMillis))
                } else {
                    request
                }
                signedRequest.into(view)
            } else if (view != null) {
                Glide.with(view).clear(view)
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
            imageFitScaleX = fitWidthFraction,
            imageFitScaleY = fitHeightFraction,
            onTap = onClick
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