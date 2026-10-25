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

data class VideoSettings(
    val autoplay: Boolean = true,
    val muted: Boolean = false,
    val endAction: VideoEndAction = VideoEndAction.STOP,
    val rememberPosition: Boolean = false,
    val rememberSpeed: Boolean = false,
    val lastSpeed: Float = 1f,
    val backgroundAction: VideoBackgroundAction = VideoBackgroundAction.PAUSE,
    val captureTree: String = "",
)

val VideoPlaybackSpeeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private val Context.videoDataStore by preferencesDataStore("video_playback")

@Singleton
class VideoSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.videoDataStore
    private val history = context.getSharedPreferences("video_positions", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val key = stringPreferencesKey("settings")
    private fun decode(value: String?): VideoSettings = runCatching {
        gson.fromJson(value, VideoSettings::class.java) ?: VideoSettings()
    }.getOrDefault(VideoSettings())

    // Null prevents a cold-start autoplay before the user's preferences have loaded.
    val settings = store.data.map { decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun update(transform: (VideoSettings) -> VideoSettings) {
        scope.launch { store.edit { it[key] = gson.toJson(transform(decode(it[key]))) } }
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
    fun update(transform: (VideoSettings) -> VideoSettings) = repository.update(transform)
}
