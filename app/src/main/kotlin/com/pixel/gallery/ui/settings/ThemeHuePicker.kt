package com.pixel.gallery.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.material.color.utilities.Hct
import com.pixel.gallery.R
import com.pixel.gallery.ui.theme.resolveManualColorSeed
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.stateDescription

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeHuePicker(savedColor: Int, onSave: (Int) -> Unit) {
    var hue by remember(savedColor) {
        mutableFloatStateOf(Hct.fromInt(resolveManualColorSeed(savedColor)).hue.toFloat())
    }
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val active = dragged || pressed
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    val handle = Color(Hct.from(hue.toDouble(), 36.0, if (dark) 80.0 else 40.0).toInt())
    val rainbow = remember(dark) {
        (0..36).map { Color(Hct.from(it * 10.0, 36.0, if (dark) 65.0 else 60.0).toInt()) }
    }
    val hueLabel = stringResource(R.string.theme_color_hue)
    val names = stringArrayResource(R.array.theme_hue_names)
    val colorName = names[((hue + 5f) / 10f).toInt() % names.size]
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val center = 20.dp + (maxWidth - 40.dp) * (hue / 360f)
        val labelShift = (52.dp - center).coerceAtLeast(0.dp) -
            (center + 52.dp - maxWidth).coerceAtLeast(0.dp)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
            SettingsOptionHeading(stringResource(R.string.theme_color_manual), Icons.Outlined.Palette)
            // Reserve indicator space inside this surface; it never covers the title.
            Spacer(Modifier.height(24.dp))
            Slider(
                value = hue,
                onValueChange = { hue = it },
                onValueChangeFinished = { onSave(Hct.from(hue.toDouble(), 36.0, 50.0).toInt()) },
                valueRange = 0f..360f,
                interactionSource = interactions,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hueLabel; stateDescription = colorName },
                thumb = {
                    Box(Modifier.size(width = 8.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(if (active) 6.dp else 8.dp).height(44.dp)
                            .background(handle, RoundedCornerShape(4.dp)))
                        if (active) {
                            Text(colorName, maxLines = 1,
                                modifier = Modifier.align(Alignment.TopCenter).offset(x = labelShift, y = (-26).dp)
                                    .requiredWidth(104.dp).background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(20.dp))
                                    .padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                        }
                    }
                },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                        val x = size.width * (state.value / 360f)
                        val gap = 10.dp.toPx()
                        val brush = Brush.horizontalGradient(rainbow)
                        clipRect(right = (x - gap).coerceAtLeast(0f)) {
                            drawRoundRect(brush, size = Size(size.width, size.height), cornerRadius = CornerRadius(8.dp.toPx()))
                        }
                        clipRect(left = (x + gap).coerceAtMost(size.width)) {
                            drawRoundRect(brush, size = Size(size.width, size.height), cornerRadius = CornerRadius(8.dp.toPx()))
                        }
                    }
                },
            )
    }
    }
}
