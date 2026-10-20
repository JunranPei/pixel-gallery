package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import com.pixel.gallery.ui.theme.EmphasizedTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(onBack: () -> Unit, onNavigateToExcludedFolders: () -> Unit) {
    Scaffold(topBar = { TopAppBar(
        title = { Text(stringResource(R.string.library_settings_title), style = EmphasizedTypography.TitleLarge) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    ) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = SettingsLayout.ContentPadding,
            // One group: large outer corners, small inner corners and a 4dp gap.
            verticalArrangement = Arrangement.spacedBy(SettingsLayout.GroupSpacing)) {
            item {
                SettingsGroup(itemCount = 2) { index ->
                    when (index) {
                        0 -> LabelBackupSettingsBlock()
                        1 -> SettingsClickItem(title = "Excluded Folders", icon = Icons.Outlined.FolderOff,
                            onClick = onNavigateToExcludedFolders)
                    }
                }
            }
        }
    }
}
