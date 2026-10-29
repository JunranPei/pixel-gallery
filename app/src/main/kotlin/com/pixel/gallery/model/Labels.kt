package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.LabelEntry
import com.pixel.gallery.data.local.entity.MediaLabelEntry
import com.pixel.gallery.data.local.entity.MediaEntry
import java.text.Normalizer
import java.util.Locale

data class LabelSummary(
    val label: LabelEntry,
    val count: Int,
    val cover: MediaEntry? = null,
    val lastModified: Long = 0L,
)

/** Counts and covers use the same already-filtered collection as the normal grid. */
fun buildLabelSummaries(
    labels: List<LabelEntry>,
    memberships: List<MediaLabelEntry>,
    visibleMedia: List<MediaEntry>
): List<LabelSummary> {
    val mediaById = visibleMedia.associateBy { it.contentId }
    val membersByLabel = memberships.asSequence()
        .filter { it.contentId in mediaById }
        .groupBy { it.labelId }
    val coverOrder = compareBy<MediaEntry> { it.bestTimestamp }.thenBy { it.contentId }
    return labels.map { label ->
        val members = membersByLabel[label.id].orEmpty().mapNotNull { mediaById[it.contentId] }
        LabelSummary(
            label = label,
            count = members.size,
            cover = members.maxWithOrNull(coverOrder),
            lastModified = members.maxOfOrNull { it.chronologicalTimestamp() } ?: 0L,
        )
    }
}

/** Only explicitly changed rows are present; an untouched mixed row stays mixed. */
data class LabelEditRequest(
    val changes: Map<Long, Boolean> = emptyMap(),
    val newLabelNames: List<String> = emptyList()
)

enum class LabelSelection { NONE, SOME, ALL }

fun commonLabelIds(selectedIds: Set<Long>, memberships: List<MediaLabelEntry>): Set<Long> {
    if (selectedIds.isEmpty()) return emptySet()
    return memberships.asSequence().filter { it.contentId in selectedIds }
        .groupBy { it.labelId }
        .filterValues { links -> links.mapTo(hashSetOf()) { it.contentId }.size == selectedIds.size }
        .keys
}

fun labelSelection(
    labelId: Long,
    selectedIds: Set<Long>,
    memberships: List<MediaLabelEntry>
): LabelSelection {
    if (selectedIds.isEmpty()) return LabelSelection.NONE
    val matching = memberships.asSequence()
        .filter { it.labelId == labelId && it.contentId in selectedIds }
        .map { it.contentId }
        .toSet().size
    return when (matching) {
        0 -> LabelSelection.NONE
        selectedIds.size -> LabelSelection.ALL
        else -> LabelSelection.SOME
    }
}

fun normalizeLabelName(name: String): String =
    Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

fun isValidLabelName(name: String): Boolean =
    name.trim().isNotEmpty() && name.trim().length <= 60 && name.none { it.isISOControl() }
