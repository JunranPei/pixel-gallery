package com.pixel.gallery.ui.settings
 
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.BuildConfig
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
    val largeImageColdTestMode by viewModel.largeImageColdTestMode.collectAsState()

    var showClearCacheConfirm by remember { mutableStateOf(false) }
 
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear Cache") },
            text = { Text("Clear thumbnail and large-image disk caches? They will be rebuilt as needed.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCacheConfirm = false
                        viewModel.clearAllCaches(context) { success ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (success) "Cache cleared" else "Some cache files could not be cleared"
                                )
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

    val pageColor = MaterialTheme.colorScheme.surface

    Scaffold(
        containerColor = pageColor,
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pageColor,
                    scrolledContainerColor = pageColor
                )
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
                    description = "Controls source-image decoding threads. Lower values save battery; higher values load faster. Restart the app after changing it.",
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
                SettingsClickItem(
                    title = "Clear Cache",
                    description = "Clear thumbnail and large-image disk caches.",
                    icon = Icons.Outlined.DeleteSweep,
                    onClick = { showClearCacheConfirm = true }
                )
            }
            if (BuildConfig.VIEWER_METRICS_ENABLED) {
                item {
                    SettingsToggleItem(
                        title = "Large-image Cold Test",
                        description = "Disable preview and tile caches; reclaim JPEG/PNG file pages before decoding.",
                        icon = Icons.Outlined.Science,
                        checked = largeImageColdTestMode,
                        onCheckedChange = viewModel::setLargeImageColdTestMode,
                    )
                }
            }
        }
    }
}
