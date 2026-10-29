package com.pixel.gallery.ui.deduplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixel.gallery.R
import com.pixel.gallery.model.DuplicateScanStage
import java.text.NumberFormat
import kotlin.math.min

/** One vector drawing at both sizes; theme colors and square photo proportions stay identical. */
@Composable
internal fun DuplicatePhotoIllustration(showCheck: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val landscape = remember {
        Path().apply {
            moveTo(18f, 108f); lineTo(43f, 77f); quadraticTo(50f, 67f, 57f, 77f)
            lineTo(69f, 92f); quadraticTo(74f, 98f, 79f, 92f)
            lineTo(84f, 86f); quadraticTo(91f, 77f, 98f, 86f)
            lineTo(119f, 109f); quadraticTo(124f, 116f, 115f, 116f)
            lineTo(23f, 116f); quadraticTo(13f, 116f, 18f, 108f); close()
        }
    }
    val check = remember {
        Path().apply { moveTo(166.39f, 157f); lineTo(174.22f, 165.1f); lineTo(190.42f, 148.36f) }
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val factor = min(size.width / 220f, size.height / 200f)
        withTransform({
            translate((size.width - 220f * factor) / 2f, (size.height - 200f * factor) / 2f)
            scale(factor, factor, Offset.Zero)
        }) {
            withTransform({ translate(22f, 27f); rotate(-11f, Offset(68f, 68f)) }) {
                drawRoundRect(lerp(colors.primaryContainer, colors.primary, .26f), size = Size(136f, 136f), cornerRadius = CornerRadius(28f))
                val rearLandscape = lerp(colors.primaryContainer, colors.primary, .65f)
                drawCircle(rearLandscape, 11f, Offset(39f, 40f))
                drawPath(landscape, rearLandscape)
            }
            withTransform({ translate(60f, 38f) }) {
                drawRoundRect(colors.primaryContainer, size = Size(136f, 136f), cornerRadius = CornerRadius(28f))
                drawCircle(colors.primary, 11f, Offset(103f, 38f))
                drawPath(landscape, colors.primary)
            }
            if (showCheck) {
                drawCircle(colors.primary, 27f, Offset(178f, 157f))
                drawPath(check, colors.onPrimary, style = Stroke(6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
internal fun DuplicateProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.height(8.dp),
            color = colors.primary, trackColor = colors.primaryContainer,
            strokeCap = StrokeCap.Round, gapSize = 4.dp, drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier.height(8.dp),
            color = colors.primary, trackColor = colors.primaryContainer,
            strokeCap = StrokeCap.Round, gapSize = 4.dp,
        )
    }
}

@Composable
internal fun DuplicateScanContent(state: DuplicateUiState) {
    val progress = state.progress
    val determinate = state.phase == DuplicatePhase.SCANNING && progress != null &&
        progress.total > 0 && progress.stage != DuplicateScanStage.PREPARING
    val stage = when (state.phase) {
        DuplicatePhase.VALIDATING -> R.string.duplicate_status_validating
        DuplicatePhase.AWAITING_PERMISSION -> R.string.duplicate_status_permission
        DuplicatePhase.VERIFYING -> R.string.duplicate_status_moving
        else -> when (progress?.stage) {
            null, DuplicateScanStage.PREPARING -> R.string.duplicate_status_preparing
            DuplicateScanStage.INVENTORY -> R.string.duplicate_status_inventory
            DuplicateScanStage.SAMPLING -> R.string.duplicate_status_sampling
            DuplicateScanStage.COMPARING -> R.string.duplicate_status_comparing
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = (maxHeight * .18f).coerceIn(40.dp, 132.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp).padding(top = top, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            DuplicatePhotoIllustration(false, Modifier.offset(x = (-6).dp).size(78.dp, 71.dp))
            Text(stringResource(stage), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (determinate && progress != null) {
                val formatter = NumberFormat.getIntegerInstance()
                val description = stringResource(R.string.duplicate_scan_progress, progress.completed, progress.total)
                Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatter.format(progress.completed), modifier = Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 48.sp),
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text("/ ${formatter.format(progress.total)}", modifier = Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DuplicateProgressBar(
                if (determinate && progress != null) progress.completed.toFloat() / progress.total else null,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Both no-match scans and successful cleanup end at this exact same content. */
@Composable
internal fun DuplicateResultContent(title: String, noDuplicates: Boolean, detail: String? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = 28.dp, vertical = 24.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            if (noDuplicates) DuplicatePhotoIllustration(true, Modifier.size(244.dp, 222.dp))
            else Icon(Icons.Outlined.Info, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
