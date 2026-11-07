package com.pixel.gallery.ui.home

import androidx.compose.animation.core.animate
import com.pixel.gallery.ui.components.GalleryMotion
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.collectLatest
import android.graphics.CornerPathEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.lerp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.pixel.gallery.ui.utils.albumGridDragSelect
import com.pixel.gallery.ui.utils.AlbumReorderDragState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.theme.ExpressiveShapes

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupedAlbumsScreen(
    albums: List<Album>, labels: List<LabelSummary>, grouping: AlbumGrouping,
    bottomPadding: Dp, gridState: LazyGridState, columns: Int, onColumnsChange: (Int) -> Unit,
    selectedPaths: Set<String>, selectionMode: Boolean, onToggleSelection: (String) -> Unit,
    selectedLabelIds: Set<Long>, onToggleLabelSelection: (Long) -> Unit,
    onAlbum: (String) -> Unit, onLabel: (Long) -> Unit, onLabels: () -> Unit, onTrash: () -> Unit,
    onToggleGroup: (String) -> Unit,
    groupingEnabled: Boolean = true,
    search: Boolean = false, emptyMessage: String? = null,
    groupCovers: Map<String, String> = emptyMap(),
    selectedCollectionKey: String? = null,
    onSetGroupCover: (String) -> Unit = {},
    onSetLabelCover: (Long) -> Unit = {},
    coverPickingGroupId: String? = null,
    defaultOrder: AlbumSortOrder = AlbumSortOrder.NAME_ASC,
    onRememberOrder: (List<GroupedAlbumRow>) -> Unit = {},
    onMoveRow: (List<GroupedAlbumRow>, String, String) -> Unit = { _, _, _ -> },
) {
    val gridColumns = columns.coerceIn(2, 6)
    val reorderDrag = remember(gridState) { AlbumReorderDragState() }
    val leftPaddingPx = with(LocalDensity.current) { 16.dp.toPx() }
    val rows = remember(albums, labels, grouping, search, groupingEnabled, defaultOrder) {
        com.pixel.gallery.utils.GroupWorkProbe.hit("group_grid_rows")
        orderedAlbumRows(groupedAlbumRows(albums, grouping, labels, search, groupingEnabled), grouping, defaultOrder)
    }
    LaunchedEffect(albums, labels, grouping.groups, defaultOrder) {
        val all = orderedAlbumRows(groupedAlbumRows(albums, grouping.copy(collapsedIds = emptySet()), labels, false, groupingEnabled), grouping, defaultOrder)
        onRememberOrder(all)
    }
    val backgrounds = remember(rows) { rows.associate { it.key to it.backgroundGroup } }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    // Dedicated paired hues: mist blue, grey green, grey violet, warm grey.
    val colors = if (dark) listOf(Color(0xFF1C232A), Color(0xFF1D2621), Color(0xFF24212B), Color(0xFF28231F))
        else listOf(Color(0xFFF0F4F8), Color(0xFFEFF5F1), Color(0xFFF4F1F8), Color(0xFFF6F3EF))
    val groupColors = remember(grouping.groups, colors) {
        mapOf(LABELS_ALBUM_GROUP to colors[2]) + grouping.groups.associate { it.id to colors[it.resolvedColorIndex] }
    }
    AlbumGrid(
        columns = gridColumns, onColumnsChange = onColumnsChange, state = gridState,
        bottomPadding = bottomPadding, emptyMessage = if (albums.isEmpty()) emptyMessage else null,
        modifier = Modifier.testTag("grouped-albums-grid"),
        gridGestures = Modifier.albumGridDragSelect(
            state = gridState,
            keys = rows.filter { it.group != null || it.album != null || (it.label != null && !it.label.label.isSystem) }.map { it.key },
            rangeKeys = rows.filter { it.album != null || (it.label != null && !it.label.label.isSystem) }.map { it.key },
            selected = setOfNotNull(selectedCollectionKey) + selectedPaths.mapTo(linkedSetOf()) { "album:$it" } + selectedLabelIds.map { "label:$it" },
            onToggle = { key ->
                if (key.startsWith("group:")) onSetGroupCover(key.removePrefix("group:"))
                else if (key.startsWith("label:")) onToggleLabelSelection(key.removePrefix("label:").toLong())
                else onToggleSelection(key.removePrefix("album:"))
            },
            canReorder = { key -> rows.firstOrNull { it.key == key }?.let { row ->
                val saved = grouping.orderState(row.orderScope())
                row.group != null || key in saved.pinned || (saved.rule ?: defaultOrder) == AlbumSortOrder.CUSTOM
            } == true },
            reorderOnLongPress = { key -> rows.any { it.key == key && it.group != null } },
            reorderTarget = { from, to ->
                val source = rows.firstOrNull { it.key == from }
                val target = rows.firstOrNull { it.key == to }
                if (source?.group == null) to else {
                    val header = if (target?.group != null) target else
                        rows.firstOrNull { it.group != null && it.group.id == target?.backgroundGroup }
                    header?.takeIf { it.orderScope() == source.orderScope() }?.key
                }
            },
            onReorder = { from, to -> onMoveRow(rows, from, to) },
            reorderDrag = reorderDrag,
            leftPaddingPx = leftPaddingPx,
            columns = gridColumns,
            enabled = coverPickingGroupId == null,
            collapseGroupForKey = { key ->
                if (selectionMode || selectedCollectionKey != null) null else backgrounds[key]
            },
            onSwipeCollapse = { id ->
                val header = rows.indexOfFirst { it.backgroundGroup == id }
                if (header >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == header }) {
                    gridState.requestScrollToItem(header)
                }
                onToggleGroup(id)
            },
            keyForGridKey = { key -> rows.firstOrNull { it.key == key && (it.group != null || it.album != null || (it.label != null && !it.label.label.isSystem)) }?.key },
        ),
        contentBackground = Modifier.groupBackgrounds(gridState, backgrounds, groupColors,
            selectedCollectionKey?.takeIf { it.startsWith("group:") }?.removePrefix("group:"), scheme.primary)
            .pointerInput(rows, selectedCollectionKey, coverPickingGroupId) {
                fun groupAt(point: androidx.compose.ui.geometry.Offset): String? {
                    val x = point.x - 16.dp.toPx()
                    val y = point.y - gridState.layoutInfo.beforeContentPadding
                    val visible = gridState.layoutInfo.visibleItemsInfo
                    // Child cards own their entire bounds; only the exposed background is a group target.
                    if (visible.any { x >= it.offset.x && x <= it.offset.x + it.size.width &&
                            y >= it.offset.y && y <= it.offset.y + it.size.height }) return null
                    val pad = 2.dp.toPx()
                    return visible.firstNotNullOfOrNull {
                        val group = backgrounds[it.key] ?: return@firstNotNullOfOrNull null
                        val rowHeight = visible.filter { cell -> cell.row == it.row }.maxOf { cell -> cell.size.height }
                        if (x >= it.offset.x - pad && x <= it.offset.x + it.size.width + pad &&
                            y >= it.offset.y - pad && y <= it.offset.y + rowHeight + pad &&
                            (coverPickingGroupId == null || group == coverPickingGroupId)) group else null
                    }
                }
                detectTapGestures(
                    onTap = { groupAt(it)?.let(onSetGroupCover) },
                    onLongPress = { groupAt(it)?.let(onSetGroupCover) },
                    onDoubleTap = { point -> groupAt(point)?.let {
                        onSetGroupCover(it)
                        if (coverPickingGroupId == null) onToggleGroup(it)
                    } },
                )
            },
    ) {
        items(rows, key = { it.key }, contentType = { if (it.favourite != null) "favourites-entry" else "album-card" }) { row ->
            val rowEnabled = coverPickingGroupId == null ||
                row.group?.id == coverPickingGroupId ||
                (row.album != null && grouping.groups.any { it.id == coverPickingGroupId && row.album.path in it.paths }) ||
                (row.label != null && grouping.labelGroupId(row.label.label.id) == coverPickingGroupId)
            // The active group remains an exit target, but only member albums stay visually emphasized.
            val rowDimmed = !rowEnabled || (coverPickingGroupId != null && row.group != null)
            val dragged = reorderDrag.key == row.key
            Box(Modifier
                .animateItem(placementSpec = if (dragged) null else if (reorderDrag.key != null) GalleryMotion.reorder() else GalleryMotion.layout())
                .zIndex(if (dragged) 1f else 0f)
                .graphicsLayer {
                    // Read pointer/layout state in the layer phase: no per-frame row rebuilding.
                    val offset = if (dragged) reorderDrag.translation(gridState, leftPaddingPx) else androidx.compose.ui.geometry.Offset.Zero
                    translationX = offset.x
                    translationY = offset.y
                }
                .alpha(if (rowDimmed) 0.3f else 1f)) {
            when {
                row.favourite != null -> {
                    AlbumCoverCard(
                        title = stringResource(R.string.favourites),
                        subtitle = stringResource(R.string.labels_count, row.favourite.count),
                        coverUri = null, coverModifiedMillis = 0L, columns = gridColumns,
                        onClick = { onLabel(row.favourite.label.id) },
                        enabled = !selectionMode && coverPickingGroupId == null,
                        coverBackground = Color.Transparent,
                        coverShape = androidx.compose.ui.graphics.RectangleShape,
                        coverContent = {
                            // Official Material flower-like cookie silhouette, within one grid cell.
                            val badgeShape = MaterialShapes.Cookie9Sided.toShape()
                            Box(
                                modifier = Modifier.fillMaxSize(0.88f)
                                    .background(scheme.secondaryContainer, badgeShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = scheme.onSecondaryContainer,
                                    modifier = Modifier.fillMaxSize(0.42f),
                                )
                            }
                        },
                    )
                }
                row.group != null -> {
                    val group = row.group
                    val isLabels = group.isLabelGroup
                    val title = if (group.id == LABELS_ALBUM_GROUP) stringResource(R.string.qs_labels_title) else group.name
                    val open = group.id !in grouping.collapsedIds
                    val members = if (isLabels) labels.filter { grouping.labelGroupId(it.label.id) == group.id }.map { it.cover?.uri } else albums.filter { it.path in group.paths }.map { it.coverUri }
                    AlbumCoverCard(
                        title = title,
                        subtitle = stringResource(if (isLabels) R.string.album_group_label_count else R.string.album_group_count, members.size),
                        coverUri = null, coverModifiedMillis = 0L, columns = gridColumns, enabled = rowEnabled,
                        onClick = { if (!selectionMode) { if (coverPickingGroupId != null || selectedCollectionKey != null) onSetGroupCover(group.id) else onToggleGroup(group.id) } },
                        onLongClick = null,
                        onClickLabel = stringResource(
                            if (open) R.string.album_group_collapse else R.string.album_group_expand, title,
                        ),
                        selected = false,
                        coverBackground = Color.Transparent,
                        coverShape = androidx.compose.ui.graphics.RectangleShape,
                        coverContent = {
                            GroupIllustration(group.id, Modifier.fillMaxSize().padding(8.dp), labelGroup = isLabels)
                        },
                    )
                }
                row.album != null -> {
                    val album = row.album
                    AlbumCoverCard(album.name, "${album.itemCount} items", album.coverUri.takeIf { it.isNotBlank() }, album.lastModified,
                        onClick = { if (selectionMode) onToggleSelection(album.path) else onAlbum(album.path) },
                        columns = gridColumns, enabled = rowEnabled, selected = album.path in selectedPaths, selectionMode = selectionMode,
                        onLongClick = null)
                }
                row.label != null -> {
                    val summary = row.label
                    AlbumCoverCard(
                        title = if (summary.label.isSystem) stringResource(R.string.favourites) else summary.label.name,
                        subtitle = stringResource(R.string.labels_count, summary.count),
                        coverUri = summary.cover?.uri, coverModifiedMillis = summary.cover?.dateModifiedMillis ?: 0L,
                        coverMimeType = summary.cover?.sourceMimeType, coverSizeBytes = summary.cover?.sizeBytes,
                        coverRotationDegrees = summary.cover?.sourceRotationDegrees ?: 0,
                        emptyIcon = if (summary.label.isSystem) Icons.Default.Favorite else Icons.Outlined.Label,
                        columns = gridColumns, enabled = rowEnabled,
                        selectionMode = selectionMode,
                        selected = summary.label.id in selectedLabelIds,
                        onClick = {
                            if (selectionMode) onToggleLabelSelection(summary.label.id)
                            else if (coverPickingGroupId != null) onSetLabelCover(summary.label.id)
                            else onLabel(summary.label.id)
                        },
                        onLongClick = null,
                    )
                }
                else -> AlbumCoverCard(stringResource(R.string.album_group_trash), "", null, 0L,
                    onClick = onTrash, columns = gridColumns, enabled = !selectionMode && rowEnabled, emptyIcon = Icons.Outlined.DeleteOutline)
            }

            }
        }
    }

}

private class GroupPathCache(var bands: List<Rect> = emptyList(), val path: Path = Path())

/** Paint only behind the existing grid. Measuring/placing album cards is never affected. */
@Composable
private fun Modifier.groupBackgrounds(state: LazyGridState, groups: Map<String, String?>, colors: Map<String, Color>, selectedGroup: String?, selectionColor: Color): Modifier {
    var drawn by remember { mutableStateOf<Map<String, List<Rect>>>(emptyMap()) }
    val latestGroups by rememberUpdatedState(groups)
    LaunchedEffect(state) {
        var lastKeys: List<Any> = emptyList()
        snapshotFlow {
            val cells = state.layoutInfo.visibleItemsInfo
            val heights = cells.groupBy { it.row }.mapValues { (_, row) -> row.maxOf { it.size.height } }
            cells.map { it.key } to cells.filter { latestGroups[it.key] != null }
                .groupBy { latestGroups[it.key]!! }.mapValues { (_, members) ->
                    members.groupBy { it.row }.values.map { row ->
                        Rect(row.minOf { it.offset.x }.toFloat(), row.first().offset.y.toFloat(),
                            row.maxOf { it.offset.x + it.size.width }.toFloat(),
                            (row.first().offset.y + heights.getValue(row.first().row)).toFloat())
                    }
                }
        }.collectLatest { (keys, target) ->
            val previous = drawn
            val morph = previous.isNotEmpty() && keys != lastKeys && !state.isScrollInProgress
            lastKeys = keys
            if (!morph) drawn = target else {
                animate(0f, 1f, animationSpec = GalleryMotion.fraction()) { progress, _ ->
                    drawn = target.mapValues { (group, bands) ->
                        val old = previous[group] ?: bands
                        List(maxOf(old.size, bands.size)) { i ->
                            androidx.compose.ui.geometry.lerp(old.getOrElse(i) { old.last() },
                                bands.getOrElse(i) { bands.last() }, progress)
                        }
                    }
                }
                drawn = target
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radius = with(density) { ExpressiveShapes.AlbumCornerRadius.toPx() }
    val geometry = remember(radius) { mutableMapOf<String, GroupPathCache>() }
    val scratchPath = remember { Path() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val corners = remember(radius) { CornerPathEffect(radius) }
    SideEffect { geometry.keys.retainAll(colors.keys) }
    return drawBehind {
    val pad = 2.dp.toPx()
    drawIntoCanvas { canvas ->
        // Lazy-grid item offsets exclude its content padding. The draw modifier belongs
        // to the outer viewport, so translate into that same padded coordinate space.
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.clipRect(0f, 0f, size.width, size.height)
        canvas.nativeCanvas.translate(16.dp.toPx(), state.layoutInfo.beforeContentPadding.toFloat())
        colors.forEach { (group, color) ->
            val bands = drawn[group].orEmpty()
            if (bands.isEmpty()) return@forEach
            val cached = geometry.getOrPut(group) { GroupPathCache() }
            val path = cached.path
            val overlapping = (1 until bands.size).any { bands[it].top < bands[it - 1].bottom + pad * 2 }

            fun rect(left: Float, top: Float, right: Float, bottom: Float, rounded: Boolean = false) {
                val next = scratchPath.apply {
                    rewind()
                    if (rounded) addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
                    else addRect(left, top, right, bottom, Path.Direction.CW)
                }
                path.op(next, Path.Op.UNION)
            }
            if (cached.bands != bands) {
            path.rewind()
            bands.forEachIndexed { index, band ->
                // Round before union: overlapping animated bands create tiny stair-step edges.
                // Applying CornerPathEffect only after union collapses those corners to zero.
                rect(band.left - pad, band.top - pad, band.right + pad, band.bottom + pad, rounded = overlapping)
                bands.getOrNull(index + 1)?.let { next ->
                    val left = maxOf(band.left, next.left)
                    val right = minOf(band.right, next.right)
                    // Morphing rows initially overlap. Only bridge a real vertical gap;
                    // an inverted bridge cuts across their rounded outer corners.
                    if (right > left && next.top > band.bottom)
                        rect(left - pad, band.bottom - pad, right + pad, next.top + pad)
                }
            }
            cached.bands = bands
            }
            paint.apply {
                style = Paint.Style.FILL
                this.color = (if (group == selectedGroup) lerp(color, selectionColor, 0.14f) else color).toArgb()
                pathEffect = if (!overlapping) corners else null
            }
            canvas.nativeCanvas.drawPath(path, paint)
            if (group == selectedGroup) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.dp.toPx()
                paint.color = selectionColor.toArgb()
                canvas.nativeCanvas.drawPath(path, paint)
            }
        }
        canvas.nativeCanvas.restore()
    }
}

}
