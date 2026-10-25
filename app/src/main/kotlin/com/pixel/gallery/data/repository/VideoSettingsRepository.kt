package com.pixel.gallery.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class VideoEndAction { STOP, LOOP, NEXT }
enum class VideoBackgroundAction { PAUSE, AUDIO, PIP }
enum class ViewerScreenDirection { SYSTEM, LOCK_CURRENT, LANDSCAPE, PORTRAIT }

data class ImageViewerSettings(
    val screenDirection: ViewerScreenDirection = ViewerScreenDirection.SYSTEM,
    val rememberDirection: Boolean = false,
    val rememberZoom: Boolean = true,
)

data class SavedViewerZoom(val scale: Float, val centerX: Float, val centerY: Float,
    val baseFitScale: Float = 1f, val sourceWidth: Int = 0, val sourceHeight: Int = 0)

data class VideoSettings(
    val autoplay: Boolean = true,
    val muted: Boolean = false,
    val endAction: VideoEndAction = VideoEndAction.STOP,
    val rememberPosition: Boolean = false,
    val rememberSpeed: Boolean = false,
    val lastSpeed: Float = 1f,
    val backgroundAction: VideoBackgroundAction = VideoBackgroundAction.PAUSE,
    val captureTree: String = "",
    val screenDirection: ViewerScreenDirection = ViewerScreenDirection.SYSTEM,
    val rememberDirection: Boolean = false,
    val rememberZoom: Boolean = false,
)

val VideoPlaybackSpeeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private val Context.videoDataStore by preferencesDataStore("video_playback")

@Singleton
class VideoSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.videoDataStore
    private val history = context.getSharedPreferences("video_positions", Context.MODE_PRIVATE)
    private val viewerHistory = context.getSharedPreferences("viewer_memory", Context.MODE_PRIVATE)
    @Volatile private var videoMemoryPolicy: Pair<Boolean, Boolean>? = null
    @Volatile private var imageMemoryPolicy: Pair<Boolean, Boolean>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val key = stringPreferencesKey("settings")
    private val imageKey = stringPreferencesKey("image_settings")
    private fun decodeImage(value: String?): ImageViewerSettings = runCatching {
        gson.fromJson(value, ImageViewerSettings::class.java) ?: ImageViewerSettings()
    }.getOrDefault(ImageViewerSettings())
    private fun decode(value: String?): VideoSettings = runCatching {
        gson.fromJson(value, VideoSettings::class.java) ?: VideoSettings()
    }.getOrDefault(VideoSettings())

    // Null prevents a cold-start autoplay before the user's preferences have loaded.
    val settings = store.data.map { decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val imageSettings = store.data.map { decodeImage(it[imageKey]) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun update(transform: (VideoSettings) -> VideoSettings) {
        scope.launch { store.edit {
            val value = transform(decode(it[key]))
            videoMemoryPolicy = value.rememberDirection to value.rememberZoom
            if (!value.rememberDirection) clearMemory("video:direction:")
            if (!value.rememberZoom) clearMemory("video:zoom:")
            it[key] = gson.toJson(value)
        } }
    }

    fun updateImage(transform: (ImageViewerSettings) -> ImageViewerSettings) {
        scope.launch { store.edit {
            val value = transform(decodeImage(it[imageKey]))
            imageMemoryPolicy = value.rememberDirection to value.rememberZoom
            if (!value.rememberDirection) clearMemory("image:direction:")
            if (!value.rememberZoom) clearMemory("image:zoom:")
            it[imageKey] = gson.toJson(value)
        } }
    }

    @Synchronized
    private fun clearMemory(prefix: String) {
        val keys = viewerHistory.all.keys.filter { it.startsWith(prefix) }
        if (keys.isNotEmpty()) viewerHistory.edit().also { edit -> keys.forEach(edit::remove) }.apply()
    }

    fun direction(isVideo: Boolean, source: String): Int? =
        viewerHistory.getString("${if (isVideo) "video" else "image"}:direction:$source", null)?.toIntOrNull().takeIf { it in listOf(0, 1, 8, 9) }

    fun saveDirection(isVideo: Boolean, source: String, orientation: Int) {
        val allowed = if (isVideo) settings.value?.rememberDirection == true else imageSettings.value?.rememberDirection == true
        if (allowed) writeMemory("${if (isVideo) "video" else "image"}:direction:$source", orientation.toString())
    }

    fun zoom(isVideo: Boolean, source: String): SavedViewerZoom? = runCatching {
        gson.fromJson(viewerHistory.getString("${if (isVideo) "video" else "image"}:zoom:$source", null), SavedViewerZoom::class.java)
    }.getOrNull()?.takeIf { it.scale.isFinite() && it.scale > 0 && it.centerX.isFinite() && it.centerY.isFinite() }

    fun saveZoom(isVideo: Boolean, source: String, value: SavedViewerZoom) {
        val allowed = if (isVideo) settings.value?.rememberZoom == true else imageSettings.value?.rememberZoom == true
        if (allowed) writeMemory("${if (isVideo) "video" else "image"}:zoom:$source", gson.toJson(value))
    }

    @Synchronized
    private fun writeMemory(key: String, value: String) {
        val video = key.startsWith("video:")
        val policy = (if (video) videoMemoryPolicy else imageMemoryPolicy)
            ?: if (video) ((settings.value?.rememberDirection == true) to (settings.value?.rememberZoom == true))
            else ((imageSettings.value?.rememberDirection == true) to (imageSettings.value?.rememberZoom == true))
        val directionRecord = key.startsWith("${if (video) "video" else "image"}:direction:")
        if (if (directionRecord) !policy.first else !policy.second) return
        if (viewerHistory.getString(key, null) == value) return
        val edit = viewerHistory.edit()
        if (!viewerHistory.contains(key) && viewerHistory.all.size >= 512) {
            viewerHistory.all.keys.take(viewerHistory.all.size - 511).forEach(edit::remove)
        }
        edit.putString(key, value).apply()
    }

    fun position(sourceKey: String): Long = history.getLong(sourceKey, 0L).coerceAtLeast(0L)

    fun savePosition(sourceKey: String, position: Long) {
        val editor = history.edit()
        // Bound history without a timer or per-frame writes.
        if (!history.contains(sourceKey) && history.all.size >= 200) {
            history.all.keys.take(history.all.size - 199).forEach(editor::remove)
        }
        if (position <= 0) editor.remove(sourceKey) else editor.putLong(sourceKey, position)
        editor.apply()
    }
}

@HiltViewModel
class VideoSettingsViewModel @Inject constructor(val repository: VideoSettingsRepository) : ViewModel() {
    val settings = repository.settings
    val imageSettings = repository.imageSettings
    fun update(transform: (VideoSettings) -> VideoSettings) = repository.update(transform)
    fun updateImage(transform: (ImageViewerSettings) -> ImageViewerSettings) = repository.updateImage(transform)
}
