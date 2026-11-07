package com.pixel.gallery.integration

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.pixel.gallery.MainActivity
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.ui.MainScaffold
import com.pixel.gallery.ui.Screen
import com.pixel.gallery.ui.theme.PixelGalleryTheme
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reproduces the viewer's real failure window: the media source is emptied
 * briefly, the Activity is recreated while it is empty, and the source is
 * restored immediately afterwards. The test observes both the StateFlow and
 * the actual renderer view so an empty intermediate value cannot be hidden by
 * a purely model-level assertion.
 */
internal class ViewerRecreationFlickerChecks(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext

    fun exercise(): String = runBlocking {
        val token = UUID.randomUUID().toString().take(8)
        val relativeFolder = "Pictures/ViewerFlickerProbe_$token/"
        val probe = createProbe(relativeFolder)
        val settings = SettingsRepository(context)
        val observedSizes = Collections.synchronizedList(mutableListOf<Int>())
        var activity: MainActivity? = null
        var recreated: MainActivity? = null
        var collector: Job? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            val model = ViewModelProvider(activity!!)[PhotosViewModel::class.java]
            setViewerContent(activity!!, model, probe.id)
            model.refresh()
            await(15_000) { model.viewerPhotos.value.any { it.contentId == probe.id } }
            awaitRenderer(activity!!)

            collector = launch {
                model.viewerPhotos.collect { observedSizes += it.size }
            }

            // Force the same empty-source window seen during a MediaStore rebuild.
            settings.addExcludedFolder(probe.folderPath)
            delay(60)
            check(model.viewerPhotos.value.isNotEmpty()) {
                "Viewer stream exposed an empty value before Activity recreation"
            }

            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity!!.recreate() }
            // Restore the source immediately after recreation is requested. This
            // keeps the probe inside the same short rebuild window as the real
            // MediaStore callback instead of turning it into a legitimate,
            // long-lived empty library.
            settings.removeExcludedFolder(probe.folderPath)
            recreated = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                as? MainActivity
                ?: error("Activity recreation did not produce a new MainActivity")
            instrumentation.removeMonitor(monitor)

            val recreatedModel = ViewModelProvider(recreated!!)[PhotosViewModel::class.java]
            // Reapply the viewer route explicitly so this probe tests the same
            // retained route even on devices that do not restore Compose state.
            setViewerContent(recreated!!, recreatedModel, probe.id)
            check(recreatedModel.viewerPhotos.value.isNotEmpty()) {
                "Viewer stream was empty immediately after Activity recreation"
            }
            awaitRenderer(recreated!!)

            recreatedModel.refresh()
            await(15_000) { recreatedModel.viewerPhotos.value.any { it.contentId == probe.id } }
            delay(450)
            collector?.cancel()

            val sizes = observedSizes.toList()
            check(sizes.dropWhile { it == 0 }.none { it == 0 }) {
                "Viewer emitted an empty list during the forced rebuild: $sizes"
            }
            "PASS forced empty MediaStore window + Activity recreation: " +
                "viewer remained non-empty, renderer present before/after recreation (frame continuity not tested), samples=${sizes.size}"
        } finally {
            collector?.cancel()
            try {
                settings.removeExcludedFolder(probe.folderPath)
            } catch (_: Exception) {
            }
            instrumentation.runOnMainSync {
                recreated?.finish()
                if (recreated == null) activity?.finish()
            }
            context.contentResolver.delete(probe.uri, null, null)
        }
    }

    private fun setViewerContent(activity: MainActivity, model: PhotosViewModel, id: Long) {
        instrumentation.runOnMainSync {
            activity.setContent {
                PixelGalleryTheme(dynamicColor = false, darkTheme = false) {
                    MainScaffold(
                        initialScreen = Screen.Viewer(id, Screen.ViewerSource.All),
                        photosViewModel = model,
                    )
                }
            }
        }
    }

    private fun awaitRenderer(activity: MainActivity) {
        await(15_000) {
            var found = false
            instrumentation.runOnMainSync {
                fun visit(view: View) {
                    // The probe may be behind Android's permission surface for a
                    // few frames. Existence and layout of the production tile
                    // renderer are the invariant; visibility is measured by the
                    // frame observer after the surface is focused.
                    if ((view is SubsamplingScaleImageView || view is android.widget.ImageView) &&
                        view.width >= 400 && view.height >= 400
                    ) found = true
                    if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                visit(activity.window.decorView)
            }
            found
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Condition did not become true within ${timeoutMs}ms")
    }

    private fun createProbe(relativeFolder: String): Probe {
        val name = "viewer-flicker-probe.jpg"
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ) ?: error("Could not create viewer flicker probe")
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff5577aa.toInt())
        }
        try {
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }
        } finally {
            bitmap.recycle()
        }
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)
        val path = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DATA),
            null,
            null,
            null,
        )!!.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        val folderPath = File(path).parentFile?.path ?: error("Probe folder path missing")
        val scanned = java.util.concurrent.CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg")) { _, _ -> scanned.countDown() }
        check(scanned.await(15, TimeUnit.SECONDS)) { "Probe scan timed out" }
        return Probe(uri, path, folderPath, uri.lastPathSegment!!.toLong())
    }

    private data class Probe(val uri: android.net.Uri, val path: String, val folderPath: String, val id: Long)
}
