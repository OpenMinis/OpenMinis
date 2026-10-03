package com.openminis.app.backup

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/**
 * [T-android-backup-subagents] One custom sub agent in `data/sub_agents.jsonl`
 * (envelope type `SubAgentV1`). Field-for-field iOS `BackupSubAgentRecord`
 * (Agent/Backup/BackupFormat.swift, 451f62e52 + db5bfa95f).
 *
 * Why this file exists on Android at all: iOS moved sub agents out of its
 * ProviderConfig into their own store and writes them ONLY here, while
 * Android wrote them ONLY inside `provider_config.json` and never read this
 * file. Each platform's restore therefore dropped the other's custom sub
 * agents, silently. Android now writes and reads both.
 *
 * The fields without defaults are the ones iOS's decoder requires. Kotlinx
 * omits a field equal to its default (encodeDefaults is off), so a default
 * here would drop, say, an empty `instructions` and make iOS reject the record.
 */
@Serializable
data class BackupSubAgentRecord(
    val id: String,
    val name: String,
    val subAgentDescription: String,
    val instructions: String,
    val modelGroupId: String? = null,
    val sortOrder: Int,
    /** ISO-8601, whole seconds, UTC (`2026-09-28T12:00:00Z`). */
    val updatedAt: String,
    /** iOS ThinkingLevel raw value (`"high"`); absent = not set. */
    val thinkingLevelOverride: String? = null,
)

internal object BackupSubAgentMapping {

    const val FILE_BASE = "sub_agents"
    const val RECORD_TYPE = "SubAgentV1"

    /**
     * The built-in is never written: it ships with the app and its name and
     * description are canonical, so an old package's copy must not overwrite
     * a newer build's (same rule as iOS and as built-in thinking rules).
     */
    fun exportable(roster: List<SubAgentDefinition>): List<SubAgentDefinition> =
        roster.filter { !it.isBuiltIn && it.id != SubAgentDefinition.BUILT_IN_ID }

    fun toRecord(def: SubAgentDefinition): BackupSubAgentRecord = BackupSubAgentRecord(
        id = def.id,
        name = def.name,
        subAgentDescription = def.description,
        instructions = def.instructions,
        modelGroupId = def.modelGroupId,
        sortOrder = def.sortOrder,
        // Whole seconds: Swift's `.iso8601` date strategy REJECTS fractional
        // seconds, and one undecodable date fails the whole record on iOS.
        updatedAt = Instant.ofEpochMilli(def.updatedAt).truncatedTo(ChronoUnit.SECONDS).toString(),
        // iOS's raw value. iOS also accepts "HIGH" now (4722d537d), but the
        // file format is iOS's, so write it the way iOS does.
        thinkingLevelOverride = def.thinkingLevelOverride?.name?.lowercase(),
    )

    /**
     * Null for a record that must not be restored: the built-in, or one
     * without a name (the roster matches on names). An unreadable date
     * becomes 0 = older than anything local, so it can add a new agent but
     * never overwrite one the user has.
     */
    fun fromRecord(r: BackupSubAgentRecord): SubAgentDefinition? {
        if (r.id.isBlank() || r.id == SubAgentDefinition.BUILT_IN_ID || r.name.isBlank()) return null
        return SubAgentDefinition(
            id = r.id,
            name = r.name,
            description = r.subAgentDescription,
            instructions = r.instructions,
            modelGroupId = r.modelGroupId?.takeIf { it.isNotBlank() },
            // Case-insensitive: "high" (iOS) and "HIGH" (older Android) both work.
            thinkingLevelOverride = r.thinkingLevelOverride?.let { ThinkingLevel.parseOrNull(it) },
            isBuiltIn = false,
            sortOrder = r.sortOrder,
            updatedAt = parseMillis(r.updatedAt),
        )
    }

    internal fun parseMillis(iso: String): Long =
        runCatching { Instant.parse(iso).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .getOrDefault(0L)
}
