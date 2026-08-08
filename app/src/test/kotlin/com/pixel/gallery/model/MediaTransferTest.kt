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

    @Test
    fun `transfer verification requires an exact byte count`() {
        assertEquals(true, isVerifiedTransferSize(42, 42))
        assertEquals(false, isVerifiedTransferSize(42, 41))
        assertEquals(false, isVerifiedTransferSize(42, 43))
        assertEquals(false, isVerifiedTransferSize(-1, 42))
    }

    @Test
    fun `destination search ignores case and matches only from the beginning`() {
        val camera = destination("Camera")

        assertEquals(true, matchesTransferDestinationQuery(camera, "CAM"))
        assertEquals(false, matchesTransferDestinationQuery(camera, "mera"))
    }

    @Test
    fun `destination search matches Chinese album names by full pinyin prefix`() {
        assertEquals(true, matchesTransferDestinationQuery(destination("微信"), "WEI"))
        assertEquals(true, matchesTransferDestinationQuery(destination("小红书"), "xiaohong"))
        assertEquals(false, matchesTransferDestinationQuery(destination("小红书"), "hongshu"))
    }

    @Test
    fun `destination search supports mixed Chinese and English album names`() {
        assertEquals(true, matchesTransferDestinationQuery(destination("微信Backup"), "weixinb"))
        assertEquals(true, matchesTransferDestinationQuery(destination("微信Backup"), "微信b"))
    }

    @Test
    fun `destination search considers polyphonic Chinese prefixes`() {
        assertEquals(true, matchesTransferDestinationQuery(destination("重庆旅行"), "chongqing"))
    }

    @Test
    fun `destination search ignores separators and full width letter forms`() {
        assertEquals(true, matchesTransferDestinationQuery(destination("Screen recordings"), "screen_rec"))
        assertEquals(true, matchesTransferDestinationQuery(destination("Camera"), "ＣＡＭ"))
    }

    @Test
    fun `committed move rolls back while source still exists`() {
        assertEquals(
            ReplacementRecoveryAction.ROLLBACK_TO_BACKUP,
            replacementRecoveryAction(
                ReplacementStage.TARGET_COMMITTED,
                TransferMode.MOVE,
                sourceExists = true
            )
        )
    }

    @Test
    fun `committed move is kept after source removal`() {
        assertEquals(
            ReplacementRecoveryAction.KEEP_COMMITTED,
            replacementRecoveryAction(
                ReplacementStage.TARGET_COMMITTED,
                TransferMode.MOVE,
                sourceExists = false
            )
        )
    }

    @Test
    fun `backed up target always rolls back`() {
        TransferMode.entries.forEach { mode ->
            assertEquals(
                ReplacementRecoveryAction.ROLLBACK_TO_BACKUP,
                replacementRecoveryAction(
                    ReplacementStage.TARGET_BACKED_UP,
                    mode,
                    sourceExists = mode == TransferMode.MOVE
                )
            )
        }
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

    private fun destination(name: String) = TransferDestination(
        stableKey = name,
        displayName = name,
        path = "/storage/emulated/0/$name"
    )
}
