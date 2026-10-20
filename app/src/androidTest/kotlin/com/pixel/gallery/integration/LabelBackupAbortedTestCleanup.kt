package com.pixel.gallery.integration

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** Explicit recovery for a crashed test run; a single exact generated UUID is required. */
internal fun cleanupAbortedLabelBackupTest(context: Context, token: String): String {
    require(UUID.fromString(token).toString() == token)
    val relative = "Pictures/LabelBackupEdge_$token/"
    val uri = MediaStore.Files.getContentUri("external")
    val ids = mutableListOf<Long>()
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH), Bundle().apply {
        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path LIKE ? AND media_type IN (1,3)")
        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$relative%"))
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }, null)!!.use { cursor ->
        while (cursor.moveToNext()) {
            check(cursor.getString(1).startsWith(relative))
            ids += cursor.getLong(0)
        }
    }
    ids.forEach { context.contentResolver.delete(ContentUris.withAppendedId(uri, it), null, null) }
    val root = File("/sdcard/$relative")
    if (root.exists()) root.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
    return "Removed ${ids.size} media files belonging only to aborted test token $token"
}
