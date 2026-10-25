package com.pixel.gallery.ui.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Copies the rendered video surface, excluding controls, zoom and other application chrome. */
suspend fun captureVideoFrame(view: SurfaceView, width: Int, height: Int): Bitmap {
    require(view.holder.surface.isValid && width > 0 && height > 0)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(view, bitmap, { result ->
                if (result == PixelCopy.SUCCESS && continuation.isActive) continuation.resume(bitmap, onCancellation = { _, value, _ -> value.recycle() })
                else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resumeWithException(IOException("Frame unavailable ($result)"))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (error: Exception) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

suspend fun saveVideoFrame(context: Context, bitmap: Bitmap, tree: String): Uri = withContext(Dispatchers.IO) {
    val name = "Frame_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}_${UUID.randomUUID().toString().take(6)}.png"
    val resolver = context.contentResolver
    var legacyFile: File? = null
    val uri: Uri = if (tree.isNotBlank()) {
        val treeUri = Uri.parse(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        DocumentsContract.createDocument(resolver, parent, "image/png", name)
            ?: throw IOException("Cannot create capture")
    } else {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Video captures")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Video captures")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create capture directory")
                legacyFile = File(directory, name)
                put(MediaStore.Images.Media.DATA, legacyFile!!.absolutePath)
            }
        }
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Cannot create capture")
    }
    try {
        resolver.openOutputStream(uri, "w")?.use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Cannot encode capture")
        } ?: throw IOException("Cannot write capture")
        if (tree.isBlank() && Build.VERSION.SDK_INT >= 29) {
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        }
        uri
    } catch (error: Exception) {
        runCatching {
            if (tree.isNotBlank()) DocumentsContract.deleteDocument(resolver, uri) else resolver.delete(uri, null, null)
        }
        legacyFile?.delete()
        throw error
    }
}
