package com.pixel.gallery.glide

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException

/** Buffer provider reads, but check cancellation even while buffered bytes remain. */
internal class ThumbnailSourceStream(source: InputStream, private val isCancelled: () -> Boolean) : InputStream() {
    var sourceReadCalls = 0L
        private set
    var sourceBytesRead = 0L
        private set
    private val buffered = BufferedInputStream(object : FilterInputStream(source) {
        override fun read(): Int {
            sourceReadCalls++
            return `in`.read().also { if (it >= 0) sourceBytesRead++ }
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            sourceReadCalls++
            return `in`.read(bytes, offset, length).also { if (it > 0) sourceBytesRead += it }
        }
    }, 64 * 1024)

    private fun checkActive() {
        if (isCancelled()) throw InterruptedIOException("Thumbnail request cancelled")
    }
    override fun read(): Int { checkActive(); return buffered.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        checkActive(); return buffered.read(bytes, offset, length)
    }
    override fun skip(count: Long): Long { checkActive(); return buffered.skip(count) }
    override fun available(): Int { checkActive(); return buffered.available() }
    override fun markSupported() = buffered.markSupported()
    override fun mark(readLimit: Int) = buffered.mark(readLimit)
    override fun reset() { checkActive(); buffered.reset() }
    override fun close() = buffered.close()
}
