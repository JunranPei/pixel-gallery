package com.pixel.gallery.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import com.pixel.gallery.ui.theme.ExpressiveShapes

/** Keeps the concave header edge fixed while grid content scrolls underneath. */
@Composable
fun Modifier.galleryContentEdge(): Modifier {
    val headerColor = MaterialTheme.colorScheme.surfaceContainerLow
    return drawWithCache {
        val radius = ExpressiveShapes.GridCornerRadius.toPx().coerceAtMost(size.width / 2f)
        val strip = Path().apply { addRect(Rect(0f, 0f, size.width, radius)) }
        val rounded = Path().apply {
            addRoundRect(RoundRect(Rect(0f, 0f, size.width, radius * 2f), CornerRadius(radius)))
        }
        val corners = Path.combine(PathOperation.Difference, strip, rounded)
        onDrawWithContent {
            drawContent()
            drawPath(corners, headerColor)
        }
    }
}
