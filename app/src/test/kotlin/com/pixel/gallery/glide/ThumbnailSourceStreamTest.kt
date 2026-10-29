package com.pixel.gallery.glide

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class ThumbnailSourceStreamTest {
    @Test fun tinyReadsAreBufferedWithoutChangingBytes() {
        val original = ByteArray(200_000) { (it % 251).toByte() }
        ThumbnailSourceStream(ByteArrayInputStream(original)) { false }.use { stream ->
            val actual = ArrayList<Byte>()
            val chunk = ByteArray(19)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                repeat(count) { actual += chunk[it] }
            }
            assertArrayEquals(original, actual.toByteArray())
            assertEquals(original.size.toLong(), stream.sourceBytesRead)
            assertTrue("small reads reached underlying source: ${stream.sourceReadCalls}", stream.sourceReadCalls <= 6)
        }
    }

    @Test fun cancelBeforeFirstReadNeverTouchesSource() {
        val stream = ThumbnailSourceStream(object : InputStream() {
            override fun read(): Int = error("Cancelled stream touched source")
        }) { true }
        try { stream.read(); fail() } catch (_: InterruptedIOException) { }
        assertEquals(0L, stream.sourceReadCalls)
        stream.close()
    }

    @Test fun cancelAlsoStopsBytesAlreadyBufferedAndSkipping() {
        var cancelled = false
        val stream = ThumbnailSourceStream(ByteArrayInputStream(ByteArray(80_000))) { cancelled }
        assertEquals(0, stream.read())
        val reads = stream.sourceReadCalls
        cancelled = true
        for (operation in listOf<() -> Unit>({ stream.read() }, { stream.read(ByteArray(4)) }, { stream.skip(4) })) {
            try { operation(); fail() } catch (_: InterruptedIOException) { }
        }
        assertEquals(reads, stream.sourceReadCalls)
        stream.close()
    }

    @Test fun closeReleasesSourceAndFailureIsNotHidden() {
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("fixture failure")
            override fun close() { closed = true }
        }
        try {
            ThumbnailSourceStream(source) { false }.use { it.read() }
            fail()
        } catch (error: IOException) { assertEquals("fixture failure", error.message) }
        assertTrue(closed)
    }
}
