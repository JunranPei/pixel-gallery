package com.pixel.gallery.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.preferencesDataStore

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(context, "FlutterSharedPreferences")
        )
    }
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val STARTUP_AT_ALBUMS = booleanPreferencesKey("flutter.albums")
    private val MATERIAL_YOU = booleanPreferencesKey("flutter.material_you")
    private val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
    private val HIDDEN_FOLDERS = stringSetPreferencesKey("hidden_folders")
    private val GRID_COLUMNS = intPreferencesKey("grid_columns")
    private val ALBUM_GRID_COLUMNS = intPreferencesKey("album_grid_columns")
    private val PHOTO_SORT_ORDER = stringPreferencesKey("photo_sort_order")
    private val ALBUM_SORT_ORDER = stringPreferencesKey("album_sort_order")
    private val GLIDE_THREAD_COUNT = intPreferencesKey("glide_thread_count")
    private val GLIDE_CACHE_SIZE = intPreferencesKey("glide_cache_size")

    val startupAtAlbums: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[STARTUP_AT_ALBUMS] ?: false }

    val materialYou: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[MATERIAL_YOU] ?: true }

    suspend fun setStartupAtAlbums(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STARTUP_AT_ALBUMS] = value
        }
    }

    suspend fun setMaterialYou(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MATERIAL_YOU] = value
        }
    }

    val excludedFolders: Flow<Set<String>> = context.dataStore.data
        .map { preferences -> preferences[EXCLUDED_FOLDERS] ?: emptySet() }

    suspend fun addExcludedFolder(path: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[EXCLUDED_FOLDERS] ?: emptySet()
            preferences[EXCLUDED_FOLDERS] = current + path
        }
    }

    suspend fun removeExcludedFolder(path: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[EXCLUDED_FOLDERS] ?: emptySet()
            preferences[EXCLUDED_FOLDERS] = current - path
        }
    }

    val hiddenFolders: Flow<Set<String>> = context.dataStore.data
        .map { preferences -> preferences[HIDDEN_FOLDERS] ?: emptySet() }

    suspend fun addHiddenFolder(path: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[HIDDEN_FOLDERS] ?: emptySet()
            preferences[HIDDEN_FOLDERS] = current + path
        }
    }

    suspend fun removeHiddenFolder(path: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[HIDDEN_FOLDERS] ?: emptySet()
            preferences[HIDDEN_FOLDERS] = current - path
        }
    }

    val gridColumns: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[GRID_COLUMNS] ?: 3 }

    suspend fun setGridColumns(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[GRID_COLUMNS] = value
        }
    }

    val albumGridColumns: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[ALBUM_GRID_COLUMNS] ?: 2 }

    suspend fun setAlbumGridColumns(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[ALBUM_GRID_COLUMNS] = value
        }
    }

    val photoSortOrder: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[PHOTO_SORT_ORDER] ?: "DATE_DESC" }

    suspend fun setPhotoSortOrder(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PHOTO_SORT_ORDER] = value
        }
    }

    val albumSortOrder: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[ALBUM_SORT_ORDER] ?: "NAME_ASC" }

    suspend fun setAlbumSortOrder(value: String) {
        context.dataStore.edit { preferences ->
            preferences[ALBUM_SORT_ORDER] = value
        }
    }

    private val GLIDE_PERSISTENT_CACHE_SIZE = intPreferencesKey("glide_persistent_cache_size")

    val glideThreadCount: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[GLIDE_THREAD_COUNT] ?: 2 }

    suspend fun setGlideThreadCount(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[GLIDE_THREAD_COUNT] = value
        }
    }

    val glideCacheSize: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[GLIDE_CACHE_SIZE] ?: 250 }

    suspend fun setGlideCacheSize(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[GLIDE_CACHE_SIZE] = value
        }
    }

    val glidePersistentCacheSize: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[GLIDE_PERSISTENT_CACHE_SIZE] ?: 250 }

    suspend fun setGlidePersistentCacheSize(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[GLIDE_PERSISTENT_CACHE_SIZE] = value
        }
    }

    private val GLIDE_PERSISTENT_GRID_CACHE_SIZE = intPreferencesKey("glide_persistent_grid_cache_size")
    private val GLIDE_PERSISTENT_VIEWER_CACHE_SIZE = intPreferencesKey("glide_persistent_viewer_cache_size")

    val glidePersistentGridCacheSize: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[GLIDE_PERSISTENT_GRID_CACHE_SIZE] ?: 250 }

    suspend fun setGlidePersistentGridCacheSize(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[GLIDE_PERSISTENT_GRID_CACHE_SIZE] = value
        }
    }

    val glidePersistentViewerCacheSize: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[GLIDE_PERSISTENT_VIEWER_CACHE_SIZE] ?: 250 }

    suspend fun setGlidePersistentViewerCacheSize(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[GLIDE_PERSISTENT_VIEWER_CACHE_SIZE] = value
        }
    }

    private val LAST_SYNCED_GENERATION = longPreferencesKey("last_synced_generation")

    val lastSyncedGeneration: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_SYNCED_GENERATION] ?: 0L }

    suspend fun setLastSyncedGeneration(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SYNCED_GENERATION] = value
        }
    }

    private val CUSTOM_SHORTCUTS = stringPreferencesKey("custom_shortcuts")

    val customShortcuts: Flow<List<com.pixel.gallery.utils.CustomShortcut>> = context.dataStore.data
        .map { preferences ->
            val json = preferences[CUSTOM_SHORTCUTS] ?: "[]"
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<com.pixel.gallery.utils.CustomShortcut>>() {}.type
                com.google.gson.Gson().fromJson<List<com.pixel.gallery.utils.CustomShortcut>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    suspend fun addCustomShortcut(shortcut: com.pixel.gallery.utils.CustomShortcut) {
        context.dataStore.edit { preferences ->
            val json = preferences[CUSTOM_SHORTCUTS] ?: "[]"
            val type = object : com.google.gson.reflect.TypeToken<List<com.pixel.gallery.utils.CustomShortcut>>() {}.type
            val currentList = try {
                com.google.gson.Gson().fromJson<List<com.pixel.gallery.utils.CustomShortcut>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            currentList.removeAll { it.id == shortcut.id }
            currentList.add(shortcut)

            preferences[CUSTOM_SHORTCUTS] = com.google.gson.Gson().toJson(currentList)
        }
    }

    suspend fun removeCustomShortcut(id: String) {
        context.dataStore.edit { preferences ->
            val json = preferences[CUSTOM_SHORTCUTS] ?: "[]"
            val type = object : com.google.gson.reflect.TypeToken<List<com.pixel.gallery.utils.CustomShortcut>>() {}.type
            val currentList = try {
                com.google.gson.Gson().fromJson<List<com.pixel.gallery.utils.CustomShortcut>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }.toMutableList()

            currentList.removeAll { it.id == id }
            preferences[CUSTOM_SHORTCUTS] = com.google.gson.Gson().toJson(currentList)
        }
    }

    private val LARGE_IMAGE_TILE_SIZE = intPreferencesKey("large_image_tile_size")
    private val LARGE_IMAGE_MAX_CORES = intPreferencesKey("large_image_max_cores")
    private val LARGE_IMAGE_DEBOUNCE_MS = intPreferencesKey("large_image_debounce_ms")
    private val LARGE_IMAGE_HARDWARE_BITMAP = booleanPreferencesKey("large_image_hardware_bitmap")

    val largeImageTileSize: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[LARGE_IMAGE_TILE_SIZE] ?: 1024 }

    suspend fun setLargeImageTileSize(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[LARGE_IMAGE_TILE_SIZE] = value
        }
    }

    val largeImageMaxCores: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[LARGE_IMAGE_MAX_CORES] ?: 4 }

    suspend fun setLargeImageMaxCores(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[LARGE_IMAGE_MAX_CORES] = value
        }
    }

    val largeImageDebounceMs: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[LARGE_IMAGE_DEBOUNCE_MS] ?: 150 }

    suspend fun setLargeImageDebounceMs(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[LARGE_IMAGE_DEBOUNCE_MS] = value
        }
    }

    val largeImageHardwareBitmap: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[LARGE_IMAGE_HARDWARE_BITMAP] ?: false }

    suspend fun setLargeImageHardwareBitmap(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LARGE_IMAGE_HARDWARE_BITMAP] = value
        }
    }
}
