package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    SettingsGroup(itemCount = 2, title = title) { index ->
        when (index) {
            0 -> {
                Column(
                    modifier = Modifier.padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                SettingsToggleItem(
                    title = "Material You",
                    description = "Use system dynamic colors",
                    icon = Icons.Outlined.Palette,
                    checked = materialYou,
                    onCheckedChange = viewModel::setMaterialYou,
                )
            }
        }
    }
}
