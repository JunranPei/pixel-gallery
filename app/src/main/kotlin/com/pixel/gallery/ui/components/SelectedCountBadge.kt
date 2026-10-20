package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixel.gallery.R

/** Fits only within the existing title slot; never changes the toolbar's action slots. */
@Composable
fun SelectedCountBadge(count: Int) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val number = count.toString()
    val caption = stringResource(R.string.selection_count_caption)
    val description = stringResource(R.string.qs_targets_selected_count, count)
    val numberStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
    BoxWithConstraints {
        val numberWidth = with(density) { measurer.measure(number, numberStyle).size.width.toDp() }
        val captionWidth = with(density) { measurer.measure(caption, captionStyle).size.width.toDp() }
        val inline = numberWidth + captionWidth + 20.dp <= maxWidth
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
            if (inline) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(number, style = numberStyle, maxLines = 1, softWrap = false)
                    Text(caption, style = captionStyle, maxLines = 1, softWrap = false)
                }
            } else {
                // Deliberate number/caption hierarchy in narrow title slots; no split words.
                val available = (maxWidth - 8.dp).coerceAtLeast(1.dp)
                val numberScale = (available.value / numberWidth.value.coerceAtLeast(1f)).coerceAtMost(1f)
                val captionScale = (available.value / captionWidth.value.coerceAtLeast(1f)).coerceAtMost(1f)
                Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(number, style = numberStyle.copy(fontSize = 18.sp * numberScale), maxLines = 1, softWrap = false)
                    Text(caption, style = captionStyle.copy(fontSize = 11.sp * captionScale), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
