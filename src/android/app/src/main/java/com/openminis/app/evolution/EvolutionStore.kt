package com.openminis.app.evolution

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class EvolutionStore(dir: File) {

    private val helper = Helper(File(dir, "evolution.db"))
    private val _proposals = MutableStateFlow<List<EvolutionProposal>>(emptyList())
    val proposals: StateFlow<List<EvolutionProposal>> = _proposals.asStateFlow()
    private var writesSincePrune = 0

    private companion object {
        /** ~90 days of learning signal; older rows are diagnostics only. */
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
        const val MAX_OBSERVATIONS = 5_000
        const val MAX_TOOL_FAILS = 2_000
        const val PRUNE_EVERY = 64
    }

    init {
        dir.mkdirs()
        // Retention runs before the first read so a long-lived install does
        // not keep paying for old observations on every query.
        prune()
        reload()
    }

    @Synchronized
    fun reload() {
        _proposals.value = queryProposals()
    }

    @Synchronized
    fun insertObservation(
        kind: String,
        sessionId: String?,
        skillId: String?,
        evidence: String,
        fingerprint: String,
    ) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("kind", kind)
            put("session_id", sessionId)
            put("skill_id", skillId)
            put("evidence", evidence.take(EvolutionPrefs.MAX_EVIDENCE_CHARS))
            put("fingerprint", fingerprint)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("observations", null, values)
        maybePrune()
    }

    @Synchronized
    fun bumpBelief(
        fingerprint: String,
        kind: String,
        summary: String,
        scene: SceneTag = SceneTag.GENERAL,
        now: Long = System.currentTimeMillis(),
    ): EvolutionBelief {
        val db = helper.writableDatabase
        val byFp = queryBelief(fingerprint)
        val existing = when {
            byFp != null && byFp.status != BeliefMaintenance.DECAYED -> byFp
            else -> findMergeCandidate(summary) ?: byFp
        }
        if (existing == null) {
            val belief = EvolutionBelief(
                id = UUID.randomUUID().toString(),
                fingerprint = fingerprint,
                kind = kind,
                summary = summary,
                hitCount = 1,
                status = BeliefMaintenance.DRAFT,
                updatedAt = now,
                scene = scene,
                lastHitAt = now,
            )
            db.insert("beliefs", null, ContentValues().apply {
                put("id", belief.id)
                put("fingerprint", belief.fingerprint)
                put("kind", belief.kind)
                put("summary", belief.summary)
                put("hit_count", belief.hitCount)
                put("status", belief.status)
                put("updated_at", belief.updatedAt)
                put("scene", belief.scene.raw)
                put("last_hit_at", belief.lastHitAt)
            })
            return belief
        }
        val hits = existing.hitCount + 1
        val accepted = hasAcceptedForBelief(existing.id)
        val status = BeliefMaintenance.nextTier(
            hitCount = hits,
            lastHitAt = now,
            current = existing.status,
            now = now,
            acceptedCore = accepted,
        )
        db.execSQL(
            "UPDATE beliefs SET hit_count=?, status=?, summary=?, updated_at=?, last_hit_at=?, scene=? WHERE id=?",
            arrayOf(hits, status, summary, now, now, scene.raw, existing.id),
        )
        return existing.copy(
            hitCount = hits,
            status = status,
            summary = summary,
            updatedAt = now,
            lastHitAt = now,
            scene = scene,
        )
    }

    @Synchronized
    fun promoteBelief(id: String, status: String) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.execSQL(
            "UPDATE beliefs SET status=?, updated_at=? WHERE id=?",
            arrayOf(status, now, id),
        )
    }

    @Synchronized
    fun maintainBeliefs(now: Long = System.currentTimeMillis()) {
        val beliefs = listBeliefs()
        for (b in beliefs) {
            if (b.status == BeliefMaintenance.DECAYED) continue
            val accepted = hasAcceptedForBelief(b.id)
            val next = BeliefMaintenance.nextTier(
                hitCount = b.hitCount,
                lastHitAt = b.lastHitAt,
                current = b.status,
                now = now,
                acceptedCore = accepted,
            )
            if (next != b.status) {
                helper.writableDatabase.execSQL(
                    "UPDATE beliefs SET status=?, updated_at=? WHERE id=?",
                    arrayOf(next, now, b.id),
                )
            }
        }
        mergeSimilar()
    }

    @Synchronized
    fun listBeliefs(): List<EvolutionBelief> {
        val out = mutableListOf<EvolutionBelief>()
        helper.readableDatabase.rawQuery(
            "SELECT id, fingerprint, kind, summary, hit_count, status, updated_at, scene, last_hit_at FROM beliefs",
            null,
        ).use { c ->
            while (c.moveToNext()) out.add(rowToBelief(c))
        }
        return out
    }

    @Synchronized
    fun hasOpenProposal(fingerprint: String): Boolean {
        val db = helper.readableDatabase
        db.rawQuery(
            """SELECT 1 FROM proposals WHERE status IN ('pending','deferred','accepted') AND (
                belief_id IN (SELECT id FROM beliefs WHERE fingerprint=?)
                OR skill_id=?
                OR draft_text=?
            ) LIMIT 1""",
            arrayOf(fingerprint, fingerprint.removePrefix("skill:"), fingerprint),
        ).use { c -> return c.moveToFirst() }
    }

    @Synchronized
    fun insertProposal(proposal: EvolutionProposal) {
        val db = helper.writableDatabase
        db.insert("proposals", null, ContentValues().apply {
            put("id", proposal.id)
            put("type", proposal.type.raw)
            put("status", proposal.status.raw)
            put("belief_id", proposal.beliefId)
            put("title", proposal.title)
            put("draft_text", proposal.draftText)
            put("evidence", proposal.evidence.take(EvolutionPrefs.MAX_EVIDENCE_CHARS))
            put("skill_id", proposal.skillId)
            put("rollback_text", proposal.rollbackText)
            put("created_at", proposal.createdAt)
            put("decided_at", proposal.decidedAt)
            put("scene", proposal.scene.raw)
        })
        reload()
    }

    @Synchronized
    fun setStatus(id: String, status: EvolutionProposal.Status, rollbackText: String? = null) {
        val now = System.currentTimeMillis()
        if (rollbackText != null) {
            helper.writableDatabase.execSQL(
                "UPDATE proposals SET status=?, rollback_text=?, decided_at=? WHERE id=?",
                arrayOf(status.raw, rollbackText, now, id),
            )
        } else {
            helper.writableDatabase.execSQL(
                "UPDATE proposals SET status=?, decided_at=? WHERE id=?",
                arrayOf(status.raw, now, id),
            )
        }
        reload()
    }

    @Synchronized
    fun getProposal(id: String): EvolutionProposal? =
        queryProposals().find { it.id == id }

    @Synchronized
    fun coreBulletBodies(): Set<String> {
        val out = mutableSetOf<String>()
        helper.readableDatabase.rawQuery(
            """SELECT p.draft_text FROM proposals p
               LEFT JOIN beliefs b ON p.belief_id = b.id
               WHERE p.status='accepted' AND p.type='learned_rule'
                 AND (b.status='core' OR b.hit_count>=?)""",
            arrayOf(BeliefMaintenance.CORE_HITS.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(0).orEmpty()
                out.add(LearnedPrefsStore.parseItem(raw).body)
            }
        }
        return out
    }

    @Synchronized
    fun recordToolFail(sessionId: String, skillId: String, error: String) {
        helper.writableDatabase.insert("tool_fails", null, ContentValues().apply {
            put("session_id", sessionId)
            put("skill_id", skillId)
            put("error", error.take(EvolutionPrefs.MAX_EVIDENCE_CHARS))
            put("created_at", System.currentTimeMillis())
        })
        maybePrune()
    }

    /**
     * Bounded retention for the two append-only diagnostics tables.
     * `beliefs`/`proposals` are deliberately excluded: they are the learned
     * state the user can inspect and revert, not a log.
     *
     * @return rows deleted, for tests and metrics.
     */
    internal fun prune(
        maxObservations: Int = MAX_OBSERVATIONS,
        maxToolFails: Int = MAX_TOOL_FAILS,
        maxAgeMs: Long = MAX_AGE_MS,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val db = helper.writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
            val cutoff = (now - maxAgeMs).toString()
            deleted += db.delete("observations", "created_at < ?", arrayOf(cutoff))
            deleted += db.delete("tool_fails", "created_at < ?", arrayOf(cutoff))
            deleted += db.delete(
                "observations",
                "id NOT IN (SELECT id FROM observations ORDER BY created_at DESC LIMIT ?)",
                arrayOf(maxObservations.toString()),
            )
            deleted += db.delete(
                "tool_fails",
                "rowid NOT IN (SELECT rowid FROM tool_fails ORDER BY created_at DESC LIMIT ?)",
                arrayOf(maxToolFails.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deleted
    }

    private fun maybePrune() {
        if (++writesSincePrune < PRUNE_EVERY) return
        writesSincePrune = 0
        runCatching { prune() }
    }

    @Synchronized
    fun recentToolFails(sessionId: String, skillId: String, sinceMs: Long): List<String> {
        val out = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT error FROM tool_fails WHERE session_id=? AND skill_id=? AND created_at>=? ORDER BY created_at DESC LIMIT 8",
            arrayOf(sessionId, skillId, sinceMs.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0).orEmpty())
        }
        return out
    }

    @Synchronized
    fun watermark(sessionId: String): String? {
        helper.readableDatabase.rawQuery(
            "SELECT last_message_id FROM harvest_watermarks WHERE session_id=?",
            arrayOf(sessionId),
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    @Synchronized
    fun setWatermark(sessionId: String, lastMessageId: String) {
        helper.writableDatabase.insertWithOnConflict(
            "harvest_watermarks",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("last_message_id", lastMessageId)
                put("harvested_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun hasAcceptedForBelief(beliefId: String): Boolean {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM proposals WHERE belief_id=? AND status='accepted' LIMIT 1",
            arrayOf(beliefId),
        ).use { c -> return c.moveToFirst() }
    }

    private fun findMergeCandidate(summary: String): EvolutionBelief? {
        return listBeliefs()
            .filter { it.status != BeliefMaintenance.DECAYED }
            .firstOrNull { BeliefMaintenance.shouldMerge(it.summary, summary) }
    }

    private fun mergeSimilar() {
        val live = listBeliefs().filter { it.status != BeliefMaintenance.DECAYED }
        val used = mutableSetOf<String>()
        for (i in live.indices) {
            val a = live[i]
            if (a.id in used) continue
            for (j in i + 1 until live.size) {
                val b = live[j]
                if (b.id in used) continue
                if (!BeliefMaintenance.shouldMerge(a.summary, b.summary)) continue
                val keep = if (a.createdLike() <= b.createdLike()) a else b
                val drop = if (keep.id == a.id) b else a
                val hits = keep.hitCount + drop.hitCount
                val lastHit = maxOf(keep.lastHitAt, drop.lastHitAt)
                val status = BeliefMaintenance.nextTier(
                    hitCount = hits,
                    lastHitAt = lastHit,
                    current = keep.status,
                    now = System.currentTimeMillis(),
                    acceptedCore = hasAcceptedForBelief(keep.id) || hasAcceptedForBelief(drop.id),
                )
                helper.writableDatabase.execSQL(
                    "UPDATE beliefs SET hit_count=?, status=?, last_hit_at=?, updated_at=? WHERE id=?",
                    arrayOf(hits, status, lastHit, System.currentTimeMillis(), keep.id),
                )
                helper.writableDatabase.execSQL(
                    "UPDATE proposals SET belief_id=? WHERE belief_id=?",
                    arrayOf(keep.id, drop.id),
                )
                helper.writableDatabase.execSQL(
                    "UPDATE beliefs SET status=?, updated_at=? WHERE id=?",
                    arrayOf(BeliefMaintenance.DECAYED, System.currentTimeMillis(), drop.id),
                )
                used.add(drop.id)
            }
        }
    }

    private fun EvolutionBelief.createdLike(): Long = lastHitAt

    private fun queryBelief(fingerprint: String): EvolutionBelief? {
        helper.readableDatabase.rawQuery(
            "SELECT id, fingerprint, kind, summary, hit_count, status, updated_at, scene, last_hit_at FROM beliefs WHERE fingerprint=? LIMIT 1",
            arrayOf(fingerprint),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return rowToBelief(c)
        }
    }

    private fun rowToBelief(c: android.database.Cursor): EvolutionBelief {
        val updated = c.getLong(6)
        val sceneRaw = if (c.isNull(7)) null else c.getString(7)
        val lastHit = if (c.columnCount > 8 && !c.isNull(8)) c.getLong(8) else updated
        return EvolutionBelief(
            id = c.getString(0),
            fingerprint = c.getString(1),
            kind = c.getString(2),
            summary = c.getString(3),
            hitCount = c.getInt(4),
            status = c.getString(5),
            updatedAt = updated,
            scene = SceneTag.from(sceneRaw),
            lastHitAt = lastHit,
        )
    }

    private fun queryProposals(): List<EvolutionProposal> {
        val out = mutableListOf<EvolutionProposal>()
        helper.readableDatabase.rawQuery(
            "SELECT id, type, status, belief_id, title, draft_text, evidence, skill_id, rollback_text, created_at, decided_at, scene FROM proposals ORDER BY created_at DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val sceneRaw = if (c.columnCount > 11 && !c.isNull(11)) c.getString(11) else null
                out.add(
                    EvolutionProposal(
                        id = c.getString(0),
                        type = EvolutionProposal.Type.from(c.getString(1)),
                        status = EvolutionProposal.Status.from(c.getString(2)),
                        beliefId = c.getString(3),
                        title = c.getString(4),
                        draftText = c.getString(5),
                        evidence = c.getString(6),
                        skillId = c.getString(7),
                        rollbackText = c.getString(8),
                        createdAt = c.getLong(9),
                        decidedAt = if (c.isNull(10)) null else c.getLong(10),
                        scene = SceneTag.from(sceneRaw),
                    ),
                )
            }
        }
        return out
    }

    private class Helper(file: File) {
        val writableDatabase: SQLiteDatabase
        val readableDatabase: SQLiteDatabase
            get() = writableDatabase

        init {
            file.parentFile?.mkdirs()
            writableDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
            onCreate(writableDatabase)
            migrate(writableDatabase)
        }

        private fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS observations (id TEXT PRIMARY KEY, kind TEXT NOT NULL, session_id TEXT, skill_id TEXT, evidence TEXT NOT NULL, fingerprint TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS beliefs (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, summary TEXT NOT NULL, hit_count INTEGER NOT NULL, status TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS proposals (id TEXT PRIMARY KEY, type TEXT NOT NULL, status TEXT NOT NULL, belief_id TEXT, title TEXT NOT NULL, draft_text TEXT NOT NULL, evidence TEXT NOT NULL, skill_id TEXT, rollback_text TEXT, created_at INTEGER NOT NULL, decided_at INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS harvest_watermarks (session_id TEXT PRIMARY KEY, last_message_id TEXT, harvested_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS tool_fails (session_id TEXT NOT NULL, skill_id TEXT NOT NULL, error TEXT NOT NULL, created_at INTEGER NOT NULL)")
        }

        private fun migrate(db: SQLiteDatabase) {
            addColumn(db, "beliefs", "scene", "TEXT")
            addColumn(db, "beliefs", "last_hit_at", "INTEGER")
            addColumn(db, "proposals", "scene", "TEXT")
        }

        private fun addColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
            if (hasColumn(db, table, column)) return
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }

        private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == column) return true
                }
            }
            return false
        }
    }
}
