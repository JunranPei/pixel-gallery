package com.pixel.gallery.ui.settings

import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.ConfirmationReminder
import com.pixel.gallery.ui.components.ConfirmationFact
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.FileDownload
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.BuildConfig
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.components.LocalGalleryMessages
import com.pixel.gallery.ui.GalleryHeader
import com.pixel.gallery.ui.GalleryPage
import com.pixel.gallery.ui.GalleryPageContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: PhotosViewModel = hiltViewModel(),
): GalleryPageContent {
    val context = LocalContext.current
    val messages = LocalGalleryMessages.current
    val glideThreadCount by viewModel.glideThreadCount.collectAsState()
    val glideCacheSize by viewModel.glideCacheSize.collectAsState()
    val glidePersistentGridCacheSize by viewModel.glidePersistentGridCacheSize.collectAsState()
    val largeImageColdTestMode by viewModel.largeImageColdTestMode.collectAsState()
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                com.pixel.gallery.utils.AppDiagnostics.export(context, uri)
            } }
            messages.show(context.getString(if (result.isSuccess) R.string.diagnostics_saved else R.string.diagnostics_failed))
        }
    }

    return GalleryPage(
        modifier = Modifier.navigationBarsPadding(),
        topBar = {
            GalleryHeader(
                title = { Text("Performance & Caching", style = EmphasizedTypography.TitleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (showClearCacheConfirm) {
            GalleryDialog(
                onDismissRequest = { showClearCacheConfirm = false },
                title = { Text(stringResource(R.string.dialog_cache_title)) },
                icon = Icons.Outlined.DeleteSweep,
                text = {
                    ConfirmationReminder(stringResource(R.string.dialog_cache_body), Icons.Outlined.DeleteSweep)
                    ConfirmationReminder(stringResource(R.string.dialog_cache_decode))
                },
                confirmButton = {
                    Button(onClick = {
                        showClearCacheConfirm = false
                        viewModel.clearAllCaches(context) { success ->
                            messages.show(if (success) "Cache cleared" else "Some cache files could not be cleared")
                        }
                    }) { Text(stringResource(R.string.dialog_clear)) }
                },
                dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = SettingsLayout.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(SettingsLayout.GroupSpacing),
        ) {
            if (BuildConfig.VIEWER_METRICS_ENABLED) item {
                SettingsGroup(itemCount = 1) {
                    SettingsToggleItem(
                        title = "Large-image Cold Test",
                        icon = Icons.Outlined.Speed,
                        checked = largeImageColdTestMode,
                        onCheckedChange = viewModel::setLargeImageColdTestMode,
                    )
                }
            }
            item {
                SettingsGroup(
                    itemCount = 3,
                    title = stringResource(R.string.settings_section_performance),
                ) { index ->
                    when (index) {
                        0 -> {
                            SettingsSliderItem(
                                title = "Image Decoding Threads: $glideThreadCount",
                                description = "Requires app restart.",
                                icon = Icons.Outlined.Speed,
                                value = glideThreadCount.toFloat(),
                                valueRange = 1f..8f,
                                steps = 6,
                                onValueChangeFinished = { viewModel.setGlideThreadCount(it.toInt()) },
                            )
                        }
                        1 -> {
                            SettingsSliderItem(
                                title = "Flowing Cache Limit: $glideCacheSize MB",
                                description = "Requires app restart.",
                                icon = Icons.Outlined.Storage,
                                value = glideCacheSize.toFloat(),
                                valueRange = 100f..2000f,
                                steps = 18,
                                onValueChangeFinished = { viewModel.setGlideCacheSize(it.toInt()) },
                            )
                        }
                        2 -> {
                            SettingsSliderItem(
                                title = "Persistent Grid Limit: $glidePersistentGridCacheSize MB",
                                description = "Thumbnails for files over 5 MB.",
                                icon = Icons.Outlined.Storage,
                                value = glidePersistentGridCacheSize.toFloat(),
                                valueRange = 50f..2000f,
                                steps = 38,
                                onValueChangeFinished = { viewModel.setGlidePersistentGridCacheSize(it.toInt()) },
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup(
                    itemCount = 1,
                    title = stringResource(R.string.settings_section_cache),
                ) {
                    SettingsClickItem(
                        title = "Clear Cache",
                        icon = Icons.Outlined.DeleteSweep,
                        onClick = { showClearCacheConfirm = true },
                    )
                }
            }
            item {
                SettingsGroup(itemCount = 1) {
                    SettingsClickItem(title = stringResource(R.string.diagnostics_export),
                        icon = Icons.Outlined.FileDownload,
                        onClick = { exportDiagnostics.launch("gallery-diagnostics.zip") })
                }
            }
        }
    }
}
