package com.pixel.gallery.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                withDismissAction = duration == SnackbarDuration.Long,
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
        SnackbarHost(hostState = messages.hostState, modifier = modifier.padding(8.dp)) { data ->
            Snackbar(
                snackbarData = data,
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                actionColor = MaterialTheme.colorScheme.inversePrimary,
                dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}
