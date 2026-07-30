package com.pixel.gallery.ui.viewer

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.PointF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pixel.gallery.ui.viewer.decoders.UltraHdrTileSupport
import com.pixel.gallery.ui.viewer.decoders.UltraHdrAwareFitCenter
import com.pixel.gallery.ui.viewer.formats.ViewerRegionDecoderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Custom LIFO (Last-In-First-Out) Queue to prioritize newly submitted active page tasks
class LIFOLinkedBlockingDeque<T> : LinkedBlockingDeque<T>() {
    override fun offer(e: T): Boolean {
        return super.offerFirst(e)
    }

    override fun offer(e: T, timeout: Long, unit: TimeUnit): Boolean {
        return super.offerFirst(e, timeout, unit)
    }

    override fun add(e: T): Boolean {
        return super.offerFirst(e)
    }

    override fun put(e: T) {
        super.putFirst(e)
    }
}

// Custom Executor that supports purging pending tasks to clear stale page backlog instantly
class LIFOThreadPoolExecutor(corePoolSize: Int, maximumPoolSize: Int, keepAliveTime: Long, unit: TimeUnit) :
    ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, LIFOLinkedBlockingDeque<Runnable>()) {

    fun purgePendingTasks(): Int {
        val count = queue.size
        queue.clear()
        return count
    }
}

private val tileDecodeExecutor = LIFOThreadPoolExecutor(
    corePoolSize = 2,
    maximumPoolSize = 2,
    keepAliveTime = 60L,
    unit = TimeUnit.SECONDS
).apply {
    allowCoreThreadTimeOut(true)
}

internal class ViewerTransformStateStore {
    private val states = HashMap<String, SubsamplingScaleImageView.ViewState>()

    fun get(key: String): SubsamplingScaleImageView.ViewState? = states[key]

    fun save(key: String, state: SubsamplingScaleImageView.ViewState) {
        states[key] = state
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

private fun requiresDeepZoom(state: SubsamplingScaleImageView.ViewState?): Boolean {
    if (state == null || state.baseFitScale <= 0f) return false
    val relativeScale = state.scale / state.baseFitScale
    val centeredX = state.sourceWidth / 2f
    val centeredY = state.sourceHeight / 2f
    return kotlin.math.abs(relativeScale - 1f) > 0.02f ||
        kotlin.math.abs(state.sourceCenter.x - centeredX) > 1f ||
        kotlin.math.abs(state.sourceCenter.y - centeredY) > 1f ||
        kotlin.math.abs(state.rotationRadians) > 0.001
}

private fun previewTransformState(
    userScale: Float,
    offsetX: Float,
    offsetY: Float,
    viewWidth: Int,
    viewHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    orientationDegrees: Int,
): SubsamplingScaleImageView.ViewState? {
    if (
        viewWidth <= 0 || viewHeight <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0 ||
        userScale <= 0f
    ) return null

    val swapped = orientationDegrees == 90 || orientationDegrees == 270
    val orientedWidth = if (swapped) sourceHeight else sourceWidth
    val orientedHeight = if (swapped) sourceWidth else sourceHeight
    val baseFitScale = minOf(
        viewWidth / orientedWidth.toFloat(),
        viewHeight / orientedHeight.toFloat(),
    ).coerceAtLeast(0.0001f)
    val absoluteScale = (baseFitScale * userScale).coerceAtLeast(0.0001f)
    val sourceCenter = PointF(
        (orientedWidth / 2f - offsetX / absoluteScale).coerceIn(0f, orientedWidth.toFloat()),
        (orientedHeight / 2f - offsetY / absoluteScale).coerceIn(0f, orientedHeight.toFloat()),
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
    var deepZoomRequested by remember(transformStateKey) {
        mutableStateOf(requiresDeepZoom(savedTransformAtAttach))
    }
    val previewOwnsTransform = remember(transformStateKey) {
        !requiresDeepZoom(savedTransformAtAttach)
    }
    var previewUserScale by remember(transformStateKey) { mutableFloatStateOf(1f) }
    var previewOffsetX by remember(transformStateKey) { mutableFloatStateOf(0f) }
    var previewOffsetY by remember(transformStateKey) { mutableFloatStateOf(0f) }
    var ssivBaseDrawn by remember(transformStateKey) { mutableStateOf(false) }
    val previewRequestGuard = remember(transformStateKey) { PreviewRequestGuard() }
    var ssivView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
    var imageViewRef by remember { mutableStateOf<android.widget.ImageView?>(null) }
    var metricsSessionId by remember(transformStateKey) { mutableStateOf(0L) }
    val renderedLayer = remember(transformStateKey) { AtomicReference("UNSET") }

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
        deepZoomRequested,
        ssivView,
        imagePath,
        imageAssigned
    ) {
        val view = ssivView
        when {
            isActivePage && isPagerIdle && enableSubsampling && previewLoaded &&
                deepZoomRequested &&
                view != null && !imageAssigned -> {
                val token = ViewerLoadMetrics.workStarted(
                    "SSIV_ASSIGN_IMAGE",
                    transformStateKey,
                    "trigger=on-demand delay=0ms executorActive=${tileDecodeExecutor.activeCount} " +
                        "executorQueued=${tileDecodeExecutor.queue.size}",
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
                    imageAssigned = true
                    view.setImage(imagePath)
                    ViewerLoadMetrics.workReady(
                        token,
                        source = "SET_IMAGE_RETURNED",
                        detail = "executorActive=${tileDecodeExecutor.activeCount} " +
                            "executorQueued=${tileDecodeExecutor.queue.size}",
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
                view.snapshotViewState()?.let { transformStateStore.save(transformStateKey, it) }
                val purged = tileDecodeExecutor.purgePendingTasks()
                view.recycle()
                view.visibility = View.GONE
                view.alpha = 0f
                imageAssigned = false
                subsamplingReady = false
                ssivBaseDrawn = false
                ViewerLoadMetrics.workReady(
                    recycleToken,
                    detail = "purged=$purged executorActive=${tileDecodeExecutor.activeCount}",
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
    ) {
        if (
            !ssivBaseDrawn || !isActivePage || !imageAssigned || subsamplingReady
        ) return@LaunchedEffect

        delay(120)
        val view = ssivView ?: return@LaunchedEffect
        val handoffState = if (previewOwnsTransform) {
            previewTransformState(
                userScale = previewUserScale,
                offsetX = previewOffsetX,
                offsetY = previewOffsetY,
                viewWidth = view.width,
                viewHeight = view.height,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                orientationDegrees = orientationDegrees,
            )?.also { transformStateStore.save(transformStateKey, it) }
        } else {
            transformStateStore.get(transformStateKey)
        }
        handoffState?.let(view::restoreViewState)
        delay(16)
        if (isActivePage && imageAssigned && ssivBaseDrawn) {
            view.alpha = 1f
            subsamplingReady = true
            ViewerLoadMetrics.event(
                "DEEP_ZOOM_HANDOFF",
                "scale=${handoffState?.scale ?: view.scale} " +
                    "center=${handoffState?.sourceCenter ?: "none"}",
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
            imageView.visibility = View.VISIBLE
            val requestModel = previewModel ?: imagePath
            val metricsToken = previewRequestGuard.begin(
                imageView = imageView,
                key = transformStateKey,
                detail = metricsDetail,
                activeAtStart = isActivePage,
                model = requestModel,
            )
            if (metricsToken != null) {
                imageView.rotation = savedTransform
                    ?.let { Math.toDegrees(it.rotationRadians).toFloat() }
                    ?: 0f
                imageView.scaleX = 1f
                imageView.scaleY = 1f
                imageView.translationX = 0f
                imageView.translationY = 0f
                imageView.alpha = if (savedTransform == null) 1f else 0f

                val requestOptions = RequestOptions()
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
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
                    .load(previewModel ?: imagePath)
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
                                            applySavedPreviewTransform(
                                                imageView,
                                                imageView.drawable ?: resource,
                                                savedTransform
                                            )
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
                                    applySavedPreviewTransform(
                                        imageView,
                                        imageView.drawable ?: resource,
                                        savedTransform
                                    )
                                    imageView.alpha = 1f
                                }
                            }
                            previewLoaded = true
                            ViewerLoadMetrics.previewReady(
                                token = metricsToken,
                                source = dataSource.name,
                                detail = "view=${imageView.width}x${imageView.height} ${drawableMetrics(resource)}",
                            )
                            return false
                        }
                    })
                    .into(imageView)
            }
        } else {
            previewRequestGuard.clear("not-visible")
            previewDrawable = null
            previewLoaded = false
        }
        onDispose { }
    }

    DisposableEffect(previewRequestGuard) {
        onDispose { previewRequestGuard.clear("dispose") }
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
            minScale = previewMinScale,
            maxScale = previewMaxScale,
            scaleToOriginal = scaleToOriginal,
            enabled = isActivePage && !subsamplingReady,
            autoApplyTransformations = !subsamplingReady,
            imageFitScaleX = fitWidthFraction,
            imageFitScaleY = fitHeightFraction,
            onTap = onClick,
            onZoomGestureStarted = {
                if (!deepZoomRequested && enableSubsampling) {
                    ViewerLoadMetrics.event(
                        "DEEP_ZOOM_REQUEST",
                        "reason=user-gesture previewLoaded=$previewLoaded pagerIdle=$isPagerIdle",
                        imageKey = transformStateKey,
                    )
                    deepZoomRequested = true
                }
            },
            onTransformChanged = { scale, offsetX, offsetY ->
                if (!subsamplingReady && previewOwnsTransform) {
                    previewUserScale = scale
                    previewOffsetX = offsetX
                    previewOffsetY = offsetY
                    previewTransformState(
                        userScale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        viewWidth = constraints.maxWidth,
                        viewHeight = constraints.maxHeight,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        orientationDegrees = normalizedOrientation,
                    )?.let { transformStateStore.save(transformStateKey, it) }
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
            val ssiv = SubsamplingScaleImageView(ctx).apply ssivView@ {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

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
                rotationEnabled = true
                doubleTapReturnsToFit = true
                orientation = ((orientationDegrees % 360) + 360) % 360

                val bitmapDecoder = object : DecoderFactory<ImageDecoder> {
                    override fun make() = GlideBaseImageDecoder(dateModifiedMillis)
                }
                val regionDecoder = object : DecoderFactory<ImageRegionDecoder> {
                    override fun make(): ImageRegionDecoder = when (regionDecoderKind) {
                        ViewerRegionDecoderKind.PLATFORM -> FastRegionDecoder(
                            minTileDpi = minTileDpi,
                            imageVersion = "$imagePath:$dateModifiedMillis",
                            knownSourceWidth = sourceWidth,
                            knownSourceHeight = sourceHeight,
                        )
                        ViewerRegionDecoderKind.TIFF -> TiffRegionDecoder()
                        ViewerRegionDecoderKind.SVG -> SvgRegionDecoder()
                        ViewerRegionDecoderKind.RAW_EMBEDDED -> RawEmbeddedPreviewRegionDecoder(decoderSourceKey)
                    }
                }
                bitmapDecoderFactory = bitmapDecoder
                regionDecoderFactory = regionDecoder

                doubleTapZoomScale = 1f
                visibility = View.GONE

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
                        }
                        ViewerLoadMetrics.tilesReady(transformStateKey)
                        ViewerLoadMetrics.event(
                            "SSIV_READY",
                            "source=${sWidth}x${sHeight} scale=$scale minScaleFactor=$minScaleFactor " +
                                "maxScale=$maxScale executorActive=${tileDecodeExecutor.activeCount} " +
                                "executorQueued=${tileDecodeExecutor.queue.size}",
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
                            transformStateStore.save(transformStateKey, it)
                        }
                    }

                    override fun onUpEvent() {
                        // SSIV may spend 200 ms snapping scale/rotation to bounds after
                        // the fingers lift. Save once after that animation, never per frame.
                        this@ssivView.postDelayed({
                            this@ssivView.snapshotViewState()?.let {
                                transformStateStore.save(transformStateKey, it)
                            }
                        }, 220L)
                    }
                }

                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        ViewerLoadMetrics.event(
                            "SSIV_VIEW_ATTACHED",
                            imageKey = transformStateKey,
                        )
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        val token = ViewerLoadMetrics.workStarted(
                            "SSIV_DETACH_RECYCLE",
                            transformStateKey,
                        )
                        this@ssivView.snapshotViewState()?.let {
                            transformStateStore.save(transformStateKey, it)
                        }
                        recycle()
                        ViewerLoadMetrics.workReady(token)
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
                            "tilesReady=$subsamplingReady",
                        imageKey = transformStateKey,
                    )
                }
                imageView.visibility = if (layer == "PREVIEW") View.VISIBLE else View.GONE
            }
        }
            )
        }
    }
}
