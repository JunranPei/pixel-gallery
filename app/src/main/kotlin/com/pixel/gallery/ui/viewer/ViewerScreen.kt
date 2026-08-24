package com.pixel.gallery.ui.viewer

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.pixel.gallery.ui.components.DeleteConfirmDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.glide.AvesAppGlideModule
import com.pixel.gallery.glide.SvgImage
import com.pixel.gallery.glide.TiffImage
import com.pixel.gallery.glide.VideoThumbnail
import com.pixel.gallery.utils.MimeTypes
import com.pixel.gallery.ui.viewer.formats.ViewerFormatRegistry
import com.pixel.gallery.ui.viewer.formats.ViewerPreviewKind
import com.pixel.gallery.ui.viewer.formats.ViewerRenderPlan
import com.pixel.gallery.ui.viewer.formats.ViewerRegionDecoderKind
import com.pixel.gallery.ui.viewer.decoders.UltraHdrTileSupport
import io.github.indexedjpeg.IndexedJpegStatus
import io.github.indexedjpeg.IndexedJpegStore
import io.github.indexedpng.IndexedPngStatus
import io.github.indexedpng.IndexedPngStore
import io.github.indexedtiff.IndexedTiffStatus
import io.github.indexedtiff.IndexedTiffStore
import io.github.indexedwebp.IndexedWebpStatus
import io.github.indexedwebp.IndexedWebpStore
import io.github.indexedraw.IndexedRawStatus
import io.github.indexedraw.IndexedRawStore
import io.github.indexedheif.IndexedHeifStatus
import io.github.indexedheif.IndexedHeifStore
import io.github.indexedheif.HeifFileType
import io.github.indexedbmp.IndexedBmpStatus
import io.github.indexedbmp.IndexedBmpStore
import io.github.indexedjxl.IndexedJxlStatus
import io.github.indexedjxl.IndexedJxlStore
import com.pixel.gallery.services.ViewerPhotoMetadata
import com.pixel.gallery.ui.theme.EmphasizedTypography
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import me.saket.telephoto.zoomable.glide.ZoomableGlideImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.input.pointer.pointerInput



private val MapnikHttps = XYTileSource(
    "Mapnik",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/"
    ),
    "© OpenStreetMap contributors"
)

private val viewerPhotoMetadataCache = ConcurrentHashMap<String, ViewerPhotoMetadata>()

private val rawIndexExtensions = setOf(
    "3fr", "arw", "bay", "cap", "cr2", "cr3", "crw", "dcr", "dcs", "dng",
    "drf", "eip", "erf", "fff", "gpr", "iiq", "k25", "kdc", "mdc", "mef",
    "mos", "mrw", "nef", "nrw", "obm", "orf", "pef", "ptx", "pxn", "r3d",
    "raf", "raw", "rw2", "rwl", "rwz", "sr2", "srf", "srw", "x3f",
)

private enum class IndexedImageFormat(val displayName: String) {
    JPEG("JPEG"),
    PNG("PNG"),
    TIFF("TIFF"),
    WEBP("WebP"),
    RAW("RAW"),
    HEIF("HEIF/AVIF"),
    BMP("BMP"),
    JXL("JPEG XL"),
}

private data class IndexedImageTarget(
    val format: IndexedImageFormat,
    val path: String,
    val detectedFromContent: Boolean = false,
)

private enum class IndexedImageAction { BUILD, DELETE }

private fun MediaEntry.viewerCacheKey(): String = "$contentId:$dateModifiedMillis"

/**
 * Remembers a content-based format result only after its index has been built.
 * The key includes the MediaStore modification time, so replacing or editing a file naturally
 * falls back to its declared format and requires a fresh on-demand check.
 */
private object IndexedImageFormatMemory {
    private const val PREFS = "indexed_image_format_memory"

    fun get(context: android.content.Context, mediaKey: String?): IndexedImageFormat? {
        val storedName = mediaKey?.let {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(it, null)
        } ?: return null
        return runCatching { IndexedImageFormat.valueOf(storedName) }.getOrNull()
    }

    fun put(context: android.content.Context, mediaKey: String?, format: IndexedImageFormat) {
        mediaKey ?: return
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(mediaKey, format.name)
            .apply()
    }
}

private fun MediaEntry.viewerMetricsDescriptor(role: String): String {
    val fileName = Uri.encode(File(path).name)
    return "$role={id=$contentId name=$fileName mime=$sourceMimeType bytes=$sizeBytes " +
        "dimensions=${width}x$height rotation=$sourceRotationDegrees " +
        "dateTaken=${sourceDateTakenMillis ?: 0L} modified=$dateModifiedMillis added=$dateAddedSecs}"
}

private fun MediaEntry.canContainMotionPhoto(): Boolean =
    sourceMimeType.equals("image/jpeg", ignoreCase = true) ||
        path.endsWith(".jpg", ignoreCase = true) ||
        path.endsWith(".jpeg", ignoreCase = true)

private val pngSignature = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
)

private data class DetectedImageContent(
    val indexedFormat: IndexedImageFormat?,
    val displayName: String,
)

private fun File.detectImageContent(): DetectedImageContent? {
    if (!isFile || !canRead() || length() < 2L) return null
    return try {
        inputStream().buffered().use { input ->
            val header = ByteArray(512)
            val length = input.read(header)
            val asciiHeader = String(header, 0, length.coerceAtLeast(0), Charsets.US_ASCII)
            when {
                length >= 2 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() ->
                    DetectedImageContent(IndexedImageFormat.JPEG, "JPEG")
                length >= pngSignature.size && header.copyOfRange(0, pngSignature.size).contentEquals(pngSignature) ->
                    DetectedImageContent(IndexedImageFormat.PNG, "PNG")
                length >= 4 &&
                    ((header[0] == 0x49.toByte() && header[1] == 0x49.toByte() && header[2] == 0x2a.toByte() && header[3] == 0.toByte()) ||
                        (header[0] == 0x4d.toByte() && header[1] == 0x4d.toByte() && header[2] == 0.toByte() && header[3] == 0x2a.toByte()) ||
                        (header[0] == 0x49.toByte() && header[1] == 0x49.toByte() && header[2] == 0x2b.toByte() && header[3] == 0.toByte()) ||
                        (header[0] == 0x4d.toByte() && header[1] == 0x4d.toByte() && header[2] == 0.toByte() && header[3] == 0x2b.toByte())) ->
                    DetectedImageContent(IndexedImageFormat.TIFF, "TIFF")
                length >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WEBP" ->
                    DetectedImageContent(IndexedImageFormat.WEBP, "WebP")
                length >= 2 && header[0] == 0x42.toByte() && header[1] == 0x4d.toByte() ->
                    DetectedImageContent(IndexedImageFormat.BMP, "BMP")
                length >= 2 && header[0] == 0xff.toByte() && header[1] == 0x0a.toByte() ->
                    DetectedImageContent(IndexedImageFormat.JXL, "JPEG XL")
                length >= 12 && header.copyOfRange(0, 12).contentEquals(
                    byteArrayOf(0, 0, 0, 12, 0x4a, 0x58, 0x4c, 0x20, 0x0d, 0x0a, 0x87.toByte(), 0x0a),
                ) -> DetectedImageContent(IndexedImageFormat.JXL, "JPEG XL")
                length >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") ->
                    DetectedImageContent(null, "GIF")
                length >= 4 && header[0] == 0.toByte() && header[1] == 0.toByte() && header[2] in byteArrayOf(1, 2) && header[3] == 0.toByte() ->
                    DetectedImageContent(null, if (header[2] == 1.toByte()) "ICO" else "CUR")
                length >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "8BPS" ->
                    DetectedImageContent(null, "Photoshop PSD")
                length >= 12 && header.copyOfRange(0, 12).contentEquals(
                    byteArrayOf(0, 0, 0, 12, 0x6a, 0x50, 0x20, 0x20, 0x0d, 0x0a, 0x87.toByte(), 0x0a),
                ) -> DetectedImageContent(null, "JPEG 2000")
                length >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "qoif" ->
                    DetectedImageContent(null, "QOI")
                length >= 5 && asciiHeader.startsWith("%PDF-") ->
                    DetectedImageContent(null, "PDF")
                asciiHeader.trimStart().let { it.startsWith("<svg", true) ||
                    (it.startsWith("<?xml", true) && it.contains("<svg", true)) } ->
                    DetectedImageContent(null, "SVG")
                HeifFileType.hasCompatibleBrand(this@detectImageContent) ->
                    DetectedImageContent(IndexedImageFormat.HEIF, "HEIF/AVIF")
                else -> null
            }
        }
    } catch (_: IOException) {
        null
    }
}

private fun IndexedImageFormat.hasMatchingExtension(path: String): Boolean = when (this) {
    IndexedImageFormat.JPEG -> path.endsWith(".jpg", true) || path.endsWith(".jpeg", true)
    IndexedImageFormat.PNG -> path.endsWith(".png", true)
    IndexedImageFormat.TIFF -> path.endsWith(".tif", true) || path.endsWith(".tiff", true)
    IndexedImageFormat.WEBP -> path.endsWith(".webp", true)
    IndexedImageFormat.HEIF -> path.endsWith(".heic", true) || path.endsWith(".heif", true) ||
        path.endsWith(".hif", true) || path.endsWith(".avif", true)
    IndexedImageFormat.BMP -> path.endsWith(".bmp", true)
    IndexedImageFormat.JXL -> path.endsWith(".jxl", true)
    IndexedImageFormat.RAW -> true
}

private fun MediaEntry.declaredIndexFormat(): IndexedImageFormat? {
    val normalizedMime = sourceMimeType.substringBefore(';').trim().lowercase()
    return when {
        canContainMotionPhoto() -> IndexedImageFormat.JPEG
        normalizedMime == "image/png" || path.endsWith(".png", true) -> IndexedImageFormat.PNG
        normalizedMime == "image/tiff" || path.endsWith(".tif", true) || path.endsWith(".tiff", true) -> IndexedImageFormat.TIFF
        normalizedMime == "image/webp" || path.endsWith(".webp", true) -> IndexedImageFormat.WEBP
        MimeTypes.isRaw(normalizedMime) || path.substringAfterLast('.', "").lowercase() in rawIndexExtensions -> IndexedImageFormat.RAW
        MimeTypes.isIsoBMFFImage(normalizedMime) || path.substringAfterLast('.', "").lowercase() in setOf("heic", "heif", "hif", "avif") -> IndexedImageFormat.HEIF
        normalizedMime == MimeTypes.BMP || path.endsWith(".bmp", true) -> IndexedImageFormat.BMP
        normalizedMime == "image/jxl" || path.endsWith(".jxl", true) -> IndexedImageFormat.JXL
        else -> null
    }
}

private fun trackedDrawableListener(
    token: ViewerLoadMetrics.WorkToken,
    tokenRef: AtomicReference<ViewerLoadMetrics.WorkToken?>,
) = object : RequestListener<Drawable> {
    override fun onLoadFailed(
        e: GlideException?,
        model: Any?,
        target: Target<Drawable>,
        isFirstResource: Boolean,
    ): Boolean {
        if (tokenRef.compareAndSet(token, null)) {
            ViewerLoadMetrics.workFailed(
                token,
                e?.rootCauses?.firstOrNull()?.javaClass?.simpleName
                    ?: e?.javaClass?.simpleName
                    ?: "unknown",
            )
        }
        return false
    }

    override fun onResourceReady(
        resource: Drawable,
        model: Any,
        target: Target<Drawable>,
        dataSource: DataSource,
        isFirstResource: Boolean,
    ): Boolean {
        if (tokenRef.compareAndSet(token, null)) {
            ViewerLoadMetrics.workReady(
                token,
                source = dataSource.name,
                detail = "drawable=${resource.intrinsicWidth}x${resource.intrinsicHeight} " +
                    "model=${model.javaClass.simpleName} first=$isFirstResource",
            )
        }
        return false
    }
}


@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
internal fun ViewerScreen(
    initialId: Long,
    photos: List<MediaEntry>,
    transformStateStore: ViewerTransformStateStore,
    onBack: () -> Unit,
    allowTransfer: Boolean = true,
    onRequestTransfer: (MediaEntry) -> Unit = {},
    enableUltraHdr: Boolean = false,
    viewModel: PhotosViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val initialIndex = remember(initialId, photos) {
        val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
        photos.indexOfFirst { it.contentId == initialId }.coerceAtLeast(0).also { index ->
            ViewerLoadMetrics.event(
                "INITIAL_INDEX_RESOLVED",
                "initialId=$initialId index=$index count=${photos.size} " +
                    "duration=${(android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L}ms",
            )
        }
    }
    val entryId = remember(initialId) {
        ViewerLoadMetrics.ensureEntry(
            context.applicationContext,
            initialId,
            source = "ViewerScreen",
            sourceItems = photos.size,
        )
    }
    
    val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
    var showUI by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var rotationLocked by remember { mutableStateOf(true) }
    var ultraHdrActive by remember { mutableStateOf(false) }

    val currentMedia = remember(pagerState.currentPage, photos) {
        if (photos.isNotEmpty()) photos[pagerState.currentPage] else null
    }

    DisposableEffect(entryId) {
        val activity = context as? Activity
        ViewerLoadMetrics.event(
            "VIEWER_ATTACH",
            "initialId=$initialId initialIndex=$initialIndex count=${photos.size}",
            entryId = entryId,
        )
        val frameListener = activity?.let { ViewerLoadMetrics.attachFrameMetrics(it, entryId) }
        onDispose {
            ViewerLoadMetrics.event("VIEWER_DISPOSE", entryId = entryId)
            if (activity != null) {
                ViewerLoadMetrics.detachFrameMetrics(activity, entryId, frameListener)
            }
            ViewerLoadMetrics.entryEnded(context.applicationContext, entryId, "viewer-dispose")
        }
    }

    LaunchedEffect(entryId) {
        val current = photos.getOrNull(initialIndex)
        val left = photos.getOrNull(initialIndex - 1)
        val right = photos.getOrNull(initialIndex + 1)
        val triplet = listOfNotNull(left, current, right)
        ViewerLoadMetrics.event(
            "ENTRY_NEIGHBOR_CONTEXT",
            buildString {
                append("index=$initialIndex count=${photos.size} ")
                append(left?.viewerMetricsDescriptor("left") ?: "left={none}")
                append(' ')
                append(current?.viewerMetricsDescriptor("current") ?: "current={none}")
                append(' ')
                append(right?.viewerMetricsDescriptor("right") ?: "right={none}")
                append(" tripletBytes=${triplet.sumOf { it.sizeBytes.coerceAtLeast(0L) }}")
                append(" tripletPixels=${triplet.sumOf { it.width.toLong() * it.height.toLong() }}")
            },
            imageKey = current?.viewerCacheKey(),
            entryId = entryId,
        )
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "viewer-compose")
        delay(50)
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "50ms")
        delay(50)
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "100ms")
        delay(150)
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "250ms")
        delay(250)
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "500ms")
        delay(500)
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "1000ms")
        delay(1000)
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "2000ms")
        delay(1000)
        ViewerLoadMetrics.checkpoint(context.applicationContext, entryId, "3000ms")
    }

    // Entering a camera JPEG only reads XMP. Embedded Motion Photo video is
    // extracted lazily when the user explicitly requests playback.
    var motionVideoFile by remember { mutableStateOf<File?>(null) }
    var isPlayingMotion by remember { mutableStateOf(false) }
    var isExtractingMotion by remember { mutableStateOf(false) }
    val viewerScope = rememberCoroutineScope()
    val pagerIsDragged by pagerState.interactionSource.collectIsDraggedAsState()
    // After an edge swipe is released back toward the settled page, Pager can remain
    // scroll-active for a few frames even though the current image already looks centred.
    // A new fast stroke during that window used to be captured by Pager and could expose
    // the opposite neighbour without first panning across the zoomed image. Disable only
    // user paging during this return animation; the Android image view then owns the new
    // stroke immediately, while Pager is still free to finish settling programmatically.
    val pagerReturningToSettledPage =
        pagerState.isScrollInProgress &&
            !pagerIsDragged &&
            pagerState.targetPage == pagerState.settledPage

    LaunchedEffect(pagerReturningToSettledPage) {
        ViewerLoadMetrics.event(
            "PAGER_RETURN_GUARD",
            "enabled=$pagerReturningToSettledPage current=${pagerState.currentPage} " +
                "settled=${pagerState.settledPage} target=${pagerState.targetPage} " +
                "dragged=$pagerIsDragged scrolling=${pagerState.isScrollInProgress}",
            imageKey = photos.getOrNull(pagerState.settledPage)?.viewerCacheKey(),
        )
    }

    val currentMediaCacheKey = remember(currentMedia?.contentId, currentMedia?.dateModifiedMillis) {
        currentMedia?.viewerCacheKey()
    }
    val jpegIndexStore = remember(context.applicationContext) {
        IndexedJpegStore(context.applicationContext)
    }
    val pngIndexStore = remember(context.applicationContext) {
        IndexedPngStore(context.applicationContext)
    }
    val tiffIndexStore = remember(context.applicationContext) {
        IndexedTiffStore(context.applicationContext)
    }
    val webpIndexStore = remember(context.applicationContext) {
        IndexedWebpStore(context.applicationContext)
    }
    val rawIndexStore = remember(context.applicationContext) {
        IndexedRawStore(context.applicationContext)
    }
    val heifIndexStore = remember(context.applicationContext) {
        IndexedHeifStore(context.applicationContext)
    }
    val bmpIndexStore = remember(context.applicationContext) {
        IndexedBmpStore(context.applicationContext)
    }
    val jxlIndexStore = remember(context.applicationContext) {
        IndexedJxlStore(context.applicationContext)
    }
    val currentLocalIndexPath = remember(
        currentMedia?.contentId,
        currentMedia?.dateModifiedMillis,
        currentMedia?.path,
    ) {
        currentMedia
            ?.path
            ?.takeIf { it.isNotEmpty() && File(it).isFile }
    }
    val declaredIndexFormat = remember(
        currentMedia?.contentId,
        currentMedia?.dateModifiedMillis,
        currentMedia?.path,
        currentMedia?.sourceMimeType,
    ) {
        currentMedia?.declaredIndexFormat()
    }
    val rememberedIndexFormat = remember(context.applicationContext, currentMediaCacheKey) {
        IndexedImageFormatMemory.get(context.applicationContext, currentMediaCacheKey)
    }
    var resolvedIndexFormat by remember(currentMediaCacheKey, rememberedIndexFormat) {
        mutableStateOf(rememberedIndexFormat)
    }
    val activeIndexFormat = resolvedIndexFormat ?: declaredIndexFormat
    val currentIndexTarget = remember(
        currentLocalIndexPath,
        activeIndexFormat,
        resolvedIndexFormat,
    ) {
        currentLocalIndexPath?.let { path ->
            activeIndexFormat?.let { format ->
                IndexedImageTarget(
                    format = format,
                    path = path,
                    detectedFromContent = resolvedIndexFormat == format && !format.hasMatchingExtension(path),
                )
            }
        }
    }
    var imageIndexReady by remember { mutableStateOf<Boolean?>(null) }
    var imageIndexAction by remember { mutableStateOf<IndexedImageAction?>(null) }
    var imageIndexBusy by remember { mutableStateOf(false) }
    var pendingIndexBuildAfterResolution by remember(currentMediaCacheKey) { mutableStateOf(false) }
    var unsupportedIndexFormat by remember(currentMediaCacheKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(currentIndexTarget) {
        if (!pendingIndexBuildAfterResolution) imageIndexAction = null
        imageIndexBusy = false
        val isReady = currentIndexTarget?.let { target ->
            withContext(Dispatchers.IO) {
                when (target.format) {
                    IndexedImageFormat.JPEG -> jpegIndexStore.status(target.path) is IndexedJpegStatus.Ready
                    IndexedImageFormat.PNG -> pngIndexStore.status(target.path) is IndexedPngStatus.Ready
                    IndexedImageFormat.TIFF -> tiffIndexStore.status(target.path) is IndexedTiffStatus.Ready
                    IndexedImageFormat.WEBP -> webpIndexStore.status(target.path) is IndexedWebpStatus.Ready
                    IndexedImageFormat.RAW -> rawIndexStore.status(target.path) is IndexedRawStatus.Ready
                    IndexedImageFormat.HEIF -> heifIndexStore.status(target.path) is IndexedHeifStatus.Ready
                    IndexedImageFormat.BMP -> bmpIndexStore.status(target.path) is IndexedBmpStatus.Ready
                    IndexedImageFormat.JXL -> jxlIndexStore.status(target.path) is IndexedJxlStatus.Ready
                }
            }
        }
        imageIndexReady = isReady
        if (pendingIndexBuildAfterResolution) {
            imageIndexAction = if (isReady == true) {
                IndexedImageAction.DELETE
            } else {
                IndexedImageAction.BUILD
            }
            pendingIndexBuildAfterResolution = false
        }
    }
    val settledMediaCacheKey = remember(pagerState.settledPage, photos) {
        photos.getOrNull(pagerState.settledPage)?.viewerCacheKey()
    }
    LaunchedEffect(settledMediaCacheKey, enableUltraHdr) {
        ViewerLoadMetrics.event(
            "HDR_STATE_RESET",
            "settledKey=$settledMediaCacheKey enabled=$enableUltraHdr previous=$ultraHdrActive",
            imageKey = settledMediaCacheKey,
        )
        ultraHdrActive = false
        if (!enableUltraHdr) {
            UltraHdrTileSupport.clearAll()
        }
    }
    DisposableEffect(context, enableUltraHdr, ultraHdrActive) {
        val activity = context as? Activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && activity != null) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            val enableHdr =
                enableUltraHdr && ultraHdrActive && powerManager?.isPowerSaveMode != true
            ViewerLoadMetrics.event(
                "WINDOW_COLOR_MODE_SET",
                "requested=${if (enableHdr) "HDR" else "DEFAULT"} " +
                    "ultraHdrActive=$ultraHdrActive powerSave=${powerManager?.isPowerSaveMode}",
                imageKey = settledMediaCacheKey,
            )
            activity.window.colorMode = if (enableHdr) {
                ActivityInfo.COLOR_MODE_HDR
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ViewerLoadMetrics.event(
                    "WINDOW_COLOR_MODE_DISPOSE",
                    "requested=DEFAULT",
                    imageKey = settledMediaCacheKey,
                )
                activity?.window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }
    val latestMediaCacheKey = rememberUpdatedState(currentMediaCacheKey)
    var viewerPhotoMetadata by remember(currentMediaCacheKey) {
        mutableStateOf<ViewerPhotoMetadata?>(null)
    }

    LaunchedEffect(currentMediaCacheKey) {
        val metadataToken = ViewerLoadMetrics.workStarted(
            type = "MOTION_METADATA",
            imageKey = currentMediaCacheKey ?: "none",
            detail = "mime=${currentMedia?.sourceMimeType}",
        )
        isPlayingMotion = false
        val oldFile = motionVideoFile
        motionVideoFile = null
        isExtractingMotion = false
        if (oldFile != null) {
            val deleteToken = ViewerLoadMetrics.workStarted(
                "MOTION_TEMP_DELETE",
                currentMediaCacheKey ?: "none",
                "path=${oldFile.name}",
            )
            val deleted = withContext(Dispatchers.IO) { oldFile.delete() }
            ViewerLoadMetrics.workReady(deleteToken, detail = "deleted=$deleted")
        }

        val media = currentMedia
        viewerPhotoMetadata = if (media == null || !media.canContainMotionPhoto()) {
            ViewerLoadMetrics.workReady(metadataToken, source = "NOT_APPLICABLE")
            ViewerPhotoMetadata.NONE
        } else {
            val cacheKey = media.viewerCacheKey()
            val cached = viewerPhotoMetadataCache[cacheKey]
            if (cached != null) {
                ViewerLoadMetrics.workReady(
                    metadataToken,
                    source = "MEMORY_CACHE",
                    detail = "hasMotion=${cached.hasMotionPhoto}",
                )
                cached
            } else {
                withContext(Dispatchers.IO) {
                    viewModel.inspectViewerPhoto(media.path)
                }.also {
                    viewerPhotoMetadataCache[cacheKey] = it
                    ViewerLoadMetrics.workReady(
                        metadataToken,
                        source = "EXIF_XMP",
                        detail = "hasMotion=${it.hasMotionPhoto} cacheSize=${viewerPhotoMetadataCache.size}",
                    )
                }
            }
        }
    }

    val hasMotionPhoto = viewerPhotoMetadata?.hasMotionPhoto == true

    DisposableEffect(Unit) {
        val activity = context as? Activity
        onDispose {
            motionVideoFile?.delete()
            val window = activity?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Auto-hide UI timer
    LaunchedEffect(showUI, pagerState.currentPage, isPlayingMotion) {
        ViewerLoadMetrics.event(
            "UI_AUTO_HIDE_SCHEDULE",
            "showUI=$showUI page=${pagerState.currentPage} playingMotion=$isPlayingMotion",
        )
        if (showUI && !isPlayingMotion) {
            delay(3000)
            showUI = false
            ViewerLoadMetrics.event("UI_AUTO_HIDE_FIRE", "page=${pagerState.currentPage}")
        }
    }

    // Immersive Mode
    LaunchedEffect(showUI) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        ViewerLoadMetrics.event(
            "SYSTEM_BARS_SET",
            "visible=$showUI",
            imageKey = currentMediaCacheKey,
        )
        if (showUI) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    val favouriteFlow = remember(currentMedia?.contentId) {
        currentMedia?.let { media ->
            viewModel.isFavourite(media.contentId)
                .onStart {
                    ViewerLoadMetrics.event(
                        "FAVOURITE_QUERY_START",
                        "contentId=${media.contentId}",
                        imageKey = media.viewerCacheKey(),
                    )
                }
                .onEach { value ->
                    ViewerLoadMetrics.event(
                        "FAVOURITE_QUERY_EMIT",
                        "contentId=${media.contentId} value=$value",
                        imageKey = media.viewerCacheKey(),
                    )
                }
                .onCompletion { error ->
                    ViewerLoadMetrics.event(
                        "FAVOURITE_QUERY_END",
                        "contentId=${media.contentId} error=${error?.javaClass?.simpleName ?: "none"}",
                        imageKey = media.viewerCacheKey(),
                    )
                }
        } ?: flowOf(false)
    }
    val isFavourite by favouriteFlow.collectAsState(initial = false)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            beyondViewportPageCount = 1,
            userScrollEnabled = !isPlayingMotion && !pagerReturningToSettledPage,
            key = { photos[it].contentId }
        ) { page ->
            val media = photos[page]
            val pageKey = remember(media.contentId, media.dateModifiedMillis) {
                media.viewerCacheKey()
            }
            val swipeMainTokenRef = remember(pageKey) {
                AtomicReference<ViewerLoadMetrics.WorkToken?>()
            }
            var fullPreviewReady by remember(pageKey) { mutableStateOf(false) }
            DisposableEffect(pageKey) {
                ViewerLoadMetrics.event(
                    "PAGER_PAGE_ATTACH",
                    "page=$page current=${pagerState.currentPage} settled=${pagerState.settledPage} " +
                        "distance=${kotlin.math.abs(initialIndex - page)} mime=${media.sourceMimeType}",
                    imageKey = pageKey,
                )
                onDispose {
                    swipeMainTokenRef.getAndSet(null)?.let {
                        ViewerLoadMetrics.workCleared(it, "page-dispose")
                    }
                    ViewerLoadMetrics.event(
                        "PAGER_PAGE_DISPOSE",
                        "page=$page current=${pagerState.currentPage} settled=${pagerState.settledPage}",
                        imageKey = pageKey,
                    )
                }
            }
            LaunchedEffect(page, pagerState.currentPage, pagerState.settledPage, pagerState.isScrollInProgress) {
                ViewerLoadMetrics.event(
                    "PAGER_PAGE_STATE",
                    "page=$page current=${pagerState.currentPage} settled=${pagerState.settledPage} " +
                        "scrolling=${pagerState.isScrollInProgress}",
                    imageKey = pageKey,
                )
            }
            val isVideo = media.sourceMimeType.startsWith("video/")
            val context = LocalContext.current
            val isGif = remember(media.sourceMimeType, media.path) {
                media.sourceMimeType.equals("image/gif", ignoreCase = true) ||
                    media.path.endsWith(".gif", ignoreCase = true)
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    val shouldPrepareVideo by remember(pagerState, page) {
                        derivedStateOf {
                            page == pagerState.settledPage ||
                                pagerState.layoutInfo.visiblePagesInfo.any { it.index == page }
                        }
                    }
                    val firstFrameModel = remember(media.uri, media.dateModifiedMillis) {
                        VideoThumbnail(
                            context = context,
                            uri = Uri.parse(media.uri),
                            frameTimeMicros = 0L,
                            cacheVersion = media.dateModifiedMillis,
                        )
                    }
                    VideoPlayer(
                        uri = media.uri, 
                        filePath = media.path,
                        fallbackDurationMillis = media.durationMillis ?: 0L,
                        firstFrameModel = firstFrameModel,
                        showUI = showUI, 
                        isActive = pagerState.settledPage == page && !pagerState.isScrollInProgress,
                        shouldPrepare = shouldPrepareVideo,
                        onTap = { showUI = !showUI }
                    )
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val isActivePage = pagerState.settledPage == page
                        val isPagerIdle = !pagerState.isScrollInProgress
                        val isPreviewVisible by remember(pagerState, page) {
                            derivedStateOf {
                                pagerState.settledPage == page ||
                                    (pagerState.isScrollInProgress &&
                                        kotlin.math.abs(pagerState.currentPage - page) <= 1)
                            }
                        }
                        val allowSwipeThumbnailSourceLoad = pagerState.isScrollInProgress
                        val swipeThumbnailModel = remember(
                            media.uri,
                            media.sourceMimeType,
                            media.sizeBytes,
                            media.sourceRotationDegrees,
                            media.dateModifiedMillis
                        ) {
                            AvesAppGlideModule.getModel(
                                context = context,
                                uri = Uri.parse(media.uri),
                                mimeType = media.sourceMimeType,
                                pageId = null,
                                sizeBytes = media.sizeBytes,
                                isThumbnail = true,
                                rotationDegrees = media.sourceRotationDegrees,
                                dateModifiedMillis = media.dateModifiedMillis,
                                // This 200 px image only bridges Grid/pager motion. Persisting it
                                // causes JPEG writes and cache trimming on viewer entry.
                                allowPersistentThumbnailCache = false,
                                traceViewerLoad = ViewerLoadMetrics.isEnabled,
                            ).also { model ->
                                ViewerLoadMetrics.event(
                                    "SWIPE_THUMB_MODEL",
                                    "page=$page model=${model.javaClass.simpleName}",
                                    imageKey = pageKey,
                                )
                            }
                        }
                        val swipeThumbnailSignature = remember(media.dateModifiedMillis) {
                            ObjectKey(media.dateModifiedMillis)
                        }
                        val swipeThumbnailTransform = remember(
                            swipeThumbnailSignature,
                            pageKey,
                            page,
                            allowSwipeThumbnailSourceLoad,
                        ) {
                            { request: com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> ->
                                // At rest the 200 px image is only allowed to reuse the Grid
                                // cache. A cache miss must not decode another copy of the source
                                // while the current page's screen-sized ARGB preview is waiting.
                                // Once a pager gesture starts it may load normally so the incoming
                                // page always has a lightweight moving placeholder.
                                val configured = request
                                    // Match Grid's untransformed 200 px cache entry. Compose
                                    // applies ContentScale.Fit when drawing this cover.
                                    .dontTransform()
                                    .format(DecodeFormat.PREFER_RGB_565)
                                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                    .signature(swipeThumbnailSignature)
                                    .override(200)
                                    .priority(
                                        if (allowSwipeThumbnailSourceLoad) Priority.NORMAL
                                        else Priority.LOW
                                    )
                                    .onlyRetrieveFromCache(!allowSwipeThumbnailSourceLoad)
                                if (ViewerLoadMetrics.isEnabled) {
                                    val mainToken = ViewerLoadMetrics.workStarted(
                                        "SWIPE_THUMB_200PX",
                                        pageKey,
                                        "page=$page sourceAllowed=$allowSwipeThumbnailSourceLoad " +
                                            "model=${swipeThumbnailModel.javaClass.simpleName}",
                                    )
                                    swipeMainTokenRef.getAndSet(mainToken)?.let {
                                        ViewerLoadMetrics.workCleared(it, "request-replaced")
                                    }
                                    configured.listener(
                                        trackedDrawableListener(mainToken, swipeMainTokenRef),
                                    )
                                } else {
                                    configured
                                }
                            }
                        }

                        GlideImage(
                            model = swipeThumbnailModel,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (fullPreviewReady) 0f else 1f),
                            contentScale = ContentScale.Fit,
                            requestBuilderTransform = swipeThumbnailTransform
                        )

                        if (isGif) {
                            val model = remember(media.uri, media.sourceMimeType, media.sizeBytes) {
                                AvesAppGlideModule.getModel(
                                    context = context,
                                    uri = Uri.parse(media.uri),
                                    mimeType = media.sourceMimeType,
                                    pageId = null,
                                    sizeBytes = media.sizeBytes
                                )
                            }
                        val containerWidth = constraints.maxWidth.toFloat()
                        val containerHeight = constraints.maxHeight.toFloat()

                        val scaleFit = remember(
                            media.width,
                            media.height,
                            media.sourceRotationDegrees,
                            containerWidth,
                            containerHeight
                        ) {
                            val rotation = media.sourceRotationDegrees
                            val isSwapped = rotation == 90 || rotation == 270
                            val imgWidth = (if (isSwapped) media.height else media.width).toFloat()
                            val imgHeight = (if (isSwapped) media.width else media.height).toFloat()

                            if (imgWidth <= 0f || imgHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) {
                                1f
                            } else {
                                val imgRatio = imgWidth / imgHeight
                                val layoutRatio = containerWidth / containerHeight

                                if (imgRatio > layoutRatio) {
                                    containerWidth / imgWidth
                                } else {
                                    containerHeight / imgHeight
                                }
                            }
                        }

                        val scaleToOriginal = remember(scaleFit) {
                            if (scaleFit > 0f) 1f / scaleFit else 1f
                        }

                        val minUserZoom = remember(scaleToOriginal) {
                            minOf(scaleToOriginal * 0.333f, 0.333f).coerceAtLeast(0.05f)
                        }

                        val calculatedMaxZoom = remember(scaleToOriginal) {
                            maxOf(scaleToOriginal * 3.0f, 3.0f).coerceIn(3.0f, 60.0f)
                        }

                        val targetDoubleTapZoom = remember(scaleToOriginal) {
                            if (kotlin.math.abs(scaleToOriginal - 1.0f) < 0.05f) 2.0f else scaleToOriginal
                        }

                        val zoomSpec = remember(calculatedMaxZoom, minUserZoom) {
                            val spec = ZoomSpec(
                                maxZoomFactor = calculatedMaxZoom,
                                preventOverOrUnderZoom = true
                            )
                            try {
                                val zoomRangeClass = Class.forName("me.saket.telephoto.zoomable.ZoomRange")
                                val customRange = zoomRangeClass
                                    .getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                                    .apply { isAccessible = true }
                                    .newInstance(minUserZoom, calculatedMaxZoom)
                                val rangeField = ZoomSpec::class.java.declaredFields.firstOrNull {
                                    it.name == "range" || it.type.name.contains("ZoomRange")
                                }
                                rangeField?.apply { isAccessible = true }?.set(spec, customRange)
                            } catch (e: Exception) {
                                android.util.Log.e("GalleryCompose", "Failed to patch ZoomSpec range: $e")
                            }
                            spec
                        }

                        val zoomableState = key(media.contentId) {
                            rememberZoomableImageState(
                                zoomableState = rememberZoomableState(
                                    zoomSpec = zoomSpec
                                )
                            )
                        }


                            val coroutineScope = rememberCoroutineScope()
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zoomable(zoomableState.zoomableState)
                                    .pointerInput(scaleFit, scaleToOriginal) {
                                        detectTapGestures(
                                            onTap = {
                                                if (isPlayingMotion) {
                                                    isPlayingMotion = false
                                                } else {
                                                    showUI = !showUI
                                                }
                                            },
                                            onDoubleTap = { centroid: Offset ->
                                                val state = zoomableState.zoomableState
                                                val userZoom = state.contentTransformation.scaleMetadata.userZoom
                                                if (kotlin.math.abs(userZoom - 1f) < 0.05f) {
                                                    coroutineScope.launch {
                                                        state.zoomTo(zoomFactor = targetDoubleTapZoom, centroid = centroid)
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        state.resetZoom()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    .graphicsLayer {
                                        val transformation = zoomableState.zoomableState.contentTransformation
                                        scaleX = transformation.scale.scaleX
                                        scaleY = transformation.scale.scaleY
                                        translationX = transformation.offset.x
                                        translationY = transformation.offset.y
                                        transformOrigin = transformation.transformOrigin
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                GlideImage(
                                    model = model,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    requestBuilderTransform = { request ->
                                        request
                                            .withViewerTaskCompression()
                                            .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                                override fun onLoadFailed(
                                                    e: com.bumptech.glide.load.engine.GlideException?,
                                                    model: Any?,
                                                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                                    isFirstResource: Boolean,
                                                ): Boolean {
                                                    fullPreviewReady = false
                                                    return false
                                                }

                                                override fun onResourceReady(
                                                    resource: android.graphics.drawable.Drawable,
                                                    model: Any,
                                                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                                    dataSource: com.bumptech.glide.load.DataSource,
                                                    isFirstResource: Boolean,
                                                ): Boolean {
                                                    fullPreviewReady = true
                                                    return false
                                                }
                                            })
                                    },
                                )
                            }
                        } else {
                            // [Legacy/Original Code commented out per user request - migrated to Simple-Gallery native SubsamplingScaleImageView component]
                            /*
                            ZoomableGlideImage(
                                model = model,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                state = zoomableState,
                                contentScale = ContentScale.Fit,
                                requestBuilderTransform = transform,
                                onClick = { 
                                    if (isPlayingMotion) {
                                        isPlayingMotion = false
                                    } else {
                                        showUI = !showUI 
                                    }
                                },
                                onDoubleClick = { state, centroid ->
                                    val userZoom = state.contentTransformation.scaleMetadata.userZoom
                                    if (kotlin.math.abs(userZoom - 1f) < 0.05f) {
                                        state.zoomTo(zoomFactor = targetDoubleTapZoom, centroid = centroid)
                                    } else {
                                        state.resetZoom()
                                    }
                                }
                            )
                            */
                            val metadataPending = isActivePage &&
                                pagerState.currentPage == page &&
                                media.canContainMotionPhoto() && viewerPhotoMetadata == null
                            val renderPlan = remember(media.contentId, media.dateModifiedMillis) {
                                ViewerFormatRegistry.resolve(media).also {
                                    ViewerLoadMetrics.event(
                                        "RENDER_PLAN_RESOLVED",
                                        "page=$page plan=$it",
                                        imageKey = pageKey,
                                    )
                                }
                            }
                            val onImageClick = {
                                if (isPlayingMotion) isPlayingMotion = false else showUI = !showUI
                            }
                            if (renderPlan is ViewerRenderPlan.PreviewOnly) {
                                GlideViewerFallback(
                                    imagePath = media.path.ifEmpty { media.uri },
                                    width = media.width,
                                    height = media.height,
                                    orientationDegrees = media.sourceRotationDegrees,
                                    dateModifiedMillis = media.dateModifiedMillis,
                                    isVisiblePage = isPreviewVisible,
                                    modifier = Modifier.fillMaxSize(),
                                    onContentReadyChanged = { fullPreviewReady = it },
                                    onClick = onImageClick
                                )
                            } else if (renderPlan is ViewerRenderPlan.RawEmbeddedPreview) {
                                RawEmbeddedPreviewViewer(
                                    uri = media.uri,
                                    filePath = media.path,
                                    width = media.width,
                                    height = media.height,
                                    orientationDegrees = media.sourceRotationDegrees,
                                    dateModifiedMillis = media.dateModifiedMillis,
                                    isActivePage = isActivePage,
                                    isPreviewVisible = isPreviewVisible,
                                    rawIndexReady = imageIndexReady == true &&
                                        currentIndexTarget?.format == IndexedImageFormat.RAW &&
                                        currentIndexTarget.path == media.path,
                                    transformStateStore = transformStateStore,
                                    modifier = Modifier.fillMaxSize(),
                                    onContentReadyChanged = { fullPreviewReady = it },
                                    onClick = onImageClick,
                                )
                            } else if (
                                renderPlan is ViewerRenderPlan.IndexedBmp ||
                                renderPlan is ViewerRenderPlan.IndexedJxl
                            ) {
                                val indexedFormat = if (renderPlan is ViewerRenderPlan.IndexedJxl) {
                                    IndexedImageFormat.JXL
                                } else {
                                    IndexedImageFormat.BMP
                                }
                                val indexedRegionKind = if (renderPlan is ViewerRenderPlan.IndexedJxl) {
                                    ViewerRegionDecoderKind.JXL
                                } else {
                                    ViewerRegionDecoderKind.BMP
                                }
                                val rareIndexReady = imageIndexReady == true &&
                                    currentIndexTarget?.format == indexedFormat &&
                                    currentIndexTarget.path == media.path
                                if (!rareIndexReady) {
                                    GlideViewerFallback(
                                        imagePath = media.path.ifEmpty { media.uri },
                                        width = media.width,
                                        height = media.height,
                                        orientationDegrees = media.sourceRotationDegrees,
                                        dateModifiedMillis = media.dateModifiedMillis,
                                        isVisiblePage = isPreviewVisible,
                                        modifier = Modifier.fillMaxSize(),
                                        onContentReadyChanged = { fullPreviewReady = it },
                                        onClick = onImageClick,
                                    )
                                } else {
                                    SimpleSubsamplingImageView(
                                        uri = media.uri,
                                        filePath = media.path,
                                        contentId = media.contentId,
                                        orientationDegrees = media.sourceRotationDegrees,
                                        isActivePage = isActivePage,
                                        isPagerIdle = isPagerIdle,
                                        // The incoming page already has a lightweight 200px cover.
                                        // Load its screen-sized preview only after it settles; doing
                                        // both source decodes during the pager gesture caused sporadic
                                        // high-power swipes between otherwise unzoomed large images.
                                        isPreviewVisible = isActivePage,
                                        enableSubsampling = !metadataPending,
                                        dateModifiedMillis = media.dateModifiedMillis,
                                        sourceWidth = media.width,
                                        sourceHeight = media.height,
                                        previewModel = media.path.ifEmpty { media.uri },
                                        intermediatePreviewModel = swipeThumbnailModel,
                                        regionDecoderKind = indexedRegionKind,
                                        transformStateStore = transformStateStore,
                                        onContentReadyChanged = { fullPreviewReady = it },
                                        modifier = Modifier.fillMaxSize(),
                                        onClick = onImageClick,
                                    )
                                }
                            } else {
                                val tiledPlan = renderPlan as ViewerRenderPlan.Tiled
                                val sourceUri = remember(media.uri, media.path) {
                                    media.path.takeIf { it.isNotEmpty() && File(it).isFile }
                                        ?.let { Uri.fromFile(File(it)) }
                                        ?: Uri.parse(media.uri)
                                }
                                val previewModel = remember(tiledPlan.previewKind, sourceUri) {
                                    when (tiledPlan.previewKind) {
                                        ViewerPreviewKind.DEFAULT -> media.path.ifEmpty { media.uri }
                                        ViewerPreviewKind.SVG -> SvgImage(context, sourceUri)
                                        ViewerPreviewKind.TIFF -> TiffImage(context, sourceUri)
                                    }
                                }
                                SimpleSubsamplingImageView(
                                    uri = media.uri,
                                    filePath = media.path,
                                    contentId = media.contentId,
                                    orientationDegrees = media.sourceRotationDegrees,
                                    isActivePage = isActivePage,
                                    isPagerIdle = isPagerIdle,
                                    isPreviewVisible = isActivePage,
                                    enableSubsampling = !metadataPending,
                                    dateModifiedMillis = media.dateModifiedMillis,
                                    sourceWidth = media.width,
                                    sourceHeight = media.height,
                                    enableUltraHdr = enableUltraHdr,
                                    previewModel = previewModel,
                                    intermediatePreviewModel = swipeThumbnailModel,
                                    metricsDetail = "id=${media.contentId} mime=${media.sourceMimeType} " +
                                        "bytes=${media.sizeBytes} source=${media.width}x${media.height} " +
                                        "rotation=${media.sourceRotationDegrees} modified=${media.dateModifiedMillis} " +
                                        "preview=${tiledPlan.previewKind} decoder=${tiledPlan.regionDecoderKind} " +
                                        "motionPending=$metadataPending",
                                    regionDecoderKind = tiledPlan.regionDecoderKind,
                                    transformStateStore = transformStateStore,
                                    onContentReadyChanged = { fullPreviewReady = it },
                                    onUltraHdrAvailabilityChanged = { available ->
                                        ViewerLoadMetrics.event(
                                            "ULTRA_HDR_AVAILABILITY",
                                            "page=$page available=$available settled=${pagerState.settledPage}",
                                            imageKey = pageKey,
                                        )
                                        if (enableUltraHdr && pagerState.settledPage == page) {
                                            ultraHdrActive = available
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    onClick = onImageClick
                                )
                            }
                        }
                        
                        if (isPlayingMotion && motionVideoFile != null) {
                            VideoPlayer(
                                uri = Uri.fromFile(motionVideoFile!!).toString(),
                                isMotionPhoto = true,
                                isActive = true, 
                                modifier = Modifier.fillMaxSize(),
                                onTap = { isPlayingMotion = false }
                            )
                        }
                    }
                }
            }
        }

        // Top Overlay
        AnimatedVisibility(
            visible = showUI,
            enter = fadeIn(animationSpec = tween(durationMillis = 150)) + slideInVertically(animationSpec = tween(durationMillis = 150)) { -it },
            exit = fadeOut(animationSpec = tween(durationMillis = 150)) + slideOutVertically(animationSpec = tween(durationMillis = 150)) { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        if (hasMotionPhoto || motionVideoFile != null) {
                            IconButton(
                                enabled = !isExtractingMotion,
                                onClick = {
                                    if (isPlayingMotion) {
                                        isPlayingMotion = false
                                    } else if (motionVideoFile != null) {
                                        isPlayingMotion = true
                                    } else {
                                        val media = currentMedia ?: return@IconButton
                                        val requestedKey = currentMediaCacheKey ?: return@IconButton
                                        isExtractingMotion = true
                                        viewerScope.launch {
                                            val extracted = withContext(Dispatchers.IO) {
                                                viewModel.extractMotionVideo(media.path)
                                            }
                                            if (latestMediaCacheKey.value == requestedKey) {
                                                motionVideoFile = extracted
                                                isPlayingMotion = extracted != null
                                                isExtractingMotion = false
                                            } else {
                                                withContext(Dispatchers.IO) { extracted?.delete() }
                                            }
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isPlayingMotion) Icons.Default.MotionPhotosPause else Icons.Default.MotionPhotosOn,
                                    contentDescription = "Motion Photo",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(onClick = { 
                            rotationLocked = !rotationLocked
                            val activity = context as? Activity
                            activity?.requestedOrientation = if (rotationLocked) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            }
                        }) {
                            Icon(
                                imageVector = if (rotationLocked) Icons.Outlined.ScreenLockRotation else Icons.Outlined.ScreenRotation,
                                contentDescription = "Auto-Rotate",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            currentIndexTarget?.let { indexTarget ->
                                val indexReady = imageIndexReady == true
                                val indexChecking = imageIndexReady == null
                                val formatName = indexTarget.format.displayName
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                imageIndexBusy -> "Building $formatName index…"
                                                indexChecking -> "Checking $formatName index…"
                                                indexReady -> "Delete $formatName index"
                                                else -> "Build $formatName index"
                                            }
                                        )
                                    },
                                    enabled = !imageIndexBusy && !indexChecking,
                                    onClick = {
                                        showMenu = false
                                        if (indexReady) {
                                            imageIndexAction = IndexedImageAction.DELETE
                                        } else {
                                            imageIndexBusy = true
                                            val requestedTarget = indexTarget
                                            viewerScope.launch {
                                                val detected = withContext(Dispatchers.IO) {
                                                    File(requestedTarget.path).detectImageContent()
                                                        ?: DetectedImageContent(null, "Unrecognized image format")
                                                }
                                                if (currentIndexTarget == requestedTarget) {
                                                    val detectedFormat = detected.indexedFormat
                                                    when {
                                                        detectedFormat == null -> {
                                                            unsupportedIndexFormat = detected.displayName
                                                            imageIndexBusy = false
                                                        }
                                                        detectedFormat == requestedTarget.format -> {
                                                            imageIndexBusy = false
                                                            imageIndexAction = IndexedImageAction.BUILD
                                                        }
                                                        else -> {
                                                            pendingIndexBuildAfterResolution = true
                                                            resolvedIndexFormat = detectedFormat
                                                        }
                                                    }
                                                } else {
                                                    imageIndexBusy = false
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (indexReady) Icons.Outlined.DeleteSweep else Icons.Outlined.Storage,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Set as Wallpaper") },
                                onClick = {
                                    showMenu = false
                                    currentMedia?.let { media ->
                                        val intent = WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(Uri.parse(media.uri))
                                        context.startActivity(intent)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) }
                            )
                            if (allowTransfer) {
                                DropdownMenuItem(
                                    text = { Text("Move or copy to…") },
                                    onClick = {
                                        showMenu = false
                                        currentMedia?.let(onRequestTransfer)
                                    },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Move to locked folder") },
                                onClick = {
                                    showMenu = false
                                    currentMedia?.let { media ->
                                        viewModel.moveToVault(media)
                                        onBack()
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Open With") },
                                onClick = {
                                    showMenu = false
                                    currentMedia?.let { media ->
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(Uri.parse(media.uri), media.sourceMimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        try {
                                            context.startActivity(Intent.createChooser(intent, "Open with..."))
                                        } catch (e: Exception) { }
                                    }
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        }

        // Bottom Overlay
        AnimatedVisibility(
            visible = showUI,
            enter = fadeIn(animationSpec = tween(durationMillis = 150)) + slideInVertically(animationSpec = tween(durationMillis = 150)) { it },
            exit = fadeOut(animationSpec = tween(durationMillis = 150)) + slideOutVertically(animationSpec = tween(durationMillis = 150)) { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp, top = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ViewerAction(Icons.Outlined.Share, "Share") {
                        currentMedia?.let { media ->
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(media.path))
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = media.sourceMimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Media"))
                        }
                    }
                    ViewerAction(Icons.Outlined.Edit, "Edit") {
                        currentMedia?.let { media ->
                            val intent = Intent(Intent.ACTION_EDIT).apply {
                                setDataAndType(Uri.parse(media.uri), media.sourceMimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                context.startActivity(Intent.createChooser(intent, "Edit Media"))
                            } catch (e: Exception) { }
                        }
                    }
                    IconButton(onClick = { 
                        currentMedia?.let { viewModel.toggleFavourite(it.contentId, isFavourite) }
                    }) {
                        Icon(
                            imageVector = if (isFavourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavourite) Color.Red else Color.White
                        )
                    }
                    ViewerAction(Icons.Outlined.Info, "Info") {
                        showInfo = true
                    }
                    if (currentMedia?.isTrashed == true) {
                        ViewerAction(Icons.Outlined.RestoreFromTrash, "Restore") {
                            currentMedia?.let { media ->
                                viewModel.restoreMedia(media.contentId, media.uri)
                                onBack()
                            }
                        }
                    } else {
                        ViewerAction(Icons.Outlined.Delete, "Delete") {
                            showDeleteConfirm = true
                        }
                    }
                }
            }
        }

        if (showInfo && currentMedia != null) {
            InfoBottomSheet(
                media = currentMedia,
                viewModel = viewModel,
                onDismiss = { showInfo = false }
            )
        }

        DeleteConfirmDialog(
            visible = showDeleteConfirm,
            onDismissRequest = { showDeleteConfirm = false },
            title = "Move to Recycle Bin?",
            message = "Move this item to the recycle bin?",
            confirmLabel = "Move to Bin",
            isDeletePermanently = false,
            onConfirm = {
                currentMedia?.let { media ->
                    viewModel.moveToTrash(media.contentId, media.uri, media.path)
                    onBack()
                }
            }
        )

        imageIndexAction?.let { action ->
            val target = currentIndexTarget ?: return@let
            val formatName = target.format.displayName
            val isBuild = action == IndexedImageAction.BUILD
            val detectedFormatMessage = if (target.detectedFromContent) {
                "This file is named .${File(target.path).extension}, but its actual content is $formatName. " +
                    "It will be indexed as $formatName.\n\n"
            } else {
                ""
            }
            AlertDialog(
                onDismissRequest = { if (!imageIndexBusy) imageIndexAction = null },
                title = {
                    Text(if (isBuild) "Build $formatName index?" else "Delete $formatName index?")
                },
                text = {
                    Text(
                        if (isBuild) {
                            detectedFormatMessage + when (target.format) {
                                IndexedImageFormat.JPEG ->
                                    "This reads the complete JPEG once and may briefly use significant power. " +
                                        "The saved seek index applies only to this image and every zoom level."
                                IndexedImageFormat.PNG ->
                                    "This decodes the complete PNG once and builds a lossless multi-resolution " +
                                        "tile index. It may temporarily use significant power and storage."
                                IndexedImageFormat.TIFF ->
                                    "This validates and activates the TIFF's existing tiles, strips, and " +
                                        "reduced-resolution directories. It does not decode or duplicate the full image."
                                IndexedImageFormat.WEBP ->
                                    "This decodes the complete static WebP once and builds a lossless " +
                                        "multi-resolution tile index. Animated WebP is not changed or indexed."
                                IndexedImageFormat.RAW ->
                                    "This develops the complete camera RAW once with camera white balance and " +
                                        "builds a lossless sRGB tile pyramid. The original RAW is never changed."
                                IndexedImageFormat.HEIF ->
                                    "This reads bounded HEIF/AVIF regions once and builds a lossless tile " +
                                        "pyramid. The complete decoded image is never held in memory."
                                IndexedImageFormat.BMP ->
                                    "This validates the uncompressed BMP row layout and writes a 56-byte " +
                                        "activation. Later zoom reads only scan lines crossing the viewport."
                                IndexedImageFormat.JXL ->
                                    "This streams the complete still JPEG XL once and builds a lossless sRGB " +
                                        "multi-resolution tile index without holding the complete image in memory. " +
                                        "Animated and HDR JPEG XL are not indexed yet."
                            }
                        } else {
                            "Delete the saved $formatName index for this image? Future uncached tiles will use " +
                                "the standard decoder again."
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !imageIndexBusy,
                        onClick = {
                            val operationTarget = currentIndexTarget ?: return@TextButton
                            imageIndexAction = null
                            imageIndexBusy = isBuild
                            viewerScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        when (operationTarget.format) {
                                            IndexedImageFormat.JPEG -> if (isBuild) {
                                                jpegIndexStore.build(operationTarget.path)
                                            } else {
                                                check(jpegIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the JPEG index"
                                                }
                                            }
                                            IndexedImageFormat.PNG -> if (isBuild) {
                                                pngIndexStore.build(operationTarget.path)
                                            } else {
                                                check(pngIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the PNG index"
                                                }
                                            }
                                            IndexedImageFormat.TIFF -> if (isBuild) {
                                                tiffIndexStore.build(operationTarget.path)
                                            } else {
                                                check(tiffIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the TIFF index"
                                                }
                                            }
                                            IndexedImageFormat.WEBP -> if (isBuild) {
                                                webpIndexStore.build(operationTarget.path)
                                            } else {
                                                check(webpIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the WebP index"
                                                }
                                            }
                                            IndexedImageFormat.RAW -> if (isBuild) {
                                                rawIndexStore.build(operationTarget.path)
                                            } else {
                                                check(rawIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the RAW index"
                                                }
                                            }
                                            IndexedImageFormat.HEIF -> if (isBuild) {
                                                heifIndexStore.build(operationTarget.path)
                                            } else {
                                                check(heifIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the HEIF/AVIF index"
                                                }
                                            }
                                            IndexedImageFormat.BMP -> if (isBuild) {
                                                bmpIndexStore.build(operationTarget.path)
                                            } else {
                                                check(bmpIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the BMP activation"
                                                }
                                            }
                                            IndexedImageFormat.JXL -> if (isBuild) {
                                                jxlIndexStore.build(operationTarget.path)
                                            } else {
                                                check(jxlIndexStore.delete(operationTarget.path)) {
                                                    "Unable to delete the JPEG XL index"
                                                }
                                            }
                                        }
                                        "$formatName index ${if (isBuild) "built" else "deleted"}"
                                    }
                                }
                                if (currentIndexTarget == operationTarget) {
                                    imageIndexBusy = false
                                    val indexReadyAfterOperation = withContext(Dispatchers.IO) {
                                        when (operationTarget.format) {
                                            IndexedImageFormat.JPEG ->
                                                jpegIndexStore.status(operationTarget.path) is IndexedJpegStatus.Ready
                                            IndexedImageFormat.PNG ->
                                                pngIndexStore.status(operationTarget.path) is IndexedPngStatus.Ready
                                            IndexedImageFormat.TIFF ->
                                                tiffIndexStore.status(operationTarget.path) is IndexedTiffStatus.Ready
                                            IndexedImageFormat.WEBP ->
                                                webpIndexStore.status(operationTarget.path) is IndexedWebpStatus.Ready
                                            IndexedImageFormat.RAW ->
                                                rawIndexStore.status(operationTarget.path) is IndexedRawStatus.Ready
                                            IndexedImageFormat.HEIF ->
                                                heifIndexStore.status(operationTarget.path) is IndexedHeifStatus.Ready
                                            IndexedImageFormat.BMP ->
                                                bmpIndexStore.status(operationTarget.path) is IndexedBmpStatus.Ready
                                            IndexedImageFormat.JXL ->
                                                jxlIndexStore.status(operationTarget.path) is IndexedJxlStatus.Ready
                                        }
                                    }
                                    imageIndexReady = indexReadyAfterOperation
                                    if (isBuild && result.isSuccess && indexReadyAfterOperation) {
                                        IndexedImageFormatMemory.put(
                                            context.applicationContext,
                                            currentMediaCacheKey,
                                            operationTarget.format,
                                        )
                                    }
                                }
                                android.widget.Toast.makeText(
                                    context,
                                    result.getOrElse { it.message ?: "$formatName index operation failed" },
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    ) {
                        Text(if (isBuild) "Build index" else "Delete index")
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !imageIndexBusy,
                        onClick = { imageIndexAction = null },
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }

        unsupportedIndexFormat?.let { formatName ->
            AlertDialog(
                onDismissRequest = { unsupportedIndexFormat = null },
                title = { Text("$formatName indexing is not supported") },
                text = {
                    Text(
                        "This file is named .${currentMedia?.path?.let(::File)?.extension ?: ""}, " +
                            "but its actual content is $formatName. It can still be viewed normally, " +
                            "but a zoom index cannot be built for this format yet.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { unsupportedIndexFormat = null }) { Text("Got it") }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoBottomSheet(
    media: MediaEntry,
    viewModel: PhotosViewModel,
    onDismiss: () -> Unit
) {
    val metadata = remember(media.path) { viewModel.getMediaMetadata(media.path) }
    val coords = remember(media.path) { viewModel.getCoordinates(media.path) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Details",
                style = EmphasizedTypography.TitleLarge,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            InfoRow(Icons.Outlined.Image, media.path.substringAfterLast("/"), "${media.width} x ${media.height} • ${media.sizeBytes / 1024} KB")
            InfoRow(Icons.Outlined.Folder, "Storage Path", media.path)
            // Sharing apps can strip EXIF while MediaStore still retains DATE_TAKEN.
            // Fall back through the timestamps already collected for this item.
            val dateTaken = metadata["Date Taken"]
                ?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
                ?: media.sourceDateTakenMillis
                    ?.takeIf { it > 0L }
                    ?.let(::formatPhotoDateTime)
                ?: media.bestTimestamp
                    .takeIf { it > 0L }
                    ?.let(::formatPhotoDateTime)
                ?: "Unknown"
            InfoRow(Icons.Outlined.CalendarToday, "Date Taken", dateTaken)

            if (metadata["Model"] != "Unknown") {
                Spacer(Modifier.height(24.dp))
                Text("Camera Info", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                InfoRow(Icons.Outlined.CameraAlt, "${metadata["Make"]} ${metadata["Model"]}", "${metadata["Aperture"]} • ${metadata["Exposure Time"]} • ISO ${metadata["ISO"]}")
            }

            if (coords != null) {
                Spacer(Modifier.height(24.dp))
                Text("Location", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val context = LocalContext.current
                    AndroidView(
                        factory = { ctx ->
                            org.osmdroid.views.MapView(ctx).apply {
                                setTileSource(MapnikHttps)
                                setMultiTouchControls(true)
                                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                                controller.setZoom(15.0)
                                val point = org.osmdroid.util.GeoPoint(coords.first, coords.second)
                                controller.setCenter(point)
                                
                                val marker = org.osmdroid.views.overlay.Marker(this)
                                marker.position = point
                                marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                                overlays.add(marker)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun VideoPlayer(
    uri: String, 
    filePath: String = "",
    fallbackDurationMillis: Long = 0L,
    firstFrameModel: Any? = null,
    modifier: Modifier = Modifier,
    isMotionPhoto: Boolean = false,
    isActive: Boolean = true,
    shouldPrepare: Boolean = isActive,
    showUI: Boolean = true,
    onTap: () -> Unit = {}
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var useNativeFallback by remember(uri) { mutableStateOf(false) }
    var nativeVideoView by remember { mutableStateOf<android.widget.VideoView?>(null) }
    var hasRenderedFirstFrame by remember(uri) { mutableStateOf(false) }
    var hasPresentedVideoSurface by remember(uri) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val minUserZoom = 0.333f
    val maxUserZoom = 10f
    val zoomSpec = remember {
        val spec = ZoomSpec(
            maxZoomFactor = maxUserZoom,
            preventOverOrUnderZoom = true
        )
        try {
            val zoomRangeClass = Class.forName("me.saket.telephoto.zoomable.ZoomRange")
            val customRange = zoomRangeClass
                .getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(minUserZoom, maxUserZoom)
            val rangeField = ZoomSpec::class.java.declaredFields.firstOrNull {
                it.name == "range" || it.type.name.contains("ZoomRange")
            }
            rangeField?.apply { isAccessible = true }?.set(spec, customRange)
        } catch (e: Exception) {
            android.util.Log.e("GalleryCompose", "Failed to patch VideoPlayer ZoomSpec range: $e")
        }
        spec
    }

    val zoomableState = key(uri) {
        rememberZoomableState(
            zoomSpec = zoomSpec,
            autoApplyTransformations = false
        )
    }

    DisposableEffect(shouldPrepare, uri, filePath) {
        hasRenderedFirstFrame = false
        hasPresentedVideoSurface = false
        val player = if (shouldPrepare) {
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)

            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setMp4ExtractorFlags(
                    androidx.media3.extractor.mp4.Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA
                )

            val primaryUri = Uri.parse(uri)
            val fallbackUri = try {
                val file = java.io.File(filePath)
                if (filePath.isNotEmpty() && file.exists() && file.canRead()) Uri.fromFile(file) else null
            } catch (e: Exception) {
                null
            }

            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, extractorsFactory)

            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(primaryUri))
                    repeatMode = if (isMotionPhoto) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            hasRenderedFirstFrame = true
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            hasRenderedFirstFrame = false
                            useNativeFallback = true
                        }
                    })
                    prepare()
                    playWhenReady = isActive
                }
        } else null
        
        exoPlayer = player
        
        onDispose {
            exoPlayer = null
            player?.stop()
            player?.release()
        }
    }

    LaunchedEffect(exoPlayer, nativeVideoView, isActive, useNativeFallback) {
        exoPlayer?.playWhenReady = isActive
        nativeVideoView?.let { view ->
            if (isActive) {
                if (!view.isPlaying) view.start()
            } else if (view.isPlaying) {
                view.pause()
            }
        }
    }

    LaunchedEffect(isActive, hasRenderedFirstFrame) {
        if (isActive && hasRenderedFirstFrame) {
            // Once a settled page has handed off to the real video surface, keep
            // showing that paused surface while it slides out. Switching it back
            // to the retriever-produced poster can cause an immediate color jump.
            hasPresentedVideoSurface = true
        }
    }

    val showVideoSurface = hasRenderedFirstFrame && hasPresentedVideoSurface

    Box(
        modifier = modifier
            .zoomable(
                state = zoomableState,
                onClick = { _ -> onTap() },
                onDoubleClick = DoubleClickToZoomListener { state, centroid ->
                    val userZoom = state.contentTransformation.scaleMetadata.userZoom
                    if (kotlin.math.abs(userZoom - 1f) < 0.05f) {
                        state.zoomTo(zoomFactor = 2f, centroid = centroid)
                    } else {
                        state.resetZoom()
                    }
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val transformation = zoomableState.contentTransformation
                    scaleX = transformation.scale.scaleX
                    scaleY = transformation.scale.scaleY
                    translationX = transformation.offset.x
                    translationY = transformation.offset.y
                    transformOrigin = transformation.transformOrigin
                }
        ) {
            if (firstFrameModel != null && !showVideoSurface) {
                GlideImage(
                    model = firstFrameModel,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    requestBuilderTransform = { request ->
                        request
                            .dontTransform()
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .override(720)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (exoPlayer != null) {
                if (useNativeFallback) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoURI(Uri.parse(uri))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = !isMotionPhoto
                                    hasRenderedFirstFrame = true
                                    if (isActive) mp.start()
                                }
                                setOnErrorListener { _, _, _ -> true }
                                nativeVideoView = this
                            }
                        },
                        update = { view ->
                            nativeVideoView = view
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (showVideoSurface) 1f else 0f)
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            object : PlayerView(ctx) {
                                override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                                    return false
                                }
                            }.apply {
                                setEnableComposeSurfaceSyncWorkaround(true)
                                player = exoPlayer
                                useController = false
                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setKeepScreenOn(true)
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { view ->
                            if (view.player != exoPlayer) {
                                view.player = exoPlayer
                            }
                        },
                        onRelease = { view ->
                            view.player = null
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (showVideoSurface) 1f else 0f)
                    )
                }
            }
        }

        if (!isMotionPhoto) {
            val currentZoom = zoomableState.contentTransformation.takeIf { it.isSpecified }?.scaleMetadata?.userZoom ?: 1f
            VideoControls(
                player = exoPlayer,
                nativeVideoView = if (useNativeFallback) nativeVideoView else null,
                isVisible = showUI,
                currentZoom = currentZoom,
                fallbackDurationMillis = fallbackDurationMillis,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun VideoControls(
    player: Player?,
    nativeVideoView: android.widget.VideoView? = null,
    isVisible: Boolean,
    currentZoom: Float = 1f,
    fallbackDurationMillis: Long = 0L,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(player, nativeVideoView, isDragging, fallbackDurationMillis) {
        while (true) {
            try {
                if (nativeVideoView != null) {
                    isPlaying = nativeVideoView.isPlaying
                    if (!isDragging) {
                        currentPosition = nativeVideoView.currentPosition.toLong().coerceAtLeast(0L)
                    }
                    val nvDuration = nativeVideoView.duration.toLong()
                    duration = if (nvDuration > 0L) nvDuration else fallbackDurationMillis.coerceAtLeast(0L)
                } else if (player != null) {
                    isPlaying = player.isPlaying
                    if (!isDragging) {
                        currentPosition = player.currentPosition.coerceAtLeast(0L)
                    }
                    val pDuration = player.duration
                    duration = if (pDuration > 0L) pDuration else fallbackDurationMillis.coerceAtLeast(0L)
                }
            } catch (e: Exception) {
            }
            delay(300)
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 150)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val pauseButtonScale = minOf(currentZoom, 1.0f)
            IconButton(
                onClick = { 
                    try {
                        if (nativeVideoView != null) {
                            if (nativeVideoView.isPlaying) {
                                nativeVideoView.pause()
                            } else {
                                nativeVideoView.start()
                            }
                        } else if (player != null) {
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                if (player.playbackState == Player.STATE_ENDED) {
                                    player.seekTo(0)
                                } else if (player.playbackState == Player.STATE_IDLE || player.playerError != null) {
                                    player.prepare()
                                }
                                player.play()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VideoPlayerDiag", "[PlayPauseClick] Exception: $e", e)
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        scaleX = pauseButtonScale
                        scaleY = pauseButtonScale
                    }
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isLandscape) 48.dp else 96.dp)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
            ) {
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { 
                        isDragging = true
                        currentPosition = it.toLong()
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        try {
                            if (nativeVideoView != null) {
                                nativeVideoView.seekTo(currentPosition.toInt())
                            } else {
                                player?.seekTo(currentPosition)
                            }
                        } catch (e: Exception) {}
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentPosition),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    Text(
                        text = formatTime(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun formatPhotoDateTime(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

@Composable
fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}
