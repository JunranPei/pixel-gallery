package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.model.LabelSummary
import com.pixel.gallery.model.labelCoverKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

class SharedLabelCollectionsTest {
    private fun photo(id: Long) = MediaEntry(id, "content://media/$id", "/A/$id.jpg",
        "image/jpeg", 100, 100, 0, 100, 1, 1, bestTimestamp = id)

    @Test fun delayedInputsDoNotPublishAnEmptyLoadedLabelCollection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mediaReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val linksReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val data = LabelCollectionStreams(scope,
            kotlinx.coroutines.flow.flowOf(listOf(LabelEntry(2, "Trip", "trip"))),
            flow { linksReady.await(); emit(listOf(MediaLabelEntry(2, 1))) },
            flow { mediaReady.await(); emit(listOf(photo(1))) },
            MutableStateFlow(emptySet()), MutableStateFlow(emptySet()), MutableStateFlow(emptyList()),
            MutableStateFlow("NAME_ASC"), MutableStateFlow(emptyMap()))
        try {
            withTimeout(5000) { data.labelEntries.first { it.isNotEmpty() } }
            org.junit.Assert.assertNull(data.loadedLabels.value)
            mediaReady.complete(Unit)
            org.junit.Assert.assertNull(kotlinx.coroutines.withTimeoutOrNull(100) { data.loadedLabels.first { it != null } })
            linksReady.complete(Unit)
            val loaded = withTimeout(5000) { data.loadedLabels.first { it != null } }!!
            assertEquals(1, loaded.single().count)
            assertEquals(1L, loaded.single().cover!!.contentId)
        } finally { scope.cancel() }
    }

    @Test fun fiftyConsumersShareReadsAndReceiveVisibilityCoverAndMembershipChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = AtomicInteger()
        val labelReads = AtomicInteger()
        val rows = MutableStateFlow(listOf(photo(1), photo(2)))
        val entries = MutableStateFlow(listOf(LabelEntry(2, "Trip", "trip")))
        val links = MutableStateFlow(listOf(MediaLabelEntry(2, 1), MediaLabelEntry(2, 2)))
        val hidden = MutableStateFlow(emptySet<String>())
        val vault = MutableStateFlow(emptyList<MediaEntry>())
        val covers = MutableStateFlow(emptyMap<String, String>())
        val data = LabelCollectionStreams(scope,
            flow { labelReads.incrementAndGet(); emitAll(entries) },
            flow { reads.incrementAndGet(); emitAll(links) }, rows, hidden,
            MutableStateFlow(emptySet()), vault, MutableStateFlow("NAME_ASC"), covers)
        val observed = AtomicReferenceArray<List<LabelSummary>?>(50)
        try {
            repeat(50) { index -> scope.launch { data.labels.collect { observed.set(index, it) } } }
            suspend fun waitFor(count: Int, cover: Long?) {
                withTimeout(5000) {
                    while ((0 until 50).any { i -> observed.get(i)?.singleOrNull()?.let {
                        it.count != count || it.cover?.contentId != cover
                    } != false }) delay(5)
                }
                repeat(50) { assertSame(data.labels.value, observed.get(it)) }
            }
            waitFor(2, 2)
            val originalIds = withTimeout(5000) { data.labelIdsByMedia.first { it.size == 2 } }
            assertEquals(listOf(2L), originalIds[1L])
            assertEquals(listOf(2L), originalIds[2L])
            assertEquals(1, reads.get())
            assertEquals(1, labelReads.get())
            covers.value = mapOf(labelCoverKey(2) to photo(1).uri)
            waitFor(2, 1)
            hidden.value = setOf("/A")
            waitFor(0, null)
            hidden.value = emptySet()
            waitFor(2, 1)
            vault.value = listOf(photo(1))
            waitFor(1, 2)
            vault.value = emptyList()
            covers.value = emptyMap()
            rows.value = listOf(photo(1).copy(bestTimestamp = 50), photo(2))
            waitFor(2, 1)
            links.value = listOf(MediaLabelEntry(2, 2))
            waitFor(1, 2)
            val labelMedia = withTimeout(5000) { data.mediaForLabel(2).first { it.size == 1 } }
            assertEquals(2L, labelMedia.single().contentId)
            assertEquals(mapOf(2L to listOf(2L)), withTimeout(5000) { data.labelIdsByMedia.first { it.size == 1 } })
            entries.value = emptyList()
            withTimeout(5000) { data.labels.first { it.isEmpty() } }
            assertEquals(1, reads.get())
            assertEquals(1, labelReads.get())
        } finally {
            scope.cancel()
        }
    }
}
