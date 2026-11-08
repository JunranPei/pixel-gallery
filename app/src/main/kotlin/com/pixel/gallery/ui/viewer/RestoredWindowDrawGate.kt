package com.pixel.gallery.ui.viewer

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalRestoredWindowDrawGate = staticCompositionLocalOf<RestoredWindowDrawGate?> { null }

/** Keep the system's retained window until a restored image has actual content. */
internal class RestoredWindowDrawGate(
    private val decor: View,
    timeoutMillis: Long = 5_000L,
) : ViewTreeObserver.OnPreDrawListener {
    private var expected: String? = null
    private val resolved = mutableMapOf<String, String>()
    private var closed = false
    val isWaiting: Boolean get() = !closed
    private var gridReady: (() -> Boolean)? = null
    private val closeActions = mutableListOf<() -> Unit>()
    private val timeout = Runnable { release("timeout") }

    init {
        decor.viewTreeObserver.addOnPreDrawListener(this)
        decor.postDelayed(timeout, timeoutMillis)
        ViewerLoadMetrics.event("RESTORE_DRAW_WAIT")
    }

    fun expect(key: String) {
        if (!closed) {
            expected = key
            gridReady = null
        }
    }

    fun expectGrid(key: String, ready: () -> Boolean) {
        if (!closed) {
            expected = key
            gridReady = ready
        }
    }

    fun resolve(key: String, reason: String = "image-ready") {
        if (closed) return
        resolved[key] = reason
        decor.invalidate()
    }

    fun onClosed(action: () -> Unit) {
        if (closed) action() else closeActions.add(action)
    }

    override fun onPreDraw(): Boolean {
        if (closed) return true
        val reason = gridReady?.let { if (it()) "grid-ready" else return false }
            ?: resolved[expected] ?: return false
        release(reason)
        return true
    }

    private fun release(reason: String) {
        if (closed) return
        ViewerLoadMetrics.event("RESTORE_DRAW_RELEASE", "content=$expected reason=$reason")
        dispose()
        decor.invalidate()
    }

    fun dispose() {
        if (closed) return
        closed = true
        decor.removeCallbacks(timeout)
        if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
        resolved.clear()
        gridReady = null
        closeActions.forEach { it() }
        closeActions.clear()
    }
}
