"""Exercise the production v3->v4 SQL with a real SQLite database.

Run: python app/src/test/python/test_label_migration.py
This verifies SQLite behavior; the Android build still validates Room's schema.
"""
import pathlib
import re
import sqlite3
import unittest


MAIN = pathlib.Path(__file__).resolve().parents[2] / "main" / "kotlin" / "com" / "pixel" / "gallery"
MIGRATION = (MAIN / "data" / "local" / "LabelMigration.kt").read_text(encoding="utf-8")
DAO = (MAIN / "data" / "local" / "dao" / "MediaDao.kt").read_text(encoding="utf-8")


def migration_statements():
    seed = re.search(r'internal const val INSERT_FAVOURITES_LABEL\s*=\s*"([^"]+)"', MIGRATION).group(1)
    block = MIGRATION.split("internal val LABEL_MIGRATION_3_4_SQL = listOf(", 1)[1].split("\n)", 1)[0]
    return [seed if token == "INSERT_FAVOURITES_LABEL" else token[3:-3]
            for token in re.findall(r'""".*?"""|INSERT_FAVOURITES_LABEL', block, re.DOTALL)]


def query_for(method):
    pattern = r'@Query\("([^"]+)"\)\s*(?:suspend\s+)?fun\s+' + method + r'\('
    return re.search(pattern, DAO).group(1)


class LabelMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        # Only columns relevant to the upgrade are needed: these v3 tables
        # deliberately contain user data that must not be rebuilt or deleted.
        self.db.executescript("""
            CREATE TABLE media_entries(contentId INTEGER PRIMARY KEY, path TEXT, isTrashed INTEGER);
            CREATE TABLE favourites(id INTEGER PRIMARY KEY NOT NULL);
            CREATE TABLE vault(id INTEGER PRIMARY KEY, vaultPath TEXT, originalPath TEXT, entryJson TEXT);
            INSERT INTO media_entries VALUES (10, '/DCIM/a.jpg', 0), (20, '/DCIM/b.jpg', 1);
            INSERT INTO favourites VALUES (10), (20), (99);
            INSERT INTO vault VALUES (99, '/vault/99', '/DCIM/secret.jpg', '{"contentId":99}');
        """)
        for statement in migration_statements():
            self.db.execute(statement)
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def test_upgrade_keeps_favourites_including_trashed_and_vault_files(self):
        self.assertEqual([(1, 10), (1, 20), (1, 99)], self.db.execute(
            "SELECT labelId, contentId FROM media_labels ORDER BY contentId").fetchall())
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM vault WHERE id=99").fetchone()[0])
        self.assertEqual(3, self.db.execute("SELECT COUNT(*) FROM favourites").fetchone()[0])
        self.assertEqual(2, self.db.execute("SELECT COUNT(*) FROM media_entries").fetchone()[0])

    def test_refreshing_or_removing_media_cache_preserves_user_labels(self):
        self.db.execute("INSERT OR REPLACE INTO media_entries VALUES (10, '/Pictures/a.jpg', 0)")
        self.db.execute("DELETE FROM media_entries WHERE contentId=20")
        self.assertEqual(3, self.db.execute("SELECT COUNT(*) FROM media_labels").fetchone()[0])

    def test_only_label_deletion_cascades_to_its_members(self):
        self.db.execute("INSERT INTO labels VALUES (2, 'Travel', 'travel', NULL, 1)")
        self.db.execute("INSERT INTO media_labels VALUES (2, 10)")
        self.db.execute("DELETE FROM labels WHERE id=2")
        self.assertEqual(3, self.db.execute("SELECT COUNT(*) FROM media_labels").fetchone()[0])
        self.assertEqual(2, self.db.execute("SELECT COUNT(*) FROM media_entries").fetchone()[0])

    def test_old_favourite_api_sql_uses_the_new_single_source(self):
        self.db.execute(query_for("removeFavourite"), {"id": 10})
        self.assertEqual(0, self.db.execute(query_for("isFavourite"), {"id": 10}).fetchone()[0])
        # The preserved old table is deliberately not read or dual-written.
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM favourites WHERE id=10").fetchone()[0])
        self.db.execute(query_for("addFavouriteLabel"), {"id": 30})
        self.assertEqual(1, self.db.execute(query_for("isFavourite"), {"id": 30}).fetchone()[0])

    def test_remap_queries_merge_every_label_before_removing_old_links(self):
        self.db.execute("INSERT INTO labels VALUES (2, 'Travel', 'travel', NULL, 1)")
        self.db.execute("INSERT INTO media_labels VALUES (2, 10), (2, 40)")
        self.db.execute(query_for("copyMediaLabels"), {"oldId": 10, "newId": 40})
        self.db.execute(query_for("deleteMediaLabels"), {"id": 10})
        self.assertEqual([(1,), (2,)], self.db.execute(
            "SELECT labelId FROM media_labels WHERE contentId=40 ORDER BY labelId").fetchall())
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM media_labels WHERE contentId=10").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
