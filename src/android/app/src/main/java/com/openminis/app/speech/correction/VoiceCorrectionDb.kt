package com.openminis.app.speech.correction

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * [T-android-voice-correction] SQLite store for voice smart correction.
 * Port of iOS `Providers/Voice/VoiceCorrectionDB.swift`.
 *
 * ## Why raw SQLite and not Room (the project's usual choice)
 * Two behaviours here are load-bearing and Room cannot express them cleanly:
 *
 *  1. **Self-heal on corruption.** This is regenerable LEARNING data — losing
 *     it degrades correction to "no personalization", it does not break the
 *     app. So an unopenable file is deleted and recreated empty rather than
 *     propagated as an error. Room's `fallbackToDestructiveMigration` covers
 *     version mismatch, NOT a corrupt file.
 *  2. **Unconditional additive column repair.** [ensureIdempotentColumns] runs
 *     on EVERY open, BEFORE the version gate. ProviderConfigDB learned this the
 *     hard way on iOS: a column was added without bumping the schema version,
 *     every already-current DB took the early return, never gained the column,
 *     and the statements referencing it failed to prepare — silently wiping
 *     every provider from the UI. Room's migrations are version-to-version
 *     edges with no "repair regardless of version" slot.
 *
 * It also lives in its OWN file, not in minis.db: "clear my correction data" is
 * then one file to wipe, and a corrupt learning DB can never take the rest of
 * the app down with it.
 *
 * ## Threading
 * Every method is synchronous and must be called off the main thread (callers
 * use Dispatchers.IO). `SQLiteDatabase` is internally synchronized, and WAL
 * lets the send-time read path proceed without blocking the capture writer.
 */
class VoiceCorrectionDb private constructor(private val dbFile: File) {

    private var db: SQLiteDatabase? = null
    private var writesSincePrune = 0

    init {
        db = openOrRebuild(dbFile)
        // Retention before first use: a long-lived install's event log is
        // regenerable learning signal, not an archive.
        pruneEvents()
    }

    // ---- Confusion dictionary ----

    /**
     * Upsert one learned ASR correction. Dedup key is
     * (phonetic key, corrected term, locale): the same intent recorded via a
     * different misspelling folds into one row and bumps `frequency`, which is
     * the whole point of an aggregate table rather than an append-only log.
     *
     * Re-writing a row also clears `archived_at` — evidence of continued use
     * revives a row that cleanup had retired.
     */
    fun upsertConfusion(
        phoneticKey: String,
        originalVariant: String,
        correctedTerm: String,
        locale: String,
        contextSample: String?,
        asrProvider: String,
        source: String = SOURCE_VOICE,
    ) {
        val d = db ?: return
        val now = System.currentTimeMillis() / 1000.0
        runCatching {
            var existingId: String? = null
            var frequency = 0
            var negative = 0
            var variantsJson = "[]"
            d.rawQuery(
                """
                SELECT id, frequency, negative_feedback_count, original_variants
                FROM confusion_dictionary
                WHERE original_phonetic_key = ? AND corrected_term = ? AND locale = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(phoneticKey, correctedTerm, locale),
            ).use { c ->
                if (c.moveToFirst()) {
                    existingId = c.getString(0)
                    frequency = c.getInt(1)
                    negative = c.getInt(2)
                    variantsJson = c.getString(3) ?: "[]"
                }
            }

            val variants = decodeVariants(variantsJson).toMutableList()
            if (!variants.contains(originalVariant)) variants.add(originalVariant)
            val encoded = encodeVariants(variants)

            if (existingId != null) {
                val newFreq = frequency + 1
                val values = ContentValues().apply {
                    put("original_variants", encoded)
                    put("frequency", newFreq)
                    put("confidence", confidence(newFreq, negative))
                    put("last_seen", now)
                    if (contextSample != null) put("context_before_sample", contextSample)
                    else putNull("context_before_sample")
                    putNull("archived_at")
                }
                d.update("confusion_dictionary", values, "id = ?", arrayOf(existingId))
            } else {
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("original_phonetic_key", phoneticKey)
                    put("original_variants", encoded)
                    put("corrected_term", correctedTerm)
                    put("locale", locale)
                    put("frequency", 1)
                    put("negative_feedback_count", 0)
                    put("confidence", confidence(1, 0))
                    if (contextSample != null) put("context_before_sample", contextSample)
                    else putNull("context_before_sample")
                    put("asr_provider", asrProvider)
                    put("source", source)
                    put("last_seen", now)
                    putNull("archived_at")
                }
                d.insert("confusion_dictionary", null, values)
            }
            // [privacy] Lengths only — these pairs are verbatim user speech.
            // The row keeps the truth; the log does not need it.
            Log.i(
                TAG,
                "[Record] upsert originalLen=${originalVariant.length} " +
                    "correctedLen=${correctedTerm.length} locale=$locale source=$source",
            )
        }.onFailure { Log.e(TAG, "upsertConfusion failed", it) }
    }

    /**
     * Negative feedback: a correction we proposed was rejected. Never write a
     * POSITIVE row for an accepted AI suggestion — the suggestion was itself
     * derived from this table, so feeding it back would make the system learn
     * from an echo of its own output.
     */
    fun recordNegativeFeedback(phoneticKey: String, correctedTerm: String, locale: String) {
        val d = db ?: return
        runCatching {
            d.execSQL(
                """
                UPDATE confusion_dictionary
                SET negative_feedback_count = negative_feedback_count + 1,
                    confidence = CAST(frequency AS REAL) /
                                 (frequency + (negative_feedback_count + 1) * 2.0)
                WHERE original_phonetic_key = ? AND corrected_term = ? AND locale = ?
                """.trimIndent(),
                arrayOf(phoneticKey, correctedTerm, locale),
            )
        }.onFailure { Log.e(TAG, "recordNegativeFeedback failed", it) }
    }

    /**
     * Batch lookup for many phonetic keys in ONE statement: a 30-token
     * transcript must not become 30 round-trips. Archived and low-confidence
     * rows are excluded in SQL, not in Kotlin, so the index does the work.
     */
    fun lookupConfusion(
        phoneticKeys: List<String>,
        locale: String,
        minConfidence: Double,
        limit: Int,
    ): List<ConfusionRow> {
        val d = db ?: return emptyList()
        if (phoneticKeys.isEmpty()) return emptyList()
        val placeholders = phoneticKeys.joinToString(",") { "?" }
        val args = (phoneticKeys + listOf(locale, minConfidence.toString(), limit.toString()))
            .toTypedArray()
        return runCatching {
            val out = mutableListOf<ConfusionRow>()
            d.rawQuery(
                """
                SELECT id, original_phonetic_key, original_variants, corrected_term,
                       locale, frequency, negative_feedback_count, confidence, last_seen
                FROM confusion_dictionary
                WHERE original_phonetic_key IN ($placeholders)
                  AND locale = ?
                  AND confidence >= ?
                  AND archived_at IS NULL
                ORDER BY confidence DESC, frequency DESC
                LIMIT ?
                """.trimIndent(),
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ConfusionRow(
                            id = c.getString(0),
                            phoneticKey = c.getString(1),
                            variants = decodeVariants(c.getString(2) ?: "[]"),
                            correctedTerm = c.getString(3),
                            locale = c.getString(4),
                            frequency = c.getInt(5),
                            negativeFeedbackCount = c.getInt(6),
                            confidence = c.getDouble(7),
                            lastSeen = c.getDouble(8),
                        ),
                    )
                }
            }
            out
        }.getOrElse {
            Log.e(TAG, "lookupConfusion failed", it)
            emptyList()
        }
    }

    // ---- Typed vocabulary ----

    /**
     * Upsert a mined vocabulary term. `distinct_days` only increments when the
     * day key differs from the last recorded one — a term typed 50 times in one
     * afternoon must not look as established as one used across a week.
     */
    fun upsertVocabulary(
        term: String,
        phoneticKey: String,
        locale: String,
        posTag: String?,
        backgroundRank: Int?,
        occurrences: Int,
        day: String,
    ) {
        val d = db ?: return
        val now = System.currentTimeMillis() / 1000.0
        runCatching {
            var id: String? = null
            var freq = 0
            var days = 0
            var lastDay: String? = null
            d.rawQuery(
                "SELECT id, frequency, distinct_days, last_day FROM typed_vocabulary " +
                    "WHERE term = ? AND locale = ? LIMIT 1",
                arrayOf(term, locale),
            ).use { c ->
                if (c.moveToFirst()) {
                    id = c.getString(0); freq = c.getInt(1)
                    days = c.getInt(2); lastDay = c.getString(3)
                }
            }
            if (id != null) {
                val values = ContentValues().apply {
                    put("frequency", freq + occurrences)
                    put("distinct_days", if (lastDay != day) days + 1 else days)
                    put("last_day", day)
                    put("last_seen", now)
                    if (posTag != null) put("pos_tag", posTag)
                    if (backgroundRank != null) put("background_rank", backgroundRank)
                    putNull("archived_at")
                }
                d.update("typed_vocabulary", values, "id = ?", arrayOf(id))
            } else {
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("term", term)
                    put("phonetic_key", phoneticKey)
                    put("locale", locale)
                    if (posTag != null) put("pos_tag", posTag) else putNull("pos_tag")
                    put("frequency", occurrences)
                    put("distinct_days", 1)
                    put("last_day", day)
                    if (backgroundRank != null) put("background_rank", backgroundRank)
                    else putNull("background_rank")
                    put("last_seen", now)
                    put("source", "typed")
                    putNull("archived_at")
                }
                d.insert("typed_vocabulary", null, values)
            }
        }.onFailure { Log.e(TAG, "upsertVocabulary failed", it) }
    }

    /**
     * Vocabulary lookup. The frequency / distinct-days floors are enforced HERE
     * at retrieval, not at intake — a term must be storable at frequency=1 to
     * ever accumulate to the threshold.
     */
    fun lookupVocabulary(
        phoneticKeys: List<String>,
        locale: String,
        limit: Int,
        minFrequency: Int = VoiceCorrectionConfig.VOCAB_MIN_FREQUENCY,
        minDistinctDays: Int = VoiceCorrectionConfig.VOCAB_MIN_DISTINCT_DAYS,
    ): List<VocabularyRow> {
        val d = db ?: return emptyList()
        if (phoneticKeys.isEmpty()) return emptyList()
        val placeholders = phoneticKeys.joinToString(",") { "?" }
        val args = (
            phoneticKeys + listOf(
                locale, minFrequency.toString(), minDistinctDays.toString(), limit.toString(),
            )
            ).toTypedArray()
        return runCatching {
            val out = mutableListOf<VocabularyRow>()
            d.rawQuery(
                """
                SELECT id, term, phonetic_key, locale, pos_tag, frequency, distinct_days
                FROM typed_vocabulary
                WHERE phonetic_key IN ($placeholders)
                  AND locale = ?
                  AND frequency >= ?
                  AND distinct_days >= ?
                  AND archived_at IS NULL
                ORDER BY frequency DESC
                LIMIT ?
                """.trimIndent(),
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        VocabularyRow(
                            id = c.getString(0), term = c.getString(1),
                            phoneticKey = c.getString(2), locale = c.getString(3),
                            posTag = c.getString(4), frequency = c.getInt(5),
                            distinctDays = c.getInt(6),
                        ),
                    )
                }
            }
            out
        }.getOrElse {
            Log.e(TAG, "lookupVocabulary failed", it)
            emptyList()
        }
    }

    // ---- Meta / events ----

    fun metaValue(key: String): String? {
        val d = db ?: return null
        return runCatching {
            d.rawQuery("SELECT value FROM correction_meta WHERE key = ? LIMIT 1", arrayOf(key))
                .use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    fun setMetaValue(key: String, value: String) {
        val d = db ?: return
        runCatching {
            d.execSQL(
                "INSERT INTO correction_meta(key, value) VALUES(?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                arrayOf(key, value),
            )
        }.onFailure { Log.e(TAG, "setMetaValue failed", it) }
    }

    /** Outcome is one of "suggested" / "accepted" / "reverted" — metrics only. */
    fun recordCorrectionEvent(
        original: String,
        corrected: String,
        outcome: String,
        sourceSummary: String?,
    ) {
        val d = db ?: return
        runCatching {
            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("created_at", System.currentTimeMillis() / 1000.0)
                put("original", original)
                put("corrected", corrected)
                put("outcome", outcome)
                if (sourceSummary != null) put("source_summary", sourceSummary)
                else putNull("source_summary")
            }
            d.insert("correction_events", null, values)
            if (++writesSincePrune >= PRUNE_EVERY) {
                writesSincePrune = 0
                pruneEvents()
            }
        }.onFailure { Log.e(TAG, "recordCorrectionEvent failed", it) }
    }

    /**
     * Bounded retention for [correction_events]. The vocabulary/dictionary
     * tables are bounded by their own learning rules and stay untouched.
     *
     * @return rows deleted, for tests and metrics.
     */
    internal fun pruneEvents(
        maxEvents: Int = MAX_EVENTS,
        maxAgeDays: Int = MAX_AGE_DAYS,
        nowSec: Double = System.currentTimeMillis() / 1000.0,
    ): Int {
        val d = db ?: return 0
        return runCatching {
            val cutoff = nowSec - maxAgeDays * 86_400.0
            var deleted = d.delete(
                "correction_events",
                "created_at < ?",
                arrayOf(cutoff.toString()),
            )
            deleted += d.delete(
                "correction_events",
                "rowid NOT IN (SELECT rowid FROM correction_events ORDER BY created_at DESC LIMIT ?)",
                arrayOf(maxEvents.toString()),
            )
            deleted
        }.onFailure { Log.e(TAG, "pruneEvents failed", it) }.getOrDefault(0)
    }

    /** Row counts per table, for the settings screen and debug metrics. */
    fun counts(): Map<String, Int> {
        val d = db ?: return emptyMap()
        return runCatching {
            listOf("confusion_dictionary", "typed_vocabulary", "correction_events")
                .associateWith { t ->
                    d.rawQuery("SELECT COUNT(*) FROM $t", null)
                        .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                }
        }.getOrElse { emptyMap() }
    }

    /** Wipe every learning table. Backs the "Clear Collected Data" button. */
    fun clearAll() {
        val d = db ?: return
        runCatching {
            d.beginTransaction()
            try {
                d.execSQL("DELETE FROM confusion_dictionary")
                d.execSQL("DELETE FROM typed_vocabulary")
                d.execSQL("DELETE FROM correction_events")
                d.execSQL("DELETE FROM correction_meta")
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            // Reclaim pages in bounded chunks rather than one long blocking
            // VACUUM. rawQuery, not execSQL — see openAndPrepare.
            d.rawQuery("PRAGMA incremental_vacuum(1000)", null).use { it.moveToFirst() }
            Log.i(TAG, "[Privacy] all correction learning data cleared")
        }.onFailure { Log.e(TAG, "clearAll failed", it) }
    }

    // ---- Open / schema ----

    private fun openOrRebuild(file: File): SQLiteDatabase? {
        openAndPrepare(file)?.let { return it }
        // Self-heal: throw the file away and come back as a cold-start empty DB.
        // A WAL database has sidecars; leaving a stale -wal/-shm beside a fresh
        // main file is its own corruption source, so drop those too.
        Log.e(TAG, "[DBHealth] open failed — rebuilding empty (size=${file.length()})")
        runCatching {
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
        return openAndPrepare(file).also {
            if (it == null) Log.e(TAG, "[DBHealth] rebuild failed — correction disabled")
        }
    }

    private fun openAndPrepare(file: File): SQLiteDatabase? = runCatching<SQLiteDatabase> {
        file.parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(file, null)
        // ⚠️ PRAGMAs that RETURN A ROW must go through rawQuery, not execSQL.
        // Android's execSQL rejects any statement producing results with
        // "Queries can be performed using SQLiteDatabase query or rawQuery
        // methods only" — `journal_mode` returns the resulting mode and
        // `auto_vacuum` returns the setting, so both fail there. (iOS's
        // sqlite3_exec has no such restriction, which is why the ported code
        // needed adjusting rather than transcribing.)
        //
        // auto_vacuum MUST be set before any table is created — SQLite ignores
        // it afterwards. INCREMENTAL (not FULL) so cleanup can reclaim pages in
        // bounded chunks instead of one long blocking VACUUM.
        d.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }
        d.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
        // synchronous returns nothing, so execSQL is fine here.
        d.execSQL("PRAGMA synchronous = NORMAL")
        runMigrations(d)
        d
    }.onFailure { Log.e(TAG, "[DBHealth] openAndPrepare failed", it) }.getOrNull()

    /**
     * Order is load-bearing:
     *   1. [ensureIdempotentColumns] — EVERY open, BEFORE the version gate.
     *   2. versioned structural migrations — only when current < [SCHEMA_VERSION].
     *   3. stamp user_version.
     *
     * Step 1 must precede the gate; see the class comment for the incident that
     * rule exists to prevent.
     */
    private fun runMigrations(d: SQLiteDatabase) {
        val current = d.rawQuery("PRAGMA user_version", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val patched = ensureIdempotentColumns(d)

        // Downgrade safety: an OLD build's compiled-in version is lower than the
        // user_version a NEWER build stamped. It takes this early return and
        // reads only the columns it knows about. That works ONLY because every
        // statement above names its columns explicitly (never SELECT *), so
        // extra columns are invisible rather than shifting indices.
        if (current >= SCHEMA_VERSION) {
            if (patched > 0) Log.i(TAG, "[Migration] at v$current, columns patched=$patched")
            return
        }

        d.beginTransaction()
        try {
            if (current < 1) {
                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS confusion_dictionary (
                        id                      TEXT PRIMARY KEY,
                        original_phonetic_key   TEXT NOT NULL,
                        original_variants       TEXT NOT NULL,
                        corrected_term          TEXT NOT NULL,
                        locale                  TEXT NOT NULL,
                        frequency               INTEGER NOT NULL DEFAULT 1,
                        negative_feedback_count INTEGER NOT NULL DEFAULT 0,
                        confidence              REAL NOT NULL DEFAULT 0.5,
                        context_before_sample   TEXT,
                        asr_provider            TEXT NOT NULL DEFAULT '',
                        source                  TEXT NOT NULL DEFAULT '$SOURCE_VOICE',
                        last_seen               REAL NOT NULL,
                        archived_at             REAL DEFAULT NULL,
                        device_id               TEXT,
                        last_synced_at          REAL
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_phonetic ON confusion_dictionary(original_phonetic_key)")
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_locale ON confusion_dictionary(locale)")
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_confusion_confidence ON confusion_dictionary(confidence DESC)")

                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS typed_vocabulary (
                        id              TEXT PRIMARY KEY,
                        term            TEXT NOT NULL,
                        phonetic_key    TEXT NOT NULL,
                        locale          TEXT NOT NULL,
                        pos_tag         TEXT,
                        frequency       INTEGER NOT NULL DEFAULT 1,
                        distinct_days   INTEGER NOT NULL DEFAULT 1,
                        last_day        TEXT,
                        background_rank INTEGER,
                        last_seen       REAL NOT NULL,
                        source          TEXT NOT NULL DEFAULT 'typed',
                        archived_at     REAL DEFAULT NULL
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_vocab_phonetic ON typed_vocabulary(phonetic_key)")

                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS correction_meta (" +
                        "key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                )
                d.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS correction_events (
                        id             TEXT PRIMARY KEY,
                        created_at     REAL NOT NULL,
                        original       TEXT NOT NULL,
                        corrected      TEXT NOT NULL,
                        outcome        TEXT NOT NULL,
                        source_summary TEXT
                    )
                    """.trimIndent(),
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created ON correction_events(created_at DESC)")
            }
            d.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        Log.i(TAG, "[Migration] schema $current → $SCHEMA_VERSION")
    }

    /**
     * Additive-only column repairs, run on every open regardless of
     * user_version. Each entry also ships in the v1 CREATE TABLE above; this is
     * the belt-and-braces that repairs a DB written by a build predating the
     * column. Returns how many columns were actually added.
     */
    private fun ensureIdempotentColumns(d: SQLiteDatabase): Int {
        var added = 0
        fun columns(table: String): Set<String> = runCatching {
            d.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val s = mutableSetOf<String>()
                while (c.moveToNext()) s.add(c.getString(1))
                s
            }
        }.getOrElse { emptySet() }

        fun ensure(table: String, column: String, ddl: String) {
            val existing = columns(table)
            // Empty = table not created yet (fresh DB; the v1 CREATE includes
            // the column). Skip so we don't log a spurious "no such table".
            if (existing.isEmpty() || existing.contains(column)) return
            runCatching {
                d.execSQL("ALTER TABLE $table ADD COLUMN $ddl")
                added++
                Log.i(TAG, "[Migration] schema repair: added $table.$column")
            }.onFailure { Log.e(TAG, "column repair failed for $table.$column", it) }
        }
        ensure("confusion_dictionary", "archived_at", "archived_at REAL DEFAULT NULL")
        ensure("confusion_dictionary", "source", "source TEXT NOT NULL DEFAULT '$SOURCE_VOICE'")
        ensure("typed_vocabulary", "archived_at", "archived_at REAL DEFAULT NULL")
        ensure("typed_vocabulary", "last_day", "last_day TEXT")
        return added
    }

    companion object {
        private const val TAG = "VoiceCorrectionDB"

        /** Retention for the diagnostic event log (regenerable signal). */
        private const val MAX_EVENTS = 2_000
        private const val MAX_AGE_DAYS = 90
        private const val PRUNE_EVERY = 64

        /**
         * Bumped ONLY for versioned structural migrations. Additive columns go
         * through [ensureIdempotentColumns] and do NOT require a bump.
         */
        const val SCHEMA_VERSION = 1

        /** Signal captured from editing an ASR transcript. */
        const val SOURCE_VOICE = "voice"

        /**
         * Signal captured from a select-and-replace edit in a plain text field.
         * Kept distinct from [SOURCE_VOICE] so the two can be weighted or
         * cleared independently later.
         */
        const val SOURCE_TEXT = "text_input"

        @Volatile
        private var instance: VoiceCorrectionDb? = null

        /**
         * Shared instance, or null if even the rebuild-empty fallback failed —
         * in which case every caller degrades to "no correction" rather than
         * crashing.
         */
        fun getInstance(context: Context): VoiceCorrectionDb? {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: runCatching {
                    val file = File(context.applicationContext.filesDir, "voice-correction.db")
                    VoiceCorrectionDb(file).takeIf { it.db != null }
                }.getOrNull()?.also { instance = it }
            }
        }

        /** Fresh store on an explicit file — tests must not share the singleton. */
        internal fun openForTest(file: File): VoiceCorrectionDb? =
            VoiceCorrectionDb(file).takeIf { it.db != null }

        /**
         * `frequency / (frequency + negative * 2)` — a rejection costs double a
         * confirmation. (3,0)=1.0, (3,1)=0.6, (1,3)=0.25 which is below the 0.3
         * retrieval floor, so a thrice-rejected pair stops being offered.
         */
        fun confidence(frequency: Int, negative: Int): Double {
            val f = maxOf(frequency, 0).toDouble()
            val n = maxOf(negative, 0).toDouble()
            val denominator = f + n * 2.0
            if (denominator <= 0) return 0.5
            return f / denominator
        }

        /** yyyy-MM-dd in the device zone, the key for `distinct_days`. */
        fun dayKey(epochSeconds: Double): String {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = (epochSeconds * 1000).toLong()
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }

        internal fun decodeVariants(json: String): List<String> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrElse { emptyList() }

        internal fun encodeVariants(variants: List<String>): String =
            JSONArray().apply { variants.forEach { put(it) } }.toString()
    }
}

/** One row of the confusion dictionary, as read by the retrieval path. */
data class ConfusionRow(
    val id: String,
    val phoneticKey: String,
    val variants: List<String>,
    val correctedTerm: String,
    val locale: String,
    val frequency: Int,
    val negativeFeedbackCount: Int,
    val confidence: Double,
    val lastSeen: Double,
)

/** One row of the typed-vocabulary table. */
data class VocabularyRow(
    val id: String,
    val term: String,
    val phoneticKey: String,
    val locale: String,
    val posTag: String?,
    val frequency: Int,
    val distinctDays: Int,
)
