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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
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
    diagnosticsKey: String = "",
    minScale: Float = 0.333f,
    maxScale: Float = 3.0f,
    scaleToOriginal: Float = 1.0f,
    initialScale: Float = 1.0f,
    initialOffsetX: Float = 0.0f,
    initialOffsetY: Float = 0.0f,
    enabled: Boolean = true,
    autoApplyTransformations: Boolean = true,
    // The fraction of container size the image occupies at userScale=1.0 (fit-to-screen).
    // e.g. for a landscape photo on a portrait screen: imageFitScaleX=1.0, imageFitScaleY=0.5
    // Pass 1.0f (default) to fall back to container-size clamping.
    imageFitScaleX: Float = 1.0f,
    imageFitScaleY: Float = 1.0f,
    onTap: () -> Unit = {},
    onZoomGestureStarted: () -> Unit = {},
    onZoomGestureEnded: () -> Unit = {},
    onTransformChanged: (scale: Float, offsetX: Float, offsetY: Float) -> Unit = { _, _, _ -> },
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    var containerSize by remember(diagnosticsKey) { mutableStateOf(IntSize.Zero) }

    // Ensure minScale <= maxScale to prevent coerceIn crashes
    val safeMinScale = minOf(minScale, maxScale)
    val safeMaxScale = maxOf(minScale, maxScale)

    // Animated values for smooth double-tap transitions
    val startingScale = initialScale.coerceIn(safeMinScale, safeMaxScale)
    val animScale = remember(diagnosticsKey) { Animatable(startingScale) }
    val animOffsetX = remember(diagnosticsKey) { Animatable(initialOffsetX) }
    val animOffsetY = remember(diagnosticsKey) { Animatable(initialOffsetY) }
    var animationJob by remember(diagnosticsKey) { mutableStateOf<Job?>(null) }
    var renderScale by remember(diagnosticsKey) { mutableFloatStateOf(startingScale) }
    var renderOffsetX by remember(diagnosticsKey) { mutableFloatStateOf(initialOffsetX) }
    var renderOffsetY by remember(diagnosticsKey) { mutableFloatStateOf(initialOffsetY) }
    var pointerGestureInProgress by remember(diagnosticsKey) { mutableStateOf(false) }

    // Immediate tracking values used during gesture processing
    var gestureScale by remember(diagnosticsKey) { mutableFloatStateOf(startingScale) }
    var gestureOffsetX by remember(diagnosticsKey) { mutableFloatStateOf(initialOffsetX) }
    var gestureOffsetY by remember(diagnosticsKey) { mutableFloatStateOf(initialOffsetY) }

    fun trace(name: String, detail: String) {
        if (diagnosticsKey.isNotEmpty()) {
            ViewerLoadMetrics.event(name, detail, imageKey = diagnosticsKey)
        }
    }

    // Double-tap animation values update one render state. Pointer gestures write the
    // render state directly so no per-event coroutine can overtake a newer touch sample.
    LaunchedEffect(animScale.value, animOffsetX.value, animOffsetY.value) {
        if (!pointerGestureInProgress) {
            renderScale = animScale.value
            renderOffsetX = animOffsetX.value
            renderOffsetY = animOffsetY.value
        } else {
            trace(
                "ZOOM_PREVIEW_STALE_ANIMATION_IGNORED",
                "anim=${animScale.value},${animOffsetX.value},${animOffsetY.value} " +
                    "gesture=$gestureScale,$gestureOffsetX,$gestureOffsetY",
            )
        }
    }
    LaunchedEffect(renderScale, renderOffsetX, renderOffsetY) {
        onTransformChanged(renderScale, renderOffsetX, renderOffsetY)
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
            .clipToBounds()
            .onSizeChanged {
                containerSize = it
                trace(
                    "ZOOM_PREVIEW_SIZE",
                    "container=${it.width}x${it.height} fit=$imageFitScaleX,$imageFitScaleY " +
                        "range=$safeMinScale..$safeMaxScale original=$scaleToOriginal",
                )
            }
            // Tap / double-tap handler (separate pointerInput to avoid interference)
            .pointerInput(enabled, scaleToOriginal, safeMinScale, safeMaxScale) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapPosition ->
                        val currentScale = gestureScale
                        val currentOffsetX = gestureOffsetX
                        val currentOffsetY = gestureOffsetY
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
                            if (targetScale > 1.01f) {
                                onZoomGestureStarted()
                            }
                            val cx = containerSize.width / 2f
                            val cy = containerSize.height / 2f
                            // Offset so the tap point stays visually fixed
                            val rawX = (cx - tapPosition.x) * (targetScale - 1f)
                            val rawY = (cy - tapPosition.y) * (targetScale - 1f)
                            val (clampedX, clampedY) = clampOffset(targetScale, rawX, rawY)
                            targetOffsetX = clampedX
                            targetOffsetY = clampedY
                        }

                        trace(
                            "ZOOM_PREVIEW_DOUBLE_TAP",
                            "tap=${tapPosition.x},${tapPosition.y} container=${containerSize.width}x${containerSize.height} " +
                                "from=$currentScale,$currentOffsetX,$currentOffsetY " +
                                "to=$targetScale,$targetOffsetX,$targetOffsetY " +
                                "fit=$imageFitScaleX,$imageFitScaleY",
                        )
                        gestureScale = targetScale
                        gestureOffsetX = targetOffsetX
                        gestureOffsetY = targetOffsetY

                        val animSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                        animationJob?.cancel()
                        animationJob = scope.launch {
                            try {
                                trace(
                                    "ZOOM_PREVIEW_ANIMATION_START",
                                    "from=$currentScale,$currentOffsetX,$currentOffsetY " +
                                        "to=$targetScale,$targetOffsetX,$targetOffsetY",
                                )
                                animScale.snapTo(currentScale)
                                animOffsetX.snapTo(currentOffsetX)
                                animOffsetY.snapTo(currentOffsetY)
                                coroutineScope {
                                    launch { animScale.animateTo(targetScale, animSpec) }
                                    launch { animOffsetX.animateTo(targetOffsetX, animSpec) }
                                    launch { animOffsetY.animateTo(targetOffsetY, animSpec) }
                                }
                            } finally {
                                trace(
                                    "ZOOM_PREVIEW_ANIMATION_END",
                                    "gesture=$gestureScale,$gestureOffsetX,$gestureOffsetY " +
                                        "anim=${animScale.value},${animOffsetX.value},${animOffsetY.value} " +
                                        "render=$renderScale,$renderOffsetX,$renderOffsetY",
                                )
                                onZoomGestureEnded()
                            }
                        }
                    }
                )
            }
            // Pinch-zoom and pan handler
            .pointerInput(
                enabled,
                safeMinScale,
                safeMaxScale,
                containerSize,
                imageFitScaleX,
                imageFitScaleY
            ) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Wait for the first finger down
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    pointerGestureInProgress = true
                    animationJob?.cancel()
                    trace(
                        "ZOOM_PREVIEW_POINTER_DOWN",
                        "pointer=${firstDown.id.value} at=${firstDown.position.x},${firstDown.position.y} " +
                            "gesture=$gestureScale,$gestureOffsetX,$gestureOffsetY " +
                            "anim=${animScale.value},${animOffsetX.value},${animOffsetY.value} " +
                            "render=$renderScale,$renderOffsetX,$renderOffsetY",
                    )

                    // Touch-slop tracking
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    var accumulatedZoom = 1f
                    var accumulatedPan = Offset.Zero

                    // Gesture lock state to prevent mid-gesture control handover to parent Pager
                    var isGestureLockedToPan = false
                    var gestureLockChecked = false
                    var zoomIntentDispatched = false
                    var pinchGestureClaimed = false
                    var sample = 0
                    var endedBecauseConsumed = false
                    var lastSampleTraceTime = Long.MIN_VALUE

                    // Gesture event loop
                    var gestureActive = true
                    while (gestureActive) {
                        val event = awaitPointerEvent()
                        val hasMultiplePointers = event.changes.size >= 2

                        // Once a second pointer appears, this entire stroke belongs to zoom,
                        // including pointer-up and the remaining one-finger tail. Otherwise
                        // HorizontalPager can inherit the same pinch halfway through.
                        if (hasMultiplePointers) {
                            pinchGestureClaimed = true
                        }
                        if (pinchGestureClaimed) {
                            event.changes.forEach { it.consume() }
                        }

                        // A consumed single-finger stroke belongs to another recognizer. A
                        // claimed pinch remains ours even if the tap detector consumed a change.
                        if (event.changes.any { it.isConsumed } && !pinchGestureClaimed) {
                            endedBecauseConsumed = true
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
                                trace(
                                    "ZOOM_PREVIEW_SLOP_CROSSED",
                                    "zoomMotion=$zoomMotion panMotion=$panMotion touchSlop=$touchSlop " +
                                        "pointers=${event.changes.size} centroid=${centroid.x},${centroid.y}",
                                )
                            }
                        }

                        if (pastTouchSlop) {
                            val isPinching = hasMultiplePointers
                            val newScale = (gestureScale * zoomChange).coerceIn(safeMinScale, safeMaxScale)
                            // The preview is already sufficient at and below fit-screen. Start
                            // the tiled layer only once the gesture actually needs detail above
                            // fit; shrinking must never cause a mid-gesture renderer handoff.
                            if (isPinching && newScale > 1.01f && !zoomIntentDispatched) {
                                zoomIntentDispatched = true
                                onZoomGestureStarted()
                            }

                            // Lock in the gesture consumer on the very first frame of panning motion
                            if (!gestureLockChecked && !isPinching) {
                                gestureLockChecked = true
                                if (newScale > 1.05f) {
                                    val w = containerSize.width.toFloat()
                                    val maxX = (w * imageFitScaleX * newScale - w).coerceAtLeast(0f) / 2f
                                    val atLeftEdge = gestureOffsetX >= maxX - 1f
                                    val atRightEdge = gestureOffsetX <= -maxX + 1f

                                    val isAtBoundaryForScroll = when {
                                        atLeftEdge && panChange.x > 0f -> true
                                        atRightEdge && panChange.x < 0f -> true
                                        else -> false
                                    }
                                    isGestureLockedToPan = !isAtBoundaryForScroll
                                } else {
                                    isGestureLockedToPan = false
                                }
                            }

                            val shouldConsume = if (isPinching) {
                                // Always consume pinch gestures
                                true
                            } else if (newScale <= 1.05f) {
                                // Not zoomed in → let Pager handle horizontal swipes
                                false
                            } else {
                                // Zoomed in → rely strictly on our gesture lock decision made at the start of the stroke
                                isGestureLockedToPan
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

                                sample += 1
                                val sampleTime = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                                if (sample == 1 || sampleTime - lastSampleTraceTime >= 80L) {
                                    lastSampleTraceTime = sampleTime
                                    trace(
                                        "ZOOM_PREVIEW_SAMPLE",
                                        "sample=$sample pointers=${event.changes.size} pinch=$isPinching consume=$shouldConsume " +
                                            "centroid=${centroid.x},${centroid.y} zoomChange=$zoomChange " +
                                            "pan=${panChange.x},${panChange.y} scale=$prevScale->$newScale " +
                                            "offset=$gestureOffsetX,$gestureOffsetY raw=$rawOffsetX,$rawOffsetY " +
                                            "clamped=$clampedX,$clampedY",
                                    )
                                }
                                gestureScale = newScale
                                gestureOffsetX = clampedX
                                gestureOffsetY = clampedY

                                // Snap (no animation) during active gesture
                                renderScale = newScale
                                renderOffsetX = clampedX
                                renderOffsetY = clampedY
                            }
                        }

                        // Continue while any pointer is still down
                        gestureActive = event.changes.any { it.pressed }
                    }
                    if (zoomIntentDispatched) {
                        onZoomGestureEnded()
                    }
                    pointerGestureInProgress = false
                    trace(
                        "ZOOM_PREVIEW_POINTER_END",
                        "samples=$sample consumed=$endedBecauseConsumed zoomIntent=$zoomIntentDispatched " +
                            "pinchClaimed=$pinchGestureClaimed " +
                            "gesture=$gestureScale,$gestureOffsetX,$gestureOffsetY " +
                            "render=$renderScale,$renderOffsetX,$renderOffsetY",
                    )
                }
            }
            .graphicsLayer {
                if (autoApplyTransformations) {
                    scaleX = renderScale
                    scaleY = renderScale
                    translationX = renderOffsetX
                    translationY = renderOffsetY
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
