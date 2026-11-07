package com.pixel.gallery.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import com.pixel.gallery.R
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

private object WindowOrigin : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

private class PlatterClip(private val height: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, (size.height - height) / 2, size.width, (size.height + height) / 2), CornerRadius(radius)))
}

@Composable
internal fun AnchoredOverflowPopup(
    expanded: Boolean,
    anchor: Rect,
    width: Dp,
    onDismissRequest: () -> Unit,
    onReopen: () -> Unit,
    onSourceHidden: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var retained by remember { mutableStateOf(false) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var target by remember { mutableStateOf(Rect.Zero) }
    var opening by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var from by remember { mutableStateOf(OverflowMotion.closed(anchor)) }
    var destination by remember { mutableStateOf(from) }
    val frame = remember(density) { derivedStateOf { OverflowMotion.frame(from, destination, progress, opening, density.density) } }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestReopen by rememberUpdatedState(onReopen)
    val latestExpanded by rememberUpdatedState(expanded)
    val hideSource by rememberUpdatedState(onSourceHidden)

    LaunchedEffect(expanded, target, anchor) {
        if (target.isEmpty || anchor.isEmpty) return@LaunchedEffect
        from = if (retained) frame.value else OverflowMotion.closed(anchor)
        destination = if (expanded) OverflowMotion.opened(target, with(density) { 32.dp.toPx() }) else OverflowMotion.closed(anchor)
        opening = expanded
        progress = 0f
        if (!expanded && !retained) return@LaunchedEffect
        retained = true
        animate(0f, 1f, animationSpec = tween(if (expanded) OverflowMotion.OpenMillis else OverflowMotion.CloseMillis, easing = LinearEasing)) { value, _ -> progress = value }
        if (!expanded) retained = false
    }
    if (!expanded && !retained) return
    Popup(
        popupPositionProvider = WindowOrigin,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = false, usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val margin = with(density) { 16.dp.toPx() }
            val maxW = constraints.maxWidth.toFloat()
            val maxH = constraints.maxHeight.toFloat()
            val actualWidth = width.coerceAtMost((maxWidth - 32.dp).coerceAtLeast(1.dp))
            val ready = menuSize != IntSize.Zero && !anchor.isEmpty
            val menuTarget = if (!ready) Rect.Zero else {
                val x = (anchor.right - menuSize.width).coerceIn(margin, (maxW - margin - menuSize.width).coerceAtLeast(margin))
                val y = (anchor.bottom - with(density) { 8.dp.toPx() }).coerceIn(margin, (maxH - margin - menuSize.height).coerceAtLeast(margin))
                Rect(x, y, x + menuSize.width, y + menuSize.height)
            }
            val sourceIsHidden = ready && retained
            SideEffect { target = menuTarget; hideSource(sourceIsHidden) }
            DisposableEffect(Unit) { onDispose { hideSource(false) } }
            Box(Modifier.matchParentSize().pointerInput(anchor) {
                detectTapGestures { point ->
                    if (anchor.contains(point)) { if (latestExpanded) latestDismiss() else latestReopen() }
                    else if (!frame.value.bounds.contains(point)) latestDismiss()
                }
            })
            val color = MaterialTheme.colorScheme.surfaceContainer
            val measuredHeight = menuSize.height.toFloat().coerceAtLeast(1f)
            Box(Modifier
                .offset { IntOffset(menuTarget.left.roundToInt(), menuTarget.top.roundToInt()) }
                .width(actualWidth)
                .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(1.dp))
                .onSizeChanged { menuSize = it }
                .graphicsLayer {
                    val f = frame.value
                    OverflowMotionProbe.record(opening, progress, f)
                    val scale = if (ready) f.bounds.width / menuSize.width.coerceAtLeast(1) else 1f
                    transformOrigin = TransformOrigin.Center
                    scaleX = scale; scaleY = scale
                    translationX = f.bounds.center.x - menuTarget.center.x
                    translationY = f.bounds.center.y - menuTarget.center.y
                    alpha = if (ready && retained) 1f else 0f
                    shape = PlatterClip(f.bounds.height / scale.coerceAtLeast(.001f), f.corner / scale.coerceAtLeast(.001f))
                    clip = true
                    shadowElevation = 6.dp.toPx() * f.surfaceAlpha
                }
                .drawBehind {
                    val f = frame.value
                    val scale = f.bounds.width / size.width.coerceAtLeast(1f)
                    val h = f.bounds.height / scale.coerceAtLeast(.001f)
                    drawRoundRect(color.copy(alpha = f.surfaceAlpha),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, (measuredHeight - h) / 2),
                        size = Size(size.width, h), cornerRadius = CornerRadius(f.corner / scale.coerceAtLeast(.001f)))
                }
            ) {
                Column(Modifier
                    .then(if (!expanded) Modifier.clearAndSetSemantics { } else Modifier)
                    .pointerInput(expanded) {
                        if (!expanded) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                    .graphicsLayer { alpha = frame.value.contentAlpha }
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp), content = content)
            }
            Icon(Icons.Default.MoreVert, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).graphicsLayer {
                    val f = frame.value
                    alpha = if (ready && retained) f.glyphAlpha else 0f
                    translationX = f.bounds.center.x - size.width / 2
                    translationY = f.bounds.center.y - size.height / 2
                })
            val moreLabel = stringResource(R.string.more)
            Box(Modifier
                .offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
                .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() })
                .clickable(role = Role.Button, indication = null, interactionSource = null) {
                    if (latestExpanded) latestDismiss() else latestReopen()
                }
                .semantics { contentDescription = moreLabel })
        }
    }
}
