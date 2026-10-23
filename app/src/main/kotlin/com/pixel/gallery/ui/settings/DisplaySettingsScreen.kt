package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.model.Handedness
import com.pixel.gallery.R

@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    onNavigateToThemeSettings: () -> Unit,
    viewModel: PhotosViewModel = hiltViewModel(),
) {
    val startupAtAlbums by viewModel.startupAtAlbums.collectAsStateWithLifecycle()
    val handedness by viewModel.handedness.collectAsStateWithLifecycle()
    val showFullAlbumNames by viewModel.showFullAlbumNames.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Display", style = EmphasizedTypography.TitleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = SettingsLayout.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(SettingsLayout.GroupSpacing),
        ) {
            item {
                SettingsGroup(itemCount = 4) { index ->
                    when (index) {
                        0 -> {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.PanTool, null, tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 12.dp).size(32.dp))
                                    Column {
                                        Text(stringResource(R.string.display_handedness),
                                            style = EmphasizedTypography.LabelLarge.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                                SettingsOptionControl(
                                    options = listOf(Handedness.RIGHT to stringResource(R.string.display_right_handed),
                                        Handedness.LEFT to stringResource(R.string.display_left_handed)),
                                    selectedMode = handedness,
                                    onSelected = viewModel::setHandedness,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        1 -> {
                            SettingsToggleItem(
                                title = "Start at Albums",
                                icon = Icons.Outlined.Tab,
                                checked = startupAtAlbums,
                                onCheckedChange = viewModel::setStartupAtAlbums,
                            )
                        }
                        2 -> {
                            SettingsToggleItem(
                                title = stringResource(R.string.display_show_full_album_names),
                                icon = Icons.Outlined.TextFields,
                                checked = showFullAlbumNames,
                                onCheckedChange = viewModel::setShowFullAlbumNames,
                            )
                        }
                        3 -> {
                            SettingsClickItem(
                                title = "Themes",
                                icon = Icons.Outlined.Palette,
                                onClick = onNavigateToThemeSettings,
                            )
                        }
                    }
                }
            }
        }
    }
}
