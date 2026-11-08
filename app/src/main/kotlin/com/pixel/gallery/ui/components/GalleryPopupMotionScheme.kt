package com.pixel.gallery.ui.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme

/** The stock modal sheet keeps its gestures/layout and uses the same popup response. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object GalleryPopupMotionScheme : MotionScheme by MotionScheme.standard() {
    private val opening = Easing { OverflowMotion.responseFraction(it, opening = true) }
    private val closing = Easing { OverflowMotion.responseFraction(it, opening = false) }
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        tween(OverflowMotion.OpenMillis, easing = opening)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        tween(OverflowMotion.CloseMillis, easing = closing)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        tween(OverflowMotion.OpenMillis, easing = Easing { opening.transform(it).coerceIn(0f, 1f) })
}
