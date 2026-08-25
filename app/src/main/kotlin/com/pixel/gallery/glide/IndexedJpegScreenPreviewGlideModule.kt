package com.pixel.gallery.glide

import android.content.Context
import android.graphics.Bitmap
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
import io.github.indexedjpeg.IndexedJpegStore
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicReference

/**
 * A fit-screen preview backed only by the low-frequency layer embedded in a ready JPEG index.
 *
 * Missing and legacy indexes deliberately fail so Glide can fall back to the ordinary JPEG
 * source. Displaying an image never creates or updates an index.
 */
data class IndexedJpegScreenPreview(
    val sourcePath: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
    val dateModifiedMillis: Long,
)

internal class IndexedJpegScreenPreviewLoader(
    private val context: Context,
) : ModelLoader<IndexedJpegScreenPreview, Bitmap> {
    override fun buildLoadData(
        model: IndexedJpegScreenPreview,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<Bitmap> {
        val source = File(model.sourcePath)
        val indexSignature = IndexedJpegStore(context).indexCacheSignature(model.sourcePath)
        val cacheKey = ObjectKey(
            "indexed-jpeg-screen:v5-fit-source:$indexSignature:${model.sourcePath}:${model.dateModifiedMillis}:" +
                "${source.length()}:${source.lastModified()}:" +
                "${model.sourceWidth}x${model.sourceHeight}:${model.rotationDegrees}:${width}x$height",
        )
        return ModelLoader.LoadData(
            cacheKey,
            IndexedJpegScreenPreviewFetcher(context, model, width, height),
        )
    }

    override fun handles(model: IndexedJpegScreenPreview): Boolean = true

    internal class Factory(
        private val context: Context,
    ) : ModelLoaderFactory<IndexedJpegScreenPreview, Bitmap> {
        override fun build(multiFactory: MultiModelLoaderFactory) =
            IndexedJpegScreenPreviewLoader(context.applicationContext)

        override fun teardown() = Unit
    }
}

private class IndexedJpegScreenPreviewFetcher(
    private val context: Context,
    private val model: IndexedJpegScreenPreview,
    private val requestedWidth: Int,
    private val requestedHeight: Int,
) : DataFetcher<Bitmap> {
    private val activeMetricsToken = AtomicReference<ViewerLoadMetrics.WorkToken?>()
    @Volatile private var cancelled = false

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        val imageKey = "${model.sourcePath}:${model.dateModifiedMillis}"
        val token = ViewerLoadMetrics.workStarted(
            "INDEXED_JPEG_SCREEN_PREVIEW",
            imageKey,
            "requested=${requestedWidth}x$requestedHeight source=${model.sourceWidth}x${model.sourceHeight} " +
                "rotation=${model.rotationDegrees} priority=$priority",
        )
        activeMetricsToken.getAndSet(token)?.let {
            ViewerLoadMetrics.workCleared(it, "fetch-replaced")
        }

        try {
            if (cancelled) {
                finishCleared(token, "cancelled-before-open")
                return
            }
            val bitmap = IndexedJpegStore(context.applicationContext).decodeScreenOverview(
                sourcePath = model.sourcePath,
                rotationDegrees = model.rotationDegrees,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            ) ?: throw FileNotFoundException("No covering JPEG index overview")

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
                    source = "JPEG_INDEX_OVERVIEW",
                    detail = "bitmap=${oriented.width}x${oriented.height} bytes=${oriented.allocationByteCount}",
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
