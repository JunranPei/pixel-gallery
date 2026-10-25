package com.pixel.gallery.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh

// User chose the rightmost setting of the approved interactive prototype.
private const val NavigationDamping = 1.10f
private const val NavigationStiffness = 520f
private const val RevealDistance = 112f

internal enum class GalleryNavigationTab(val label: String, val side: Int = 0) {
    Lock("Lock", 1), Photos("Photos"), Albums("Albums"), Hidden("Hidden", -1),
}

/** One motion owner for the root dock; revealing never navigates. */
@Stable
internal class GalleryNavigationState(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    var drift by mutableFloatStateOf(0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    var anchor by mutableIntStateOf(0)
        private set
    private var velocity = 0f
    private var driftVelocity = 0f
    private var motion: Job? = null
    private var start = 0f
    private var startDrift = 0f
    private var priorAnchor = 0
    val expanded: Boolean get() = anchor != 0 || abs(progress) > .001f

    fun settle(target: Int, initialVelocity: Float = velocity) {
        motion?.cancel()
        dragging = false
        anchor = target
        motion = scope.launch {
            coroutineScope {
                launch {
                    animate(progress, target.toFloat(), initialVelocity,
                        spring(dampingRatio = NavigationDamping, stiffness = NavigationStiffness, visibilityThreshold = .0005f)) { value, speed ->
                        progress = value
                        velocity = speed
                    }
                }
                launch {
                    animate(drift, 0f, driftVelocity,
                        spring(dampingRatio = NavigationDamping, stiffness = NavigationStiffness, visibilityThreshold = .02f)) { value, speed ->
                        drift = value
                        driftVelocity = speed
                    }
                }
            }
        }
    }

    fun reset(target: Int) {
        motion?.cancel()
        dragging = false
        anchor = target
        progress = target.toFloat()
        drift = 0f
        velocity = 0f
        driftVelocity = 0f
    }

    fun begin() {
        motion?.cancel()
        priorAnchor = anchor
        start = progress.coerceIn(-1f, 1f)
        startDrift = drift
        dragging = true
        velocity = 0f
        driftVelocity = 0f
    }

    fun drag(distance: Float, pinnedSide: Int) {
        if (pinnedSide != 0) {
            drift = startDrift + 10f * tanh(distance / 70f)
        } else if (abs(start) < .02f) {
            val u = (abs(distance) / RevealDistance).coerceIn(0f, 1f)
            progress = sign(distance) * (.8f * u + .2f * (2f * u - u * u))
            drift = 12f * progress + sign(distance) * 8f *
                (1f - exp(-(abs(distance) - RevealDistance).coerceAtLeast(0f) / 75f))
        } else {
            // A gesture that starts on one side may close it, never cross to the other.
            progress = if (start > 0f) (start + distance / RevealDistance).coerceIn(0f, 1f)
                else (start + distance / RevealDistance).coerceIn(-1f, 0f)
            drift = startDrift + 10f * tanh(distance / 100f)
        }
    }

    fun release(distance: Float, speed: Float, pinnedSide: Int) {
        if (pinnedSide != 0) {
            settle(pinnedSide, 0f)
            return
        }
        val side = sign(progress).toInt().takeIf { it != 0 } ?: sign(start).toInt()
        val target = if (abs(start) < .02f) {
            val fling = abs(distance) > 24f && sign(speed).toInt() == side && abs(speed) > 700f
            if (abs(progress) >= .55f || fling) side else 0
        } else {
            val closing = abs(distance) > 24f && sign(speed) == -sign(start) && abs(speed) > 700f
            if (abs(progress) < .45f || closing) 0 else sign(start).toInt()
        }
        settle(target, (speed / RevealDistance).coerceIn(-4f, 4f))
    }

    fun cancelGesture() {
        if (dragging) settle(priorAnchor, 0f)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RevealGalleryNavigation(
    selected: GalleryNavigationTab,
    state: GalleryNavigationState,
    onSelect: (GalleryNavigationTab) -> Unit,
    scrollBehavior: FloatingToolbarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentSelected by rememberUpdatedState(selected)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.reset(currentSelected.side)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.cancelGesture()
        }
    }
    val tabs = GalleryNavigationTab.entries
    val weights = tabs.map { tab ->
        animateFloatAsState(
            if (selected == tab) 1f else 0f,
            spring(dampingRatio = NavigationDamping, stiffness = NavigationStiffness),
            label = "${tab.label} navigation selection",
        ).value.coerceIn(0f, 1f)
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val maxLabelWidth = tabs.maxOf { textMeasurer.measure(AnnotatedString(it.label), labelStyle).size.width }
    val selectedWidth = with(density) { maxOf(120.dp.toPx(), maxLabelWidth + 56.dp.toPx()) }
    val colors = MaterialTheme.colorScheme
    val progress = state.progress.coerceIn(-1f, 1f)
    val side = if (progress >= 0f) 1 else -1
    val revealed = abs(progress)
    val availableSide = if (!state.dragging && revealed >= .995f && state.anchor == side) side else 0

    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier
            .graphicsLayer { translationX = with(density) { state.drift.dp.toPx() } }
            .onGloballyPositioned { coordinates = it }
            .semantics {
                customActions = if (selected.side == 0) listOf(
                    CustomAccessibilityAction("Show Lock") { state.settle(1); true },
                    CustomAccessibilityAction("Show Hidden") { state.settle(-1); true },
                    CustomAccessibilityAction("Hide extra entry") { state.settle(0); true },
                ) else emptyList()
            }
            .pointerInput(state, density.density) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val origin = coordinates?.localToRoot(down.position) ?: down.position
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, origin)
                        var owned = false
                        var distance = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || event.changes.count { it.pressed } > 1 || change.isConsumed) {
                                if (owned) state.cancelGesture()
                                break
                            }
                            val point = coordinates?.takeIf { it.isAttached }?.localToRoot(change.position) ?: change.position
                            val dx = point.x - origin.x
                            val dy = point.y - origin.y
                            tracker.addPosition(change.uptimeMillis, point)
                            if (!change.pressed) {
                                if (owned) {
                                    change.consume()
                                    state.release(distance, tracker.calculateVelocity().x / density.density, currentSelected.side)
                                }
                                break
                            }
                            if (!owned) {
                                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                                if (abs(dx) <= viewConfiguration.touchSlop || abs(dx) < abs(dy) * 1.2f) continue
                                state.begin()
                                owned = true
                            }
                            change.consume()
                            distance = dx / density.density
                            state.drag(distance, currentSelected.side)
                        }
                    }
                } finally {
                    state.cancelGesture()
                }
            },
        contentPadding = PaddingValues(0.dp),
        scrollBehavior = if (selected.side == 0) scrollBehavior else null,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    ) {
        Layout(
            modifier = Modifier.clip(CircleShape),
            content = {
                Box(Modifier.clip(CircleShape).background(colors.primaryContainer))
                tabs.forEachIndexed { index, tab ->
                    val weight = weights[index]
                    val visible = tab.side == 0 || (tab.side == side && revealed > .001f)
                    val available = tab.side == 0 || tab.side == availableSide
                    val selectedIcon = when (tab) {
                        GalleryNavigationTab.Lock -> Icons.Filled.Lock
                        GalleryNavigationTab.Photos -> Icons.Filled.Photo
                        GalleryNavigationTab.Albums -> Icons.Filled.PhotoAlbum
                        GalleryNavigationTab.Hidden -> Icons.Filled.VisibilityOff
                    }
                    val plainIcon = when (tab) {
                        GalleryNavigationTab.Lock -> Icons.Outlined.Lock
                        GalleryNavigationTab.Photos -> Icons.Outlined.Photo
                        GalleryNavigationTab.Albums -> Icons.Outlined.PhotoAlbum
                        GalleryNavigationTab.Hidden -> Icons.Outlined.VisibilityOff
                    }
                    Layout(
                        modifier = Modifier
                            .graphicsLayer { alpha = if (!visible) 0f else if (tab.side == 0) 1f else ((revealed - .04f) / .7f).coerceIn(0f, 1f) }
                            .clip(CircleShape)
                            .then(if (available) Modifier.clickable(role = Role.Tab) { onSelect(tab) }
                                .semantics { this.selected = selected == tab; contentDescription = tab.label }
                                else Modifier.clearAndSetSemantics { }) ,
                        content = {
                            Icon(if (selected == tab) selectedIcon else plainIcon, contentDescription = null,
                                tint = lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, weight), modifier = Modifier.size(24.dp))
                            Text(tab.label, style = labelStyle, maxLines = 1,
                                color = colors.onPrimaryContainer,
                                modifier = Modifier.graphicsLayer { alpha = ((weight - .1f) / .8f).coerceIn(0f, 1f) }.clearAndSetSemantics { })
                        },
                    ) { measurables, constraints ->
                        val icon = measurables[0].measure(Constraints.fixed(24.dp.roundToPx(), 24.dp.roundToPx()))
                        val label = measurables[1].measure(Constraints(maxWidth = (constraints.maxWidth - icon.width - 16.dp.roundToPx()).coerceAtLeast(0), maxHeight = constraints.maxHeight))
                        val gap = 8.dp.toPx() * weight
                        val labelWidth = label.width * weight
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val x = ((constraints.maxWidth - icon.width - gap - labelWidth) / 2f).roundToInt()
                            icon.place(x, (constraints.maxHeight - icon.height) / 2)
                            label.place(x + icon.width + gap.roundToInt(), (constraints.maxHeight - label.height) / 2)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val pad = 8.dp.toPx()
            val slot = 56.dp.toPx()
            // Preserve 48dp touch targets; constrain the text slot on narrow windows.
            val expandedSlot = selectedWidth.coerceAtMost((constraints.maxWidth - 2 * slot - 4 * pad).coerceAtLeast(slot))
            val widths = weights.map { slot + (expandedSlot - slot) * it }
            val extension = widths[if (side > 0) 0 else 3] + pad
            val width = (2 * pad + widths[1] + pad + widths[2] + extension * revealed).roundToInt()
            val x = FloatArray(4)
            x[1] = pad + if (side > 0) extension * revealed else 0f
            x[0] = x[1] - widths[0] - pad
            x[2] = x[1] + widths[1] + pad
            x[3] = x[2] + widths[2] + pad
            val pillWidth = widths.indices.sumOf { (widths[it] * weights[it]).toDouble() }.toFloat()
            val pillX = widths.indices.sumOf { (x[it] * weights[it]).toDouble() }.toFloat()
            val height = 64.dp.roundToPx()
            val itemHeight = 48.dp.roundToPx()
            val pill = measurables[0].measure(Constraints.fixed(pillWidth.roundToInt().coerceAtLeast(0), itemHeight))
            val items = widths.indices.map { measurables[it + 1].measure(Constraints.fixed(widths[it].roundToInt(), itemHeight)) }
            layout(constraints.constrainWidth(width), height) {
                pill.place(pillX.roundToInt(), pad.roundToInt())
                items.forEachIndexed { index, item ->
                    // Unplaced side entries have neither hit regions nor accessibility nodes.
                    if (tabs[index].side == 0 || (tabs[index].side == side && revealed > .001f)) {
                        item.place(x[index].roundToInt(), pad.roundToInt())
                    }
                }
            }
        }
    }
}
