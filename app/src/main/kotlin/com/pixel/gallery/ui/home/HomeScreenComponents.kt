package com.pixel.gallery.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.model.Album
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.graphicsLayer
import com.pixel.gallery.ui.utils.photoGridDragSelect
import com.pixel.gallery.ui.utils.pinchToZoomColumns
import com.pixel.gallery.ui.viewmodel.PhotosViewModel.GridItem
import androidx.compose.ui.text.font.FontWeight
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.pixel.gallery.glide.AvesAppGlideModule
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import com.bumptech.glide.load.DecodeFormat
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.pixel.gallery.R
import com.pixel.gallery.ui.quicksort.QuickSortUiConfig
import com.pixel.gallery.ui.quicksort.QuickSortGestureState
import com.pixel.gallery.ui.quicksort.QuickSortDock
import com.pixel.gallery.ui.quicksort.QuickSortPhotoDragPreview
import com.pixel.gallery.ui.quicksort.quickSortPhotoGestures
import com.pixel.gallery.ui.quicksort.quickSortGestureArbitration



@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PhotosScreen(
    items: List<GridItem>,
    onNavigateToViewer: (Long) -> Unit,
    selectedIds: Set<Long> = emptySet(),
    onSelectionChange: (Set<Long>) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    columns: Int = 3,
    onColumnsChange: (Int) -> Unit = {},
    bottomPadding: Dp = 0.dp,
    state: LazyGridState = rememberLazyGridState(),
    emptyMessage: String? = null,
    quickSort: QuickSortUiConfig? = null,
) {
    var isFastScrolling by remember { mutableStateOf(false) }
    var isScrollbarDragging by remember { mutableStateOf(false) }
    val quickSortGestures = remember { QuickSortGestureState() }
    var gridOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val dockHeight = with(LocalDensity.current) { dockHeightPx.toDp() }
    val effectiveBottomPadding = bottomPadding + (quickSort?.dockBottomInset ?: 0.dp)
    val gridSidePadding = 4.dp
    val gridSidePaddingPx = with(LocalDensity.current) { gridSidePadding.roundToPx().toFloat() }
    val reservedIds = quickSort?.reservedIds.orEmpty()
    val quickSortBusy = quickSort != null && quickSortGestures.busy
    val idsByIndex = remember(items) {
        items.map { (it as? GridItem.Photo)?.entry?.contentId }
    }
    SideEffect { quickSortGestures.scrollbarDragging = isScrollbarDragging }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    isFastScrolling = false
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (java.lang.Math.abs(available.y) > 15000f) {
                    isFastScrolling = true
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress) {
            isFastScrolling = false
        }
    }

    val scrollbarPositionLabels = remember(items) {
        var currentDateLabel: String? = null
        List(items.size) { index ->
            when (val item = items[index]) {
                is GridItem.Header -> {
                    currentDateLabel = item.title
                    item.title
                }
                is GridItem.Photo -> currentDateLabel
            }
        }
    }
    val scrollbarPositionLabelProvider = remember(scrollbarPositionLabels) {
        { index: Int -> scrollbarPositionLabels.getOrNull(index) }
    }

    Box(modifier = Modifier.fillMaxSize()
        .then(if (quickSort != null) Modifier.quickSortGestureArbitration(quickSortGestures) else Modifier)
        .onGloballyPositioned { quickSortGestures.containerBounds = it.boundsInWindow() }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            userScrollEnabled = !quickSortBusy,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { gridOriginInWindow = it.positionInWindow() }
                .nestedScroll(nestedScrollConnection)
                .then(
                    if (quickSort == null) {
                        Modifier.photoGridDragSelect(
                            gridState = state,
                            items = items,
                            selectedIds = selectedIds,
                            onSelectionChange = onSelectionChange,
                            isPhoto = { index -> items[index] is GridItem.Photo },
                            getPhotoId = { index -> (items[index] as GridItem.Photo).entry.contentId },
                            leftContentPaddingPx = gridSidePaddingPx,
                        )
                    } else {
                        Modifier.quickSortPhotoGestures(
                            gridState = state,
                            idsByIndex = idsByIndex,
                            selectedIds = selectedIds,
                            onSelectionChange = onSelectionChange,
                            config = quickSort,
                            gestures = quickSortGestures,
                            gridOriginInWindow = gridOriginInWindow,
                            scrollbarDragging = isScrollbarDragging,
                            leftContentPaddingPx = gridSidePaddingPx,
                        )
                    },
                )
                .pinchToZoomColumns(
                    currentColumns = columns,
                    onColumnsChange = onColumnsChange,
                    minColumns = 2,
                    maxColumns = 6,
                    isEnabled = { quickSort == null || !quickSortGestures.busy },
                ),
            contentPadding = PaddingValues(
                start = gridSidePadding,
                top = 4.dp + if (quickSort?.dockTop == true) dockHeight + 16.dp else 0.dp,
                end = gridSidePadding,
                    bottom = effectiveBottomPadding + when {
                    quickSort == null -> 80.dp
                    !quickSort.dockTop -> dockHeight + 24.dp
                    else -> 4.dp
                },
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                count = items.size,
                key = { index ->
                    when (val item = items[index]) {
                        is GridItem.Header -> "header_${item.title}_${item.timestamp}"
                        is GridItem.Photo -> item.entry.contentId
                    }
                },
                span = { index ->
                    when (items[index]) {
                        is GridItem.Header -> androidx.compose.foundation.lazy.grid.GridItemSpan(columns)
                        is GridItem.Photo -> androidx.compose.foundation.lazy.grid.GridItemSpan(1)
                    }
                }
            ) { index ->
                when (val item = items[index]) {
                    is GridItem.Header -> {
                        val headerPhotos = remember(item, items) {
                            val photos = mutableListOf<Long>()
                            for (i in index + 1 until items.size) {
                                val nextItem = items[i]
                                if (nextItem is GridItem.Header) break
                                if (nextItem is GridItem.Photo) photos.add(nextItem.entry.contentId)
                            }
                            photos
                        }
                        val selectableHeaderPhotos = headerPhotos.filterNot { it in reservedIds }
                        val isHeaderSelected = selectableHeaderPhotos.isNotEmpty() && selectableHeaderPhotos.all { selectedIds.contains(it) }
                        val isSelectionMode = selectedIds.isNotEmpty() || quickSort != null
                        val onHeaderClick = remember(isHeaderSelected, selectedIds, selectableHeaderPhotos, quickSortBusy, reservedIds) {
                            {
                                if (!quickSortBusy) {
                                    if (isHeaderSelected) {
                                        onSelectionChange(selectedIds - selectableHeaderPhotos.toSet() - reservedIds)
                                    } else {
                                        onSelectionChange((selectedIds + selectableHeaderPhotos.toSet()) - reservedIds)
                                    }
                                }
                            }
                        }
                        
                        DateHeader(
                            title = item.title,
                            isSelectionMode = isSelectionMode,
                            isSelected = isHeaderSelected,
                            onToggleSelection = onHeaderClick
                        )
                    }
                    is GridItem.Photo -> {
                        val media = item.entry
                        val isSelected = selectedIds.contains(media.contentId)
                        val isSelectionMode = selectedIds.isNotEmpty() || quickSort != null
                        val isReserved = media.contentId in reservedIds
                        val onPhotoClick: () -> Unit = {
                            if (!isReserved && !quickSortBusy) {
                                if (isSelectionMode) {
                                    onToggleSelection(media.contentId)
                                } else {
                                    onNavigateToViewer(media.contentId)
                                }
                            }
                        }
                        
                        Box(Modifier.fillMaxWidth()) {
                            PhotoTile(
                                media = media,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                isFastScrolling = isFastScrolling || isScrollbarDragging,
                                onClick = onPhotoClick,
                                onLongClick = null,
                            )
                            if (isReserved) {
                                Box(
                                    Modifier.matchParentSize().clip(ExpressiveShapes.LargeIncreased)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .55f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.HourglassEmpty, stringResource(R.string.qs_pending),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (items.isEmpty() && emptyMessage != null) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!quickSortBusy) com.pixel.gallery.ui.components.GalleryScrollbar(
            lazyGridState = state,
            layoutModifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(
                    top = if (quickSort?.dockTop == true) dockHeight + 16.dp else 0.dp,
                    bottom = effectiveBottomPadding + if (quickSort != null && !quickSort.dockTop) dockHeight + 24.dp else 0.dp,
                ),
            positionLabelProvider = scrollbarPositionLabelProvider,
            onDragStateChanged = { isScrollbarDragging = it }
        )

        if (quickSort != null) {
            QuickSortDock(
                config = quickSort,
                gestures = quickSortGestures,
                selectedIds = selectedIds,
                modifier = Modifier.align(if (quickSort.dockTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(top = if (quickSort.dockTop) 8.dp else 0.dp,
                        bottom = if (quickSort.dockTop) 0.dp else effectiveBottomPadding + 16.dp),
                onMeasuredHeight = { dockHeightPx = it },
            )
            QuickSortPhotoDragPreview(quickSortGestures)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PhotoTile(
    media: MediaEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isFastScrolling: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val isVideo = remember(media.sourceMimeType) { media.sourceMimeType.startsWith("video/") }
    val context = LocalContext.current
    val model = remember(media.uri, media.sourceMimeType, media.sizeBytes, isFastScrolling) {
        if (isFastScrolling) null
        else AvesAppGlideModule.getModel(
            context = context,
            uri = Uri.parse(media.uri),
            mimeType = media.sourceMimeType,
            pageId = null,
            sizeBytes = media.sizeBytes,
            isThumbnail = true,
            rotationDegrees = media.sourceRotationDegrees,
            dateModifiedMillis = media.dateModifiedMillis
        )
    }
    val signatureKey = remember(media.dateModifiedMillis) {
        ObjectKey(media.dateModifiedMillis)
    }
    val transform = remember(signatureKey) {
        { requestBuilder: com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> ->
            requestBuilder
                // Keep the cached 200 px resource untransformed. Compose still
                // draws it with ContentScale.Crop, while the viewer can draw the
                // same cached pixels with ContentScale.Fit.
                .dontTransform()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(signatureKey)
                .override(200)
                .thumbnail(requestBuilder.clone().sizeMultiplier(0.1f))
        }
    }
    val formattedDuration = remember(media.durationMillis) {
        media.durationMillis?.let { ms ->
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60
            if (hours > 0) {
                java.lang.String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                java.lang.String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
            }
        }
    }

    if (isSelectionMode || isSelected) {
        val transition = updateTransition(isSelected, label = "SelectionTransition")
        
        val scale by transition.animateFloat(
            label = "Scale",
            transitionSpec = { 
                if (targetState) {
                    spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow) 
                } else {
                    spring(stiffness = Spring.StiffnessLow)
                }
            }
        ) { state ->
            if (state) 0.92f else 1.0f
        }
        
        val cornerRadius by transition.animateDp(
            label = "CornerRadius",
            transitionSpec = { spring(stiffness = Spring.StiffnessLow) }
        ) { state ->
            if (state) 28.dp else 20.dp
        }
        
        val overlayAlpha by transition.animateFloat(
            label = "OverlayAlpha",
            transitionSpec = { tween(200) }
        ) { state ->
            if (state) 0.3f else 0.0f
        }

        PhotoTileContent(
            model = model,
            isVideo = isVideo,
            formattedDuration = formattedDuration,
            scale = scale,
            cornerRadius = cornerRadius,
            overlayAlpha = overlayAlpha,
            isSelected = isSelected,
            isSelectionMode = isSelectionMode,
            transform = transform,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        PhotoTileContent(
            model = model,
            isVideo = isVideo,
            formattedDuration = formattedDuration,
            scale = 1.0f,
            cornerRadius = 20.dp,
            overlayAlpha = 0.0f,
            isSelected = false,
            isSelectionMode = false,
            transform = transform,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PhotoTileContent(
    model: Any?,
    isVideo: Boolean,
    formattedDuration: String?,
    scale: Float,
    cornerRadius: Dp,
    overlayAlpha: Float,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    transform: (com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable>) -> com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = true
                shape = RoundedCornerShape(cornerRadius)
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        GlideImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            requestBuilderTransform = transform
        )
        
        // Selection overlay
        if (overlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = overlayAlpha))
            )
        }
        
        // Selection indicators
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isSelected,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp).background(Color.White, CircleShape)
                    )
                }
                
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isSelected,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                            .padding(2.dp)
                            .background(Color.White.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
        }
        
        // Video indicators
        if (isVideo && !isSelectionMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (formattedDuration != null) {
                    Text(
                        text = formattedDuration,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
fun AlbumsScreen(
    albums: List<Album>,
    bottomPadding: Dp = 0.dp,
    gridState: LazyGridState = rememberLazyGridState(),
    onNavigateToFavourites: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onExclude: (String) -> Unit = {},
    onHide: (String) -> Unit = {},
    columns: Int = 2,
    onColumnsChange: (Int) -> Unit = {},
    showHeaderButtons: Boolean = true,
    emptyMessage: String? = null,
) {
    var isScrollbarDragging by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoomColumns(
                    currentColumns = columns,
                    onColumnsChange = onColumnsChange,
                    minColumns = 1,
                    maxColumns = 4
                ),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 80.dp + bottomPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showHeaderButtons) {
                // Reuse the existing Favourites slot for Labels; keep the Bin slot unchanged.
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AlbumHeaderButton(
                            modifier = Modifier.weight(1.0f),
                            icon = Icons.Outlined.Label,
                            label = stringResource(R.string.qs_labels_title),
                            onClick = onNavigateToFavourites
                        )
                        AlbumHeaderButton(
                            modifier = Modifier.weight(1.0f),
                            icon = Icons.Outlined.DeleteOutline,
                            label = "Recycle Bin",
                            onClick = onNavigateToTrash
                        )
                    }
                }
            }

            items(
                count = albums.size,
                key = { albums[it].path }
            ) { index ->
                val album = albums[index]
                val onAlbumClick = remember(album.path) { { onNavigateToAlbum(album.path) } }
                val onAlbumExclude = remember(album.path) { { onExclude(album.path) } }
                val onAlbumHide = remember(album.path) { { onHide(album.path) } }
                AlbumCard(
                    album = album,
                    onClick = onAlbumClick,
                    onExclude = onAlbumExclude,
                    onHide = onAlbumHide,
                    columns = columns
                )
            }
        }

        if (albums.isEmpty() && emptyMessage != null) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        com.pixel.gallery.ui.components.GalleryScrollbar(
            lazyGridState = gridState,
            layoutModifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = bottomPadding),
            onDragStateChanged = { isScrollbarDragging = it }
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onExclude: () -> Unit,
    onHide: () -> Unit,
    columns: Int = 2
) {
    var showMenu by remember { mutableStateOf(false) }
    AlbumCoverCard(
        title = album.name,
        subtitle = "${album.itemCount} items",
        coverUri = album.coverUri.takeIf { it.isNotBlank() },
        coverModifiedMillis = album.lastModified,
        onClick = onClick,
        onLongClick = { showMenu = true },
        columns = columns,
    ) {
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Hide Album") },
                onClick = { showMenu = false; onHide() },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("Exclude Album") },
                onClick = { showMenu = false; onExclude() },
                leadingIcon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) }
            )
        }
    }
}

/** Shared album/label presentation. Filesystem actions belong to the caller. */
@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AlbumCoverCard(
    title: String,
    subtitle: String,
    coverUri: String?,
    coverModifiedMillis: Long,
    onClick: () -> Unit,
    columns: Int = 2,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    coverMimeType: String? = null,
    coverSizeBytes: Long? = null,
    coverRotationDegrees: Int = 0,
    emptyIcon: ImageVector = Icons.Outlined.Folder,
    captionContent: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val mimeType = remember(coverUri, coverMimeType) {
        coverMimeType ?: run {
            val extension = MimeTypeMap.getFileExtensionFromUrl(coverUri.orEmpty())
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "image/jpeg"
        }
    }
    val model = remember(coverUri, mimeType, coverModifiedMillis, coverSizeBytes, coverRotationDegrees) {
        coverUri?.let { AvesAppGlideModule.getModel(
            context = context,
            uri = Uri.parse(it),
            mimeType = mimeType,
            pageId = null,
            sizeBytes = coverSizeBytes,
            isThumbnail = true,
            rotationDegrees = coverRotationDegrees,
            dateModifiedMillis = coverModifiedMillis
        ) }
    }
    val signatureKey = remember(coverModifiedMillis) {
        ObjectKey(coverModifiedMillis)
    }
    val transform = remember(signatureKey) {
        { requestBuilder: com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> ->
            requestBuilder
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(signatureKey)
                .override(200)
                .thumbnail(requestBuilder.clone().sizeMultiplier(0.1f))
        }
    }

    val titleStyle = when (columns) {
        1 -> EmphasizedTypography.TitleLarge
        2 -> EmphasizedTypography.TitleLarge
        3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> EmphasizedTypography.LabelLarge
    }

    val subtitleStyle = when (columns) {
        1 -> MaterialTheme.typography.bodyMedium
        2 -> MaterialTheme.typography.bodyMedium
        3 -> MaterialTheme.typography.bodySmall
        else -> MaterialTheme.typography.labelSmall
    }

    val spacerHeight = when (columns) {
        1, 2 -> 12.dp
        3 -> 8.dp
        else -> 6.dp
    }

    Column(
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = ExpressiveShapes.ExtraLargeIncreased
                )
                .clip(ExpressiveShapes.ExtraLargeIncreased),
            contentAlignment = Alignment.Center
        ) {
            if (coverUri != null) {
                GlideImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    requestBuilderTransform = transform
                )
            } else {
                Icon(
                    imageVector = emptyIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (columns <= 2) 56.dp else 36.dp)
                )
            }
        }
        Spacer(Modifier.height(spacerHeight))
        Text(
            text = title,
            style = titleStyle,
            modifier = Modifier.padding(start = 4.dp)
        )
        Text(
            text = subtitle,
            style = subtitleStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = if (columns <= 2) 2.dp else 0.dp)
        )

        captionContent()
    }
}

@Composable
fun PhotoTilePlaceholder(index: Int) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(ExpressiveShapes.LargeIncreased)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AlbumHeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = ExpressiveShapes.LargeIncreased,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 16.dp, horizontal = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = EmphasizedTypography.LabelLarge
            )
        }
    }
}

@Composable
fun DateHeader(
    title: String,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(start = 8.dp, top = 16.dp, bottom = 8.dp, end = 16.dp)
            .fillMaxWidth()
            .clickable(enabled = isSelectionMode, onClick = onToggleSelection),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = EmphasizedTypography.LabelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        // Keep the header's measured height stable when selection mode changes.
        Box(modifier = Modifier.size(24.dp)) {
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "Select Group",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
