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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import kotlinx.coroutines.delay


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
    state: LazyGridState = rememberLazyGridState()
) {
    var isFastScrolling by remember { mutableStateOf(false) }
    var isScrollbarDragging by remember { mutableStateOf(false) }

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

    val firstVisibleIndex by remember { derivedStateOf { state.firstVisibleItemIndex } }
    val context = LocalContext.current

    LaunchedEffect(firstVisibleIndex, items, isFastScrolling, isScrollbarDragging) {
        if (!isFastScrolling && !isScrollbarDragging) {
            val info = state.layoutInfo
            val visibleCount = info.visibleItemsInfo.size
            if (visibleCount > 0 && items.isNotEmpty()) {
                val preloadStartIndex = firstVisibleIndex + visibleCount
                val preloadEndIndex = (preloadStartIndex + 18).coerceAtMost(items.size - 1)
                for (i in preloadStartIndex..preloadEndIndex) {
                    val item = items[i]
                    if (item is GridItem.Photo) {
                        val media = item.entry
                        val model = AvesAppGlideModule.getModel(
                            context = context,
                            uri = Uri.parse(media.uri),
                            mimeType = media.sourceMimeType,
                            pageId = null,
                            sizeBytes = media.sizeBytes,
                            isThumbnail = true,
                            rotationDegrees = media.sourceRotationDegrees,
                            dateModifiedMillis = media.dateModifiedMillis
                        )
                        Glide.with(context)
                            .load(model)
                            .signature(ObjectKey(media.dateModifiedMillis))
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .override(200)
                            .preload()
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .photoGridDragSelect(
                    gridState = state,
                    items = items,
                    selectedIds = selectedIds,
                    onSelectionChange = onSelectionChange,
                    isPhoto = { index -> items[index] is GridItem.Photo },
                    getPhotoId = { index -> (items[index] as GridItem.Photo).entry.contentId }
                )
                .pinchToZoomColumns(
                    currentColumns = columns,
                    onColumnsChange = onColumnsChange,
                    minColumns = 2,
                    maxColumns = 6
                ),
            contentPadding = PaddingValues(
                start = 4.dp,
                top = 4.dp,
                end = 4.dp,
                bottom = 80.dp + bottomPadding
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
                        val isHeaderSelected = headerPhotos.isNotEmpty() && headerPhotos.all { selectedIds.contains(it) }
                        val isSelectionMode = selectedIds.isNotEmpty()
                        val onHeaderClick = remember(isHeaderSelected, selectedIds, headerPhotos) {
                            {
                                if (isHeaderSelected) {
                                    onSelectionChange(selectedIds - headerPhotos.toSet())
                                } else {
                                    onSelectionChange(selectedIds + headerPhotos.toSet())
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
                        val isSelectionMode = selectedIds.isNotEmpty()
                        val onPhotoClick = remember(media.contentId, isSelectionMode) {
                            {
                                if (isSelectionMode) {
                                    onToggleSelection(media.contentId)
                                } else {
                                    onNavigateToViewer(media.contentId)
                                }
                            }
                        }
                        
                        PhotoTile(
                            media = media,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            isFastScrolling = isFastScrolling || isScrollbarDragging,
                            onClick = onPhotoClick,
                            onLongClick = null
                        )
                    }
                }
            }
        }

        com.pixel.gallery.ui.components.GalleryScrollbar(
            lazyGridState = state,
            layoutModifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = bottomPadding),
            onDragStateChanged = { isScrollbarDragging = it }
        )
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
    onColumnsChange: (Int) -> Unit = {}
) {
    var isScrollbarDragging by remember { mutableStateOf(false) }
    val firstVisibleIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    val context = LocalContext.current

    LaunchedEffect(firstVisibleIndex, albums, isScrollbarDragging) {
        delay(100)
        if (!isScrollbarDragging) {
            val info = gridState.layoutInfo
            val visibleCount = info.visibleItemsInfo.size
            if (visibleCount > 0 && albums.isNotEmpty()) {
                val preloadStartIndex = firstVisibleIndex + visibleCount
                val preloadEndIndex = (preloadStartIndex + 18).coerceAtMost(albums.size - 1)
                for (i in preloadStartIndex..preloadEndIndex) {
                    val album = albums[i]
                    val mimeType = MimeTypeMap.getFileExtensionFromUrl(album.coverUri).lowercase().let { ext ->
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
                    }
                    val model = AvesAppGlideModule.getModel(
                        context = context,
                        uri = Uri.parse(album.coverUri),
                        mimeType = mimeType,
                        pageId = null,
                        sizeBytes = null,
                        isThumbnail = true,
                        rotationDegrees = 0,
                        dateModifiedMillis = album.lastModified
                    )
                    Glide.with(context)
                        .load(model)
                        .signature(ObjectKey(album.lastModified))
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .override(200)
                        .preload()
                }
            }
        }
    }

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
            // Header Buttons: Favourites and Bin
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(columns) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AlbumHeaderButton(
                        modifier = Modifier.weight(1.0f),
                        icon = Icons.Outlined.StarOutline,
                        label = "Favourites",
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

            items(
                count = albums.size,
                key = { albums[it].name }
            ) { index ->
                val album = albums[index]
                val onAlbumClick = remember(album.name) { { onNavigateToAlbum(album.name) } }
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
    val context = LocalContext.current
    val mimeType = remember(album.coverUri) {
        val extension = MimeTypeMap.getFileExtensionFromUrl(album.coverUri)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "image/jpeg"
    }
    val model = remember(album.coverUri, mimeType) {
        AvesAppGlideModule.getModel(
            context = context,
            uri = Uri.parse(album.coverUri),
            mimeType = mimeType,
            pageId = null,
            sizeBytes = null,
            isThumbnail = true,
            rotationDegrees = 0,
            dateModifiedMillis = album.lastModified
        )
    }
    val signatureKey = remember(album.lastModified) {
        ObjectKey(album.lastModified)
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
            onClick = onClick,
            onLongClick = { showMenu = true }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(24.dp)
                }
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            GlideImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                requestBuilderTransform = transform
            )
        }
        Spacer(Modifier.height(spacerHeight))
        Text(
            text = album.name,
            style = titleStyle,
            modifier = Modifier.padding(start = 4.dp)
        )
        Text(
            text = "${album.itemCount} items",
            style = subtitleStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = if (columns <= 2) 2.dp else 0.dp)
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Hide Album") },
                onClick = {
                    showMenu = false
                    onHide()
                },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("Exclude Album") },
                onClick = {
                    showMenu = false
                    onExclude()
                },
                leadingIcon = { Icon(Icons.Outlined.FolderOff, contentDescription = null) }
            )
        }
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
        if (isSelectionMode) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = "Select Group",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
