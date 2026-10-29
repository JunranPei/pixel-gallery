package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.MediaTypeFilter

@Composable
internal fun MediaTypeMenuSection(
    selected: MediaTypeFilter,
    onSelected: (MediaTypeFilter) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.media_type_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        SettingsOptionControl(
            options = listOf(
                MediaTypeFilter.ALL to stringResource(R.string.media_type_all),
                MediaTypeFilter.IMAGES to stringResource(R.string.media_type_images),
                MediaTypeFilter.VIDEOS to stringResource(R.string.media_type_videos),
            ),
            selectedMode = selected,
            onSelected = onSelected,
            compact = true,
        )
    }
}
