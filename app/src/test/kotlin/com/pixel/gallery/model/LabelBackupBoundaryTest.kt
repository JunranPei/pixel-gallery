package com.pixel.gallery.model

import org.junit.Assert.*
import org.junit.Test

class LabelBackupBoundaryTest {
    private val hash = "c".repeat(64)
    private fun backup(labels: List<BackupLabel> = listOf(BackupLabel("tag", "Trip")), media: List<BackupMedia> = emptyList()) =
        LabelBackup(exportedAt = "2026-10-20T00:00:00Z", labels = labels, media = media)
    private fun file(key: String, name: String = "$key.jpg", labels: List<String> = listOf("tag")) =
        BackupMedia(key, hash, 100, name, "Pictures/$name", labels)
    private fun read(source: LabelBackup) = LabelBackupJson.read(LabelBackupJson.gson.toJson(source).reader())

    @Test fun emptyBackupAndLabelsWithoutMediaRoundTrip() {
        assertEquals(backup(emptyList()), read(backup(emptyList())))
        assertEquals(backup(), read(backup()))
    }
    @Test fun unicodeEmojiEscapesAndSixtyCharacterLabelsRoundTrip() {
        val source = backup(listOf(BackupLabel("tag", "旅行 📷 \"a\" \\ café"), BackupLabel("long", "中".repeat(60))),
            listOf(file("one", "中文 📷 \"a\".jpg")))
        assertEquals(source, read(source))
    }
    @Test fun unicodeBomAndAdditionalMetadataAreAccepted() {
        val source = backup()
        val text = LabelBackupJson.gson.toJson(source).dropLast(1) + ",\"extra\":{\"source\":\"test\"}}"
        assertEquals(source, LabelBackupJson.read(("\uFEFF" + text).reader()))
    }
    @Test fun exactLongValuesSurviveWithoutFloatingPointRounding() {
        listOf(0L, 9007199254740993L, Long.MAX_VALUE).forEach { size ->
            val source = backup(media = listOf(file("one").copy(sizeBytes = size)))
            assertEquals(source, read(source))
        }
        val text = LabelBackupJson.gson.toJson(backup(media = listOf(file("one"))))
            .replace("\"sizeBytes\": 100", "\"sizeBytes\": 1e2")
        assertEquals(100L, LabelBackupJson.read(text.reader()).media.single().sizeBytes)
    }
    @Test fun normalizedDuplicateNamesAndDuplicateKeysAreRejected() {
        listOf(
            listOf(BackupLabel("a", "Trip"), BackupLabel("b", " trip ")),
            listOf(BackupLabel("a", "Ｃａｆé"), BackupLabel("b", "Cafe\u0301")),
            listOf(BackupLabel("a", "One"), BackupLabel("a", "Two")),
            listOf(BackupLabel("a", "Fav A", "FAVOURITES"), BackupLabel("b", "Fav B", "FAVOURITES")),
        ).forEach { assertTrue(runCatching { read(backup(it)) }.isFailure) }
        assertTrue(runCatching { read(backup(media = listOf(file("one"), file("one")))) }.isFailure)
    }
    @Test fun labelAndMediaCountLimitsAreEnforcedBeforeMapping() {
        val labels = (1..5000).map { BackupLabel("tag-$it", "Label $it") }
        assertEquals(5000, read(backup(labels)).labels.size)
        assertTrue(runCatching { read(backup(labels + BackupLabel("overflow", "Overflow"))) }.isFailure)
        val rows = (1..100000).map { file("media-$it") }
        assertEquals(100000, read(backup(media = rows)).media.size)
        assertTrue(runCatching { read(backup(media = rows + file("overflow"))) }.isFailure)
    }
    @Test fun matchingIsIndependentOfCandidateAndSourceOrderAndRetainsExtras() {
        val source = backup(media = listOf(file("a"), file("b"), file("c")))
        val candidates = listOf(
            LabelMediaFingerprint(11, hash, 100, "a.jpg", "Pictures/a.jpg"),
            LabelMediaFingerprint(12, hash, 100, "b.jpg", "Moved/b.jpg"),
            LabelMediaFingerprint(13, hash, 100, "renamed.jpg", "Moved/renamed.jpg"),
            LabelMediaFingerprint(14, "d".repeat(64), 100, "extra.jpg", "Pictures/extra.jpg"),
        )
        repeat(20) { seed ->
            val result = matchLabelBackup(source.copy(media = source.media.shuffled(kotlin.random.Random(seed))),
                candidates.shuffled(kotlin.random.Random(seed + 100)))
            assertEquals(mapOf("a" to 11L, "b" to 12L, "c" to 13L), result.matches)
            assertTrue(result.missing.isEmpty()); assertTrue(result.ambiguous.isEmpty())
        }
    }
    @Test fun relationshipCountLimitAcceptsExactMaximumAndRejectsOneBeyond() {
        val tags = (1..5000).map { BackupLabel("tag-$it", "Tag $it") }
        val keys = tags.map { it.key }
        val rows = (1..400).map { file("file-$it", labels = keys) }
        assertEquals(400, read(backup(tags, rows)).media.size)
        assertTrue(runCatching { read(backup(tags, rows + file("overflow", labels = listOf(keys.first())))) }.isFailure)
    }
    @Test fun partialMissingAmbiguousAndMatchedRecordsAreSeparated() {
        val source = backup(media = listOf(file("found"), file("missing").copy(sha256 = "d".repeat(64)),
            file("ambiguous").copy(sha256 = "e".repeat(64))))
        val result = matchLabelBackup(source, listOf(
            LabelMediaFingerprint(1, hash, 100, "new.jpg", "New/new.jpg"),
            LabelMediaFingerprint(2, "e".repeat(64), 100, "copy-1.jpg", "Copies/copy-1.jpg"),
            LabelMediaFingerprint(3, "e".repeat(64), 100, "copy-2.jpg", "Copies/copy-2.jpg")))
        assertEquals(mapOf("found" to 1L), result.matches)
        assertEquals(listOf("missing"), result.missing.map { it.key })
        assertEquals(listOf("ambiguous"), result.ambiguous.map { it.key })
    }
    @Test fun duplicateCandidateIdsDoNotInventAmbiguityOrReuseTargets() {
        val target = LabelMediaFingerprint(1, hash, 100, "a.jpg", "Pictures/a.jpg")
        assertEquals(mapOf("a" to 1L), matchLabelBackup(backup(media = listOf(file("a"))), listOf(target, target)).matches)
        val many = matchLabelBackup(backup(media = listOf(file("a"), file("b"))), listOf(target, target))
        assertEquals(mapOf("a" to 1L), many.matches)
        assertEquals(listOf("b"), many.ambiguous.map { it.key })
    }
    @Test fun everyMissingSourceStaysUnmatchedAndZeroByteContentStillRequiresHash() {
        val source = backup(media = listOf(file("one"), file("two")))
        assertEquals(2, matchLabelBackup(source, emptyList()).missing.size)
        val zero = source.copy(media = listOf(file("zero").copy(sizeBytes = 0)))
        val candidate = LabelMediaFingerprint(7, hash, 0, "changed.jpg", "New/changed.jpg")
        assertEquals(mapOf("zero" to 7L), matchLabelBackup(zero, listOf(candidate)).matches)
        assertTrue(matchLabelBackup(zero, listOf(candidate.copy(sha256 = "f".repeat(64)))).matches.isEmpty())
    }
}
