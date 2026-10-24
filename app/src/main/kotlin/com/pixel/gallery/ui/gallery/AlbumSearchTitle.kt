package com.pixel.gallery.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SearchSwipeDistanceFraction = 0.2f
private const val SearchSwipeVelocityInWidthsPerSecond = 1.25f

private data class SearchHeaderState(
    val isSearchMode: Boolean = false,
    val dragOffsetPx: Float = 0f,
)

internal fun shouldSwitchPhotoHeader(
    dragOffsetPx: Float,
    widthPx: Float,
    velocityPxPerSecond: Float,
): Boolean {
    if (widthPx <= 0f) return false
    return abs(dragOffsetPx) / widthPx >= SearchSwipeDistanceFraction ||
        abs(velocityPxPerSecond) / widthPx >= SearchSwipeVelocityInWidthsPerSecond
}

/** The shared title/search transition used by filesystem and label media grids. */
@Composable
internal fun AlbumSearchTitle(
    isSearchMode: Boolean,
    onSearchModeChange: (Boolean) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    title: @Composable () -> Unit,
    searchPlaceholder: String,
    clearSearchDescription: String,
    focusRequested: Boolean,
    onFocusRequestConsumed: () -> Unit,
    backEnabled: Boolean = true,
) {
    var headerState by remember(isSearchMode) { mutableStateOf(SearchHeaderState(isSearchMode = isSearchMode)) }
    var isAnimating by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val dragState = rememberDraggableState { delta ->
        if (!isAnimating && widthPx > 0f) {
            headerState = headerState.copy(
                dragOffsetPx = (headerState.dragOffsetPx + delta).coerceIn(-widthPx, widthPx),
            )
        }
    }

    LaunchedEffect(isSearchMode, headerState.isSearchMode, focusRequested, backEnabled) {
        if (isSearchMode && headerState.isSearchMode && focusRequested && backEnabled) {
            focusRequester.requestFocus()
        }
    }

    fun changeSearchMode(nextMode: Boolean) {
        if (!nextMode) {
            focusManager.clearFocus(force = true)
            if (searchQuery.isNotEmpty()) onSearchQueryChange("")
        }
        onSearchModeChange(nextMode)
    }

    BackHandler(enabled = backEnabled && isSearchMode) {
        changeSearchMode(false)
    }

    val dragOffsetPx = headerState.dragOffsetPx
    val dragDirection = if (dragOffsetPx < 0f) -1f else 1f
    val nextPageOffsetPx = dragOffsetPx - dragDirection * widthPx
    val titleOffsetPx = if (headerState.isSearchMode) nextPageOffsetPx else dragOffsetPx
    val searchOffsetPx = if (headerState.isSearchMode) dragOffsetPx else nextPageOffsetPx

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clipToBounds()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = !isAnimating,
                onDragStopped = { velocity ->
                    if (widthPx <= 0f || headerState.dragOffsetPx == 0f) {
                        headerState = headerState.copy(dragOffsetPx = 0f)
                        return@draggable
                    }
                    val startingState = headerState
                    val switchPage = shouldSwitchPhotoHeader(startingState.dragOffsetPx, widthPx, velocity)
                    val direction = if (startingState.dragOffsetPx > 0f) 1f else -1f
                    val targetOffset = if (switchPage) direction * widthPx else 0f
                    isAnimating = true
                    try {
                        Animatable(startingState.dragOffsetPx).animateTo(targetOffset, tween(180)) {
                            headerState = startingState.copy(dragOffsetPx = value)
                        }
                        val nextMode = if (switchPage) !startingState.isSearchMode else startingState.isSearchMode
                        headerState = SearchHeaderState(isSearchMode = nextMode)
                        if (switchPage) changeSearchMode(nextMode)
                    } finally {
                        isAnimating = false
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!headerState.isSearchMode || dragOffsetPx != 0f) {
            Box(
                modifier = Modifier.offset { IntOffset(titleOffsetPx.roundToInt(), 0) }.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { title() }
        }
        if (headerState.isSearchMode || dragOffsetPx != 0f) {
            Box(
                modifier = Modifier.offset { IntOffset(searchOffsetPx.roundToInt(), 0) }.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxSize().focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused && focusRequested) onFocusRequestConsumed()
                        },
                    placeholder = { androidx.compose.material3.Text(searchPlaceholder) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, clearSearchDescription)
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                )
            }
        }
    }
}
