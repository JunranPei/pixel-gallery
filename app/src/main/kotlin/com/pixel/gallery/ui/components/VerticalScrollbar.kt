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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A custom interactive scrollbar for LazyVerticalGrid.
 * Rebuilt from scratch to align with Material Design Expression principles.
 * Uses Modifier.offset to ensure 100% reliable positioning and composition updates.
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

    // Simple, robust scroll percentage calculation
    val scrollPercentage by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val totalItems = info.totalItemsCount
            val visibleItems = info.visibleItemsInfo.size
            if (totalItems == 0 || totalItems <= visibleItems) 0f
            else {
                val firstVisibleIndex = gridState.firstVisibleItemIndex
                (firstVisibleIndex.toFloat() / (totalItems - visibleItems).toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeightDp = 64.dp

    // Calculate vertical offset in DP to trigger Compose layout phase updates reliably
    val thumbOffsetDp by remember {
        derivedStateOf {
            val pct = dragPercentage ?: scrollPercentage
            val totalHeightDp = containerHeightPx / density.density
            val trackHeightDp = totalHeightDp - 64f // 64dp is thumb height
            if (trackHeightDp <= 0f) 0.dp else (pct * trackHeightDp).dp
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(36.dp) // Broadened interactive area for better grab target
            .graphicsLayer { this.alpha = alpha }
            .onSizeChanged { containerHeightPx = it.height.toFloat() }
            .pointerInput(containerHeightPx) {
                val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
                val trackHeightPx = containerHeightPx - thumbHeightPx
                if (trackHeightPx > 0f) {
                    awaitPointerEventScope {
                        while (true) {
                            // 1. Wait for touch down
                            val down = awaitFirstDown(requireUnconsumed = false)
                            
                            // Check if the touch down position is inside the thumb bounds
                            val thumbOffsetPx = with(density) { thumbOffsetDp.toPx() }
                            val isTouchOnThumb = down.position.y in thumbOffsetPx..(thumbOffsetPx + thumbHeightPx)
                            
                            if (isTouchOnThumb) {
                                // A. User touched the thumb - trigger drag gesture immediately
                                isPressed = true
                                dragPercentage = scrollPercentage
                                var dragPct = scrollPercentage
                                var dragTriggered = false
                                
                                drag(down.id) { change ->
                                    change.consume()
                                    if (!dragTriggered) {
                                        dragTriggered = true
                                        isDragging = true
                                    }
                                    val deltaY = change.position.y - change.previousPosition.y
                                    dragPct = (dragPct + deltaY / trackHeightPx).coerceIn(0f, 1f)
                                    dragPercentage = dragPct

                                    val info = gridState.layoutInfo
                                    val totalItems = info.totalItemsCount
                                    if (totalItems > 0) {
                                        val targetIndex = (dragPct * (totalItems - 1)).toInt().coerceIn(0, totalItems - 1)
                                        scrollJob?.cancel()
                                        scrollJob = coroutineScope.launch {
                                            gridState.scrollToItem(targetIndex)
                                        }
                                    }
                                }
                                
                                // Reset states
                                isPressed = false
                                isDragging = false
                                dragPercentage = null
                            } else {
                                // B. User touched the track (outside the thumb) - wait for release to perform tap-to-jump
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    val clickY = down.position.y - (thumbHeightPx / 2)
                                    val targetPct = (clickY / trackHeightPx).coerceIn(0f, 1f)
                                    
                                    val info = gridState.layoutInfo
                                    val totalItems = info.totalItemsCount
                                    if (totalItems > 0) {
                                        val targetIndex = (targetPct * (totalItems - 1)).toInt().coerceIn(0, totalItems - 1)
                                        scrollJob?.cancel()
                                        scrollJob = coroutineScope.launch {
                                            gridState.scrollToItem(targetIndex)
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
                .offset(y = thumbOffsetDp) // Using offset modifier for 100% reliable layout updates
        )
    }
}
