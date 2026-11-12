package com.pixel.gallery.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** One-shot inspection of existing objects. No registration, redraw, navigation or store.get(). */
internal object ViewerDiagnosticsSnapshot {
    fun capture(): JSONObject {
        if (Build.VERSION.SDK_INT < 29) return JSONObject().put("supported",false)
        if (Looper.myLooper() == Looper.getMainLooper()) return inspectWindows()
        val task=FutureTask { inspectWindows() }
        val handler=Handler(Looper.getMainLooper())
        handler.post(task)
        return try { task.get(2,TimeUnit.SECONDS) } catch (error: Exception) {
            handler.removeCallbacks(task)
            task.cancel(false)
            JSONObject().put("supported",true).put("error",error.javaClass.simpleName)
        }
    }

    private fun inspectWindows(): JSONObject = JSONObject().put("supported",true)
        .put("windows",JSONArray(WindowInspector.getGlobalWindowViews().map(::inspectRoot)))

    internal fun inspectRoot(root: View): JSONObject {
        val tiles=JSONArray()
        val previews=JSONArray()
        var activity=findActivity(root.context)
        fun visit(view: View) {
            if (activity == null) activity=findActivity(view.context)
            when(view) {
                is SubsamplingScaleImageView -> {
                    val state=view.snapshotViewState()
                    val item=geometry(view).put("ready",state != null)
                    if (state != null) item.put("scale",numeric(state.scale)).put("baseFitScale",numeric(state.baseFitScale))
                        .put("sourceWidth",state.sourceWidth).put("sourceHeight",state.sourceHeight)
                        .put("centerX",numeric(state.sourceCenter.x)).put("centerY",numeric(state.sourceCenter.y))
                    val callback=view.onImageEventListener
                    if (callback != null) for(field in callback.javaClass.declaredFields) {
                        try {
                            if (field.type == ViewerTransformStateStore::class.java || field.type == String::class.java) {
                                field.isAccessible=true
                                when(val value=field.get(callback)) {
                                    is ViewerTransformStateStore -> item.put("store",System.identityHashCode(value))
                                        .put("storeSnapshot",JSONArray(value.saveableSnapshot().map {
                                            if (it is Number && !it.toDouble().isFinite()) it.toString() else it
                                        }))
                                    is String -> if (field.name.contains("transformStateKey",true) || value.startsWith("media:"))
                                        item.put("callbackKey",value)
                                }
                            }
                        } catch (error: Exception) { item.put("callbackInspectionError",error.javaClass.simpleName) }
                    }
                    try {
                        val field=SubsamplingScaleImageView::class.java.getDeclaredField("uri")
                        field.isAccessible=true
                        item.put("decoderUri",field.get(view)?.toString())
                    } catch (error: Exception) { item.put("decoderInspectionError",error.javaClass.simpleName) }
                    tiles.put(item)
                }
                is ImageView -> previews.put(geometry(view).put("scaleX",view.scaleX).put("scaleY",view.scaleY)
                    .put("translationX",view.translationX).put("translationY",view.translationY)
                    .put("drawableWidth",view.drawable?.intrinsicWidth).put("drawableHeight",view.drawable?.intrinsicHeight))
            }
            if (view is ViewGroup) for(i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return geometry(root).put("task",activity?.taskId).put("activity",activity?.let(System::identityHashCode))
            .put("focus",root.hasWindowFocus()).put("tiles",tiles).put("imageViews",previews)
    }

    private fun geometry(view: View)=JSONObject().put("class",view.javaClass.name)
        .put("identity",System.identityHashCode(view)).put("width",view.width).put("height",view.height)
        .put("visibility",view.visibility).put("alpha",view.alpha).put("attached",view.isAttachedToWindow)

    private fun numeric(value: Float): Any = if (value.isFinite()) value else value.toString()

    private fun findActivity(context: Context): Activity? {
        var current=context
        repeat(16) {
            if (current is Activity) return current as Activity
            val next=(current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current=next
        }
        return null
    }
}
