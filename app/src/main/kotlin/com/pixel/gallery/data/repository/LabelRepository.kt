package com.pixel.gallery.data.repository

import androidx.room.withTransaction
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.local.dao.LabelDao
import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.isValidLabelName
import com.pixel.gallery.model.normalizeLabelName
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

class InvalidLabelNameException : IllegalArgumentException()
class DuplicateLabelNameException : IllegalArgumentException()
class LabelUnavailableException : IllegalStateException()
class LabelMediaUnavailableException(message: String = "The selected media is no longer available") : IllegalStateException(message)

@Singleton
class LabelRepository @Inject constructor(
    private val database: GalleryDatabase,
    private val dao: LabelDao,
    private val readRefreshes: MediaReadRefreshCoordinator = MediaReadRefreshCoordinator(),
) {
    val labels = dao.observeLabels()
    val memberships = readRefreshes.refreshes(dao.observeMembershipChanges()).map { dao.getMemberships() }

    suspend fun createLabel(name: String): Long = database.withTransaction {
        insertLabel(name, dao.getLabels())
    }

    suspend fun renameLabel(id: Long, name: String) = database.withTransaction {
        val current = dao.getLabels()
        val label = current.find { it.id == id } ?: throw LabelUnavailableException()
        if (label.isSystem) throw LabelUnavailableException()
        validateName(name, current.filter { it.id != id })
        dao.renameLabel(id, name.trim(), normalizeLabelName(name))
    }

    suspend fun deleteLabel(id: Long) = dao.deleteLabel(id)
    suspend fun deleteLabels(ids: Set<Long>) = database.withTransaction {
        ids.forEach { dao.deleteLabel(it) }
    }

    suspend fun reorderLabels(orderedIds: List<Long>) = database.withTransaction {
        orderedIds.distinct().forEachIndexed { index, id -> dao.updateSortOrder(id, index + 1) }
    }

    suspend fun applyEdits(contentIds: Set<Long>, request: LabelEditRequest): Set<Long> = database.withTransaction {
        if (contentIds.isEmpty()) return@withTransaction emptySet()
        val current = dao.getLabels().toMutableList()
        val touched = request.changes.keys.toMutableSet()
        // Validate before mutating, including labels removed while the sheet was open.
        if (request.changes.keys.any { id -> current.none { it.id == id } }) {
            throw LabelUnavailableException()
        }
        request.changes.forEach { (labelId, add) ->
            contentIds.chunked(500).forEach { chunk ->
                if (add) dao.addMemberships(chunk.map { MediaLabelEntry(labelId, it) })
                else dao.removeMemberships(labelId, chunk)
            }
        }
        request.newLabelNames.forEach { name ->
            val id = insertLabel(name, current)
            touched += id
            current += LabelEntry(id, name.trim(), normalizeLabelName(name))
            contentIds.chunked(500).forEach { chunk ->
                dao.addMemberships(chunk.map { MediaLabelEntry(id, it) })
            }
        }
        touched
    }.also { readRefreshes.publish() }

    private suspend fun insertLabel(name: String, current: List<LabelEntry>): Long {
        validateName(name, current)
        return dao.insertLabel(LabelEntry(
            name = name.trim(),
            normalizedName = normalizeLabelName(name),
            sortOrder = (current.maxOfOrNull { it.sortOrder } ?: 0) + 1
        ))
    }

    private fun validateName(name: String, current: List<LabelEntry>) {
        if (!isValidLabelName(name)) throw InvalidLabelNameException()
        if (current.any { it.normalizedName == normalizeLabelName(name) }) throw DuplicateLabelNameException()
    }
}
