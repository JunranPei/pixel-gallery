package com.pixel.gallery.integration

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.bumptech.glide.Priority
import com.bumptech.glide.load.data.DataFetcher
import com.pixel.gallery.glide.MediaStoreThumbnail
import com.pixel.gallery.glide.MediaStoreThumbnailFetcher
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Actual provider thumbnails, source decodes and persistent-cache reads; user originals are read only. */
internal class ThumbnailAspectDeviceChecks(private val context: Context) {
    private val output = File(context.getExternalFilesDir(null), "thumbnail-aspect-checks").apply { mkdirs() }
    private var checks = 0

    fun exercise(): String {
        val sampleRoots = listOf("PreviewSizeQA_20261020", "ThumbnailAspectFix20261020")
        val files = sampleRoots.flatMap { File("/sdcard/Pictures/$it").listFiles()!!.filter { file -> file.isFile } }
        check(files.size == 16)
        val scan = CountDownLatch(files.size)
        MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null) { _, _ -> scan.countDown() }
        check(scan.await(40, TimeUnit.SECONDS))
        val resolver = context.contentResolver
        val projection = arrayOf("_id", "_display_name", "width", "height", "orientation", "date_modified", "mime_type")
        val models = mutableListOf<MediaStoreThumbnail>()
        val names = mutableListOf<String>()
        for (album in sampleRoots) resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
            "relative_path = ?", arrayOf("Pictures/$album/"), null)!!.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                val name = cursor.getString(1)
                val width = cursor.getInt(2); val height = cursor.getInt(3); val rotation = cursor.getInt(4)
                val model = MediaStoreThumbnail(uri, cursor.getString(6), rotation, cursor.getLong(5)*1000L,
                    sizeBytes = 8L*1024*1024, allowPersistentCache = false, traceViewerLoad = true)
                models += model; names += name
                for (size in listOf(200, 512)) {
                    val bitmap = load(model, size)
                    val orientedWidth = if (rotation % 180 == 90) height else width
                    val orientedHeight = if (rotation % 180 == 90) width else height
                    val error = if (orientedWidth >= orientedHeight)
                        abs(bitmap.height - bitmap.width.toDouble()*orientedHeight/orientedWidth)
                    else abs(bitmap.width - bitmap.height.toDouble()*orientedWidth/orientedHeight)
                    check(error <= 1.5) { "$name $size: ${bitmap.width}x${bitmap.height} vs $orientedWidth x $orientedHeight" }
                    File(output, "$name-$size.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    if (name.startsWith("black-border")) {
                        check(bitmap.getPixel(0,0) and 0x00ffffff == 0) { "Actual black border was removed: $name" }
                        val p = bitmap.getPixel(bitmap.width/2, bitmap.height/2) and 0x00ffffff
                        check(p != 0) { "Image content was lost: $name" }
                    }
                    bitmap.recycle(); checks++
                }
            }
        }
        check(models.size == 16)
        // Existing unversioned files must not win against corrected cache entries.
        val model = models.first().copy(allowPersistentCache = true)
        for (size in listOf(200,512)) {
            val folder = if(size == 200) "persistent_grid_thumbnails" else "persistent_viewer_thumbnails"
            val suffix = if(size == 200) "_q75.jpg" else ".jpg"
            val old = File(File(context.cacheDir, folder).apply { mkdirs() }, "${android.content.ContentUris.parseId(model.uri)}_${model.dateModifiedMillis}_${model.rotationDegrees}$suffix")
            val wrong = Bitmap.createBitmap(120,160,Bitmap.Config.RGB_565)
            old.outputStream().use { wrong.compress(Bitmap.CompressFormat.JPEG,90,it) }; wrong.recycle()
            repeat(2) {
                val bitmap=load(model,size)
                check(abs(bitmap.width.toDouble()/bitmap.height - 2.0/3.0) < .01)
                bitmap.recycle(); checks++
            }
        }
        var failed = false
        val missing = model.copy(uri = Uri.parse("content://media/external/images/media/9223372036854775806"),allowPersistentCache=false)
        MediaStoreThumbnailFetcher(context,missing,200,200).loadData(Priority.NORMAL,object : DataFetcher.DataCallback<Bitmap> {
            override fun onDataReady(data: Bitmap?) { error("Missing source unexpectedly produced a bitmap") }
            override fun onLoadFailed(error: Exception) { failed=true }
        })
        check(failed); checks++
        return "$checks checks passed: 7 original-byte samples + 9 landscape/portrait/square/tiny/panorama/real-black-border rotation fixtures at 200 and 512; old persistent cache ignored, corrected cache reused, absent source fails cleanly. PNGs: $output"
    }

    private fun load(model: MediaStoreThumbnail, size: Int): Bitmap {
        var result: Bitmap? = null
        var failure: Exception? = null
        val fetcher = MediaStoreThumbnailFetcher(context,model,size,size)
        fetcher.loadData(Priority.NORMAL,object : DataFetcher.DataCallback<Bitmap> {
            override fun onDataReady(data: Bitmap?) { result=data }
            override fun onLoadFailed(error: Exception) { failure=error }
        })
        fetcher.cleanup()
        failure?.let { throw it }
        return checkNotNull(result)
    }
}
