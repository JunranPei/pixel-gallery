package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.dp
import com.pixel.gallery.ui.theme.EmphasizedTypography
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import com.pixel.gallery.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToDisplaySettings: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToPerformanceSettings: () -> Unit,
    onNavigateToLibrarySettings: () -> Unit,
) {
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
                .padding(innerPadding),
            contentPadding = SettingsLayout.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(SettingsLayout.GroupSpacing),
        ) {
            item {
                SettingsGroup(itemCount = 3) { index ->
                    when (index) {
                        0 -> {
                            SettingsClickItem(
                                title = "Display",
                                icon = Icons.Outlined.Tab,
                                onClick = onNavigateToDisplaySettings,
                            )
                        }
                        1 -> {
                            SettingsClickItem(
                                title = stringResource(R.string.library_settings_title),
                                icon = Icons.Outlined.PhotoLibrary,
                                onClick = onNavigateToLibrarySettings,
                            )
                        }
                        2 -> {
                            SettingsClickItem(
                                title = "Performance & Caching",
                                icon = Icons.Outlined.Speed,
                                onClick = onNavigateToPerformanceSettings,
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup(itemCount = 1) {
                    SettingsClickItem(
                        title = "About",
                        description = "Pixel Gallery v${BuildConfig.VERSION_NAME}",
                        icon = Icons.Outlined.Info,
                        onClick = onNavigateToLicenses,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { 
            Text(
                title,
                style = EmphasizedTypography.LabelLarge
            ) 
        },
        supportingContent = description?.let { { Text(it) } },
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
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable { onClick() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { 
            Text(
                title,
                style = EmphasizedTypography.LabelLarge
            ) 
        },
        supportingContent = description?.let { { Text(it) } },
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
                    track = { state -> SliderDefaults.Track(sliderState = state, drawStopIndicator = null) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
    )
}
