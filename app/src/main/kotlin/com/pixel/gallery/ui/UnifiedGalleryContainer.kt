package com.pixel.gallery.ui

import android.view.View
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.util.WeakHashMap
import kotlin.math.roundToInt

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
): GalleryHeaderSpec = GalleryHeaderSpec(title, navigationIcon, actions, colors, fullWidth, modifier, visible, centerTitle)

class GalleryPageContent internal constructor(
    val header: GalleryHeaderSpec,
    val content: @Composable () -> Unit,
    val snackbarHost: @Composable () -> Unit,
)

/** Prepares state/slots without emitting a Scaffold, toolbar, or body surface. */
@Composable
fun GalleryPage(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> GalleryHeaderSpec,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
): GalleryPageContent {
    val header = topBar()
    return GalleryPageContent(
        header = header,
        content = { Box(modifier.fillMaxSize()) { content(PaddingValues(0.dp)) } },
        snackbarHost = snackbarHost,
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

/** The toolbar's three layout slots remain mounted across every header mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermanentGalleryToolbar(
    header: GalleryHeaderSpec,
    statusBarHeightPx: Int,
    heightOffsetPx: Float,
) {
    Layout(
        modifier = header.modifier
            .fillMaxWidth()
            .background(header.colors.containerColor)
            .clipToBounds()
            .drawWithContent {
                // Sliding toolbar content must not enter the fixed status-bar strip.
                clipRect(top = statusBarHeightPx.toFloat()) { this@drawWithContent.drawContent() }
            },
        content = {
            Box(contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides header.colors.navigationIconContentColor) {
                    if (!header.fullWidth) header.navigationIcon()
                }
            }
            Box(contentAlignment = if (header.centerTitle) Alignment.Center else Alignment.CenterStart) {
                CompositionLocalProvider(LocalContentColor provides header.colors.titleContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { header.title() }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides header.colors.actionIconContentColor) {
                    if (!header.fullWidth) header.actions(this@Row)
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
        val titleLeft = if (header.fullWidth || header.centerTitle) {
            titlePadding
        } else {
            maxOf(titlePadding, navigation.width + titlePadding)
        }
        val titleRight = if (header.fullWidth || header.centerTitle) {
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
        val offset = heightOffsetPx.roundToInt().coerceIn(-toolbarHeight, 0)
        val height = (statusBarHeightPx + toolbarHeight + offset).coerceAtLeast(0)
        layout(width, height) {
            fun childTop(childHeight: Int) = statusBarHeightPx + offset + (toolbarHeight - childHeight) / 2
            navigation.placeRelative(edgePadding, childTop(navigation.height))
            title.placeRelative(titleLeft, childTop(title.height))
            // Actions are last both in drawing order and at the fixed trailing edge.
            actions.placeRelative(width - edgePadding - actions.width, childTop(actions.height))
        }
    }
}
