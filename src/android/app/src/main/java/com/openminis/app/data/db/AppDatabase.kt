package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
        FolderEntity::class,
    ],
    version = 12,
    // [T-android-downgrade-compat] Kept ON so MigrationTestHelper and CI can
    // validate every migration (and its downgrade counterpart) against the
    // committed schema json. Without it the upgrade/downgrade chain has no
    // automated check at all and only a real device install can catch a break.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN last_message TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN model_binding TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_content TEXT")
            }
        }

        /**
         * compact_markers: add Phase-A id-first boundary columns. The legacy
         * sort_order columns stay for backfill; when both are present the
         * id-first fields win on lookup (see ChatDao.latestCompactMarker).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN first_kept_message_id TEXT")
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN last_compacted_message_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
            }
        }

        /**
         * T239: per-session thinking-mode override. Nullable so existing
         * sessions transparently keep "unset" semantics; only sessions where
         * the user explicitly chooses a level start storing a non-null value.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN thinking_override TEXT")
            }
        }

        /**
         * T-pwa-1: pwa_shortcuts table backs the home-screen PWA pinning
         * flow. Pure additive migration — no existing entity is modified
         * and no data is rewritten.
         *
         * Superseded by MIGRATION_8_9 below (Pwa → WebApp rename); kept
         * here so users who already migrated from <=6 land on a
         * consistent state before the rename runs.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pwa_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * compact_markers: add `version` column for marker schema versioning.
         * Mirrors iOS Phase v2 — version=1 = legacy multi-field model,
         * version=2 = simplified id-only anchor model. Existing rows default
         * to 1 so legacy resolution code keeps running for them.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Pwa → WebApp rename: copy every row from `pwa_shortcuts` into a
         * new `webapp_shortcuts` table with identical schema, then drop
         * the old table. Row contents (UUIDs, html paths, icon refs) are
         * preserved verbatim — only the table name changes — so existing
         * in-app shortcut lists keep showing the same entries.
         *
         * Note: pinned launcher icons created before this rename still
         * carry the old `ACTION_OPEN_PWA` intent action and will be dead
         * after the upgrade (manifest no longer registers it). The user
         * has to re-pin from inside the app. Per
         * `feedback_no_destructive_git` we do NOT silently delete data —
         * the DB row stays, only the launcher-side icon dies.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO webapp_shortcuts (
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    )
                    SELECT
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    FROM pwa_shortcuts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
            }
        }

        /**
         * [T-error-persist-android] messages.error_info — persist the terminal
         * error sticker on an assistant turn so the inline error survives a
         * session reload (mirrors iOS messages.error_info). Pure additive,
         * nullable column; existing rows read back NULL (= no error). No data
         * rewrite.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN error_info TEXT")
            }
        }

        /**
         * [T-android-session-grouping] Session groups. Adds the `folders` table
         * and `sessions.folder_id`.
         *
         * Purely additive: existing sessions read back `folder_id = NULL`
         * (= ungrouped), which is exactly the pre-migration behaviour, so no
         * data is rewritten and a downgrade loses only the grouping.
         *
         * `folder_id` carries NO foreign key on purpose — an id pointing at a
         * group that is not present locally must render as ungrouped rather
         * than fail a constraint (see ChatSessionEntity.folderId). The index is
         * plain and non-unique: many sessions share one group.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT,
                        color TEXT,
                        origin TEXT NOT NULL DEFAULT 'manual',
                        sort_index INTEGER NOT NULL DEFAULT 0,
                        pinned_at INTEGER,
                        description TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE sessions ADD COLUMN folder_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions(folder_id)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // sessions: add iOS-parity columns
                db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN memory_enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned_at INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN edit_count INTEGER NOT NULL DEFAULT 0")

                // messages: add iOS-parity columns
                db.execSQL("ALTER TABLE messages ADD COLUMN stream_interrupt_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER")

                // compact_markers: new table mirroring iOS
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS compact_markers (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        first_kept_sort_order INTEGER NOT NULL,
                        compacted_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        ui_boundary_sort_order INTEGER,
                        boundary_message_id TEXT,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
            }
        }

        /**
         * [T-token-attribution-snapshot] Per-message model attribution.
         *
         * Four nullable columns, no DEFAULT. `ADD COLUMN` is an O(1) metadata
         * change in SQLite — existing rows are untouched and simply read NULL,
         * which is the signal the Usage page uses to mark a row "estimated"
         * rather than "measured". A `NOT NULL DEFAULT ''` would make old rows
         * indistinguishable from new ones that genuinely have no model.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN model_display_name TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN provider_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN provider_instance_id TEXT")
            }
        }

        /**
         * [T-android-downgrade-compat] Downgrade 12 → 11. Deliberately a NO-OP.
         *
         * ## Why this exists
         *
         * Room resolves a downgrade by calling `onUpgrade(from, to)` and
         * looking for a migration path; when none exists it consults
         * `isMigrationRequired`, which either throws or — with a destructive
         * fallback enabled — calls `dropAllTables`. We enable no fallback, so
         * before this migration existed, installing an older build on top of a
         * newer database threw `IllegalStateException` at first DB access and
         * the app could not start at all. Data survived on disk, but the user
         * experience was "the app is broken".
         *
         * Registering any 12 → 11 path is enough for Room to proceed; it does
         * not inspect what the migration does. So the cheapest correct answer
         * is to do nothing and let the four extra columns stay.
         *
         * ## Why NOT `DROP COLUMN`
         *
         * Dropping would genuinely delete the attribution captured by the
         * newer build, so a user who downgrades to look at something and then
         * upgrades back would silently lose it — violating the "no data loss"
         * constraint this whole mechanism exists to uphold. Keeping the
         * columns costs a few dozen bytes per row and makes the round trip
         * lossless. (`ALTER TABLE ... DROP COLUMN` also needs SQLite 3.35+ /
         * API 34+, and a full table rebuild below that.)
         *
         * ## Why leaving the columns is safe for the older build
         *
         * - Room's generated DAOs bind by column NAME, never by position.
         * - Room's schema validation checks that every column the entity
         *   REQUIRES exists; extra columns in the table are ignored.
         * - `SELECT *` is likewise resolved by name.
         * - The one place this codebase reads a cursor positionally
         *   (`VoiceCorrectionDb`, `ConfigAuditLog`) uses explicit column lists
         *   in separate databases, so the projection is fixed regardless.
         * - Old `INSERT` statements omit the new columns, which is fine
         *   precisely because they are nullable with no DEFAULT.
         *
         * ## Scope
         *
         * This pattern generalises to ADD COLUMN / ADD TABLE / ADD INDEX. It
         * does NOT cover renames, drops, type changes, or changes to the
         * MEANING of existing data (Room cannot detect the last one at all).
         * Those need a real reverse migration — or the version pre-check in
         * [com.openminis.app.data.db.DatabaseVersionGuard], which is the
         * backstop for exactly this case.
         */
        val MIGRATION_12_11 = object : Migration(12, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Intentionally empty. See the doc comment above — the four
                // columns added by MIGRATION_11_12 are left in place.
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minis.db"
                )
                    // MIGRATION_12_11 is the downgrade counterpart of
                    // MIGRATION_11_12 — registering it is what lets an older
                    // build open a newer database instead of failing to start.
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_11,
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
