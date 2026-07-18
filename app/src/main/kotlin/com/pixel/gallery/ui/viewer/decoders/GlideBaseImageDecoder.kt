package com.pixel.gallery.ui.viewer.decoders

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.davemorrissey.labs.subscaleview.ImageDecoder
import java.io.File

class GlideBaseImageDecoder(private val dateModifiedMillis: Long) : ImageDecoder {
    override fun decode(context: Context, uri: Uri): Bitmap {
        val options = RequestOptions()
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

        return builder.get()
    }
}
