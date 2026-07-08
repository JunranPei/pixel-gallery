package com.pixel.gallery.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Custom zoom/pan container for the image viewer.
 *
 * Responsibilities:
 *  - Pinch-to-zoom with focal-point tracking
 *  - Single-finger pan (only when zoomed in, i.e. scale > 1.0)
 *  - Double-tap toggle between fit-screen (scale=1.0) and original-pixel (scale=scaleToOriginal)
 *  - Boundary clamping so image edges never leave the viewport
 *  - Event passthrough to HorizontalPager when not zoomed in
 *
 * This component does NOT touch SubSamplingImage or tile loading in any way.
 * It only reports (scale, offsetX, offsetY) via [onTransformChanged].
 */
@Composable
fun ZoomableContainer(
    modifier: Modifier = Modifier,
    minScale: Float = 0.333f,
    maxScale: Float = 3.0f,
    scaleToOriginal: Float = 1.0f,
    autoApplyTransformations: Boolean = true,
    // The fraction of container size the image occupies at userScale=1.0 (fit-to-screen).
    // e.g. for a landscape photo on a portrait screen: imageFitScaleX=1.0, imageFitScaleY=0.5
    // Pass 1.0f (default) to fall back to container-size clamping.
    imageFitScaleX: Float = 1.0f,
    imageFitScaleY: Float = 1.0f,
    onTap: () -> Unit = {},
    onTransformChanged: (scale: Float, offsetX: Float, offsetY: Float) -> Unit = { _, _, _ -> },
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Ensure minScale <= maxScale to prevent coerceIn crashes
    val safeMinScale = minOf(minScale, maxScale)
    val safeMaxScale = maxOf(minScale, maxScale)

    // Animated values for smooth double-tap transitions
    val animScale = remember { Animatable(1f) }
    val animOffsetX = remember { Animatable(0f) }
    val animOffsetY = remember { Animatable(0f) }

    // Immediate tracking values used during gesture processing
    var gestureScale by remember { mutableFloatStateOf(1f) }
    var gestureOffsetX by remember { mutableFloatStateOf(0f) }
    var gestureOffsetY by remember { mutableFloatStateOf(0f) }

    // Report transformation changes to the parent
    LaunchedEffect(animScale.value, animOffsetX.value, animOffsetY.value) {
        onTransformChanged(animScale.value, animOffsetX.value, animOffsetY.value)
    }

    /**
     * Clamp offsets so image edges never leave the viewport.
     * When scale <= 1.0, offset is locked to (0, 0).
     *
     * At userScale=1.0 the image occupies:
     *   renderedW = containerW * imageFitScaleX
     *   renderedH = containerH * imageFitScaleY
     *
     * At userScale=s the rendered image is s times bigger:
     *   overflow_x = renderedW*s - containerW  → max pan = overflow/2
     *   overflow_y = renderedH*s - containerH  → max pan = overflow/2
     */
    fun clampOffset(scale: Float, rawX: Float, rawY: Float): Pair<Float, Float> {
        if (scale <= 1f) return 0f to 0f
        val w = containerSize.width.toFloat()
        val h = containerSize.height.toFloat()
        val maxX = (w * imageFitScaleX * scale - w).coerceAtLeast(0f) / 2f
        val maxY = (h * imageFitScaleY * scale - h).coerceAtLeast(0f) / 2f
        return rawX.coerceIn(-maxX, maxX) to rawY.coerceIn(-maxY, maxY)
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            // Tap / double-tap handler (separate pointerInput to avoid interference)
            .pointerInput(scaleToOriginal, safeMinScale, safeMaxScale) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapPosition ->
                        val currentScale = gestureScale
                        val targetScale: Float
                        val targetOffsetX: Float
                        val targetOffsetY: Float

                        if (abs(currentScale - 1f) > 0.01f) {
                            // Currently zoomed → reset to fit-screen
                            targetScale = 1f
                            targetOffsetX = 0f
                            targetOffsetY = 0f
                        } else {
                            // Currently at fit-screen → zoom to original pixel size
                            targetScale = scaleToOriginal.coerceIn(safeMinScale, safeMaxScale)
                            val cx = containerSize.width / 2f
                            val cy = containerSize.height / 2f
                            // Offset so the tap point stays visually fixed
                            val rawX = (cx - tapPosition.x) * (targetScale - 1f)
                            val rawY = (cy - tapPosition.y) * (targetScale - 1f)
                            val (clampedX, clampedY) = clampOffset(targetScale, rawX, rawY)
                            targetOffsetX = clampedX
                            targetOffsetY = clampedY
                        }

                        gestureScale = targetScale
                        gestureOffsetX = targetOffsetX
                        gestureOffsetY = targetOffsetY

                        val animSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                        scope.launch { animScale.animateTo(targetScale, animSpec) }
                        scope.launch { animOffsetX.animateTo(targetOffsetX, animSpec) }
                        scope.launch { animOffsetY.animateTo(targetOffsetY, animSpec) }
                    }
                )
            }
            // Pinch-zoom and pan handler
            .pointerInput(safeMinScale, safeMaxScale, containerSize) {
                awaitEachGesture {
                    // Wait for the first finger down
                    awaitFirstDown(requireUnconsumed = false)

                    // Touch-slop tracking
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    var accumulatedZoom = 1f
                    var accumulatedPan = Offset.Zero

                    // Gesture event loop
                    var gestureActive = true
                    while (gestureActive) {
                        val event = awaitPointerEvent()

                        // If any change was already consumed upstream, bail out
                        if (event.changes.any { it.isConsumed }) {
                            gestureActive = false
                            continue
                        }

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = false)

                        if (!pastTouchSlop) {
                            accumulatedZoom *= zoomChange
                            accumulatedPan += panChange
                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                            val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                            val panMotion = accumulatedPan.getDistance()
                            if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                pastTouchSlop = true
                            }
                        }

                        if (pastTouchSlop) {
                            val isPinching = event.changes.size >= 2
                            val newScale = (gestureScale * zoomChange).coerceIn(safeMinScale, safeMaxScale)

                            // Determine whether we should consume this event or let Pager handle it
                            val shouldConsume = if (isPinching) {
                                // Always consume pinch gestures
                                true
                            } else if (newScale <= 1f) {
                                // Not zoomed in → let Pager handle horizontal swipes
                                false
                            } else {
                                // Zoomed in → check if at horizontal boundary
                                val w = containerSize.width.toFloat()
                                val maxX = w * (newScale - 1f) / 2f
                                val atLeftEdge = gestureOffsetX >= maxX - 1f
                                val atRightEdge = gestureOffsetX <= -maxX + 1f

                                when {
                                    atLeftEdge && panChange.x > 0f -> false  // Can't pan further right → Pager
                                    atRightEdge && panChange.x < 0f -> false // Can't pan further left → Pager
                                    else -> true  // Still room to pan → consume
                                }
                            }

                            if (shouldConsume) {
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        change.consume()
                                    }
                                }
                            }

                            // Apply the transform regardless of consumption (so pinch still works visually)
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val prevScale = gestureScale
                                val scaleRatio = if (prevScale != 0f) newScale / prevScale else 1f

                                // Focal-point-aware offset calculation
                                val cx = containerSize.width / 2f
                                val cy = containerSize.height / 2f
                                val pivotX = centroid.x - cx
                                val pivotY = centroid.y - cy

                                val rawOffsetX = (gestureOffsetX - pivotX) * scaleRatio + pivotX + panChange.x
                                val rawOffsetY = (gestureOffsetY - pivotY) * scaleRatio + pivotY + panChange.y

                                val (clampedX, clampedY) = clampOffset(newScale, rawOffsetX, rawOffsetY)

                                gestureScale = newScale
                                gestureOffsetX = clampedX
                                gestureOffsetY = clampedY

                                // Snap (no animation) during active gesture
                                scope.launch {
                                    animScale.snapTo(newScale)
                                    animOffsetX.snapTo(clampedX)
                                    animOffsetY.snapTo(clampedY)
                                }
                            }
                        }

                        // Continue while any pointer is still down
                        gestureActive = event.changes.any { it.pressed }
                    }
                }
            }
            .graphicsLayer {
                if (autoApplyTransformations) {
                    scaleX = animScale.value
                    scaleY = animScale.value
                    translationX = animOffsetX.value
                    translationY = animOffsetY.value
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}