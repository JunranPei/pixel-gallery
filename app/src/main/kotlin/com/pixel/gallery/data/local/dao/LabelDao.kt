package com.pixel.gallery.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelDao {
    @Query("SELECT * FROM labels ORDER BY CASE WHEN systemKind IS NULL THEN 1 ELSE 0 END, sortOrder, normalizedName, id")
    fun observeLabels(): Flow<List<LabelEntry>>

    @Query("SELECT * FROM media_labels")
    fun observeMemberships(): Flow<List<MediaLabelEntry>>

    @Query("SELECT EXISTS(SELECT 1 FROM media_labels)")
    fun observeMembershipChanges(): Flow<Int>

    @Query("SELECT * FROM media_labels")
    suspend fun getMemberships(): List<MediaLabelEntry>

    @Query("SELECT * FROM labels ORDER BY sortOrder, id")
    suspend fun getLabels(): List<LabelEntry>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLabel(label: LabelEntry): Long

    @Query("UPDATE labels SET name = :name, normalizedName = :normalizedName WHERE id = :id AND systemKind IS NULL")
    suspend fun renameLabel(id: Long, name: String, normalizedName: String)

    @Query("DELETE FROM labels WHERE id = :id AND systemKind IS NULL")
    suspend fun deleteLabel(id: Long)

    @Query("UPDATE labels SET sortOrder = :sortOrder WHERE id = :id AND systemKind IS NULL")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMemberships(entries: List<MediaLabelEntry>)

    @Query("DELETE FROM media_labels WHERE labelId = :labelId AND contentId IN (:contentIds)")
    suspend fun removeMemberships(labelId: Long, contentIds: List<Long>)
}
