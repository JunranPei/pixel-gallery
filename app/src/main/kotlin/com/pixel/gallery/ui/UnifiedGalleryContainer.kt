package com.pixel.gallery.ui

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.util.WeakHashMap
import kotlin.math.roundToInt
import com.pixel.gallery.ui.gallery.shouldSwitchPhotoHeader

internal class GallerySearchState(isSearchMode: Boolean) {
    var isSearchMode by mutableStateOf(isSearchMode)
    var dragOffsetPx by mutableFloatStateOf(0f)
    var isAnimating by mutableStateOf(false)
}

/** Search is another content slot of the permanent toolbar, never another bar. */
class GallerySearchSpec internal constructor(
    internal val state: GallerySearchState,
    internal val onSearchModeChange: (Boolean) -> Unit,
    internal val enabled: Boolean,
    internal val content: @Composable () -> Unit,
)

@Composable
fun rememberGallerySearch(
    isSearchMode: Boolean,
    onSearchModeChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
): GallerySearchSpec {
    val state = remember(isSearchMode) { GallerySearchState(isSearchMode) }
    return GallerySearchSpec(state, onSearchModeChange, enabled, content)
}

/** Data only. Routes describe header slots; they cannot construct a toolbar. */
@OptIn(ExperimentalMaterial3Api::class)
class GalleryHeaderSpec internal constructor(
    val title: @Composable () -> Unit,
    val navigationIcon: @Composable () -> Unit,
    val actions: @Composable RowScope.() -> Unit,
    val colors: TopAppBarColors,
    val fullWidth: Boolean,
    val modifier: Modifier,
    val visible: Boolean,
    val centerTitle: Boolean,
    val search: GallerySearchSpec?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryHeader(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
    fullWidth: Boolean = false,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    centerTitle: Boolean = false,
    search: GallerySearchSpec? = null,
): GalleryHeaderSpec = GalleryHeaderSpec(title, navigationIcon, actions, colors, fullWidth, modifier, visible, centerTitle, search)

class GalleryPageContent internal constructor(
    val header: GalleryHeaderSpec,
    val content: @Composable () -> Unit,
)

/** Prepares state/slots without emitting a Scaffold, toolbar, or body surface. */
@Composable
fun GalleryPage(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> GalleryHeaderSpec,
    content: @Composable (PaddingValues) -> Unit,
): GalleryPageContent {
    val header = topBar()
    return GalleryPageContent(
        header = header,
        content = { Box(modifier.fillMaxSize()) { content(PaddingValues(0.dp)) } },
    )
}

/** Keep route content alive without placing hidden nodes or exposing hit targets. */
@Composable
internal fun RetainedGalleryLayer(visible: Boolean, content: @Composable () -> Unit) {
    Layout(
        modifier = Modifier.fillMaxSize().then(
            if (visible) Modifier else Modifier.clearAndSetSemantics {},
        ),
        content = content,
    ) { measurables, constraints ->
        val children = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (visible) children.forEach { it.placeRelative(0, 0) }
        }
    }
}

/** Only this singleton can own the gallery toolbar and body surface. */
class UnifiedGalleryContainer private constructor() {
    companion object {
        val instance = UnifiedGalleryContainer()
    }

    private val mountedRoots = WeakHashMap<View, Any>()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun Shell(
        modifier: Modifier = Modifier,
        containerColor: Color,
        header: GalleryHeaderSpec,
        showHeader: Boolean = true,
        scrollBehavior: TopAppBarScrollBehavior? = null,
        snackbarHost: @Composable () -> Unit,
        content: @Composable (PaddingValues) -> Unit,
    ) {
        val root = LocalView.current
        val owner = remember { Any() }
        DisposableEffect(root) {
            check(mountedRoots[root] == null) {
                "Only one UnifiedGalleryContainer may be mounted in a gallery window"
            }
            mountedRoots[root] = owner
            onDispose { if (mountedRoots[root] === owner) mountedRoots.remove(root) }
        }
        val density = LocalDensity.current
        val toolbarHeightPx = with(density) { 64.dp.toPx() }
        val statusBarHeightPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
        LaunchedEffect(scrollBehavior, toolbarHeightPx) {
            scrollBehavior?.state?.let { state ->
                state.heightOffsetLimit = -toolbarHeightPx
                state.heightOffset = state.heightOffset.coerceIn(-toolbarHeightPx, 0f)
            }
        }
        val heightOffsetPx = (scrollBehavior?.state?.heightOffset ?: 0f)
            .coerceIn(-toolbarHeightPx, 0f)
        val topInset = with(density) {
            (statusBarHeightPx + toolbarHeightPx + heightOffsetPx).toDp()
        }
        // Both layout nodes are unconditional. Selection/route changes only update
        // slots, never the toolbar/body identity or their geometry.
        Box(modifier.fillMaxSize().background(containerColor)) {
            Box(Modifier.fillMaxSize()) { content(PaddingValues(top = topInset)) }
            Layout(
                modifier = Modifier.align(Alignment.TopCenter).clipToBounds(),
                content = {
                    PermanentGalleryToolbar(
                        header = header,
                        statusBarHeightPx = statusBarHeightPx,
                        heightOffsetPx = heightOffsetPx,
                    )
                },
            ) { measurables, constraints ->
                val bar = measurables.single().measure(constraints)
                layout(bar.width, bar.height) {
                    if (showHeader && header.visible) bar.placeRelative(0, 0)
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { snackbarHost() }
        }
    }
}

/** All four slots remain mounted; the ordinary slots move together during search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermanentGalleryToolbar(
    header: GalleryHeaderSpec,
    statusBarHeightPx: Int,
    heightOffsetPx: Float,
) {
    val search = header.search
    val latestSearch by rememberUpdatedState(search)
    var widthPx by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        search?.takeIf { it.enabled && !it.state.isAnimating && widthPx > 0f }?.let {
            it.state.dragOffsetPx = (it.state.dragOffsetPx + delta).coerceIn(-widthPx, widthPx)
        }
    }
    val fullWidth = header.fullWidth && search == null
    val dragOffsetPx = search?.state?.dragOffsetPx ?: 0f
    val isSearchMode = search?.state?.isSearchMode == true
    val showNormalContent = !isSearchMode || dragOffsetPx != 0f
    val showSearchContent = search != null && (isSearchMode || dragOffsetPx != 0f)
    Layout(
        modifier = header.modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = search?.let { it.enabled && !it.state.isAnimating } == true,
                onDragStopped = { velocity ->
                    val activeSearch = latestSearch ?: return@draggable
                    val state = activeSearch.state
                    if (widthPx <= 0f || state.dragOffsetPx == 0f) {
                        state.dragOffsetPx = 0f
                        return@draggable
                    }
                    val startingMode = state.isSearchMode
                    val startingOffset = state.dragOffsetPx
                    val switchPage = shouldSwitchPhotoHeader(startingOffset, widthPx, velocity)
                    val direction = if (startingOffset > 0f) 1f else -1f
                    val targetOffset = if (switchPage) direction * widthPx else 0f
                    state.isAnimating = true
                    try {
                        Animatable(startingOffset).animateTo(targetOffset, tween(180)) {
                            if (latestSearch?.state === state) state.dragOffsetPx = value
                        }
                        if (latestSearch?.state === state) {
                            val nextMode = if (switchPage) !startingMode else startingMode
                            state.isSearchMode = nextMode
                            state.dragOffsetPx = 0f
                            if (switchPage) latestSearch?.onSearchModeChange?.invoke(nextMode)
                        }
                    } finally {
                        if (latestSearch?.state !== state) state.dragOffsetPx = 0f
                        state.isAnimating = false
                    }
                },
            )
            .background(header.colors.containerColor)
            .clipToBounds()
            .drawWithContent {
                // Sliding toolbar content must not enter the fixed status-bar strip.
                clipRect(top = statusBarHeightPx.toFloat()) { this@drawWithContent.drawContent() }
            },
        content = {
            Box(contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides header.colors.navigationIconContentColor) {
                    if (!fullWidth) header.navigationIcon()
                }
            }
            Box(contentAlignment = if (header.centerTitle) Alignment.Center else Alignment.CenterStart) {
                CompositionLocalProvider(LocalContentColor provides header.colors.titleContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { header.title() }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides header.colors.actionIconContentColor) {
                    if (!fullWidth) header.actions(this@Row)
                }
            }
            Box(contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides header.colors.titleContentColor) {
                    if (showSearchContent) search?.content?.invoke()
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val toolbarHeight = 64.dp.roundToPx()
        val edgePadding = 4.dp.roundToPx()
        val titlePadding = 16.dp.roundToPx()
        val loose = Constraints(
            maxWidth = (width - edgePadding * 2).coerceAtLeast(0),
            maxHeight = toolbarHeight,
        )
        val navigation = measurables[0].measure(loose)
        val actions = measurables[2].measure(loose)
        val titleLeft = if (fullWidth || header.centerTitle) {
            titlePadding
        } else {
            maxOf(titlePadding, navigation.width + titlePadding)
        }
        val titleRight = if (fullWidth || header.centerTitle) {
            width - titlePadding
        } else {
            width - actions.width - edgePadding
        }
        val title = measurables[1].measure(
            Constraints(
                minWidth = (titleRight - titleLeft).coerceAtLeast(0),
                maxWidth = (titleRight - titleLeft).coerceAtLeast(0),
                maxHeight = toolbarHeight,
            ),
        )
        val searchField = measurables[3].measure(
            Constraints(
                minWidth = (width - titlePadding * 2).coerceAtLeast(0),
                maxWidth = (width - titlePadding * 2).coerceAtLeast(0),
                maxHeight = toolbarHeight,
            ),
        )
        val dragDirection = if (dragOffsetPx < 0f) -1f else 1f
        val otherPageOffset = dragOffsetPx - dragDirection * width
        val normalOffset = (if (isSearchMode) otherPageOffset else dragOffsetPx).roundToInt()
        val searchOffset = (if (isSearchMode) dragOffsetPx else otherPageOffset).roundToInt()
        val offset = heightOffsetPx.roundToInt().coerceIn(-toolbarHeight, 0)
        val height = (statusBarHeightPx + toolbarHeight + offset).coerceAtLeast(0)
        layout(width, height) {
            fun childTop(childHeight: Int) = statusBarHeightPx + offset + (toolbarHeight - childHeight) / 2
            if (showNormalContent) {
                navigation.placeRelative(edgePadding + normalOffset, childTop(navigation.height))
                title.placeRelative(titleLeft + normalOffset, childTop(title.height))
                actions.placeRelative(width - edgePadding - actions.width + normalOffset, childTop(actions.height))
            }
            if (showSearchContent) {
                searchField.placeRelative(titlePadding + searchOffset, childTop(searchField.height))
            }
        }
    }
}
