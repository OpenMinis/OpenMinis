package com.openminis.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-p1-delegate-task] 12 → 13 adds `parent_session_id` / `parent_tool_use_id`
 * to `sessions`; 13 → 12 is a no-op that keeps them. Mirrors
 * MessageAttributionMigrationTest. NOTE: connectedAndroidTest UNINSTALLS the
 * app under test — run only on a device whose data is disposable.
 */
@RunWith(AndroidJUnit4::class)
class ChildSessionMigrationTest {
    private val dbName = "child-session-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun upgrade12to13_existingRowsReadNullParent() {
        helper.createDatabase(dbName, 12).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, model_id, created_at, updated_at, memory_enabled) " +
                    "VALUES ('s1', 'top', 'm', 1, 1, 1)"
            )
        }
        helper.runMigrationsAndValidate(dbName, 13, true, AppDatabase.MIGRATION_12_13).use { db ->
            db.query("SELECT parent_session_id, parent_tool_use_id FROM sessions WHERE id='s1'").use { c ->
                assertTrue(c.moveToFirst())
                assertNull(c.getString(0)); assertNull(c.getString(1))
            }
        }
    }

    @Test
    fun downgrade13to12_keepsChildColumnsAndValues() {
        helper.createDatabase(dbName, 13).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, model_id, created_at, updated_at, memory_enabled, parent_session_id, parent_tool_use_id) " +
                    "VALUES ('c1', 'Helper · x', 'm', 1, 1, 0, 'p1', 'tu1')"
            )
        }
        helper.runMigrationsAndValidate(dbName, 12, true, AppDatabase.MIGRATION_13_12).use { db ->
            db.query("SELECT parent_session_id FROM sessions WHERE id='c1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("p1", c.getString(0))
            }
            db.query("PRAGMA integrity_check").use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
        }
    }
}
