package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.dp
import com.pixel.gallery.ui.theme.EmphasizedTypography
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.BuildConfig
import com.pixel.gallery.model.CloneMode
import com.pixel.gallery.ui.viewmodel.PhotosViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToExcludedFolders: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToShortcutManager: () -> Unit,
    onNavigateToPerformanceSettings: () -> Unit,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val materialYou by viewModel.materialYou.collectAsState()
    val startupAtAlbums by viewModel.startupAtAlbums.collectAsState()
    val cloneMode by viewModel.cloneMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Settings",
                        style = EmphasizedTypography.TitleLarge
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                SettingsToggleItem(
                    title = "Material You",
                    description = "Use system dynamic colors",
                    icon = Icons.Outlined.Palette,
                    checked = materialYou,
                    onCheckedChange = { viewModel.setMaterialYou(it) }
                )
            }
            item {
                CloneModeItem(
                    mode = cloneMode,
                    onModeChange = viewModel::setCloneMode,
                )
            }
            item {
                SettingsToggleItem(
                    title = "Start at Albums",
                    description = "Open the albums tab by default",
                    icon = Icons.Outlined.Tab,
                    checked = startupAtAlbums,
                    onCheckedChange = { viewModel.setStartupAtAlbums(it) }
                )
            }
            item {
                SettingsClickItem(
                    title = "Performance & Caching",
                    description = "Manage cache limit, threads and cleanup",
                    icon = Icons.Outlined.Speed,
                    onClick = onNavigateToPerformanceSettings
                )
            }
            item {
                SettingsClickItem(
                    title = "Desktop Shortcuts",
                    description = "Create and manage independent custom shortcuts",
                    icon = Icons.Outlined.Tab,
                    onClick = onNavigateToShortcutManager,
                )
            }

            item {
                SettingsClickItem(
                    title = "Excluded Folders",
                    description = "Manage ignored media locations",
                    icon = Icons.Outlined.FolderOff,
                    onClick = onNavigateToExcludedFolders
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            item {
                SettingsClickItem(
                    title = "About",
                    description = "Pixel Gallery v${BuildConfig.VERSION_NAME}",
                    icon = Icons.Outlined.Info,
                    onClick = onNavigateToLicenses
                )
            }
        }
    }
}

@Composable
private fun CloneModeItem(
    mode: CloneMode,
    onModeChange: (CloneMode) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text("Clone mode", style = EmphasizedTypography.LabelLarge)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (mode) {
                        CloneMode.DISABLED -> "Normal launch; no extra tasks"
                        CloneMode.MANUAL -> "Only custom shortcuts open separate tasks"
                        CloneMode.AUTO -> "External/document entries open separate tasks"
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CloneMode.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate == mode,
                            onClick = { onModeChange(candidate) },
                            label = {
                                Text(
                                    when (candidate) {
                                        CloneMode.DISABLED -> "Off"
                                        CloneMode.MANUAL -> "Manual"
                                        CloneMode.AUTO -> "Auto"
                                    }
                                )
                            },
                        )
                    }
                }
            }
        },
        leadingContent = {
            Icon(Icons.Outlined.Tab, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
    )
}

@Composable
fun SettingsToggleItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { 
            Text(
                title,
                style = EmphasizedTypography.LabelLarge
            ) 
        },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
fun SettingsClickItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { 
            Text(
                title,
                style = EmphasizedTypography.LabelLarge
            ) 
        },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
    )
}

@Composable
fun SettingsSliderItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableStateOf(value) }
    ListItem(
        headlineContent = { 
            Text(
                title,
                style = EmphasizedTypography.LabelLarge
            ) 
        },
        supportingContent = {
            Column {
                Text(description)
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onValueChangeFinished(sliderValue) },
                    valueRange = valueRange,
                    steps = steps,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
    )
}
