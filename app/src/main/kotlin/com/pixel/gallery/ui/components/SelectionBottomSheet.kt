package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.unit.Velocity
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
    persistent: Boolean = false,
    onPanelHeightChange: (Dp) -> Unit = {},
    headerActions: @Composable RowScope.() -> Unit = {},
    showBackButton: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (persistent) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val panel = rememberVerticalRevealState(expanded) { expanded = it }
        val density = LocalDensity.current
        // A downward drag returns to the compact header; it never dismisses.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val navigationPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val collapsed = 68.dp + navigationPadding
            val open = (maxHeight - 240.dp).coerceAtLeast(collapsed)
            val range = with(density) { (open - collapsed).toPx() }
            val threshold = with(density) { 600.dp.toPx() }
            val latestRange by rememberUpdatedState(range)
            val nestedScroll = remember(panel) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && available.y < 0f && panel.fraction < 1f) {
                            if (!panel.dragging) panel.start()
                            val before = panel.fraction
                            panel.dragBy(available.y, latestRange)
                            return Offset(0f, -(panel.fraction - before) * latestRange)
                        }
                        return Offset.Zero
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && available.y > 0f && panel.fraction > 0f) {
                            if (!panel.dragging) panel.start()
                            val before = panel.fraction
                            panel.dragBy(available.y, latestRange)
                            return Offset(0f, -(panel.fraction - before) * latestRange)
                        }
                        return Offset.Zero
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!panel.dragging) return Velocity.Zero
                        panel.finish(available.y, threshold)
                        return available
                    }
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .height(collapsed + (open - collapsed) * panel.fraction)
                    .onGloballyPositioned { onPanelHeightChange(with(density) { it.size.height.toDp() }) },
                shape = ExpressiveShapes.ExtraLargeIncreased,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    Column(Modifier.fillMaxWidth().verticalRevealDrag(panel, range, true, threshold)) {
                        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                            HorizontalDivider(Modifier.width(32.dp), thickness = 4.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        SelectionSheetHeader(title, subtitle, {
                            if (expanded) expanded = false else onDismiss()
                        }, headerActions, showBackButton)
                    }
                    if (panel.fraction > 0f) {
                        Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(nestedScroll).padding(horizontal = 20.dp, vertical = 8.dp), content = content)
                    }
                }
            }
        }
        return
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { target -> target != SheetValue.Hidden || confirmDismiss() },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = ExpressiveShapes.ExtraLargeIncreased,
    ) {
        val density = LocalDensity.current
        val fallbackHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
        val windowHeight = LocalView.current.height.toFloat().takeIf { it > 0f } ?: fallbackHeight
        // Preserve partial/full dragging while laying controls out inside the
        // visible viewport, rather than below a tall sheet's hidden bottom.
        val offset = runCatching { sheetState.requireOffset() }.getOrDefault(windowHeight / 2f)
        val viewport = with(density) { (windowHeight - offset).toDp() } - 48.dp -
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxWidth().height(720.dp)) {
            Column(
                Modifier.fillMaxWidth().height(viewport.coerceIn(0.dp, 720.dp)).imePadding()
                    .padding(horizontal = 20.dp).padding(bottom = 16.dp)
            ) {
                SelectionSheetHeader(title, subtitle, onDismiss, headerActions, showBackButton)
                content()
            }
            // Overlay only; keep the sheet viewport, drag range and content layout unchanged.
            Box(Modifier.fillMaxWidth().height(viewport.coerceIn(0.dp, 720.dp)).imePadding()) {
                GalleryMessageHost(Modifier.align(Alignment.BottomCenter), modal = true)
            }
        }
    }
}

@Composable
private fun SelectionSheetHeader(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit, showBackButton: Boolean) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBackButton) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
            }
        }
        Column(Modifier.weight(1f).padding(start = if (showBackButton) 4.dp else 20.dp)) {
            Text(title, style = EmphasizedTypography.TitleLarge, maxLines = 1)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}
