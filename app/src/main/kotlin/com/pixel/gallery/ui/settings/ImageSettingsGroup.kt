package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Restore
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

@Composable
fun ImageSettingsGroup(viewModel: VideoSettingsViewModel = hiltViewModel()) {
    val loaded by viewModel.imageSettings.collectAsStateWithLifecycle()
    val settings = loaded ?: return
    SettingsGroup(itemCount = 2, title = stringResource(R.string.image_settings_title)) { index ->
        ViewerPresentationSetting(index, settings.screenDirection, settings.rememberView,
            onDirection = { value -> viewModel.updateImage { it.copy(screenDirection = value) } },
            onRememberView = { value -> viewModel.updateImage { it.copy(rememberDirection = value, rememberZoom = value) } })
    }
}

@Composable
internal fun ViewerPresentationSetting(
    index: Int, direction: ViewerScreenDirection, rememberView: Boolean,
    onDirection: (ViewerScreenDirection) -> Unit, onRememberView: (Boolean) -> Unit,
) {
    when (index) {
        0 -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsOptionHeading(stringResource(R.string.viewer_screen_direction), Icons.Outlined.ScreenRotation)
            val options = listOf(
                ViewerScreenDirection.SYSTEM to stringResource(R.string.viewer_direction_system),
                ViewerScreenDirection.LOCK_CURRENT to stringResource(R.string.viewer_direction_locked),
                ViewerScreenDirection.LANDSCAPE to stringResource(R.string.viewer_direction_landscape),
                ViewerScreenDirection.PORTRAIT to stringResource(R.string.viewer_direction_portrait))
            options.chunked(2).forEach { row -> SettingsOptionControl(row, direction, onDirection) }
        }
        1 -> SettingsToggleItem(stringResource(R.string.viewer_remember_view), icon = Icons.Outlined.Restore,
            checked = rememberView, onCheckedChange = onRememberView)
    }
}
