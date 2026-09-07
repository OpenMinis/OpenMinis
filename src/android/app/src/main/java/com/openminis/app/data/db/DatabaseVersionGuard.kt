package com.openminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.logging.AppLogger

/**
 * [T-android-downgrade-compat] Detects a database written by a NEWER build
 * than the one now running, before Room ever opens it.
 *
 * ## The failure this prevents
 *
 * Room resolves a downgrade by calling `onUpgrade(from, to)` and looking for a
 * migration path. Finding none it consults `isMigrationRequired`, which either
 * throws `IllegalStateException` or — with a destructive fallback enabled —
 * calls `dropAllTables`. We enable no fallback (deliberately: dropping would
 * destroy the user's entire chat history), so an unmatched downgrade means the
 * app throws at first database access and simply will not start.
 *
 * [AppDatabase.MIGRATION_12_11] handles the 12 → 11 case, and the same no-op
 * pattern extends to any future ADD COLUMN / ADD TABLE change. But it cannot
 * cover everything: renames, drops, type changes and — most dangerously —
 * changes to the MEANING of existing data have no safe automatic downgrade,
 * and a missing link anywhere in the chain (12 → 11 → 10) breaks the whole
 * path. This guard is the backstop for those, and for the plain human case of
 * forgetting to add the downgrade migration at all.
 *
 * ## Why a pre-check instead of catching Room's exception
 *
 * `Room.databaseBuilder(...).build()` is lazy: nothing opens until the first
 * DAO call, so the exception surfaces from an arbitrary coroutine somewhere
 * deep in the app. Every one of those call sites would need a catch, and one
 * miss is still a crash. Reading the version first is deterministic and
 * happens once, in one place.
 *
 * Crucially, this runs BEFORE Room is constructed — so at the moment we
 * decide, no migration and no `dropAllTables` can possibly have executed. The
 * database file is guaranteed untouched.
 */
object DatabaseVersionGuard {

    private const val TAG = "DbVersionGuard"

    /**
     * The schema version this build understands. MUST equal the `version` in
     * [AppDatabase]'s `@Database` annotation.
     *
     * Kept as a separate constant because the annotation value is not readable
     * at runtime without reflection. [AppDatabaseVersionTest] asserts the two
     * agree, so they cannot drift apart silently — a stale copy here would
     * either disable the guard or trip it on every launch.
     */
    const val CODE_DB_VERSION = 13

    /** Filename must match the one passed to `Room.databaseBuilder`. */
    private const val DB_NAME = "minis.db"

    /**
     * Read `user_version` without going through Room.
     *
     * Opened READ-ONLY on purpose: it makes it impossible for the probe itself
     * to modify, migrate or corrupt the file. Returns null when the database
     * does not exist yet (a fresh install) or cannot be read — in both cases
     * the caller should just proceed and let Room do its normal thing.
     */
    fun readOnDiskVersion(context: Context): Int? {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            // A file we cannot even open read-only is not a downgrade signal;
            // let Room report whatever the real problem is.
            AppLogger.warning(TAG, "could not probe db version: ${it.javaClass.simpleName}")
            null
        }
    }

    /**
     * True when the on-disk database is newer than this build supports, i.e.
     * the user has downgraded and Room may be unable to open it.
     *
     * Note this returns true even when a no-op downgrade migration WOULD have
     * handled it. That is intentional for versions we cannot vouch for: see
     * [isHandledDowngrade].
     */
    fun isFromNewerBuild(context: Context): Boolean {
        val onDisk = readOnDiskVersion(context) ?: return false
        val newer = onDisk > CODE_DB_VERSION
        if (newer) {
            AppLogger.warning(
                TAG,
                "database is from a newer build: onDisk=$onDisk code=$CODE_DB_VERSION",
            )
        }
        return newer
    }

    /**
     * Whether a registered downgrade migration covers this jump, in which case
     * the app can open the database normally and the guidance screen is
     * unnecessary.
     *
     * Only versions with an explicit, verified no-op downgrade belong here.
     * Anything else falls through to the guidance screen rather than being
     * optimistically opened — being wrong in that direction costs the user a
     * screen they can dismiss by upgrading; being wrong the other way costs
     * them a crash.
     */
    fun isHandledDowngrade(onDiskVersion: Int): Boolean =
        onDiskVersion == 12 && CODE_DB_VERSION == 11

    /** Result of the launch-time check. */
    enum class Decision {
        /** Normal path — open the database. */
        PROCEED,

        /**
         * Database is from a newer build with no downgrade path. Show guidance
         * and do NOT touch the file; upgrading restores everything.
         */
        SHOW_NEWER_DB_GUIDANCE,
    }

    fun evaluate(context: Context): Decision {
        val onDisk = readOnDiskVersion(context) ?: return Decision.PROCEED
        if (onDisk <= CODE_DB_VERSION) return Decision.PROCEED
        if (isHandledDowngrade(onDisk)) return Decision.PROCEED
        return Decision.SHOW_NEWER_DB_GUIDANCE
    }
}
