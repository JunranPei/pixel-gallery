package com.pixel.gallery.ui.viewmodel

import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.data.repository.LabelRepository
import com.pixel.gallery.data.repository.MediaRepository
import com.pixel.gallery.data.repository.SettingsRepository
import com.pixel.gallery.model.AlbumSortOrder
import com.pixel.gallery.model.AlbumSorter
import com.pixel.gallery.model.buildLabelSummaries
import com.pixel.gallery.model.findCoverMedia
import com.pixel.gallery.model.labelCoverKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** All windows use the same global label visibility, membership and cover settings. */
@Singleton
class SharedLabelCollections @Inject constructor(
    labels: LabelRepository,
    media: MediaRepository,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val data = LabelCollectionStreams(scope, labels.labels, labels.memberships, media.loadedEntries.filterNotNull(),
        settings.hiddenFolders, settings.excludedFolders, media.vaultEntries,
        settings.labelSortOrder, settings.collectionCovers)

    // Isolated instrumentation owns its manually constructed instance.
    internal fun close() = scope.cancel()
}

internal class LabelCollectionStreams(
    scope: CoroutineScope,
    entries: Flow<List<LabelEntry>>,
    links: Flow<List<MediaLabelEntry>>,
    media: Flow<List<MediaEntry>>,
    hidden: Flow<Set<String>>,
    excluded: Flow<Set<String>>,
    vault: Flow<List<MediaEntry>>,
    sort: Flow<String>,
    covers: Flow<Map<String, String>>,
) {
    private val loadedEntries = entries.stateIn(scope, SharingStarted.Eagerly, null)
    private val loadedMemberships = links.stateIn(scope, SharingStarted.Eagerly, null)
    val labelEntries = loadedEntries.filterNotNull().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val memberships = loadedMemberships.filterNotNull().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val labelIdsByMedia = memberships.map { membership ->
        membership.groupBy({ it.contentId }, { it.labelId })
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val sortMode = sort.map { AlbumSortOrder.fromStored(it) }
        .stateIn(scope, SharingStarted.Eagerly, AlbumSortOrder.NAME_ASC)
    private val visibleMedia = combine(media, hidden, excluded, vault) { rows, hiddenFolders, excludedFolders, locked ->
        com.pixel.gallery.utils.GroupWorkProbe.hit("label_visibility_filter")
        filterVisibleLabelMedia(rows, hiddenFolders + excludedFolders, locked.mapTo(hashSetOf()) { it.contentId })
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val loadedLabels = combine(loadedEntries.filterNotNull(), loadedMemberships.filterNotNull(), visibleMedia.filterNotNull(), sortMode, covers) { rows, membership, visible, order, choices ->
        com.pixel.gallery.utils.GroupWorkProbe.hit("labels_rebuild")
        val requested = rows.mapNotNullTo(hashSetOf()) { choices[labelCoverKey(it.id)] }
        val byUri = findCoverMedia(visible, requested)
        val summaries = buildLabelSummaries(rows, membership, visible).map { summary ->
            val manual = choices[labelCoverKey(summary.label.id)]?.let(byUri::get)
                ?.takeIf { candidate -> membership.any { it.labelId == summary.label.id && it.contentId == candidate.contentId } }
            if (manual == null) summary else summary.copy(cover = manual)
        }
        AlbumSorter.shared.sortLabels(summaries, order)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val labels = loadedLabels.filterNotNull().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun mediaForLabel(labelId: Long): Flow<List<MediaEntry>> = combine(visibleMedia.filterNotNull(), loadedMemberships.filterNotNull()) { rows, membership ->
        val ids = membership.asSequence().filter { it.labelId == labelId }.map { it.contentId }.toHashSet()
        if (ids.isEmpty()) emptyList() else rows.filter { it.contentId in ids }
    }.flowOn(Dispatchers.Default)
}
