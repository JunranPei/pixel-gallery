package com.pixel.gallery.data.local.dao

import androidx.room.*
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.MetadataEntry
import com.pixel.gallery.data.local.entity.FavouriteEntry
import com.pixel.gallery.data.local.entity.TrashEntry
import com.pixel.gallery.data.local.entity.VaultEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // --- All Media ---
    @Query("SELECT COUNT(*) FROM media_entries WHERE isTrashed = 0")
    fun observeAllEntriesChanges(): Flow<Int>

    @Query("SELECT * FROM media_entries WHERE isTrashed = 0 AND contentId > :afterContentId ORDER BY contentId LIMIT :limit")
    suspend fun getAllEntriesPage(limit: Int, afterContentId: Long): List<MediaEntry>

    @Transaction
    suspend fun getAllEntriesPaged(): List<MediaEntry> =
        loadAllPages(::getAllEntriesPage, MediaEntry::contentId)
            .sortedWith(compareByDescending<MediaEntry> { it.bestTimestamp }.thenByDescending { it.contentId })

    @Query("SELECT contentId, path, dateModifiedMillis, isTrashed, trashedAtMillis FROM media_entries WHERE contentId > :afterContentId ORDER BY contentId LIMIT :limit")
    suspend fun getKnownEntriesPage(limit: Int, afterContentId: Long): List<KnownEntry>

    @Transaction
    suspend fun getKnownEntries(): List<KnownEntry> =
        loadAllPages(::getKnownEntriesPage, KnownEntry::contentId)

    @Query("SELECT contentId, path FROM media_entries WHERE contentId > :afterContentId ORDER BY contentId LIMIT :limit")
    suspend fun getAllMediaPathsPage(limit: Int, afterContentId: Long): List<MediaPath>

    @Transaction
    suspend fun getAllMediaPaths(): List<MediaPath> =
        loadAllPages(::getAllMediaPathsPage, MediaPath::contentId)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MediaEntry>)

    @Query("DELETE FROM media_entries WHERE contentId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM media_entries WHERE contentId IN (:ids)")
    suspend fun getEntriesByIds(ids: List<Long>): List<MediaEntry>

    @Transaction
    suspend fun reconcileMedia(entries: List<MediaEntry>, obsoleteIds: List<Long>) {
        if (entries.isNotEmpty()) insertAll(entries)
        if (obsoleteIds.isNotEmpty()) deleteByIds(obsoleteIds)
    }

    // --- Favourites ---
    @Query("SELECT COUNT(*) FROM media_entries WHERE isTrashed = 0 AND contentId IN (SELECT contentId FROM media_labels WHERE labelId = 1)")
    fun observeFavouritesChanges(): Flow<Int>

    @Query("SELECT * FROM media_entries WHERE isTrashed = 0 AND contentId > :afterContentId AND contentId IN (SELECT contentId FROM media_labels WHERE labelId = 1) ORDER BY contentId LIMIT :limit")
    suspend fun getFavouritesPage(limit: Int, afterContentId: Long): List<MediaEntry>

    @Transaction
    suspend fun getFavouritesPaged(): List<MediaEntry> =
        loadAllPages(::getFavouritesPage, MediaEntry::contentId)
            .sortedWith(compareByDescending<MediaEntry> { it.bestTimestamp }.thenByDescending { it.contentId })

    suspend fun addFavourite(favourite: FavouriteEntry) = addFavouriteLabel(favourite.id)

    @Query("INSERT OR IGNORE INTO media_labels (labelId, contentId) VALUES (1, :id)")
    suspend fun addFavouriteLabel(id: Long)

    @Query("DELETE FROM media_labels WHERE labelId = 1 AND contentId = :id")
    suspend fun removeFavourite(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM media_labels WHERE labelId = 1 AND contentId = :id)")
    fun isFavourite(id: Long): Flow<Boolean>

    @Query("INSERT OR IGNORE INTO media_labels (labelId, contentId) SELECT labelId, :newId FROM media_labels WHERE contentId = :oldId")
    suspend fun copyMediaLabels(oldId: Long, newId: Long)

    @Query("DELETE FROM media_labels WHERE contentId = :id")
    suspend fun deleteMediaLabels(id: Long)

    @Transaction
    suspend fun remapMediaLabels(oldId: Long, newId: Long) {
        if (oldId == newId) return
        copyMediaLabels(oldId, newId)
        deleteMediaLabels(oldId)
    }

    // --- Trash ---
    @Query("SELECT COUNT(*) FROM media_entries WHERE isTrashed = 1")
    fun observeTrashChanges(): Flow<Int>

    @Query("SELECT * FROM media_entries WHERE isTrashed = 1 AND contentId > :afterContentId ORDER BY contentId LIMIT :limit")
    suspend fun getTrashPage(limit: Int, afterContentId: Long): List<MediaEntry>

    @Transaction
    suspend fun getTrashPaged(): List<MediaEntry> =
        loadAllPages(::getTrashPage, MediaEntry::contentId)
            .sortedWith(compareByDescending<MediaEntry> { it.bestTimestamp }.thenByDescending { it.contentId })

    // --- Vault ---
    @Query("SELECT * FROM vault")
    fun getVaultEntries(): Flow<List<VaultEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVaultEntry(entry: VaultEntry)

    @Query("DELETE FROM vault WHERE id = :id")
    suspend fun deleteVaultEntry(id: Long)

    @Query("SELECT * FROM vault WHERE id = :id")
    suspend fun getVaultEntry(id: Long): VaultEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun moveToTrash(trashEntry: TrashEntry)

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun removeFromTrash(id: Long)

    // --- Metadata ---
    @Query("SELECT * FROM media_metadata WHERE id = :id")
    suspend fun getMetadata(id: Long): MetadataEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(metadata: MetadataEntry)
}

data class KnownEntry(
    val contentId: Long,
    val path: String,
    val dateModifiedMillis: Long,
    val isTrashed: Boolean,
    val trashedAtMillis: Long = 0L,
)

data class MediaPath(
    val contentId: Long,
    val path: String
)

private const val DATABASE_PAGE_SIZE = 256

private suspend fun <T> loadAllPages(
    loadPage: suspend (limit: Int, afterContentId: Long) -> List<T>,
    contentIdOf: (T) -> Long
): List<T> {
    val result = ArrayList<T>()
    var afterContentId = -1L

    while (true) {
        val page = loadPage(DATABASE_PAGE_SIZE, afterContentId)
        result.addAll(page)
        if (page.size < DATABASE_PAGE_SIZE) return result
        afterContentId = contentIdOf(page.last())
    }
}
