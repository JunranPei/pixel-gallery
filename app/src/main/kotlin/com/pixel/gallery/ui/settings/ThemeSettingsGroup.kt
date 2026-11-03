package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.*
import com.pixel.gallery.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel.gallery.model.ThemeMode
import com.pixel.gallery.ui.components.SettingsOptionControl
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel

@Composable
fun ThemeSettingsGroup(
    viewModel: PhotosViewModel = hiltViewModel(),
    title: String? = null,
) {
    val materialYou by viewModel.materialYou.collectAsStateWithLifecycle()
    val manualColor by viewModel.manualColor.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    SettingsGroup(itemCount = if (materialYou) 2 else 3, title = title) { index ->
        when (index) {
            0 -> {
                Column(
                    modifier = Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp).size(32.dp),
                        )
                        Text(
                            "Theme mode",
                            style = EmphasizedTypography.LabelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    SettingsOptionControl(
                        options = listOf(
                            ThemeMode.SYSTEM to "System",
                            ThemeMode.LIGHT to "Light",
                            ThemeMode.DARK to "Dark",
                        ),
                        selectedMode = themeMode,
                        onSelected = viewModel::setThemeMode,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            1 -> {
                Column {
                    Text(stringResource(R.string.theme_color_title), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp))
                    SettingsToggleItem(
                        title = stringResource(R.string.theme_color_auto), icon = Icons.Outlined.Palette,
                        checked = materialYou, onCheckedChange = viewModel::setMaterialYou,
                    )
                }
            }
            2 -> ThemeHuePicker(manualColor, viewModel::setManualColor)
        }
    }
}
