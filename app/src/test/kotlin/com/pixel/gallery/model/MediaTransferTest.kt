package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaTransferTest {

    @Test
    fun `same album names at different paths stay separate`() {
        val entries = listOf(
            entry(1, "/storage/emulated/0/DCIM/Screenshots/one.jpg"),
            entry(2, "/storage/emulated/0/Pictures/Screenshots/two.jpg")
        )

        val destinations = buildTransferDestinations(entries)

        assertEquals(2, destinations.size)
        assertEquals(setOf("Screenshots"), destinations.map { it.displayName }.toSet())
        assertNotEquals(destinations[0].stableKey, destinations[1].stableKey)
    }

    @Test
    fun `multiple items in one path produce one destination`() {
        val entries = listOf(
            entry(1, "/storage/emulated/0/Pictures/Travel/one.jpg"),
            entry(2, "/storage/emulated/0/Pictures/Travel/two.jpg")
        )

        val destination = buildTransferDestinations(entries).single()

        assertEquals("Travel", destination.displayName)
        assertEquals(2, destination.itemCount)
    }

    @Test
    fun `keep both naming preserves multi dot extension`() {
        val existing = setOf("holiday.final.jpg", "holiday.final (1).jpg")

        val result = getAvailableTransferName("holiday.final.jpg", existing::contains)

        assertEquals("holiday.final (2).jpg", result)
    }

    @Test
    fun `keep both naming supports extensionless files`() {
        val result = getAvailableTransferName("README") { it == "README" }

        assertEquals("README (1)", result)
    }

    private fun entry(id: Long, path: String) = MediaEntry(
        contentId = id,
        uri = "content://media/$id",
        path = path,
        sourceMimeType = "image/jpeg",
        width = 100,
        height = 100,
        sourceRotationDegrees = 0,
        sizeBytes = 1,
        dateAddedSecs = 1,
        dateModifiedMillis = 1,
        bestTimestamp = id
    )
}
