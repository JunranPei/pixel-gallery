package com.pixel.gallery.ui.viewer

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PointF
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pixel.gallery.data.repository.VideoSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID
import java.io.File
import java.util.concurrent.ThreadPoolExecutor

/** Android API model tests; no claim of real device decoding/gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class FixedImageZoomMemoryTest {
    @Before fun captureAndroidDiagnostics() {
        ShadowLog.stream = System.out
        ViewerDiagnosticsInterface.setOpen(RuntimeEnvironment.getApplication(),false)
    }
    @After fun closeDiagnostics() {
        ViewerMemoryDiagnostics.flush()
        ViewerDiagnosticsInterface.setOpen(RuntimeEnvironment.getApplication(),false)
    }
    private val samples = listOf(
        Triple("stable-portrait", 1080, 1440),
        Triple("resets-portrait", 1280, 1920),
        Triple("stable-map", 1630, 1280),
        Triple("resets-song-map", 17008, 18826),
    )

    private fun state(w: Int, h: Int, relative: Float): SubsamplingScaleImageView.ViewState {
        val cls = Class.forName("com.pixel.gallery.ui.viewer.SimpleSubsamplingImageViewKt")
        val method = cls.declaredMethods.single { it.name == "previewTransformState" }
        method.isAccessible = true
        return method.invoke(null, relative, 0f, 0f, 1080, 2340, w, h, 0, null) as SubsamplingScaleImageView.ViewState
    }

    @Test fun realRepositoryRestoresEverySampleWithoutSelectingVictims() {
        val app = RuntimeEnvironment.getApplication()
        ViewerMemoryDiagnostics.setEnabled(app,true)
        val token = UUID.randomUUID().toString()
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$token-$name", mode)
            override fun getApplicationContext(): Context = this
        }
        val repository = VideoSettingsRepository(isolated)
        runBlocking { withTimeout(15000) { repository.imageSettings.first { it != null } } }
        fun fresh() = rememberedImageTransforms(repository, ViewerTransformStateStore()) { true }
        for ((name, w, h) in samples) for (relative in listOf(1.1f, 1.4f, 3f)) {
            val original = state(w, h, relative)
            fresh().save(name, original, "composition-dispose")
            val restored = fresh().get(name)!!
            assertEquals(name, relative, restored.scale / restored.baseFitScale, .00001f)
            assertEquals(w, restored.sourceWidth)
            assertEquals(h, restored.sourceHeight)
        }
    }

    @Test fun actualSsivRestoreAndSnapshotKeepsAllSampleScales() {
        val context = RuntimeEnvironment.getApplication()
        for ((name, w, h) in samples) for (relative in listOf(1.1f, 1.4f, 3f)) {
            val view = SubsamplingScaleImageView(context)
            view.layout(0, 0, 1080, 2340)
            fun field(name: String, value: Any) {
                val f = SubsamplingScaleImageView::class.java.getDeclaredField(name)
                f.isAccessible = true
                f.set(view, value)
            }
            field("sWidth", w); field("sHeight", h); field("isReady", true)
            view.maxScale = 3f
            val saved = state(w, h, relative)
            view.restoreViewState(saved)
            val restored = view.snapshotViewState()!!
            assertEquals(name, relative, restored.scale / restored.baseFitScale, .00001f)
            assertEquals(name, w / 2f, restored.sourceCenter.x, .01f)
            assertEquals(name, h / 2f, restored.sourceCenter.y, .01f)
        }
    }


    @Test fun memoryOnlyRecorderIsBoundedAndReleasedOnStop() {
        val context=RuntimeEnvironment.getApplication()
        assertFalse(ViewerMemoryDiagnostics.hasRecorderForTest())
        assertFalse(ViewerLoadMetrics.isEnabled)
        val prefsBefore=context.getSharedPreferences("viewer_memory",0).all
        val directory=File(context.filesDir,"viewer-memory-diagnostics")
        val current=File(directory,"current.log")
        val previous=File(directory,"previous.log")
        ViewerMemoryDiagnostics.setEnabled(context,true)
        ViewerMemoryDiagnostics.event("RECORDER_TEST","first-marker")
        assertTrue(ViewerMemoryDiagnostics.flush())
        assertTrue(current.readText().contains("first-marker"))
        current.writeText("x".repeat(4*1024*1024))
        ViewerMemoryDiagnostics.event("RECORDER_TEST","second-marker")
        assertTrue(ViewerMemoryDiagnostics.flush())
        assertEquals(4L*1024*1024,previous.length())
        assertTrue(current.length()<4096)
        ViewerMemoryDiagnostics.setEnabled(context,false)
        assertFalse(ViewerMemoryDiagnostics.hasRecorderForTest())
        assertEquals(prefsBefore,context.getSharedPreferences("viewer_memory",0).all)
    }

    @Test fun dynamicInterfaceDoesNotCreateRecorderOrRefreshMediaWhenConnecting() {
        val context=RuntimeEnvironment.getApplication()
        assertFalse(ViewerDiagnosticsInterface.isOpen)
        assertFalse(ViewerMemoryDiagnostics.hasRecorderForTest())
        ViewerDiagnosticsInterface.setOpen(context,true)
        fun command(name:String)=ViewerDiagnosticsInterface.handle(context,android.content.Intent().putExtra("command",name))
        assertFalse(command("status").getBoolean("enabled"))
        assertEquals(2,command("status").getInt("protocol"))
        assertEquals(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName,0)),
            command("status").getLong("versionCode"))
        assertFalse(ViewerMemoryDiagnostics.hasRecorderForTest())
        val memory=context.getSharedPreferences("viewer_memory",0)
        memory.edit().putString("image:zoom:sample","existing-value").commit()
        command("capture")
        assertFalse(ViewerMemoryDiagnostics.hasRecorderForTest())
        val data=ViewerDiagnosticsInterface.handle(context,android.content.Intent().putExtra("command","read")
            .putExtra("resource","snapshot.json").putExtra("offset",0L))
        val text=String(android.util.Base64.decode(data.getString("data"),android.util.Base64.NO_WRAP),Charsets.UTF_8)
        assertEquals("existing-value",org.json.JSONObject(text).getJSONObject("viewerMemory").getString("image:zoom:sample"))
        assertTrue(command("start").getBoolean("enabled"))
        assertFalse(command("stop").getBoolean("enabled"))
        assertEquals("existing-value",memory.getString("image:zoom:sample",null))
        assertThrows(IllegalArgumentException::class.java) {command("refresh")}
        assertThrows(IllegalArgumentException::class.java) {
            ViewerDiagnosticsInterface.handle(context,android.content.Intent().putExtra("command","read").putExtra("resource","../viewer_memory.xml"))
        }
        assertNull(org.robolectric.Shadows.shadowOf(context).nextStartedActivity)
        assertTrue(org.robolectric.Shadows.shadowOf(context).broadcastIntents.none {
            it.action==android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE
        })
        ViewerDiagnosticsInterface.setOpen(context,false)
        assertFalse(ViewerDiagnosticsInterface.isOpen)
        assertFalse(ViewerMemoryDiagnostics.hasRecorderForTest())
        assertThrows(IllegalStateException::class.java) {command("status")}
    }

    @Test fun oneShotWindowInspectionPreservesTransformCacheOrderAndDoesNotDraw() {
        val context=RuntimeEnvironment.getApplication()
        val store=ViewerTransformStateStore()
        val key="media:42:1700000000000"
        store.save(key,state(1280,1920,3f),"preview-gesture")
        store.save("media:43:1700000000000",state(1630,1280,1.4f),"preview-gesture")
        val orderBefore=store.saveableSnapshot()
        val view=object:SubsamplingScaleImageView(context) {
            var draws=0
            override fun invalidate() { draws++;super.invalidate() }
        }
        view.layout(0,0,1080,2340)
        for((name,value) in listOf("sWidth" to 1280,"sHeight" to 1920,"isReady" to true)) {
            val field=SubsamplingScaleImageView::class.java.getDeclaredField(name)
            field.isAccessible=true;field.set(view,value)
        }
        view.maxScale=3f
        view.restoreViewState(state(1280,1920,3f))
        class Callback(val transformStateStore:ViewerTransformStateStore,val transformStateKey:String):SubsamplingScaleImageView.OnImageEventListener {
            var calls=0
            override fun onReady(){calls++}
            override fun onImageDrawn(){calls++}
            override fun onImageLoadError(e:Exception){calls++}
            override fun onImageRotation(degrees:Int){calls++}
            override fun onUpEvent(){calls++}
        }
        val callback=Callback(store,key)
        view.onImageEventListener=callback
        val root=android.widget.FrameLayout(context)
        root.addView(view)
        val before=view.snapshotViewState()!!
        val drawCount=view.draws
        val report=ViewerDiagnosticsSnapshot.inspectRoot(root)
        val tiled=report.getJSONArray("tiles").getJSONObject(0)
        assertEquals(key,tiled.getString("callbackKey"))
        assertEquals(orderBefore.size,tiled.getJSONArray("storeSnapshot").length())
        assertEquals(orderBefore,store.saveableSnapshot())
        assertEquals(before,view.snapshotViewState())
        assertEquals(drawCount,view.draws)
        assertEquals(0,callback.calls)
        assertFalse(ViewerMemoryDiagnostics.hasRecorderForTest())
    }
}
