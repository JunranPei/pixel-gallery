package com.pixel.gallery.ui.viewer.decoders

import android.graphics.Bitmap
import android.os.Build
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.roundToInt

object UltraHdrAwareFitCenter : BitmapTransformation() {
    private const val ID = "com.pixel.gallery.viewer.UltraHdrAwareFitCenter.v1"
    private val idBytes = ID.toByteArray(StandardCharsets.UTF_8)

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !toTransform.hasGainmap()
        ) {
            return TransformationUtils.fitCenter(pool, toTransform, outWidth, outHeight)
        }

        val scale = minOf(
            outWidth.toFloat() / toTransform.width.coerceAtLeast(1),
            outHeight.toFloat() / toTransform.height.coerceAtLeast(1),
        )
        val targetWidth = (toTransform.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (toTransform.height * scale).roundToInt().coerceAtLeast(1)
        if (targetWidth == toTransform.width && targetHeight == toTransform.height) {
            return toTransform
        }
        return Bitmap.createScaledBitmap(toTransform, targetWidth, targetHeight, true).apply {
            density = toTransform.density
        }
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(idBytes)
    }

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = ID.hashCode()
}