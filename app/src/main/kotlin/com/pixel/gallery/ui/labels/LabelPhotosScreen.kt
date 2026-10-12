package com.pixel.gallery.ui.labels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.ui.home.PhotosScreen
import com.pixel.gallery.ui.quicksort.QuickSortUiConfig
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.ui.components.GalleryOverflowMenu
import com.pixel.gallery.ui.components.GalleryMenuRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelPhotosScreen(
    label: LabelEntry?, items: List<GridItem>, selectedIds: Set<Long>, columns: Int,
    gridState: LazyGridState, onBack: () -> Unit, onNavigateToViewer: (Long) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit, onToggleSelection: (Long) -> Unit,
    onColumnsChange: (Int) -> Unit, quickSort: QuickSortUiConfig?,
    onToggleQuickSort: () -> Unit, onQueueClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        if (selectedIds.isEmpty()) TopAppBar(
            title = { Text(label?.let { labelDisplayName(it) } ?: stringResource(R.string.labels_title), style = EmphasizedTypography.TitleLarge) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back)) } },
            actions = {
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                    GalleryOverflowMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (quickSort == null) GalleryMenuRow(
                            label = stringResource(R.string.qs_start),
                            icon = Icons.Outlined.DriveFileMove,
                            accented = true,
                            onClick = { showMenu = false; onToggleQuickSort() },
                        )
                    }
                }
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PhotosScreen(items = items, onNavigateToViewer = onNavigateToViewer, selectedIds = selectedIds,
                onSelectionChange = onSelectionChange, onToggleSelection = onToggleSelection,
                columns = columns, onColumnsChange = onColumnsChange, state = gridState,
                bottomPadding = if (quickSort != null) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp,
                emptyMessage = stringResource(R.string.labels_empty_contents), quickSort = quickSort)
        }
    }
}
