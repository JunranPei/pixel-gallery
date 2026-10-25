package com.pixel.gallery.ui.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PointF
import android.view.Surface
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pixel.gallery.data.repository.SavedViewerZoom
import com.pixel.gallery.data.repository.VideoSettingsRepository
import com.pixel.gallery.data.repository.ViewerScreenDirection

internal fun ViewerScreenDirection.requestedOrientation(): Int = when (this) {
    ViewerScreenDirection.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    ViewerScreenDirection.LOCK_CURRENT -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
    ViewerScreenDirection.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    ViewerScreenDirection.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

@Suppress("DEPRECATION")
internal fun Activity.visibleViewerOrientation(): Int {
    val rotation = windowManager.defaultDisplay.rotation
    val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return if (landscape) {
        if (rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    } else {
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_180) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

internal fun rememberedImageTransforms(repository: VideoSettingsRepository, previous: ViewerTransformStateStore) =
    ViewerTransformStateStore(
        readSaved = { key ->
            repository.zoom(false, key)?.takeIf { it.sourceWidth > 0 && it.sourceHeight > 0 && it.baseFitScale > 0 }?.let {
                SubsamplingScaleImageView.ViewState(it.scale, it.baseFitScale, PointF(it.centerX, it.centerY),
                    it.sourceWidth, it.sourceHeight, 0.0)
            } ?: previous.get(key)
        },
        writeSaved = { key, state ->
            repository.saveZoom(false, key, SavedViewerZoom(state.scale, state.sourceCenter.x, state.sourceCenter.y,
                state.baseFitScale, state.sourceWidth, state.sourceHeight))
        },
    )
