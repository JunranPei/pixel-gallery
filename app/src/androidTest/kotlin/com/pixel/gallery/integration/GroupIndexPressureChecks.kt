package com.pixel.gallery.integration

import android.app.Instrumentation
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.google.gson.Gson
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.LABEL_DATABASE_CALLBACK
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.repository.LabelRepository
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.services.MetadataService
import com.pixel.gallery.ui.viewmodel.LabelsViewModel
import com.pixel.gallery.ui.viewmodel.PhotosViewModel
import com.pixel.gallery.ui.viewmodel.SharedLabelCollections
import com.pixel.gallery.utils.GroupWorkProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/** Synthetic library size, actual repository/ViewModels; no renderer or flicker assertion. */
internal class GroupIndexPressureChecks(private val runner: Instrumentation, private val run: String) {
    fun exercise(): String = runBlocking {
        val context = runner.targetContext
        val settings = SettingsRepository(context)
        val original = settings.albumGrouping.first()
        val token = "index-pressure-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$token.jpg").apply { writeBytes(byteArrayOf(1)) }
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .addCallback(LABEL_DATABASE_CALLBACK).build()
        var repository: MediaRepository? = null
        var sharedLabels: SharedLabelCollections? = null
        var stage = "seed"
        val store = ViewModelStore()
        val records = mutableListOf<Map<String, Any>>()
        val count = 131_013
        try {
            // All rows refer to an existing test file so startup housekeeping does not prune them.
            // No scan or decoder is run against these synthetic URIs.
            (1..count).chunked(1000).forEach { ids -> db.mediaDao().insertAll(ids.map { id ->
                MediaEntry(id.toLong(), "content://index-pressure/$id", source.path,
                    "image/jpeg", 100, 100, 0, 1, 1, 1_700_000_000_000L,
                    bestTimestamp = 1_700_000_000_000L - id)
            }) }
            val media = MediaRepository(context, db.mediaDao(), settings).also { repository = it }
            stage = "source"
            withTimeout(60_000) { media.allEntries.first { it.size == count } }
            settings.setCollectionCover(token, "content://index-pressure/1")
            val labelsRepository = LabelRepository(db, db.labelDao(), media.readRefreshes)
            val collections = SharedLabelCollections(labelsRepository, media, settings).also { sharedLabels = it }
            val photoModels = mutableListOf<PhotosViewModel>()
            runner.runOnMainSync {
                repeat(4) { index ->
                    val photos = PhotosViewModel(media, settings, MetadataService(context))
                    // Seed the browsing lifecycle gate without scanning real MediaStore into
                    // the isolated synthetic database. Production transforms remain unchanged.
                    @Suppress("UNCHECKED_CAST")
                    val resumed = PhotosViewModel::class.java.getDeclaredField("resumedState")
                        .apply { isAccessible = true }.get(photos) as MutableStateFlow<Boolean>
                    resumed.value = true
                    photoModels += photos
                    store.put("photos-$index", photos)
                    store.put("labels-$index", LabelsViewModel(labelsRepository, media, settings, context, collections))
                }
            }
            stage = "models"
            withTimeout(60_000) { while (photoModels.any { it.albums.value.isEmpty() }) delay(100) }
            delay(2500)
            GroupWorkProbe.enabled = true
            stage = "measure"
            repeat(12) { index ->
                GroupWorkProbe.reset()
                val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                val gc = Debug.getRuntimeStat("art.gc.gc-count").toLong()
                val cpu = Process.getElapsedCpuTime()
                settings.updateAlbumGrouping { state -> state.copy(collapsedIds =
                    if (index % 2 == 0) state.collapsedIds + token else state.collapsedIds - token) }
                // Same observation window before/after, all asynchronous rebuilding included.
                delay(1500)
                records += mapOf("iteration" to index, "cpuMs" to Process.getElapsedCpuTime() - cpu,
                    "allocatedBytes" to Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated,
                    "gc" to Debug.getRuntimeStat("art.gc.gc-count").toLong() - gc,
                    "heapUsed" to Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                    "work" to GroupWorkProbe.snapshot())
            }
            "Measured 12 grouping-only writes, 131013 synthetic records, 4 production Photos/Labels ViewModels; this measures CPU/allocations, not visible flicker."
        } finally {
            GroupWorkProbe.enabled = false
            File(context.cacheDir, "group-index-$run.json").writeText(Gson().toJson(mapOf(
                "run" to run, "stage" to stage, "loadedRecords" to repository?.allEntries?.value?.size,
                "syntheticRecords" to count, "heapMax" to Runtime.getRuntime().maxMemory(), "records" to records)))
            runner.runOnMainSync { store.clear() }
            sharedLabels?.close()
            repository?.let { repo ->
                (MediaRepository::class.java.getDeclaredField("repositoryScope").apply { isAccessible = true }
                    .get(repo) as CoroutineScope).cancel()
            }
            settings.setCollectionCover(token, null)
            settings.updateAlbumGrouping { original }
            db.close()
            source.delete()
        }
    }
}
