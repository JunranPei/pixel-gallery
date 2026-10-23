package com.pixel.gallery.model

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AlbumRenameTest {
    @Test fun validatesFolderNamesAndUtf8Limit() {
        listOf("", " ", ".", "..", ".hidden", "a/b", "a\\b", "a\n", "x".repeat(256), "相".repeat(86))
            .forEach { assertFalse(it, isValidAlbumName(it)) }
        listOf("Vacation 2026", "旅行", "x".repeat(255), "相".repeat(85))
            .forEach { assertTrue(it, isValidAlbumName(it)) }
    }

    @Test fun rejectsExistingFolderWithoutTouchingFiles() {
        val root = Files.createTempDirectory("album-rename").toFile()
        try {
            val source = root.resolve("Source").apply { mkdir() }
            val existing = root.resolve("Existing").apply { mkdir() }
            existing.resolve("keep.txt").writeText("preserved")
            assertTrue(runCatching { renamedAlbumDirectory(source, "Existing") }.isFailure)
            assertEquals("preserved", existing.resolve("keep.txt").readText())
            assertEquals(source, renamedAlbumDirectory(source, "Source"))
            assertEquals(root.resolve("Renamed"), renamedAlbumDirectory(source, " Renamed "))
        } finally { root.deleteRecursively() }
    }

    @Test fun remapsDescendantsWithoutChangingSimilarPrefixes() {
        assertEquals("/Pictures/New", remapAlbumPath("/Pictures/Old", "/Pictures/Old", "/Pictures/New"))
        assertEquals("/Pictures/New/Sub/a.jpg", remapAlbumPath("/Pictures/Old/Sub/a.jpg", "/Pictures/Old", "/Pictures/New"))
        assertEquals("/Pictures/Older/a.jpg", remapAlbumPath("/Pictures/Older/a.jpg", "/Pictures/Old", "/Pictures/New"))
    }
}
