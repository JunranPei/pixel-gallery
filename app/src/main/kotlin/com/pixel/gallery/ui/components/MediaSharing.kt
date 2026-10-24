package com.pixel.gallery.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.File

/** Shares normal and Locked Folder files through the app's configured FileProvider. */
fun shareMedia(context: Context, entries: List<MediaEntry>, chooserTitle: String = "Share Media") {
    val validEntries = entries.filter { File(it.path).isFile }
    if (validEntries.isEmpty()) return

    val authority = "${context.packageName}.fileprovider"
    val uris = validEntries.map { entry ->
        FileProvider.getUriForFile(context, authority, File(entry.path))
    }
    val mimeTypes = validEntries.map { it.sourceMimeType }.distinct()
    val mimeType = mimeTypes.singleOrNull() ?: "*/*"
    val multiple = uris.size > 1
    val intent = Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        type = mimeType
        if (multiple) {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        } else {
            putExtra(Intent.EXTRA_STREAM, uris.single())
        }
        clipData = ClipData.newUri(context.contentResolver, "Shared media", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
