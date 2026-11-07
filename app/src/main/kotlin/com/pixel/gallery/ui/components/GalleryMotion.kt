package com.pixel.gallery.ui.components

import androidx.compose.animation.core.spring

/** Shared response families; gestures write positions directly until release. */
internal object GalleryMotion {
    const val LayoutStiffness = 520f
    const val ReorderStiffness = 850f
    const val NavigationStiffness = 620f
    fun <T> layout() = spring<T>(dampingRatio = 1f, stiffness = LayoutStiffness)
    fun <T> reorder() = spring<T>(dampingRatio = 1f, stiffness = ReorderStiffness)
    fun fraction() = spring<Float>(dampingRatio = 1f, stiffness = LayoutStiffness, visibilityThreshold = .001f)
    fun <T> navigation() = spring<T>(dampingRatio = 1f, stiffness = NavigationStiffness)
}
