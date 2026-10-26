package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixel.gallery.R
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.theme.ExpressiveShapes
import com.pixel.gallery.ui.viewer.excludeViewerGestures

/** Shared half-page selection surface for Label, Move/Copy and Quick Sort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBottomSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    confirmDismiss: () -> Boolean = { true },
    persistent: Boolean = false,
    centerTitle: Boolean = false,
    onPanelHeightChange: (Dp) -> Unit = {},
    headerActions: @Composable RowScope.() -> Unit = {},
    showBackButton: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(!persistent) }
    val panel = rememberVerticalRevealState(expanded) { expanded = it }
    val density = LocalDensity.current
    val pullScope = rememberCoroutineScope()
    var pullOffset by remember { mutableFloatStateOf(0f) }
    var pullReturn by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler {
        if (expanded || panel.fraction > 0f) expanded = false
        else if (confirmDismiss()) onDismiss()
    }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val navigationPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val collapsed = 68.dp + navigationPadding
        val open = (maxHeight - 240.dp).coerceAtLeast(collapsed)
        val range = with(density) { (open - collapsed).toPx() }
        val threshold = with(density) { 600.dp.toPx() }
        val headerColor = MaterialTheme.colorScheme.surfaceContainerLow
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(collapsed + (open - collapsed) * panel.fraction)
                .graphicsLayer { translationY = pullOffset }
                .excludeViewerGestures()
                .pointerInput(Unit) {
                    // Own blank areas too; child controls still receive the complete gesture.
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                }
                .onGloballyPositioned { onPanelHeightChange(with(density) { it.size.height.toDp() }) },
            shape = ExpressiveShapes.ExtraLargeIncreased,
            color = headerColor,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Column(Modifier.fillMaxWidth().compactSheetDismissDrag(
                    panel, expanded, range, threshold, with(density) { 40.dp.toPx() },
                    onPull = { offset -> if (!closing) { pullReturn?.cancel(); pullOffset = offset } },
                    onReleasePull = { dismiss ->
                        if (!closing) {
                            val accepted = dismiss && confirmDismiss()
                            closing = accepted
                            pullReturn?.cancel()
                            pullReturn = pullScope.launch {
                                val target = if (accepted) with(density) { (collapsed + 24.dp).toPx() } else 0f
                                animate(pullOffset, target,
                                    animationSpec = tween(if (accepted) 180 else 150, easing = FastOutLinearInEasing),
                                ) { value, _ -> pullOffset = value }
                                if (accepted) onDismiss()
                                // An asynchronous save may retain the sheet, including on failure.
                                pullOffset = 0f
                                closing = false
                            }
                        }
                    },
                )) {
                    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                        HorizontalDivider(Modifier.width(32.dp), thickness = 4.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    SelectionSheetHeader(title, subtitle, {
                        if (expanded || panel.fraction > 0f) expanded = false else if (confirmDismiss()) onDismiss()
                    }, headerActions, showBackButton, centerTitle)
                }
                if (panel.fraction > 0f || panel.dragging) {
                    Column(
                        Modifier.fillMaxWidth().weight(1f)
                            .imePadding()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionSheetHeader(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit, showBackButton: Boolean, centerTitle: Boolean) {
    if (centerTitle) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = EmphasizedTypography.TitleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBackButton) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.labels_back))
            }
        }
        Column(Modifier.weight(1f).padding(start = if (showBackButton) 4.dp else 20.dp)) {
            Text(title, style = EmphasizedTypography.TitleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp), maxLines = 1)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}
