package io.github.indexedjpeg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class IndexedJpegStoreInstrumentedTest {
    @Test
    fun changeListenerReportsPublishedAndDeletedIndex() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-change-listener-fixture.jpg")
        createFixture(source)
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)
        val unrelatedPath = File(context.cacheDir, "indexed-jpeg-unrelated.jpg").absolutePath
        val unrelatedGeneration = store.currentGenerationFor(unrelatedPath)
        val changes = mutableListOf<IndexedJpegChange>()
        val registration = store.addChangeListener(changes::add)
        try {
            store.build(source.absolutePath)
            assertEquals(1, changes.size)
            assertEquals(source.absolutePath, changes.single().sourcePath)
            assertEquals(store.currentGeneration, changes.single().generation)
            assertEquals(changes.single().generation, store.currentGenerationFor(source.absolutePath))
            assertEquals(unrelatedGeneration, store.currentGenerationFor(unrelatedPath))

            assertTrue(store.delete(source.absolutePath))
            assertEquals(2, changes.size)
            assertTrue(changes[1].generation > changes[0].generation)
        } finally {
            registration.close()
            store.delete(source.absolutePath)
            source.delete()
        }
    }

    @Test
    fun buildPersistDecodeMultipleSamplesAndDelete() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-fixture.jpg")
        createFixture(source)
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)

        assertEquals(IndexedJpegStatus.Absent, store.status(source.absolutePath))
        val info = store.build(source.absolutePath)
        assertEquals(512, info.sourceWidth)
        assertEquals(384, info.sourceHeight)
        assertTrue(info.indexBytes > 0L)
        assertEquals(0, info.pyramidLayerCount)
        val initialStatus = store.status(source.absolutePath) as IndexedJpegStatus.Ready
        assertEquals(7, initialStatus.formatVersion)
        assertEquals(IndexedJpegPyramidType.SEEK_ONLY, initialStatus.pyramidType)
        assertEquals(false, initialStatus.canUpgradeToAddressablePyramid)

        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(64, 48, 448, 336)
            assertDecodedSize(decoder!!.decodeRegion(region, 1), 384, 288)
            assertDecodedSize(decoder.decodeRegion(region, 2), 192, 144)
            assertDecodedSize(decoder.decodeRegion(region, 16), 24, 18)
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder,
                region = Rect(0, 0, 180, 288),
                sampleSize = 1,
            )
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder,
                region = Rect(0, 48, 180, 336),
                sampleSize = 1,
            )
            // Start beyond the first horizontal Huffman checkpoint. A broken
            // persisted entropy state still returns a correctly-sized, but
            // visibly corrupt, bitmap here.
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder,
                region = Rect(320, 48, 500, 336),
                sampleSize = 1,
            )
        }

        val relocated = File(context.cacheDir, "indexed-jpeg-fixture-relocated.jpg")
        store.delete(relocated.absolutePath)
        relocated.delete()
        assertTrue(source.renameTo(relocated))
        assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
        assertTrue(store.status(relocated.absolutePath) is IndexedJpegStatus.Ready)
        assertTrue(relocated.renameTo(source))
        assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Ready)

        assertTrue(source.setLastModified(source.lastModified() + 2_000L))
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Invalid)
        assertEquals(null, store.openDecoder(source.absolutePath))
        assertTrue(store.delete(source.absolutePath))
        assertEquals(IndexedJpegStatus.Absent, store.status(source.absolutePath))
        assertEquals(null, store.openDecoder(source.absolutePath))
    }

    @Test
    fun progressiveJpegBuildsAndDecodesIndexedRegions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-progressive-fixture.jpg")
        context.assets.open("progressive-fixture.jpg").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)

        val info = store.build(source.absolutePath)
        assertTrue(info.scanCount > 1)
        assertEquals(0L, info.overviewBytes)
        val progressiveStatus = store.status(source.absolutePath) as IndexedJpegStatus.Ready
        assertEquals(7, progressiveStatus.formatVersion)
        assertEquals(IndexedJpegPyramidType.SEEK_ONLY, progressiveStatus.pyramidType)
        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val region = Rect(64, 48, 448, 336)
            assertDecodedSize(decoder!!.decodeRegion(region, 1), 384, 288)
            assertDecodedSize(decoder.decodeRegion(region, 4), 96, 72)
        }

        // Version 3 indexes without an overview used the original checkpoint
        // path and remain safe; only the affected v3 overview files are rejected.
        val index = persistedIndexFile(context, source)
        val version3 = index.readBytes()
        writeLittleEndianInt(version3, 8, 3)
        index.writeBytes(version3)
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Ready)
        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            assertDecodedSize(decoder!!.decodeRegion(Rect(64, 48, 448, 336), 4), 96, 72)
        }

        assertTrue(store.delete(source.absolutePath))
    }

    @Test
    fun version2IndexRemainsUsableForTilesWithoutOverview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-v2-compatibility-fixture.jpg")
        createFixture(source)
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)

        val info = store.buildForViewport(
            sourcePath = source.absolutePath,
            viewportWidth = 120,
            viewportHeight = 160,
        )
        assertTrue(info.overviewBytes > 0L)
        val index = persistedIndexFile(context, source)
        val region = Rect(320, 48, 500, 336)
        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder!!,
                region = region,
                sampleSize = 1,
            )
        }
        val currentVersion = index.readBytes()
        writeLittleEndianInt(currentVersion, 8, 3)
        index.writeBytes(currentVersion)
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Invalid)
        assertEquals(null, store.openDecoder(source.absolutePath))

        writeLittleEndianInt(currentVersion, 8, 7)
        convertCurrentPyramidToSingleLayer(index, currentVersion, version = 4)
        val version4Status = store.status(source.absolutePath) as IndexedJpegStatus.Ready
        assertEquals(4, version4Status.formatVersion)
        assertEquals(IndexedJpegPyramidType.FIT_PREVIEW, version4Status.pyramidType)
        assertTrue(version4Status.canUpgradeToAddressablePyramid)
        val legacyOverview = store.decodeScreenOverview(source.absolutePath, 0, 120, 160)
        assertNotNull(legacyOverview)
        legacyOverview!!.recycle()
        assertEquals(null, store.openOverviewDecoder(source.absolutePath))

        convertCurrentPyramidToSingleLayer(index, currentVersion, version = 5)
        val version5Status = store.status(source.absolutePath) as IndexedJpegStatus.Ready
        assertEquals(5, version5Status.formatVersion)
        assertEquals(IndexedJpegPyramidType.WHOLE_JPEG_LAYERS, version5Status.pyramidType)
        assertEquals(1, version5Status.pyramidLayerCount)
        store.openOverviewDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
        }
        index.writeBytes(currentVersion)
        downgradeCurrentIndexToVersion2(index)

        val version2Status = store.status(source.absolutePath) as IndexedJpegStatus.Ready
        assertEquals(2, version2Status.formatVersion)
        assertEquals(IndexedJpegPyramidType.SEEK_ONLY, version2Status.pyramidType)
        assertTrue(version2Status.canUpgradeToAddressablePyramid)
        val version2Bytes = index.readBytes()
        index.writeBytes(version2Bytes.copyOf(version2Bytes.size - 1))
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Invalid)
        index.writeBytes(version2Bytes)
        assertEquals(
            null,
            store.decodeScreenOverview(
                sourcePath = source.absolutePath,
                rotationDegrees = 0,
                requestedWidth = 120,
                requestedHeight = 160,
            ),
        )
        store.openDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            assertRegionMatchesPlatform(
                source = source,
                indexed = decoder!!,
                region = region,
                sampleSize = 1,
            )
        }

        assertTrue(store.delete(source.absolutePath))
        source.delete()
    }

    @Test
    fun baselineBuildEmbedsAndDecodesFitOverview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-overview-fixture.jpg")
        createOverviewFixture(source)
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)

        assertBuildAndDecodeOverview(
            store = store,
            source = source,
            viewportWidth = 500,
            viewportHeight = 700,
            expectedSamples = listOf(2),
        )
        assertBuildAndDecodeOverview(
            store = store,
            source = source,
            viewportWidth = 250,
            viewportHeight = 350,
            expectedSamples = listOf(2, 4),
        )
        assertBuildAndDecodeOverview(
            store = store,
            source = source,
            viewportWidth = 120,
            viewportHeight = 160,
            expectedSamples = listOf(2, 4, 8),
        )

        val relocated = File(context.cacheDir, "indexed-jpeg-overview-relocated.jpg")
        store.delete(relocated.absolutePath)
        relocated.delete()
        assertTrue(source.renameTo(relocated))
        assertTrue(store.relocate(source.absolutePath, relocated.absolutePath))
        val relocatedOverview = store.decodeScreenOverview(
            sourcePath = relocated.absolutePath,
            rotationDegrees = 0,
            requestedWidth = 120,
            requestedHeight = 160,
        )
        assertNotNull(relocatedOverview)
        relocatedOverview!!.recycle()
        assertTrue(relocated.renameTo(source))
        assertTrue(store.relocate(relocated.absolutePath, source.absolutePath))

        // The embedded layer is never stretched beyond its available resolution.
        assertEquals(
            null,
            store.decodeScreenOverview(
                sourcePath = source.absolutePath,
                rotationDegrees = 0,
                requestedWidth = 3000,
                requestedHeight = 3000,
            ),
        )
        assertTrue(store.delete(source.absolutePath))
        assertEquals(
            null,
            store.decodeScreenOverview(source.absolutePath, 0, 120, 160),
        )
        source.delete()
    }

    @Test
    fun addressablePyramidDecodesAcrossStoredTileBoundaries() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "indexed-jpeg-addressable-pyramid-fixture.jpg")
        createLargeOverviewFixture(source)
        val store = IndexedJpegStore(context)
        store.delete(source.absolutePath)

        val info = store.buildForViewport(
            sourcePath = source.absolutePath,
            viewportWidth = 1000,
            viewportHeight = 1200,
        )
        val layer = store.pyramidLayers(source.absolutePath).single()
        assertEquals(2, layer.sampleSize)
        assertEquals(1024, layer.tileSize)
        assertTrue(layer.width > layer.tileSize)
        assertTrue(layer.height > layer.tileSize)

        store.openOverviewDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val active = requireNotNull(decoder)
            assertTrue(active.isAddressableTiled)
            val alignedStoredTile = Rect(0, 0, 2048, 2048)
            assertEquals(1, active.addressableTileCount(alignedStoredTile, layer.sampleSize))
            val direct = active.decodeRegion(alignedStoredTile, layer.sampleSize)
            assertNotNull(direct)
            assertEquals(layer.tileSize, direct!!.width)
            assertEquals(layer.tileSize, direct.height)
            direct.recycle()

            val crossing = Rect(1800, 1800, 2400, 2250)
            assertEquals(4, active.addressableTileCount(crossing, layer.sampleSize))
            val decoded = active.decodeRegion(crossing, layer.sampleSize)
            assertNotNull(decoded)
            assertEquals(ceilDiv(crossing.width(), layer.sampleSize), decoded!!.width)
            assertEquals(ceilDiv(crossing.height(), layer.sampleSize), decoded.height)
            val topLeft = decoded.getPixel(0, 0)
            val bottomRight = decoded.getPixel(decoded.width - 1, decoded.height - 1)
            assertTrue(Color.red(bottomRight) > Color.red(topLeft) + 20)
            assertTrue(Color.green(bottomRight) > Color.green(topLeft) + 20)
            decoded.recycle()
        }

        val index = persistedIndexFile(context, source)
        val validIndex = index.readBytes()
        val ready = store.status(source.absolutePath) as IndexedJpegStatus.Ready
        assertEquals(7, ready.formatVersion)
        assertEquals(IndexedJpegPyramidType.ADDRESSABLE_TILES, ready.pyramidType)
        assertEquals(info.pyramidLayerCount, ready.pyramidLayerCount)
        val payloadBytes = readLittleEndianInt(validIndex, 60)
        val overviewMarkerOffset =
            validIndex.size - Int.SIZE_BYTES - payloadBytes - Int.SIZE_BYTES
        val corruptIndex = validIndex.copyOf()
        writeLittleEndianInt(
            corruptIndex,
            overviewMarkerOffset + Int.SIZE_BYTES,
            0x32525951,
        )
        index.writeBytes(corruptIndex)
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Invalid)
        index.writeBytes(validIndex)
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Ready)
        index.writeBytes(validIndex.copyOf(validIndex.size - 1))
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Invalid)
        index.writeBytes(validIndex)
        assertTrue(store.status(source.absolutePath) is IndexedJpegStatus.Ready)

        assertTrue(store.delete(source.absolutePath))
        source.delete()
    }

    private fun assertBuildAndDecodeOverview(
        store: IndexedJpegStore,
        source: File,
        viewportWidth: Int,
        viewportHeight: Int,
        expectedSamples: List<Int>,
    ) {
        val info = store.buildForViewport(
            sourcePath = source.absolutePath,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        assertTrue(info.overviewBytes > 0L)
        assertEquals(expectedSamples.size, info.pyramidLayerCount)
        assertEquals(2, info.overviewSampleSize)
        assertEquals(ceilDiv(info.sourceWidth, 2), info.overviewWidth)
        assertEquals(ceilDiv(info.sourceHeight, 2), info.overviewHeight)
        val layers = store.pyramidLayers(source.absolutePath)
        assertEquals(expectedSamples, layers.map { it.sampleSize })
        layers.forEach { layer ->
            assertEquals(ceilDiv(info.sourceWidth, layer.sampleSize), layer.width)
            assertEquals(ceilDiv(info.sourceHeight, layer.sampleSize), layer.height)
            assertTrue(layer.bytes > 0)
            assertEquals(1024, layer.tileSize)
        }
        val overview = store.decodeScreenOverview(
            sourcePath = source.absolutePath,
            rotationDegrees = 0,
            requestedWidth = viewportWidth,
            requestedHeight = viewportHeight,
        )
        assertNotNull(overview)
        overview!!
        assertTrue(overview.width <= layers.last().width)
        assertTrue(overview.height <= layers.last().height)
        assertTrue(
            overviewCoversFit(
                overviewWidth = overview.width,
                overviewHeight = overview.height,
                sourceWidth = info.sourceWidth,
                sourceHeight = info.sourceHeight,
                rotationDegrees = 0,
                requestedWidth = viewportWidth,
                requestedHeight = viewportHeight,
            ),
        )
        assertOverviewColorsArePlausible(overview)
        val decodeSample = (info.sourceWidth / overview.width).coerceAtLeast(1)
        assertOverviewMatchesSampledSource(
            source,
            overview,
            decodeSample,
        )
        overview.recycle()

        store.openOverviewDecoder(source.absolutePath).use { decoder ->
            assertNotNull(decoder)
            val activeDecoder = requireNotNull(decoder)
            val splitX = info.sourceWidth / 2
            val splitY = info.sourceHeight / 2
            layers.forEach { layer ->
                val full = activeDecoder.decodeRegion(
                    Rect(0, 0, info.sourceWidth, info.sourceHeight),
                    layer.sampleSize,
                )
                assertNotNull(full)
                assertEquals(layer.width, full!!.width)
                assertEquals(layer.height, full.height)
                assertOverviewMatchesSampledSource(source, full, layer.sampleSize)
                full.recycle()

                listOf(
                    Rect(0, 0, splitX, splitY),
                    Rect(splitX, 0, info.sourceWidth, splitY),
                    Rect(0, splitY, splitX, info.sourceHeight),
                    Rect(splitX, splitY, info.sourceWidth, info.sourceHeight),
                ).forEach { rect ->
                    val tile = activeDecoder.decodeRegion(rect, layer.sampleSize)
                    assertNotNull(tile)
                    assertEquals(ceilDiv(rect.width(), layer.sampleSize), tile!!.width)
                    assertEquals(ceilDiv(rect.height(), layer.sampleSize), tile.height)
                    tile.recycle()
                }
            }
            assertEquals(
                null,
                activeDecoder.decodeRegion(
                    Rect(0, 0, info.sourceWidth, info.sourceHeight),
                    1,
                ),
            )
        }
    }

    private fun persistedIndexFile(
        context: android.content.Context,
        source: File,
    ): File {
        val stablePath = source.canonicalPath
        val key = MessageDigest.getInstance("SHA-256")
            .digest(stablePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.noBackupFilesDir, "indexed-jpeg"), "$key.ijx")
            .also { assertTrue(it.isFile) }
    }

    private fun convertCurrentPyramidToSingleLayer(
        index: File,
        current: ByteArray,
        version: Int,
    ) {
        val headerBytes = 64
        val pyramidBytes = readLittleEndianInt(current, 60)
        assertEquals(7, readLittleEndianInt(current, 8))
        assertTrue(pyramidBytes > 0)
        val overviewMarkerOffset = current.size - Int.SIZE_BYTES - pyramidBytes - Int.SIZE_BYTES
        assertEquals(0x3152564f, readLittleEndianInt(current, overviewMarkerOffset))
        val payloadOffset = overviewMarkerOffset + Int.SIZE_BYTES
        assertEquals(0x32525950, readLittleEndianInt(current, payloadOffset))
        val count = readLittleEndianInt(current, payloadOffset + Int.SIZE_BYTES)
        assertTrue(count > 0)
        val layerRecordBytes = 28
        val tileRecordBytes = 20
        val selectedDirectory =
            payloadOffset + 2 * Int.SIZE_BYTES + (count - 1) * layerRecordBytes
        val sampleSize = readLittleEndianInt(current, selectedDirectory)
        val width = readLittleEndianInt(current, selectedDirectory + 4)
        val height = readLittleEndianInt(current, selectedDirectory + 8)
        val encodedBytes = readLittleEndianInt(current, selectedDirectory + 12)
        val tilesAcross = readLittleEndianInt(current, selectedDirectory + 20)
        val tilesDown = readLittleEndianInt(current, selectedDirectory + 24)
        assertEquals(1, tilesAcross * tilesDown)
        var totalTileRecords = 0
        repeat(count) { position ->
            val directory = payloadOffset + 2 * Int.SIZE_BYTES + position * layerRecordBytes
            totalTileRecords +=
                readLittleEndianInt(current, directory + 20) *
                    readLittleEndianInt(current, directory + 24)
        }
        var encodedOffset =
            payloadOffset + 2 * Int.SIZE_BYTES + count * layerRecordBytes +
                totalTileRecords * tileRecordBytes
        repeat(count - 1) { position ->
            encodedOffset += readLittleEndianInt(
                current,
                payloadOffset + 2 * Int.SIZE_BYTES + position * layerRecordBytes + 12,
            )
        }

        val recordsBytes = overviewMarkerOffset - headerBytes
        val single = ByteArray(
            headerBytes + recordsBytes + Int.SIZE_BYTES + encodedBytes + Int.SIZE_BYTES,
        )
        current.copyInto(single, endIndex = headerBytes)
        writeLittleEndianInt(single, 8, version)
        writeLittleEndianInt(single, 48, width)
        writeLittleEndianInt(single, 52, height)
        writeLittleEndianInt(single, 56, sampleSize)
        writeLittleEndianInt(single, 60, encodedBytes)
        current.copyInto(
            destination = single,
            destinationOffset = headerBytes,
            startIndex = headerBytes,
            endIndex = overviewMarkerOffset,
        )
        val singleMarkerOffset = headerBytes + recordsBytes
        writeLittleEndianInt(single, singleMarkerOffset, 0x3152564f)
        current.copyInto(
            destination = single,
            destinationOffset = singleMarkerOffset + Int.SIZE_BYTES,
            startIndex = encodedOffset,
            endIndex = encodedOffset + encodedBytes,
        )
        writeLittleEndianInt(single, single.size - Int.SIZE_BYTES, 0x31444e45)
        index.writeBytes(single)
    }

    private fun downgradeCurrentIndexToVersion2(index: File) {
        val current = index.readBytes()
        val versionOffset = 8
        val currentHeaderBytes = 64
        val version2HeaderBytes = 48
        val overviewBytes = readLittleEndianInt(current, 60)
        assertEquals(7, readLittleEndianInt(current, versionOffset))
        assertTrue(overviewBytes > 0)

        val overviewMarkerOffset = current.size - Int.SIZE_BYTES - overviewBytes - Int.SIZE_BYTES
        assertEquals(0x3152564f, readLittleEndianInt(current, overviewMarkerOffset))
        assertEquals(0x31444e45, readLittleEndianInt(current, current.size - Int.SIZE_BYTES))

        val recordsBytes = overviewMarkerOffset - currentHeaderBytes
        val version2 = ByteArray(version2HeaderBytes + recordsBytes + Int.SIZE_BYTES)
        current.copyInto(version2, endIndex = version2HeaderBytes)
        writeLittleEndianInt(version2, versionOffset, 2)
        current.copyInto(
            destination = version2,
            destinationOffset = version2HeaderBytes,
            startIndex = currentHeaderBytes,
            endIndex = overviewMarkerOffset,
        )
        current.copyInto(
            destination = version2,
            destinationOffset = version2.size - Int.SIZE_BYTES,
            startIndex = current.size - Int.SIZE_BYTES,
        )
        index.writeBytes(version2)
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private fun assertDecodedSize(bitmap: Bitmap?, width: Int, height: Int) {
        assertNotNull(bitmap)
        bitmap!!
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
        bitmap.recycle()
    }

    @Suppress("DEPRECATION")
    private fun assertRegionMatchesPlatform(
        source: File,
        indexed: IndexedJpegRegionDecoder,
        region: Rect,
        sampleSize: Int,
    ) {
        val actual = indexed.decodeRegion(region, sampleSize)
        assertNotNull(actual)
        actual!!
        val platform = BitmapRegionDecoder.newInstance(source.absolutePath, false)
        assertNotNull(platform)
        val expected = try {
            platform!!.decodeRegion(
                region,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } finally {
            platform!!.recycle()
        }
        assertNotNull(expected)
        expected!!
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)

        var totalDifference = 0L
        var samples = 0L
        val stepX = (actual.width / 32).coerceAtLeast(1)
        val stepY = (actual.height / 32).coerceAtLeast(1)
        for (y in 0 until actual.height step stepY) {
            for (x in 0 until actual.width step stepX) {
                val expectedColor = expected.getPixel(x, y)
                val actualColor = actual.getPixel(x, y)
                totalDifference += kotlin.math.abs(Color.red(expectedColor) - Color.red(actualColor))
                totalDifference += kotlin.math.abs(Color.green(expectedColor) - Color.green(actualColor))
                totalDifference += kotlin.math.abs(Color.blue(expectedColor) - Color.blue(actualColor))
                samples += 3
            }
        }
        val meanDifference = totalDifference.toDouble() / samples.coerceAtLeast(1)
        val centerX = actual.width / 2
        val centerY = actual.height / 2
        val actualCenter = actual.getPixel(centerX, centerY)
        val expectedCenter = expected.getPixel(centerX, centerY)
        assertTrue(
            "Mean RGB difference for $region was $meanDifference; " +
                "center actual=${Integer.toHexString(actualCenter)} " +
                "expected=${Integer.toHexString(expectedCenter)}",
            meanDifference <= 5.0,
        )
        expected.recycle()
        actual.recycle()
    }

    private fun createFixture(destination: File) {
        val bitmap = Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(
                    x,
                    y,
                    Color.rgb((x * 3) and 0xff, (y * 5) and 0xff, (x + y) and 0xff),
                )
            }
        }
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, output))
        }
        bitmap.recycle()
    }

    private fun createOverviewFixture(destination: File) {
        val width = 2048
        val height = 1536
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                row[x] = Color.rgb(
                    24 + x * 200 / (width - 1),
                    20 + y * 210 / (height - 1),
                    32 + (x + y) * 180 / (width + height - 2),
                )
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
        }
        bitmap.recycle()
    }

    private fun createLargeOverviewFixture(destination: File) {
        val width = 2560
        val height = 2304
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                row[x] = Color.rgb(
                    24 + x * 200 / (width - 1),
                    20 + y * 210 / (height - 1),
                    32 + (x + y) * 180 / (width + height - 2),
                )
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        FileOutputStream(destination).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
        }
        bitmap.recycle()
    }

    private fun assertOverviewColorsArePlausible(bitmap: Bitmap) {
        val topLeft = bitmap.getPixel(bitmap.width / 8, bitmap.height / 8)
        val bottomRight = bitmap.getPixel(bitmap.width * 7 / 8, bitmap.height * 7 / 8)
        assertTrue(Color.red(bottomRight) > Color.red(topLeft) + 100)
        assertTrue(Color.green(bottomRight) > Color.green(topLeft) + 100)
        assertTrue(Color.blue(bottomRight) > Color.blue(topLeft) + 80)
    }

    private fun assertOverviewMatchesSampledSource(
        source: File,
        overview: Bitmap,
        sampleSize: Int,
    ) {
        val sampled = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
        assertNotNull(sampled)
        sampled!!
        val expected = if (sampled.width == overview.width && sampled.height == overview.height) {
            sampled
        } else {
            Bitmap.createScaledBitmap(sampled, overview.width, overview.height, true)
        }
        assertEquals(expected.width, overview.width)
        assertEquals(expected.height, overview.height)
        var totalDifference = 0L
        var channelSamples = 0L
        val stepX = (overview.width / 32).coerceAtLeast(1)
        val stepY = (overview.height / 32).coerceAtLeast(1)
        for (y in 0 until overview.height step stepY) {
            for (x in 0 until overview.width step stepX) {
                val actualColor = overview.getPixel(x, y)
                val expectedColor = expected.getPixel(x, y)
                totalDifference += kotlin.math.abs(Color.red(actualColor) - Color.red(expectedColor))
                totalDifference += kotlin.math.abs(Color.green(actualColor) - Color.green(expectedColor))
                totalDifference += kotlin.math.abs(Color.blue(actualColor) - Color.blue(expectedColor))
                channelSamples += 3
            }
        }
        val meanDifference = totalDifference.toDouble() / channelSamples.coerceAtLeast(1)
        if (expected !== sampled) expected.recycle()
        sampled.recycle()
        assertTrue("Overview mean RGB difference was $meanDifference", meanDifference <= 12.0)
    }
}
