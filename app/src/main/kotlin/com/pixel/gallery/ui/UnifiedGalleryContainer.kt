package com.pixel.gallery.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets

/**
 * The single chrome/body owner for the gallery surface.
 *
 * The constructor is private and the only instance is exposed by [instance].
 * Every gallery route is rendered through this same shell; route changes update
 * its content and state instead of creating another top-level Scaffold.
 */
class UnifiedGalleryContainer private constructor() {
    companion object {
        val instance: UnifiedGalleryContainer = UnifiedGalleryContainer()
    }

    @Composable
    fun Shell(
        modifier: Modifier = Modifier,
        containerColor: Color,
        topBar: @Composable () -> Unit,
        snackbarHost: @Composable () -> Unit,
        content: @Composable (PaddingValues) -> Unit,
    ) {
        Scaffold(
            modifier = modifier,
            containerColor = containerColor,
            contentWindowInsets = WindowInsets(0),
            topBar = topBar,
            snackbarHost = snackbarHost,
            content = content,
        )
    }
}
