package com.pixel.gallery.ui.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.home.PhotosScreen
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import com.pixel.gallery.ui.components.galleryContentEdge
import com.pixel.gallery.ui.components.MediaCollectionTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    onNavigateToViewer: (Long) -> Unit,
    onSort: () -> Unit = {},
    selectedIds: Set<Long> = emptySet(),
    onSelectionChange: (Set<Long>) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    items: List<GridItem> = emptyList(),
    gridState: LazyGridState = rememberLazyGridState(),
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val gridColumns by viewModel.gridColumns.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selectedIds.isEmpty()) {
                MediaCollectionTopBar("Recycle Bin", onBack, sortLabel = "Sort Bin", onSort = onSort)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .galleryContentEdge(),
        ) {
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Bin is Empty",
                    style = EmphasizedTypography.HeadlineMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Items in the bin will be permanently deleted after 30 days.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                PhotosScreen(
                    items = items,
                    onNavigateToViewer = onNavigateToViewer,
                    selectedIds = selectedIds,
                    onSelectionChange = onSelectionChange,
                    onToggleSelection = onToggleSelection,
                    columns = gridColumns,
                    onColumnsChange = { viewModel.setGridColumns(it) },
                    state = gridState
                )
            }
        }
        }
    }
}
