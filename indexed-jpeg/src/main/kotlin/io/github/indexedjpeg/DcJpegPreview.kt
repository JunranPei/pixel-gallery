package io.github.indexedjpeg

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental on-demand JPEG block-mean preview. No index or disk cache.
 * Null means unsupported, invalid, unreadable or cancelled: use the existing
 * decoder unless cancelled. EXIF orientation and ICC transforms are not applied.
 * Intended for the isolated viewer experiment, not a general JPEG replacement.
 */
object DcJpegPreview {
    init { System.loadLibrary("pixel-jpeg-preview") }

    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        minimumLongEdge: Int = 512,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): Bitmap? {
        require(minimumLongEdge > 0)
        if (cancelled.get()) return null
        return try {
            resolver.openFileDescriptor(uri, "r")?.use {
                decodeNative(it.fd, minimumLongEdge, cancelled)?.let { bitmap ->
                    if (cancelled.get()) { bitmap.recycle(); null } else bitmap
                }
            }
        } catch (_: java.io.IOException) { null }
        catch (_: SecurityException) { null }
    }

    private external fun decodeNative(fd: Int, minimumLongEdge: Int, cancelled: AtomicBoolean): Bitmap?

    /** Adaptive standard scanlines (1/8, 1/4, 1/2, full) covering the actual fit.
     * Color-managed non-sRGB input must use the existing platform decoder.
     * Returned bitmap is encoded orientation, not yet resized to the viewport.
     */
    fun decodeFit(
        resolver: ContentResolver,
        uri: Uri,
        width: Int,
        height: Int,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): Bitmap? {
        require(width > 0 && height > 0)
        if (cancelled.get()) return null
        return try {
            resolver.openFileDescriptor(uri, "r")?.use {
                // Reject pipes before asking a header decoder to read/block on them.
                if (!OsConstants.S_ISREG(Os.fstat(it.fileDescriptor).st_mode)) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds)
                if (cancelled.get() || bounds.outMimeType != "image/jpeg" ||
                    bounds.outColorSpace?.isSrgb != true) return null
                decodeFitNative(it.fd, width, height, cancelled)?.let { bitmap ->
                    if (cancelled.get()) { bitmap.recycle(); null } else bitmap
                }
            }
        } catch (_: java.io.IOException) { null }
        catch (_: SecurityException) { null }
        catch (_: android.system.ErrnoException) { null }
    }

    private external fun decodeFitNative(fd: Int, width: Int, height: Int, cancelled: AtomicBoolean): Bitmap?

    /** Same-thread diagnostic only; header/alloc/setup/scan/finish ns, sample, fast entropy. */
    fun lastFitTimings(): LongArray = lastFitTimingsNative()
    private external fun lastFitTimingsNative(): LongArray
}
