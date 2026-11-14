package com.pixel.gallery.glide

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.pixel.gallery.ui.viewer.ViewerLoadMetrics

/** Includes requests from Grid, intermediate previews and error fallbacks. */
internal class DiagnosticImageRequestListener : RequestListener<Any> {
    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Any>, isFirstResource: Boolean): Boolean {
        ViewerLoadMetrics.event("DIAGNOSTIC_IMAGE_FAILED",
            "model=${model?.javaClass?.simpleName} error=${e?.rootCauses?.firstOrNull()?.javaClass?.simpleName}",
            imageKey = model.toString())
        return false
    }

    override fun onResourceReady(resource: Any, model: Any, target: Target<Any>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
        val bitmap = (resource as? Bitmap) ?: (resource as? BitmapDrawable)?.bitmap
        val cacheHit = dataSource == DataSource.MEMORY_CACHE || dataSource == DataSource.DATA_DISK_CACHE ||
            dataSource == DataSource.RESOURCE_DISK_CACHE
        ViewerLoadMetrics.event(if (cacheHit) "NO_CACHE_POLICY_VIOLATION" else "DIAGNOSTIC_IMAGE_READY",
            "source=$dataSource model=${model.javaClass.simpleName} " +
                "bitmap=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} config=${bitmap?.config} " +
                "bytes=${bitmap?.allocationByteCount ?: 0} first=$isFirstResource",
            imageKey = model.toString())
        return false
    }
}
