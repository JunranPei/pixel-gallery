package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel.gallery.R
import com.pixel.gallery.model.ViewerOpeningEffect
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.ui.viewmodel.PhotosViewModel

@Composable
internal fun AnimationSettingsGroup(viewModel: PhotosViewModel) {
    val selected by viewModel.viewerOpeningEffect.collectAsStateWithLifecycle()
    SettingsGroup(itemCount = 1, title = stringResource(R.string.settings_section_animation)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsOptionHeading(stringResource(R.string.viewer_opening_effect), Icons.Outlined.OpenInFull)
            SettingsOptionControl(
                options = listOf(
                    ViewerOpeningEffect.NONE to stringResource(R.string.viewer_opening_none),
                    ViewerOpeningEffect.THUMBNAIL to stringResource(R.string.viewer_opening_thumbnail),
                    ViewerOpeningEffect.SCALE_FADE to stringResource(R.string.viewer_opening_scale_fade),
                ),
                selectedMode = selected ?: ViewerOpeningEffect.NONE,
                onSelected = viewModel::setViewerOpeningEffect,
                enabled = selected != null,
            )
        }
    }
}
