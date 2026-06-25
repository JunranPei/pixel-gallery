package com.pixel.gallery.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rebuilt from scratch with completely unique variable names and correct modifier order.
 */
@Composable
fun GalleryScrollbar(
    lazyGridState: LazyGridState,
    layoutModifier: Modifier = Modifier,
    onDragStateChanged: (Boolean) -> Unit = {}
) {
    val scrollbarScope = rememberCoroutineScope()
    val localDensity = LocalDensity.current

    var dragActive by remember { mutableStateOf(false) }
    var dragFraction by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var touchActive by remember { mutableStateOf(false) }
    var scrollbarVisible by remember { mutableStateOf(false) }
    var activeScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(dragActive) {
        onDragStateChanged(dragActive)
    }

    // Visibility control
    LaunchedEffect(lazyGridState.isScrollInProgress, dragActive, touchActive) {
        if (lazyGridState.isScrollInProgress || dragActive || touchActive) {
            scrollbarVisible = true
        } else {
            delay(1500)
            scrollbarVisible = false
        }
    }

    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (scrollbarVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "scrollbar_alpha_anim"
    )

    val sliderWidth by animateDpAsState(
        targetValue = if (touchActive || dragActive) 12.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "slider_width_anim"
    )

    // Calculate fraction
    val scrollFraction by remember {
        derivedStateOf {
            val gridInfo = lazyGridState.layoutInfo
            val allItemsCount = gridInfo.totalItemsCount
            val onScreenItemsCount = gridInfo.visibleItemsInfo.size
            if (allItemsCount == 0 || allItemsCount <= onScreenItemsCount) 0f
            else {
                val topItemIndex = lazyGridState.firstVisibleItemIndex
                (topItemIndex.toFloat() / (allItemsCount - onScreenItemsCount).toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    var parentHeightPx by remember { mutableFloatStateOf(0f) }
    val barHeightDp = 64.dp
    val barHeightPx = remember(localDensity) { with(localDensity) { barHeightDp.toPx() } }

    val effectiveTrackPx by remember {
        derivedStateOf {
            val scrollableRange = parentHeightPx - barHeightPx
            if (scrollableRange < 0f) 0f else scrollableRange
        }
    }

    val sliderOffsetPx by remember {
        derivedStateOf {
            val fraction = if (dragActive) dragFraction else scrollFraction
            fraction * effectiveTrackPx
        }
    }

    val tapGestureModifier = if (scrollbarVisible) {
        Modifier.pointerInput(effectiveTrackPx) {
            detectTapGestures(
                onPress = { tapCoord ->
                    val touchY = tapCoord.y
                    val touchOnSlider = touchY in sliderOffsetPx..(sliderOffsetPx + barHeightPx)
                    if (touchOnSlider) {
                        touchActive = true
                        try {
                            awaitRelease()
                        } finally {
                            touchActive = false
                        }
                    } else {
                        if (effectiveTrackPx > 0f) {
                            val clickCenterY = touchY - (barHeightPx / 2)
                            val jumpPercentage = (clickCenterY / effectiveTrackPx).coerceIn(0f, 1f)
                            val gridInfo = lazyGridState.layoutInfo
                            val allItemsCount = gridInfo.totalItemsCount
                            if (allItemsCount > 0) {
                                val targetScrollIdx = (jumpPercentage * (allItemsCount - 1)).toInt().coerceIn(0, allItemsCount - 1)
                                activeScrollJob?.cancel()
                                activeScrollJob = scrollbarScope.launch {
                                    lazyGridState.scrollToItem(targetScrollIdx)
                                }
                            }
                        }
                    }
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = layoutModifier
            .fillMaxHeight()
            .width(36.dp)
            .alpha(scrollbarAlpha)
            .onSizeChanged { parentHeightPx = it.height.toFloat() }
            .then(tapGestureModifier)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(x = 0, y = sliderOffsetPx.toInt()) } // Offset first!
                .padding(end = 4.dp)
                .width(sliderWidth)
                .height(barHeightDp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dragDelta ->
                        if (effectiveTrackPx > 0f) {
                            dragActive = true
                            dragFraction = (dragFraction + dragDelta / effectiveTrackPx).coerceIn(0f, 1f)
                            val gridInfo = lazyGridState.layoutInfo
                            val allItemsCount = gridInfo.totalItemsCount
                            if (allItemsCount > 0) {
                                val targetScrollIdx = (dragFraction * (allItemsCount - 1)).toInt().coerceIn(0, allItemsCount - 1)
                                if (targetScrollIdx != lazyGridState.firstVisibleItemIndex) {
                                    activeScrollJob?.cancel()
                                    activeScrollJob = scrollbarScope.launch {
                                        lazyGridState.scrollToItem(targetScrollIdx)
                                    }
                                }
                            }
                        }
                    },
                    onDragStarted = { startedPosition ->
                        dragActive = true
                        dragFraction = scrollFraction
                    },
                    onDragStopped = { velocity ->
                        dragActive = false
                    }
                )
        )
    }
}
