package com.davemorrissey.labs.subscaleview

import android.content.Context
import android.graphics.*
import android.graphics.Paint.Style
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import java.io.File
import java.io.UnsupportedEncodingException
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.*

private const val INTERMEDIATE_SAMPLE_MAX_RECONSTRUCTION_UPSCALE = 1.05f

private fun isPowerOfTwoSample(sampleSize: Int): Boolean =
    sampleSize > 0 && (sampleSize and (sampleSize - 1)) == 0

internal fun maximumScaleForStoredSample(sampleSize: Int): Float {
    if (sampleSize <= 0) return 0f
    val reconstructionUpscale = if (isPowerOfTwoSample(sampleSize)) {
        1f
    } else {
        INTERMEDIATE_SAMPLE_MAX_RECONSTRUCTION_UPSCALE
    }
    return reconstructionUpscale / sampleSize.toFloat()
}

internal fun selectStoredSampleSize(
    availableSamples: Iterable<Int>,
    inverseScale: Float,
    maximumSample: Int = Int.MAX_VALUE,
): Int = availableSamples
    .asSequence()
    .filter { sampleSize ->
        sampleSize in 1..maximumSample &&
            // Power-of-two levels keep their historic exact boundary. Optional
            // intermediate levels may reconstruct by at most 5%, matching the
            // pyramid's existing fit-layer quality allowance. This lets a useful
            // sample=3 level own real gesture landing points such as 1/2.97 without
            // changing the established 1/2/4/8 behaviour.
            sampleSize.toFloat() <= inverseScale *
                if (isPowerOfTwoSample(sampleSize)) {
                    1f
                } else {
                    INTERMEDIATE_SAMPLE_MAX_RECONSTRUCTION_UPSCALE
                }
    }
    .maxOrNull()
    ?: 1

internal fun selectRequiredStoredSampleSize(
    availableSamples: Iterable<Int>,
    effectiveScale: Float,
    currentSampleSize: Int,
    maximumSample: Int,
): Int {
    val samples = availableSamples
        .asSequence()
        .filter { it in 1..maximumSample }
        .distinct()
        .toList()
    val safeScale = effectiveScale.coerceAtLeast(0.000001f)
    val target = selectStoredSampleSize(samples, 1f / safeScale, maximumSample)
    val current = currentSampleSize.takeIf { it in samples } ?: target
    return when {
        target == current -> current
        target < current -> {
            val clearerBoundary = maximumScaleForStoredSample(current)
            val clearerLimit = if (isPowerOfTwoSample(current)) {
                clearerBoundary * (1f + SubsamplingScaleImageView.SAMPLE_SIZE_HYSTERESIS)
            } else {
                // The intermediate level's reconstruction allowance is already its
                // quality limit. Never extend it with the ordinary hysteresis.
                clearerBoundary
            }
            if (safeScale > clearerLimit) target else current
        }
        // [target] has already passed its exact or tolerated quality boundary.
        else -> target
    }.coerceIn(1, maximumSample)
}

// rotation inspired by https://github.com/IndoorAtlas/subsampling-scale-image-view/tree/feature_rotation
open class SubsamplingScaleImageView @JvmOverloads constructor(context: Context, attr: AttributeSet? = null) : ImageView(context, attr) {
    data class ViewState(
        val scale: Float,
        val baseFitScale: Float,
        val sourceCenter: PointF,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotationRadians: Double
    )
    companion object {
        const val FILE_SCHEME = "file://"
        const val ASSET_PREFIX = "$FILE_SCHEME/android_asset/"

        private val TAG = SubsamplingScaleImageView::class.java.simpleName

        private const val ORIENTATION_USE_EXIF = -1
        private const val ORIENTATION_0 = 0
        private const val ORIENTATION_90 = 90
        private const val ORIENTATION_180 = 180
        private const val ORIENTATION_270 = 270

        private const val EASE_OUT_QUAD = 1
        private const val EASE_IN_OUT_QUAD = 2

        private const val TILE_SIZE_AUTO = Integer.MAX_VALUE
        private const val ANIMATION_DURATION = 200L
        private const val FLING_DURATION = 300L
        private const val INSTANT_ANIMATION_DURATION = 10L
        // Keep one ordinary ARGB tile below ~5.5MB. Decoders backed by an independently
        // addressable pyramid can override this with their stored block size so SSIV aligns
        // to the source layout without changing the grid for unrelated formats.
        private const val TARGET_DECODED_TILE_SIZE = 1200
        private const val ARGB_8888_BYTES_PER_PIXEL = 4L
        // A source miss is decoded into one temporary sampled fragment and then split
        // into the visible tiles it covers. Two 1024-class ARGB tiles keep one decode wave
        // near 8MB and the transient fragment + split copies near 16MB. This preserves the
        // scan-amortisation benefit without recreating the 30-60MB allocation bursts seen
        // in the power trace.
        private const val MAX_SOURCE_MISS_FRAGMENT_BYTES = 12L * 1024L * 1024L
        private const val MAX_SOURCE_MISS_TILES_PER_FRAGMENT = 2
        private const val MAX_DIRECT_SOURCE_MISS_FRAGMENT_BYTES = 24L * 1024L * 1024L
        private const val MAX_DIRECT_SOURCE_MISS_TILES_PER_FRAGMENT = 4
        private const val SOURCE_MISS_NEXT_WAVE_DELAY_MS = 40L
        private const val TILE_CACHE_ADMISSION_DELAY_MS = 1200L
        private const val MAX_PENDING_TILE_CACHE_WRITES = 4
        internal const val SAMPLE_SIZE_HYSTERESIS = 0.12f
        private const val ACTIVE_OFFSCREEN_TILE_CACHE_ENTRIES = 12
        private const val ACTIVE_OFFSCREEN_TILE_CACHE_MIN_BYTES = 48L * 1024L * 1024L
        private const val ACTIVE_OFFSCREEN_TILE_CACHE_MAX_BYTES = 48L * 1024L * 1024L
        private const val INACTIVE_OFFSCREEN_TILE_CACHE_ENTRIES = 12
        private const val INACTIVE_OFFSCREEN_TILE_CACHE_BYTES = 48L * 1024L * 1024L
        private val NEXT_DIAGNOSTIC_TASK_ID = AtomicLong()
        private val ROTATION_THRESHOLD = Math.toRadians(10.0)
        private val ZOOM_IN_THRESHOLD = 0.05f   // if the user zooms in a bit, do not allow rotating the image with the given gesture anymore
    }

    var maxScale = 2f
    var isOneToOneZoomEnabled = false
    var rotationEnabled = true
    var eagerLoadingEnabled = false
    var debug = false
    var onImageEventListener: OnImageEventListener? = null
    var diagnosticsListener: ((String) -> Unit)? = null
    var doubleTapZoomScale = 1f
    var cacheTaskExecutor: Executor? = null
    /**
     * A caller with a complete fit-screen preview can take rendering ownership back at fit.
     * In that mode, do not decode a lower-resolution tile wave that will immediately be hidden.
     */
    var deferTileLoadsAtOrBelowFit = false

    /**
     * Minimum zoom relative to fit-screen. The upstream value is 1f.
     * Values below 1f allow the image to remain smaller than fit-screen.
     */
    var minScaleFactor = 1f

    /**
     * When enabled, a double tap at any non-fit scale returns to fit-screen.
     */
    var doubleTapReturnsToFit = false

    /**
     * Clamp pinch/quick-scale continuously instead of allowing an elastic excursion beyond the
     * configured range. Pixel's fit-screen preview already uses strict bounds; enabling this for
     * the deferred tile layer keeps the same gesture from changing behaviour after tile handoff.
     */
    var strictScaleBounds = false
    var taskExecutor: Executor = AsyncTask.THREAD_POOL_EXECUTOR
    var bitmapDecoderFactory: DecoderFactory<out ImageDecoder> = CompatDecoderFactory(SkiaImageDecoder::class.java)
    var regionDecoderFactory: DecoderFactory<out ImageRegionDecoder> = CompatDecoderFactory(SkiaImageRegionDecoder::class.java)
    var scale = 0f
    var sWidth = 0
    var sHeight = 0
    var orientation = ORIENTATION_0

    private var bitmap: Bitmap? = null
    private var bitmapIsBorrowedPreview = false
    private var borrowedPreviewReleaseRequested = false
    private var uri: Uri? = null
    private var fullImageSampleSize = 0
    private var tileMap: MutableMap<Int, List<Tile>>? = null
    private var tileMapCapabilityRevision = Long.MIN_VALUE
    private var hostCapabilityRevision = Long.MIN_VALUE
    private var tileAccessSequence = 0L
    private var sourceMissWaveSequence = 0L
    private var lastRequiredSampleSize = 0
    private var stableTileRefreshGeneration = 0L
    private var stableTileCacheGeneration = 0L
    private var activeTileMemoryCache = true
    private var tileMemoryCacheEnabled = true
    private var minimumTileDpi = -1
    private var maxTileWidth = TILE_SIZE_AUTO
    private var maxTileHeight = TILE_SIZE_AUTO
    private var scaleStart = 0f

    private var imageRotation = 0.0
    private var cos = cos(0.0)
    private var sin = sin(0.0)

    private var vTranslate: PointF? = null
    private var vTranslateStart: PointF? = null
    private var vTranslateBefore: PointF? = null

    private var pendingScale: Float? = null
    private var sPendingCenter: PointF? = null

    private var sOrientation = 0

    private var isZooming = false
    private var isPanning = false
    private var allFingersLifted = true
    private var isQuickScaling = false
    private var maxTouchCount = 0
    private var didZoomInGesture = false
    private var ignoreTouches = false
    private var didRotateInGesture = false
    private var preventRotatingInGesture = false
    private var prevDegrees = 0

    private var detector: GestureDetector? = null
    private var singleDetector: GestureDetector? = null

    private var decoder: ImageRegionDecoder? = null
    private val decoderLock = ReentrantReadWriteLock(true)
    private var imageGeneration = 0L
    private var sCenterStart: PointF? = null
    private var vCenterStart: PointF? = null
    private var vCenterStartNow: PointF? = null
    private var vDistStart = 0f
    private var lastAngle: Double = 0.0

    private val quickScaleThreshold: Float
    private var quickScaleLastDistance = 0f
    private var quickScaleMoved = false
    private var quickScaleVLastPoint: PointF? = null
    private var quickScaleSCenter: PointF? = null
    private var quickScaleVStart: PointF? = null

    private var anim: Anim? = null
    private var isReady = false
    private var isImageLoaded = false
    private var hasDispatchedImageDrawn = false
    private var lastDiagnosticsMoveEventTime = Long.MIN_VALUE
    private var lastDiagnosticsDrawLogNanos = 0L

    private var bitmapPaint: Paint? = null
    private var debugTextPaint: Paint? = null
    private var debugLinePaint: Paint? = null

    private var satTemp: ScaleTranslateRotate? = null
    private var objectMatrix: Matrix? = null
    private val srcArray = FloatArray(8)
    private val dstArray = FloatArray(8)

    private val density = resources.displayMetrics.density

    init {
        setMinimumDpi(160)
        setDoubleTapZoomDpi(160)
        setMinimumTileDpi(320)
        setGestureDetector(context)
        quickScaleThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, context.resources.displayMetrics)
    }

    private fun getIsBaseLayerReady(): Boolean {
        if (bitmap != null) {
            return true
        }
        return getAreBaseTilesReady()
    }

    private fun getAreBaseTilesReady(): Boolean {
        val baseTiles = tileMap?.get(fullImageSampleSize) ?: return false
        return baseTiles.all { !it.loading && it.bitmap != null }
    }

    private fun clearBaseBitmap() {
        if (!bitmapIsBorrowedPreview) {
            bitmap?.recycle()
        }
        bitmap = null
        bitmapIsBorrowedPreview = false
        borrowedPreviewReleaseRequested = false
    }

    /**
     * Keep the borrowed Glide preview through the renderer handoff, then release the
     * reference as soon as the complete base tile layer can replace it without a gap.
     * The bitmap belongs to Glide and is never recycled here.
     */
    fun releaseBorrowedPreviewWhenTilesReady() {
        if (!bitmapIsBorrowedPreview) return
        borrowedPreviewReleaseRequested = true
        if (getAreBaseTilesReady()) {
            clearBaseBitmap()
        }
        invalidate()
    }

    private fun borrowedPreviewCoversCurrentScale(): Boolean {
        if (!bitmapIsBorrowedPreview) return false
        val preview = bitmap?.takeUnless { it.isRecycled } ?: return false
        val sourceWidth = sWidth().takeIf { it > 0 } ?: return false
        val sourceHeight = sHeight().takeIf { it > 0 } ?: return false
        val previewPixelsPerSourcePixel = min(
            preview.width.toFloat() / sourceWidth,
            preview.height.toFloat() / sourceHeight,
        )
        return scale <= previewPixelsPerSourcePixel * 1.001f
    }

    private fun getRequiredRotation() = if (orientation == ORIENTATION_USE_EXIF) sOrientation else orientation

    private fun getCenter(): PointF? {
        val centerX = width / 2
        val centerY = height / 2
        return viewToSourceCoord(centerX.toFloat(), centerY.toFloat())
    }

    fun setImage(
        path: String,
        borrowedPreview: Bitmap? = null,
        previewSourceWidth: Int = 0,
        previewSourceHeight: Int = 0,
    ) {
        reset(true)

        var newPath = path
        if (!newPath.contains("://")) {
            if (newPath.startsWith("/")) {
                newPath = path.substring(1)
            }
            newPath = "$FILE_SCHEME/$newPath"
        }

        if (newPath.startsWith(FILE_SCHEME)) {
            val uriFile = File(newPath.substring(FILE_SCHEME.length))
            if (!uriFile.exists()) {
                try {
                    newPath = URLDecoder.decode(newPath, "UTF-8")
                } catch (e: UnsupportedEncodingException) {
                }
            }
        }

        if (!context.packageName.startsWith("com.davemorrissey") && !context.packageName.startsWith("com.simplemobiletools")) {
            newPath = path
        }

        uri = Uri.parse(newPath)
        if (
            borrowedPreview?.isRecycled == false &&
            previewSourceWidth > 0 && previewSourceHeight > 0
        ) {
            bitmap = borrowedPreview
            bitmapIsBorrowedPreview = true
            borrowedPreviewReleaseRequested = false
            sWidth = previewSourceWidth
            sHeight = previewSourceHeight
            sOrientation = orientation
            invalidate()
            requestLayout()
        }
        val task = TilesInitTask(this, context, regionDecoderFactory, uri!!)
        execute(task)
    }

    private fun reset(newImage: Boolean) {
        if (newImage) imageGeneration++
        scale = 0f
        scaleStart = 0f
        imageRotation = 0.0
        vTranslate = null
        vTranslateStart = null
        vTranslateBefore = null
        pendingScale = null
        sPendingCenter = null
        isZooming = false
        isPanning = false
        isQuickScaling = false
        maxTouchCount = 0
        fullImageSampleSize = 0
        lastRequiredSampleSize = 0
        sourceMissWaveSequence = 0L
        stableTileRefreshGeneration += 1
        stableTileCacheGeneration += 1
        lastDiagnosticsDrawLogNanos = 0L
        sCenterStart = null
        vCenterStart = null
        vCenterStartNow = null
        vDistStart = 0f
        lastAngle = 0.0
        quickScaleLastDistance = 0f
        quickScaleMoved = false
        quickScaleSCenter = null
        quickScaleVLastPoint = null
        quickScaleVStart = null
        anim = null
        satTemp = null
        objectMatrix = null

        if (newImage) {
            uri = null
            decoderLock.writeLock().lock()
            try {
                decoder?.recycle()
                decoder = null
            } finally {
                decoderLock.writeLock().unlock()
            }

            clearBaseBitmap()

            prevDegrees = 0
            sWidth = 0
            sHeight = 0
            sOrientation = 0
            isReady = false
            isImageLoaded = false
            hasDispatchedImageDrawn = false
            cos = Math.cos(0.0)
            sin = Math.sin(0.0)
        }

        tileMap?.values?.forEach {
            for (tile in it) {
                tile.visible = false
                clearTileBitmap(tile, recycleBitmap = true)
            }
        }
        tileMap = null
        tileMapCapabilityRevision = Long.MIN_VALUE
        tileAccessSequence = 0L
        setGestureDetector(context)
    }

    private fun setGestureDetector(context: Context) {
        detector = GestureDetector(context, object : GestureDetectorListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // we have to return true here so ACTION_UP (and onFling) can be dispatched
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || e2 == null) {
                    return true
                }

                if (isReady && vTranslate != null && !isZooming && (abs(e1.x - e2.x) > 50 || abs(e1.y - e2.y) > 50) &&
                    (abs(velocityX) > 500 || abs(velocityY) > 500)
                ) {
                    val vX = (velocityX * cos - velocityY * -sin).toFloat()
                    val vY = (velocityX * -sin + velocityY * cos).toFloat()

                    val vTranslateEnd = PointF(vTranslate!!.x + vX * 0.25f, vTranslate!!.y + vY * 0.25f)
                    val sCenterXEnd = (width / 2 - vTranslateEnd.x) / scale
                    val sCenterYEnd = (height / 2 - vTranslateEnd.y) / scale
                    AnimationBuilder(PointF(sCenterXEnd, sCenterYEnd)).apply {
                        interruptible = true
                        easing = EASE_OUT_QUAD
                        duration = FLING_DURATION
                        start()
                    }
                    return true
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                performClick()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (isReady && vTranslate != null) {
                    setGestureDetector(context)
                    vCenterStart = PointF(event.x, event.y)
                    vTranslateStart = PointF(vTranslate!!.x, vTranslate!!.y)
                    scaleStart = scale
                    isQuickScaling = true
                    isZooming = true
                    quickScaleLastDistance = -1f
                    quickScaleSCenter = viewToSourceCoord(vCenterStart!!)
                    quickScaleVStart = PointF(event.x, event.y)
                    quickScaleVLastPoint = PointF(quickScaleSCenter!!.x, quickScaleSCenter!!.y)
                    quickScaleMoved = false
                    return false
                }
                return super.onDoubleTapEvent(event)
            }
        })

        singleDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                performClick()
                return true
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val sCenter = getCenter()
        if (isReady && sCenter != null) {
            anim = null
            pendingScale = scale
            sPendingCenter = sCenter
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        val resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
        val resizeHeight = heightSpecMode != MeasureSpec.EXACTLY

        var width = parentWidth
        var height = parentHeight
        if (sWidth > 0 && sHeight > 0) {
            if (resizeWidth && resizeHeight) {
                width = sWidth()
                height = sHeight()
            } else if (resizeHeight) {
                height = (sHeight().toDouble() / sWidth().toDouble() * width).toInt()
            } else if (resizeWidth) {
                width = (sWidth().toDouble() / sHeight().toDouble() * height).toInt()
            }
        }
        width = max(width, suggestedMinimumWidth)
        height = max(height, suggestedMinimumHeight)
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (anim?.interruptible == false || ignoreTouches) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                isZooming = false
            }

            ignoreTouches = true
            parent?.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                onImageEventListener?.onUpEvent()
                ignoreTouches = false
            }

            return true
        } else {
            anim = null
        }

        if (vTranslate == null) {
            singleDetector?.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                onImageEventListener?.onUpEvent()
                ignoreTouches = false
            }
            return true
        }

        detector?.onTouchEvent(event)
        if (vTranslateStart == null) {
            vTranslateStart = PointF(0f, 0f)
        }

        if (vTranslateBefore == null) {
            vTranslateBefore = PointF(0f, 0f)
        }

        if (sCenterStart == null) {
            sCenterStart = PointF(0f, 0f)
        }

        if (vCenterStart == null) {
            vCenterStart = PointF(0f, 0f)
        }

        if (vCenterStartNow == null) {
            vCenterStartNow = PointF(0f, 0f)
        }

        vTranslateBefore!!.set(vTranslate!!)
        val shouldTraceTouch = diagnosticsListener != null && (
            event.actionMasked != MotionEvent.ACTION_MOVE ||
                lastDiagnosticsMoveEventTime == Long.MIN_VALUE ||
                event.eventTime - lastDiagnosticsMoveEventTime >= 80L
            )
        if (shouldTraceTouch && event.actionMasked == MotionEvent.ACTION_MOVE) {
            lastDiagnosticsMoveEventTime = event.eventTime
        }
        val beforeScale = if (shouldTraceTouch) scale else 0f
        val beforeTranslate = if (shouldTraceTouch) PointF(vTranslate!!.x, vTranslate!!.y) else null
        val beforeCenter = if (shouldTraceTouch) getCenter() else null
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            tileMap?.values?.flatten()?.forEach { tile -> tile.sourceMissWaveId = 0L }
        }
        val handled = onTouchEventInternal(event) || super.onTouchEvent(event)
        // Commit the transform only after SSIV has processed UP/CANCEL. Dispatching this
        // callback before onTouchEventInternal() caused callers to persist the previous
        // scale, then restore that stale scale after a pager detach/re-attach.
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            onImageEventListener?.onUpEvent()
            ignoreTouches = false
            // Pager interception can deliver CANCEL instead of the UP branch handled by
            // onTouchEventInternal(). Always perform one final stable-viewport scan so
            // skipped tiles cannot remain stalled until the next nudge.
            postOnAnimation {
                if (anim == null && !isZooming && !isPanning) {
                    scheduleStableTileRefresh()
                }
            }
        }
        if (shouldTraceTouch) {
            val pointerDetail = buildString {
                for (index in 0 until event.pointerCount) {
                    if (index > 0) append(';')
                    append(event.getPointerId(index))
                    append('@')
                    append(event.getX(index))
                    append(',')
                    append(event.getY(index))
                }
            }
            diagnosticsListener?.invoke(
                "action=${MotionEvent.actionToString(event.actionMasked)} index=${event.actionIndex} " +
                    "pointers=$pointerDetail handled=$handled " +
                    "beforeScale=$beforeScale beforeTranslate=${beforeTranslate?.x},${beforeTranslate?.y} " +
                    "beforeCenter=${beforeCenter?.x},${beforeCenter?.y} " +
                    "afterScale=$scale afterTranslate=${vTranslate?.x},${vTranslate?.y} " +
                    "afterCenter=${getCenter()?.let { "${it.x},${it.y}" }} " +
                    "zooming=$isZooming panning=$isPanning quick=$isQuickScaling " +
                    "maxTouch=$maxTouchCount anim=${anim != null}",
            )
        }
        return handled
    }

    private fun onTouchEventInternal(event: MotionEvent): Boolean {
        val touchCount = event.pointerCount
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_1_DOWN, MotionEvent.ACTION_POINTER_2_DOWN -> {
                allFingersLifted = true
                anim = null
                parent?.requestDisallowInterceptTouchEvent(true)
                maxTouchCount = max(maxTouchCount, touchCount)
                if (touchCount >= 2) {
                    // A second pointer definitively turns the stroke into a pinch. The
                    // gesture detector may already have marked the first DOWN as the start
                    // of double-tap quick-scale; leaving that flag set makes POINTER_UP run
                    // doubleTapZoom() and unexpectedly snap the image back to fit-screen.
                    isQuickScaling = false
                    quickScaleMoved = false
                    scaleStart = scale
                    vDistStart = distance(event.getX(0), event.getX(1), event.getY(0), event.getY(1))
                    vTranslateStart!!.set(vTranslate!!.x, vTranslate!!.y)
                    vCenterStart!!.set((event.getX(0) + event.getX(1)) / 2, (event.getY(0) + event.getY(1)) / 2)
                    viewToSourceCoord(vCenterStart!!, sCenterStart!!)

                    if (rotationEnabled) {
                        lastAngle = atan2((event.getY(0) - event.getY(1)).toDouble(), (event.getX(0) - event.getX(1)).toDouble())
                    }
                } else if (!isQuickScaling) {
                    vTranslateStart!!.set(vTranslate!!.x, vTranslate!!.y)
                    vCenterStart!!.set(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var consumed = false
                if (maxTouchCount > 0) {
                    if (touchCount >= 2 && allFingersLifted) {
                        if (rotationEnabled && !preventRotatingInGesture || didRotateInGesture) {
                            val angle = atan2((event.getY(0) - event.getY(1)).toDouble(), (event.getX(0) - event.getX(1)).toDouble())
                            val changeInAngle = abs(lastAngle - angle)
                            if (changeInAngle > ROTATION_THRESHOLD) {
                                lastAngle = angle
                                didRotateInGesture = true
                            } else if (didRotateInGesture) {
                                setRotationInternal(imageRotation + angle - lastAngle)
                                lastAngle = angle
                                consumed = true
                            }

                            if (ZOOM_IN_THRESHOLD < abs(scale - scaleStart)) {
                                preventRotatingInGesture = true
                            }
                        }

                        val vDistEnd = distance(event.getX(0), event.getX(1), event.getY(0), event.getY(1))
                        val vCenterEndX = (event.getX(0) + event.getX(1)) / 2
                        val vCenterEndY = (event.getY(0) + event.getY(1)) / 2
                        if (distance(vCenterStart!!.x, vCenterEndX, vCenterStart!!.y, vCenterEndY) > 5 || abs(vDistEnd - vDistStart) > 5 || isPanning) {
                            didZoomInGesture = true
                            isZooming = true
                            isPanning = true
                            consumed = true

                            val previousScale = scale.toDouble()
                            scale = if (strictScaleBounds) {
                                limitedScale(vDistEnd / vDistStart * scaleStart)
                            } else {
                                min(maxScale, vDistEnd / vDistStart * scaleStart)
                            }

                            sourceToViewCoord(sCenterStart!!, vCenterStartNow!!)

                            val dx = vCenterEndX - vCenterStartNow!!.x
                            val dy = vCenterEndY - vCenterStartNow!!.y

                            val dxR = (dx * cos - dy * -sin).toFloat()
                            val dyR = (dx * -sin + dy * cos).toFloat()

                            vTranslate!!.x += dxR
                            vTranslate!!.y += dyR

                            if (strictScaleBounds) {
                                // The preview path is centered and bounded on every gesture
                                // sample. Apply the same rule here so shrinking through fit-screen
                                // cannot leave a transient offset that later animates back.
                                fitToBounds()
                            }

                            if (previousScale * sHeight() < height && scale * sHeight() >= height || previousScale * sWidth() < width && scale * sWidth() >= width) {
                                vCenterStart!!.set(vCenterEndX, vCenterEndY)
                                vTranslateStart!!.set(vTranslate!!)
                                scaleStart = scale
                                vDistStart = vDistEnd
                            }

                            refreshRequiredTiles(eagerLoadingEnabled)
                        }
                    } else if (isQuickScaling) {
                        var dist = abs(quickScaleVStart!!.y - event.y) * 2 + quickScaleThreshold

                        if (quickScaleLastDistance == -1f) {
                            quickScaleLastDistance = dist
                        }

                        val isUpwards = event.y > quickScaleVLastPoint!!.y
                        quickScaleVLastPoint!!.set(0f, event.y)

                        val spanDiff = abs(1 - dist / quickScaleLastDistance) * 0.5f
                        if (spanDiff > 0.03f || quickScaleMoved) {
                            quickScaleMoved = true

                            var multiplier = 1f
                            if (quickScaleLastDistance > 0) {
                                multiplier = if (isUpwards) 1 + spanDiff else 1 - spanDiff
                            }

                            val previousScale = scale.toDouble()
                            scale = if (strictScaleBounds) {
                                limitedScale(scale * multiplier)
                            } else {
                                min(maxScale, scale * multiplier)
                            }

                            val vLeftStart = vCenterStart!!.x - vTranslateStart!!.x
                            val vTopStart = vCenterStart!!.y - vTranslateStart!!.y
                            val vLeftNow = vLeftStart * (scale / scaleStart)
                            val vTopNow = vTopStart * (scale / scaleStart)
                            vTranslate!!.x = vCenterStart!!.x - vLeftNow
                            vTranslate!!.y = vCenterStart!!.y - vTopNow
                            if (strictScaleBounds) {
                                fitToBounds()
                            }
                            if (previousScale * sHeight() < height && scale * sHeight() >= height || previousScale * sWidth() < width && scale * sWidth() >= width) {
                                vCenterStart!!.set(sourceToViewCoord(quickScaleSCenter!!)!!)
                                vTranslateStart!!.set(vTranslate!!)
                                scaleStart = scale
                                dist = 0f
                            }
                        }

                        quickScaleLastDistance = dist

                        refreshRequiredTiles(eagerLoadingEnabled)
                        consumed = true
                    } else if (!isZooming) {
                        val dx = event.x - vCenterStart!!.x
                        val dy = event.y - vCenterStart!!.y
                        val dxA = abs(dx)
                        val dyA = abs(dy)

                        val offset = density * 5
                        if (dxA > offset || dyA > offset || isPanning) {
                            consumed = true
                            val dxR = (dx * cos - dy * -sin).toFloat()
                            val dyR = (dx * -sin + dy * cos).toFloat()

                            vTranslate!!.x = vTranslateStart!!.x + dxR
                            vTranslate!!.y = vTranslateStart!!.y + dyR

                            val lastX = vTranslate!!.x
                            val lastY = vTranslate!!.y
                            if (!didZoomInGesture) {
                                // A single-finger stroke must never move an image that already
                                // fits completely inside the viewport. The old scale guard left
                                // the below-fit translation mutated just before the parent Pager
                                // cancelled the event, so a centred image was saved off-centre.
                                fitToBounds()
                            }

                            val degrees = Math.toDegrees(imageRotation)
                            val rightAngle = getClosestRightAngle(degrees)
                            val atXEdge = if (rightAngle == 90.0 || rightAngle == 270.0) lastY != vTranslate!!.y else lastX != vTranslate!!.x
                            val edgeXSwipe = atXEdge && dxA > dyA && !isPanning
                            // disable panning and allow swiping when the image is too small to fit the view bounds
                            val lowRes = height > sHeight * scale && width > sWidth * scale
                            // The only parent gesture is a HorizontalPager. Reaching the image's
                            // top or bottom must therefore keep the whole stroke inside SSIV;
                            // otherwise the pager can steal the horizontal component of a
                            // diagonal vertical pan and switch pages before the image reaches a
                            // left/right edge.
                            if (!edgeXSwipe && !lowRes) {
                                isPanning = true
                            } else if (lowRes || (dxA > offset && atXEdge && dxA > dyA)) {
                                maxTouchCount = 0
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }

                            refreshRequiredTiles(eagerLoadingEnabled)
                        }
                    }
                }

                if (consumed) {
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_2_UP -> {
                if (isQuickScaling) {
                    isQuickScaling = false
                    if (quickScaleMoved) {
                        animateToBounds()
                    } else {
                        doubleTapZoom(quickScaleSCenter, quickScaleVStart)
                    }
                }

                if (touchCount == 1) {
                    if (didZoomInGesture) {
                        animateToBounds()
                    }
                }

                didZoomInGesture = false
                didRotateInGesture = false
                preventRotatingInGesture = false

                if (touchCount > 2) {
                    allFingersLifted = false
                }

                if (maxTouchCount > 0 && (isZooming || isPanning)) {
                    if (touchCount == 2) {
                        animateToBounds()
                    }

                    if (touchCount == 1) {
                        isZooming = false
                    }

                    if (touchCount < 2) {
                        maxTouchCount = 0
                    }

                    isPanning = false
                    scheduleStableTileRefresh()
                    return true
                }

                if (touchCount == 1) {
                    isZooming = false
                    isPanning = false
                    maxTouchCount = 0
                }

                if (maxTouchCount > touchCount) {
                    allFingersLifted = true
                }
                return true
            }
        }
        return false
    }

    private fun getClosestRightAngle(degrees: Double) = Math.round(degrees / 90f) * 90.0

    private fun doubleTapZoom(sCenter: PointF?, vFocus: PointF?) {
        if (sCenter == null) return
        val doubleTapZoomScale = min(maxScale, doubleTapZoomScale)
        if (doubleTapReturnsToFit) {
            val fullScale = getFullScale()
            val isAtFitScale = abs(scale / fullScale - 1f) < 0.05f
            val targetCenter = if (isAtFitScale) sCenter else PointF(sWidth / 2f, sHeight / 2f)
            val targetScale = if (isAtFitScale) doubleTapZoomScale else fullScale
            diagnosticsListener?.invoke(
                "gesture=DOUBLE_TAP source=${sCenter.x},${sCenter.y} " +
                    "view=${vFocus?.x},${vFocus?.y} fromScale=$scale targetScale=$targetScale " +
                    "fitScale=$fullScale fixedFocus=$isAtFitScale",
            )
            if (isAtFitScale && vFocus != null) {
                AnimationBuilder(targetCenter, targetScale, vFocus).start()
            } else {
                AnimationBuilder(targetCenter, targetScale).start()
            }
            invalidate()
            return
        }

        val zoomIn = scale <= doubleTapZoomScale * 0.9 || isZoomedOut()
        if (sWidth == sHeight || !isOneToOneZoomEnabled) {
            val targetScale = if (zoomIn) doubleTapZoomScale else getFullScale()
            if (zoomIn && vFocus != null) {
                AnimationBuilder(sCenter, targetScale, vFocus).start()
            } else {
                AnimationBuilder(sCenter, targetScale).start()
            }
        } else {
            val targetScale = if (zoomIn && scale != 1f) doubleTapZoomScale else getFullScale()
            if (scale != 1f) {
                if (zoomIn) {
                    if (vFocus != null) {
                        AnimationBuilder(sCenter, targetScale, vFocus).start()
                    } else {
                        AnimationBuilder(sCenter, targetScale).start()
                    }
                } else {
                    AnimationBuilder(sCenter, 1f).start()
                }
            } else {
                if (zoomIn && vFocus != null) {
                    AnimationBuilder(sCenter, targetScale, vFocus).start()
                } else {
                    AnimationBuilder(sCenter, targetScale).start()
                }
            }
        }
        invalidate()
    }

    private fun drawBaseBitmapLayer(canvas: Canvas): Boolean {
        val baseBitmap = bitmap?.takeUnless { it.isRecycled } ?: return false
        // A borrowed preview can be smaller than the source image. SSIV's scale is
        // expressed in source pixels, so compensate for the preview's downsampling.
        val xScale = scale * sWidth().toFloat() / baseBitmap.width.coerceAtLeast(1)
        val yScale = scale * sHeight().toFloat() / baseBitmap.height.coerceAtLeast(1)

        if (objectMatrix == null) objectMatrix = Matrix()
        objectMatrix!!.apply {
            reset()
            postScale(xScale, yScale)
            postRotate(getRequiredRotation().toFloat())
            vTranslate?.let { postTranslate(it.x, it.y) }
            when (getRequiredRotation()) {
                ORIENTATION_90 -> postTranslate(scale * sHeight, 0f)
                ORIENTATION_180 -> postTranslate(scale * sWidth, scale * sHeight)
                ORIENTATION_270 -> postTranslate(0f, scale * sWidth)
            }
            postRotate(Math.toDegrees(imageRotation).toFloat(), width / 2f, height / 2f)
        }
        canvas.drawBitmap(baseBitmap, objectMatrix!!, bitmapPaint)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        createPaints()

        if (sWidth == 0 || sHeight == 0 || width == 0 || height == 0) {
            return
        }

        if (tileMap == null && decoder != null) {
            initialiseBaseLayer(getMaxBitmapDimensions(canvas))
        }
        refreshTileMapForDecoderCapabilities(getMaxBitmapDimensions(canvas))

        if (!checkReady()) {
            return
        }
        val diagnosticsDrawStartedAt = if (diagnosticsListener != null) {
            SystemClock.elapsedRealtimeNanos()
        } else {
            0L
        }

        if (anim != null && anim!!.vFocusStart != null) {
            if (vTranslateBefore == null) {
                vTranslateBefore = PointF(0f, 0f)
            }
            vTranslateBefore!!.set(vTranslate!!)

            var scaleElapsed = System.currentTimeMillis() - anim!!.time
            val finished = scaleElapsed > anim!!.duration
            scaleElapsed = min(scaleElapsed, anim!!.duration)
            scale = ease(anim!!.easing, scaleElapsed, anim!!.scaleStart, anim!!.scaleEnd - anim!!.scaleStart, anim!!.duration, anim!!.scaleEnd)

            val focusStart = anim!!.vFocusStart!!
            val focusEnd = anim!!.vFocusEnd!!
            val vFocusNowX = ease(anim!!.easing, scaleElapsed, focusStart.x, focusEnd.x - focusStart.x, anim!!.duration, focusEnd.x)
            val vFocusNowY = ease(anim!!.easing, scaleElapsed, focusStart.y, focusEnd.y - focusStart.y, anim!!.duration, focusEnd.y)

            val rotationStart = anim!!.rotationStart
            val rotationEnd = anim!!.rotationEnd
            val easeValue = ease(anim!!.easing, scaleElapsed, rotationStart, rotationEnd - rotationStart, anim!!.duration, rotationEnd)
            setRotationInternal(easeValue.toDouble())

            val animVCenterEnd = sourceToViewCoord(anim!!.sCenterEnd!!)
            val dX = animVCenterEnd!!.x - vFocusNowX
            val dY = animVCenterEnd.y - vFocusNowY
            vTranslate!!.x -= (dX * cos + dY * sin).toFloat()
            vTranslate!!.y -= (-dX * sin + dY * cos).toFloat()

            refreshRequiredTiles(false)
            if (finished) {
                anim = null
                fitToBounds()
                scheduleStableTileRefresh()
                val degrees = Math.round(Math.toDegrees(imageRotation)).toInt()
                if (degrees != prevDegrees) {
                    var diff = degrees - prevDegrees
                    if (diff == 270) {
                        diff = -90
                    } else if (diff == -270) {
                        diff = 90
                    }
                    onImageEventListener?.onImageRotation(diff)
                    prevDegrees = degrees
                }
            }
            invalidate()
        }

        // The borrowed fit preview is only the bottom-most fallback. Draw every ready
        // tile over it immediately; waiting for the entire base grid causes an avoidable
        // full-screen blur even when sharper regions are already available.
        var imageDrawnThisFrame = bitmapIsBorrowedPreview && drawBaseBitmapLayer(canvas)
        var drawnTileCount = 0
        var drawnTileBytes = 0L
        var drawnFallbackSampleSize: Int? = null
        val hasReadyTile = tileMap?.values?.any { grid ->
            grid.any { it.bitmap?.isRecycled == false }
        } == true
        if (tileMap != null && hasReadyTile) {
            val sampleSize = calculateRequiredTileSampleSize()
            var hasMissingTiles = false
            for ((key, value) in tileMap!!) {
                if (key == sampleSize) {
                    for (tile in value) {
                        if (tile.visible && (tile.loading || tile.bitmap == null)) {
                            hasMissingTiles = true
                        }
                    }
                }
            }

            // While a zoom crosses a sample boundary, the target grid is incomplete for a
            // short time. Drawing every retained grid as a fallback stacks several complete
            // layers of large bitmaps and makes zooming much more expensive than panning.
            // Keep exactly one closest ready fallback. Prefer the coarser grid on an equal
            // distance because it covers the viewport with fewer textures; the borrowed
            // preview underneath still fills any uncovered area.
            drawnFallbackSampleSize = if (hasMissingTiles) {
                tileMap!!.keys
                    .asSequence()
                    .filter { it != sampleSize }
                    .filter { candidate ->
                        tileMap!![candidate].orEmpty().any { tile ->
                            tile.bitmap?.isRecycled == false && tileVisible(tile)
                        }
                    }
                    .minWithOrNull(
                        compareBy<Int> {
                            kotlin.math.abs(kotlin.math.ln(it.toDouble() / sampleSize.toDouble()))
                        }.thenBy { candidate ->
                            if (candidate > sampleSize) 0 else 1
                        },
                    )
            } else {
                null
            }

            for ((key, value) in tileMap!!) {
                if (key == sampleSize || key == drawnFallbackSampleSize) {
                    for (tile in value) {
                        // A decoded off-screen tile may remain in the CPU-side LRU, but it
                        // must not enter this frame's display list. Telephoto/0713 keeps its
                        // off-screen painters out of viewportImageTiles for the same reason.
                        // Drawing every retained 20MB bitmap here made RenderThread upload
                        // and evict invisible textures on every pan.
                        if (!tileVisible(tile)) {
                            // Keep the decoded bitmap in the bounded CPU cache, but stop
                            // pinning an invisible GPU display list immediately.
                            discardTileRenderNode(tile)
                            continue
                        }
                        sourceToViewRect(tile.sRect!!, tile.vRect!!)
                        if (!tile.loading && tile.bitmap != null) {
                            if (objectMatrix == null) {
                                objectMatrix = Matrix()
                            }

                            objectMatrix!!.reset()
                            val bitmap = tile.bitmap!!
                            val bitmapWidth = bitmap.width.toFloat()
                            val bitmapHeight = bitmap.height.toFloat()
                            setMatrixArray(srcArray, 0f, 0f, bitmapWidth, 0f, bitmapWidth, bitmapHeight, 0f, bitmapHeight)

                            val rect = tile.vRect!!
                            val left = rect.left.toFloat()
                            val right = rect.right.toFloat()
                            val bottom = rect.bottom.toFloat()
                            val top = rect.top.toFloat()
                            when (getRequiredRotation()) {
                                ORIENTATION_0 -> setMatrixArray(dstArray, left, top, right, top, right, bottom, left, bottom)
                                ORIENTATION_90 -> setMatrixArray(dstArray, right, top, right, bottom, left, bottom, left, top)
                                ORIENTATION_180 -> setMatrixArray(dstArray, right, bottom, left, bottom, left, top, right, top)
                                ORIENTATION_270 -> setMatrixArray(dstArray, left, bottom, left, top, right, top, right, bottom)
                            }
                            objectMatrix!!.setPolyToPoly(srcArray, 0, dstArray, 0, 4)
                            objectMatrix!!.postRotate(Math.toDegrees(imageRotation).toFloat(), width / 2f, height / 2f)
                            drawTileBitmap(canvas, tile, objectMatrix!!)
                            drawnTileCount += 1
                            drawnTileBytes += tileBytes(tile)
                            imageDrawnThisFrame = true
                            if (debug) {
                                canvas.drawRect(tile.vRect!!, debugLinePaint!!)
                            }
                        } else if (tile.loading && debug) {
                            canvas.drawText("LOADING", (tile.vRect!!.left + px(5)).toFloat(), (tile.vRect!!.top + px(35)).toFloat(), debugTextPaint!!)
                        }
                        if (tile.visible && debug) {
                            canvas.drawText(
                                "ISS ${tile.sampleSize} RECT ${tile.sRect!!.top}, ${tile.sRect!!.left}, ${tile.sRect!!.bottom}, ${tile.sRect!!.right}",
                                (tile.vRect!!.left + px(5)).toFloat(),
                                (tile.vRect!!.top + px(15)).toFloat(),
                                debugTextPaint!!
                            )
                        }
                    }
                }
            }
        } else if (!bitmapIsBorrowedPreview) {
            imageDrawnThisFrame = drawBaseBitmapLayer(canvas)
        }

        if (imageDrawnThisFrame && !hasDispatchedImageDrawn) {
            hasDispatchedImageDrawn = true
            val drawnGeneration = imageGeneration
            post {
                if (drawnGeneration == imageGeneration && hasDispatchedImageDrawn) {
                    onImageEventListener?.onImageDrawn()
                }
            }
        }

        if (debug) {
            canvas.drawText(
                "Scale: ${String.format(Locale.ENGLISH, "%.2f", scale)} (${String.format(Locale.ENGLISH, "%.2f", getFullScale())} - ${
                    String.format(
                        Locale.ENGLISH,
                        "%.2f",
                        maxScale
                    )
                })", px(5).toFloat(), px(15).toFloat(), debugTextPaint!!
            )
            canvas.drawText(
                "Translate: ${String.format(Locale.ENGLISH, "%.2f", vTranslate!!.x)}:${String.format(Locale.ENGLISH, "%.2f", vTranslate!!.y)}",
                px(5).toFloat(),
                px(30).toFloat(),
                debugTextPaint!!
            )
            val center = getCenter()

            canvas.drawText(
                "Source center: ${String.format(Locale.ENGLISH, "%.2f", center!!.x)}:${String.format(Locale.ENGLISH, "%.2f", center.y)}",
                px(5).toFloat(),
                px(45).toFloat(),
                debugTextPaint!!
            )
            canvas.drawText(
                "Rotation: ${String.format(Locale.ENGLISH, "%.2f", Math.toDegrees(imageRotation))}",
                px(5).toFloat(),
                px(60).toFloat(),
                debugTextPaint!!
            )
            if (anim != null) {
                val vCenterStart = sourceToViewCoord(anim!!.sCenterStart!!)
                val vCenterEndRequested = sourceToViewCoord(anim!!.sCenterEndRequested!!)
                val vCenterEnd = sourceToViewCoord(anim!!.sCenterEnd!!)

                canvas.drawCircle(vCenterStart!!.x, vCenterStart.y, px(10).toFloat(), debugLinePaint!!)
                debugLinePaint!!.color = Color.RED

                canvas.drawCircle(vCenterEndRequested!!.x, vCenterEndRequested.y, px(20).toFloat(), debugLinePaint!!)
                debugLinePaint!!.color = Color.BLUE

                canvas.drawCircle(vCenterEnd!!.x, vCenterEnd.y, px(25).toFloat(), debugLinePaint!!)
                debugLinePaint!!.color = Color.CYAN
                canvas.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), px(30).toFloat(), debugLinePaint!!)
            }

            if (vCenterStart != null) {
                debugLinePaint!!.color = Color.RED
                canvas.drawCircle(vCenterStart!!.x, vCenterStart!!.y, px(20).toFloat(), debugLinePaint!!)
            }

            if (quickScaleSCenter != null) {
                debugLinePaint!!.color = Color.BLUE
                canvas.drawCircle(sourceToViewX(quickScaleSCenter!!.x), sourceToViewY(quickScaleSCenter!!.y), px(35).toFloat(), debugLinePaint!!)
            }

            if (quickScaleVStart != null && isQuickScaling) {
                debugLinePaint!!.color = Color.CYAN
                canvas.drawCircle(quickScaleVStart!!.x, quickScaleVStart!!.y, px(30).toFloat(), debugLinePaint!!)
            }

            debugLinePaint!!.color = Color.GREEN
        }

        if (diagnosticsDrawStartedAt != 0L) {
            val now = SystemClock.elapsedRealtimeNanos()
            val drawMs = (now - diagnosticsDrawStartedAt) / 1_000_000L
            if (drawMs >= 8L || now - lastDiagnosticsDrawLogNanos >= 80_000_000L) {
                lastDiagnosticsDrawLogNanos = now
                val sampleSize = calculateRequiredTileSampleSize()
                val visibleTiles = tileMap?.get(sampleSize).orEmpty().filter(::tileVisible)
                val loaded = visibleTiles.count { it.bitmap?.isRecycled == false }
                val loading = visibleTiles.count { it.loading }
                val retained = tileMap?.values.orEmpty().flatten()
                    .count { it.bitmap?.isRecycled == false }
                diagnosticsListener?.invoke(
                    "tile=DRAW durationMs=$drawMs sample=$sampleSize visible=${visibleTiles.size} " +
                        "loaded=$loaded loading=$loading missing=${visibleTiles.size - loaded} " +
                        "retained=$retained drawn=$drawnTileCount drawnBytes=$drawnTileBytes " +
                        "fallbackSample=${drawnFallbackSampleSize ?: "none"} " +
                        "preview=$bitmapIsBorrowedPreview scale=$scale " +
                        "translate=${vTranslate?.x},${vTranslate?.y} center=${getCenter()} " +
                        "gesture=${isZooming || isPanning} anim=${anim != null}",
                )
            }
        }
    }

    private fun setMatrixArray(array: FloatArray, f0: Float, f1: Float, f2: Float, f3: Float, f4: Float, f5: Float, f6: Float, f7: Float) {
        array[0] = f0
        array[1] = f1
        array[2] = f2
        array[3] = f3
        array[4] = f4
        array[5] = f5
        array[6] = f6
        array[7] = f7
    }

    private fun checkReady(): Boolean {
        val ready = width > 0 && height > 0 && sWidth > 0 && sHeight > 0 && (bitmap != null || getIsBaseLayerReady())
        if (!isReady && ready) {
            preDraw()
            isReady = true
            onReady()
            onImageEventListener?.onReady()
        }
        return ready
    }

    private fun setRotationInternal(rot: Double) {
        imageRotation = rot % (Math.PI * 2)
        if (imageRotation < 0) {
            imageRotation += (Math.PI * 2)
        }

        cos = cos(rot)
        sin = sin(rot)
    }

    private fun checkImageLoaded(): Boolean {
        val imageLoaded = getIsBaseLayerReady()
        if (!isImageLoaded && imageLoaded) {
            preDraw()
            isImageLoaded = true
        }
        return imageLoaded
    }

    private fun createPaints() {
        if (bitmapPaint == null) {
            bitmapPaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
        }

        if ((debugTextPaint == null || debugLinePaint == null) && debug) {
            debugTextPaint = Paint().apply {
                textSize = px(12).toFloat()
                color = Color.GREEN
                style = Style.FILL
            }

            debugLinePaint = Paint().apply {
                color = Color.GREEN
                style = Style.STROKE
                strokeWidth = px(1).toFloat()
            }
        }
    }

    @Synchronized
    private fun initialiseBaseLayer(maxTileDimensions: Point) {
        debug("initialiseBaseLayer maxTileDimensions=${maxTileDimensions.x}x${maxTileDimensions.y}")

        satTemp = ScaleTranslateRotate(0f, PointF(0f, 0f), 0f)
        fitToBounds(satTemp!!)

        // Telephoto/0713 derives its base sample directly from the fit-screen zoom.
        // Using SSIV's temporary bounded scale here can select a blurrier base level,
        // which then shrinks every foreground grid and multiplies the tile count.
        fullImageSampleSize = calculateInSampleSize(getFullScale())
        lastRequiredSampleSize = fullImageSampleSize

        if (uri == null) {
            return
        }

        if (
            fullImageSampleSize == 1 &&
            sWidth() < maxTileDimensions.x && sHeight() < maxTileDimensions.y &&
            !bitmapIsBorrowedPreview
        ) {
            decoder!!.recycle()
            decoder = null
            val task = BitmapLoadTask(this, context, bitmapDecoderFactory, uri!!)
            execute(task)
        } else {
            initialiseTileMap(maxTileDimensions)

            val baseGrid = tileMap!![fullImageSampleSize]
            if (!bitmapIsBorrowedPreview) {
                for (baseTile in baseGrid!!) {
                    val task = TileLoadTask(this, decoder!!, baseTile)
                    execute(task)
                }
            }
            refreshRequiredTiles(true)
        }
    }

    fun resetView() {
        scale = getFullScale()
        animateToBounds(true)
    }

    private fun refreshRequiredTiles(load: Boolean) {
        if (decoder == null || tileMap == null) {
            return
        }
        if (!load) {
            stableTileRefreshGeneration += 1
            stableTileCacheGeneration += 1
        }

        val sampleSize = calculateRequiredTileSampleSize()
        var scheduledCount = 0

        tileMap!!.values.forEach {
            for (tile in it) {
                if (tile.sampleSize != sampleSize) tile.sourceMissWaveId = 0L
                if (tile.sampleSize == sampleSize) {
                    if (tileVisible(tile)) {
                        tile.visible = true
                        markTileAccess(tile)
                    } else {
                        tile.visible = false
                        tile.sourceMissWaveId = 0L
                    }
                } else if (tile.sampleSize == fullImageSampleSize) {
                    tile.visible = true
                    if (tile.bitmap != null) markTileAccess(tile)
                } else {
                    // Keep already decoded tiles as a bounded LRU. A previous sampling
                    // level is the best available fallback while the new level loads,
                    // and an off-screen tile may be needed again on the next pan.
                    tile.visible = false
                    if (tile.bitmap != null && tileVisible(tile)) markTileAccess(tile)
                }
            }
        }
        if (load) {
            val sourceFocus = getCenter() ?: PointF(sWidth() / 2f, sHeight() / 2f)
            val prioritizedTiles = tileMap!![sampleSize]
                .orEmpty()
                .asSequence()
                .filter {
                    it.visible && !it.loading && it.bitmap == null && it.failedAttempts < 2 &&
                        // At fit-screen the borrowed preview already contains every displayed
                        // pixel, so decoding the same base layer is wasted entry-time work.
                        // Once the user zooms beyond the preview's real pixel density, however,
                        // the source tile must load even when sampleSize equals the base level.
                        !(sampleSize == fullImageSampleSize && borrowedPreviewCoversCurrentScale())
                }
                .sortedWith(
                    compareByDescending<Tile> { tile ->
                        tile.sRect?.contains(sourceFocus.x.toInt(), sourceFocus.y.toInt()) == true
                    }.thenBy { tile ->
                        val rect = tile.sRect ?: return@thenBy Float.MAX_VALUE
                        val dx = rect.exactCenterX() - sourceFocus.x
                        val dy = rect.exactCenterY() - sourceFocus.y
                        dx * dx + dy * dy
                    },
                )
                .toList()
            prioritizedTiles.forEach { tile ->
                fileSRect(tile.sRect, tile.fileSRect)
            }
            val batchDecoder = decoder as? BatchedImageRegionDecoder
            if (batchDecoder == null) {
                for (tile in prioritizedTiles) {
                    execute(TileLoadTask(this, decoder!!, tile))
                    scheduledCount += 1
                }
            } else {
                val decoderCapabilities = batchDecoder.capabilities(sampleSize)
                val cacheProbeDetails = if (diagnosticsListener != null) {
                    ArrayList<String>(prioritizedTiles.size)
                } else {
                    null
                }
                val (cachedTiles, sourceMissTiles) = prioritizedTiles.partition { tile ->
                    val probeStartedAt = if (cacheProbeDetails != null) {
                        SystemClock.elapsedRealtimeNanos()
                    } else {
                        0L
                    }
                    batchDecoder.isRegionCached(tile.fileSRect!!, tile.sampleSize).also { cached ->
                        tile.diskCacheReady = cached
                        cacheProbeDetails?.add(
                            "${tile.fileSRect}@${tile.sampleSize}:${if (cached) "H" else "M"}:" +
                                "${(SystemClock.elapsedRealtimeNanos() - probeStartedAt) / 1_000_000L}ms",
                        )
                    }
                }
                val sourceMissWaveId = if (
                    decoderCapabilities.coordinateSourceMissWave && sourceMissTiles.isNotEmpty()
                ) {
                    sourceMissTiles.asSequence()
                        .map { it.sourceMissWaveId }
                        .firstOrNull { it > 0L }
                        ?: (++sourceMissWaveSequence)
                } else {
                    0L
                }
                if (sourceMissWaveId > 0L) {
                    sourceMissTiles.forEach { tile -> tile.sourceMissWaveId = sourceMissWaveId }
                }
                if (cacheProbeDetails?.isNotEmpty() == true) {
                    diagnosticsListener?.invoke(
                        "tile=CACHE_PROBE count=${cacheProbeDetails.size} " +
                            "hits=${cachedTiles.size} misses=${sourceMissTiles.size} " +
                            "items=${cacheProbeDetails.joinToString(";")}",
                    )
                }
                // A disk hit stays on the original single-tile path and never queues
                // behind an expensive source batch.
                for (tile in cachedTiles) {
                    execute(TileLoadTask(this, decoder!!, tile))
                    scheduledCount += 1
                }

                // Submit one bounded source fragment per wave. BitmapRegionDecoder has to
                // advance through a JPEG stream to reach a vertical region, so decoding
                // every visible tile independently repeats much of that work. A dense 2D
                // fragment amortizes the scan across up to four tiles. The next wave starts
                // only after this one finishes, avoiding a long queue of obsolete misses.
                val fragment = buildSourceMissFragment(
                    candidates = sourceMissTiles,
                    directOutputs = decoderCapabilities.directSourceMissOutputs,
                )
                if (fragment.isNotEmpty()) {
                    diagnosticsListener?.invoke(
                        "tile=SOURCE_FRAGMENT sample=$sampleSize count=${fragment.size} " +
                            "bytes=${sourceMissFragmentBytes(fragment)} " +
                            "deferred=${sourceMissTiles.size - fragment.size} " +
                            "wave=${sourceMissWaveId.takeIf { it > 0L } ?: "none"}",
                    )
                }
                if (
                    (fragment.size > 1 && decoderCapabilities.batchSourceMisses) ||
                    (fragment.isNotEmpty() && decoderCapabilities.coordinateSourceMissWave)
                ) {
                    execute(
                        TileBatchLoadTask(
                            this,
                            batchDecoder,
                            fragment,
                            sourceMissWaveId.takeIf { it > 0L },
                        ),
                    )
                    scheduledCount += fragment.size
                } else {
                    for (tile in fragment) {
                        execute(TileLoadTask(this, decoder!!, tile))
                        scheduledCount += 1
                    }
                }
            }
        }
        // A gesture only changes which already-loaded tiles are visible; it does not load
        // new ones. Trimming here used to recycle tens of megabytes on the UI thread in
        // ACTION_MOVE, forcing RenderThread fence waits and producing low-power jank.
        val cacheStats = if (load && !isZooming && !isPanning && anim == null) {
            trimTileMemoryCache(sampleSize)
        } else {
            val budget = tileMemoryCacheBudget(sampleSize)
            TileMemoryCacheStats(
                entries = 0,
                maxEntries = budget.entries,
                maxBytes = budget.bytes,
                profile = budget.profile,
            )
        }
        if (load && diagnosticsListener != null) {
            val currentTiles = tileMap!![sampleSize].orEmpty().filter(::tileVisible)
            val memoryHits = currentTiles.count { it.bitmap?.isRecycled == false }
            val loading = currentTiles.count { it.loading }
            val retainedTiles = tileMap!!.values.flatten().count { it.bitmap?.isRecycled == false }
            val retainedBytes = tileMap!!.values.flatten().sumOf(::tileBytes)
            diagnosticsListener?.invoke(
                "tile=REFRESH sample=$sampleSize visible=${currentTiles.size} " +
                    "memoryHits=$memoryHits loading=$loading scheduled=$scheduledCount " +
                    "retained=$retainedTiles bytes=$retainedBytes " +
                    "offscreen=${cacheStats.entries}/${cacheStats.maxEntries} " +
                    "offscreenBytes=${cacheStats.bytes}/${cacheStats.maxBytes} " +
                    "cacheProfile=${cacheStats.profile}",
            )
        }
        // Do not enqueue a delayed persistence callback when this decoder has already
        // declared that decoded tiles must not be persisted. Cold benchmarks used to
        // schedule one callback per refresh only for every callback to wake up and skip.
        if (
            load &&
            (decoder as? BatchedImageRegionDecoder)
                ?.capabilities(sampleSize)
                ?.persistDecodedTiles == true
        ) {
            scheduleStableTileCachePersistence()
        }
    }

    /**
     * Record a visible software bitmap once, then let RenderThread reuse that child
     * display list while only the parent matrix changes during pan/zoom. The node is
     * discarded as soon as the tile leaves the viewport; the independent CPU bitmap
     * cache remains bounded by [trimTileMemoryCache].
     */
    private fun drawTileBitmap(canvas: Canvas, tile: Tile, matrix: Matrix) {
        val bitmap = tile.bitmap ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
            drawTileRenderNode(canvas, tile, bitmap, matrix)
        } else {
            canvas.drawBitmap(bitmap, matrix, bitmapPaint)
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun drawTileRenderNode(canvas: Canvas, tile: Tile, bitmap: Bitmap, matrix: Matrix) {
        var node = tile.renderNode as? RenderNode
        if (node == null || tile.renderNodeBitmap !== bitmap || !node.hasDisplayList()) {
            node?.discardDisplayList()
            node = RenderNode("ssiv-tile-${tile.sampleSize}").apply {
                setPosition(0, 0, bitmap.width, bitmap.height)
                val recordingCanvas = beginRecording(bitmap.width, bitmap.height)
                recordingCanvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                endRecording()
            }
            tile.renderNode = node
            tile.renderNodeBitmap = bitmap
            diagnosticsListener?.invoke(
                "tile=RENDER_NODE_RECORD sample=${tile.sampleSize} " +
                    "bitmap=${bitmap.width}x${bitmap.height} bytes=${tileBytes(tile)}",
            )
        }
        val saveCount = canvas.save()
        canvas.concat(matrix)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(saveCount)
    }

    private fun discardTileRenderNode(tile: Tile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            discardTileRenderNodeApi29(tile)
        } else {
            tile.renderNode = null
            tile.renderNodeBitmap = null
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun discardTileRenderNodeApi29(tile: Tile) {
        (tile.renderNode as? RenderNode)?.discardDisplayList()
        tile.renderNode = null
        tile.renderNodeBitmap = null
    }

    private fun clearTileBitmap(tile: Tile, recycleBitmap: Boolean) {
        val previous = tile.bitmap
        discardTileRenderNode(tile)
        tile.bitmap = null
        tile.sourceMissWaveId = 0L
        if (recycleBitmap && previous?.isRecycled == false) {
            previous.recycle()
        }
    }

    private fun assignTileBitmap(tile: Tile, bitmap: Bitmap) {
        if (tile.bitmap !== bitmap) {
            // A late duplicate result can replace a bitmap that RenderThread has already
            // seen. Drop ownership and let GC reclaim it instead of recycling across a
            // possibly in-flight display list.
            clearTileBitmap(tile, recycleBitmap = false)
            tile.bitmap = bitmap
        }
    }

    private fun buildSourceMissFragment(
        candidates: List<Tile>,
        directOutputs: Boolean,
    ): List<Tile> {
        val first = candidates.firstOrNull() ?: return emptyList()
        val fragment = mutableListOf(first)
        val remaining = candidates.drop(1).toMutableList()
        val maximumTiles = if (directOutputs) {
            MAX_DIRECT_SOURCE_MISS_TILES_PER_FRAGMENT
        } else {
            MAX_SOURCE_MISS_TILES_PER_FRAGMENT
        }
        val maximumBytes = if (directOutputs) {
            MAX_DIRECT_SOURCE_MISS_FRAGMENT_BYTES
        } else {
            MAX_SOURCE_MISS_FRAGMENT_BYTES
        }
        while (fragment.size < maximumTiles) {
            val nextIndex = remaining.indices
                .filter { canExtendSourceMissFragment(fragment, remaining[it], maximumBytes) }
                .minByOrNull { index -> sourceMissFragmentBytes(fragment + remaining[index]) }
                ?: break
            fragment += remaining.removeAt(nextIndex)
        }
        // Three tiles forming an L require decoding the missing fourth tile's pixels. Two
        // direct batches are cheaper in that case. Full rows, columns and 2x2 groups remain
        // dense and can share one JPEG scan without invisible-area overhead.
        if (directOutputs && fragment.size > 2 && !sourceMissFragmentIsDense(fragment)) {
            return fragment.take(MAX_SOURCE_MISS_TILES_PER_FRAGMENT)
        }
        return fragment
    }

    private fun canExtendSourceMissFragment(
        fragment: List<Tile>,
        candidate: Tile,
        maximumBytes: Long,
    ): Boolean {
        val first = fragment.firstOrNull() ?: return false
        if (candidate.sampleSize != first.sampleSize) return false
        val candidateRect = candidate.fileSRect ?: return false
        val rects = fragment.mapNotNull { it.fileSRect }
        if (rects.size != fragment.size) return false

        val touchesExisting = rects.any { rect ->
            val horizontalTouch = (rect.right == candidateRect.left || candidateRect.right == rect.left) &&
                max(rect.top, candidateRect.top) < min(rect.bottom, candidateRect.bottom)
            val verticalTouch = (rect.bottom == candidateRect.top || candidateRect.bottom == rect.top) &&
                max(rect.left, candidateRect.left) < min(rect.right, candidateRect.right)
            horizontalTouch || verticalTouch
        }
        if (!touchesExisting) return false

        val extended = fragment + candidate
        if (sourceMissFragmentBytes(extended) > maximumBytes) return false

        // Permit an L-shape while assembling a 2x2 fragment, but reject sparse unions
        // that would decode mostly invisible pixels.
        val union = sourceMissFragmentBounds(extended) ?: return false
        val coveredArea = extended.sumOf { tile ->
            val rect = tile.fileSRect ?: return false
            rect.width().toLong() * rect.height().toLong()
        }
        val unionArea = union.width().toLong() * union.height().toLong()
        return coveredArea * 3L >= unionArea * 2L
    }

    private fun sourceMissFragmentIsDense(tiles: List<Tile>): Boolean {
        val union = sourceMissFragmentBounds(tiles) ?: return false
        val coveredArea = tiles.sumOf { tile ->
            val rect = tile.fileSRect ?: return false
            rect.width().toLong() * rect.height().toLong()
        }
        return coveredArea == union.width().toLong() * union.height().toLong()
    }

    private fun sourceMissFragmentBytes(tiles: List<Tile>): Long {
        val first = tiles.firstOrNull() ?: return 0L
        val union = sourceMissFragmentBounds(tiles) ?: return Long.MAX_VALUE
        return ceilDiv(union.width(), first.sampleSize).toLong() *
            ceilDiv(union.height(), first.sampleSize).toLong() *
            ARGB_8888_BYTES_PER_PIXEL
    }

    private fun sourceMissFragmentBounds(tiles: List<Tile>): Rect? {
        val firstRect = tiles.firstOrNull()?.fileSRect ?: return null
        return Rect(firstRect).also { union ->
            tiles.drop(1).forEach { tile ->
                union.union(tile.fileSRect ?: return null)
            }
        }
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun scheduleStableTileRefresh(delayMillis: Long = 150L) {
        val generation = ++stableTileRefreshGeneration
        diagnosticsListener?.invoke(
            "tile=REFRESH_SCHEDULE generation=$generation delayMs=$delayMillis " +
                "sample=${calculateRequiredTileSampleSize()} zooming=$isZooming " +
                "panning=$isPanning anim=${anim != null}",
        )
        postDelayed({
            val deferredToFitPreview = shouldDeferTileLoadsToFitPreview()
            if (
                generation == stableTileRefreshGeneration &&
                decoder != null && tileMap != null &&
                anim == null && !isZooming && !isPanning &&
                !deferredToFitPreview
            ) {
                diagnosticsListener?.invoke(
                    "tile=REFRESH_FIRE generation=$generation sample=${calculateRequiredTileSampleSize()}",
                )
                refreshRequiredTiles(true)
                invalidate()
            } else {
                diagnosticsListener?.invoke(
                    "tile=REFRESH_SKIP generation=$generation current=$stableTileRefreshGeneration " +
                        "decoder=${decoder != null} tileMap=${tileMap != null} zooming=$isZooming " +
                        "panning=$isPanning anim=${anim != null} fitPreview=$deferredToFitPreview",
                )
            }
        }, delayMillis)
    }

    private fun shouldDeferTileLoadsToFitPreview(): Boolean =
        deferTileLoadsAtOrBelowFit &&
            kotlin.math.abs(imageRotation) <= 0.001 &&
            scale <= getFullScale() * 1.02f

    private fun scheduleStableTileCachePersistence() {
        val generation = ++stableTileCacheGeneration
        diagnosticsListener?.invoke(
            "tile=CACHE_SCHEDULE generation=$generation delayMs=$TILE_CACHE_ADMISSION_DELAY_MS " +
                "sample=${calculateRequiredTileSampleSize()} zooming=$isZooming " +
                "panning=$isPanning anim=${anim != null}",
        )
        postDelayed({
            val cacheDecoder = decoder as? BatchedImageRegionDecoder
            if (
                generation != stableTileCacheGeneration ||
                cacheDecoder == null || tileMap == null ||
                anim != null || isZooming || isPanning
            ) {
                diagnosticsListener?.invoke(
                    "tile=CACHE_SKIP generation=$generation current=$stableTileCacheGeneration " +
                        "decoder=${cacheDecoder != null} tileMap=${tileMap != null} " +
                        "zooming=$isZooming panning=$isPanning anim=${anim != null}",
                )
                return@postDelayed
            }

            val sampleSize = calculateRequiredTileSampleSize()
            val decoderCapabilities = cacheDecoder.capabilities(sampleSize)
            if (!decoderCapabilities.persistDecodedTiles) {
                diagnosticsListener?.invoke(
                    "tile=CACHE_SKIP generation=$generation reason=PERSISTENT_REGION_SOURCE",
                )
                return@postDelayed
            }
            val sourceFocus = getCenter() ?: PointF(sWidth() / 2f, sHeight() / 2f)
            val candidates = tileMap!!.values
                .flatten()
                .filter { tile ->
                    tile.visible &&
                        (tile.sampleSize == sampleSize || tile.sampleSize == fullImageSampleSize) &&
                        tile.bitmap?.isRecycled == false &&
                        !tile.loading && !tile.cacheWriteScheduled && !tile.diskCacheReady
                }
                .sortedBy { tile ->
                    val rect = tile.sRect ?: return@sortedBy Float.MAX_VALUE
                    val dx = rect.exactCenterX() - sourceFocus.x
                    val dy = rect.exactCenterY() - sourceFocus.y
                    dx * dx + dy * dy
                }
            val alreadyPending = tileMap!!.values.flatten().count { it.cacheWriteScheduled }
            val admissionSlots =
                (MAX_PENDING_TILE_CACHE_WRITES - alreadyPending).coerceAtLeast(0)
            val admitted = candidates.take(admissionSlots)
            for (tile in admitted) {
                fileSRect(tile.sRect, tile.fileSRect)
                if (cacheDecoder.isRegionCached(tile.fileSRect!!, tile.sampleSize)) {
                    tile.diskCacheReady = true
                } else {
                    executeCacheWrite(TileCacheWriteTask(this, cacheDecoder, tile, generation))
                }
            }
            if (candidates.isNotEmpty()) {
                diagnosticsListener?.invoke(
                    "tile=CACHE_ADMIT sample=$sampleSize candidates=${candidates.size} " +
                        "admitted=${admitted.size} pending=$alreadyPending/$MAX_PENDING_TILE_CACHE_WRITES " +
                        "delayMs=$TILE_CACHE_ADMISSION_DELAY_MS",
                )
            }
        }, TILE_CACHE_ADMISSION_DELAY_MS)
    }

    private fun markTileAccess(tile: Tile) {
        tile.lastAccessSequence = ++tileAccessSequence
    }

    private fun tileBytes(tile: Tile): Long {
        val bitmap = tile.bitmap ?: return 0L
        return runCatching { bitmap.allocationByteCount.toLong() }
            .getOrElse { bitmap.byteCount.toLong() }
    }

    private data class TileMemoryCacheStats(
        val entries: Int,
        val bytes: Long = 0L,
        val evictedCount: Int = 0,
        val evictedBytes: Long = 0L,
        val maxEntries: Int = 0,
        val maxBytes: Long = 0L,
        val profile: String = "none",
    )

    private data class TileMemoryCacheBudget(
        val entries: Int,
        val bytes: Long,
        val profile: String,
    )

    private fun tileMemoryCacheBudget(currentSampleSize: Int): TileMemoryCacheBudget {
        if (!tileMemoryCacheEnabled) {
            return TileMemoryCacheBudget(
                entries = 0,
                bytes = 0L,
                profile = "DISABLED",
            )
        }
        if (!activeTileMemoryCache) {
            return TileMemoryCacheBudget(
                entries = INACTIVE_OFFSCREEN_TILE_CACHE_ENTRIES,
                bytes = INACTIVE_OFFSCREEN_TILE_CACHE_BYTES,
                profile = "INACTIVE",
            )
        }
        val representativeTileBytes = tileMap?.get(currentSampleSize)
            .orEmpty()
            .asSequence()
            .map(::tileBytes)
            .filter { it > 0L }
            .maxOrNull()
            ?: 0L
        val adaptiveBytes = (representativeTileBytes * ACTIVE_OFFSCREEN_TILE_CACHE_ENTRIES)
            .coerceIn(
                ACTIVE_OFFSCREEN_TILE_CACHE_MIN_BYTES,
                ACTIVE_OFFSCREEN_TILE_CACHE_MAX_BYTES,
            )
        return TileMemoryCacheBudget(
            entries = ACTIVE_OFFSCREEN_TILE_CACHE_ENTRIES,
            bytes = adaptiveBytes,
            profile = "ACTIVE",
        )
    }

    /** Visible/base tiles stay active. The current page keeps a bounded recent corridor
     * sized from its actual tile allocations, while inactive pages fall back to the small
     * fixed budget. This prevents 20MB tiles from reducing the active cache to two entries
     * without restoring the stable viewer's unbounded-by-bytes 12-tile retention.
     */
    private fun trimTileMemoryCache(currentSampleSize: Int): TileMemoryCacheStats {
        val map = tileMap ?: return TileMemoryCacheStats(0)
        val budget = tileMemoryCacheBudget(currentSampleSize)
        val loadedTiles = map.values.flatten().filter { it.bitmap?.isRecycled == false }

        val currentViewportTiles = map[currentSampleSize]
            .orEmpty()
            .filter(::tileVisible)
        val currentViewportComplete = currentViewportTiles.isNotEmpty() &&
            currentViewportTiles.all { it.bitmap?.isRecycled == false }

        val offscreenTiles = loadedTiles.filter { tile ->
            val intersectsViewport = tileVisible(tile)
            val protected = tile.loading || tile.cacheWriting ||
                (tileMemoryCacheEnabled && tile.sampleSize == fullImageSampleSize) ||
                (tile.sampleSize == currentSampleSize && intersectsViewport) ||
                (!currentViewportComplete && intersectsViewport)
            !protected
        }.sortedBy { it.lastAccessSequence }

        var evictedCount = 0
        var evictedBytes = 0L
        var remainingEntries = offscreenTiles.size
        var remainingBytes = offscreenTiles.sumOf(::tileBytes)
        for (tile in offscreenTiles) {
            if (
                remainingEntries <= budget.entries &&
                remainingBytes <= budget.bytes
            ) {
                break
            }
            val bytes = tileBytes(tile)
            evictedBytes += bytes
            remainingBytes -= bytes
            remainingEntries -= 1
            // Match the stable Telephoto cache lifecycle: stop owning the bitmap but do
            // not recycle it explicitly. RenderThread may still reference a display list
            // containing this bitmap; recycling it after an arbitrary delay can force a
            // fence wait and make later pans stall for hundreds of milliseconds.
            clearTileBitmap(tile, recycleBitmap = false)
            evictedCount += 1
        }
        if (evictedCount > 0) {
            diagnosticsListener?.invoke(
                "tile=EVICT count=$evictedCount bytes=$evictedBytes " +
                    "offscreen=$remainingEntries/${budget.entries} " +
                    "offscreenBytes=$remainingBytes/${budget.bytes} " +
                    "sample=$currentSampleSize profile=${budget.profile} recycle=GC_SAFE",
            )
        }
        return TileMemoryCacheStats(
            entries = remainingEntries,
            bytes = remainingBytes,
            evictedCount = evictedCount,
            evictedBytes = evictedBytes,
            maxEntries = budget.entries,
            maxBytes = budget.bytes,
            profile = budget.profile,
        )
    }

    private fun tileVisible(tile: Tile): Boolean {
        if (this.imageRotation == 0.0) {
            val sVisLeft = viewToSourceX(0f)
            val sVisRight = viewToSourceX(width.toFloat())
            val sVisTop = viewToSourceY(0f)
            val sVisBottom = viewToSourceY(height.toFloat())
            return !(sVisLeft > tile.sRect!!.right || tile.sRect!!.left > sVisRight || sVisTop > tile.sRect!!.bottom || tile.sRect!!.top > sVisBottom)
        }

        val corners = arrayOf(
            sourceToViewCoord(tile.sRect!!.left.toFloat(), tile.sRect!!.top.toFloat()),
            sourceToViewCoord(tile.sRect!!.right.toFloat(), tile.sRect!!.top.toFloat()),
            sourceToViewCoord(tile.sRect!!.right.toFloat(), tile.sRect!!.bottom.toFloat()),
            sourceToViewCoord(tile.sRect!!.left.toFloat(), tile.sRect!!.bottom.toFloat())
        )

        for (pointF in corners) {
            if (pointF == null) {
                return false
            }
        }

        val rotation = this.imageRotation % (Math.PI * 2)

        return when {
            rotation < Math.PI / 2 -> !(corners[0]!!.y > height || corners[1]!!.x < 0 || corners[2]!!.y < 0 || corners[3]!!.x > width)
            rotation < Math.PI -> !(corners[3]!!.y > height || corners[0]!!.x < 0 || corners[1]!!.y < 0 || corners[2]!!.x > width)
            rotation < Math.PI * 3 / 2 -> !(corners[2]!!.y > height || corners[3]!!.x < 0 || corners[0]!!.y < 0 || corners[1]!!.x > width)
            else -> !(corners[1]!!.y > height || corners[2]!!.x < 0 || corners[3]!!.y < 0 || corners[0]!!.x > width)
        }
    }

    private fun preDraw() {
        if (width == 0 || height == 0 || sWidth <= 0 || sHeight <= 0) {
            return
        }

        if (sPendingCenter != null && pendingScale != null) {
            scale = pendingScale!!
            if (vTranslate == null) {
                vTranslate = PointF()
            }
            vTranslate!!.x = width / 2 - scale * sPendingCenter!!.x
            vTranslate!!.y = height / 2 - scale * sPendingCenter!!.y
            sPendingCenter = null
            pendingScale = null
            refreshRequiredTiles(true)
        }

        fitToBounds()
    }

    private fun availableDecoderSampleSizes(): List<Int>? =
        (decoder as? BatchedImageRegionDecoder)
            ?.availableSampleSizes()
            ?.asSequence()
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()
            ?.toList()
            ?.takeIf { it.firstOrNull() == 1 }

    private fun calculateInSampleSize(scale: Float): Int {
        // Choose the coarsest stored level that is still pixel-complete at this scale.
        // Decoders without an explicit layer directory retain Telephoto's 1/2/4/8 grid.
        val safeScale = scale.coerceAtLeast(0.000001f)
        val inverseScale = 1f / safeScale
        availableDecoderSampleSizes()?.let { availableSamples ->
            return selectStoredSampleSize(availableSamples, inverseScale)
        }
        var sampleSize = 1
        while (sampleSize <= Int.MAX_VALUE / 2 && sampleSize * 2 <= inverseScale) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Select a tile level using the decoder's real boundaries. Keep hysteresis only
     * while moving to a clearer level: the coarser level remains pixel-complete until the
     * scale has crossed the boundary, so a small zoom-in buffer prevents level thrashing.
     * While zooming out, retaining the finer level below that boundary cannot add visible
     * detail and multiplies decoded/drawn pixels, so switch to the coarser level immediately.
     */
    private fun calculateRequiredTileSampleSize(): Int {
        if (fullImageSampleSize <= 0) return 1
        val effectiveScale = scale.coerceAtLeast(0.000001f)

        val inverseScale = 1f / effectiveScale
        val target = tileMap?.keys?.let { availableSamples ->
            selectStoredSampleSize(availableSamples, inverseScale, fullImageSampleSize)
        } ?: 1

        val current = lastRequiredSampleSize
            .takeIf { it in 1..fullImageSampleSize && tileMap?.containsKey(it) == true }
            ?: target
        val selected = selectRequiredStoredSampleSize(
            availableSamples = tileMap?.keys.orEmpty(),
            effectiveScale = effectiveScale,
            currentSampleSize = current,
            maximumSample = fullImageSampleSize,
        )

        if (selected != current) {
            diagnosticsListener?.invoke(
                "tile=SAMPLE_CHANGE from=$current to=$selected target=$target " +
                    "scale=$effectiveScale inverse=$inverseScale",
            )
        }
        lastRequiredSampleSize = selected
        return selected
    }

    private fun fitToBounds(sat: ScaleTranslateRotate) {
        val vTranslate = sat.vTranslate
        val scale = limitedScale(sat.scale)
        val scaledWidth = scale * sWidth()
        val scaledHeight = scale * sHeight()
        val rotation = sat.rotate.toDouble()
        val rotationCos = cos(rotation)
        val rotationSin = sin(rotation)
        val viewCenterX = width / 2f
        val viewCenterY = height / 2f
        val preRotationCenterX = vTranslate.x + scaledWidth / 2f
        val preRotationCenterY = vTranslate.y + scaledHeight / 2f
        val relativeCenterX = preRotationCenterX - viewCenterX
        val relativeCenterY = preRotationCenterY - viewCenterY
        val rotatedCenterX = viewCenterX +
            (relativeCenterX * rotationCos - relativeCenterY * rotationSin).toFloat()
        val rotatedCenterY = viewCenterY +
            (relativeCenterX * rotationSin + relativeCenterY * rotationCos).toFloat()
        val rotatedHalfWidth =
            (abs(rotationCos) * scaledWidth + abs(rotationSin) * scaledHeight).toFloat() / 2f
        val rotatedHalfHeight =
            (abs(rotationSin) * scaledWidth + abs(rotationCos) * scaledHeight).toFloat() / 2f

        val targetCenterX = if (rotatedHalfWidth * 2f <= width) {
            viewCenterX
        } else {
            rotatedCenterX.coerceIn(width - rotatedHalfWidth, rotatedHalfWidth)
        }
        val targetCenterY = if (rotatedHalfHeight * 2f <= height) {
            viewCenterY
        } else {
            rotatedCenterY.coerceIn(height - rotatedHalfHeight, rotatedHalfHeight)
        }

        val rotatedDeltaX = targetCenterX - rotatedCenterX
        val rotatedDeltaY = targetCenterY - rotatedCenterY
        vTranslate.x += (rotatedDeltaX * rotationCos + rotatedDeltaY * rotationSin).toFloat()
        vTranslate.y += (-rotatedDeltaX * rotationSin + rotatedDeltaY * rotationCos).toFloat()
        sat.scale = scale
    }

    fun fitToBounds() {
        var init = false
        if (vTranslate == null) {
            init = true
            vTranslate = PointF(0f, 0f)
        }

        if (satTemp == null) {
            satTemp = ScaleTranslateRotate(0f, PointF(0f, 0f), 0f)
        }

        satTemp!!.scale = scale
        satTemp!!.vTranslate.set(vTranslate!!)
        satTemp!!.rotate = imageRotation.toFloat()
        fitToBounds(satTemp!!)
        scale = satTemp!!.scale
        vTranslate!!.set(satTemp!!.vTranslate)
        setRotationInternal(satTemp!!.rotate.toDouble())

        if (init) {
            vTranslate!!.set(vTranslateForSCenter((sWidth() / 2).toFloat(), (sHeight() / 2).toFloat(), scale))
        }
    }

    /** Captures the small, decoder-independent transform state for pager restoration. */
    fun snapshotViewState(): ViewState? {
        if (!isReady || scale <= 0f) return null
        // A snapshot must be read-only. Calling fitToBounds() here used to mutate the
        // live scale/translation before ACTION_UP reached the gesture state machine,
        // which could visibly shrink or recenter the image while merely saving state.
        val center = getCenter() ?: return null
        val baseFitScale = min(
            width / sWidth().toFloat(),
            height / sHeight().toFloat()
        )
        return ViewState(
            scale = scale,
            baseFitScale = baseFitScale,
            sourceCenter = PointF(center.x, center.y),
            sourceWidth = sWidth(),
            sourceHeight = sHeight(),
            rotationRadians = imageRotation
        )
    }

    /** Restores an already-ready image without reloading its decoder or base layer. */
    fun restoreViewState(state: ViewState) {
        if (!isReady || width <= 0 || height <= 0 || sWidth <= 0 || sHeight <= 0) return
        setRotationInternal(state.rotationRadians)
        prevDegrees = Math.round(Math.toDegrees(imageRotation)).toInt()
        scale = limitedScale(state.scale)
        vTranslate = vTranslateForSCenter(
            state.sourceCenter.x,
            state.sourceCenter.y,
            scale
        )
        fitToBounds()
        refreshRequiredTiles(true)
        invalidate()
    }

    /**
     * Requests a cheap capability check on the next frame. If a persistent index changed,
     * SSIV rebuilds only its tile grid; the live scale, source centre and rotation are retained.
     */
    fun refreshDecoderCapabilities(hostRevision: Long = 0L) {
        if (hostCapabilityRevision == hostRevision) return
        hostCapabilityRevision = hostRevision
        invalidate()
    }

    fun animateToBounds(forceInstantRefresh: Boolean = false) {
        isPanning = false
        val degrees = Math.toDegrees(imageRotation)
        val rightAngle = getClosestRightAngle(degrees)
        val minimumScale = getMinimumScale()

        if (scale >= minimumScale) {
            val isZoomedIn = height < sHeight * scale && width < sWidth * scale
            val center = viewToSourceCoord(PointF(width / 2f, height / 2f)) ?: return
            AnimationBuilder(center, rightAngle).apply {
                duration = if (isZoomedIn || forceInstantRefresh) INSTANT_ANIMATION_DURATION else ANIMATION_DURATION
                start()
            }
        } else {
            val center = PointF(sWidth / 2f, sHeight / 2f)
            AnimationBuilder(center, minimumScale, rightAngle).start()
        }
    }

    private fun getFullScale(): Float {
        val degrees = Math.toDegrees(imageRotation) + orientation
        val rightAngle = getClosestRightAngle(degrees)
        return if (rightAngle % 360 == 0.0 || rightAngle == 180.0) {
            min(width / sWidth.toFloat(), height / sHeight.toFloat())
        } else {
            min(width / sHeight.toFloat(), height / sWidth.toFloat())
        }
    }

    private fun getRotatedFullScale(): Float {
        val degrees = Math.toDegrees(imageRotation) + orientation
        val rightAngle = getClosestRightAngle(degrees)
        return if (rightAngle % 360 == 0.0 || rightAngle == 180.0) {
            min(width / sHeight.toFloat(), height / sWidth.toFloat())
        } else {
            min(width / sWidth.toFloat(), height / sHeight.toFloat())
        }
    }

    private fun initialiseTileMap(maxTileDimensions: Point) {
        debug("initialiseTileMap maxTileDimensions=${maxTileDimensions.x}x${maxTileDimensions.y}")
        tileMap = LinkedHashMap()
        val explicitSamples = availableDecoderSampleSizes()
            ?.filter { it <= fullImageSampleSize }
            ?.toMutableSet()
            ?.apply {
                add(1)
                add(fullImageSampleSize)
            }
            ?.sortedDescending()
        val samples = explicitSamples ?: buildList {
            var sampleSize = fullImageSampleSize
            while (true) {
                add(sampleSize)
                if (sampleSize == 1) break
                sampleSize /= 2
            }
        }

        for (sampleSize in samples) {
            // Bound the decoded bitmap, not its source-space rectangle. The previous
            // Telephoto-derived formula made every foreground tile as large as the whole
            // fit layer. On tall or wide sources that silently produced 20MB tiles even
            // though TARGET_DECODED_TILE_SIZE claimed a 1024-class grid.
            val sourceAlignedTileSize = (decoder as? BatchedImageRegionDecoder)
                ?.capabilities(sampleSize)
                ?.preferredDecodedTileSize
                ?.takeIf { it > 0 }
            val decodedTileSize = sourceAlignedTileSize ?: TARGET_DECODED_TILE_SIZE
            val decodedTileWidth = min(decodedTileSize, maxTileDimensions.x)
                .coerceAtLeast(1)
            val decodedTileHeight = min(decodedTileSize, maxTileDimensions.y)
                .coerceAtLeast(1)
            val sourceTileWidthLimit = min(
                sWidth(),
                (decodedTileWidth.toLong() * sampleSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ).coerceAtLeast(1)
            val sourceTileHeightLimit = min(
                sHeight(),
                (decodedTileHeight.toLong() * sampleSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ).coerceAtLeast(1)
            val xTiles = ceilDiv(sWidth(), sourceTileWidthLimit).coerceAtLeast(1)
            val yTiles = ceilDiv(sHeight(), sourceTileHeightLimit).coerceAtLeast(1)
            // A persistent source grid must keep exact interior boundaries. Ordinary
            // sequential sources retain the balanced remainder that minimizes edge tiles.
            val sTileWidth = if (sourceAlignedTileSize != null) {
                sourceTileWidthLimit
            } else {
                ceilDiv(sWidth(), xTiles)
            }
            val sTileHeight = if (sourceAlignedTileSize != null) {
                sourceTileHeightLimit
            } else {
                ceilDiv(sHeight(), yTiles)
            }

            val tileGrid = ArrayList<Tile>(xTiles * yTiles)
            for (x in 0 until xTiles) {
                for (y in 0 until yTiles) {
                    val tile = Tile()
                    tile.sampleSize = sampleSize
                    tile.visible = sampleSize == fullImageSampleSize
                    tile.sRect = Rect(
                        x * sTileWidth,
                        y * sTileHeight,
                        min(sWidth(), (x + 1) * sTileWidth),
                        min(sHeight(), (y + 1) * sTileHeight)
                    )

                    tile.vRect = Rect(0, 0, 0, 0)
                    tile.fileSRect = Rect(tile.sRect)
                    tileGrid.add(tile)
                }
            }
            tileMap!![sampleSize] = tileGrid

            diagnosticsListener?.invoke(
                "tile=GRID sample=$sampleSize sourceTile=${sTileWidth}x$sTileHeight " +
                    "grid=${xTiles}x$yTiles count=${tileGrid.size} " +
                    "decodedTarget=${(sTileWidth + sampleSize - 1) / sampleSize}x" +
                    "${(sTileHeight + sampleSize - 1) / sampleSize} " +
                    "base=$fullImageSampleSize viewport=${width}x$height source=${sWidth()}x${sHeight()}",
            )
        }
        tileMapCapabilityRevision = (decoder as? BatchedImageRegionDecoder)
            ?.capabilityRevision()
            ?: 0L
    }

    @Synchronized
    private fun refreshTileMapForDecoderCapabilities(maxTileDimensions: Point) {
        val batchDecoder = decoder as? BatchedImageRegionDecoder ?: return
        val currentMap = tileMap ?: run {
            tileMapCapabilityRevision = batchDecoder.capabilityRevision()
            return
        }
        val revision = batchDecoder.capabilityRevision()
        if (revision == tileMapCapabilityRevision) return
        if (isZooming || isPanning || isQuickScaling || anim != null) {
            postInvalidateOnAnimation()
            return
        }

        val previousRevision = tileMapCapabilityRevision
        imageGeneration += 1
        stableTileRefreshGeneration += 1
        stableTileCacheGeneration += 1
        currentMap.values.flatten().forEach { tile ->
            tile.visible = false
            tile.loading = false
            // RenderThread may still hold the previous display list. Drop SSIV's ownership
            // without synchronously recycling the backing Bitmap on the UI thread.
            clearTileBitmap(tile, recycleBitmap = false)
        }
        tileMap = null
        tileAccessSequence = 0L
        isImageLoaded = false
        fullImageSampleSize = calculateInSampleSize(getFullScale())
        lastRequiredSampleSize = calculateInSampleSize(scale)
        initialiseTileMap(maxTileDimensions)
        diagnosticsListener?.invoke(
            "tile=CAPABILITY_REBUILD from=$previousRevision to=$tileMapCapabilityRevision " +
                "scale=$scale center=${getCenter()} base=$fullImageSampleSize",
        )
        refreshRequiredTiles(true)
        invalidate()
    }

    private class TilesInitTask internal constructor(
        view: SubsamplingScaleImageView,
        context: Context,
        decoderFactory: DecoderFactory<out ImageRegionDecoder>,
        private val source: Uri
    ) : AsyncTask<Void, Void, IntArray>() {
        private val generation = view.imageGeneration
        private val viewRef = WeakReference(view)
        private val contextRef = WeakReference(context)
        private val decoderFactoryRef = WeakReference(decoderFactory)
        private var decoder: ImageRegionDecoder? = null
        private var exception: Exception? = null

        override fun doInBackground(vararg params: Void): IntArray? {
            try {
                val context = contextRef.get()
                val decoderFactory = decoderFactoryRef.get()
                val view = viewRef.get()
                if (context != null && decoderFactory != null && view != null) {
                    view.debug("TilesInitTask.doInBackground")
                    decoder = decoderFactory.make()
                    val dimensions = decoder!!.init(context, source)
                    val sWidth = dimensions.x
                    val sHeight = dimensions.y
                    val exifOrientation = view.orientation
                    return intArrayOf(sWidth, sHeight, exifOrientation)
                }
            } catch (e: Exception) {
                exception = e
            }

            return null
        }

        override fun onPostExecute(xyo: IntArray?) {
            val view = viewRef.get()
            if (view == null || view.imageGeneration != generation) {
                decoder?.recycle()
                return
            }
            if (decoder != null && xyo != null && xyo.size == 3) {
                view.onTilesInited(decoder!!, xyo[0], xyo[1], xyo[2])
            } else if (exception != null) {
                view.onImageEventListener?.onImageLoadError(exception!!)
            }
        }    }

    private data class TileBatchResult(
        val tiles: List<Tile>,
        val bitmaps: List<Bitmap>,
    )

    private class TileBatchLoadTask internal constructor(
        view: SubsamplingScaleImageView,
        decoder: BatchedImageRegionDecoder,
        tiles: List<Tile>,
        private val sourceMissWaveId: Long?,
    ) : AsyncTask<Void, Void, TileBatchResult>() {
        private val viewRef = WeakReference(view)
        private val decoderRef = WeakReference(decoder)
        private val tileRefs = tiles.map(::WeakReference)
        private val generation = view.imageGeneration
        private val diagnosticTaskId = NEXT_DIAGNOSTIC_TASK_ID.incrementAndGet()
        private val queuedAtNanos = SystemClock.elapsedRealtimeNanos()
        private var exception: Exception? = null

        init {
            tiles.forEach { it.loading = true }
            view.diagnosticsListener?.invoke(
                "tile=TASK_ENQUEUE id=$diagnosticTaskId mode=BATCH generation=$generation " +
                    "count=${tiles.size} regions=${tiles.joinToString(";") { tile ->
                        val rect = tile.sRect
                        "${rect?.left},${rect?.top}-${rect?.right},${rect?.bottom}@${tile.sampleSize}"
                    }}",
            )
        }

        override fun doInBackground(vararg params: Void): TileBatchResult? {
            try {
                val view = viewRef.get() ?: return null
                val decoder = decoderRef.get() ?: return null
                if (view.imageGeneration != generation || !decoder.isReady()) return null
                val tiles = tileRefs.mapNotNull { it.get() }
                    .filter { it.visible && it.loading && it.bitmap == null }
                if (tiles.isEmpty()) return null

                view.decoderLock.readLock().lock()
                try {
                    if (!decoder.isReady()) return null
                    tiles.forEach { view.fileSRect(it.sRect, it.fileSRect) }
                    val bitmaps = if (tiles.size == 1 && sourceMissWaveId == null) {
                        listOf(decoder.decodeRegion(tiles.first().fileSRect!!, tiles.first().sampleSize))
                    } else {
                        decoder.decodeRegions(
                            tiles.map { Rect(it.fileSRect!!) },
                            tiles.first().sampleSize,
                            sourceMissWaveId,
                        )
                    }
                    require(bitmaps.size == tiles.size) {
                        "Batch decoder returned ${bitmaps.size} bitmaps for ${tiles.size} regions"
                    }
                    return TileBatchResult(tiles, bitmaps)
                } finally {
                    view.decoderLock.readLock().unlock()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to decode tile batch", error)
                exception = error
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Failed to decode tile batch - OutOfMemoryError", error)
                exception = RuntimeException(error)
            }
            return null
        }

        override fun onPostExecute(result: TileBatchResult?) {
            val view = viewRef.get()
            val originalTiles = tileRefs.mapNotNull { it.get() }
            if (view == null || view.imageGeneration != generation) {
                result?.bitmaps?.forEach { if (!it.isRecycled) it.recycle() }
                originalTiles.forEach { it.loading = false }
                view?.diagnosticsListener?.invoke(
                    "tile=TASK_RESULT id=$diagnosticTaskId mode=BATCH stale=true " +
                        "generation=$generation currentGeneration=${view.imageGeneration} " +
                        "totalMs=${(SystemClock.elapsedRealtimeNanos() - queuedAtNanos) / 1_000_000L}",
                )
                return
            }

            val completed = result?.tiles?.zip(result.bitmaps).orEmpty().toMap()
            val allocationBytes = completed.values.sumOf { bitmap ->
                runCatching { bitmap.allocationByteCount.toLong() }
                    .getOrElse { bitmap.byteCount.toLong() }
            }
            for (tile in originalTiles) {
                val bitmap = completed[tile]
                if (bitmap != null) {
                    view.assignTileBitmap(tile, bitmap)
                    tile.failedAttempts = 0
                    view.markTileAccess(tile)
                } else if (exception != null) {
                    tile.failedAttempts += 1
                }
                tile.loading = false
            }
            view.diagnosticsListener?.invoke(
                "tile=TASK_RESULT id=$diagnosticTaskId mode=BATCH stale=false " +
                    "requested=${originalTiles.size} completed=${completed.size} bytes=$allocationBytes " +
                    "error=${exception?.javaClass?.simpleName ?: "none"} " +
                    "totalMs=${(SystemClock.elapsedRealtimeNanos() - queuedAtNanos) / 1_000_000L} " +
                    "visible=${originalTiles.count { it.visible }}",
            )
            if (completed.isNotEmpty()) {
                view.onTileLoaded()
            } else {
                view.onTileLoadFinishedWithoutBitmap(retryImmediately = exception == null)
            }
        }
    }

    private class TileCacheWriteTask internal constructor(
        view: SubsamplingScaleImageView,
        decoder: BatchedImageRegionDecoder,
        tile: Tile,
        private val cacheGeneration: Long,
    ) : AsyncTask<Void, Void, Boolean>() {
        private val viewRef = WeakReference(view)
        private val decoderRef = WeakReference(decoder)
        private val tileRef = WeakReference(tile)
        private val generation = view.imageGeneration
        private val diagnosticTaskId = NEXT_DIAGNOSTIC_TASK_ID.incrementAndGet()
        private val queuedAtNanos = SystemClock.elapsedRealtimeNanos()

        init {
            tile.cacheWriteScheduled = true
            view.diagnosticsListener?.invoke(
                "tile=TASK_ENQUEUE id=$diagnosticTaskId mode=CACHE_WRITE generation=$generation " +
                    "cacheGeneration=$cacheGeneration rect=${tile.sRect} sample=${tile.sampleSize} " +
                    "bytes=${view.tileBytes(tile)}",
            )
        }

        override fun doInBackground(vararg params: Void): Boolean {
            val view = viewRef.get() ?: return false
            val decoder = decoderRef.get() ?: return false
            val tile = tileRef.get() ?: return false
            if (
                view.imageGeneration != generation || !decoder.isReady() ||
                view.stableTileCacheGeneration != cacheGeneration ||
                !tile.visible || tile.loading
            ) {
                return false
            }
            val bitmap = tile.bitmap?.takeIf { !it.isRecycled } ?: return false
            tile.cacheWriting = true
            view.decoderLock.readLock().lock()
            return try {
                if (
                    view.imageGeneration != generation || !decoder.isReady() ||
                    view.stableTileCacheGeneration != cacheGeneration ||
                    !tile.visible || tile.bitmap !== bitmap || bitmap.isRecycled
                ) {
                    false
                } else {
                    view.fileSRect(tile.sRect, tile.fileSRect)
                    decoder.cacheRegion(tile.fileSRect!!, tile.sampleSize, bitmap)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to cache stable tile", error)
                false
            } finally {
                view.decoderLock.readLock().unlock()
                tile.cacheWriting = false
            }
        }

        override fun onPostExecute(cached: Boolean) {
            val tile = tileRef.get() ?: return
            tile.cacheWriteScheduled = false
            if (cached) tile.diskCacheReady = true
            val view = viewRef.get()
            view?.diagnosticsListener?.invoke(
                "tile=TASK_RESULT id=$diagnosticTaskId mode=CACHE_WRITE cached=$cached " +
                    "generation=$generation cacheGeneration=$cacheGeneration " +
                    "currentGeneration=${view.imageGeneration} rect=${tile.sRect} " +
                    "sample=${tile.sampleSize} visible=${tile.visible} totalMs=" +
                    "${(SystemClock.elapsedRealtimeNanos() - queuedAtNanos) / 1_000_000L}",
            )
        }
    }

    @Synchronized
    private fun onTilesInited(decoder: ImageRegionDecoder, sWidth: Int, sHeight: Int, sOrientation: Int) {
        debug("onTilesInited sWidth=$sWidth, sHeight=$sHeight, sOrientation=$orientation")
        if (this.sWidth > 0 && this.sHeight > 0 && (this.sWidth != sWidth || this.sHeight != sHeight)) {
            reset(false)
            clearBaseBitmap()
        }
        this.decoder = decoder
        this.sWidth = sWidth
        this.sHeight = sHeight
        this.sOrientation = sOrientation
        checkReady()
        if (tileMap == null && decoder != null && maxTileWidth > 0 && maxTileWidth != TILE_SIZE_AUTO && maxTileHeight > 0 && maxTileHeight != TILE_SIZE_AUTO && width > 0 && height > 0) {
            initialiseBaseLayer(Point(maxTileWidth, maxTileHeight))
        }

        invalidate()
        requestLayout()
    }

    private class TileLoadTask internal constructor(view: SubsamplingScaleImageView, decoder: ImageRegionDecoder, tile: Tile) :
        AsyncTask<Void, Void, Bitmap>() {
        private val viewRef = WeakReference(view)
        private val decoderRef = WeakReference(decoder)
        private val tileRef = WeakReference(tile)
        private val generation = view.imageGeneration
        private val diagnosticTaskId = NEXT_DIAGNOSTIC_TASK_ID.incrementAndGet()
        private val queuedAtNanos = SystemClock.elapsedRealtimeNanos()
        private var exception: Exception? = null

        init {
            tile.loading = true
            view.diagnosticsListener?.invoke(
                "tile=TASK_ENQUEUE id=$diagnosticTaskId mode=SINGLE generation=$generation " +
                    "rect=${tile.sRect} sample=${tile.sampleSize} diskReady=${tile.diskCacheReady}",
            )
        }

        override fun doInBackground(vararg params: Void): Bitmap? {
            try {
                val view = viewRef.get()
                val decoder = decoderRef.get()
                val tile = tileRef.get()
                if (decoder != null && tile != null && view != null && view.imageGeneration == generation && decoder.isReady() && tile.visible) {
                    view.debug("TileLoadTask.doInBackground, tile.sRect=${tile.sRect as Rect}, tile.sampleSize=${tile.sampleSize}")
                    view.decoderLock.readLock().lock()
                    try {
                        if (decoder.isReady()) {
                            view.fileSRect(tile.sRect, tile.fileSRect)
                            return decoder.decodeRegion(tile.fileSRect!!, tile.sampleSize)
                        } else {
                            tile.loading = false
                        }
                    } finally {
                        view.decoderLock.readLock().unlock()
                    }
                } else {
                    tile?.loading = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode tile $e")
                exception = e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Failed to decode tile - OutOfMemoryError $e")
                exception = RuntimeException(e)
            }

            return null
        }

        override fun onPostExecute(bitmap: Bitmap?) {
            val view = viewRef.get()
            val tile = tileRef.get()
            if (view == null || view.imageGeneration != generation || tile == null) {
                bitmap?.recycle()
                tile?.loading = false
                view?.diagnosticsListener?.invoke(
                    "tile=TASK_RESULT id=$diagnosticTaskId mode=SINGLE stale=true " +
                        "generation=$generation currentGeneration=${view.imageGeneration} " +
                        "totalMs=${(SystemClock.elapsedRealtimeNanos() - queuedAtNanos) / 1_000_000L}",
                )
                return
            }
            if (bitmap != null) {
                view.assignTileBitmap(tile, bitmap)
                tile.loading = false
                tile.failedAttempts = 0
                view.markTileAccess(tile)
                view.diagnosticsListener?.invoke(
                    "tile=TASK_RESULT id=$diagnosticTaskId mode=SINGLE stale=false success=true " +
                        "rect=${tile.sRect} sample=${tile.sampleSize} bitmap=${bitmap.width}x${bitmap.height} " +
                        "bytes=${runCatching { bitmap.allocationByteCount }.getOrElse { bitmap.byteCount }} " +
                        "visible=${tile.visible} totalMs=" +
                        "${(SystemClock.elapsedRealtimeNanos() - queuedAtNanos) / 1_000_000L}",
                )
                view.onTileLoaded()
            } else {
                tile.loading = false
                if (exception != null) tile.failedAttempts += 1
                view.diagnosticsListener?.invoke(
                    "tile=TASK_RESULT id=$diagnosticTaskId mode=SINGLE stale=false success=false " +
                        "rect=${tile.sRect} sample=${tile.sampleSize} visible=${tile.visible} " +
                        "error=${exception?.javaClass?.simpleName ?: "none"} totalMs=" +
                        "${(SystemClock.elapsedRealtimeNanos() - queuedAtNanos) / 1_000_000L}",
                )
                view.onTileLoadFinishedWithoutBitmap(retryImmediately = exception == null)
            }
        }    }

    @Synchronized
    private fun onTileLoaded() {
        debug("onTileLoaded")
        checkReady()
        checkImageLoaded()
        if (getAreBaseTilesReady() && (!bitmapIsBorrowedPreview || borrowedPreviewReleaseRequested)) {
            clearBaseBitmap()
        }
        val currentSampleSize = calculateRequiredTileSampleSize()
        if (!isZooming && !isPanning && anim == null) {
            trimTileMemoryCache(currentSampleSize)
        }
        if (
            (decoder as? BatchedImageRegionDecoder)
                ?.capabilities(currentSampleSize)
                ?.persistDecodedTiles == true
        ) {
            scheduleStableTileCachePersistence()
        }
        val currentTiles = tileMap?.get(currentSampleSize).orEmpty().filter(::tileVisible)
        val hasLoadingTile = currentTiles.any { it.loading }
        val hasMissingTile = currentTiles.any { tile ->
            tile.bitmap == null && tile.failedAttempts < 2 &&
                !(currentSampleSize == fullImageSampleSize && borrowedPreviewCoversCurrentScale())
        }
        diagnosticsListener?.invoke(
            "tile=LOAD_APPLIED sample=$currentSampleSize visible=${currentTiles.size} " +
                "loaded=${currentTiles.count { it.bitmap?.isRecycled == false }} " +
                "loading=${currentTiles.count { it.loading }} missing=$hasMissingTile " +
                "retained=${tileMap?.values.orEmpty().flatten().count { it.bitmap?.isRecycled == false }} " +
                "preview=$bitmapIsBorrowedPreview",
        )
        if (!hasLoadingTile && hasMissingTile && !shouldDeferTileLoadsToFitPreview()) {
            scheduleStableTileRefresh(SOURCE_MISS_NEXT_WAVE_DELAY_MS)
        }
        invalidate()
    }

    @Synchronized
    private fun onTileLoadFinishedWithoutBitmap(retryImmediately: Boolean) {
        // A queued task can become non-visible before it starts and return no bitmap.
        // Once the viewport is stable, rescan immediately so a now-visible gap cannot
        // remain stalled until the user nudges the image. Real decoder failures are not
        // retried here; refreshRequiredTiles caps their later attempts.
        val willRetry = retryImmediately && !isZooming && !isPanning && anim == null
        diagnosticsListener?.invoke(
            "tile=LOAD_EMPTY retryRequested=$retryImmediately willRetry=$willRetry " +
                "zooming=$isZooming panning=$isPanning anim=${anim != null} " +
                "sample=${calculateRequiredTileSampleSize()}",
        )
        if (willRetry) {
            refreshRequiredTiles(true)
        }
        invalidate()
    }

    private class BitmapLoadTask internal constructor(
        view: SubsamplingScaleImageView,
        context: Context,
        decoderFactory: DecoderFactory<out ImageDecoder>,
        private val source: Uri
    ) : AsyncTask<Void, Void, Int>() {
        private val viewRef = WeakReference(view)
        private val contextRef = WeakReference(context)
        private val decoderFactoryRef = WeakReference(decoderFactory)
        private val generation = view.imageGeneration
        private var bitmap: Bitmap? = null
        private var exception: Exception? = null

        override fun doInBackground(vararg params: Void): Int? {
            try {
                val context = contextRef.get()
                val decoderFactory = decoderFactoryRef.get()
                val view = viewRef.get()

                if (context != null && decoderFactory != null && view != null) {
                    view.debug("BitmapLoadTask.doInBackground")
                    bitmap = decoderFactory.make().decode(context, source)
                    return view.orientation
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bitmap", e)
                exception = e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Failed to load bitmap - OutOfMemoryError $e")
                exception = RuntimeException(e)
            }

            return null
        }

        override fun onPostExecute(orientation: Int?) {
            val view = viewRef.get()
            if (view == null || view.imageGeneration != generation) {
                bitmap?.recycle()
                return
            }
            if (bitmap != null && orientation != null) {
                view.onImageLoaded(bitmap, orientation)
            } else if (exception != null) {
                view.onImageEventListener?.onImageLoadError(exception!!)
            }
        }    }

    @Synchronized
    private fun onImageLoaded(bitmap: Bitmap?, sOrientation: Int) {
        debug("onImageLoaded")
        if (sWidth > 0 && sHeight > 0 && (sWidth != bitmap!!.width || sHeight != bitmap.height)) {
            reset(false)
        }

        clearBaseBitmap()
        this.bitmap = bitmap
        bitmapIsBorrowedPreview = false
        sWidth = bitmap!!.width
        sHeight = bitmap.height
        this.sOrientation = sOrientation
        val ready = checkReady()
        val imageLoaded = checkImageLoaded()
        if (ready || imageLoaded) {
            invalidate()
            requestLayout()
        }
    }

    private fun execute(asyncTask: AsyncTask<Void, Void, *>) {
        asyncTask.executeOnExecutor(taskExecutor)
    }

    private fun executeCacheWrite(asyncTask: AsyncTask<Void, Void, *>) {
        asyncTask.executeOnExecutor(cacheTaskExecutor ?: taskExecutor)
    }

    fun setActiveTileMemoryCache(active: Boolean) {
        if (activeTileMemoryCache == active) return
        activeTileMemoryCache = active
        val sampleSize = if (tileMap != null && sWidth > 0 && sHeight > 0) {
            calculateRequiredTileSampleSize()
        } else {
            0
        }
        val stats = if (sampleSize > 0) trimTileMemoryCache(sampleSize) else null
        val budget = if (sampleSize > 0) tileMemoryCacheBudget(sampleSize) else null
        diagnosticsListener?.invoke(
            "tile=CACHE_PROFILE profile=${if (active) "ACTIVE" else "INACTIVE"} " +
                "sample=$sampleSize retained=${stats?.entries ?: 0}/${budget?.entries ?: 0} " +
                "bytes=${stats?.bytes ?: 0L}/${budget?.bytes ?: 0L} " +
                "evicted=${stats?.evictedCount ?: 0}",
        )
    }

    /**
     * Test-only policy: visible tiles remain the renderer's working set, but a bitmap is
     * released as soon as it leaves the viewport. Returning to that area must decode it
     * again, so no in-viewer off-screen LRU can make a later pass look artificially warm.
     */
    fun setTileMemoryCacheEnabled(enabled: Boolean) {
        if (tileMemoryCacheEnabled == enabled) return
        tileMemoryCacheEnabled = enabled
        val sampleSize = if (tileMap != null && sWidth > 0 && sHeight > 0) {
            calculateRequiredTileSampleSize()
        } else {
            0
        }
        val stats = if (sampleSize > 0) trimTileMemoryCache(sampleSize) else null
        diagnosticsListener?.invoke(
            "tile=CACHE_POLICY memory=${if (enabled) "ENABLED" else "DISABLED"} " +
                "sample=$sampleSize retainedOffscreen=${stats?.entries ?: 0} " +
                "bytes=${stats?.bytes ?: 0L} evicted=${stats?.evictedCount ?: 0}",
        )
    }

    fun setMaxTileSize(maxPixels: Int) {
        maxTileWidth = maxPixels
        maxTileHeight = maxPixels
    }

    fun setMaxTileSize(maxPixelsX: Int, maxPixelsY: Int) {
        maxTileWidth = maxPixelsX
        maxTileHeight = maxPixelsY
    }

    private fun getMaxBitmapDimensions(canvas: Canvas) =
        Point(min(canvas.maximumBitmapWidth, maxTileWidth), min(canvas.maximumBitmapHeight, maxTileHeight))

    private fun sWidth(): Int {
        val rotation = getRequiredRotation()
        return if (rotation == 90 || rotation == 270) {
            sHeight
        } else {
            sWidth
        }
    }

    private fun sHeight(): Int {
        val rotation = getRequiredRotation()
        return if (rotation == 90 || rotation == 270) {
            sWidth
        } else {
            sHeight
        }
    }

    private fun fileSRect(sRect: Rect?, target: Rect?) {
        when (getRequiredRotation()) {
            0 -> target!!.set(sRect!!)
            90 -> target!!.set(sRect!!.top, sHeight - sRect.right, sRect.bottom, sHeight - sRect.left)
            180 -> target!!.set(sWidth - sRect!!.right, sHeight - sRect.bottom, sWidth - sRect.left, sHeight - sRect.top)
            else -> target!!.set(sWidth - sRect!!.bottom, sRect.left, sWidth - sRect.top, sRect.right)
        }
    }

    private fun distance(x0: Float, x1: Float, y0: Float, y1: Float): Float {
        val x = x0 - x1
        val y = y0 - y1
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    fun recycle() {
        reset(true)
        bitmapPaint = null
        debugTextPaint = null
        debugLinePaint = null
    }

    private fun viewToSourceX(vx: Float): Float {
        return if (vTranslate == null) {
            Float.NaN
        } else {
            (vx - vTranslate!!.x) / scale
        }
    }

    private fun viewToSourceY(vy: Float): Float {
        return if (vTranslate == null) {
            Float.NaN
        } else {
            (vy - vTranslate!!.y) / scale
        }
    }

    fun viewToSourceCoord(vxy: PointF, sTarget: PointF) = viewToSourceCoord(vxy.x, vxy.y, sTarget)

    fun viewToSourceCoord(vxy: PointF) = viewToSourceCoord(vxy.x, vxy.y, PointF())

    private fun viewToSourceCoord(vx: Float, vy: Float, sTarget: PointF = PointF()): PointF? {
        if (vTranslate == null) {
            return null
        }

        var sXPreRotate = viewToSourceX(vx)
        var sYPreRotate = viewToSourceY(vy)

        if (imageRotation == 0.0) {
            sTarget.set(sXPreRotate, sYPreRotate)
        } else {
            val sourceVCenterX = viewToSourceX((width / 2).toFloat())
            val sourceVCenterY = viewToSourceY((height / 2).toFloat())
            sXPreRotate -= sourceVCenterX
            sYPreRotate -= sourceVCenterY
            sTarget.x = (sXPreRotate * cos + sYPreRotate * sin).toFloat() + sourceVCenterX
            sTarget.y = (-sXPreRotate * sin + sYPreRotate * cos).toFloat() + sourceVCenterY
        }

        return sTarget
    }

    private fun sourceToViewX(sx: Float): Float {
        return if (vTranslate == null) {
            Float.NaN
        } else {
            sx * scale + vTranslate!!.x
        }
    }

    private fun sourceToViewY(sy: Float): Float {
        return if (vTranslate == null) {
            Float.NaN
        } else {
            sy * scale + vTranslate!!.y
        }
    }

    fun sourceToViewCoord(sxy: PointF, vTarget: PointF) = sourceToViewCoord(sxy.x, sxy.y, vTarget)

    fun sourceToViewCoord(sxy: PointF) = sourceToViewCoord(sxy.x, sxy.y, PointF())

    private fun sourceToViewCoord(sx: Float, sy: Float, vTarget: PointF = PointF()): PointF? {
        if (vTranslate == null) {
            return null
        }

        var xPreRotate = sourceToViewX(sx)
        var yPreRotate = sourceToViewY(sy)

        if (imageRotation == 0.0) {
            vTarget.set(xPreRotate, yPreRotate)
        } else {
            val vCenterX = (width / 2).toFloat()
            val vCenterY = (height / 2).toFloat()
            xPreRotate -= vCenterX
            yPreRotate -= vCenterY
            vTarget.x = (xPreRotate * cos - yPreRotate * sin).toFloat() + vCenterX
            vTarget.y = (xPreRotate * sin + yPreRotate * cos).toFloat() + vCenterY
        }

        return vTarget
    }

    private fun sourceToViewRect(sRect: Rect, vTarget: Rect) {
        vTarget.set(
            sourceToViewX(sRect.left.toFloat()).toInt(),
            sourceToViewY(sRect.top.toFloat()).toInt(),
            sourceToViewX(sRect.right.toFloat()).toInt(),
            sourceToViewY(sRect.bottom.toFloat()).toInt()
        )
    }

    private fun vTranslateForSCenter(sCenterX: Float, sCenterY: Float, scale: Float): PointF {
        val vxCenter = width / 2
        val vyCenter = height / 2
        if (satTemp == null) {
            satTemp = ScaleTranslateRotate(0f, PointF(0f, 0f), 0f)
        }

        satTemp!!.scale = scale
        satTemp!!.rotate = imageRotation.toFloat()
        satTemp!!.vTranslate.set(vxCenter - sCenterX * scale, vyCenter - sCenterY * scale)
        fitToBounds(satTemp!!)
        // Never expose the mutable scratch point. restoreViewState used to retain this
        // exact instance as the live translation, so a later animation endpoint
        // calculation overwrote the visible position before the animation had started.
        return PointF(satTemp!!.vTranslate.x, satTemp!!.vTranslate.y)
    }

    /**
     * Returns the closest in-bounds view focus for a source point at [targetScale].
     *
     * Double-tap zoom uses this to keep the tapped source pixel under the user's
     * finger. If an image edge makes that geometrically impossible, only the
     * constrained axis is adjusted.
     */
    private fun limitedVFocus(
        sFocus: PointF,
        requestedVFocus: PointF,
        targetScale: Float,
    ): PointF {
        val viewCenterX = width / 2f
        val viewCenterY = height / 2f
        val focusDeltaX = requestedVFocus.x - viewCenterX
        val focusDeltaY = requestedVFocus.y - viewCenterY
        val preRotatedFocusX =
            viewCenterX + (focusDeltaX * cos + focusDeltaY * sin).toFloat()
        val preRotatedFocusY =
            viewCenterY + (-focusDeltaX * sin + focusDeltaY * cos).toFloat()
        val target = ScaleTranslateRotate(
            scale = targetScale,
            vTranslate = PointF(
                preRotatedFocusX - sFocus.x * targetScale,
                preRotatedFocusY - sFocus.y * targetScale,
            ),
            rotate = imageRotation.toFloat(),
        )
        fitToBounds(target)

        val boundedPreRotatedX = sFocus.x * target.scale + target.vTranslate.x
        val boundedPreRotatedY = sFocus.y * target.scale + target.vTranslate.y
        val boundedDeltaX = boundedPreRotatedX - viewCenterX
        val boundedDeltaY = boundedPreRotatedY - viewCenterY
        return PointF(
            viewCenterX + (boundedDeltaX * cos - boundedDeltaY * sin).toFloat(),
            viewCenterY + (boundedDeltaX * sin + boundedDeltaY * cos).toFloat(),
        )
    }

    private fun limitedSCenter(sCenterX: Float, sCenterY: Float, scale: Float, sTarget: PointF): PointF {
        val vTranslate = vTranslateForSCenter(sCenterX, sCenterY, scale)
        val vxCenter = width / 2
        val vyCenter = height / 2
        val sx = (vxCenter - vTranslate.x) / scale
        val sy = (vyCenter - vTranslate.y) / scale
        sTarget.set(sx, sy)
        return sTarget
    }

    private fun limitedScale(targetScale: Float): Float {
        var newTargetScale = targetScale
        newTargetScale = max(getMinimumScale(), newTargetScale)
        newTargetScale = min(maxScale, newTargetScale)
        return newTargetScale
    }

    private fun getMinimumScale() = getFullScale() * minScaleFactor.coerceIn(0.01f, 1f)

    private fun ease(type: Int, time: Long, from: Float, change: Float, duration: Long, finalValue: Float): Float {
        return if (time == duration) {
            finalValue
        } else {
            when (type) {
                EASE_OUT_QUAD -> easeOutQuad(time, from, change, duration)
                else -> easeInOutQuad(time, from, change, duration)
            }
        }
    }

    private fun easeOutQuad(time: Long, from: Float, change: Float, duration: Long): Float {
        val progress = time.toFloat() / duration.toFloat()
        return -change * progress * (progress - 2) + from
    }

    private fun easeInOutQuad(time: Long, from: Float, change: Float, duration: Long): Float {
        var timeF = time / (duration / 2f)
        return if (timeF < 1) {
            change / 2f * timeF * timeF + from
        } else {
            timeF--
            -change / 2f * (timeF * (timeF - 2) - 1) + from
        }
    }

    private fun debug(message: String) {
        if (debug) {
            Log.d(TAG, message)
        }
    }

    private fun px(px: Int) = (density * px).toInt()

    fun setMinimumDpi(dpi: Int) {
        val metrics = resources.displayMetrics
        val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
        maxScale = averageDpi / dpi
    }

    fun setMinimumTileDpi(minimumTileDpi: Int) {
        val metrics = resources.displayMetrics
        val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
        this.minimumTileDpi = min(averageDpi, minimumTileDpi.toFloat()).toInt()
        if (isReady) {
            reset(false)
            invalidate()
        }
    }

    protected fun onReady() {}

    fun setDoubleTapZoomDpi(dpi: Int) {
        val metrics = resources.displayMetrics
        val averageDpi = (metrics.xdpi + metrics.ydpi) / 2
        doubleTapZoomScale = averageDpi / dpi
    }

    fun isZoomedOut() = scale == getFullScale()

    fun rotateBy(degrees: Int) {
        if (anim != null) {
            return
        }

        val oldDegrees = Math.toDegrees(imageRotation.toDouble())
        val rightAngle = getClosestRightAngle(oldDegrees)
        val newDegrees = ((rightAngle + degrees).toInt())
        val center = PointF(sWidth() / 2f, sHeight() / 2f)
        val scale = if (degrees == -90 || degrees == 90 || degrees == 270) getRotatedFullScale() else scale
        AnimationBuilder(center, scale, newDegrees.toDouble()).start(true)
    }

    inner class AnimationBuilder {
        private val targetScale: Float
        private var targetSCenter: PointF?
        private var targetVFocus: PointF? = null
        private var targetRotation = imageRotation
        var duration = ANIMATION_DURATION
        var easing = EASE_IN_OUT_QUAD
        var interruptible = false

        constructor(sCenter: PointF) {
            targetScale = scale
            targetSCenter = sCenter
        }

        constructor(sCenter: PointF, scale: Float) {
            targetScale = scale
            targetSCenter = sCenter
        }

        constructor(sCenter: PointF, scale: Float, vFocus: PointF) {
            targetScale = scale
            targetSCenter = sCenter
            targetVFocus = PointF(vFocus.x, vFocus.y)
        }

        constructor(sCenter: PointF, degrees: Double) {
            targetScale = scale
            targetSCenter = sCenter
            targetRotation = Math.toRadians(degrees)
        }

        constructor(sCenter: PointF, scale: Float, degrees: Double) {
            targetScale = scale
            targetSCenter = sCenter
            targetRotation = Math.toRadians(degrees)
        }

        fun start(skipCenterLimiting: Boolean = false) {
            val vxCenter = width / 2
            val vyCenter = height / 2

            if (!skipCenterLimiting && targetVFocus == null) {
                targetSCenter = limitedSCenter(targetSCenter!!.x, targetSCenter!!.y, targetScale, PointF())
            }
            val boundedVFocus = targetVFocus?.let {
                limitedVFocus(targetSCenter!!, it, targetScale)
            }

            anim = Anim().apply {
                scaleStart = scale
                scaleEnd = targetScale
                rotationStart = imageRotation.toFloat()
                rotationEnd = targetRotation.toFloat()
                time = System.currentTimeMillis()
                sCenterEndRequested = targetSCenter
                sCenterStart = getCenter()
                sCenterEnd = targetSCenter
                vFocusStart = sourceToViewCoord(targetSCenter!!)
                vFocusEnd = boundedVFocus ?: PointF(
                    vxCenter.toFloat(),
                    vyCenter.toFloat(),
                )
                time = System.currentTimeMillis()
            }

            anim!!.duration = duration
            anim!!.interruptible = interruptible
            anim!!.easing = easing

            invalidate()
        }
    }

    data class ScaleTranslateRotate(var scale: Float, var vTranslate: PointF, var rotate: Float)

    class Tile {
        var sRect: Rect? = null
        var sampleSize = 0
        var bitmap: Bitmap? = null
        // Kept as Any so loading this class remains safe on API 26-28 where RenderNode's
        // public drawing API does not exist. Access is guarded by SDK checks above.
        var renderNode: Any? = null
        var renderNodeBitmap: Bitmap? = null
        var loading = false
        var visible = false
        var vRect: Rect? = null
        var fileSRect: Rect? = null
        var lastAccessSequence = 0L
        var failedAttempts = 0
        var diskCacheReady = false
        var cacheWriteScheduled = false
        var sourceMissWaveId = 0L
        @Volatile var cacheWriting = false
    }

    class Anim {
        var scaleStart = 0f
        var scaleEnd = 0f
        var rotationStart = 0f
        var rotationEnd = 0f
        var sCenterStart: PointF? = null
        var sCenterEnd: PointF? = null
        var sCenterEndRequested: PointF? = null
        var vFocusStart: PointF? = null
        var vFocusEnd: PointF? = null
        var duration = ANIMATION_DURATION
        var interruptible = true
        var easing = EASE_IN_OUT_QUAD
        var time = System.currentTimeMillis()
    }

    interface OnImageEventListener {
        fun onReady()
        fun onImageDrawn()
        fun onImageLoadError(e: Exception)
        fun onImageRotation(degrees: Int)
        fun onUpEvent()
    }
}
