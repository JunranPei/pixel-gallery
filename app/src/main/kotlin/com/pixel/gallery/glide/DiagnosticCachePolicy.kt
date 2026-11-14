package com.pixel.gallery.glide

import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.pixel.gallery.BuildConfig

/** Compile-time policy: settings cannot re-enable image caches in the diagnostic APK. */
internal object DiagnosticCachePolicy {
    const val disabled = BuildConfig.IMAGE_LOAD_NO_CACHE
    fun disk(normal: DiskCacheStrategy): DiskCacheStrategy =
        if (disabled) DiskCacheStrategy.NONE else normal
}
