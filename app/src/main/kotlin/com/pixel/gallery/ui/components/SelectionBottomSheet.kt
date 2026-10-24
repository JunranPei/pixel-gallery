package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes

/** Shared half-page selection surface for Label, Move/Copy and Quick Sort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBottomSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    confirmDismiss: () -> Boolean = { true },
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || confirmDismiss() },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = ExpressiveShapes.ExtraLargeIncreased,
    ) {
        Column(
            // Size the content itself to half the window. A partially expanded tall
            // sheet otherwise places bottom actions below the visible viewport.
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp / 2).dp).imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title, style = EmphasizedTypography.TitleLarge)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}
