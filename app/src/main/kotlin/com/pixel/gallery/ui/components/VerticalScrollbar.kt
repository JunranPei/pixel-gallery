package com.pixel.gallery.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A custom interactive scrollbar for LazyVerticalGrid.
 * Optimized to prevent layout/recomposition overhead during scrolling.
 */
@Composable
fun VerticalScrollbar(
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Buffer the visibility check via derivedStateOf to prevent recomposition on every pixel of scroll
    val showScrollbar by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val totalItems = info.totalItemsCount
            val visibleItems = info.visibleItemsInfo.size
            totalItems > visibleItems && totalItems > 0
        }
    }

    if (!showScrollbar) return

    // Calculate scroll percentage using derivedStateOf to avoid recomposing the main body on scroll
    val scrollPercentage by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val totalItems = info.totalItemsCount
            val visibleItems = info.visibleItemsInfo.size
            val firstVisibleItemIndex = gridState.firstVisibleItemIndex
            if (totalItems == 0 || totalItems <= visibleItems) 0f
            else {
                val totalScrollableIcons = totalItems - visibleItems
                (firstVisibleItemIndex.toFloat() / maxOf(1, totalScrollableIcons)).coerceIn(0f, 1f)
            }
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    
    // Read state for alpha anim
    val alpha by animateFloatAsState(
        targetValue = if (isDragging || gridState.isScrollInProgress) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "scrollbar_alpha"
    )

    // Store measured height of the container to eliminate BoxWithConstraints subcomposition
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeight = 60.dp

    // Calculate translationY purely in a derivedStateOf so it's read only in the draw pass
    val thumbOffsetPx by remember {
        derivedStateOf {
            val thumbHeightPx = with(density) { thumbHeight.toPx() }
            val trackHeight = containerHeightPx - thumbHeightPx
            if (trackHeight <= 0f) 0f else scrollPercentage * trackHeight
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(32.dp)
            .graphicsLayer { this.alpha = alpha }
            .onSizeChanged { containerHeightPx = it.height.toFloat() }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(6.dp)
                .height(thumbHeight)
                .padding(end = 2.dp)
                .clip(CircleShape)
                .graphicsLayer {
                    // Defer state read to Draw pass (translationY) to bypass layout/recomposition entirely
                    translationY = thumbOffsetPx
                }
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                .pointerInput(containerHeightPx) {
                    val thumbHeightPx = thumbHeight.toPx()
                    val trackHeight = containerHeightPx - thumbHeightPx
                    if (trackHeight > 0) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val currentPercentage = scrollPercentage
                                val newScrollPercentage = (currentPercentage + dragAmount.y / trackHeight).coerceIn(0f, 1f)
                                val info = gridState.layoutInfo
                                val totalItems = info.totalItemsCount
                                val targetIndex = (newScrollPercentage * (totalItems - 1)).toInt().coerceIn(0, totalItems - 1)
                                coroutineScope.launch {
                                    gridState.scrollToItem(targetIndex)
                                }
                            }
                        )
                    }
                }
        )
    }
}
