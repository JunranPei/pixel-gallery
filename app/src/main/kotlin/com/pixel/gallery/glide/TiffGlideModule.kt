package com.pixel.gallery.glide

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.LibraryGlideModule
import com.bumptech.glide.signature.ObjectKey
import org.beyka.tiffbitmapfactory.TiffBitmapFactory
import java.io.File

@GlideModule
class TiffGlideModule : LibraryGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.append(TiffImage::class.java, Bitmap::class.java, TiffLoader.Factory())
    }
}

class TiffImage(val context: Context, val uri: Uri)

private class TiffLoader : ModelLoader<TiffImage, Bitmap> {
    override fun buildLoadData(model: TiffImage, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(ObjectKey(model.uri), TiffFetcher(model, width, height))

    override fun handles(model: TiffImage) = true

    class Factory : ModelLoaderFactory<TiffImage, Bitmap> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<TiffImage, Bitmap> = TiffLoader()
        override fun teardown() = Unit
    }
}

private class TiffFetcher(
    private val model: TiffImage,
    private val requestedWidth: Int,
    private val requestedHeight: Int,
) : DataFetcher<Bitmap> {
    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Bitmap>) {
        try {
            openTiffDescriptor(model.context, model.uri)?.use { pfd ->
                val bounds = TiffBitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    inUseOrientationTag = false
                    inThrowException = true
                }
                TiffBitmapFactory.decodeFileDescriptor(pfd.fd, bounds)
                val targetWidth = requestedWidth.takeIf { it > 0 } ?: bounds.outWidth
                val targetHeight = requestedHeight.takeIf { it > 0 } ?: bounds.outHeight
                val options = TiffBitmapFactory.Options().apply {
                    inUseOrientationTag = false
                    inThrowException = true
                    inSampleSize = calculateTiffSample(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
                    inPreferredConfig = TiffBitmapFactory.ImageConfig.ARGB_8888
                }
                val bitmap = TiffBitmapFactory.decodeFileDescriptor(pfd.fd, options)
                    ?: throw IllegalStateException("TIFF preview decode returned null")
                callback.onDataReady(bitmap)
                return
            }
            callback.onLoadFailed(IllegalArgumentException("Unable to open TIFF uri=${model.uri}"))
        } catch (error: Exception) {
            callback.onLoadFailed(error)
        }
    }

    override fun cleanup() = Unit
    override fun cancel() = Unit
    override fun getDataClass(): Class<Bitmap> = Bitmap::class.java
    override fun getDataSource(): DataSource = DataSource.LOCAL
}

private fun calculateTiffSample(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    var sample = 1
    while (srcWidth / (sample * 2) >= targetWidth && srcHeight / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}

private fun openTiffDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
    return if (uri.scheme == null || uri.scheme == "file") {
        ParcelFileDescriptor.open(File(uri.path ?: uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
        context.contentResolver.openFileDescriptor(uri, "r")
    }
}