package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.VideoSettingsViewModel
import com.pixel.gallery.data.repository.ViewerScreenDirection
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.ui.theme.EmphasizedTypography

@Composable
fun ImageSettingsGroup(viewModel: VideoSettingsViewModel = hiltViewModel()) {
    val loaded by viewModel.imageSettings.collectAsStateWithLifecycle()
    val settings = loaded ?: return
    SettingsGroup(itemCount = 3, title = stringResource(R.string.image_settings_title)) { index ->
        ViewerPresentationSetting(index, settings.screenDirection, settings.rememberDirection, settings.rememberZoom,
            onDirection = { value -> viewModel.updateImage { it.copy(screenDirection = value) } },
            onRememberDirection = { value -> viewModel.updateImage { it.copy(rememberDirection = value) } },
            onRememberZoom = { value -> viewModel.updateImage { it.copy(rememberZoom = value) } })
    }
}

@Composable
internal fun ViewerPresentationSetting(
    index: Int, direction: ViewerScreenDirection, rememberDirection: Boolean, rememberZoom: Boolean,
    onDirection: (ViewerScreenDirection) -> Unit, onRememberDirection: (Boolean) -> Unit, onRememberZoom: (Boolean) -> Unit,
) {
    when (index) {
        0 -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.viewer_screen_direction), style = EmphasizedTypography.LabelLarge)
            val options = listOf(
                ViewerScreenDirection.SYSTEM to stringResource(R.string.viewer_direction_system),
                ViewerScreenDirection.LOCK_CURRENT to stringResource(R.string.viewer_direction_locked),
                ViewerScreenDirection.LANDSCAPE to stringResource(R.string.viewer_direction_landscape),
                ViewerScreenDirection.PORTRAIT to stringResource(R.string.viewer_direction_portrait))
            options.chunked(2).forEach { row -> SettingsOptionControl(row, direction, onDirection) }
            Text(stringResource(R.string.viewer_direction_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        1 -> SettingsToggleItem(stringResource(R.string.viewer_remember_direction),
            description = stringResource(R.string.viewer_remember_direction_description), icon = Icons.Outlined.ScreenRotation,
            checked = rememberDirection, onCheckedChange = onRememberDirection)
        2 -> SettingsToggleItem(stringResource(R.string.viewer_remember_zoom),
            description = stringResource(R.string.viewer_remember_zoom_description), icon = Icons.Outlined.ZoomIn,
            checked = rememberZoom, onCheckedChange = onRememberZoom)
    }
}
