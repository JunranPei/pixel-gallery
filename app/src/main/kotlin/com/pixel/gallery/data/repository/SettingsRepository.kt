package com.pixel.gallery.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.pixel.gallery.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import com.pixel.gallery.model.ThemeMode
import com.pixel.gallery.model.Handedness
import com.pixel.gallery.model.QuickSortPreferences
import com.pixel.gallery.model.TransferDestination
import com.pixel.gallery.model.remapAlbumPath
import com.google.gson.Gson

import androidx.datastore.preferences.SharedPreferencesMigration

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
    private val viewerTestPreferences = context.getSharedPreferences(
        "viewer_test_settings",
        Context.MODE_PRIVATE,
    )
    private val mutableLargeImageColdTestMode = MutableStateFlow(
        viewerTestPreferences.getBoolean(
            "large_image_cold_test_mode",
            BuildConfig.LARGE_IMAGE_COLD_TEST_DEFAULT,
        ),
    )
    // Settings are process-scoped. Keeping the resolved visibility state alive
    // lets a recreated Activity reuse the same detail-page inputs immediately.
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val STARTUP_AT_ALBUMS = booleanPreferencesKey("flutter.albums")
    private val SHOW_FULL_ALBUM_NAMES = booleanPreferencesKey("show_full_album_names")
    private val MATERIAL_YOU = booleanPreferencesKey("flutter.material_you")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val HANDEDNESS = stringPreferencesKey("handedness")
    private val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
    private val HIDDEN_FOLDERS = stringSetPreferencesKey("hidden_folders")
    private val HIDDEN_RENAME_PROTECTION = stringSetPreferencesKey("hidden_rename_protection")
    private val GRID_COLUMNS = intPreferencesKey("grid_columns")
    private val ALBUM_GRID_COLUMNS = intPreferencesKey("album_grid_columns")
    private val MOVE_SHEET_COLUMNS = intPreferencesKey("move_sheet_columns")
    private val LABEL_SHEET_COLUMNS = intPreferencesKey("label_sheet_columns")
    private val QUICK_SORT_SHEET_COLUMNS = intPreferencesKey("quick_sort_sheet_columns")
    private val PHOTO_SORT_ORDER = stringPreferencesKey("photo_sort_order")
    private val TRASH_SORT_ORDER = stringPreferencesKey("trash_sort_order")
    private val ALBUM_SORT_ORDER = stringPreferencesKey("album_sort_order")
    private val LABEL_SORT_ORDER = stringPreferencesKey("label_sort_order")
    private val RECENT_LABEL_IDS = stringPreferencesKey("recent_label_ids")
    private val GLIDE_THREAD_COUNT = intPreferencesKey("glide_thread_count")
    private val GLIDE_CACHE_SIZE = intPreferencesKey("glide_cache_size")
    private val QUICK_SORT_TARGETS = stringPreferencesKey("quick_sort_targets")
    private val QUICK_SORT_DOCK_TOP = booleanPreferencesKey("quick_sort_dock_top")
    private val QUICK_SORT_ALBUM_ROWS = intPreferencesKey("quick_sort_album_rows")

    val quickSortPreferences: Flow<QuickSortPreferences> = context.dataStore.data.map { preferences ->
        val targets = runCatching {
            Gson().fromJson(preferences[QUICK_SORT_TARGETS] ?: "[]", Array<TransferDestination>::class.java)
                .orEmpty().filter { it.path.isNotBlank() && it.stableKey.isNotBlank() }
                .distinctBy { it.stableKey }
        }.getOrDefault(emptyList())
        QuickSortPreferences(targets, preferences[QUICK_SORT_DOCK_TOP] ?: false,
            (preferences[QUICK_SORT_ALBUM_ROWS] ?: 2).coerceIn(1, 4))
    }

    suspend fun setQuickSortTargets(targets: List<TransferDestination>) {
        context.dataStore.edit { preferences ->
            preferences[QUICK_SORT_TARGETS] = Gson().toJson(targets.distinctBy { it.stableKey })
        }
    }

    suspend fun setQuickSortDockTop(top: Boolean) {
        context.dataStore.edit { it[QUICK_SORT_DOCK_TOP] = top }
    }

    suspend fun setQuickSortAlbumRows(rows: Int) {
        context.dataStore.edit { it[QUICK_SORT_ALBUM_ROWS] = rows.coerceIn(1, 4) }
    }

    suspend fun remapAlbumPaths(
        source: String,
        target: String,
        renameProtectionPaths: Set<String> = emptySet(),
    ) {
        context.dataStore.edit { preferences ->
            listOf(EXCLUDED_FOLDERS, HIDDEN_FOLDERS).forEach { key ->
                preferences[key]?.let { paths ->
                    preferences[key] = paths.map { remapAlbumPath(it, source, target) }.toSet()
                }
            }
            val targets = Gson().fromJson(preferences[QUICK_SORT_TARGETS] ?: "[]", Array<TransferDestination>::class.java).orEmpty()
            preferences[QUICK_SORT_TARGETS] = Gson().toJson(targets.map { destination ->
                val path = remapAlbumPath(destination.path, source, target)
                if (path == destination.path) destination else destination.copy(
                    path = path,
                    stableKey = remapAlbumPath(destination.stableKey, source, target),
                    displayName = java.io.File(path).name,
                )
            })
            // Remove this attempt's guard in the same edit that publishes the
            // new user roots. Older guards follow the move and remain protected.
            val protection = preferences[HIDDEN_RENAME_PROTECTION] ?: emptySet()
            preferences[HIDDEN_RENAME_PROTECTION] = (protection - renameProtectionPaths)
                .map { remapAlbumPath(it, source, target) }.toSet()
        }
    }

    /** Keep both locations hidden until the media and settings paths have settled. */
    internal suspend fun protectHiddenAlbumRename(source: String, target: String): Set<String> {
        var addedPaths = emptySet<String>()
        context.dataStore.edit { preferences ->
            val roots = preferences[HIDDEN_FOLDERS] ?: emptySet()
            val protection = preferences[HIDDEN_RENAME_PROTECTION] ?: emptySet()
            val requiredPaths = (roots + protection).flatMap { path ->
                val renamed = remapAlbumPath(path, source, target)
                if (renamed == path) emptyList() else listOf(path, renamed)
            }.toSet()
            addedPaths = requiredPaths - protection
            if (addedPaths.isNotEmpty()) {
                preferences[HIDDEN_RENAME_PROTECTION] = protection + addedPaths
            }
        }
        return addedPaths
    }

    internal suspend fun clearHiddenAlbumRenameProtection(paths: Set<String>) {
        if (paths.isEmpty()) return
        context.dataStore.edit { preferences ->
            val protection = preferences[HIDDEN_RENAME_PROTECTION] ?: emptySet()
            preferences[HIDDEN_RENAME_PROTECTION] = protection - paths
        }
    }

    val startupAtAlbums: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[STARTUP_AT_ALBUMS] ?: false }

    val showFullAlbumNames: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SHOW_FULL_ALBUM_NAMES] ?: true }

    suspend fun setShowFullAlbumNames(value: Boolean) {
        context.dataStore.edit { it[SHOW_FULL_ALBUM_NAMES] = value }
    }

    val materialYou: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[MATERIAL_YOU] ?: true }

    val themeMode: Flow<ThemeMode> = context.dataStore.data
        .map { preferences ->
            when (preferences[THEME_MODE]) {
                ThemeMode.LIGHT.name -> ThemeMode.LIGHT
                ThemeMode.DARK.name -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
        }

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

    suspend fun setThemeMode(value: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE] = value.name
        }
    }

    val handedness: Flow<Handedness> = context.dataStore.data.map {
        if (it[HANDEDNESS] == Handedness.LEFT.name) Handedness.LEFT else Handedness.RIGHT
    }

    suspend fun setHandedness(value: Handedness) {
        context.dataStore.edit { it[HANDEDNESS] = value.name }
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

    val hiddenFolderRoots: Flow<Set<String>> = context.dataStore.data
        .map { preferences -> preferences[HIDDEN_FOLDERS] ?: emptySet() }

    /** Visibility includes in-flight protection; the Hidden grid uses saved roots only. */
    val hiddenFolders: StateFlow<Set<String>> = context.dataStore.data.map { preferences ->
        (preferences[HIDDEN_FOLDERS] ?: emptySet()) +
            (preferences[HIDDEN_RENAME_PROTECTION] ?: emptySet())
    }.stateIn(settingsScope, SharingStarted.Eagerly, emptySet())

    suspend fun addHiddenFolder(path: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[HIDDEN_FOLDERS] ?: emptySet()
            preferences[HIDDEN_FOLDERS] = current + path
        }
    }

    suspend fun removeHiddenFolder(path: String) {
        removeHiddenFolders(setOf(path))
    }

    suspend fun removeHiddenFolders(paths: Set<String>) {
        context.dataStore.edit { preferences ->
            val current = preferences[HIDDEN_FOLDERS] ?: emptySet()
            preferences[HIDDEN_FOLDERS] = current - paths
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

    val moveSheetColumns: Flow<Int> = context.dataStore.data
        .map { preferences -> (preferences[MOVE_SHEET_COLUMNS] ?: 3).coerceIn(2, 6) }

    suspend fun setMoveSheetColumns(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[MOVE_SHEET_COLUMNS] = value.coerceIn(2, 6)
        }
    }

    val labelSheetColumns: Flow<Int> = context.dataStore.data
        .map { preferences -> (preferences[LABEL_SHEET_COLUMNS] ?: 3).coerceIn(2, 6) }

    suspend fun setLabelSheetColumns(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[LABEL_SHEET_COLUMNS] = value.coerceIn(2, 6)
        }
    }

    val quickSortSheetColumns: Flow<Int> = context.dataStore.data
        .map { preferences -> (preferences[QUICK_SORT_SHEET_COLUMNS] ?: 3).coerceIn(2, 6) }

    suspend fun setQuickSortSheetColumns(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[QUICK_SORT_SHEET_COLUMNS] = value.coerceIn(2, 6)
        }
    }

    val photoSortOrder: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[PHOTO_SORT_ORDER] ?: "DATE_DESC" }

    suspend fun setPhotoSortOrder(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PHOTO_SORT_ORDER] = value
        }
    }

    val trashSortOrder: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[TRASH_SORT_ORDER] ?: "FILE_DATE_DESC" }

    suspend fun setTrashSortOrder(value: String) {
        context.dataStore.edit { preferences -> preferences[TRASH_SORT_ORDER] = value }
    }

    val albumSortOrder: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[ALBUM_SORT_ORDER] ?: "NAME_ASC" }

    suspend fun setAlbumSortOrder(value: String) {
        context.dataStore.edit { preferences ->
            preferences[ALBUM_SORT_ORDER] = value
        }
    }

    val labelSortOrder: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[LABEL_SORT_ORDER] ?: "NAME_ASC" }

    suspend fun setLabelSortOrder(value: String) {
        context.dataStore.edit { preferences -> preferences[LABEL_SORT_ORDER] = value }
    }

    val recentLabelIds: Flow<List<Long>> = context.dataStore.data.map { preferences ->
        runCatching {
            Gson().fromJson(preferences[RECENT_LABEL_IDS] ?: "[]", Array<Long>::class.java).orEmpty().toList()
        }.getOrDefault(emptyList())
    }

    suspend fun recordRecentLabelIds(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { preferences ->
            val current = runCatching {
                Gson().fromJson(preferences[RECENT_LABEL_IDS] ?: "[]", Array<Long>::class.java).orEmpty().toList()
            }.getOrDefault(emptyList())
            preferences[RECENT_LABEL_IDS] = Gson().toJson((ids.toList() + current).distinct().take(20))
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

    val largeImageColdTestMode: StateFlow<Boolean> = mutableLargeImageColdTestMode.asStateFlow()

    suspend fun setLargeImageColdTestMode(value: Boolean) {
        viewerTestPreferences.edit().putBoolean("large_image_cold_test_mode", value).apply()
        mutableLargeImageColdTestMode.value = value
    }
}
