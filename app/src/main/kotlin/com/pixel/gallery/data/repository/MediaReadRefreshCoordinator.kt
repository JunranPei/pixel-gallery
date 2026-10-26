package com.pixel.gallery.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** Batch presentation reads, never the durable per-file database writes. */
@Singleton
class MediaReadRefreshCoordinator @Inject constructor() {
    private data class Window(val holders: Int = 0, val revision: Long = 0)
    private val window = MutableStateFlow(Window())
    private val lock = Any()
    val isHeld: Boolean get() = window.value.holders > 0

    fun hold(): AutoCloseable {
        synchronized(lock) { window.value = window.value.copy(holders = window.value.holders + 1) }
        return object : AutoCloseable {
            private var closed = false
            override fun close() = synchronized(lock) {
                if (!closed) {
                    closed = true
                    window.value = window.value.copy(holders = window.value.holders - 1)
                }
            }
        }
    }

    fun publish() = synchronized(lock) { window.value = window.value.copy(revision = window.value.revision + 1) }

    suspend fun awaitIdle() { window.first { it.holders == 0 } }

    fun refreshes(changes: Flow<*>): Flow<Unit> = flow {
        var initial = true
        var revision = -1L
        combine(changes, window) { _, state -> state }.collect { state ->
            if (initial || state.holders == 0 || state.revision != revision) {
                initial = false
                revision = state.revision
                emit(Unit)
            }
        }
    }.conflate()
}
