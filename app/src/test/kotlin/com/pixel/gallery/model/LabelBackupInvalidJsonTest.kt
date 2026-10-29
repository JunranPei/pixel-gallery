package com.pixel.gallery.model

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LabelBackupInvalidJsonTest(private val description: String, private val input: String) {
    @Test fun rejectsBeforeRestoringAnything() {
        assertTrue("Accepted $description", runCatching { LabelBackupJson.read(input.reader()) }.isFailure)
    }

    companion object {
        private val hash = "a".repeat(64)
        private val valid = """{"format":"pixel-gallery-labels","version":1,"exportedAt":"2026-10-20T00:00:00Z","labels":[{"key":"tag","name":"Trip"}],"media":[{"key":"file","sha256":"$hash","sizeBytes":100,"fileName":"a.jpg","relativePath":"Pictures/a.jpg","labelKeys":["tag"]}]}"""
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<String>> {
            val cases = linkedMapOf(
                "empty" to "", "whitespace" to " \n\t", "truncated" to valid.dropLast(8),
                "null root" to "null", "array root" to "[]", "string root" to "\"text\"",
                "trailing garbage" to "$valid nope", "second document" to "$valid $valid",
                "comment" to "/* backup */$valid", "unquoted field" to valid.replace("\"version\"", "version"),
                "single quoted value" to valid.replace("\"Trip\"", "'Trip'"),
                "duplicate version" to valid.replace("\"version\":1", "\"version\":99,\"version\":1"),
                "duplicate nested name" to valid.replace("\"name\":\"Trip\"", "\"name\":\"Other\",\"name\":\"Trip\""),
                "wrong format" to valid.replace("pixel-gallery-labels", "another-app"),
                "future version" to valid.replace("\"version\":1", "\"version\":2"),
                "fractional version" to valid.replace("\"version\":1", "\"version\":1.5"),
                "string version" to valid.replace("\"version\":1", "\"version\":\"1\""),
                "null labels" to valid.replace("\"labels\":[{\"key\":\"tag\",\"name\":\"Trip\"}]", "\"labels\":null"),
                "missing media" to valid.substringBefore(",\"media\"") + "}",
                "wrong media type" to valid.replace("\"media\":[", "\"media\":{\"bad\":[").dropLast(1) + "}}",
                "null label name" to valid.replace("\"name\":\"Trip\"", "\"name\":null"),
                "control label name" to valid.replace("\"Trip\"", "\"Trip\\nOther\""),
                "blank label name" to valid.replace("\"Trip\"", "\"   \""),
                "long label name" to valid.replace("\"Trip\"", "\"${"x".repeat(61)}\""),
                "unpaired high surrogate" to valid.replace("\"Trip\"", "\"bad\\uD800\""),
                "unpaired low surrogate" to valid.replace("\"Trip\"", "\"bad\\uDC00\""),
                "unknown system kind" to valid.replace("\"name\":\"Trip\"", "\"name\":\"Trip\",\"systemKind\":\"OTHER\""),
                "short hash" to valid.replace(hash, "abc"), "nonhex hash" to valid.replace(hash, "z".repeat(64)),
                "negative size" to valid.replace("\"sizeBytes\":100", "\"sizeBytes\":-1"),
                "fractional size" to valid.replace("\"sizeBytes\":100", "\"sizeBytes\":100.5"),
                "overflow size" to valid.replace("\"sizeBytes\":100", "\"sizeBytes\":9223372036854775808"),
                "exponent overflow" to valid.replace("\"sizeBytes\":100", "\"sizeBytes\":1e9999999999"),
                "string size" to valid.replace("\"sizeBytes\":100", "\"sizeBytes\":\"100\""),
                "negative skipped" to valid.dropLast(1) + ",\"skippedMedia\":-1}",
                "fractional skipped" to valid.dropLast(1) + ",\"skippedMedia\":1.1}",
                "overflow skipped" to valid.dropLast(1) + ",\"skippedMedia\":2147483648}",
                "duplicate relationship" to valid.replace("\"labelKeys\":[\"tag\"]", "\"labelKeys\":[\"tag\",\"tag\"]"),
                "empty relationship" to valid.replace("\"labelKeys\":[\"tag\"]", "\"labelKeys\":[]"),
                "dangling relationship" to valid.replace("\"labelKeys\":[\"tag\"]", "\"labelKeys\":[\"missing\"]"),
                "nonstring relationship" to valid.replace("\"labelKeys\":[\"tag\"]", "\"labelKeys\":[1]"),
                "blank filename" to valid.replace("\"fileName\":\"a.jpg\"", "\"fileName\":\"\""),
                "oversize filename" to valid.replace("\"fileName\":\"a.jpg\"", "\"fileName\":\"${"x".repeat(1025)}\""),
                "oversize path" to valid.replace("Pictures/a.jpg", "x".repeat(4097)),
                "deep unknown field" to valid.dropLast(1) + ",\"extra\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}",
            )
            return cases.map { arrayOf(it.key, it.value) }
        }
    }
}
