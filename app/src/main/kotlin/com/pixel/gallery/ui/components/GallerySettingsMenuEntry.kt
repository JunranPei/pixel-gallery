package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R

val LocalGallerySettingsAction = staticCompositionLocalOf<(() -> Unit)?> { null }

internal val GalleryMenuGroupSpacing = 8.dp

@Composable
internal fun GallerySettingsMenuEntry(
    onDismissRequest: () -> Unit,
    onSettings: (() -> Unit)? = null,
) {
    val settingsAction = LocalGallerySettingsAction.current ?: onSettings
    Spacer(Modifier.height(GalleryMenuGroupSpacing))
    GalleryMenuRow(
        label = stringResource(R.string.settings),
        icon = Icons.Outlined.Settings,
        enabled = settingsAction != null,
        onClick = {
            onDismissRequest()
            settingsAction?.invoke()
        },
    )
}
