package com.pixel.gallery.model

import org.junit.Assert.*
import org.junit.Test

class LabelBackupTest {
    private val hash = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun file(key: String = "file", path: String = "Pictures/one.jpg", labels: List<String> = listOf("trip")) =
        BackupMedia(key, hash, 100, path.substringAfterLast('/'), path, labels)
    private fun backup(vararg files: BackupMedia) = LabelBackup(exportedAt = "2026-10-20T00:00:00Z",
        labels = listOf(BackupLabel("trip", "旅行"), BackupLabel("empty", "空标签"), BackupLabel("fav", "Favourites", "FAVOURITES")),
        media = files.toList())
    private fun candidate(id: Long = 900, path: String = "Pictures/one.jpg", digest: String = hash) =
        LabelMediaFingerprint(id, digest, 100, path.substringAfterLast('/'), path)

    @Test fun jsonRoundTripPreservesUnicodeEmptyLabelsAndFavourites() {
        val source = backup(file(labels = listOf("trip", "fav"))).copy(skippedMedia = 2)
        assertEquals(source, LabelBackupJson.read(LabelBackupJson.gson.toJson(source).reader()))
    }

    @Test fun renameRelocateAndNewMediaStoreIdStillMatchByContents() {
        val result = matchLabelBackup(backup(file()), listOf(candidate(78, "DCIM/new-name.jpg")))
        assertEquals(mapOf("file" to 78L), result.matches)
        assertTrue(result.missing.isEmpty()); assertTrue(result.ambiguous.isEmpty())
    }

    @Test fun sameNameAndPathWithDifferentBytesNeverMatch() {
        val result = matchLabelBackup(backup(file()), listOf(candidate(digest = other)))
        assertTrue(result.matches.isEmpty()); assertEquals(1, result.missing.size)
    }

    @Test fun sameContentDifferentLengthNeverMatches() {
        assertTrue(matchLabelBackup(backup(file()), listOf(candidate().copy(sizeBytes = 101))).matches.isEmpty())
    }

    @Test fun duplicateCopiesNeedAnUnambiguousTieBreaker() {
        val source = backup(file(path = "Old/a.jpg"))
        val candidates = listOf(candidate(2, "New/b.jpg"), candidate(3, "New/c.jpg"))
        val result = matchLabelBackup(source, candidates)
        assertTrue(result.matches.isEmpty()); assertEquals(1, result.ambiguous.size)
    }

    @Test fun pathTakesPrecedenceOverFilenameForIdenticalCopies() {
        val result = matchLabelBackup(backup(file()), listOf(candidate(2), candidate(3, "Elsewhere/one.jpg")))
        assertEquals(mapOf("file" to 2L), result.matches)
    }

    @Test fun uniqueFilenameBreaksATieOnlyAfterHashMatches() {
        val result = matchLabelBackup(backup(file()), listOf(candidate(2, "New/one.jpg"), candidate(3, "New/two.jpg")))
        assertEquals(mapOf("file" to 2L), result.matches)
    }

    @Test fun differentLabelsOnDuplicateSourcesAreNeverSilentlyMerged() {
        val source = backup(file("a", "Old/a.jpg"), file("b", "Old/b.jpg", listOf("fav")))
        val result = matchLabelBackup(source, listOf(candidate(2, "New/c.jpg")))
        assertTrue(result.matches.isEmpty()); assertEquals(2, result.ambiguous.size)
    }

    @Test fun matchedDuplicateCannotBeReusedForAnotherSource() {
        val source = backup(file("a", "Old/a.jpg"), file("b", "Old/b.jpg", listOf("fav")))
        val result = matchLabelBackup(source, listOf(candidate(2, "New/a.jpg")))
        assertEquals(mapOf("a" to 2L), result.matches)
        assertEquals(listOf("b"), result.ambiguous.map { it.key })
    }

    @Test fun duplicatePathAndNameStillDoNotChooseArbitraryVolume() {
        val result = matchLabelBackup(backup(file()), listOf(candidate(2), candidate(3)))
        assertTrue(result.matches.isEmpty()); assertEquals(1, result.ambiguous.size)
    }

    @Test fun rejectsDamagedUnsupportedOrDanglingBackupsBeforeAnyImport() {
        val valid = LabelBackupJson.gson.toJson(backup(file()))
        listOf(
            valid.replace("\"version\": 1", "\"version\": 2"),
            valid.replace("\"version\": 1", "\"version\": 1.5"),
            valid.replace(hash, "short-hash"),
            LabelBackupJson.gson.toJson(backup(file(labels = listOf("missing")))),
            LabelBackupJson.gson.toJson(backup(file(), file())),
            valid.dropLast(12),
        ).forEach { input ->
            assertTrue("Accepted invalid backup", runCatching { LabelBackupJson.read(input.reader()) }.isFailure)
        }
    }
}
