package com.pixel.gallery.integration

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.lifecycle.ViewModelProvider
import com.pixel.gallery.MainActivity
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Real app screens/decoders. Only this test runner polls; it is identical in both builds. */
class ListenerAcceptanceInstrumentation : Instrumentation() {
    private val windows=mutableListOf<Activity>()
    private val life=AtomicLong()
    private val draws=AtomicLong()
    private val gson=Gson()
    private var count=24
    private fun out(name:String,value:Any) { File(targetContext.filesDir,name).writeText(gson.toJson(value)) }
    override fun onCreate(arguments:Bundle?) { count=arguments?.getString("windows")?.toInt() ?: 24; super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            setup()
            out("qa-status.json",mapOf("state" to "ready","pid" to android.os.Process.myPid(),"windows" to windows.size))
            val command=File(targetContext.filesDir,"qa-command")
            var running=true
            val deadline=SystemClock.elapsedRealtime()+30*60*1000
            while(running && SystemClock.elapsedRealtime()<deadline) {
                if(command.isFile) {
                    val text=command.readText().trim();command.delete()
                    val action=text.substringBefore(':');val label=text.substringAfter(':',action)
                    when(action) {
                        "sample" -> out("qa-$label.json",sample())
                        "open","close" -> {
                            runOnMainSync {
                                val cls=Class.forName("com.pixel.gallery.ui.viewer.ViewerDiagnosticsInterface")
                                cls.getDeclaredMethod("setOpen",android.content.Context::class.java,Boolean::class.javaPrimitiveType)
                                    .invoke(cls.getField("INSTANCE").get(null),targetContext,action=="open")
                            }
                        }
                        "exercise" -> out("qa-$label.json",exercise())
                        "nodes" -> out("qa-$label.json",nodes())
                        "click" -> check(click(label)) { "Missing UI $label" }
                        "back" -> uiAutomation.performGlobalAction(1)
                        "finish" -> running=false
                        else -> error("Unknown test command")
                    }
                    out("qa-status.json",mapOf("state" to "ready","done" to text))
                }
                SystemClock.sleep(100)
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","PASS listener device acceptance\n") })
        } catch(t:Throwable) {
            out("qa-status.json",mapOf("state" to "failed","error" to t.stackTraceToString()))
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream",t.stackTraceToString()) })
        }
    }
    private fun walk(v:View, action:(View)->Unit) { action(v);if(v is ViewGroup) repeat(v.childCount) { walk(v.getChildAt(it),action) } }
    private fun setup() {
        val app=targetContext.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,b:Bundle?) {life.incrementAndGet()}
            override fun onActivityStarted(a:Activity) {life.incrementAndGet()}
            override fun onActivityResumed(a:Activity) {life.incrementAndGet()}
            override fun onActivityPaused(a:Activity) {life.incrementAndGet()}
            override fun onActivityStopped(a:Activity) {life.incrementAndGet()}
            override fun onActivityDestroyed(a:Activity) {life.incrementAndGet()}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle) {life.incrementAndGet()}
        })
        val bitmap=Bitmap.createBitmap(4096,3072,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val paint=Paint()
        repeat(96) { i -> paint.color=0xff000000.toInt() or (i*190003);canvas.drawRect(0f,i*32f,4096f,(i+1)*32f,paint) }
        val bytes=java.io.ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG,95,this) }.toByteArray()
        bitmap.recycle()
        val uris=(0 until count).map { i ->
            val relative="Pictures/ListenerAcceptance/fixtures/"
            val existing=targetContext.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf("_id"),"relative_path=? AND _display_name=?",arrayOf(relative,"Listener QA $i.jpg"),null)?.use {
                if(it.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it.getLong(0)) else null
            }
            if(existing!=null) return@map existing
            val values=ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,"Listener QA $i.jpg")
                put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,relative)
                put(MediaStore.Images.Media.IS_PENDING,1)
            }
            val uri=targetContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
            targetContext.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            targetContext.contentResolver.update(uri,ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            uri
        }
        for((index,uri) in uris.withIndex()) {
            uiAutomation.performGlobalAction(2);SystemClock.sleep(150)
            val a=startActivitySync(Intent(targetContext,MainActivity::class.java)
                .setData(android.net.Uri.parse("listener-qa://grid/$index"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            windows+=a
            runOnMainSync {
                a.window.decorView.viewTreeObserver.addOnDrawListener {draws.incrementAndGet()}
                @Suppress("UNCHECKED_CAST")
                (MainActivity::class.java.getDeclaredField("showNotificationAccess").apply{isAccessible=true}.get(a) as androidx.compose.runtime.MutableState<Boolean>).value=false
            }
            var entered=false
            for(attempt in 0 until 120) {
                if(clickThumbnail(index)) {entered=true;break}
                click("Got it");click("Cancel");SystemClock.sleep(100)
            }
            check(entered) {"No actual photo thumbnail in grid"}
            for(attempt in 0 until 200) {
                var ready=false
                runOnMainSync { walk(a.window.decorView) { if(it is SubsamplingScaleImageView && it.snapshotViewState()!=null || it is ImageView && (it.drawable?.intrinsicWidth ?: 0)>=1024 && it.width>400) ready=true } }
                if(ready) break
                click("取消"); SystemClock.sleep(50)
            }
            SystemClock.sleep(1000)
            // Double-tap through the real input dispatch path, including the preview-to-tiles handoff.
            val size=android.graphics.Point();a.windowManager.defaultDisplay.getRealSize(size)
            val x=size.x*.5f;val y=size.y*.5f
            repeat(2) {
                val now=SystemClock.uptimeMillis()
                sendPointerSync(android.view.MotionEvent.obtain(now,now,0,x,y,0).apply{source=android.view.InputDevice.SOURCE_TOUCHSCREEN})
                SystemClock.sleep(30)
                sendPointerSync(android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),1,x,y,0).apply{source=android.view.InputDevice.SOURCE_TOUCHSCREEN})
                SystemClock.sleep(90)
            }
            SystemClock.sleep(1500)
            var zoomed=false
            runOnMainSync {walk(a.window.decorView) {
                if(it is ImageView && it.width>400 && it.scaleX>1.05f)zoomed=true
                if(it is SubsamplingScaleImageView)it.snapshotViewState()?.let{s->if(s.scale/s.baseFitScale>1.05f)zoomed=true}
            }}
            check(zoomed) { "Double tap did not zoom real image in task ${a.taskId}" }
        }
        check(windows.map{it.taskId}.distinct().size==count)
        SystemClock.sleep(5000)
    }
    private fun clickThumbnail(index:Int):Boolean {
        val matches=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo) {
            val r=android.graphics.Rect();n.getBoundsInScreen(r)
            if(n.isClickable && n.isVisibleToUser && r.width() in 150..500 && r.height() in 150..500 && kotlin.math.abs(r.width()-r.height())<30) matches+=n
            repeat(n.childCount){n.getChild(it)?.let(::walk)}
        }
        uiAutomation.clearCache();uiAutomation.rootInActiveWindow?.let(::walk)
        if(matches.isEmpty())return false
        return matches[index%matches.size].performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun field(target:Any,name:String):Any? = target.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(target)
    private fun sample():Map<String,Any?> {
        val states=mutableListOf<Map<String,Any?>>()
        runOnMainSync {
            for(a in windows) {
                val model=ViewModelProvider(a as MainActivity)[PhotosViewModel::class.java]
                val repo=field(model,"repository")!!
                val flow=field(field(repo,"readRefreshes")!!,"window") as kotlinx.coroutines.flow.StateFlow<*>
                val sync=field(model,"syncJob")
                val tiles=mutableListOf<Map<String,Any?>>()
                val images=mutableListOf<Map<String,Any?>>()
                walk(a.window.decorView) {v ->
                    if(v is SubsamplingScaleImageView) {
                        val s=v.snapshotViewState()
                        tiles+=mapOf("view" to System.identityHashCode(v),"scale" to s?.scale,"base" to s?.baseFitScale,"x" to s?.sourceCenter?.x,"y" to s?.sourceCenter?.y)
                    }
                    if(v is ImageView && v.width>400) images+=mapOf("view" to System.identityHashCode(v),"sx" to v.scaleX,"sy" to v.scaleY,"tx" to v.translationX,"ty" to v.translationY,"drawable" to System.identityHashCode(v.drawable))
                }
                states+=mapOf("task" to a.taskId,"activity" to System.identityHashCode(a),"root" to System.identityHashCode(a.window.decorView),"destroyed" to a.isDestroyed,"refreshWindow" to flow.value.toString(),"syncJob" to System.identityHashCode(sync),"syncActive" to (sync as? kotlinx.coroutines.Job)?.isActive,"tiles" to tiles,"images" to images)
            }
        }
        val mem=Debug.MemoryInfo();Debug.getMemoryInfo(mem)
        val threads=File("/proc/self/task").listFiles()!!.mapNotNull {runCatching{File(it,"comm").readText().trim()}.getOrNull()}
        return mapOf("pid" to android.os.Process.myPid(),"at" to SystemClock.elapsedRealtime(),"lifecycle" to life.get(),"draws" to draws.get(),"windows" to states,
            "memory" to targetContext.getSharedPreferences("viewer_memory",0).all,
            "pssKiB" to mem.totalPss,"javaUsed" to Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(),"nativeUsed" to Debug.getNativeHeapAllocatedSize(),
            "threads" to threads,"allocated" to Debug.getRuntimeStat("art.gc.bytes-allocated"),"cpu" to File("/proc/self/stat").readText(),
            "diagnosticBytes" to File(targetContext.filesDir,"viewer-memory-diagnostics").walkTopDown().filter{it.isFile}.sumOf{it.length()})
    }
    private fun exercise():Map<String,Any?> {
        val times=mutableListOf<Long>()
        for(a in windows) {
            val start=SystemClock.elapsedRealtime()
            uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.REORDER_TASKS)
            try { targetContext.getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(a.taskId,0) }
            finally {uiAutomation.dropShellPermissionIdentity()}
            repeat(200) {if(!a.hasWindowFocus())SystemClock.sleep(10)}
            check(a.hasWindowFocus() && !a.isDestroyed)
            waitForIdleSync();times+=SystemClock.elapsedRealtime()-start
            SystemClock.sleep(200)
        }
        return mapOf("returnMillis" to times,"sample" to sample())
    }
    private fun nodes():List<String> {
        val values=mutableListOf<String>()
        fun walk(n:AccessibilityNodeInfo) {values+="${n.text}|${n.contentDescription}";repeat(n.childCount){n.getChild(it)?.let(::walk)}}
        uiAutomation.clearCache();uiAutomation.rootInActiveWindow?.let(::walk);return values
    }
    private fun click(text:String):Boolean {
        fun find(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(n.text?.toString()==text || n.contentDescription?.toString()==text)return n
            repeat(n.childCount){n.getChild(it)?.let{c->find(c)?.let{return it}}};return null
        }
        uiAutomation.clearCache()
        var n=uiAutomation.rootInActiveWindow?.let(::find) ?: return false
        while(!n.isClickable) n=n.parent ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
