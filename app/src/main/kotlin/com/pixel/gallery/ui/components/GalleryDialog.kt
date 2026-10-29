package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Family 6: one quiet surface, an inline icon/title, and a fixed action row. */
@Composable
fun GalleryDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    icon: ImageVector = Icons.Outlined.Info,
    destructive: Boolean = false,
    dismissEnabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = dismissEnabled,
            dismissOnClickOutside = dismissEnabled,
        ),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp),
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = if (destructive) colors.errorContainer else colors.primaryContainer,
                        contentColor = if (destructive) colors.onErrorContainer else colors.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
                    }
                    Box(Modifier.weight(1f)) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = colors.onSurfaceVariant, lineHeight = 21.sp)) { text() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton()
                    Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

/** Variant A: unboxed, small outline icon followed by a short paragraph. */
@Composable
fun ConfirmationReminder(message: String, icon: ImageVector = Icons.Outlined.Info) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmationFact(label: String, value: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
