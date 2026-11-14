package com.pixel.gallery.ui.viewer

import android.animation.ValueAnimator
import android.os.PowerManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixel.gallery.model.ViewerOpeningEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

internal val LocalViewerOpening = staticCompositionLocalOf<ViewerOpeningTransition?> { null }

internal fun openingReadyListener(opening: ViewerOpeningTransition?, key: ViewerTransitionKey?) =
    object : RequestListener<Drawable> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>,
            isFirstResource: Boolean) = false
        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>,
            dataSource: DataSource, isFirstResource: Boolean): Boolean {
            opening?.ready(key)
            return false
        }
    }

/** One finite animation per navigation entry. Only draw/layer lambdas observe the frame state. */
internal class ViewerOpeningTransition(val key: ViewerTransitionKey?, pending: Boolean,
    seed: ViewerOpeningSeed? = null,
    private val initialEffect: ViewerOpeningEffect = ViewerOpeningEffect.SCALE_FADE,
    private val ratio: Float = 1f) {
    var isRunning by mutableStateOf(pending)
        private set
    var visual by mutableStateOf(if (pending) ViewerDismissVisual(imageAlpha = 0f,
        backdropAlpha = 0f, chromeAlpha = 0f) else ViewerDismissVisual())
        private set
    var viewport by mutableStateOf(IntSize.Zero)
        private set
    private var started = false
    private var capturedTarget = seed?.target.takeIf { pending }
    var windowOrigin = Offset.Zero
        set(value) { field = value; prepareFirstVisual() }
    var preview by mutableStateOf(seed?.bitmap.takeIf { pending })
        private set
    var contentReady by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun ready(mediaKey: ViewerTransitionKey?) { if (mediaKey == key) contentReady = true }
    fun resize(size: IntSize) {
        if (viewport != IntSize.Zero && viewport != size) cancel()
        viewport = size
        prepareFirstVisual()
    }
    fun cancel() {
        job?.cancel()
        finish()
    }
    private fun finish() {
        isRunning = false
        visual = ViewerDismissVisual()
        preview = null
        capturedTarget = null
        job = null
    }

    private fun prepareFirstVisual() {
        if (!isRunning || started) return
        val source = openingFitBounds(viewport.width.toFloat(), viewport.height.toFloat(), ratio) ?: return
        visual = openingVisual(initialEffect, source,
            capturedTarget?.let { it.copy(bounds = it.bounds.translate(-windowOrigin)) }, 0f)
    }

    suspend fun play(effect: ViewerOpeningEffect, registry: ViewerTransitionRegistry?,
        ratio: Float, allowThumbnail: Boolean) {
        if (!isRunning) return
        if (effect == ViewerOpeningEffect.NONE) { finish(); return }
        job = currentCoroutineContext().job
        try {
            snapshotFlow { viewport }.first { it.width > 0 && it.height > 0 }
            // Start immediately with the pixels already displayed in the grid.
            // Viewer preview decoding must not hold up the opening transition.
            started = true
            val source = openingFitBounds(viewport.width.toFloat(), viewport.height.toFloat(), ratio)
                ?: Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
            val target = if (allowThumbnail) capturedTarget?.let {
                it.copy(bounds = it.bounds.translate(-windowOrigin))
            } ?: registry?.target(key, windowOrigin, viewport.width, viewport.height.toFloat()) else null
            val resolved = if (effect == ViewerOpeningEffect.THUMBNAIL && target == null)
                ViewerOpeningEffect.SCALE_FADE else effect
            visual = openingVisual(resolved, source, target, 0f)
            animate(0f, 1f, animationSpec = tween(if (resolved == ViewerOpeningEffect.THUMBNAIL) 280 else 220)) { p, _ ->
                visual = openingVisual(resolved, source, target, p)
            }
        } finally {
            finish()
        }
    }
}

@Composable
internal fun ViewerOpeningPreview(opening: ViewerOpeningTransition) {
    val bitmap = opening.preview ?: return
    if (!opening.isRunning) return
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(Modifier.fillMaxSize().viewerVisualMotion { opening.visual }) {
        if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            val fit = openingFitBounds(size.width, size.height, bitmap.width.toFloat() / bitmap.height)
            if (fit != null) drawContext.canvas.nativeCanvas.drawBitmap(bitmap, null,
                RectF(fit.left, fit.top, fit.right, fit.bottom), paint)
        }
    }
}

@Composable
internal fun rememberViewerOpeningTransition(entryId: Long, key: ViewerTransitionKey?,
    effect: ViewerOpeningEffect?, isForeground: Boolean, ratio: Float,
    allowThumbnail: Boolean): ViewerOpeningTransition {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = LocalViewerTransitions.current
    var consumed by rememberSaveable(entryId) { mutableStateOf(false) }
    val allowed = remember(entryId) {
        ValueAnimator.areAnimatorsEnabled() &&
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode != true
    }
    val opening = remember(entryId) {
        val seed = registry?.consumeOpening(key)?.takeIf { allowThumbnail }
        ViewerOpeningTransition(key, !consumed && allowed && effect != null && effect != ViewerOpeningEffect.NONE,
            seed, effect ?: ViewerOpeningEffect.NONE, ratio)
    }
    DisposableEffect(opening, registry, lifecycle) {
        registry?.opening = opening
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                consumed = true
                opening.cancel()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            opening.cancel()
            if (registry?.opening === opening) registry.opening = null
        }
    }
    LaunchedEffect(opening, effect, isForeground) {
        if (!isForeground) { consumed = true; opening.cancel(); return@LaunchedEffect }
        if (consumed || effect == null) return@LaunchedEffect
        consumed = true
        if (!allowed || effect == ViewerOpeningEffect.NONE) opening.cancel()
        else opening.play(effect, registry, ratio, allowThumbnail)
    }
    return opening
}
