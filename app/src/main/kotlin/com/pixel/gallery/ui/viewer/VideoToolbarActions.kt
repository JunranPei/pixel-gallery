package com.pixel.gallery.ui.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.VideoPlaybackSpeeds
import com.pixel.gallery.ui.components.LocalGalleryMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val MutedIcon = ImageVector.Builder("Muted", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(4f, 15f); close()
        moveTo(16f, 10f); lineTo(20f, 14f); moveTo(20f, 10f); lineTo(16f, 14f)
    }
}.build()

private val FrameIcon = ImageVector.Builder("Capture frame", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(8f, 3f); lineTo(3f, 3f); lineTo(3f, 8f)
        moveTo(16f, 3f); lineTo(21f, 3f); lineTo(21f, 8f)
        moveTo(21f, 16f); lineTo(21f, 21f); lineTo(16f, 21f)
        moveTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f)
        moveTo(7f, 16f); lineTo(10.5f, 12f); lineTo(13f, 14f); lineTo(15f, 11f); lineTo(18f, 16f); close()
        moveTo(9f, 8f); lineTo(9.01f, 8f)
    }
}.build()

internal fun speedLabel(value: Float) = "${value.toString().removeSuffix(".0")}×"

@Composable
fun VideoToolbarActions(session: VideoPlaybackSession, onRotateScreen: () -> Unit,
    onBusyChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val messages = LocalGalleryMessages.current
    val scope = rememberCoroutineScope()
    var showSpeed by remember(session) { mutableStateOf(false) }
    var stripBusy by remember(session) { mutableStateOf(false) }
    val latestBusyChanged by rememberUpdatedState(onBusyChanged)
    DisposableEffect(showSpeed, session.captureBusy, stripBusy) {
        latestBusyChanged(showSpeed || session.captureBusy || stripBusy)
        onDispose { latestBusyChanged(false) }
    }
    VideoActionStrip(session, onBusyChanged = { stripBusy = it }) { visibleActions ->
    IconButton(onClick = session::toggleMute, modifier = Modifier.size(48.dp)) {
        Icon(if (session.muted) MutedIcon else Icons.AutoMirrored.Outlined.VolumeUp,
            stringResource(if (session.muted) R.string.video_unmute else R.string.video_mute), tint = Color.White)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onRotateScreen, enabled = visibleActions >= 2,
        modifier = Modifier.size(48.dp).then(if (visibleActions < 2) Modifier.clearAndSetSemantics {} else Modifier)) {
        Icon(Icons.Outlined.ScreenRotation, stringResource(R.string.video_rotate_screen), tint = Color.White)
    }
    Box(Modifier.then(if (visibleActions < 3) Modifier.clearAndSetSemantics {} else Modifier)) {
    IconButton(onClick = { showSpeed = !showSpeed }, enabled = visibleActions >= 3,
        modifier = Modifier.width(64.dp).height(48.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Speed, stringResource(R.string.video_speed), tint = Color.White, modifier = Modifier.size(20.dp))
            Text(speedLabel(session.speed), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
    if (showSpeed) InlineSpeedChoices(session.speed, onDismiss = { showSpeed = false }) { speed ->
        if (!session.setSpeed(speed)) scope.launch { messages.show(context.getString(R.string.video_speed_failed)) }
        showSpeed = false
    }
    }
    IconButton(enabled = visibleActions >= 4 && session.firstFrame && !session.captureBusy,
        modifier = Modifier.size(48.dp).then(if (visibleActions < 4) Modifier.clearAndSetSemantics {} else Modifier), onClick = {
        session.captureBusy = true
        scope.launch {
            try {
                val width = session.native?.width ?: session.player.videoSize.width
                val height = session.native?.height ?: session.player.videoSize.height
                val bitmap = captureSessionFrame(session, width, height)
                try { saveVideoFrame(context, bitmap, session.settings.captureTree) } finally { bitmap.recycle() }
                messages.show(context.getString(R.string.video_capture_saved))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { messages.show(context.getString(R.string.video_capture_failed), SnackbarDuration.Long) }
            finally { session.captureBusy = false }
        }
    }) {
        Icon(FrameIcon, stringResource(R.string.video_capture), tint = Color.White.copy(alpha = if (session.firstFrame && !session.captureBusy) 1f else .38f))
    }
    IconButton(enabled = visibleActions >= 5 && session.pipSupported && session.firstFrame,
        modifier = Modifier.size(48.dp).then(if (visibleActions < 5) Modifier.clearAndSetSemantics {} else Modifier), onClick = {
        if (!session.enterPip()) scope.launch { messages.show(context.getString(R.string.video_pip_failed)) }
    }) {
        Icon(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.video_pip), tint = Color.White.copy(alpha = if (session.pipSupported && session.firstFrame) 1f else .38f))
    }
    }
    }
}

@Composable
private fun InlineSpeedChoices(speed: Float, onDismiss: () -> Unit, onSelect: (Float) -> Unit) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                (anchorBounds.right - popupContentSize.width).coerceIn(margin,
                    (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                (anchorBounds.bottom + margin).coerceAtMost(
                    (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
            )
        }
    }
    val rowState = rememberLazyListState(
        initialFirstVisibleItemIndex = (VideoPlaybackSpeeds.indexOf(speed) - 3).coerceAtLeast(0))
    val width = (LocalConfiguration.current.screenWidthDp.dp - 16.dp).coerceAtMost(416.dp)
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Surface(color = Color(0xF0202020), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(width)) {
            LazyRow(state = rowState, contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                items(VideoPlaybackSpeeds) { value ->
                    val active = value == speed
                    Surface(onClick = { onSelect(value) }, color = if (active) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.size(48.dp).semantics { selected = active }) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(speedLabel(value), color = if (active) Color.Black else Color.White,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
