package com.pixel.gallery.data.local

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val INSERT_FAVOURITES_LABEL =
    "INSERT OR IGNORE INTO labels (id, name, normalizedName, systemKind, sortOrder) VALUES (1, 'Favourites', 'favourites', 'FAVOURITES', 0)"

// Kept as concrete SQL so the upgrade can also be exercised against SQLite
// without an Android device. The existing v3 tables, including vault, stay intact.
internal val LABEL_MIGRATION_3_4_SQL = listOf(
    """CREATE TABLE IF NOT EXISTS labels (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, normalizedName TEXT NOT NULL, systemKind TEXT, sortOrder INTEGER NOT NULL)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_labels_normalizedName ON labels (normalizedName)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_labels_systemKind ON labels (systemKind)""",
    """CREATE TABLE IF NOT EXISTS media_labels (labelId INTEGER NOT NULL, contentId INTEGER NOT NULL, PRIMARY KEY(labelId, contentId), FOREIGN KEY(labelId) REFERENCES labels(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE INDEX IF NOT EXISTS index_media_labels_contentId ON media_labels (contentId)""",
    INSERT_FAVOURITES_LABEL,
    """INSERT OR IGNORE INTO media_labels (labelId, contentId) SELECT 1, id FROM favourites"""
)

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LABEL_MIGRATION_3_4_SQL.forEach(db::execSQL)
    }
}

val LABEL_DATABASE_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(INSERT_FAVOURITES_LABEL)
    }
}
