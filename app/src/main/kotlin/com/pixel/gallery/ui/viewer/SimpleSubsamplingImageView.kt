package com.pixel.gallery.ui.viewer

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
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
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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

    fun purgePendingTasks() {
        queue.clear()
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

@Composable
internal fun SimpleSubsamplingImageView(
    uri: String,
    filePath: String,
    orientationDegrees: Int = 0,
    modifier: Modifier = Modifier,
    isActivePage: Boolean = true,
    isPreviewVisible: Boolean = isActivePage,
    enableSubsampling: Boolean = true,
    dateModifiedMillis: Long = 0L,
    previewModel: Any? = null,
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
    // 2. subsamplingReady: set when SubsamplingScaleImageView finishes its onReady() event
    var previewLoaded by remember(uri, filePath) { mutableStateOf(false) }
    var subsamplingReady by remember(uri, filePath) { mutableStateOf(false) }
    var ssivView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
    var imageViewRef by remember { mutableStateOf<android.widget.ImageView?>(null) }

    // Exactly matching Simple-Gallery scheduleZoomableView() (ZOOMABLE_VIEW_LOAD_DELAY = 100ms):
    LaunchedEffect(isActivePage, enableSubsampling, previewLoaded, ssivView, imagePath) {
        val view = ssivView
        if (isActivePage && enableSubsampling && previewLoaded && view != null && !subsamplingReady) {
            delay(100)
            ViewerLoadMetrics.tilesScheduled("$imagePath:$dateModifiedMillis")
            // minScaleFactor is relaxed after onReady() so users can shrink below fit-screen.
            // Restore it before reloading this retained pager view, otherwise SSIV starts
            // at the stale ~1/3 minimum instead of fit-screen.
            view.minScaleFactor = 1f
            view.visibility = View.VISIBLE
            view.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            view.setImage(imagePath)
        } else if ((!isActivePage || !enableSubsampling) && view != null) {
            view.snapshotViewState()?.let { transformStateStore.save(transformStateKey, it) }
            view.recycle()
            view.visibility = View.GONE
            subsamplingReady = false
        }
    }

    DisposableEffect(
        uri,
        filePath,
        isActivePage,
        isPreviewVisible,
        imageViewRef,
        dateModifiedMillis,
        previewModel
    ) {
        val imageView = imageViewRef
        if (imageView != null && isPreviewVisible) {
            val savedTransform = transformStateStore.get(transformStateKey)
            if (isActivePage) ViewerLoadMetrics.begin("$imagePath:$dateModifiedMillis")
            imageView.visibility = View.VISIBLE
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
                .transform(UltraHdrAwareFitCenter)
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
                        UltraHdrTileSupport.clear(transformStateKey)
                        onUltraHdrAvailabilityChanged(false)
                        imageView.alpha = 1f
                        previewLoaded = true
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<Drawable>,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        val hasUltraHdr = isActivePage &&
                            UltraHdrTileSupport.capture(transformStateKey, resource)
                        onUltraHdrAvailabilityChanged(hasUltraHdr)
                        imageView.viewTreeObserver.addOnPreDrawListener(
                            object : android.view.ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    if (imageView.viewTreeObserver.isAlive) {
                                        imageView.viewTreeObserver.removeOnPreDrawListener(this)
                                    }
                                    applySavedPreviewTransform(
                                        imageView,
                                        imageView.drawable ?: resource,
                                        savedTransform
                                    )
                                    imageView.alpha = 1f
                                    return true
                                }
                            }
                        )
                        previewLoaded = true
                        if (isActivePage) {
                            ViewerLoadMetrics.previewReady(
                                "$imagePath:$dateModifiedMillis",
                                dataSource.name
                            )
                        }
                        return false
                    }
                })
                .into(imageView)
        } else if (imageView != null && !isPreviewVisible) {
            Glide.with(context).clear(imageView)
            previewLoaded = false
        }
        onDispose {
            if (imageView != null && !isPreviewVisible) {
                Glide.with(context).clear(imageView)
            }
            UltraHdrTileSupport.clear(transformStateKey)
            onUltraHdrAvailabilityChanged(false)
            if (isActivePage) {
                ViewerLoadMetrics.end("$imagePath:$dateModifiedMillis")
            }
        }
    }

    // Single fixed AndroidView holding FrameLayout matching pager_photo_item.xml exactly
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
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
                            imageVersion = "$imagePath:$dateModifiedMillis"
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
                        subsamplingReady = true
                        ViewerLoadMetrics.tilesReady("$imagePath:$dateModifiedMillis")
                        imageView.visibility = View.GONE
                    }

                    override fun onImageLoadError(e: Exception) {
                        android.util.Log.e("SimpleSubsampling", "SSIV load error: $e")
                        visibility = View.GONE
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
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {
                        this@ssivView.snapshotViewState()?.let {
                            transformStateStore.save(transformStateKey, it)
                        }
                        recycle()
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
                imageView.visibility = when {
                    !isPreviewVisible -> View.GONE
                    isActivePage && subsamplingReady -> View.GONE
                    else -> View.VISIBLE
                }
            }
        }
    )
}
