package com.pixel.gallery.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class MediaReadRefreshCoordinatorTest {
    @Test fun `a thousand commits do not read the library during a batch and boundary reads latest`() = runBlocking {
        val gate = MediaReadRefreshCoordinator()
        val changes = MutableSharedFlow<Int>(replay = 1).apply { emit(0) }
        val reads = Channel<Int>(Channel.UNLIMITED)
        var committed = 0
        var count = 0
        val collector = launch { gate.refreshes(changes).collect { count++; reads.send(committed) } }
        try {
            assertEquals(0, withTimeout(5000) { reads.receive() })
            val batch = gate.hold()
            repeat(1000) {
                committed++
                changes.emit(committed)
                repeat(3) { yield() }
            }
            assertEquals(1, count)
            gate.publish()
            assertEquals(1000, withTimeout(5000) { reads.receive() })
            assertTrue(gate.isHeld)
            repeat(20) { changes.emit(committed); repeat(3) { yield() } }
            assertEquals(2, count)
            batch.close()
            assertEquals(1000, withTimeout(5000) { reads.receive() })
            assertEquals(3, count)
            println("BATCH_REFRESH: 1000 commits; initial=1; during=0; boundary=1; release=1; reads=$count")
        } finally { collector.cancelAndJoin() }
    }

    @Test fun `nested windows release once and cancellation exposes the final committed state`() = runBlocking {
        val gate = MediaReadRefreshCoordinator()
        val changes = MutableSharedFlow<Int>(replay = 1).apply { emit(0) }
        val reads = Channel<Int>(Channel.UNLIMITED)
        var committed = 0
        val collector = launch { gate.refreshes(changes).collect { reads.send(committed) } }
        try {
            withTimeout(5000) { reads.receive() }
            val outer = gate.hold()
            val inner = gate.hold()
            committed = 23
            changes.emit(committed)
            repeat(5) { yield() }
            inner.close()
            inner.close()
            repeat(5) { yield() }
            assertTrue(gate.isHeld)
            assertTrue(reads.tryReceive().isFailure)
            outer.close()
            assertEquals(23, withTimeout(5000) { reads.receive() })
            assertFalse(gate.isHeld)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun `manual label changes publish immediately without releasing the running transfer`() = runBlocking {
        val gate = MediaReadRefreshCoordinator()
        val changes = MutableSharedFlow<Int>(replay = 1).apply { emit(0) }
        val reads = Channel<Int>(Channel.UNLIMITED)
        var committed = 0
        val batch = gate.hold()
        val collector = launch { gate.refreshes(changes).collect { reads.send(committed) } }
        try {
            assertEquals(0, withTimeout(5000) { reads.receive() })
            committed = 7
            changes.emit(committed)
            repeat(5) { yield() }
            assertTrue(reads.tryReceive().isFailure)
            gate.publish()
            assertEquals(7, withTimeout(5000) { reads.receive() })
            assertTrue(gate.isHeld)
            val idle = async { gate.awaitIdle() }
            yield()
            assertFalse(idle.isCompleted)
            batch.close()
            withTimeout(5000) { idle.await() }
        } finally { batch.close(); collector.cancelAndJoin() }
    }

    @Test fun `ordinary idle invalidations remain immediate without a transfer`() = runBlocking {
        val gate = MediaReadRefreshCoordinator()
        val changes = MutableSharedFlow<Int>(replay = 1).apply { emit(0) }
        val reads = Channel<Int>(Channel.UNLIMITED)
        var committed = 0
        val collector = launch { gate.refreshes(changes).collect { reads.send(committed) } }
        try {
            assertEquals(0, withTimeout(5000) { reads.receive() })
            for (version in 1..10) {
                committed = version
                changes.emit(version)
                assertEquals(version, withTimeout(5000) { reads.receive() })
            }
        } finally { collector.cancelAndJoin() }
    }
}
