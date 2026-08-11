package com.pixel.gallery.ui.viewer

import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.davemorrissey.labs.subscaleview.DecoderFactory
import com.davemorrissey.labs.subscaleview.ImageDecoder
import com.davemorrissey.labs.subscaleview.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pixel.gallery.ui.viewer.decoders.FastRegionDecoder
import com.pixel.gallery.ui.viewer.decoders.GlideBaseImageDecoder
import com.pixel.gallery.ui.viewer.decoders.SvgRegionDecoder
import com.pixel.gallery.ui.viewer.decoders.TiffRegionDecoder
import com.pixel.gallery.ui.viewer.decoders.RawEmbeddedPreviewRegionDecoder
import com.pixel.gallery.ui.viewer.decoders.BmpRegionDecoder
import com.pixel.gallery.ui.viewer.decoders.JxlRegionDecoder
import com.pixel.gallery.ui.viewer.decoders.UltraHdrTileSupport
import com.pixel.gallery.ui.viewer.decoders.UltraHdrAwareFitCenter
import com.pixel.gallery.ui.viewer.formats.ViewerRegionDecoderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val VISIBLE_TILE_TASK_PRIORITY = 0
private const val CACHE_WRITE_TASK_PRIORITY = 1
private val tileTaskSequence = AtomicLong()

private class PrioritizedTileTask(
    private val priority: Int,
    private val sequence: Long,
    private val delegate: Runnable,
) : Runnable, Comparable<PrioritizedTileTask> {
    private val submittedAtNanos = SystemClock.elapsedRealtimeNanos()

    override fun run() {
        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        ViewerLoadMetrics.event(
            "TILE_EXECUTOR_START",
            "task=$sequence priority=$priority waitMs=" +
                "${(startedAtNanos - submittedAtNanos) / 1_000_000L} " +
                "queuedAfterTake=${tileTaskExecutor.queue.size} active=${tileTaskExecutor.activeCount}",
        )
        try {
            delegate.run()
        } finally {
            ViewerLoadMetrics.event(
                "TILE_EXECUTOR_END",
                "task=$sequence priority=$priority runMs=" +
                    "${(SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L} " +
                    "queued=${tileTaskExecutor.queue.size} active=${tileTaskExecutor.activeCount}",
            )
        }
    }

    override fun compareTo(other: PrioritizedTileTask): Int {
        val priorityOrder = priority.compareTo(other.priority)
        return if (priorityOrder != 0) priorityOrder else sequence.compareTo(other.sequence)
    }
}

// Keep all heavy tile work serial for predictable power. Newly visible decodes can pass
// cache writes that have not started yet, while work from another window remains intact.
private val tileTaskExecutor = ThreadPoolExecutor(
    // A single decode lane matches the stable viewer's large-image resource pool.
    // More workers do not make one BitmapRegionDecoder parallel, but they can leave a
    // stale tile waiting inside the decoder lock and increase cross-window power peaks.
    1,
    1,
    60L,
    TimeUnit.SECONDS,
    PriorityBlockingQueue<Runnable>(),
).apply {
    allowCoreThreadTimeOut(true)
}

private fun prioritizedTileExecutor(priority: Int): Executor = Executor { runnable ->
    val sequence = tileTaskSequence.getAndIncrement()
    ViewerLoadMetrics.event(
        "TILE_EXECUTOR_SUBMIT",
        "task=$sequence priority=$priority queuedBefore=${tileTaskExecutor.queue.size} " +
            "active=${tileTaskExecutor.activeCount} completed=${tileTaskExecutor.completedTaskCount}",
    )
    tileTaskExecutor.execute(PrioritizedTileTask(priority, sequence, runnable))
}

private val tileDecodeExecutor = prioritizedTileExecutor(VISIBLE_TILE_TASK_PRIORITY)
private val tileCacheWriteExecutor = prioritizedTileExecutor(CACHE_WRITE_TASK_PRIORITY)

internal class ViewerTransformStateStore {
    private data class StoredState(
        val state: SubsamplingScaleImageView.ViewState,
        val revision: Int,
    )

    private val states = HashMap<String, StoredState>()

    @Synchronized
    fun get(key: String): SubsamplingScaleImageView.ViewState? = states[key]?.state

    @Synchronized
    fun revision(key: String): Int = states[key]?.revision ?: 0

    @Synchronized
    fun save(
        key: String,
        state: SubsamplingScaleImageView.ViewState,
        reason: String = "unspecified",
    ) {
        val revision = (states[key]?.revision ?: 0) + 1
        states[key] = StoredState(state, revision)
        ViewerLoadMetrics.event(
            "TRANSFORM_STORE_SAVE",
            "revision=$revision reason=$reason scale=${state.scale} " +
                "base=${state.baseFitScale} center=${state.sourceCenter} " +
                "rotation=${state.rotationRadians}",
            imageKey = key,
        )
    }
}

private class PreviewRequestGuard {
    private var target: android.widget.ImageView? = null
    private var requestKey: String? = null
    private var metricsToken: ViewerLoadMetrics.PreviewToken? = null

    fun begin(
        imageView: android.widget.ImageView,
        key: String,
        detail: String,
        activeAtStart: Boolean,
        model: Any,
    ): ViewerLoadMetrics.PreviewToken? {
        if (target === imageView && requestKey == key) return null
        clear("replaced")
        target = imageView
        requestKey = key
        return ViewerLoadMetrics.previewStarted(
            imageKey = key,
            activeAtStart = activeAtStart,
            viewWidth = imageView.width,
            viewHeight = imageView.height,
            modelType = model.javaClass.simpleName.ifEmpty { model.javaClass.name },
            detail = detail,
        ).also { metricsToken = it }
    }

    fun isCurrent(imageView: android.widget.ImageView, key: String): Boolean =
        target === imageView && requestKey == key

    fun clear(reason: String) {
        metricsToken?.let { ViewerLoadMetrics.previewCleared(it, reason) }
        target?.let { imageView -> Glide.with(imageView).clear(imageView) }
        target = null
        requestKey = null
        metricsToken = null
    }
}

private fun drawableMetrics(drawable: Drawable): String {
    val bitmap = (drawable as? BitmapDrawable)?.bitmap
        ?: return "drawable=${drawable.intrinsicWidth}x${drawable.intrinsicHeight} bitmap=none"
    val hasGainmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
    return "drawable=${drawable.intrinsicWidth}x${drawable.intrinsicHeight} " +
        "bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config} " +
        "bytes=${bitmap.allocationByteCount} gainmap=$hasGainmap"
}

private fun applySavedPreviewTransform(
    imageView: android.widget.ImageView,
    drawable: Drawable,
    state: SubsamplingScaleImageView.ViewState?
) {
    if (state == null) {
        imageView.rotation = 0f
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        imageView.translationX = 0f
        imageView.translationY = 0f
        return
    }

    val drawableWidth = drawable.intrinsicWidth.toFloat()
    val drawableHeight = drawable.intrinsicHeight.toFloat()
    val viewWidth = imageView.width.toFloat()
    val viewHeight = imageView.height.toFloat()
    if (
        drawableWidth <= 0f || drawableHeight <= 0f ||
        viewWidth <= 0f || viewHeight <= 0f ||
        state.baseFitScale <= 0f || state.sourceWidth <= 0 || state.sourceHeight <= 0
    ) return

    val drawableFitScale = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
    val fittedWidth = drawableWidth * drawableFitScale
    val fittedHeight = drawableHeight * drawableFitScale
    val fittedLeft = (viewWidth - fittedWidth) / 2f
    val fittedTop = (viewHeight - fittedHeight) / 2f
    val normalizedCenterX = (state.sourceCenter.x / state.sourceWidth).coerceIn(0f, 1f)
    val normalizedCenterY = (state.sourceCenter.y / state.sourceHeight).coerceIn(0f, 1f)
    val previewCenterX = fittedLeft + normalizedCenterX * fittedWidth
    val previewCenterY = fittedTop + normalizedCenterY * fittedHeight
    val viewCenterX = viewWidth / 2f
    val viewCenterY = viewHeight / 2f
    val relativeScale = (state.scale / state.baseFitScale).coerceIn(0.01f, 1000f)
    val rotation = state.rotationRadians
    val offsetX = (previewCenterX - viewCenterX) * relativeScale
    val offsetY = (previewCenterY - viewCenterY) * relativeScale
    val rotatedOffsetX = offsetX * kotlin.math.cos(rotation) - offsetY * kotlin.math.sin(rotation)
    val rotatedOffsetY = offsetX * kotlin.math.sin(rotation) + offsetY * kotlin.math.cos(rotation)

    imageView.pivotX = viewCenterX
    imageView.pivotY = viewCenterY
    imageView.scaleX = relativeScale
    imageView.scaleY = relativeScale
    imageView.rotation = Math.toDegrees(rotation).toFloat()
    imageView.translationX = -rotatedOffsetX.toFloat()
    imageView.translationY = -rotatedOffsetY.toFloat()
}

private suspend fun resolveGlideDataCacheFile(
    context: android.content.Context,
    model: Any,
    dateModifiedMillis: Long,
    fallbackPath: String,
    imageKey: String,
): String {
    val requestManager = Glide.with(context.applicationContext)
    val request = requestManager
        .downloadOnly()
        .load(model)
        .diskCacheStrategy(DiskCacheStrategy.DATA)
        .skipMemoryCache(true)
        .let { builder ->
            if (dateModifiedMillis > 0L) builder.signature(ObjectKey(dateModifiedMillis)) else builder
        }
    val target = request.submit()
    val token = ViewerLoadMetrics.workStarted(
        "REGION_SOURCE_FILE",
        imageKey,
        "model=${model.javaClass.simpleName}",
    )
    return try {
        val cachedFile = runInterruptible(Dispatchers.IO) { target.get() }
        val resolved = cachedFile
            ?.takeIf { it.isFile && it.canRead() }
            ?.absolutePath
            ?: fallbackPath
        ViewerLoadMetrics.workReady(
            token,
            source = if (resolved == fallbackPath) "ORIGINAL_FALLBACK" else "GLIDE_DATA_CACHE",
            detail = "file=${File(resolved).name} bytes=${File(resolved).length()}",
        )
        resolved
    } catch (e: CancellationException) {
        ViewerLoadMetrics.workCleared(token, "cancelled")
        throw e
    } catch (e: Exception) {
        ViewerLoadMetrics.workCleared(token, "fallback=${e.javaClass.simpleName}")
        fallbackPath
    } finally {
        requestManager.clear(target)
    }
}

private fun applyPreviewTransform(
    imageView: android.widget.ImageView,
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
) {
    imageView.pivotX = imageView.width / 2f
    imageView.pivotY = imageView.height / 2f
    imageView.rotation = 0f
    imageView.scaleX = userScale
    imageView.scaleY = userScale
    imageView.translationX = offsetX
    imageView.translationY = offsetY
}

private fun requiresDeepZoom(state: SubsamplingScaleImageView.ViewState?): Boolean {
    if (state == null || state.baseFitScale <= 0f) return false
    val relativeScale = state.scale / state.baseFitScale
    // A centered state at or below fit-screen is fully represented by the preview.
    // Loading SSIV for it causes an unnecessary renderer handoff and can strand the
    // gesture at the minimum scale. Pan is only meaningful above fit-screen.
    return relativeScale > 1.02f || kotlin.math.abs(state.rotationRadians) > 0.001
}

internal fun updatePreviewInteractionCount(current: Int, delta: Int): Int =
    (current + delta).coerceAtLeast(0)

internal fun canHandoffPreviewToTiles(
    ssivBaseDrawn: Boolean,
    isActivePage: Boolean,
    imageAssigned: Boolean,
    subsamplingReady: Boolean,
    previewGestureInProgress: Boolean,
    previewInteractionCount: Int,
): Boolean =
    ssivBaseDrawn && isActivePage && imageAssigned && !subsamplingReady &&
        !previewGestureInProgress && previewInteractionCount == 0

internal fun canTilesReceiveInput(
    isActivePage: Boolean,
    subsamplingReady: Boolean,
    previewOwnsTransform: Boolean,
): Boolean = isActivePage && subsamplingReady && !previewOwnsTransform

private fun previewTransformState(
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    viewWidth: Int,
    viewHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    orientationDegrees: Int,
    baseFitScaleOverride: Float? = null,
): SubsamplingScaleImageView.ViewState? {
    if (
        viewWidth <= 0 || viewHeight <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        userScale <= 0f
    ) return null

    val swapped = orientationDegrees == 90 || orientationDegrees == 270
    val orientedWidth = if (swapped) sourceHeight else sourceWidth
    val orientedHeight = if (swapped) sourceWidth else sourceHeight
    val baseFitScale = baseFitScaleOverride
        ?.takeIf { it > 0f }
        ?: minOf(
            viewWidth / orientedWidth.toFloat(),
            viewHeight / orientedHeight.toFloat(),
        ).coerceAtLeast(0.0001f)
    val absoluteScale = (baseFitScale * userScale).coerceAtLeast(0.0001f)
    val sourceCenter = PointF(
        (orientedWidth / 2f - offsetX / absoluteScale)
            .coerceIn(0f, orientedWidth.toFloat()),
        (orientedHeight / 2f - offsetY / absoluteScale)
            .coerceIn(0f, orientedHeight.toFloat()),
    )
    return SubsamplingScaleImageView.ViewState(
        scale = absoluteScale,
        baseFitScale = baseFitScale,
        sourceCenter = sourceCenter,
        sourceWidth = orientedWidth,
        sourceHeight = orientedHeight,
        rotationRadians = 0.0,
    )
}

@Composable
internal fun SimpleSubsamplingImageView(
    uri: String,
    filePath: String,
    orientationDegrees: Int = 0,
    modifier: Modifier = Modifier,
    isActivePage: Boolean = true,
    isPagerIdle: Boolean = true,
    isPreviewVisible: Boolean = isActivePage,
    enableSubsampling: Boolean = true,
    dateModifiedMillis: Long = 0L,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
    enableUltraHdr: Boolean = false,
    previewModel: Any? = null,
    metricsDetail: String = "",
    regionDecoderKind: ViewerRegionDecoderKind = ViewerRegionDecoderKind.PLATFORM,
    decoderSourceKey: String = "",
    transformStateStore: ViewerTransformStateStore,
    onContentReadyChanged: (Boolean) -> Unit = {},
    onUltraHdrAvailabilityChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val imagePath = remember(filePath, uri) {
        val file = File(filePath)
        if (filePath.isNotEmpty() && file.exists() && file.isFile) {
            file.absolutePath
        } else {
            uri
        }
    }
    val transformStateKey = remember(imagePath, dateModifiedMillis) {
        "$imagePath:$dateModifiedMillis"
    }

    // Two lifecycle states representing the two phases of Simple-Gallery:
    // 1. previewLoaded: set when Glide finishes loading the full-screen fitCenter bitmap into gesturesView
    // 2. subsamplingReady: set only after SubsamplingScaleImageView draws its first image frame
    var previewLoaded by remember(uri, filePath) { mutableStateOf(false) }
    var subsamplingReady by remember(uri, filePath) { mutableStateOf(false) }
    var previewDrawable by remember(uri, filePath) { mutableStateOf<Drawable?>(null) }
    var imageAssigned by remember(uri, filePath) { mutableStateOf(false) }
    val savedTransformAtAttach = remember(transformStateKey) {
        transformStateStore.get(transformStateKey)
    }
    val indexedOnlyRenderer = regionDecoderKind == ViewerRegionDecoderKind.JXL
    var deepZoomRequested by remember(transformStateKey, indexedOnlyRenderer) {
        mutableStateOf(indexedOnlyRenderer || requiresDeepZoom(savedTransformAtAttach))
    }
    var previewOwnsTransform by remember(transformStateKey, indexedOnlyRenderer) {
        mutableStateOf(!indexedOnlyRenderer && !requiresDeepZoom(savedTransformAtAttach))
    }
    val savedPreviewScale = savedTransformAtAttach
        ?.takeUnless(::requiresDeepZoom)
        ?.let { (it.scale / it.baseFitScale).coerceAtLeast(0.01f) }
        ?: 1f
    val savedPreviewOffsetX = savedTransformAtAttach
        ?.takeUnless(::requiresDeepZoom)
        ?.let { (it.sourceWidth / 2f - it.sourceCenter.x) * it.scale }
        ?: 0f
    val savedPreviewOffsetY = savedTransformAtAttach
        ?.takeUnless(::requiresDeepZoom)
        ?.let { (it.sourceHeight / 2f - it.sourceCenter.y) * it.scale }
        ?: 0f
    var previewUserScale by remember(transformStateKey) { mutableFloatStateOf(savedPreviewScale) }
    var previewOffsetX by remember(transformStateKey) { mutableFloatStateOf(savedPreviewOffsetX) }
    var previewOffsetY by remember(transformStateKey) { mutableFloatStateOf(savedPreviewOffsetY) }
    var previewGestureInProgress by remember(transformStateKey) { mutableStateOf(false) }
    var previewInteractionCount by remember(transformStateKey) { mutableIntStateOf(0) }
    var previewTransformSyncRevision by remember(transformStateKey) { mutableIntStateOf(0) }
    var previewTakeoverPending by remember(transformStateKey) { mutableStateOf(false) }
    var ssivBaseDrawn by remember(transformStateKey) { mutableStateOf(false) }
    var imageSessionGeneration by remember(transformStateKey) { mutableIntStateOf(0) }
    val previewRequestGuard = remember(transformStateKey) { PreviewRequestGuard() }
    var ssivView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
    var imageViewRef by remember { mutableStateOf<android.widget.ImageView?>(null) }
    var metricsSessionId by remember(transformStateKey) { mutableStateOf(0L) }
    val renderedLayer = remember(transformStateKey) { AtomicReference("UNSET") }
    val currentOnContentReadyChanged by rememberUpdatedState(onContentReadyChanged)
    val currentPreviewUserScale by rememberUpdatedState(previewUserScale)
    val currentPreviewOffsetX by rememberUpdatedState(previewOffsetX)
    val currentPreviewOffsetY by rememberUpdatedState(previewOffsetY)

    // Active-page bookkeeping is intentionally separate from preview loading.
    // A preview that was loaded while swiping must not be restarted on the settle frame.
    DisposableEffect(isActivePage, transformStateKey) {
        val startedSessionId = if (isActivePage) {
            ViewerLoadMetrics.begin(context.applicationContext, transformStateKey, metricsDetail)
        } else 0L
        ViewerLoadMetrics.event(
            "IMAGE_ACTIVE_STATE",
            "active=$isActivePage session=$startedSessionId previewVisible=$isPreviewVisible " +
                "pagerIdle=$isPagerIdle subsampling=$enableSubsampling",
            imageKey = transformStateKey,
        )
        metricsSessionId = startedSessionId
        onDispose {
            if (startedSessionId != 0L) {
                ViewerLoadMetrics.end(context.applicationContext, transformStateKey, startedSessionId)
            }
            if (metricsSessionId == startedSessionId) {
                metricsSessionId = 0L
            }
        }
    }

    LaunchedEffect(metricsSessionId, transformStateKey) {
        val sessionId = metricsSessionId
        if (sessionId == 0L) return@LaunchedEffect
        delay(250)
        ViewerLoadMetrics.powerSample(context.applicationContext, transformStateKey, sessionId, "250ms")
        delay(750)
        ViewerLoadMetrics.powerSample(context.applicationContext, transformStateKey, sessionId, "1000ms")
        delay(2000)
        ViewerLoadMetrics.powerSample(context.applicationContext, transformStateKey, sessionId, "3000ms")
    }

    // Gainmap copying is only allowed for the settled page and runs away from the UI thread.
    LaunchedEffect(
        enableUltraHdr,
        isActivePage,
        previewLoaded,
        previewDrawable,
        transformStateKey,
    ) {
        if (!enableUltraHdr) {
            UltraHdrTileSupport.clear(transformStateKey)
            onUltraHdrAvailabilityChanged(false)
        } else if (isActivePage && previewLoaded) {
            val drawable = previewDrawable
            val token = ViewerLoadMetrics.workStarted(
                "GAINMAP_CAPTURE",
                transformStateKey,
                "drawable=${drawable?.javaClass?.simpleName ?: "none"}",
            )
            val hasUltraHdr = if (drawable == null) {
                false
            } else {
                withContext(Dispatchers.Default) {
                    UltraHdrTileSupport.capture(transformStateKey, drawable)
                }
            }
            ViewerLoadMetrics.workReady(
                token,
                source = if (hasUltraHdr) "GAINMAP_PRESENT" else "NO_GAINMAP",
            )
            onUltraHdrAvailabilityChanged(hasUltraHdr)
        } else if (!isActivePage) {
            ViewerLoadMetrics.event(
                "GAINMAP_CLEAR_REQUEST",
                "reason=inactive",
                imageKey = transformStateKey,
            )
            UltraHdrTileSupport.clear(transformStateKey)
            onUltraHdrAvailabilityChanged(false)
        }
    }

    // Match Simple Gallery's delayed zoomable layer: retain the current layer while a
    // gesture is in progress, and only start a new tile source after the pager is idle.
    LaunchedEffect(
        isActivePage,
        isPagerIdle,
        enableSubsampling,
        previewLoaded,
        previewDrawable,
        deepZoomRequested,
        ssivView,
        imagePath,
        imageAssigned
    ) {
        val view = ssivView
        when {
            isActivePage && isPagerIdle && enableSubsampling &&
                (previewLoaded || indexedOnlyRenderer) &&
                deepZoomRequested &&
                view != null && !imageAssigned -> {
                val token = ViewerLoadMetrics.workStarted(
                    "SSIV_ASSIGN_IMAGE",
                    transformStateKey,
                    "trigger=on-demand delay=0ms executorActive=${tileTaskExecutor.activeCount} " +
                        "executorQueued=${tileTaskExecutor.queue.size}",
                )
                var tokenFinished = false
                try {
                    ViewerLoadMetrics.tilesScheduled(transformStateKey)
                    // minScaleFactor is relaxed after onReady() so users can shrink below fit-screen.
                    // Restore it before reloading this retained pager view, otherwise SSIV starts
                    // at the stale ~1/3 minimum instead of fit-screen.
                    view.minScaleFactor = 1f
                    view.visibility = View.VISIBLE
                    view.alpha = 0f
                    ssivBaseDrawn = false
                    view.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    val regionSourcePath = if (regionDecoderKind == ViewerRegionDecoderKind.PLATFORM) {
                        val sourceModel = uri
                            .takeIf { it.isNotBlank() }
                            ?.let(Uri::parse)
                            ?: imagePath
                        resolveGlideDataCacheFile(
                            context = context,
                            model = sourceModel,
                            dateModifiedMillis = dateModifiedMillis,
                            fallbackPath = imagePath,
                            imageKey = transformStateKey,
                        )
                    } else {
                        imagePath
                    }
                    imageSessionGeneration += 1
                    imageAssigned = true
                    val normalizedOrientation = ((orientationDegrees % 360) + 360) % 360
                    val borrowedPreview = (previewDrawable as? BitmapDrawable)
                        ?.bitmap
                        ?.takeIf { bitmap ->
                            val sourceRatio = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1)
                            val bitmapRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                            regionDecoderKind == ViewerRegionDecoderKind.PLATFORM &&
                                normalizedOrientation == 0 &&
                                sourceWidth > 0 && sourceHeight > 0 &&
                                !bitmap.isRecycled &&
                                kotlin.math.abs(bitmapRatio / sourceRatio - 1f) < 0.02f
                        }
                    ViewerLoadMetrics.event(
                        "SSIV_BASE_PREVIEW_REUSE",
                        "reused=${borrowedPreview != null} " +
                            "preview=${borrowedPreview?.let { "${it.width}x${it.height}" } ?: "none"} " +
                            "source=${sourceWidth}x$sourceHeight orientation=$normalizedOrientation " +
                            "decoder=$regionDecoderKind",
                        imageKey = transformStateKey,
                    )
                    view.setImage(
                        path = regionSourcePath,
                        borrowedPreview = borrowedPreview,
                        previewSourceWidth = if (borrowedPreview != null) sourceWidth else 0,
                        previewSourceHeight = if (borrowedPreview != null) sourceHeight else 0,
                    )
                    ViewerLoadMetrics.workReady(
                        token,
                        source = "SET_IMAGE_RETURNED",
                        detail = "executorActive=${tileTaskExecutor.activeCount} " +
                            "executorQueued=${tileTaskExecutor.queue.size}",
                    )
                    tokenFinished = true
                } finally {
                    if (!tokenFinished) {
                        ViewerLoadMetrics.workCleared(token, "effect-cancelled")
                    }
                }
            }

            (!isActivePage || !enableSubsampling) && view != null && imageAssigned -> {
                val recycleToken = ViewerLoadMetrics.workStarted(
                    "SSIV_RECYCLE",
                    transformStateKey,
                    "reason=${if (!isActivePage) "inactive" else "subsampling-disabled"}",
                )
                view.snapshotViewState()?.let {
                    transformStateStore.save(transformStateKey, it, "page-inactive")
                }
                imageSessionGeneration += 1
                view.recycle()
                view.visibility = View.GONE
                view.alpha = 0f
                imageAssigned = false
                subsamplingReady = false
                ssivBaseDrawn = false
                ViewerLoadMetrics.workReady(
                    recycleToken,
                    detail = "executorActive=${tileTaskExecutor.activeCount} " +
                        "executorQueued=${tileTaskExecutor.queue.size}",
                )
            }
        }
    }

    // Keep the transformed preview visible until the user's zoom animation has settled.
    // Then transfer the exact scale/center into SSIV and reveal the clear layer in one handoff.
    LaunchedEffect(
        ssivBaseDrawn,
        previewUserScale,
        previewOffsetX,
        previewOffsetY,
        isActivePage,
        imageAssigned,
        subsamplingReady,
        previewGestureInProgress,
        previewInteractionCount,
    ) {
        if (!canHandoffPreviewToTiles(
                ssivBaseDrawn = ssivBaseDrawn,
                isActivePage = isActivePage,
                imageAssigned = imageAssigned,
                subsamplingReady = subsamplingReady,
                previewGestureInProgress = previewGestureInProgress,
                previewInteractionCount = previewInteractionCount,
            )
        ) return@LaunchedEffect

        // The gesture may briefly cross above fit (which starts SSIV) and then finish
        // below fit. In that case the screen-sized preview is already the correct and
        // cheaper renderer. Never perform a late PREVIEW -> TILES swap at the final
        // below-fit scale; that swap is the full-screen background flash.
        if (previewOwnsTransform && previewUserScale <= 1.02f) {
            deepZoomRequested = false
            ViewerLoadMetrics.event(
                "DEEP_ZOOM_HANDOFF_CANCELLED",
                "reason=ended-at-or-below-fit previewScale=$previewUserScale " +
                    "assigned=$imageAssigned baseDrawn=$ssivBaseDrawn",
                imageKey = transformStateKey,
            )
            return@LaunchedEffect
        }

        val view = ssivView ?: return@LaunchedEffect
        val liveSsivState = view.snapshotViewState()
        val handoffState = if (previewOwnsTransform) {
            previewTransformState(
                userScale = previewUserScale,
                offsetX = previewOffsetX,
                offsetY = previewOffsetY,
                viewWidth = view.width,
                viewHeight = view.height,
                sourceWidth = liveSsivState?.sourceWidth ?: sourceWidth,
                sourceHeight = liveSsivState?.sourceHeight ?: sourceHeight,
                orientationDegrees = if (liveSsivState != null) 0 else orientationDegrees,
                baseFitScaleOverride = liveSsivState?.baseFitScale,
            )?.also { transformStateStore.save(transformStateKey, it, "preview-handoff") }
        } else {
            transformStateStore.get(transformStateKey)
        }
        ViewerLoadMetrics.event(
            "DEEP_ZOOM_HANDOFF_BEFORE_RESTORE",
            "intendedScale=${handoffState?.scale ?: -1f} " +
                "intendedCenter=${handoffState?.sourceCenter ?: "none"} " +
                "liveScale=${liveSsivState?.scale ?: -1f} " +
                "liveCenter=${liveSsivState?.sourceCenter ?: "none"} " +
                "previewScale=$previewUserScale previewOffset=${previewOffsetX},${previewOffsetY} " +
                "view=${view.width}x${view.height}",
            imageKey = transformStateKey,
        )
        handoffState?.let(view::restoreViewState)
        val immediateState = view.snapshotViewState()
        val intendedCenterInView = handoffState?.sourceCenter?.let(view::sourceToViewCoord)
        ViewerLoadMetrics.event(
            "DEEP_ZOOM_HANDOFF_AFTER_RESTORE",
            "actualScale=${immediateState?.scale ?: -1f} " +
                "actualCenter=${immediateState?.sourceCenter ?: "none"} " +
                "intendedCenterInView=${intendedCenterInView ?: "none"}",
            imageKey = transformStateKey,
        )
        delay(16)
        if (isActivePage && imageAssigned && ssivBaseDrawn) {
            val frameState = view.snapshotViewState()
            previewOwnsTransform = false
            view.visibility = View.VISIBLE
            view.alpha = 1f
            subsamplingReady = true
            // The first visible SSIV frame keeps using the exact Glide bitmap that
            // completed the zoom animation. Switch to decoded tiles on the next frame,
            // after the renderer handoff is already complete.
            view.postOnAnimation {
                view.releaseBorrowedPreviewWhenTilesReady()
            }
            ViewerLoadMetrics.event(
                "DEEP_ZOOM_HANDOFF",
                "intendedScale=${handoffState?.scale ?: -1f} " +
                    "intendedCenter=${handoffState?.sourceCenter ?: "none"} " +
                    "frameScale=${frameState?.scale ?: view.scale} " +
                    "frameCenter=${frameState?.sourceCenter ?: "none"} " +
                    "previewScale=$previewUserScale previewOffset=${previewOffsetX},${previewOffsetY} " +
                    "view=${view.width}x${view.height} liveBase=${liveSsivState?.baseFitScale ?: -1f} " +
                    "liveSource=${liveSsivState?.sourceWidth ?: -1}x${liveSsivState?.sourceHeight ?: -1} " +
                    "owner=TILES revision=${transformStateStore.revision(transformStateKey)}",
                imageKey = transformStateKey,
            )
        }
    }

    DisposableEffect(
        uri,
        filePath,
        isPreviewVisible,
        imageViewRef,
        dateModifiedMillis,
        previewModel,
        enableUltraHdr,
    ) {
        val imageView = imageViewRef
        if (imageView != null && isPreviewVisible) {
            val savedTransform = transformStateStore.get(transformStateKey)
            val previewUsesContainerTransform = previewOwnsTransform
            imageView.visibility = View.VISIBLE
            // Telephoto's Glide adapter resolves local photos through the MediaStore URI,
            // forces DATA caching, then gives its private cache file to the region decoder.
            // Keep format-specific models unchanged, but use that same route for platform
            // JPEG/PNG decoding instead of decoding every tile from shared external storage.
            val requestModel = if (regionDecoderKind == ViewerRegionDecoderKind.PLATFORM) {
                uri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: imagePath
            } else {
                previewModel ?: imagePath
            }
            val metricsToken = previewRequestGuard.begin(
                imageView = imageView,
                key = transformStateKey,
                detail = metricsDetail,
                activeAtStart = isActivePage,
                model = requestModel,
            )
            if (metricsToken != null) {
                currentOnContentReadyChanged(false)
                imageView.rotation = savedTransform
                    ?.takeUnless { previewUsesContainerTransform }
                    ?.let { Math.toDegrees(it.rotationRadians).toFloat() }
                    ?: 0f
                imageView.scaleX = 1f
                imageView.scaleY = 1f
                imageView.translationX = 0f
                imageView.translationY = 0f
                imageView.alpha = if (savedTransform == null) 1f else 0f

                val requestOptions = RequestOptions()
                    .withViewerTaskCompression()
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .diskCacheStrategy(
                        if (regionDecoderKind == ViewerRegionDecoderKind.PLATFORM) {
                            DiskCacheStrategy.ALL
                        } else {
                            DiskCacheStrategy.RESOURCE
                        },
                    )
                    .downsample(DownsampleStrategy.FIT_CENTER)
                    .priority(if (isActivePage) Priority.IMMEDIATE else Priority.NORMAL)
                    .let { opts ->
                        if (enableUltraHdr) {
                            opts.transform(UltraHdrAwareFitCenter)
                        } else {
                            opts.fitCenter()
                        }
                    }
                    .let { opts ->
                        if (dateModifiedMillis > 0L) opts.signature(ObjectKey(dateModifiedMillis)) else opts
                    }

                Glide.with(context)
                    // Match Simple Gallery's local-photo path exactly. Going through the
                    // MediaStore URI selects Glide's QMediaStore loader even though SSIV
                    // already proved that the original file is directly readable.
                    .load(requestModel)
                    .apply(requestOptions)
                    .listener(object : com.bumptech.glide.request.RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            if (!previewRequestGuard.isCurrent(imageView, transformStateKey)) return false
                            ViewerLoadMetrics.previewFailed(
                                metricsToken,
                                e?.rootCauses?.firstOrNull()?.javaClass?.simpleName
                                    ?: e?.javaClass?.simpleName
                                    ?: "unknown",
                            )
                            previewDrawable = null
                            imageView.alpha = 1f
                            previewLoaded = true
                            currentOnContentReadyChanged(false)
                            android.util.Log.e(
                                "SimpleSubsampling",
                                "Preview load failed for $transformStateKey",
                                e
                            )
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            if (!previewRequestGuard.isCurrent(imageView, transformStateKey)) return false
                            previewDrawable = resource
                            imageView.viewTreeObserver.addOnPreDrawListener(
                                object : android.view.ViewTreeObserver.OnPreDrawListener {
                                    override fun onPreDraw(): Boolean {
                                        if (imageView.viewTreeObserver.isAlive) {
                                            imageView.viewTreeObserver.removeOnPreDrawListener(this)
                                        }
                                        if (previewRequestGuard.isCurrent(imageView, transformStateKey)) {
                                            if (previewUsesContainerTransform) {
                                                applyPreviewTransform(
                                                    imageView,
                                                    currentPreviewUserScale,
                                                    currentPreviewOffsetX,
                                                    currentPreviewOffsetY,
                                                )
                                            } else {
                                                applySavedPreviewTransform(
                                                    imageView,
                                                    imageView.drawable ?: resource,
                                                    savedTransform,
                                                )
                                            }
                                            imageView.alpha = 1f
                                        }
                                        return true
                                    }
                                }
                            )
                            // Never leave a restored preview transparent if a target is detached
                            // before its pre-draw callback is delivered.
                            imageView.post {
                                if (
                                    previewRequestGuard.isCurrent(imageView, transformStateKey) &&
                                    imageView.alpha == 0f
                                ) {
                                    if (previewUsesContainerTransform) {
                                        applyPreviewTransform(
                                            imageView,
                                            currentPreviewUserScale,
                                            currentPreviewOffsetX,
                                            currentPreviewOffsetY,
                                        )
                                    } else {
                                        applySavedPreviewTransform(
                                            imageView,
                                            imageView.drawable ?: resource,
                                            savedTransform,
                                        )
                                    }
                                    imageView.alpha = 1f
                                }
                            }
                            previewLoaded = true
                            currentOnContentReadyChanged(true)
                            ViewerLoadMetrics.previewReady(
                                token = metricsToken,
                                source = dataSource.name,
                                detail = "view=${imageView.width}x${imageView.height} ${drawableMetrics(resource)}",
                            )
                            return false
                        }
                    })
                    .into(imageView)
            } else {
                // The request is deliberately retained across settle/active-page changes.
                // Do not reveal the 200 px cover when no new preview was started.
                currentOnContentReadyChanged(previewLoaded)
            }
        } else {
            previewRequestGuard.clear("not-visible")
            previewDrawable = null
            previewLoaded = false
            currentOnContentReadyChanged(false)
        }
        onDispose { }
    }

    DisposableEffect(previewRequestGuard) {
        onDispose { previewRequestGuard.clear("dispose") }
    }

    // AndroidView can detach temporarily during pager layout/recomposition. Detach is
    // therefore not a resource-lifecycle boundary. Recycle only when this composable is
    // actually disposed; inactive settled pages are handled by the effect above.
    DisposableEffect(ssivView, transformStateKey) {
        val view = ssivView
        onDispose {
            view?.snapshotViewState()?.let {
                transformStateStore.save(transformStateKey, it, "composition-dispose")
            }
            view?.recycle()
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val normalizedOrientation = ((orientationDegrees % 360) + 360) % 360
        val swapped = normalizedOrientation == 90 || normalizedOrientation == 270
        val orientedWidth = (if (swapped) sourceHeight else sourceWidth).coerceAtLeast(1)
        val orientedHeight = (if (swapped) sourceWidth else sourceHeight).coerceAtLeast(1)
        val containerWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val previewFitScale = minOf(
            containerWidth / orientedWidth,
            containerHeight / orientedHeight,
        ).coerceAtLeast(0.0001f)
        val scaleToOriginal = 1f / previewFitScale
        val previewMinScale = minOf(scaleToOriginal / 3f, 1f / 3f).coerceAtLeast(0.01f)
        val previewMaxScale = maxOf(scaleToOriginal * 3f, 3f).coerceAtMost(60f)
        val fitWidthFraction = (orientedWidth * previewFitScale / containerWidth).coerceIn(0f, 1f)
        val fitHeightFraction = (orientedHeight * previewFitScale / containerHeight).coerceIn(0f, 1f)

        ZoomableContainer(
            modifier = Modifier.fillMaxSize(),
            diagnosticsKey = if (ViewerLoadMetrics.isEnabled) transformStateKey else "",
            minScale = previewMinScale,
            maxScale = previewMaxScale,
            scaleToOriginal = scaleToOriginal,
            initialScale = previewUserScale,
            initialOffsetX = previewOffsetX,
            initialOffsetY = previewOffsetY,
            transformSyncRevision = previewTransformSyncRevision,
            enabled = isActivePage && !subsamplingReady && previewOwnsTransform,
            // Apply preview transforms directly to the ImageView. Transforming the
            // outer FrameLayout also transforms its SSIV sibling and makes an atomic
            // tile-to-preview handoff impossible.
            autoApplyTransformations = false,
            imageFitScaleX = fitWidthFraction,
            imageFitScaleY = fitHeightFraction,
            onTap = onClick,
            onZoomGestureStarted = {
                previewGestureInProgress = true
                if (!deepZoomRequested && enableSubsampling) {
                    ViewerLoadMetrics.event(
                        "DEEP_ZOOM_REQUEST",
                        "reason=user-gesture previewLoaded=$previewLoaded pagerIdle=$isPagerIdle",
                        imageKey = transformStateKey,
                    )
                    deepZoomRequested = true
                }
            },
            onZoomGestureEnded = {
                previewGestureInProgress = false
                if (previewOwnsTransform && previewUserScale <= 1.02f) {
                    // A gesture that briefly crossed fit no longer needs a renderer
                    // handoff when it finishes below fit.
                    deepZoomRequested = false
                }
                ViewerLoadMetrics.event(
                    "DEEP_ZOOM_GESTURE_END",
                    "previewScale=$previewUserScale previewOffset=${previewOffsetX},${previewOffsetY}",
                    imageKey = transformStateKey,
                )
            },
            onInteractionDelta = { delta ->
                previewInteractionCount = updatePreviewInteractionCount(
                    previewInteractionCount,
                    delta,
                )
            },
            onExternalTransformSynced = {
                if (previewTakeoverPending) {
                    imageViewRef?.let { imageView ->
                        applyPreviewTransform(
                            imageView,
                            previewUserScale,
                            previewOffsetX,
                            previewOffsetY,
                        )
                        imageView.alpha = 1f
                        imageView.visibility = View.VISIBLE
                    }
                    ssivView?.let { view ->
                        view.alpha = 0f
                        view.visibility = View.GONE
                    }
                    previewOwnsTransform = true
                    subsamplingReady = false
                    deepZoomRequested = false
                    previewTakeoverPending = false
                    ViewerLoadMetrics.event(
                        "PREVIEW_TAKEOVER_COMPLETE",
                        "scale=$previewUserScale offset=${previewOffsetX},${previewOffsetY} " +
                            "revision=$previewTransformSyncRevision",
                        imageKey = transformStateKey,
                    )
                }
            },
            onTransformFrame = { scale, offsetX, offsetY ->
                if (previewOwnsTransform) {
                    imageViewRef?.let { imageView ->
                        applyPreviewTransform(imageView, scale, offsetX, offsetY)
                    }
                }
            },
            onTransformChanged = { scale, offsetX, offsetY ->
                if (previewOwnsTransform) {
                    previewUserScale = scale
                    previewOffsetX = offsetX
                    previewOffsetY = offsetY
                    imageViewRef?.let { imageView ->
                        applyPreviewTransform(imageView, scale, offsetX, offsetY)
                    }
                    previewTransformState(
                        userScale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        viewWidth = constraints.maxWidth,
                        viewHeight = constraints.maxHeight,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        orientationDegrees = normalizedOrientation,
                    )?.let {
                        transformStateStore.save(transformStateKey, it, "preview-gesture")
                    }
                }
            },
        ) {
            // Single fixed AndroidView holding the preview and deferred SSIV clear layer.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            ViewerLoadMetrics.event(
                "ANDROID_VIEW_FACTORY",
                "previewVisible=$isPreviewVisible active=$isActivePage decoder=$regionDecoderKind",
                imageKey = transformStateKey,
            )
            val frameLayout = android.widget.FrameLayout(ctx)
            val imageView = android.widget.ImageView(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            val ssiv = object : SubsamplingScaleImageView(ctx) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    // setImage() makes SSIV visible (with alpha=0) so it can prepare and draw
                    // tiles behind the preview. A transparent View still receives touches,
                    // though, and must not mutate its fit-scale state before the atomic handoff.
                    if (!isEnabled) return false
                    return super.onTouchEvent(event)
                }
            }.apply ssivView@ {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isEnabled = false

                val displayMetrics = ctx.resources.displayMetrics
                val averageDpi = (displayMetrics.xdpi + displayMetrics.ydpi) / 2
                val minTileDpi = when {
                    averageDpi > 400 -> 280
                    averageDpi > 300 -> 220
                    else -> 160
                }
                setMinimumTileDpi(minTileDpi)
                setMaxTileSize(if (regionDecoderKind == ViewerRegionDecoderKind.PLATFORM) 4096 else 2048)
                taskExecutor = tileDecodeExecutor
                cacheTaskExecutor = tileCacheWriteExecutor
                setActiveTileMemoryCache(isActivePage)
                rotationEnabled = true
                doubleTapReturnsToFit = true
                // Direct fit-preview gestures already clamp at their configured minimum.
                // Keep the deferred tile renderer on that same rule so crossing into SSIV does
                // not introduce overshrink/rebound or a transient off-centre frame.
                strictScaleBounds = true
                orientation = ((orientationDegrees % 360) + 360) % 360

                val bitmapDecoder = object : DecoderFactory<ImageDecoder> {
                    override fun make() = GlideBaseImageDecoder(dateModifiedMillis)
                }
                val regionDecoder = object : DecoderFactory<ImageRegionDecoder> {
                    override fun make(): ImageRegionDecoder = when (regionDecoderKind) {
                        ViewerRegionDecoderKind.PLATFORM -> FastRegionDecoder(
                            minTileDpi = minTileDpi,
                            imageVersion = "$imagePath:$dateModifiedMillis",
                            indexedSourcePath = imagePath.takeIf { path ->
                                File(path).let { it.isFile && it.canRead() }
                            },
                            knownSourceWidth = sourceWidth,
                            knownSourceHeight = sourceHeight,
                        )
                        ViewerRegionDecoderKind.TIFF -> TiffRegionDecoder()
                        ViewerRegionDecoderKind.SVG -> SvgRegionDecoder()
                        ViewerRegionDecoderKind.RAW_EMBEDDED -> RawEmbeddedPreviewRegionDecoder(
                            sourceKey = decoderSourceKey,
                            sourcePath = imagePath,
                        )
                        ViewerRegionDecoderKind.BMP -> BmpRegionDecoder(imagePath)
                        ViewerRegionDecoderKind.JXL -> JxlRegionDecoder(imagePath)
                    }
                }
                bitmapDecoderFactory = bitmapDecoder
                regionDecoderFactory = regionDecoder

                doubleTapZoomScale = 1f
                visibility = View.GONE
                diagnosticsListener = if (ViewerLoadMetrics.isEnabled) {
                    { detail ->
                        ViewerLoadMetrics.event(
                            "SSIV_TOUCH_SAMPLE",
                            detail,
                            imageKey = transformStateKey,
                        )
                    }
                } else {
                    null
                }

                setOnClickListener {
                    onClick()
                }

                onImageEventListener = object : SubsamplingScaleImageView.OnImageEventListener {
                    override fun onReady() {
                        val fitScale = scale.coerceAtLeast(0.001f)
                        minScaleFactor = minOf(1f / 3f, 1f / (3f * fitScale))
                        maxScale = maxOf(fitScale * 3f, 3f).coerceAtMost(60f)
                        doubleTapZoomScale = 1f.coerceAtMost(maxScale)
                        transformStateStore.get(transformStateKey)?.let {
                            this@ssivView.restoreViewState(it)
                            ViewerLoadMetrics.event(
                                "SSIV_READY_RESTORE",
                                "revision=${transformStateStore.revision(transformStateKey)} " +
                                    "scale=${it.scale} center=${it.sourceCenter}",
                                imageKey = transformStateKey,
                            )
                        }
                        ViewerLoadMetrics.tilesReady(transformStateKey)
                        ViewerLoadMetrics.event(
                            "SSIV_READY",
                            "source=${sWidth}x${sHeight} scale=$scale minScaleFactor=$minScaleFactor " +
                                "maxScale=$maxScale executorActive=${tileTaskExecutor.activeCount} " +
                                "executorQueued=${tileTaskExecutor.queue.size}",
                            imageKey = transformStateKey,
                        )
                    }

                    override fun onImageDrawn() {
                        ssivBaseDrawn = true
                        ViewerLoadMetrics.event(
                            "SSIV_BASE_DRAWN",
                            "scale=$scale center=${snapshotViewState()?.sourceCenter} " +
                                "previewScale=$previewUserScale",
                            imageKey = transformStateKey,
                        )
                    }

                    override fun onImageLoadError(e: Exception) {
                        ViewerLoadMetrics.event(
                            "SSIV_LOAD_ERROR",
                            "error=${e.javaClass.simpleName}:${e.message}",
                            imageKey = transformStateKey,
                        )
                        android.util.Log.e("SimpleSubsampling", "SSIV load error: $e")
                        subsamplingReady = false
                        ssivBaseDrawn = false
                        visibility = View.GONE
                        imageView.alpha = 1f
                        imageView.visibility = View.VISIBLE
                    }

                    override fun onImageRotation(degrees: Int) {
                        this@ssivView.snapshotViewState()?.let {
                            transformStateStore.save(transformStateKey, it, "rotation")
                        }
                    }

                    override fun onUpEvent() {
                        val immediateState = this@ssivView.snapshotViewState()
                        immediateState?.let {
                            transformStateStore.save(transformStateKey, it, "touch-up-immediate")
                        }
                        val saveGeneration = imageSessionGeneration
                        ViewerLoadMetrics.event(
                            "SSIV_TOUCH_UP",
                            "immediateScale=${immediateState?.scale ?: -1f} " +
                                "immediateCenter=${immediateState?.sourceCenter ?: "none"} " +
                                "generation=$saveGeneration revision=${transformStateStore.revision(transformStateKey)}",
                            imageKey = transformStateKey,
                        )
                        // SSIV may spend 200 ms snapping scale/rotation to bounds after
                        // the fingers lift. Save once after that animation, never per frame.
                        this@ssivView.postDelayed({
                            if (saveGeneration != imageSessionGeneration || !imageAssigned) {
                                ViewerLoadMetrics.event(
                                    "SSIV_TOUCH_SETTLED_SKIPPED",
                                    "savedGeneration=$saveGeneration currentGeneration=$imageSessionGeneration " +
                                        "assigned=$imageAssigned",
                                    imageKey = transformStateKey,
                                )
                                return@postDelayed
                            }
                            this@ssivView.snapshotViewState()?.let { state ->
                                val relativeScale = if (state.baseFitScale > 0f) {
                                    state.scale / state.baseFitScale
                                } else {
                                    Float.POSITIVE_INFINITY
                                }
                                val returnToPreview =
                                    relativeScale <= 1.02f &&
                                        kotlin.math.abs(state.rotationRadians) <= 0.001
                                val settledState = if (returnToPreview) {
                                    // Below fit, the complete image is visible and therefore has
                                    // no valid pan range. Persist an exactly centred state even if
                                    // the final MotionEvent was cancelled by the parent Pager.
                                    state.copy(
                                        sourceCenter = PointF(
                                            state.sourceWidth / 2f,
                                            state.sourceHeight / 2f,
                                        ),
                                    )
                                } else {
                                    state
                                }
                                transformStateStore.save(
                                    transformStateKey,
                                    settledState,
                                    if (returnToPreview) "touch-settled-preview" else "touch-settled",
                                )
                                ViewerLoadMetrics.event(
                                    "SSIV_TOUCH_SETTLED",
                                    "scale=${settledState.scale} center=${settledState.sourceCenter} " +
                                        "base=${settledState.baseFitScale} rotation=${settledState.rotationRadians} " +
                                        "generation=$saveGeneration revision=${transformStateStore.revision(transformStateKey)}",
                                    imageKey = transformStateKey,
                                )
                                if (returnToPreview && !indexedOnlyRenderer && !previewTakeoverPending) {
                                    previewUserScale = relativeScale.coerceAtLeast(0.01f)
                                    previewOffsetX = 0f
                                    previewOffsetY = 0f
                                    previewTakeoverPending = true
                                    previewTransformSyncRevision += 1
                                    ViewerLoadMetrics.event(
                                        "PREVIEW_TAKEOVER_REQUEST",
                                        "scale=$previewUserScale fromCenter=${state.sourceCenter} " +
                                            "toCenter=${settledState.sourceCenter} " +
                                            "revision=$previewTransformSyncRevision",
                                        imageKey = transformStateKey,
                                    )
                                }
                            }
                        }, 220L)
                    }
                }

                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        ViewerLoadMetrics.event(
                            "SSIV_VIEW_ATTACHED",
                            "assigned=$imageAssigned ready=$subsamplingReady " +
                                "generation=$imageSessionGeneration",
                            imageKey = transformStateKey,
                        )
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        val token = ViewerLoadMetrics.workStarted(
                            "SSIV_VIEW_DETACHED",
                            transformStateKey,
                        )
                        this@ssivView.snapshotViewState()?.let {
                            transformStateStore.save(transformStateKey, it, "view-detached")
                        }
                        ViewerLoadMetrics.workReady(
                            token,
                            detail = "generation=$imageSessionGeneration retained=true " +
                                "assigned=$imageAssigned ready=$subsamplingReady",
                        )
                    }
                })
            }

            frameLayout.addView(imageView)
            frameLayout.addView(ssiv)
            imageViewRef = imageView
            ssivView = ssiv
            frameLayout
        },
        update = {
            ssivView?.let { view ->
                view.isEnabled = canTilesReceiveInput(
                    isActivePage = isActivePage,
                    subsamplingReady = subsamplingReady,
                    previewOwnsTransform = previewOwnsTransform,
                )
                view.setActiveTileMemoryCache(isActivePage)
            }
            val imageView = imageViewRef
            if (imageView != null) {
                val layer = when {
                    !isPreviewVisible -> "HIDDEN"
                    isActivePage && subsamplingReady -> "TILES"
                    else -> "PREVIEW"
                }
                val previousLayer = renderedLayer.getAndSet(layer)
                if (previousLayer != layer) {
                    ViewerLoadMetrics.event(
                        "VIEWER_LAYER_CHANGE",
                        "from=$previousLayer to=$layer active=$isActivePage " +
                            "previewVisible=$isPreviewVisible previewLoaded=$previewLoaded " +
                            "tilesReady=$subsamplingReady assigned=$imageAssigned " +
                            "owner=${if (previewOwnsTransform) "PREVIEW" else "TILES"} " +
                            "generation=$imageSessionGeneration revision=${transformStateStore.revision(transformStateKey)}",
                        imageKey = transformStateKey,
                    )
                }
                if (layer == "PREVIEW") {
                    // Preserve the transparent restore handoff until the new drawable has
                    // received its saved transform. A retained preview returning from tiles
                    // must first receive the newest SSIV transform. Otherwise the stale
                    // fit/original-size preview flashes for one frame while swiping or reloading.
                    if (previousLayer == "TILES" && previewLoaded) {
                        val drawable = imageView.drawable
                        val latestState = transformStateStore.get(transformStateKey)
                        if (previewOwnsTransform) {
                            applyPreviewTransform(
                                imageView,
                                previewUserScale,
                                previewOffsetX,
                                previewOffsetY,
                            )
                        } else if (drawable != null && latestState != null) {
                            applySavedPreviewTransform(imageView, drawable, latestState)
                        }
                        ViewerLoadMetrics.event(
                            "PREVIEW_REVEAL_FROM_TILES",
                            "appliedSaved=${drawable != null && latestState != null} " +
                                "revision=${transformStateStore.revision(transformStateKey)}",
                            imageKey = transformStateKey,
                        )
                        imageView.visibility = View.VISIBLE
                        imageView.alpha = 1f
                    } else {
                        if (previewOwnsTransform) {
                            applyPreviewTransform(
                                imageView,
                                previewUserScale,
                                previewOffsetX,
                                previewOffsetY,
                            )
                        }
                        imageView.visibility = View.VISIBLE
                    }
                } else {
                    imageView.alpha = 0f
                    imageView.visibility = View.GONE
                }
            }
        }
            )
        }
    }
}
