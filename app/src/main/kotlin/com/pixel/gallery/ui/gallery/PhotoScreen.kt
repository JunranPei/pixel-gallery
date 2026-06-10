package com.pixel.gallery.ui.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.home.PhotosScreen
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.filled.Sort
import com.pixel.gallery.ui.viewmodel.PhotoSortOrder
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import com.pixel.gallery.ui.components.SortDialog
import com.pixel.gallery.ui.components.SortCriterion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(
    albumName: String,
    onBack: () -> Unit,
    onNavigateToViewer: (Long) -> Unit,
    selectedIds: Set<Long> = emptySet(),
    onSelectionChange: (Set<Long>) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    gridState: LazyGridState = rememberLazyGridState(),
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val allPhotos by viewModel.allPhotos.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val photoSortOrder by viewModel.photoSortOrder.collectAsState()
    var showSortDialog by remember { mutableStateOf(false) }
    
    val albumItems = remember(allPhotos, albumName, gridColumns, photoSortOrder) {
        val filtered = allPhotos.filter { 
            val file = java.io.File(it.path)
            file.parentFile?.name == albumName
        }
        val sorted = when (photoSortOrder) {
            PhotoSortOrder.DATE_DESC -> filtered.sortedByDescending { it.bestTimestamp }
            PhotoSortOrder.DATE_ASC -> filtered.sortedBy { it.bestTimestamp }
            PhotoSortOrder.NAME_ASC -> filtered.sortedBy { java.io.File(it.path).name }
            PhotoSortOrder.NAME_DESC -> filtered.sortedByDescending { java.io.File(it.path).name }
            PhotoSortOrder.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
            PhotoSortOrder.SIZE_ASC -> filtered.sortedBy { it.sizeBytes }
        }
        viewModel.groupMedia(sorted, gridColumns, photoSortOrder)
    }

    val photoCount = remember(albumItems) {
        albumItems.count { it is GridItem.Photo }
    }

    var showMenu by remember { mutableStateOf(false) }
    val albumPath = remember(allPhotos, albumName) {
        allPhotos.find { 
            java.io.File(it.path).parentFile?.name == albumName 
        }?.let { java.io.File(it.path).parent } ?: ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selectedIds.isEmpty()) {
                TopAppBar(
                    title = { 
                        Column {
                            Text(
                                albumName,
                                style = EmphasizedTypography.TitleLarge
                            )
                            Text(
                                "$photoCount items",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sort Photos") },
                                onClick = {
                                    showMenu = false
                                    showSortDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Hide Album") },
                                onClick = {
                                    showMenu = false
                                    if (albumPath.isNotEmpty()) {
                                        viewModel.addHiddenFolder(albumPath)
                                        onBack()
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Exclude Album") },
                                onClick = {
                                    showMenu = false
                                    if (albumPath.isNotEmpty()) {
                                        viewModel.addExcludedFolder(albumPath)
                                        onBack()
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) }
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {


            PhotosScreen(
                items = albumItems,
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

    val photoCriteria = remember {
            listOf(
                SortCriterion("DATE", "Date", "Oldest first", "Newest first"),
                SortCriterion("NAME", "Name", "A to Z", "Z to A"),
                SortCriterion("SIZE", "Size", "Smallest first", "Largest first")
            )
        }

        AnimatedVisibility(
            visible = showSortDialog,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150))
        ) {
            val currentCriterion = when (photoSortOrder) {
                PhotoSortOrder.DATE_DESC, PhotoSortOrder.DATE_ASC -> "DATE"
                PhotoSortOrder.NAME_DESC, PhotoSortOrder.NAME_ASC -> "NAME"
                PhotoSortOrder.SIZE_DESC, PhotoSortOrder.SIZE_ASC -> "SIZE"
            }
            val currentDirection = when (photoSortOrder) {
                PhotoSortOrder.DATE_DESC, PhotoSortOrder.NAME_DESC, PhotoSortOrder.SIZE_DESC -> "DESC"
                PhotoSortOrder.DATE_ASC, PhotoSortOrder.NAME_ASC, PhotoSortOrder.SIZE_ASC -> "ASC"
            }
            SortDialog(
                visible = showSortDialog,
                onDismissRequest = { showSortDialog = false },
                title = "Sort Photos By",
                criteria = photoCriteria,
                initialCriterion = currentCriterion,
                initialDirection = currentDirection,
                onConfirm = { criterion, direction ->
                    val newOrder = PhotoSortOrder.valueOf("${criterion}_${direction}")
                    viewModel.setPhotoSortOrder(newOrder)
                    showSortDialog = false
                }
            )
        }
    }
}
