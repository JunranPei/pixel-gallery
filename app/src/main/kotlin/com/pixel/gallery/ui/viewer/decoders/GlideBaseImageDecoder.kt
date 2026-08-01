package com.pixel.gallery.ui.viewer.decoders

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.davemorrissey.labs.subscaleview.ImageDecoder
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics
import com.pixel.gallery.ui.viewer.withViewerTaskCompression
import java.io.File

class GlideBaseImageDecoder(private val dateModifiedMillis: Long) : ImageDecoder {
    override fun decode(context: Context, uri: Uri): Bitmap {
        val imageKey = "${uri}:$dateModifiedMillis"
        val sessionId = ViewerLoadMetrics.currentSessionId(imageKey)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val token = ViewerLoadMetrics.workStarted(
            "SSIV_BASE_GLIDE_DECODE",
            imageKey,
            "uriScheme=${uri.scheme} requested=SIZE_ORIGINAL",
        )
        val options = RequestOptions()
            .withViewerTaskCompression()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .fitCenter()
            .let { opts ->
                if (dateModifiedMillis > 0L) opts.signature(ObjectKey(dateModifiedMillis)) else opts
            }

        val model: Any = if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: uri.toString()
            val file = File(path)
            if (file.exists()) file else uri
        } else {
            uri
        }

        val builder = Glide.with(context)
            .asBitmap()
            .load(model)
            .apply(options)
            .into(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)

        return try {
            builder.get().also { bitmap ->
                ViewerLoadMetrics.baseImageDecoded(
                    imageKey = imageKey,
                    sessionId = sessionId,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L,
                    outputPixels = bitmap.width.toLong() * bitmap.height.toLong(),
                    allocationBytes = bitmap.allocationByteCount.toLong(),
                )
                ViewerLoadMetrics.workReady(
                    token,
                    source = "GLIDE_BLOCKING_GET",
                    detail = "bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config} " +
                        "allocation=${bitmap.allocationByteCount}",
                )
            }
        } catch (error: Exception) {
            ViewerLoadMetrics.workFailed(token, error.javaClass.simpleName)
            throw error
        }
    }
}
