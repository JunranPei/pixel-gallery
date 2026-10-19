package com.pixel.gallery.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.pixel.gallery.model.DuplicateFingerprintStore
import com.pixel.gallery.model.DuplicateIndexRecord

/** Disposable content index, separate from the database containing favourites and labels. */
internal class PersistentDuplicateIndex(context: Context) : DuplicateFingerprintStore {
    private val helper = Helper(context)
    private var unavailable = false

    override suspend fun loadDuplicateCandidates(uris: List<String>): Map<String, DuplicateIndexRecord> =
        access(emptyMap()) { db ->
            val wanted = uris.toHashSet()
            val result = HashMap<String, DuplicateIndexRecord>()
            db.rawQuery("SELECT uri,path,signature,size,sample,sha256 FROM fingerprints WHERE sha256 IN " +
                "(SELECT sha256 FROM fingerprints WHERE sha256 IS NOT NULL GROUP BY sha256,size HAVING COUNT(*) > 1)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = cursor.getString(0)
                    if (uri in wanted) result[uri] = DuplicateIndexRecord(uri, cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), cursor.getString(4), cursor.getString(5))
                }
            }
            result
        }

    override suspend fun load(uris: List<String>): Map<String, DuplicateIndexRecord> =
        access(emptyMap()) { db ->
            val result = HashMap<String, DuplicateIndexRecord>()
            for (batch in uris.chunked(400)) {
                db.query("fingerprints", arrayOf("uri", "path", "signature", "size", "sample", "sha256"),
                    "uri IN (${batch.joinToString(",") { "?" }})", batch.toTypedArray(), null, null, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = DuplicateIndexRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                            cursor.getLong(3), cursor.getString(4), cursor.getString(5))
                        result[row.key] = row
                    }
                }
            }
            result
        }

    override suspend fun save(records: List<DuplicateIndexRecord>) {
        if (records.isEmpty()) return
        access(Unit) { db ->
            db.beginTransaction()
            try {
                db.compileStatement("INSERT OR REPLACE INTO fingerprints (uri,path,signature,size,sample,sha256) VALUES (?,?,?,?,?,?)").use { statement ->
                    for (row in records) {
                        statement.clearBindings()
                        statement.bindString(1, row.key); statement.bindString(2, row.path)
                        statement.bindString(3, row.signature); statement.bindLong(4, row.size)
                        row.sample?.let { statement.bindString(5, it) }
                        row.sha256?.let { statement.bindString(6, it) }
                        statement.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    override suspend fun remove(uris: List<String>) {
        access(Unit) { db ->
            for (batch in uris.chunked(400)) {
                db.delete("fingerprints", "uri IN (${batch.joinToString(",") { "?" }})", batch.toTypedArray())
            }
        }
    }

    override suspend fun retain(keys: Set<String>) {
        val obsolete = access(emptyList<String>()) { db ->
            buildList {
                db.query("fingerprints", arrayOf("uri"), null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) { val key = cursor.getString(0); if (key !in keys) add(key) }
                }
            }
        }
        remove(obsolete)
    }

    private fun <T> access(fallback: T, block: (SQLiteDatabase) -> T): T {
        if (unavailable) return fallback
        return try { block(helper.writableDatabase) } catch (_: SQLiteException) {
            // A full disk or unusable index must not turn into a false "no duplicates" result.
            // Continuing without the cache is slower, but the content checks still run.
            unavailable = true
            fallback
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "duplicate-content-index-v1.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE fingerprints (uri TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, signature TEXT NOT NULL, size INTEGER NOT NULL, sample TEXT, sha256 TEXT)")
            db.execSQL("CREATE INDEX fingerprint_content ON fingerprints(sha256,size)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
