package com.pixel.gallery.ui.quicksort

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.pixel.gallery.glide.MediaStoreThumbnail
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.Handedness
import com.pixel.gallery.ui.utils.actionHorizontalAlignment
import com.pixel.gallery.R
import com.pixel.gallery.ui.theme.ExpressiveShapes
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun QuickSortDock(
    config: QuickSortUiConfig,
    gestures: QuickSortGestureState,
    selectedIds: Set<Long>,
    modifier: Modifier = Modifier,
    onMeasuredHeight: (Int) -> Unit,
) {
    val currentConfig by rememberUpdatedState(config)
    var dockOrigin by remember { mutableStateOf(Offset.Zero) }
    var previewTargets by remember { mutableStateOf(config.targets.distinctBy { it.stableKey }) }
    var reorderKey by remember { mutableStateOf<String?>(null) }
    var reorderOverKey by remember { mutableStateOf<String?>(null) }
    var reorderPointer by remember { mutableStateOf(Offset.Zero) }
    var reorderGrabOffset by remember { mutableStateOf(Offset.Zero) }
    var reorderWidth by remember { mutableIntStateOf(0) }
    val scrollState = rememberLazyStaggeredGridState()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val alignRight = config.handedness == Handedness.RIGHT
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    LaunchedEffect(config.targets) {
        if (!gestures.busy) previewTargets = config.targets.distinctBy { it.stableKey }
        gestures.targetBounds.keys.filter { key -> config.targets.none { it.stableKey == key } }
            .forEach(gestures.targetBounds::remove)
    }
    DisposableEffect(gestures) {
        onDispose {
            gestures.targetBounds.clear()
            gestures.dockBounds = null
            gestures.interaction = QuickSortInteraction.Idle
        }
    }

    fun reorderTarget(point: Offset): String? {
        val viewport = gestures.dockBounds ?: return null
        if (!viewport.contains(point)) return null
        // Gaps between variable-width capsules are valid drop positions too.
        return gestures.targetBounds.entries
            .filter { it.value.width > 0 && it.value.height > 0 && it.value.overlaps(viewport) }
            .minByOrNull { (_, rect) -> (rect.center - point).getDistanceSquared() }?.key
    }

    // A bounded, scrollable dock keeps every chosen album reachable. Long drags
    // scroll at the edges, both when arranging albums and when moving photos.
    LaunchedEffect(gestures.interaction) {
        if (gestures.interaction != QuickSortInteraction.Reordering &&
            gestures.interaction != QuickSortInteraction.Moving) return@LaunchedEffect
        val edge = with(density) { 28.dp.toPx() }
        val step = with(density) { 6.dp.toPx() }
        while (true) {
            val bounds = gestures.dockBounds
            val point = if (reorderKey != null) reorderPointer else gestures.pointerInWindow
            if (bounds != null && bounds.contains(point)) {
                val delta = when {
                    point.x < bounds.left + edge -> if (alignRight) step else -step
                    point.x > bounds.right - edge -> if (alignRight) -step else step
                    else -> 0f
                }
                if (delta != 0f && scrollState.scrollBy(delta) != 0f) {
                    if (reorderKey != null) reorderOverKey = reorderTarget(point)
                    else gestures.hoveredTargetKey = gestures.targetAt(point)
                }
            }
            delay(16)
        }
    }
    if (previewTargets.isEmpty()) {
        SideEffect {
            gestures.dockBounds = null
            gestures.targetBounds.clear()
        }
    }

    val rows = com.pixel.gallery.model.quickSortAlbumRows(config.albumRows, previewTargets.size)
    val duplicateNames = remember(previewTargets) { previewTargets.groupingBy { it.displayName }.eachCount() }
    fun label(target: TransferDestination) =
        if ((duplicateNames[target.displayName] ?: 0) > 1)
            "${target.displayName} · ${quickSortPathSuffix(target, previewTargets)}"
        else target.displayName

    Column(modifier.onGloballyPositioned { onMeasuredHeight(it.size.height) },
        horizontalAlignment = config.handedness.actionHorizontalAlignment) {
        com.pixel.gallery.ui.components.TransferUndoButton(
            available = config.undoAvailable,
            busy = gestures.busy,
            onClick = config.onUndo,
        )
        if (previewTargets.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height((rows * 56 + (rows - 1) * 8 + 12).dp)
                .onGloballyPositioned {
                    dockOrigin = it.positionInWindow()
                    gestures.dockBounds = it.boundsInWindow()
                }
                // Handle the drag on a stable parent, not on the moving child.
                // The source stays in place until release; a preview follows the finger.
                .quickSortControlDrag(
                    gestureKey = gestures,
                    longPress = true,
                    onStart = { local ->
                        val point = dockOrigin + local
                        val key = gestures.targetAt(point)
                        val bounds = key?.let { gestures.targetBounds[it] }
                        if (gestures.busy || gestures.scrollbarDragging || key == null || bounds == null) false
                        else {
                            reorderKey = key
                            reorderOverKey = key
                            reorderPointer = point
                            reorderGrabOffset = point - bounds.topLeft
                            reorderWidth = bounds.width.roundToInt()
                            gestures.interaction = QuickSortInteraction.Reordering
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            true
                        }
                    },
                    onMove = { local ->
                        reorderPointer = dockOrigin + local
                        reorderOverKey = reorderTarget(reorderPointer)
                    },
                    onFinish = { cancelled ->
                        if (!cancelled) {
                            val from = previewTargets.indexOfFirst { it.stableKey == reorderKey }
                            val to = previewTargets.indexOfFirst { it.stableKey == reorderOverKey }
                            val next = moveQuickSortTarget(previewTargets, from, to)
                            if (next != previewTargets) {
                                previewTargets = next
                                currentConfig.onReorderTargets(next)
                            }
                        }
                        reorderKey = null
                        reorderOverKey = null
                        gestures.interaction = QuickSortInteraction.Idle
                    },
                )
                .quickSortDockGapGestures(
                    gestureKey = gestures,
                    acceptsDown = { local ->
                        !gestures.busy && !gestures.scrollbarDragging &&
                            gestures.targetAt(dockOrigin + local) == null
                    },
                    onTap = { if (!gestures.busy) currentConfig.onExit() },
                    onStart = { false },
                    onMove = {},
                    onFinish = {},
                ),
        ) {
            LazyHorizontalStaggeredGrid(
                rows = StaggeredGridCells.Fixed(rows),
                state = scrollState,
                reverseLayout = alignRight != isRtl,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalItemSpacing = 8.dp,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = !gestures.busy,
            ) {
                items(previewTargets, key = { it.stableKey }) { target ->
                    // Off-screen albums must stop participating in drop hit testing.
                    DisposableEffect(target.stableKey) {
                        onDispose { gestures.targetBounds.remove(target.stableKey) }
                    }
                    val highlighted = gestures.hoveredTargetKey == target.stableKey ||
                        (reorderKey != null && reorderOverKey == target.stableKey)
                    QuickSortCapsule(
                        target = target,
                        label = label(target),
                        highlighted = highlighted,
                        modifier = Modifier
                            .animateItem()
                            .alpha(if (reorderKey == target.stableKey) .35f else 1f)
                            .onGloballyPositioned {
                                gestures.targetBounds[target.stableKey] = it.boundsInWindow()
                            }
                            .semantics { contentDescription = "${target.displayName}, ${target.path}" },
                        onClick = {
                            if (!gestures.busy) {
                                val ids = selectedIds - currentConfig.reservedIds
                                if (ids.isNotEmpty()) currentConfig.onMove(ids, target)
                            }
                        },
                    )
                }
            }
        }
        previewTargets.firstOrNull { it.stableKey == reorderKey }?.let { target ->
            // Render outside the scrolling content so the dragged capsule remains visible.
            QuickSortCapsule(
                target = target,
                label = label(target),
                highlighted = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val point = reorderPointer - dockOrigin - reorderGrabOffset
                        IntOffset(point.x.roundToInt(), point.y.roundToInt())
                    }
                    .width(with(density) { reorderWidth.toDp() }),
                onClick = {},
            )
        }
    }
        }
    }
}

@Composable
private fun QuickSortCapsule(
    target: TransferDestination,
    label: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp).widthIn(max = 200.dp),
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = if (highlighted) 4.dp else 1.dp,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickSortAlbumCover(target)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Static, tiny covers share Glide's bounded memory/resource caches with the gallery. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun QuickSortAlbumCover(target: TransferDestination) {
    val model = remember(target.coverUri, target.lastModified) {
        target.coverUri?.takeIf { it.isNotBlank() }?.let { value ->
            val uri = Uri.parse(value)
            MediaStoreThumbnail(
                uri = uri,
                mimeType = if (uri.pathSegments.contains("video")) "video/mp4" else "image/jpeg",
                rotationDegrees = 0,
                dateModifiedMillis = target.lastModified,
                allowPersistentCache = false,
                allowSourceFallback = false,
            )
        }
    }
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        // Empty or unavailable albums keep a quiet, legible cover placeholder.
        Text(
            target.displayName.take(1),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        if (model != null) {
            GlideImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) { request ->
                request.override(96, 96)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .priority(Priority.LOW)
                    .centerCrop()
                    .dontAnimate()
            }
        }
    }
}

@Composable
internal fun QuickSortPhotoDragPreview(gestures: QuickSortGestureState, modifier: Modifier = Modifier) {
    if (gestures.interaction != QuickSortInteraction.Moving || gestures.draggedIds.isEmpty()) return
    val point = gestures.pointerInWindow - gestures.containerBounds.topLeft
    Surface(
        modifier = modifier.offset { IntOffset(point.x.roundToInt() - 28, point.y.roundToInt() - 76) },
        shape = ExpressiveShapes.LargeIncreased,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp))
            Text(gestures.draggedIds.size.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}
