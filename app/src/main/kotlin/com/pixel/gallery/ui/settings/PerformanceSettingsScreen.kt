package com.pixel.gallery.ui.settings
 
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.launch
 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
 
    val glideThreadCount by viewModel.glideThreadCount.collectAsState()
    val glideCacheSize by viewModel.glideCacheSize.collectAsState()
    val glidePersistentGridCacheSize by viewModel.glidePersistentGridCacheSize.collectAsState()
    val glidePersistentViewerCacheSize by viewModel.glidePersistentViewerCacheSize.collectAsState()
 
    val largeImageTileSize by viewModel.largeImageTileSize.collectAsState()
    val largeImageMaxCores by viewModel.largeImageMaxCores.collectAsState()
    val largeImageDebounceMs by viewModel.largeImageDebounceMs.collectAsState()
    val largeImageHardwareBitmap by viewModel.largeImageHardwareBitmap.collectAsState()

    var showClearCacheConfirm by remember { mutableStateOf(false) }
 
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear Cache") },
            text = { Text("Are you sure you want to clear all thumbnail cache directories and free up disk space? Thumbnails will need to be re-decoded next time.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheConfirm = false
                        viewModel.clearAllCaches(context) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Cache cleared successfully")
                            }
                        }
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Performance & Caching",
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
                SettingsSliderItem(
                    title = "Persistent Grid Limit: $glidePersistentGridCacheSize MB",
                    description = "Limits the maximum space for persistent thumbnails of heavy Grid files (>5MB) to avoid re-decoding. Managed independently.",
                    icon = Icons.Outlined.Storage,
                    value = glidePersistentGridCacheSize.toFloat(),
                    valueRange = 50f..2000f,
                    steps = 38,
                    onValueChangeFinished = { viewModel.setGlidePersistentGridCacheSize(it.toInt()) }
                )
            }
            item {
                SettingsSliderItem(
                    title = "Persistent Viewer Limit: $glidePersistentViewerCacheSize MB",
                    description = "Limits the maximum space for persistent thumbnails of heavy Viewer files (>5MB) to avoid re-decoding. Managed independently.",
                    icon = Icons.Outlined.Storage,
                    value = glidePersistentViewerCacheSize.toFloat(),
                    valueRange = 100f..2000f,
                    steps = 18,
                    onValueChangeFinished = { viewModel.setGlidePersistentViewerCacheSize(it.toInt()) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                val maxProcessors = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
                SettingsSliderItem(
                    title = "Large Image Decoder Threads: $largeImageMaxCores",
                    description = "Limits maximum CPU threads/cores used for decoding ultra-large images. Lower values save battery; higher values render tiles faster.",
                    icon = Icons.Outlined.Speed,
                    value = largeImageMaxCores.toFloat(),
                    valueRange = 1f..maxProcessors.toFloat(),
                    steps = (maxProcessors - 2).coerceAtLeast(0),
                    onValueChangeFinished = { viewModel.setLargeImageMaxCores(it.toInt()) }
                )
            }
            item {
                val tileSliderValue = when (largeImageTileSize) {
                    512 -> 1f
                    2048 -> 3f
                    else -> 2f
                }
                SettingsSliderItem(
                    title = "Large Image Tile Size: ${when(largeImageTileSize) { 512 -> "512px (Lower RAM)"; 2048 -> "2048px (Lower CPU)"; else -> "1024px (Balanced)" }}",
                    description = "Larger tiles reduce decoding frequency during drag but consume more memory. Recommended: 1024px.",
                    icon = Icons.Outlined.Storage,
                    value = tileSliderValue,
                    valueRange = 1f..3f,
                    steps = 1,
                    onValueChangeFinished = {
                        val newSize = when (it.toInt()) {
                            1 -> 512
                            3 -> 2048
                            else -> 1024
                        }
                        viewModel.setLargeImageTileSize(newSize)
                    }
                )
            }
            item {
                SettingsSliderItem(
                    title = "Gestures Throttling Delay: ${if (largeImageDebounceMs == 0) "Disabled" else "${largeImageDebounceMs}ms"}",
                    description = "Delays full-resolution tile decoding during swipe and zoom gestures to prevent CPU spikes. Recommended: 150ms.",
                    icon = Icons.Outlined.Speed,
                    value = largeImageDebounceMs.toFloat(),
                    valueRange = 0f..400f,
                    steps = 7,
                    onValueChangeFinished = { viewModel.setLargeImageDebounceMs(it.toInt()) }
                )
            }
            item {
                SettingsToggleItem(
                    title = "Experimental: Hardware Bitmaps",
                    description = "Decodes tiles directly into GPU texture memory (Zero-Copy) to reduce system bus power. May cause rendering issues on older systems.",
                    icon = Icons.Outlined.Speed,
                    checked = largeImageHardwareBitmap,
                    onCheckedChange = { viewModel.setLargeImageHardwareBitmap(it) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                SettingsClickItem(
                    title = "Clear Cache",
                    description = "Clear all thumbnail cache directories and free up disk space.",
                    icon = Icons.Outlined.DeleteSweep,
                    onClick = { showClearCacheConfirm = true }
                )
            }
        }
    }
}
