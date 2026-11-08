package com.pixel.gallery.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Stable
internal class PopupMotionState(private val parent: PopupMotionState?, initialFraction: Float) {
    var fraction by mutableFloatStateOf(initialFraction)
    var opening by mutableStateOf(true)
    var requested by mutableStateOf(true)
    val interactive: Boolean get() = requested && parent?.interactive != false
    val displayedFraction: Float get() = fraction * (parent?.displayedFraction ?: 1f)
}

internal val LocalPopupMotion = staticCompositionLocalOf<PopupMotionState?> { null }

/** Keep only the outgoing popup payload until its exit finishes; no idle animation. */
@Composable
fun <T : Any> PopupPresence(value: T?, content: @Composable (T) -> Unit) {
    var retained by remember { mutableStateOf(value) }
    val parent = LocalPopupMotion.current
    val motion = remember(parent) {
        // A child arriving with its parent shares that entrance. A new popup
        // inside an already settled parent still needs its own entrance.
        PopupMotionState(parent, if (parent != null && value != null && parent.fraction != 1f) 1f else 0f)
    }
    val visible = value != null
    SideEffect {
        if (value != null) retained = value
        motion.requested = visible
    }
    LaunchedEffect(visible) {
        if (!visible && retained == null) return@LaunchedEffect
        val from = motion.fraction
        motion.opening = visible
        if (from == (if (visible) 1f else 0f)) {
            if (!visible) retained = null
            return@LaunchedEffect
        }
        animate(0f, 1f, animationSpec = tween(
            if (visible) OverflowMotion.OpenMillis else OverflowMotion.CloseMillis,
            easing = LinearEasing,
        )) { progress, _ ->
            val response = OverflowMotion.responseFraction(progress, visible)
            motion.fraction = from + ((if (visible) 1f else 0f) - from) * response
        }
        motion.fraction = if (visible) 1f else 0f
        if (!visible) retained = null
    }
    (value ?: retained)?.let { payload ->
        CompositionLocalProvider(LocalPopupMotion provides motion) { content(payload) }
    }
}

@Composable
fun PopupPresence(visible: Boolean, content: @Composable () -> Unit) {
    PopupPresence(Unit.takeIf { visible }) { content() }
}

@Composable
internal fun PopupDialogWindow() {
    val motion = LocalPopupMotion.current ?: return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val dim = remember(window) { window.attributes.dimAmount }
    DisposableEffect(window) {
        // The content owns both transitions. Disable the platform's extra fade.
        window.setWindowAnimations(0)
        window.setDimAmount(dim * motion.displayedFraction.coerceIn(0f, 1f))
        onDispose { }
    }
    LaunchedEffect(window, motion) {
        snapshotFlow { motion.displayedFraction.coerceIn(0f, 1f) }.collect { window.setDimAmount(dim * it) }
    }
}

@Composable
internal fun GalleryPopupDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val motion = LocalPopupMotion.current
    Dialog(onDismissRequest = { if (motion?.interactive != false) onDismissRequest() }, properties = properties) {
        PopupDialogWindow()
        content()
    }
}

/** Layer-only updates preserve text measurement and child composition while animating. */
@Composable
internal fun Modifier.popupMotion(origin: TransformOrigin = TransformOrigin.Center): Modifier {
    val motion = LocalPopupMotion.current ?: return this
    return graphicsLayer {
        val fraction = motion.displayedFraction
        alpha = fraction.coerceIn(0f, 1f)
        scaleX = .92f + .08f * fraction
        scaleY = scaleX
        transformOrigin = origin
    }.then(if (motion.interactive) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    })
}
