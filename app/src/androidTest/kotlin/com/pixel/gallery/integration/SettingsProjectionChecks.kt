package com.pixel.gallery.integration

import android.app.Instrumentation
import com.pixel.gallery.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real DataStore: unrelated writes stay silent; relevant edits still propagate. */
internal class SettingsProjectionChecks(private val runner: Instrumentation) {
    fun exercise(): String = runBlocking {
        val settings = SettingsRepository(runner.targetContext)
        val original = settings.albumGrouping.first()
        val token = "projection-${UUID.randomUUID()}"
        val path = "/$token"
        val covers = AtomicInteger()
        val excluded = AtomicInteger()
        val hidden = AtomicInteger()
        val jobs = listOf(
            launch(Dispatchers.Default) { settings.collectionCovers.collect { covers.incrementAndGet() } },
            launch(Dispatchers.Default) { settings.excludedFolders.collect { excluded.incrementAndGet() } },
            launch(Dispatchers.Default) { settings.hiddenFolderRoots.collect { hidden.incrementAndGet() } },
        )
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(5000) {
            while (!condition()) delay(10)
        }
        try {
            waitFor { covers.get() == 1 && excluded.get() == 1 && hidden.get() == 1 }
            repeat(10) { index ->
                settings.updateAlbumGrouping { state -> state.copy(collapsedIds =
                    if (index % 2 == 0) state.collapsedIds + token else state.collapsedIds - token) }
                delay(40)
            }
            delay(250)
            check(covers.get() == 1 && excluded.get() == 1 && hidden.get() == 1) {
                "Group-only writes leaked: covers=${covers.get()}, excluded=${excluded.get()}, hidden=${hidden.get()} (expected 1 initial each)"
            }
            settings.setCollectionCover(token, "content://projection/test")
            waitFor { covers.get() == 2 }
            check(settings.collectionCovers.first()[token] == "content://projection/test")
            settings.addExcludedFolder(path)
            waitFor { excluded.get() == 2 }
            check(path in settings.excludedFolders.first())
            settings.addHiddenFolder(path)
            waitFor { hidden.get() == 2 }
            check(path in settings.hiddenFolderRoots.first())
            delay(150)
            check(covers.get() == 2 && excluded.get() == 2 && hidden.get() == 2)
            settings.setCollectionCover(token, null)
            settings.removeExcludedFolder(path)
            settings.removeHiddenFolder(path)
            waitFor { covers.get() == 3 && excluded.get() == 3 && hidden.get() == 3 }
            "10 group-only writes produced no cover/visibility emissions; real cover, exclusion and hidden edits/removals each propagated once."
        } finally {
            jobs.forEach { it.cancel() }
            settings.setCollectionCover(token, null)
            settings.removeExcludedFolder(path)
            settings.removeHiddenFolder(path)
            settings.updateAlbumGrouping { original }
        }
    }
}
