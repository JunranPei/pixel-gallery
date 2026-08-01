package com.pixel.gallery.ui.viewer

import com.bumptech.glide.request.BaseRequestOptions

/**
 * Routes source work for Viewer-only Glide requests away from Glide's shared source
 * executor. This preserves the short-burst loading behaviour without changing Grid's
 * configured Glide concurrency.
 */
internal fun <T : BaseRequestOptions<T>> T.withViewerTaskCompression(): T =
    useUnlimitedSourceGeneratorsPool(true)
