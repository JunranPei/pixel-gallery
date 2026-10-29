package com.pixel.gallery.ui.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Only MOVE and the finite release animation invalidate this layer. No idle ticker. */
internal class ViewerContentRotation(initialDegrees: Int, private val scope: CoroutineScope) {
    var degrees by mutableFloatStateOf(initialDegrees.toFloat())
        private set
    var settledDegrees by mutableIntStateOf(initialDegrees)
        private set
    var busy by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var gestureStart: Float? = null
    private var gestureBase = initialDegrees

    fun drag(delta: Float) {
        if (gestureStart == null) {
            animation?.cancel()
            gestureStart = degrees
            gestureBase = settledDegrees
        }
        busy = true
        degrees = gestureStart!! + delta
    }

    fun finish(step: Int, onSettled: (Int) -> Unit) {
        val start = gestureStart ?: return
        gestureStart = null
        animation?.cancel()
        val target = gestureBase + step.coerceIn(-1, 1) * 90f
        animation = scope.launch {
            try {
                animate(degrees, target, animationSpec = tween(160)) { value, _ -> degrees = value }
                val normalized = ((target.toInt() % 360) + 360) % 360
                // Layout changes once, after the snap. The fit calculation keeps the
                // visible pixels at the same size across this quarter-turn remeasure.
                settledDegrees = normalized
                degrees = normalized.toFloat()
                onSettled(normalized)
            } finally { busy = false }
        }
    }

    fun cancel() {
        animation?.cancel()
        gestureStart = null
        degrees = settledDegrees.toFloat()
        busy = false
    }
}

internal class RotationLayerGeometry {
    var nativeImage = false
    var viewportWidth by mutableFloatStateOf(0f)
    var viewportHeight by mutableFloatStateOf(0f)
    var childWidth by mutableFloatStateOf(0f)
    var childHeight by mutableFloatStateOf(0f)
    var aspectRatio = 1f
    var rotation: ViewerContentRotation? = null

    fun scale(): Float = rotationFitScale(viewportWidth, viewportHeight, childWidth, childHeight,
        aspectRatio, rotation?.degrees ?: 0f)

    fun unmap(point: Offset, vector: Boolean = false): Offset {
        val radians = Math.toRadians((rotation?.degrees ?: 0f).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val x = point.x - if (vector) 0f else viewportWidth / 2f
        val y = point.y - if (vector) 0f else viewportHeight / 2f
        val scale = scale().coerceAtLeast(0.0001f)
        return Offset((x * c + y * s) / scale + if (vector) 0f else childWidth / 2f,
            (-x * s + y * c) / scale + if (vector) 0f else childHeight / 2f)
    }

    fun map(rect: Rect): Rect {
        val radians = Math.toRadians((rotation?.degrees ?: 0f).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val scale = scale()
        fun point(x: Float, y: Float): Offset {
            val dx = x - childWidth / 2f
            val dy = y - childHeight / 2f
            return Offset(viewportWidth / 2f + (dx * c - dy * s) * scale,
                viewportHeight / 2f + (dx * s + dy * c) * scale)
        }
        val points = listOf(point(rect.left, rect.top), point(rect.right, rect.top),
            point(rect.left, rect.bottom), point(rect.right, rect.bottom))
        return Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}

internal fun rotationFitScale(width: Float, height: Float, childWidth: Float, childHeight: Float,
    aspectRatio: Float, degrees: Float): Float {
    if (minOf(width, height, childWidth, childHeight, aspectRatio) <= 0f) return 1f
    val rawHeight = minOf(childWidth / aspectRatio, childHeight)
    val rawWidth = rawHeight * aspectRatio
    val radians = Math.toRadians(degrees.toDouble())
    val c = abs(cos(radians)).toFloat()
    val s = abs(sin(radians)).toFloat()
    return minOf(width / (rawWidth * c + rawHeight * s), height / (rawWidth * s + rawHeight * c))
}

internal val LocalRotationLayerGeometry = staticCompositionLocalOf<RotationLayerGeometry?> { null }

@Composable
internal fun RotatingMediaLayer(rotation: ViewerContentRotation?, aspectRatio: Float,
    modifier: Modifier = Modifier, nativeImage: Boolean = false, content: @Composable () -> Unit) {
    val geometry = remember { RotationLayerGeometry() }
    geometry.rotation = rotation
    geometry.nativeImage = nativeImage
    geometry.aspectRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val swap = (rotation?.settledDegrees ?: 0) % 180 != 0
    if (nativeImage) {
        BoxWithConstraints(modifier) {
            geometry.viewportWidth = constraints.maxWidth.toFloat()
            geometry.viewportHeight = constraints.maxHeight.toFloat()
            geometry.childWidth = if (swap) geometry.viewportHeight else geometry.viewportWidth
            geometry.childHeight = if (swap) geometry.viewportWidth else geometry.viewportHeight
            CompositionLocalProvider(LocalRotationLayerGeometry provides geometry) { content() }
        }
        return
    }
    Layout(modifier = modifier, content = {
        CompositionLocalProvider(LocalRotationLayerGeometry provides geometry) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                rotationZ = rotation?.degrees ?: 0f
                scaleX = geometry.scale()
                scaleY = scaleX
            }) { content() }
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val childWidth = if (swap) height else width
        val childHeight = if (swap) width else height
        geometry.viewportWidth = width.toFloat()
        geometry.viewportHeight = height.toFloat()
        geometry.childWidth = childWidth.toFloat()
        geometry.childHeight = childHeight.toFloat()
        val child = measurables.single().measure(Constraints.fixed(childWidth, childHeight))
        layout(width, height) { child.place((width - childWidth) / 2, (height - childHeight) / 2) }
    }
}
