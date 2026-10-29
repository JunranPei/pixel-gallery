package com.pixel.gallery.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

const val LABEL_BACKUP_FORMAT = "pixel-gallery-labels"
const val LABEL_BACKUP_VERSION = 1

data class BackupLabel(val key: String, val name: String, val systemKind: String? = null)
data class BackupMedia(
    val key: String,
    val sha256: String,
    val sizeBytes: Long,
    val fileName: String,
    val relativePath: String,
    val labelKeys: List<String>,
)
data class LabelBackup(
    val format: String = LABEL_BACKUP_FORMAT,
    val version: Int = LABEL_BACKUP_VERSION,
    val exportedAt: String,
    val labels: List<BackupLabel>,
    val media: List<BackupMedia>,
    val skippedMedia: Int = 0,
)

/** Versioned, portable JSON. MediaStore IDs are deliberately never used to restore links. */
object LabelBackupJson {
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun read(reader: Reader): LabelBackup {
        val root = readStrictDocument(reader).asJsonObject
        require(root.string("format") == LABEL_BACKUP_FORMAT) { "format" }
        require(root.long("version") == LABEL_BACKUP_VERSION.toLong()) { "version" }
        val rawLabels = root.getAsJsonArray("labels")
        val rawMedia = root.getAsJsonArray("media")
        require(rawLabels.size() <= 5_000 && rawMedia.size() <= 100_000) { "size" }
        val labels = rawLabels.map { element ->
            val obj = element.asJsonObject
            BackupLabel(obj.string("key"), obj.string("name"),
                obj.get("systemKind")?.takeUnless { it.isJsonNull }?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                    it.asString
                })
        }
        require(labels.map { it.key }.toSet().size == labels.size) { "duplicate label" }
        require(labels.map { it.systemKind ?: normalizeLabelName(it.name) }.toSet().size == labels.size) { "duplicate name" }
        require(labels.all { it.key.length in 1..100 && isValidLabelName(it.name) &&
            (it.systemKind == null || it.systemKind == "FAVOURITES") }) { "label" }
        val labelKeys = labels.map { it.key }.toSet()
        var references = 0
        val media = rawMedia.map { element ->
            val obj = element.asJsonObject
            val keys = obj.getAsJsonArray("labelKeys").map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString
            }
            references += keys.size
            require(references <= 2_000_000 && keys.isNotEmpty() && keys.distinct().size == keys.size && keys.all { it in labelKeys }) { "links" }
            BackupMedia(obj.string("key"), obj.string("sha256"), obj.long("sizeBytes"),
                obj.string("fileName"), obj.string("relativePath"), keys).also {
                require(it.key.length in 1..100 && it.sha256.matches(Regex("[0-9a-f]{64}")) && it.sizeBytes >= 0 &&
                    it.fileName.isNotBlank() && it.fileName.length <= 1024 && it.relativePath.length <= 4096) { "media" }
            }
        }
        require(media.map { it.key }.toSet().size == media.size) { "duplicate media" }
        val skipped = root.get("skippedMedia")?.let { root.long("skippedMedia") } ?: 0L
        require(skipped in 0..Int.MAX_VALUE.toLong())
        return LabelBackup(exportedAt = root.string("exportedAt"), labels = labels, media = media, skippedMedia = skipped.toInt())
    }

    private fun JsonObject.string(key: String): String = get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString) { key }
        it.asString
    }

    private fun JsonObject.long(key: String): Long = get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { key }
        // Reject fractions and overflow rather than silently rounding file lengths or versions.
        it.asBigDecimal.longValueExact()
    }

    /** Gson's convenience tree parser is lenient and discards duplicate object keys. */
    private fun readStrictDocument(source: Reader): JsonElement {
        val reader = JsonReader(source).apply { strictness = Strictness.STRICT }
        var nodes = 0
        fun text(value: String): String {
            require(value.length <= 4096) { "string length" }
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character.isHighSurrogate()) {
                    require(index < value.length && value[index++].isLowSurrogate()) { "unpaired surrogate" }
                } else require(!character.isLowSurrogate()) { "unpaired surrogate" }
            }
            return value
        }
        fun value(depth: Int): JsonElement {
            require(depth <= 32 && ++nodes <= 3_000_000) { "document complexity" }
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = text(reader.nextName())
                        require(!has(key)) { "duplicate object field" }
                        add(key, value(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                    reader.beginArray()
                    while (reader.hasNext()) add(value(depth + 1))
                    reader.endArray()
                }
                JsonToken.STRING -> JsonPrimitive(text(reader.nextString()))
                JsonToken.NUMBER -> {
                    val number = reader.nextString()
                    require(number.length <= 128) { "number length" }
                    JsonPrimitive(number.toBigDecimal())
                }
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> throw IllegalArgumentException("Expected JSON value")
            }
        }
        val result = value(0)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "trailing JSON" }
        return result
    }
}

data class LabelMediaFingerprint(
    val contentId: Long,
    val sha256: String,
    val sizeBytes: Long,
    val fileName: String,
    val relativePath: String,
)
data class LabelBackupMatches(
    val matches: Map<String, Long>,
    val missing: List<BackupMedia>,
    val ambiguous: List<BackupMedia>,
)

/** Paths/names break ties only AFTER a full content hash matches. No fuzzy auto-labelling. */
fun matchLabelBackup(backup: LabelBackup, candidates: List<LabelMediaFingerprint>): LabelBackupMatches {
    val matches = linkedMapOf<String, Long>()
    val missing = mutableListOf<BackupMedia>()
    val ambiguous = mutableListOf<BackupMedia>()
    val byContent = candidates.distinctBy { it.contentId }.groupBy { it.sizeBytes to it.sha256 }
    backup.media.groupBy { it.sizeBytes to it.sha256 }.forEach { (content, records) ->
        val remaining = records.toMutableList()
        val available = byContent[content].orEmpty().toMutableList()
        fun assignUnique(sourceKey: (BackupMedia) -> String, targetKey: (LabelMediaFingerprint) -> String) {
            val sources = remaining.groupBy(sourceKey)
            val targets = available.groupBy(targetKey)
            sources.forEach { (key, rows) ->
                val targetsForKey = targets[key].orEmpty()
                if (key.isNotEmpty() && rows.size == 1 && targetsForKey.size == 1) {
                    matches[rows.single().key] = targetsForKey.single().contentId
                    remaining.remove(rows.single())
                    available.remove(targetsForKey.single())
                }
            }
        }
        assignUnique({ it.relativePath }, { it.relativePath })
        assignUnique({ it.fileName }, { it.fileName })
        if (remaining.size == 1 && available.size == 1) {
            matches[remaining.single().key] = available.single().contentId
            remaining.clear()
            available.clear()
        }
        // A used-up duplicate is still unresolved, rather than silently merging its labels.
        if (byContent[content].isNullOrEmpty()) missing += remaining else ambiguous += remaining
    }
    return LabelBackupMatches(matches, missing, ambiguous)
}
