package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel.gallery.R
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.ui.viewmodel.PhotosViewModel

@Composable
internal fun QuickSortSettingsGroup(viewModel: PhotosViewModel) {
    val settings by viewModel.quickSortPreferences.collectAsStateWithLifecycle()
    SettingsGroup(itemCount = 1, title = stringResource(R.string.qs_start)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsOptionHeading(stringResource(R.string.qs_album_rows), Icons.Outlined.TableRows)
            SettingsOptionControl(
                options = (1..4).map { it to it.toString() },
                selectedMode = settings.albumRows,
                onSelected = viewModel::setQuickSortAlbumRows,
            )
        }
    }
}
