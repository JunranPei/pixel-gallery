package com.pixel.gallery.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import com.pixel.gallery.utils.BitmapUtils.applyExifOrientation
import io.github.indexedpng.IndexedPngStore
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference

/**
 * A fit-screen preview backed only by an already-created PNG index.
 *
 * A missing or invalid index deliberately fails so the Glide request can fall back to the
 * ordinary source model. Merely displaying an image never creates an index.
 */
data class IndexedPngScreenPreview(
    val sourcePath: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val dateModifiedMillis: Long,
)

internal class IndexedPngScreenPreviewLoader(
    private val context: Context,
) : ModelLoader<IndexedPngScreenPreview, Bitmap> {
    override fun buildLoadData(
        model: IndexedPngScreenPreview,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Bitmap> {
        val cacheKey = ObjectKey(
            "indexed-png-screen:${model.sourcePath}:${model.dateModifiedMillis}:" +
                "${model.sourceWidth}x${model.sourceHeight}:${model.rotationDegrees}:${width}x$height",
        )
        return ModelLoader.LoadData(
            cacheKey,
            IndexedPngScreenPreviewFetcher(context, model, width, height),
        )
    }

    override fun handles(model: IndexedPngScreenPreview): Boolean = true

    internal class Factory(
        private val context: Context,
    ) : ModelLoaderFactory<IndexedPngScreenPreview, Bitmap> {
        override fun build(multiFactory: MultiModelLoaderFactory) =
            IndexedPngScreenPreviewLoader(context.applicationContext)

        override fun teardown() = Unit
    }
}

private class IndexedPngScreenPreviewFetcher(
    private val context: Context,
    private val model: IndexedPngScreenPreview,
    private val requestedWidth: Int,
    private val requestedHeight: Int,
) : DataFetcher<Bitmap> {
    private val activeMetricsToken = AtomicReference<ViewerLoadMetrics.WorkToken?>()
    @Volatile private var cancelled = false

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        val imageKey = "${model.sourcePath}:${model.dateModifiedMillis}"
        val sampleSize = fitScreenSampleSize(
            sourceWidth = model.sourceWidth,
            sourceHeight = model.sourceHeight,
            rotationDegrees = model.rotationDegrees,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
        )
        val token = ViewerLoadMetrics.workStarted(
            "INDEXED_PNG_SCREEN_PREVIEW",
            imageKey,
            "requested=${requestedWidth}x$requestedHeight source=${model.sourceWidth}x${model.sourceHeight} " +
                "rotation=${model.rotationDegrees} sample=$sampleSize priority=$priority",
        )
        activeMetricsToken.getAndSet(token)?.let {
            ViewerLoadMetrics.workCleared(it, "fetch-replaced")
        }

        try {
            if (cancelled) {
                finishCleared(token, "cancelled-before-open")
                return
            }
            if (!isSdrSrgbStillPng(model.sourcePath)) {
                throw IllegalArgumentException("PNG colour or animation semantics require the source decoder")
            }
            val decoder = IndexedPngStore(context.applicationContext)
                .openDecoder(model.sourcePath)
                ?: throw FileNotFoundException("No ready PNG index")
            val bitmap = decoder.use {
                it.decodeRegion(
                    Rect(0, 0, model.sourceWidth, model.sourceHeight),
                    sampleSize,
                )
            } ?: throw IllegalStateException("PNG index preview decode failed")

            val oriented = applyExifOrientation(
                context,
                bitmap,
                model.rotationDegrees,
                false,
            ) ?: bitmap
            if (cancelled) {
                if (!oriented.isRecycled) oriented.recycle()
                finishCleared(token, "cancelled-after-decode")
                return
            }
            if (activeMetricsToken.compareAndSet(token, null)) {
                ViewerLoadMetrics.workReady(
                    token,
                    source = "PNG_INDEX",
                    detail = "sample=$sampleSize bitmap=${oriented.width}x${oriented.height} " +
                        "bytes=${oriented.allocationByteCount}",
                )
            }
            callback.onDataReady(oriented)
        } catch (error: Exception) {
            if (activeMetricsToken.compareAndSet(token, null)) {
                ViewerLoadMetrics.workFailed(token, error.javaClass.simpleName)
            }
            if (!cancelled) callback.onLoadFailed(error)
        }
    }

    private fun finishCleared(token: ViewerLoadMetrics.WorkToken, reason: String) {
        if (activeMetricsToken.compareAndSet(token, null)) {
            ViewerLoadMetrics.workCleared(token, reason)
        }
    }

    override fun cleanup() {
        activeMetricsToken.getAndSet(null)?.let {
            ViewerLoadMetrics.workCleared(it, "datafetcher-cleanup")
        }
    }

    override fun cancel() {
        cancelled = true
        activeMetricsToken.getAndSet(null)?.let {
            ViewerLoadMetrics.workCleared(it, "datafetcher-cancel")
        }
    }

    override fun getDataClass(): Class<Bitmap> = Bitmap::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}

/**
 * Version 1 of the PNG pyramid stores premultiplied RGBA8 pixels but no colour-profile or APNG
 * metadata. Keep the indexed fit preview on inputs whose display interpretation is unambiguous;
 * other PNGs fall back to Glide's source decoder. SSIV's existing region policy is unchanged.
 */
internal fun isSdrSrgbStillPng(sourcePath: String): Boolean = try {
    RandomAccessFile(sourcePath, "r").use { input ->
        if (input.length() < 20L) return@use false
        input.seek(8L)
        var hasSrgbChunk = false
        var hasGammaOrChromaticities = false
        while (input.filePointer + 12L <= input.length()) {
            val chunkBytes = input.readInt().toLong() and 0xffff_ffffL
            val typeBytes = ByteArray(4)
            input.readFully(typeBytes)
            val type = typeBytes.toString(Charsets.US_ASCII)
            val remaining = input.length() - input.filePointer
            if (chunkBytes > remaining - 4L) return@use false
            when (type) {
                "acTL", "iCCP", "cICP", "mDCV", "cLLI" -> return@use false
                "sRGB" -> hasSrgbChunk = true
                "gAMA", "cHRM" -> hasGammaOrChromaticities = true
                "IDAT" -> return@use hasSrgbChunk || !hasGammaOrChromaticities
                "IEND" -> return@use false
            }
            input.seek(input.filePointer + chunkBytes + 4L)
        }
        false
    }
} catch (_: Exception) {
    false
}

private fun fitScreenSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    rotationDegrees: Int,
    requestedWidth: Int,
    requestedHeight: Int,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    val targetWidth = requestedWidth
        .takeIf { it > 0 && it != com.bumptech.glide.request.target.Target.SIZE_ORIGINAL }
        ?: 1080
    val targetHeight = requestedHeight
        .takeIf { it > 0 && it != com.bumptech.glide.request.target.Target.SIZE_ORIGINAL }
        ?: 1920
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val swapped = normalizedRotation == 90 || normalizedRotation == 270
    val orientedWidth = if (swapped) sourceHeight else sourceWidth
    val orientedHeight = if (swapped) sourceWidth else sourceHeight
    val sourceAspect = orientedWidth.toFloat() / orientedHeight.coerceAtLeast(1)
    val targetAspect = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
    val visibleWidth: Float
    val visibleHeight: Float
    if (sourceAspect > targetAspect) {
        visibleWidth = targetWidth.toFloat()
        visibleHeight = visibleWidth / sourceAspect
    } else {
        visibleHeight = targetHeight.toFloat()
        visibleWidth = visibleHeight * sourceAspect
    }

    var sample = 1
    while (
        orientedWidth / (sample * 2f) >= visibleWidth &&
        orientedHeight / (sample * 2f) >= visibleHeight
    ) {
        sample *= 2
    }
    return sample
}
