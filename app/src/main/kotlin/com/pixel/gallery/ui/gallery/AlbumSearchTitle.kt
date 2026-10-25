package com.pixel.gallery.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.pixel.gallery.ui.GallerySearchSpec
import com.pixel.gallery.ui.rememberGallerySearch
import kotlin.math.abs

private const val SearchSwipeDistanceFraction = 0.2f
private const val SearchSwipeVelocityInWidthsPerSecond = 1.25f

internal fun shouldSwitchPhotoHeader(
    dragOffsetPx: Float,
    widthPx: Float,
    velocityPxPerSecond: Float,
): Boolean {
    if (widthPx <= 0f) return false
    return abs(dragOffsetPx) / widthPx >= SearchSwipeDistanceFraction ||
        abs(velocityPxPerSecond) / widthPx >= SearchSwipeVelocityInWidthsPerSecond
}

/** Supplies search content to the one root toolbar without constructing a bar. */
@Composable
internal fun rememberAlbumSearch(
    isSearchMode: Boolean,
    onSearchModeChange: (Boolean) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    clearSearchDescription: String,
    focusRequested: Boolean,
    onFocusRequestConsumed: () -> Unit,
    backEnabled: Boolean = true,
): GallerySearchSpec {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun changeSearchMode(nextMode: Boolean) {
        if (!nextMode) {
            focusManager.clearFocus(force = true)
            if (searchQuery.isNotEmpty()) onSearchQueryChange("")
        }
        onSearchModeChange(nextMode)
    }

    return rememberGallerySearch(
        isSearchMode = isSearchMode,
        onSearchModeChange = ::changeSearchMode,
        enabled = backEnabled,
    ) {
        LaunchedEffect(isSearchMode, focusRequested, backEnabled) {
            if (isSearchMode && focusRequested && backEnabled) focusRequester.requestFocus()
        }
        BackHandler(enabled = backEnabled && isSearchMode) { changeSearchMode(false) }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth().height(56.dp).focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused && focusRequested) onFocusRequestConsumed()
                },
            placeholder = { Text(searchPlaceholder) },
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
