package com.pixel.gallery.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared setting-page geometry. Independent blocks are separated by twice the item gap. */
object SettingsLayout {
    val ItemSpacing = 4.dp
    val GroupSpacing = ItemSpacing * 2
    val ContentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 32.dp)
    val OuterCorner = 24.dp
    val InnerCorner = 6.dp
}

enum class SettingsBlockPosition {
    Single, First, Middle, Last;

    val shape: RoundedCornerShape
        get() = with(SettingsLayout) {
            when (this@SettingsBlockPosition) {
                Single -> RoundedCornerShape(OuterCorner)
                First -> RoundedCornerShape(topStart = OuterCorner, topEnd = OuterCorner, bottomStart = InnerCorner, bottomEnd = InnerCorner)
                Middle -> RoundedCornerShape(InnerCorner)
                Last -> RoundedCornerShape(topStart = InnerCorner, topEnd = InnerCorner, bottomStart = OuterCorner, bottomEnd = OuterCorner)
            }
        }
}

/** Two shared theme-derived background levels, including dynamic colors and dark mode. */
enum class SettingsBlockTone {
    Standard, Emphasized;

    val color: Color
        @Composable get() = when (this) {
            Standard -> MaterialTheme.colorScheme.surfaceContainer
            Emphasized -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
}

@Composable
fun SettingsBlock(
    spec: SettingsBlockSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = spec.tone.color, shape = spec.position.shape, content = content)
}

/** A second-level block owns its appearance; content does not need to know its group position. */
@Immutable
data class SettingsBlockSpec(
    val position: SettingsBlockPosition = SettingsBlockPosition.Single,
    val tone: SettingsBlockTone = SettingsBlockTone.Standard,
)

/** One first-level group creates and positions the requested number of second-level blocks. */
@Immutable
data class SettingsGroupSpec(
    val itemCount: Int,
    val tone: SettingsBlockTone = SettingsBlockTone.Standard,
) {
    init { require(itemCount > 0) { "A settings group must contain at least one block" } }

    fun blockAt(index: Int): SettingsBlockSpec {
        require(index in 0 until itemCount) { "Block index is outside this settings group" }
        val position = when {
            itemCount == 1 -> SettingsBlockPosition.Single
            index == 0 -> SettingsBlockPosition.First
            index == itemCount - 1 -> SettingsBlockPosition.Last
            else -> SettingsBlockPosition.Middle
        }
        return SettingsBlockSpec(position, tone)
    }
}

@Composable
fun SettingsGroup(
    itemCount: Int,
    modifier: Modifier = Modifier,
    tone: SettingsBlockTone = SettingsBlockTone.Standard,
    content: @Composable (index: Int) -> Unit,
) {
    val spec = remember(itemCount, tone) { SettingsGroupSpec(itemCount, tone) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsLayout.ItemSpacing)) {
        repeat(spec.itemCount) { index ->
            SettingsBlock(spec.blockAt(index)) { content(index) }
        }
    }
}
