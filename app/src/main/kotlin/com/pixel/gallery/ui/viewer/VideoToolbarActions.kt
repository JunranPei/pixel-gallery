package com.pixel.gallery.ui.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Speed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pixel.gallery.R
import com.pixel.gallery.data.repository.VideoPlaybackSpeeds
import com.pixel.gallery.ui.components.GalleryDialog
import com.pixel.gallery.ui.components.LocalGalleryMessages
import com.pixel.gallery.ui.components.SettingsOptionControl
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
fun VideoToolbarActions(session: VideoPlaybackSession, onBusyChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val messages = LocalGalleryMessages.current
    val scope = rememberCoroutineScope()
    var showSpeed by remember(session) { mutableStateOf(false) }
    var selection by remember(session, showSpeed) { mutableFloatStateOf(session.speed) }
    val latestBusyChanged by rememberUpdatedState(onBusyChanged)
    DisposableEffect(showSpeed, session.captureBusy) {
        latestBusyChanged(showSpeed || session.captureBusy)
        onDispose { latestBusyChanged(false) }
    }
    IconButton(onClick = session::toggleMute, modifier = Modifier.size(48.dp)) {
        Icon(if (session.muted) MutedIcon else Icons.AutoMirrored.Outlined.VolumeUp,
            stringResource(if (session.muted) R.string.video_unmute else R.string.video_mute), tint = Color.White)
    }
    IconButton(enabled = session.firstFrame && !session.captureBusy, modifier = Modifier.size(48.dp), onClick = {
        session.captureBusy = true
        scope.launch {
            try {
                val surface = session.surface ?: error("Video surface unavailable")
                val width = session.native?.width ?: session.player.videoSize.width
                val height = session.native?.height ?: session.player.videoSize.height
                val bitmap = captureVideoFrame(surface, width, height)
                try { saveVideoFrame(context, bitmap, session.settings.captureTree) } finally { bitmap.recycle() }
                messages.show(context.getString(R.string.video_capture_saved))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { messages.show(context.getString(R.string.video_capture_failed), SnackbarDuration.Long) }
            finally { session.captureBusy = false }
        }
    }) {
        Icon(FrameIcon, stringResource(R.string.video_capture), tint = Color.White.copy(alpha = if (session.firstFrame && !session.captureBusy) 1f else .38f))
    }
    IconButton(onClick = { showSpeed = true }, modifier = Modifier.width(64.dp).height(48.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Outlined.Speed, stringResource(R.string.video_speed), tint = Color.White, modifier = Modifier.size(20.dp))
            Text(speedLabel(session.speed), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
    IconButton(enabled = session.pipSupported && session.firstFrame, modifier = Modifier.size(48.dp), onClick = {
        if (!session.enterPip()) scope.launch { messages.show(context.getString(R.string.video_pip_failed)) }
    }) {
        Icon(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.video_pip), tint = Color.White.copy(alpha = if (session.pipSupported && session.firstFrame) 1f else .38f))
    }
    if (showSpeed) GalleryDialog(
        onDismissRequest = { showSpeed = false },
        title = { Text(stringResource(R.string.video_speed)) },
        icon = Icons.Outlined.Speed,
        text = {
            VideoPlaybackSpeeds.chunked(2).forEach { row ->
                SettingsOptionControl(row.map { it to speedLabel(it) }, selection, { selection = it },
                    unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
        },
        confirmButton = { Button(onClick = {
            if (!session.setSpeed(selection)) scope.launch { messages.show(context.getString(R.string.video_speed_failed)) }
            showSpeed = false
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { showSpeed = false }) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}
