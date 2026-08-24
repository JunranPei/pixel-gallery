package com.pixel.gallery.ui.viewer

import com.bumptech.glide.request.BaseRequestOptions

/**
 * Keeps Viewer requests on Glide's configured source executor.
 *
 * The previous implementation selected Glide's unlimited source-generator pool. That
 * bypassed the user's image-decoding thread limit and allowed neighbouring previews to
 * expand into concurrent source decodes, which is the opposite of power compression.
 * Disk-cache work is already serialized by the app's Glide module, while source work now
 * once again observes the configured 1..8 thread budget.
 */
internal fun <T : BaseRequestOptions<T>> T.withViewerTaskCompression(): T = this
