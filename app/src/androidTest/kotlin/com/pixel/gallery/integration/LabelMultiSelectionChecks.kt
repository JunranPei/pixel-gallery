package com.pixel.gallery.integration

import android.content.Context
import androidx.room.Room
import com.pixel.gallery.data.local.GalleryDatabase
import com.pixel.gallery.data.repository.LabelRepository
import com.pixel.gallery.data.repository.LabelUnavailableException
import com.pixel.gallery.model.LabelEditRequest
import com.pixel.gallery.model.commonLabelIds
import kotlinx.coroutines.runBlocking

/** Isolated Room data only; no MediaStore, files, or UI operations. */
internal class LabelMultiSelectionChecks(private val context: Context) {
    fun exercise(): String = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = db.labelDao()
            val repository = LabelRepository(db, dao)
            val one = repository.createLabel("One")
            val two = repository.createLabel("Two")
            val three = repository.createLabel("Three")
            val recipients = setOf(10L, 20L)
            suspend fun tags(id: Long) = dao.getMemberships().filter { it.contentId == id }.map { it.labelId }.toSet()
            suspend fun reset() {
                repository.applyEdits(recipients, LabelEditRequest(mapOf(one to false, two to false, three to false)))
                repository.applyEdits(setOf(10L), LabelEditRequest(mapOf(one to true, two to true)))
                repository.applyEdits(setOf(20L), LabelEditRequest(mapOf(one to true)))
                check(commonLabelIds(recipients, dao.getMemberships()) == setOf(one))
            }
            reset()
            repository.applyEdits(recipients, LabelEditRequest(mapOf(one to false)))
            check(tags(10) == setOf(two) && tags(20).isEmpty())
            reset()
            repository.applyEdits(recipients, LabelEditRequest(mapOf(three to true)))
            check(tags(10) == setOf(one, two, three) && tags(20) == setOf(one, three))
            reset()
            repository.applyEdits(recipients, LabelEditRequest(mapOf(two to true)))
            check(tags(10) == setOf(one, two) && tags(20) == setOf(one, two))
            repository.applyEdits(recipients, LabelEditRequest(mapOf(two to true)))
            check(dao.getMemberships().size == 4)
            repository.applyEdits(recipients, LabelEditRequest(mapOf(two to false)))
            check(tags(10) == setOf(one) && tags(20) == setOf(one))
            val before = dao.getMemberships().toSet()
            repository.applyEdits(emptySet(), LabelEditRequest(mapOf(one to false)))
            check(dao.getMemberships().toSet() == before)
            try {
                repository.applyEdits(recipients, LabelEditRequest(mapOf(one to false, Long.MAX_VALUE to true)))
                error("Deleted label must fail without partial writes")
            } catch (_: LabelUnavailableException) {
                check(dao.getMemberships().toSet() == before)
            }
            "PASS: shared-label display; remove One; add Three; fill Two without duplicates; toggle removal; empty selection; invalid-label rollback"
        } finally {
            db.close()
        }
    }
}
