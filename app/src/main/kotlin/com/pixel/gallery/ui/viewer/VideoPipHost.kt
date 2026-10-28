package com.pixel.gallery.ui.viewer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout

/** Keep the existing page and video output sized once; PiP only changes their transform. */
internal class VideoPipHost(
    context: Context,
    private val content: View,
    private val onLockChanged: (Boolean) -> Unit,
) : FrameLayout(context) {
    var locked = false
        private set
    private var source: Rect? = null
    private var armedSource: Rect? = null
    private var exiting = false
    private var normalWidth = 0
    private var normalHeight = 0
    private var normalInsets: WindowInsets? = null
    private var pendingConfiguration: Configuration? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        clipChildren = true
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun arm(rect: Rect?) { if (!locked) armedSource = rect?.let(::Rect) }

    fun enter(rect: Rect? = armedSource): Boolean {
        if (locked) { exiting = false; return true }
        if (rect == null || rect.isEmpty || content.width <= 0 || content.height <= 0) return false
        normalWidth = content.width
        normalHeight = content.height
        val location = IntArray(2)
        content.getLocationInWindow(location)
        source = Rect(rect).apply { offset(-location[0], -location[1]) }
        normalInsets = content.rootWindowInsets
        locked = true
        exiting = false
        onLockChanged(true)
        report("lock")
        requestLayout()
        return true
    }

    fun exit() {
        armedSource = null
        if (!locked) return
        exiting = true
        requestLayout()
        requestApplyInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        // Older Android can resize the window before delivering the PiP mode callback.
        if (!locked && armedSource != null && content.width > 0 &&
            (width < content.width * .9f || height < content.height * .9f)) enter()
        if (!locked) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        setMeasuredDimension(width, height)
        if (content.isLayoutRequested || content.measuredWidth != normalWidth || content.measuredHeight != normalHeight) {
            content.measure(MeasureSpec.makeMeasureSpec(normalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(normalHeight, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!locked) { super.onLayout(changed, left, top, right, bottom); return }
        if (content.isLayoutRequested || content.width != normalWidth || content.height != normalHeight) {
            content.layout(0, 0, normalWidth, normalHeight)
        }
        val crop = checkNotNull(source)
        val reduced = !hasNormalWindowSize()
        if (reduced) {
            val scale = minOf(width.toFloat() / crop.width(), height.toFloat() / crop.height())
            content.pivotX = 0f
            content.pivotY = 0f
            content.scaleX = scale
            content.scaleY = scale
            content.translationX = (width - crop.width() * scale) / 2f - crop.left * scale
            content.translationY = (height - crop.height() * scale) / 2f - crop.top * scale
            content.clipBounds = crop
        } else {
            resetTransform()
            releaseIfRestored()
        }
        if (changed) report("layout")
    }

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (locked) {
            if (exiting) post { releaseIfRestored() }
            return normalInsets ?: insets
        }
        return super.dispatchApplyWindowInsets(insets)
    }

    override fun dispatchConfigurationChanged(newConfig: Configuration) {
        if (locked) {
            // The page still has its original constraints. Deliver the latest configuration
            // when those constraints are restored, instead of invalidating its resources
            // and text measurements for every intermediate PiP window size.
            pendingConfiguration = Configuration(newConfig)
            report("defer-configuration")
        } else {
            super.dispatchConfigurationChanged(newConfig)
        }
    }

    private fun releaseIfRestored() {
        if (!locked || !exiting || !hasNormalWindowSize()) return
        // The viewer may have hidden its controls/bars during PiP. Its restored insets
        // need not equal those at entry; waiting for equality would keep the page locked.
        locked = false
        exiting = false
        source = null
        resetTransform()
        report("release")
        pendingConfiguration?.let { super.dispatchConfigurationChanged(it) }
        pendingConfiguration = null
        onLockChanged(false)
        post { content.requestLayout(); requestLayout(); requestApplyInsets() }
    }

    private fun hasNormalWindowSize(): Boolean =
        minOf(width, height) >= minOf(normalWidth, normalHeight) * .9f &&
            maxOf(width, height) >= maxOf(normalWidth, normalHeight) * .9f

    private fun resetTransform() {
        content.scaleX = 1f
        content.scaleY = 1f
        content.translationX = 0f
        content.translationY = 0f
        content.clipBounds = null
    }

    private fun report(event: String) {
        if (ViewerLoadMetrics.isEnabled) ViewerLoadMetrics.event("VIDEO_PIP_HOST",
            "event=$event locked=$locked exiting=$exiting host=${width}x$height " +
                "content=${content.width}x${content.height} crop=$source scale=${content.scaleX}")
    }
}
