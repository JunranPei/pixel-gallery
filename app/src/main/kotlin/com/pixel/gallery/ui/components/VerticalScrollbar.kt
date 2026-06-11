package com.pixel.gallery.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A premium interactive scrollbar for LazyVerticalGrid designed to align with
 * Material Design Expression principles. Features smooth animations, dynamic width
 * expansion on interaction, tap-to-jump gestures, and an optimized, stutter-free scroll mapping.
 */
@Composable
fun VerticalScrollbar(
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    var dragPercentage by remember { mutableStateOf<Float?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Auto-hide control with delay
    LaunchedEffect(gridState.isScrollInProgress, isDragging) {
        if (gridState.isScrollInProgress || isDragging) {
            isVisible = true
        } else {
            delay(1500)
            isVisible = false
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(300),
        label = "scrollbar_alpha"
    )

    // Dynamic width expansion on touch / drag (Material Design Expression style)
    val thumbWidth by animateDpAsState(
        targetValue = if (isPressed || isDragging) 12.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "scrollbar_width"
    )

    // Dynamic columns count inference based on visible grid items' coordinates
    val columns by remember {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) 1
            else visible.map { it.offset.x }.distinct().size.coerceAtLeast(1)
        }
    }

    // High-precision row-based scroll percentage calculation
    val scrollPercentage by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val totalItems = info.totalItemsCount
            val visibleItems = info.visibleItemsInfo
            if (totalItems == 0 || visibleItems.isEmpty()) 0f
            else {
                val firstVisible = visibleItems.first()
                val itemHeight = firstVisible.size.height.coerceAtLeast(1)
                val firstVisibleIndex = gridState.firstVisibleItemIndex
                val scrollOffset = gridState.firstVisibleItemScrollOffset
                
                val firstVisibleRow = firstVisibleIndex / columns
                val progress = firstVisibleRow.toFloat() + (scrollOffset.toFloat() / itemHeight)
                
                val totalRows = (totalItems + columns - 1) / columns
                val visibleRows = visibleItems.map { it.offset.y }.distinct().size.coerceAtLeast(1)
                val maxProgress = (totalRows - visibleRows).coerceAtLeast(1)
                (progress / maxProgress).coerceIn(0f, 1f)
            }
        }
    }

    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    // Dynamic thumb height based on the ratio of visible rows
    val thumbHeightDp by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            if (total == 0 || containerHeightPx <= 0f) 64.dp
            else {
                val ratio = visible.toFloat() / total
                val calculated = (containerHeightPx * ratio) / density.density
                calculated.coerceIn(48f, 120f).dp
            }
        }
    }

    val thumbOffsetPx by remember {
        derivedStateOf {
            val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
            val trackHeight = containerHeightPx - thumbHeightPx
            val pct = dragPercentage ?: scrollPercentage
            if (trackHeight <= 0f) 0f else pct * trackHeight
        }
    }


    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(36.dp) // Broadened interactive area for better grab target
            .graphicsLayer { this.alpha = alpha }
            .onSizeChanged { containerHeightPx = it.height.toFloat() }
            .pointerInput(containerHeightPx) {
                val thumbHeightPx = thumbHeightDp.toPx()
                val trackHeight = containerHeightPx - thumbHeightPx
                if (trackHeight > 0f) {
                    awaitPointerEventScope {
                        while (true) {
                            // 1. Wait for touch down
                            val down = awaitFirstDown(requireUnconsumed = false)
                            
                            // Check if the touch down position is inside the thumb bounds
                            val currentThumbTop = thumbOffsetPx
                            val isTouchOnThumb = down.position.y in currentThumbTop..(currentThumbTop + thumbHeightPx)
                            
                            if (isTouchOnThumb) {
                                // A. User touched the thumb - trigger drag gesture immediately
                                isPressed = true
                                dragPercentage = scrollPercentage
                                var dragPct = scrollPercentage
                                var dragTriggered = false
                                
                                // Drag track uses the static outer container coordinate space, preventing zero-delta shifts
                                drag(down.id) { change ->
                                    change.consume()
                                    if (!dragTriggered) {
                                        dragTriggered = true
                                        isDragging = true
                                    }
                                    val deltaY = change.position.y - change.previousPosition.y
                                    dragPct = (dragPct + deltaY / trackHeight).coerceIn(0f, 1f)
                                    dragPercentage = dragPct

                                    val info = gridState.layoutInfo
                                    val totalItems = info.totalItemsCount
                                    val visibleItems = info.visibleItemsInfo
                                    if (totalItems > 0 && visibleItems.isNotEmpty()) {
                                        val firstVisible = visibleItems.first()
                                        val itemHeight = firstVisible.size.height.coerceAtLeast(1)
                                        val totalRows = (totalItems + columns - 1) / columns
                                        val visibleRows = visibleItems.map { it.offset.y }.distinct().size.coerceAtLeast(1)
                                        val maxScrollableRows = (totalRows - visibleRows).coerceAtLeast(1)

                                        val targetRowFloat = dragPct * maxScrollableRows
                                        val targetRow = targetRowFloat.toInt().coerceIn(0, totalRows - 1)
                                        val fraction = targetRowFloat - targetRow
                                        val scrollOffset = (fraction * itemHeight).toInt()
                                        val targetIndex = targetRow * columns

                                        scrollJob?.cancel()
                                        scrollJob = coroutineScope.launch {
                                            gridState.scrollToItem(targetIndex, scrollOffset)
                                        }
                                    }
                                }
                                
                                // Reset states upon release / cancel
                                isPressed = false
                                isDragging = false
                                dragPercentage = null
                            } else {
                                // B. User touched the track (outside the thumb) - wait for release to perform tap-to-jump
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    val clickY = down.position.y - (thumbHeightPx / 2)
                                    val targetPct = (clickY / trackHeight).coerceIn(0f, 1f)
                                    
                                    val info = gridState.layoutInfo
                                    val totalItems = info.totalItemsCount
                                    val visibleItems = info.visibleItemsInfo
                                    if (totalItems > 0 && visibleItems.isNotEmpty()) {
                                        val firstVisible = visibleItems.first()
                                        val itemHeight = firstVisible.size.height.coerceAtLeast(1)
                                        val totalRows = (totalItems + columns - 1) / columns
                                        val visibleRows = visibleItems.map { it.offset.y }.distinct().size.coerceAtLeast(1)
                                        val maxScrollableRows = (totalRows - visibleRows).coerceAtLeast(1)

                                        val targetRowFloat = targetPct * maxScrollableRows
                                        val targetRow = targetRowFloat.toInt().coerceIn(0, totalRows - 1)
                                        val fraction = targetRowFloat - targetRow
                                        val scrollOffset = (fraction * itemHeight).toInt()
                                        val targetIndex = targetRow * columns

                                        scrollJob?.cancel()
                                        scrollJob = coroutineScope.launch {
                                            gridState.scrollToItem(targetIndex, scrollOffset)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .width(thumbWidth)
                .height(thumbHeightDp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                .graphicsLayer {
                    translationY = thumbOffsetPx
                }
        )
    }
}
