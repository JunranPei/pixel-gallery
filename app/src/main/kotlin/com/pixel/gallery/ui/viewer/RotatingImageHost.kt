package com.pixel.gallery.ui.viewer

import android.content.Context
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/** Keep AndroidView's viewport stationary; Android maps touches through the child matrix. */
internal class RotatingImageHost(context: Context) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
    }

    val content = FrameLayout(context).also {
        it.clipChildren = false
        it.clipToPadding = false
        addView(it)
    }
    private var contentWidth = 0
    private var contentHeight = 0

    fun updateGeometry(geometry: RotationLayerGeometry?) {
        val w = geometry?.childWidth?.toInt() ?: width
        val h = geometry?.childHeight?.toInt() ?: height
        if (w != contentWidth || h != contentHeight) {
            contentWidth = w
            contentHeight = h
            requestLayout()
        }
        content.rotation = geometry?.rotation?.degrees ?: 0f
        content.scaleX = geometry?.scale() ?: 1f
        content.scaleY = content.scaleX
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val childW = contentWidth.takeIf { it > 0 } ?: w
        val childH = contentHeight.takeIf { it > 0 } ?: h
        val turning = width == w && height == h && content.width != childW
        for (index in 0 until content.childCount) {
            (content.getChildAt(index) as? SubsamplingScaleImageView)?.preserveRelativeScaleOnResize = turning
        }
        content.measure(MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - content.measuredWidth) / 2
        val y = (height - content.measuredHeight) / 2
        content.layout(x, y, x + content.measuredWidth, y + content.measuredHeight)
        content.pivotX = content.width / 2f
        content.pivotY = content.height / 2f
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            child.pivotX = child.width / 2f
            child.pivotY = child.height / 2f
        }
    }
}
