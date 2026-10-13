package com.pixel.gallery.ui.utils

import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import com.pixel.gallery.model.Handedness

// Hand preference refers to physical screen edges, independently of text direction.
internal val Handedness.actionHorizontalAlignment: Alignment.Horizontal
    get() = if (this == Handedness.RIGHT) AbsoluteAlignment.Right else AbsoluteAlignment.Left

internal val Handedness.actionBottomAlignment: Alignment
    get() = if (this == Handedness.RIGHT) AbsoluteAlignment.BottomRight else AbsoluteAlignment.BottomLeft
