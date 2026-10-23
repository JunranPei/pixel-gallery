package com.pixel.gallery.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// Material 3 Expressive - Asymmetric and Decorative Shapes
object ExpressiveShapes {
    val GridCornerRadius = 20.dp
    // Specifically mentioned in M3E: Increased corner radii
    val LargeIncreased = RoundedCornerShape(GridCornerRadius)
    val ExtraLargeIncreased = RoundedCornerShape(32.dp)
    val ExtraExtraLarge = RoundedCornerShape(48.dp)
    
    // Decorative Asymmetric Shapes (Hallmark of M3E)
    // Asymmetric 1: Tilted look
    val AsymmetricDecorative = RoundedCornerShape(
        topStart = 32.dp,
        topEnd = 4.dp,
        bottomEnd = 32.dp,
        bottomStart = 4.dp
    )
    
    // Asymmetric 2: Folder/Tab look
    val AsymmetricTab = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 24.dp,
        bottomEnd = 4.dp,
        bottomStart = 4.dp
    )
}
