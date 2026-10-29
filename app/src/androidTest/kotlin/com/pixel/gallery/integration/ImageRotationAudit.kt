package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.viewer.*
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Real pointer regression checks using production renderers and rotation arbitration. */
internal class ImageRotationAudit(private val instrumentation: Instrumentation) {
    private val output = File(instrumentation.targetContext.filesDir, "image-rotation-audit").apply { mkdirs() }
    private val lines = mutableListOf<String>()
    private lateinit var activity: MainActivity
    private lateinit var rotation: ViewerContentRotation
    private lateinit var pager: PagerState
    private var downTime = 0L
    private fun record(s: String) { lines += s; File(output, "report.txt").appendText("$s\n") }
    private fun views(): List<View> {
        val list = mutableListOf<View>()
        fun visit(v: View) { list += v; if (v is ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it)) } }
        visit(activity.window.decorView)
        return list
    }
    fun exercise(): String {
        File(output, "report.txt").writeText("")
        val file = File(output, "fixture.jpg")
        val bitmap = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        repeat(12) { y -> repeat(16) { x ->
            paint.color = if ((x+y)%2 == 0) 0xff204080.toInt() else 0xffe0b040.toInt()
            canvas.drawRect(x*128f,y*128f,(x+1)*128f,(y+1)*128f,paint)
        } }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            for ((name, scale) in listOf("underfit" to .65f, "zoomed" to 1.2f, "deep" to 3f, "fallback" to 3f)) {
                instrumentation.runOnMainSync {
                    activity.setContent {
                        key(name) {
                        val scope = rememberCoroutineScope()
                        val state = remember(name) { ViewerContentRotation(0, scope) }
                        rotation = state
                        val pageState = rememberPagerState { 2 }
                        pager = pageState
                        val gesture = rememberViewerRotationGesture(true, true, { state.drag(it) }, { state.finish(it) {} })
                        Box(Modifier.fillMaxSize().viewerRotationArea(gesture)) {
                            HorizontalPager(pageState, Modifier.fillMaxSize()) { page ->
                            if (page == 0) {
                            RotatingMediaLayer(state, 4f/3f, Modifier.fillMaxSize(), nativeImage = true) {
                                if (name == "fallback") GlideViewerFallback(file.path,2048,1536,0,0,true,
                                    modifier=Modifier.fillMaxSize()) else
                                SimpleSubsamplingImageView(file.toURI().toString(), file.path,
                                    sourceWidth=2048, sourceHeight=1536,
                                    transformStateStore=remember(name) { ViewerTransformStateStore() },
                                    modifier=Modifier.fillMaxSize())
                            }
                            }
                            }
                        }
                        }
                    }
                }
                SystemClock.sleep(2500)
                pair(scale, 0f)
                SystemClock.sleep(1800)
                pan(110f)
                capture("$name-before")
                val before = nativeState()
                instrumentation.runOnMainSync {
                    views().filterIsInstance<SubsamplingScaleImageView>().forEach { view ->
                        view.diagnosticsListener = { record("$name SSIV $it") }
                    }
                }
                pair(1f, 90f) {
                    capture("$name-held-90")
                    if (name == "deep" || name == "fallback") verifyFullViewport("$name-held-90")
                }
                SystemClock.sleep(500)
                capture("$name-after")
                check(rotation.settledDegrees == 90) { "$name did not settle at 90" }
                val after = nativeState()
                if (before != null && after != null) {
                    check(kotlin.math.abs(before.scale / before.baseFitScale - after.scale / after.baseFitScale) < .02f) { "$name zoom jumped at settle" }
                    if (name == "deep") check(android.graphics.PointF.length(
                        before.sourceCenter.x-after.sourceCenter.x, before.sourceCenter.y-after.sourceCenter.y) < 1f) { "deep center shifted" }
                }
                verifyState("$name-after")
                if (name == "underfit") verifyCenteredPreview(name)
                pan(-90f)
                pair(.8f,0f)
                SystemClock.sleep(400)
                capture("$name-followup")
                if (name == "deep") check(nativeState()!!.scale < after!!.scale * .9f) { "pinch after rotation was lost" }
                instrumentation.runOnMainSync { views().filterIsInstance<SubsamplingScaleImageView>().forEach { it.invalidate() } }
                SystemClock.sleep(300)
                capture("$name-invalidated")
                pan(80f)
                pair(.85f,0f)
                pair(1f,-70f)
                SystemClock.sleep(400)
                capture("$name-after-invalidation-input")
                check(rotation.settledDegrees == 0) { "$name reverse rotation was lost" }
                verifyState("$name-returned")
                check(pager.currentPage == 0) { "$name gesture escaped to pager" }
                if (name == "underfit") verifyCenteredPreview(name)
                record("PASS $name rotation / scale / input / cleanup")
            }
            return lines.joinToString("\n")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun nativeState(): SubsamplingScaleImageView.ViewState? {
        var result: SubsamplingScaleImageView.ViewState? = null
        instrumentation.runOnMainSync { result=views().filterIsInstance<SubsamplingScaleImageView>().firstOrNull()?.snapshotViewState() }
        return result
    }
    private fun verifyState(name: String) = instrumentation.runOnMainSync {
        views().filterIsInstance<SubsamplingScaleImageView>().filter { it.visibility == View.VISIBLE }.forEach { view ->
            val rect=android.graphics.Rect()
            check(view.getGlobalVisibleRect(rect) && rect.contains(activity.resources.displayMetrics.widthPixels/2,
                activity.resources.displayMetrics.heightPixels/2)) { "$name input viewport is outside the display" }
            for (key in listOf("isZooming","isPanning","ignoreTouches","pendingScale","sPendingCenter")) {
                val value=SubsamplingScaleImageView::class.java.getDeclaredField(key).apply { isAccessible=true }.get(view)
                check(value == null || value == false) { "$name stale $key=$value" }
            }
        }
    }
    private fun verifyCenteredPreview(name: String) = instrumentation.runOnMainSync {
        val view=views().filterIsInstance<ImageView>().first { it !is SubsamplingScaleImageView }
        check(kotlin.math.abs(view.pivotX-view.width/2f)<1 && kotlin.math.abs(view.pivotY-view.height/2f)<1) { "$name stale preview pivot" }
    }
    private fun verifyFullViewport(name: String) {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("screenshot unavailable")
        try {
            val corners=listOf(24 to 80, bitmap.width-24 to 80,24 to bitmap.height-80,bitmap.width-24 to bitmap.height-80)
            for ((x,y) in corners) {
                val color=bitmap.getPixel(x,y)
                val r=android.graphics.Color.red(color); val g=android.graphics.Color.green(color); val b=android.graphics.Color.blue(color)
                check((b>r*1.5f && g<120) || (r>150 && g>100 && b<120)) { "$name blank rotating edge $x,$y color=$color" }
            }
        } finally { bitmap.recycle() }
    }
    private fun capture(name: String) {
        instrumentation.runOnMainSync {
            record("$name rotation=${rotation.degrees} settled=${rotation.settledDegrees} busy=${rotation.busy}")
            views().filterIsInstance<ImageView>().forEach { v ->
                val details = if (v is SubsamplingScaleImageView) {
                    val flags = listOf("isZooming","isPanning","maxTouchCount","ignoreTouches", "pendingScale", "sPendingCenter").joinToString { key ->
                        "$key=${SubsamplingScaleImageView::class.java.getDeclaredField(key).apply { isAccessible=true }.get(v)}"
                    }
                    "state=${v.snapshotViewState()} $flags"
                } else "scale=${v.scaleX} translation=${v.translationX},${v.translationY}"
                val visible = android.graphics.Rect(); val visibleResult = v.getGlobalVisibleRect(visible)
                record("  ${v.javaClass.simpleName} size=${v.width}x${v.height} enabled=${v.isEnabled} alpha=${v.alpha} visibility=${v.visibility} global=$visibleResult:$visible $details")
            }
        }
        instrumentation.uiAutomation.takeScreenshot()?.apply {
            File(output,"$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) }; recycle()
        }
    }
    private fun pair(scale: Float, angle: Float, beforeRelease: () -> Unit = {}) {
        val dm=instrumentation.targetContext.resources.displayMetrics
        val cx=dm.widthPixels*.5f; val cy=dm.heightPixels*.5f; val radius=180f
        event(MotionEvent.ACTION_DOWN, listOf(cx-radius to cy))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(cx-radius to cy,cx+radius to cy))
        var points=listOf(cx-radius to cy,cx+radius to cy)
        repeat(25) { i ->
            val fraction=(i+1)/25f
            val r=radius*(1+(scale-1)*fraction)
            val rad=Math.toRadians((angle*fraction).toDouble())
            val dx=r*cos(rad).toFloat(); val dy=r*sin(rad).toFloat()
            points=listOf(cx-dx to cy-dy,cx+dx to cy+dy)
            event(MotionEvent.ACTION_MOVE, points); SystemClock.sleep(20)
        }
        beforeRelease()
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),points)
        event(MotionEvent.ACTION_UP,listOf(points[0]))
    }
    private fun pan(dx: Float) {
        val dm=instrumentation.targetContext.resources.displayMetrics
        val x=dm.widthPixels*.5f; val y=dm.heightPixels*.5f
        event(MotionEvent.ACTION_DOWN,listOf(x to y))
        repeat(12) { event(MotionEvent.ACTION_MOVE,listOf(x+dx*(it+1)/12 to y));SystemClock.sleep(20) }
        event(MotionEvent.ACTION_UP,listOf(x+dx to y)); SystemClock.sleep(350)
    }
    private fun event(action:Int,points:List<Pair<Float,Float>>) {
        if(action==MotionEvent.ACTION_DOWN) downTime=SystemClock.uptimeMillis()
        val properties=Array(points.size) { MotionEvent.PointerProperties().apply { id=it;toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coords=Array(points.size) { MotionEvent.PointerCoords().apply { x=points[it].first;y=points[it].second;pressure=1f;size=1f } }
        MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,points.size,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0).apply {
            instrumentation.sendPointerSync(this);recycle()
        }
    }
}
