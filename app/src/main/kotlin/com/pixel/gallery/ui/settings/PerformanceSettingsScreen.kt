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
                SettingsClickItem(
                    title = "Clear Cache",
                    description = "Clear all thumbnail cache directories and free up disk space.",
                    icon = Icons.Outlined.DeleteSweep,
                    onClick = {
                        viewModel.clearAllCaches(context) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Cache cleared successfully")
                            }
                        }
                    }
                )
            }
        }
    }
}
