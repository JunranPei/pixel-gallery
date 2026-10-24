package com.pixel.gallery.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared control for mutually exclusive options, with selection shown by shape and color. */
@Composable
fun <T> SettingsOptionControl(
    options: List<Pair<T, String>>,
    selectedMode: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
    unselectedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (mode, label) ->
            val selected = selectedMode == mode
            val shape = if (selected) CircleShape else RoundedCornerShape(if (compact) 8.dp else 10.dp)
            val containerColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary else unselectedContainerColor,
                label = "ThemeModeButtonColor",
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                label = "ThemeModeButtonContentColor",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (compact) 32.dp else 48.dp)
                    .clip(shape)
                    .background(containerColor, shape)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelected(mode) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}
