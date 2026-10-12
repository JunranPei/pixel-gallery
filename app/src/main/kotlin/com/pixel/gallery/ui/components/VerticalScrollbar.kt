package com.pixel.gallery.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Simple Gallery's RecyclerView-FastScroller (5a95285b1f): 26 x 40 dp outer
// handle, 12/4 dp start/end padding, 1000 ms idle timeout, 100 ms hide animation.
private val HandleWidth = 26.dp
private val HandleHeight = 40.dp
private val HandleStartPadding = 12.dp
private val HandleEndPadding = 4.dp

@Composable
fun GalleryScrollbar(
    lazyGridState: LazyGridState,
    layoutModifier: Modifier = Modifier,
    positionLabelProvider: ((Int) -> String?)? = null,
    onDragStateChanged: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val handleHeightPx = with(density) { HandleHeight.toPx() }
    var parentHeightPx by remember { mutableFloatStateOf(0f) }
    val trackLengthPx = (parentHeightPx - handleHeightPx).coerceAtLeast(0f)

    var engaged by remember { mutableStateOf(false) }
    var positionFraction by remember { mutableFloatStateOf(0f) }
    var positionItemIndex by remember { mutableIntStateOf(0) }
    var labelVisible by remember { mutableStateOf(false) }
    var scrollbarVisible by remember { mutableStateOf(false) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var labelJob by remember { mutableStateOf<Job?>(null) }
    var requestedItemIndex by remember { mutableIntStateOf(-1) }
    var lastScrollPosition by remember(lazyGridState) {
        mutableStateOf(lazyGridState.firstVisibleItemIndex to lazyGridState.firstVisibleItemScrollOffset)
    }

    val canScroll by remember(lazyGridState) {
        derivedStateOf { lazyGridState.canScrollBackward || lazyGridState.canScrollForward }
    }
    val scrollFraction by remember(lazyGridState) {
        derivedStateOf {
            val info = lazyGridState.layoutInfo
            val range = info.totalItemsCount - info.visibleItemsInfo.size
            when {
                !lazyGridState.canScrollBackward -> 0f
                !lazyGridState.canScrollForward -> 1f
                range <= 0 -> 0f
                else -> {
                    val first = info.visibleItemsInfo.firstOrNull {
                        it.index == lazyGridState.firstVisibleItemIndex
                    }
                    val rowItems = info.visibleItemsInfo.count { it.row == first?.row }.coerceAtLeast(1)
                    val rowHeight = (first?.size?.height ?: 0) + info.mainAxisItemSpacing
                    val partialRow = if (rowHeight > 0) {
                        lazyGridState.firstVisibleItemScrollOffset.toFloat() / rowHeight * rowItems
                    } else 0f
                    ((lazyGridState.firstVisibleItemIndex + partialRow) / range).coerceIn(0f, 1f)
                }
            }
        }
    }
    val sliderOffsetPx = (if (engaged || labelVisible) positionFraction else scrollFraction) * trackLengthPx
    val currentSliderOffsetPx by rememberUpdatedState(sliderOffsetPx)
    val currentCanScroll by rememberUpdatedState(canScroll)
    val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)

    LaunchedEffect(engaged) { currentOnDragStateChanged(engaged) }
    DisposableEffect(Unit) {
        onDispose { currentOnDragStateChanged(false) }
    }
    LaunchedEffect(
        canScroll, lazyGridState.isScrollInProgress, engaged,
        lazyGridState.firstVisibleItemIndex, lazyGridState.firstVisibleItemScrollOffset
    ) {
        val position = lazyGridState.firstVisibleItemIndex to lazyGridState.firstVisibleItemScrollOffset
        if (!canScroll) {
            scrollbarVisible = false
        } else if (lazyGridState.isScrollInProgress || engaged || position != lastScrollPosition) {
            scrollbarVisible = true
        }
        lastScrollPosition = position
        if (canScroll && !engaged && !lazyGridState.isScrollInProgress) {
            delay(1000)
            scrollbarVisible = false
        }
    }
    val handleScale by animateFloatAsState(
        targetValue = if (scrollbarVisible && canScroll) 1f else 0f,
        animationSpec = if (scrollbarVisible) snap() else tween(100),
        label = "scrollbar_visibility"
    )

    // Simple Gallery engages on DOWN, with the finger at the handle's centre.
    // Retain our additional track drag, but require vertical slop there so a tap
    // on the track does not jump the grid.
    val gestures = if (scrollbarVisible && canScroll) {
        Modifier.pointerInput(lazyGridState, trackLengthPx, handleHeightPx) {
            fun seek(pointerY: Float) {
                if (trackLengthPx <= 0f || !currentCanScroll) return
                val info = lazyGridState.layoutInfo
                val count = info.totalItemsCount
                if (count <= 0) return
                positionFraction = ((pointerY - handleHeightPx / 2f) / trackLengthPx).coerceIn(0f, 1f)
                // Map to the scrollable range, as Simple Gallery does, so release
                // does not shift the handle by the number of visible items.
                val range = (count - info.visibleItemsInfo.size).coerceAtLeast(0)
                val target = if (positionFraction == 1f) count - 1
                    else (positionFraction * range).roundToInt().coerceIn(0, count - 1)
                positionItemIndex = target
                if (requestedItemIndex == target && scrollJob?.isActive == true) return
                scrollJob?.cancel()
                requestedItemIndex = target
                if (target != lazyGridState.firstVisibleItemIndex || lazyGridState.firstVisibleItemScrollOffset != 0) {
                    scrollJob = scope.launch { lazyGridState.scrollToItem(target) }
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.position.x !in 0f..size.width.toFloat() || trackLengthPx <= 0f) {
                    return@awaitEachGesture
                }
                val onHandle = down.position.y in currentSliderOffsetPx..(currentSliderOffsetPx + handleHeightPx)
                val start = if (onHandle) {
                    down.consume()
                    down
                } else {
                    awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                }
                labelJob?.cancel()
                engaged = true
                labelVisible = true
                try {
                    seek(start.position.y)
                    verticalDrag(start.id) { change ->
                        seek(change.position.y)
                        change.consume()
                    }
                } finally {
                    engaged = false
                    labelJob = scope.launch {
                        // Keep the longer reading time of our track position hint.
                        delay(if (onHandle) 200L else 1000L)
                        labelVisible = false
                    }
                }
            }
        }
    } else Modifier

    val currentItemIndex = if (engaged || labelVisible) positionItemIndex else lazyGridState.firstVisibleItemIndex
    val count = lazyGridState.layoutInfo.totalItemsCount
    val label = positionLabelProvider?.invoke(currentItemIndex.coerceIn(0, (count - 1).coerceAtLeast(0)))
    val color = MaterialTheme.colorScheme.primary

    Box(
        modifier = layoutModifier
            .fillMaxHeight()
            .width(if (positionLabelProvider == null) HandleWidth else 180.dp)
            .onSizeChanged { parentHeightPx = it.height.toFloat() }
    ) {
        if (canScroll && labelVisible && !label.isNullOrBlank()) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(0, sliderOffsetPx.toInt()) }
                    .height(HandleHeight)
                    .padding(end = HandleWidth + 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = label,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(color)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
        Box(
            modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight().width(HandleWidth).then(gestures)
        ) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(0, sliderOffsetPx.toInt()) }
                    .width(HandleWidth).height(HandleHeight)
                    .graphicsLayer { scaleX = handleScale; scaleY = handleScale }
                    .padding(start = HandleStartPadding, end = HandleEndPadding)
                    .clip(RoundedCornerShape(24.dp)).background(color)
            )
        }
    }
}
