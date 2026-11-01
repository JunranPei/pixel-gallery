package com.pixel.gallery.ui.home

import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.ui.layout.ContentScale
import com.pixel.gallery.R
import com.pixel.gallery.model.*
import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.theme.ExpressiveShapes

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun GroupedAlbumsScreen(
    albums: List<Album>, labels: List<LabelSummary>, grouping: AlbumGrouping,
    bottomPadding: Dp, gridState: LazyGridState, columns: Int, onColumnsChange: (Int) -> Unit,
    selectedPaths: Set<String>, selectionMode: Boolean, onToggleSelection: (String) -> Unit,
    onAlbum: (String) -> Unit, onLabel: (Long) -> Unit, onLabels: () -> Unit, onTrash: () -> Unit,
    onToggleGroup: (String) -> Unit, onRenameGroup: (String, String) -> Unit, onDissolveGroup: (String) -> Unit,
    search: Boolean = false, emptyMessage: String? = null,
) {
    val gridColumns = columns.coerceIn(2, 6)
    val rows = remember(albums, labels, grouping, search) { groupedAlbumRows(albums, grouping, labels, search) }
    val backgrounds = remember(rows) { rows.associate { it.key to it.backgroundGroup } }
    val colors = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer)
    val groupColors = remember(grouping.groups, colors) {
        (listOf(LABELS_ALBUM_GROUP) + grouping.groups.map { it.id }).mapIndexed { index, id -> id to colors[index % colors.size] }.toMap()
    }
    var managing by remember { mutableStateOf<AlbumGroup?>(null) }
    var renaming by remember { mutableStateOf<AlbumGroup?>(null) }
    var dissolving by remember { mutableStateOf<AlbumGroup?>(null) }
    AlbumGrid(
        columns = gridColumns, onColumnsChange = onColumnsChange, state = gridState,
        bottomPadding = bottomPadding, emptyMessage = if (albums.isEmpty()) emptyMessage else null,
        modifier = Modifier.testTag("grouped-albums-grid").groupBackgrounds(gridState, backgrounds, groupColors),
    ) {
        items(rows, key = { it.key }, contentType = { "album-card" }) { row ->
            when {
                row.group != null -> {
                    val group = row.group
                    val isLabels = group.id == LABELS_ALBUM_GROUP
                    val title = if (isLabels) stringResource(R.string.qs_labels_title) else group.name
                    val open = group.id !in grouping.collapsedIds
                    val members = if (isLabels) labels.map { it.cover?.uri } else albums.filter { it.path in group.paths }.map { it.coverUri }
                    AlbumCoverCard(
                        title = title,
                        subtitle = stringResource(if (isLabels) R.string.album_group_label_count else R.string.album_group_count, members.size),
                        coverUri = null, coverModifiedMillis = 0L, columns = gridColumns,
                        onClick = { if (!selectionMode) onToggleGroup(group.id) },
                        onLongClick = { if (!selectionMode) managing = group },
                        coverBackground = if (open) Color.Transparent else groupColors.getValue(group.id),
                        coverContent = {
                            Column(Modifier.fillMaxSize().padding(if (gridColumns <= 3) 8.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                repeat(2) { r ->
                                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        repeat(2) { c ->
                                            val uri = members.getOrNull(r * 2 + c)?.takeIf { it.isNotBlank() }
                                            Surface(Modifier.weight(1f).fillMaxHeight(), shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = .7f)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    if (uri != null) GlideImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                                        requestBuilderTransform = { it.override(96).format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565) })
                                                    else Icon(if (isLabels) Icons.Outlined.Label else Icons.Outlined.Folder, null,
                                                        Modifier.size(if (gridColumns <= 3) 20.dp else 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Icon(if (open) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                                stringResource(if (open) R.string.album_group_collapse else R.string.album_group_expand, title),
                                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
                                tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
                row.album != null -> {
                    val album = row.album
                    AlbumCoverCard(album.name, "${album.itemCount} items", album.coverUri.takeIf { it.isNotBlank() }, album.lastModified,
                        onClick = { if (selectionMode) onToggleSelection(album.path) else onAlbum(album.path) },
                        columns = gridColumns, selected = album.path in selectedPaths, selectionMode = selectionMode,
                        onLongClick = { onToggleSelection(album.path) })
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
                        columns = gridColumns, enabled = !selectionMode, onClick = { onLabel(summary.label.id) },
                    )
                }
                else -> AlbumCoverCard(stringResource(R.string.album_group_trash), "", null, 0L,
                    onClick = onTrash, columns = gridColumns, enabled = !selectionMode, emptyIcon = Icons.Outlined.DeleteOutline)
            }
        }
    }
    managing?.let { group ->
        GalleryDialog(onDismissRequest = { managing = null }, title = { Text(if (group.id == LABELS_ALBUM_GROUP) stringResource(R.string.qs_labels_title) else group.name) },
            text = { Column {
                if (group.id == LABELS_ALBUM_GROUP) {
                    TextButton(onClick = { managing = null; onLabels() }) { Text(stringResource(R.string.album_group_manage)) }
                } else {
                    TextButton(onClick = { managing = null; renaming = group }) { Text(stringResource(R.string.album_group_rename)) }
                    TextButton(onClick = { managing = null; dissolving = group }) { Text(stringResource(R.string.album_group_dissolve)) }
                }
            } }, confirmButton = { TextButton(onClick = { managing = null }) { Text(stringResource(R.string.album_group_cancel)) } })
    }
    renaming?.let { group -> AlbumGroupNameDialog(grouping.groups, group, { renaming = null }) { name ->
        onRenameGroup(group.id, name); renaming = null
    } }
    dissolving?.let { group ->
        GalleryDialog(onDismissRequest = { dissolving = null }, title = { Text(stringResource(R.string.album_group_dissolve)) },
            text = { Text(stringResource(R.string.album_group_dissolve_message)) },
            confirmButton = { TextButton(onClick = { onDissolveGroup(group.id); dissolving = null }) { Text(stringResource(R.string.album_group_dissolve)) } },
            dismissButton = { TextButton(onClick = { dissolving = null }) { Text(stringResource(R.string.album_group_cancel)) } })
    }
}

/** Paint only behind the existing grid. Measuring/placing album cards is never affected. */
private fun Modifier.groupBackgrounds(state: LazyGridState, groups: Map<String, String?>, colors: Map<String, Color>) = drawBehind {
    val visible = state.layoutInfo.visibleItemsInfo
    val pad = 6.dp.toPx()
    val rowHeights = visible.groupBy { it.row }.mapValues { (_, cells) -> cells.maxOf { it.size.height } }
    drawIntoCanvas { canvas ->
        // Lazy-grid item offsets exclude its content padding. The draw modifier belongs
        // to the outer viewport, so translate into that same padded coordinate space.
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.clipRect(0f, 0f, size.width, size.height)
        canvas.nativeCanvas.translate(16.dp.toPx(), state.layoutInfo.beforeContentPadding.toFloat())
        colors.forEach { (group, color) ->
            val members = visible.filter { groups[it.key] == group }
            if (members.isEmpty()) return@forEach
            val path = Path()
            fun rect(left: Float, top: Float, right: Float, bottom: Float) {
                val next = Path().apply { addRect(left, top, right, bottom, Path.Direction.CW) }
                path.op(next, Path.Op.UNION)
            }
            members.groupBy { it.row }.forEach { (row, cells) ->
                val left = cells.minOf { it.offset.x }.toFloat()
                val right = cells.maxOf { it.offset.x + it.size.width }.toFloat()
                val top = cells.first().offset.y.toFloat()
                val bottom = top + rowHeights.getValue(row)
                rect(left - pad, top - pad, right + pad, bottom + pad)
                val nextRow = members.filter { it.row == row + 1 }
                if (nextRow.isNotEmpty()) {
                    val bridgeLeft = maxOf(left, nextRow.minOf { it.offset.x }.toFloat())
                    val bridgeRight = minOf(right, nextRow.maxOf { it.offset.x + it.size.width }.toFloat())
                    if (bridgeRight > bridgeLeft) rect(bridgeLeft - pad, bottom - pad, bridgeRight + pad, nextRow.first().offset.y + pad)
                }
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                pathEffect = CornerPathEffect(ExpressiveShapes.AlbumCornerRadius.toPx())
            }
            canvas.nativeCanvas.drawPath(path, paint)
        }
        canvas.nativeCanvas.restore()
    }
}
