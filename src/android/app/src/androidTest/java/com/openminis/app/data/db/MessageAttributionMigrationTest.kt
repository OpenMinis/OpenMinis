package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [T-token-attribution-snapshot][T-android-downgrade-compat] The 11 ⇄ 12
 * migration pair, exercised against a real SQLite file on the device.
 *
 * ## Why not MigrationTestHelper
 *
 * The obvious tool is Room's `MigrationTestHelper`, and it was tried first. It
 * cannot work here yet: it builds the starting database from the exported
 * schema JSON for that version, and `exportSchema` was only turned on as part
 * of THIS change — so `11.json` does not exist and `createDatabase(db, 11)`
 * fails with "Cannot find the schema file in the assets folder". Back-filling
 * an 11.json by hand would mean hand-writing the schema the test is supposed to
 * verify independently, which defeats the point.
 *
 * From version 12 onward the exported schemas accumulate and MigrationTestHelper
 * becomes usable for 12→13 and beyond. Until then this drives the two migration
 * lambdas directly against a v11-shaped table, which tests the same two things
 * that actually matter:
 *
 * 1. **Upgrade must not lose data.** `ADD COLUMN` should be an O(1) metadata
 *    change; a migration that rebuilds the table silently drops rows.
 * 2. **Downgrade must not crash and must not delete.** Room resolves a
 *    downgrade through `onUpgrade`; with no registered path it throws (app
 *    cannot start) or, with a destructive fallback, calls `dropAllTables`. We
 *    register a no-op, so an older build opens the newer file untouched.
 */
@RunWith(AndroidJUnit4::class)
class MessageAttributionMigrationTest {

    private lateinit var dbFile: File
    private lateinit var db: SQLiteDatabase
    private lateinit var helper: androidx.sqlite.db.SupportSQLiteOpenHelper
    private lateinit var supportDb: androidx.sqlite.db.SupportSQLiteDatabase

    /** The subset of the v11 `messages` schema this test needs. */
    private fun createV11Schema(d: SQLiteDatabase) {
        d.execSQL(
            """
            CREATE TABLE messages (
                id TEXT NOT NULL PRIMARY KEY,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                parts_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                token_usage TEXT,
                sort_order INTEGER NOT NULL,
                reasoning_content TEXT,
                stream_interrupt_count INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER,
                error_info TEXT
            )
            """.trimIndent()
        )
        d.version = 11
    }

    /**
     * Run a Room Migration's body against the test database.
     *
     * Goes through the public `SupportSQLiteOpenHelper` rather than
     * constructing `FrameworkSQLiteDatabase` directly — that wrapper is
     * `internal` to the sqlite-framework artifact and not callable from here.
     */
    private fun applyMigration(migration: androidx.room.migration.Migration) {
        migration.migrate(supportDb)
    }

    private fun columnNames(): Set<String> =
        db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            buildSet {
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) add(c.getString(idx))
            }
        }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = File(ctx.cacheDir, "migration-attribution-test.db")
        dbFile.delete()
        db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createV11Schema(db)
        db.close()

        // Reopen through the Support wrapper so Migration.migrate() — which
        // takes a SupportSQLiteDatabase — can run against the same file.
        val cfg = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
            .builder(ctx)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(d: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                override fun onUpgrade(
                    d: androidx.sqlite.db.SupportSQLiteDatabase, old: Int, new: Int,
                ) = Unit
            })
            .build()
        helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(cfg)
        supportDb = helper.writableDatabase
        db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    }

    @After
    fun tearDown() {
        db.close()
        helper.close()
        dbFile.delete()
    }

    /** A v11 row must survive the upgrade with its token_usage intact. */
    @Test
    fun upgrade11To12_preservesExistingRows() {
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order) " +
                "VALUES ('m1','s1','assistant','[]',100,'{\"inputTokens\":7}',0)"
        )

        applyMigration(AppDatabase.MIGRATION_11_12)

        db.rawQuery("SELECT token_usage, model_id FROM messages WHERE id='m1'", null).use { c ->
            assertTrue("the pre-existing row must still be there", c.moveToFirst())
            assertEquals("{\"inputTokens\":7}", c.getString(0))
            // NULL is how the Usage page tells "estimated" from "measured".
            // A NOT NULL DEFAULT would erase that distinction.
            assertTrue("pre-migration rows must have a NULL snapshot", c.isNull(1))
        }
    }

    /** The four columns must exist and be writable after upgrading. */
    @Test
    fun upgrade11To12_addsWritableSnapshotColumns() {
        applyMigration(AppDatabase.MIGRATION_11_12)

        assertTrue(
            columnNames().containsAll(
                listOf("model_id", "model_display_name", "provider_type", "provider_instance_id")
            )
        )
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order, " +
                "model_id, model_display_name, provider_type, provider_instance_id) VALUES " +
                "('m1','s1','assistant','[]',1,0,'grok-4.5','Grok 4.5','xAI','inst-1')"
        )
        db.rawQuery(
            "SELECT model_id, model_display_name, provider_type, provider_instance_id " +
                "FROM messages WHERE id='m1'", null
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("grok-4.5", c.getString(0))
            assertEquals("Grok 4.5", c.getString(1))
            assertEquals("xAI", c.getString(2))
            assertEquals("inst-1", c.getString(3))
        }
    }

    /**
     * The downgrade — the case that used to make the app unopenable.
     *
     * Asserts what a user installing an older build actually cares about: it
     * runs at all, their messages are still there, and the newer build's
     * attribution is still there when they upgrade back.
     */
    @Test
    fun downgrade12To11_runsWithoutLoss() {
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, token_usage, sort_order) " +
                "VALUES ('m1','s1','assistant','[]',100,'{\"inputTokens\":7}',0)"
        )
        applyMigration(AppDatabase.MIGRATION_11_12)
        db.execSQL("UPDATE messages SET model_id='grok-4.5' WHERE id='m1'")

        applyMigration(AppDatabase.MIGRATION_12_11)

        db.rawQuery("SELECT COUNT(*) FROM messages", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("downgrade must not drop rows", 1, c.getInt(0))
        }
        // Round trip: the snapshot survives, so upgrading again restores precise
        // attribution instead of silently degrading it. This is why the
        // downgrade is a no-op rather than a DROP COLUMN.
        db.rawQuery("SELECT model_id FROM messages WHERE id='m1'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("grok-4.5", c.getString(0))
        }
    }

    /**
     * An older build's INSERT — which names none of the new columns — must
     * still work against the post-downgrade table. This only holds because the
     * columns are nullable with no DEFAULT, and it is the whole premise of
     * keeping them rather than dropping them.
     */
    @Test
    fun downgrade12To11_oldStyleInsertStillWorks() {
        applyMigration(AppDatabase.MIGRATION_11_12)
        applyMigration(AppDatabase.MIGRATION_12_11)

        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order) " +
                "VALUES ('m2','s2','user','[]',1,0)"
        )
        db.rawQuery("SELECT model_id FROM messages WHERE id='m2'", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("unset snapshot columns must be NULL", c.isNull(0))
        }
        // And the columns are still present — the downgrade kept them.
        assertTrue(columnNames().contains("model_id"))
    }

    /** `SELECT *` (used by Room DAOs) must tolerate the extra columns. */
    @Test
    fun downgrade12To11_selectStarStillReadsOldColumns() {
        applyMigration(AppDatabase.MIGRATION_11_12)
        applyMigration(AppDatabase.MIGRATION_12_11)
        db.execSQL(
            "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order) " +
                "VALUES ('m3','s3','user','[]',5,0)"
        )
        db.rawQuery("SELECT * FROM messages WHERE id='m3'", null).use { c ->
            assertTrue(c.moveToFirst())
            // Resolved by NAME, exactly as Room's generated code does — which
            // is why trailing extra columns are harmless to an older build.
            assertEquals("m3", c.getString(c.getColumnIndexOrThrow("id")))
            assertEquals(5, c.getLong(c.getColumnIndexOrThrow("created_at")))
            assertNull(c.getString(c.getColumnIndexOrThrow("model_id")))
        }
    }
}
