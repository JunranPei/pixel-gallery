package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One queue per gallery window. Callbacks may outlive the page that sent a message. */
@Stable
internal class GalleryMessages(private val scope: CoroutineScope) {
    val hostState = SnackbarHostState()
    private val modalHosts = mutableStateListOf<Any>()

    fun show(message: String, duration: SnackbarDuration = SnackbarDuration.Short) {
        scope.launch {
            hostState.showSnackbar(
                message = message,
                duration = duration,
                withDismissAction = false,
            )
        }
    }

    fun attachModal(owner: Any) { modalHosts.add(owner) }
    fun detachModal(owner: Any) { modalHosts.remove(owner) }
    fun isActiveHost(owner: Any?) = modalHosts.lastOrNull() === owner
}

internal val LocalGalleryMessages = staticCompositionLocalOf<GalleryMessages> {
    error("Gallery messages must be provided by the gallery root")
}

@Composable
internal fun GalleryMessageProvider(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val messages = remember(scope) { GalleryMessages(scope) }
    CompositionLocalProvider(LocalGalleryMessages provides messages, content = content)
}

/** Render in the foremost window only, so a half-page cannot cover the message. */
@Composable
internal fun GalleryMessageHost(modifier: Modifier = Modifier, modal: Boolean = false) {
    val messages = LocalGalleryMessages.current
    val owner = remember { Any() }
    DisposableEffect(messages, modal) {
        if (modal) messages.attachModal(owner)
        onDispose { if (modal) messages.detachModal(owner) }
    }
    if (messages.isActiveHost(if (modal) owner else null)) {
        SnackbarHost(
            hostState = messages.hostState,
            // The old data-based Snackbar added 12.dp internally on top of the
            // host's 8.dp. Keep the visible surface another 16.dp above that.
            modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
        ) { data ->
            // Each queued message owns its gesture state, including identical messages.
            key(data) {
                val dismissState = rememberSwipeToDismissBoxState()
                LaunchedEffect(dismissState.currentValue) {
                    if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss()
                }
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {},
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss { data.dismiss(); true }
                    },
                ) {
                    Snackbar(
                        shape = MaterialTheme.shapes.large,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionContentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(data.visuals.message)
                    }
                }
            }
        }
    }
}
