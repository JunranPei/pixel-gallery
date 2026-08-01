package com.pixel.gallery.ui.viewer.decoders

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Gainmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one small, page-scoped copy of the Ultra HDR enhancement plane. The SDR base
 * continues to use the existing region decoder and disk cache; only visible tiles get
 * a matching gainmap crop attached in memory.
 */
object UltraHdrTileSupport {
    private val snapshots = ConcurrentHashMap<String, GainmapSnapshot>()

    fun capture(imageKey: String, drawable: Drawable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap == null || !bitmap.hasGainmap()) {
            val removed = snapshots.remove(imageKey)
            ViewerLoadMetrics.event(
                "GAINMAP_CAPTURE_NONE",
                "bitmap=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} " +
                    "removedExisting=${removed != null} snapshots=${snapshots.size}",
                imageKey = imageKey,
            )
            return false
        }
        return captureApi34(imageKey, bitmap)
    }

    fun clear(imageKey: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val removed = snapshots.remove(imageKey)
        ViewerLoadMetrics.event(
            "GAINMAP_SNAPSHOT_CLEAR",
            "removed=${removed != null} bytes=${removed?.contents?.allocationByteCount ?: 0} " +
                "recycled=${removed?.contents?.isRecycled} snapshots=${snapshots.size}",
            imageKey = imageKey,
        )
    }

    fun clearAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val removed = snapshots.size
        val bytes = snapshots.values.sumOf { it.contents.allocationByteCount.toLong() }
        snapshots.clear()
        ViewerLoadMetrics.event(
            "GAINMAP_SNAPSHOT_CLEAR_ALL",
            "removed=$removed bytes=$bytes",
        )
    }

    fun attach(
        imageKey: String,
        baseTile: Bitmap,
        sourceRect: Rect,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return baseTile
        return attachApi34(imageKey, baseTile, sourceRect, sourceWidth, sourceHeight)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun captureApi34(imageKey: String, bitmap: Bitmap): Boolean {
        if (!bitmap.hasGainmap()) return false
        val gainmap = bitmap.gainmap ?: return false
        val source = gainmap.gainmapContents
        val token = ViewerLoadMetrics.workStarted(
            "GAINMAP_BITMAP_COPY",
            imageKey,
            "preview=${bitmap.width}x${bitmap.height} gainmap=${source.width}x${source.height} " +
                "sourceConfig=${source.config} sourceBytes=${source.allocationByteCount}",
        )
        val safeConfig = source.config?.takeUnless { it == Bitmap.Config.HARDWARE }
            ?: Bitmap.Config.ARGB_8888
        val copiedContents = source.copy(safeConfig, false)
        if (copiedContents == null) {
            ViewerLoadMetrics.workFailed(token, "copy-null")
            return false
        }
        val snapshot = GainmapSnapshot(
            contents = copiedContents,
            ratioMin = gainmap.ratioMin,
            ratioMax = gainmap.ratioMax,
            gamma = gainmap.gamma,
            epsilonSdr = gainmap.epsilonSdr,
            epsilonHdr = gainmap.epsilonHdr,
            minDisplayRatioForHdrTransition = gainmap.minDisplayRatioForHdrTransition,
            displayRatioForFullHdr = gainmap.displayRatioForFullHdr,
        )
        val replaced = snapshots.put(imageKey, snapshot)
        ViewerLoadMetrics.workReady(
            token,
            source = "BITMAP_COPY",
            detail = "copy=${copiedContents.width}x${copiedContents.height} " +
                "config=${copiedContents.config} bytes=${copiedContents.allocationByteCount} " +
                "replaced=${replaced != null} replacedBytes=${replaced?.contents?.allocationByteCount ?: 0} " +
                "replacedRecycled=${replaced?.contents?.isRecycled} snapshots=${snapshots.size}",
        )
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun attachApi34(
        imageKey: String,
        baseTile: Bitmap,
        sourceRect: Rect,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap {
        val snapshot = snapshots[imageKey] ?: return baseTile
        if (snapshot.contents.isRecycled || sourceWidth <= 0 || sourceHeight <= 0) {
            return baseTile
        }
        val token = ViewerLoadMetrics.workStarted(
            "GAINMAP_TILE_ATTACH",
            imageKey,
            "base=${baseTile.width}x${baseTile.height} " +
                "rect=${sourceRect.left},${sourceRect.top}-${sourceRect.right},${sourceRect.bottom} " +
                "source=${sourceWidth}x$sourceHeight",
        )

        val gainWidth = snapshot.contents.width
        val gainHeight = snapshot.contents.height
        val left = floor(sourceRect.left.toDouble() * gainWidth / sourceWidth).toInt()
            .coerceIn(0, gainWidth - 1)
        val top = floor(sourceRect.top.toDouble() * gainHeight / sourceHeight).toInt()
            .coerceIn(0, gainHeight - 1)
        val right = ceil(sourceRect.right.toDouble() * gainWidth / sourceWidth).toInt()
            .coerceIn(left + 1, gainWidth)
        val bottom = ceil(sourceRect.bottom.toDouble() * gainHeight / sourceHeight).toInt()
            .coerceIn(top + 1, gainHeight)

        val rawCrop = Bitmap.createBitmap(snapshot.contents, left, top, right - left, bottom - top)
        val cropped = if (rawCrop === snapshot.contents) {
            rawCrop.copy(rawCrop.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            rawCrop
        }
        val naturalScaleX = cropped.width.toFloat() / baseTile.width.coerceAtLeast(1)
        val naturalScaleY = cropped.height.toFloat() / baseTile.height.coerceAtLeast(1)
        val targetScale = min(0.5f, min(naturalScaleX, naturalScaleY)).coerceAtLeast(0.01f)
        val targetWidth = max(1, (baseTile.width * targetScale).toInt())
        val targetHeight = max(1, (baseTile.height * targetScale).toInt())
        val contents = if (cropped.width == targetWidth && cropped.height == targetHeight) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true).also {
                cropped.recycle()
            }
        }

        val tileGainmap = Gainmap(contents).apply {
            setRatioMin(snapshot.ratioMin[0], snapshot.ratioMin[1], snapshot.ratioMin[2])
            setRatioMax(snapshot.ratioMax[0], snapshot.ratioMax[1], snapshot.ratioMax[2])
            setGamma(snapshot.gamma[0], snapshot.gamma[1], snapshot.gamma[2])
            setEpsilonSdr(snapshot.epsilonSdr[0], snapshot.epsilonSdr[1], snapshot.epsilonSdr[2])
            setEpsilonHdr(snapshot.epsilonHdr[0], snapshot.epsilonHdr[1], snapshot.epsilonHdr[2])
            minDisplayRatioForHdrTransition = snapshot.minDisplayRatioForHdrTransition
            displayRatioForFullHdr = snapshot.displayRatioForFullHdr
        }
        baseTile.gainmap = tileGainmap
        ViewerLoadMetrics.workReady(
            token,
            source = "GAINMAP_ATTACHED",
            detail = "crop=${right - left}x${bottom - top} contents=${contents.width}x${contents.height} " +
                "bytes=${contents.allocationByteCount}",
        )
        return baseTile
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private data class GainmapSnapshot(
        val contents: Bitmap,
        val ratioMin: FloatArray,
        val ratioMax: FloatArray,
        val gamma: FloatArray,
        val epsilonSdr: FloatArray,
        val epsilonHdr: FloatArray,
        val minDisplayRatioForHdrTransition: Float,
        val displayRatioForFullHdr: Float,
    )
}
