package com.pixel.gallery.ui.viewer.formats

import android.os.Build
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.utils.MimeTypes

enum class ViewerRegionDecoderKind {
    PLATFORM,
    TIFF,
    SVG,
    RAW_EMBEDDED,
    BMP,
    JXL,
}

enum class ViewerPreviewKind {
    DEFAULT,
    TIFF,
    SVG,
}

sealed interface ViewerRenderPlan {
    data class Tiled(
        val regionDecoderKind: ViewerRegionDecoderKind,
        val previewKind: ViewerPreviewKind,
    ) : ViewerRenderPlan

    data object PreviewOnly : ViewerRenderPlan
    data object RawEmbeddedPreview : ViewerRenderPlan
    data object IndexedBmp : ViewerRenderPlan
    data object IndexedJxl : ViewerRenderPlan
}

private interface ViewerFormatAdapter {
    fun resolve(media: MediaEntry, normalizedMime: String): ViewerRenderPlan?
}

/**
 * Keeps format-specific decisions out of ViewerScreen. MIME and extensions are only
 * routing hints here; each decoder remains responsible for validating its input header.
 */
object ViewerFormatRegistry {
    private val adapters = listOf(
        SvgAdapter,
        TiffAdapter,
        BmpAdapter,
        JxlAdapter,
        NativeRegionAdapter,
        RawAdapter,
    )

    fun resolve(media: MediaEntry): ViewerRenderPlan {
        val mime = media.sourceMimeType.substringBefore(';').trim().lowercase()
        return adapters.firstNotNullOfOrNull { it.resolve(media, mime) }
            ?: ViewerRenderPlan.PreviewOnly
    }

    private object SvgAdapter : ViewerFormatAdapter {
        override fun resolve(media: MediaEntry, normalizedMime: String): ViewerRenderPlan? {
            val matches = normalizedMime == MimeTypes.SVG || media.path.endsWith(".svg", true)
            return if (matches) {
                ViewerRenderPlan.Tiled(ViewerRegionDecoderKind.SVG, ViewerPreviewKind.SVG)
            } else null
        }
    }

    private object TiffAdapter : ViewerFormatAdapter {
        override fun resolve(media: MediaEntry, normalizedMime: String): ViewerRenderPlan? {
            val matches = normalizedMime == MimeTypes.TIFF ||
                media.path.endsWith(".tif", true) || media.path.endsWith(".tiff", true)
            return if (matches) {
                ViewerRenderPlan.Tiled(ViewerRegionDecoderKind.TIFF, ViewerPreviewKind.TIFF)
            } else null
        }
    }

    private object BmpAdapter : ViewerFormatAdapter {
        override fun resolve(media: MediaEntry, normalizedMime: String): ViewerRenderPlan? {
            val matches = normalizedMime == MimeTypes.BMP || media.path.endsWith(".bmp", true)
            return if (matches) ViewerRenderPlan.IndexedBmp else null
        }
    }

    private object JxlAdapter : ViewerFormatAdapter {
        override fun resolve(media: MediaEntry, normalizedMime: String): ViewerRenderPlan? {
            val matches = normalizedMime == "image/jxl" || media.path.endsWith(".jxl", true)
            return if (matches) ViewerRenderPlan.IndexedJxl else null
        }
    }

    private object NativeRegionAdapter : ViewerFormatAdapter {
        override fun resolve(media: MediaEntry, normalizedMime: String): ViewerRenderPlan? {
            val path = media.path
            val baseSupported = normalizedMime == MimeTypes.JPEG || normalizedMime == MimeTypes.PNG ||
                normalizedMime == MimeTypes.WEBP || normalizedMime == MimeTypes.HEIC ||
                normalizedMime == MimeTypes.HEIF || path.endsWith(".jpg", true) ||
                path.endsWith(".jpeg", true) || path.endsWith(".png", true) ||
                path.endsWith(".webp", true) || path.endsWith(".heic", true) ||
                path.endsWith(".heif", true)
            val avifSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (normalizedMime == "image/avif" || path.endsWith(".avif", true))
            return if (baseSupported || avifSupported) {
                ViewerRenderPlan.Tiled(ViewerRegionDecoderKind.PLATFORM, ViewerPreviewKind.DEFAULT)
            } else null
        }
    }

    private object RawAdapter : ViewerFormatAdapter {
        override fun resolve(media: MediaEntry, normalizedMime: String): ViewerRenderPlan? {
            // Proprietary RAW needs a large embedded preview or an explicit develop step.
            // Never silently demosaic the complete sensor image on viewer entry.
            return if (MimeTypes.isRaw(normalizedMime)) ViewerRenderPlan.RawEmbeddedPreview else null
        }
    }
}
