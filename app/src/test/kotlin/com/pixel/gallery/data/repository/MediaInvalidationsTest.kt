package com.pixel.gallery.data.repository

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaInvalidationsTest {
    @Test fun `bulk database invalidations are merged without dropping the final change`() = runBlocking {
        val updates = flow { repeat(1000) { emit(it) } }.coalesceMediaInvalidations(1).toList()
        assertEquals(0, updates.first())
        assertEquals(999, updates.last())
        assertTrue(updates.size < 10)
    }

    @Test fun `single invalidation remains visible without waiting for a second event`() = runBlocking {
        assertEquals(listOf(7), flow { emit(7) }.coalesceMediaInvalidations(1).toList())
    }
}
