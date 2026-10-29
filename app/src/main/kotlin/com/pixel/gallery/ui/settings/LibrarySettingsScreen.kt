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
import com.pixel.gallery.ui.GalleryHeader
import com.pixel.gallery.ui.GalleryPage
import com.pixel.gallery.ui.GalleryPageContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(onBack: () -> Unit, onNavigateToExcludedFolders: () -> Unit): GalleryPageContent {
    return GalleryPage(modifier = Modifier.navigationBarsPadding(), topBar = { GalleryHeader(
        title = { Text(stringResource(R.string.library_settings_title), style = EmphasizedTypography.TitleLarge) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    ) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = SettingsLayout.ContentPadding,
            verticalArrangement = Arrangement.spacedBy(SettingsLayout.GroupSpacing)) {
            item {
                LabelBackupSettingsBlock(title = stringResource(R.string.settings_section_label_backup))
            }
            item {
                SettingsGroup(
                    itemCount = 1,
                    title = stringResource(R.string.settings_section_exclude_folders),
                ) {
                    SettingsClickItem(title = "Excluded Folders", icon = Icons.Outlined.FolderOff,
                        onClick = onNavigateToExcludedFolders)
                }
            }
        }
    }
}
