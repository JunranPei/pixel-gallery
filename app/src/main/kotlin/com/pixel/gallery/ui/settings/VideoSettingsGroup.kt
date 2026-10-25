package com.pixel.gallery.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.*
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.ui.theme.EmphasizedTypography

@Composable
fun VideoSettingsGroup(viewModel: VideoSettingsViewModel = hiltViewModel()) {
    val loaded by viewModel.settings.collectAsStateWithLifecycle()
    val settings = loaded ?: return
    val context = LocalContext.current
    var directoryError by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                directoryError = false
                viewModel.update { it.copy(captureTree = uri.toString()) }
            }.onFailure { directoryError = true }
        }
    }
    SettingsGroup(itemCount = 10, title = stringResource(R.string.video_settings_title)) { index ->
        when (index) {
            0 -> SettingsToggleItem(stringResource(R.string.video_autoplay), icon = Icons.Outlined.PlayCircle,
                checked = settings.autoplay, onCheckedChange = { value -> viewModel.update { it.copy(autoplay = value) } })
            1 -> SettingsToggleItem(stringResource(R.string.video_default_mute), icon = Icons.Outlined.VolumeOff,
                checked = settings.muted, onCheckedChange = { value -> viewModel.update { it.copy(muted = value) } })
            2 -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.video_end_action), style = EmphasizedTypography.LabelLarge)
                SettingsOptionControl(
                    options = listOf(VideoEndAction.STOP to stringResource(R.string.video_end_stop),
                        VideoEndAction.LOOP to stringResource(R.string.video_end_loop),
                        VideoEndAction.NEXT to stringResource(R.string.video_end_next)),
                    selectedMode = settings.endAction,
                    onSelected = { value -> viewModel.update { it.copy(endAction = value) } },
                )
            }
            3 -> SettingsToggleItem(stringResource(R.string.video_remember_position), icon = Icons.Outlined.History,
                checked = settings.rememberPosition, onCheckedChange = { value -> viewModel.update { it.copy(rememberPosition = value) } })
            4 -> SettingsToggleItem(stringResource(R.string.video_remember_speed),
                description = stringResource(R.string.video_remember_speed_description), icon = Icons.Outlined.Speed,
                checked = settings.rememberSpeed, onCheckedChange = { value -> viewModel.update { it.copy(rememberSpeed = value) } })
            in 5..7 -> ViewerPresentationSetting(index - 5, settings.screenDirection, settings.rememberDirection, settings.rememberZoom,
                onDirection = { value -> viewModel.update { it.copy(screenDirection = value) } },
                onRememberDirection = { value -> viewModel.update { it.copy(rememberDirection = value) } },
                onRememberZoom = { value -> viewModel.update { it.copy(rememberZoom = value) } })
            8 -> Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.video_background), style = EmphasizedTypography.LabelLarge)
                SettingsOptionControl(
                    options = listOf(VideoBackgroundAction.PAUSE to stringResource(R.string.video_background_pause),
                        VideoBackgroundAction.AUDIO to stringResource(R.string.video_background_audio),
                        VideoBackgroundAction.PIP to stringResource(R.string.video_background_pip)),
                    selectedMode = settings.backgroundAction,
                    onSelected = { value -> viewModel.update { it.copy(backgroundAction = value) } },
                )
            }
            9 -> Column {
                SettingsClickItem(stringResource(R.string.video_capture_directory),
                    description = if (settings.captureTree.isBlank()) "Pictures/Video captures" else
                        Uri.decode(settings.captureTree.substringAfterLast('/')),
                    icon = Icons.Outlined.Folder,
                    onClick = { picker.launch(settings.captureTree.takeIf(String::isNotBlank)?.let(Uri::parse)) })
                if (settings.captureTree.isNotBlank()) TextButton(
                    onClick = { viewModel.update { it.copy(captureTree = "") } },
                    modifier = Modifier.padding(horizontal = 14.dp),
                ) { Text(stringResource(R.string.video_capture_default_directory)) }
                if (directoryError) Text(stringResource(R.string.video_directory_error),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(14.dp))
            }
        }
    }
}
