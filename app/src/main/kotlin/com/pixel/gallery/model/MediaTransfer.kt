package com.pixel.gallery.model

import com.pixel.gallery.data.local.entity.MediaEntry
import java.io.File
import java.text.Normalizer
import java.util.Locale
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

enum class TransferMode {
    COPY,
    MOVE
}

enum class ConflictPolicy {
    KEEP_BOTH,
    SKIP,
    REPLACE
}

enum class ReplacementStage {
    CREATED,
    TEMP_READY,
    TARGET_BACKED_UP,
    TARGET_COMMITTED,
    SOURCE_REMOVED
}

enum class ReplacementRecoveryAction {
    CLEAN_UNCOMMITTED,
    ROLLBACK_TO_BACKUP,
    KEEP_COMMITTED
}

fun replacementRecoveryAction(
    stage: ReplacementStage,
    mode: TransferMode,
    sourceExists: Boolean
): ReplacementRecoveryAction = when (stage) {
    ReplacementStage.CREATED,
    ReplacementStage.TEMP_READY -> ReplacementRecoveryAction.CLEAN_UNCOMMITTED
    ReplacementStage.TARGET_BACKED_UP -> ReplacementRecoveryAction.ROLLBACK_TO_BACKUP
    ReplacementStage.TARGET_COMMITTED -> {
        if (mode == TransferMode.MOVE && sourceExists) {
            ReplacementRecoveryAction.ROLLBACK_TO_BACKUP
        } else {
            ReplacementRecoveryAction.KEEP_COMMITTED
        }
    }
    ReplacementStage.SOURCE_REMOVED -> ReplacementRecoveryAction.KEEP_COMMITTED
}

data class TransferDestination(
    val stableKey: String,
    val displayName: String,
    val path: String,
    val documentUri: String? = null,
    val coverUri: String? = null,
    val itemCount: Int = 0,
    val lastModified: Long = 0L
)

data class TransferProgress(
    val mode: TransferMode,
    val completed: Int,
    val total: Int,
    val currentName: String
)

data class TransferItemFailure(
    val entry: MediaEntry,
    val reason: String
)

data class TransferSummary(
    val mode: TransferMode,
    val succeeded: Int,
    val skipped: Int,
    val failures: List<TransferItemFailure>
) {
    val failed: Int get() = failures.size
    val completedAny: Boolean get() = succeeded > 0
}

private val transferSearchPinyinFormat = HanyuPinyinOutputFormat().apply {
    caseType = HanyuPinyinCaseType.LOWERCASE
    toneType = HanyuPinyinToneType.WITHOUT_TONE
    vCharType = HanyuPinyinVCharType.WITH_V
}

fun matchesTransferDestinationQuery(
    destination: TransferDestination,
    query: String
): Boolean {
    val normalizedQuery = normalizeTransferSearchText(query)
    if (normalizedQuery.isEmpty()) return true

    val normalizedName = normalizeTransferSearchText(destination.displayName)
    return normalizedName.startsWith(normalizedQuery) ||
        transferDestinationPinyinCandidates(normalizedName).any { candidate ->
            candidate.startsWith(normalizedQuery)
        }
}

private fun transferDestinationPinyinCandidates(value: String): Set<String> {
    var candidates = linkedSetOf("")
    value.forEach { character ->
        val pronunciations = runCatching {
            PinyinHelper.toHanyuPinyinStringArray(character, transferSearchPinyinFormat)
        }.getOrNull()
            ?.map(::normalizeTransferSearchText)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
            .ifEmpty { listOf(character.toString()) }

        candidates = candidates
            .flatMapTo(linkedSetOf()) { prefix ->
                pronunciations.map { pronunciation -> prefix + pronunciation }
            }
            .take(64)
            .toCollection(linkedSetOf())
    }
    return candidates
}

private fun normalizeTransferSearchText(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

fun buildTransferDestinations(entries: List<MediaEntry>): List<TransferDestination> =
    entries
        .filter { it.path.isNotBlank() }
        .groupBy { File(it.path).parentFile?.absolutePath?.trimEnd(File.separatorChar).orEmpty() }
        .filterKeys { it.isNotEmpty() }
        .map { (path, albumEntries) ->
            val cover = albumEntries.maxByOrNull { it.bestTimestamp } ?: albumEntries.first()
            TransferDestination(
                stableKey = path,
                displayName = File(path).name.ifEmpty { path },
                path = path,
                coverUri = cover.uri,
                itemCount = albumEntries.size,
                lastModified = albumEntries.maxOfOrNull { it.dateModifiedMillis } ?: 0L
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

fun getAvailableTransferName(originalName: String, exists: (String) -> Boolean): String {
    if (!exists(originalName)) return originalName
    val dot = originalName.lastIndexOf('.')
    val baseName = if (dot > 0) originalName.substring(0, dot) else originalName
    val extension = if (dot > 0) originalName.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = "$baseName ($index)$extension"
        if (!exists(candidate)) return candidate
        index++
    }
}

fun isVerifiedTransferSize(expectedBytes: Long, actualBytes: Long): Boolean =
    expectedBytes >= 0L && actualBytes == expectedBytes
