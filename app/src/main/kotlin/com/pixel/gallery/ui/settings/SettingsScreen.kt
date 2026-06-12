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
import com.pixel.gallery.ui.viewmodel.PhotosViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToExcludedFolders: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToShortcutManager: () -> Unit,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val materialYou by viewModel.materialYou.collectAsState()
    val startupAtAlbums by viewModel.startupAtAlbums.collectAsState()

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
                SettingsToggleItem(
                    title = "Start at Albums",
                    description = "Open the albums tab by default",
                    icon = Icons.Outlined.Tab,
                    checked = startupAtAlbums,
                    onCheckedChange = { viewModel.setStartupAtAlbums(it) }
                )
            }
            item {
                val glideThreadCount by viewModel.glideThreadCount.collectAsState()
                SettingsSliderItem(
                    title = "Image Decoding Threads: $glideThreadCount",
                    description = "Limits background CPU threads for decoding thumbnails. Lower values save battery; higher values load thumbnails faster.",
                    icon = Icons.Outlined.Speed,
                    value = glideThreadCount.toFloat(),
                    valueRange = 1f..8f,
                    steps = 6,
                    onValueChangeFinished = { viewModel.setGlideThreadCount(it.toInt()) }
                )
            }
            item {
                val glideCacheSize by viewModel.glideCacheSize.collectAsState()
                SettingsSliderItem(
                    title = "Flowing Cache Limit: $glideCacheSize MB",
                    description = "Limits the maximum disk space used by Glide's standard LRU cache (for smaller files). Requires app restart to take effect.",
                    icon = Icons.Outlined.Storage,
                    value = glideCacheSize.toFloat(),
                    valueRange = 100f..2000f,
                    steps = 18,
                    onValueChangeFinished = { viewModel.setGlideCacheSize(it.toInt()) }
                )
            }
            item {
                val glidePersistentCacheSize by viewModel.glidePersistentCacheSize.collectAsState()
                SettingsSliderItem(
                    title = "Persistent Cache Limit: $glidePersistentCacheSize MB",
                    description = "Limits the maximum space for persistent thumbnails of heavy files (>5MB) to avoid re-decoding. Managed independently.",
                    icon = Icons.Outlined.Storage,
                    value = glidePersistentCacheSize.toFloat(),
                    valueRange = 100f..1000f,
                    steps = 8,
                    onValueChangeFinished = { viewModel.setGlidePersistentCacheSize(it.toInt()) }
                )
            }
            item {
                SettingsClickItem(
                    title = "Desktop Shortcuts",
                    description = "Create and manage independent custom shortcuts",
                    icon = Icons.Outlined.Tab,
                    onClick = onNavigateToShortcutManager
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
                    description = "Pixel Gallery v4.0.0",
                    icon = Icons.Outlined.Info,
                    onClick = onNavigateToLicenses
                )
            }
        }
    }
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
