package com.openminis.app.backup

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Backup record -> Room entity, and the merge rule that decides whether a
 * restored row may replace the local one.
 *
 * Moved out of [BackupImporter]'s DAO loops unchanged so the rules can be
 * tested directly. They are the error-prone part of a restore — a wrong
 * default or merge comparison silently drops or overwrites user data and a
 * restore still reports success — and while they lived inline next to Room
 * the only available tests pinned source text or kept their own copies.
 * The importer keeps everything that needs the database: looking up the
 * existing row, the parent-session check, transactions and the report.
 */
internal object BackupRecordMapper {

    // -- Merge ----------------------------------------------------------------

    /**
     * §8.2: newer `updatedAt` wins. An equal stamp keeps the local row, so
     * re-running the same restore is a no-op and an older backup never undoes
     * work the user did after taking it.
     */
    fun incomingWins(existingUpdatedAt: Long?, incomingUpdatedAt: Long): Boolean =
        existingUpdatedAt == null || incomingUpdatedAt > existingUpdatedAt

    // -- Sessions -------------------------------------------------------------

    sealed class Decoded<out T> {
        /** No id: the record cannot be placed at all. */
        object Unreadable : Decoded<Nothing>()
        /** Readable, but the local row is as new or newer; keep it. */
        data class Stale(val id: String) : Decoded<Nothing>()
        data class Apply<T>(val id: String, val entity: T, val isNew: Boolean) : Decoded<T>()
    }

    /**
     * A `SessionV2` payload, in either shape: iOS nests the session under
     * `"session"` with wrapper fields beside it; Android wrote it flat before
     * 1.14 and nested since. The two levels are merged, inner winning.
     */
    fun session(payload: JsonObject, existing: ChatSessionEntity?): Decoded<ChatSessionEntity> {
        val s = payload.unwrapNested("session")
        val id = s.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = s.millis("updatedAt") ?: 0
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            ChatSessionEntity(
                id = id,
                title = s.str("title"),
                modelId = s.str("modelId") ?: existing?.modelId ?: "",
                createdAt = s.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
                category = s.str("category"),
                lastMessage = s.str("lastMessage"),
                modelBinding = s.str("modelBinding"),
                source = s.str("source"),
                memoryEnabled = if (s.bool("memoryEnabled") != false) 1 else 0,
                pinnedAt = s.millis("pinnedAt"),
                editCount = s.int("editCount") ?: 0,
                thinkingOverride = s.str("thinkingOverride"),
                folderId = s.str("folderId"),
                parentSessionId = s.str("parentSessionId"),
                parentToolUseId = s.str("parentToolUseId"),
            ),
            isNew = existing == null,
        )
    }

    // -- Folders --------------------------------------------------------------

    fun folder(f: JsonObject, existing: FolderEntity?): Decoded<FolderEntity> {
        val id = f.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = f.millis("updatedAt") ?: 0
        // Merge by the same rule as sessions: an older backup must not undo a
        // rename the user made after taking it.
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            FolderEntity(
                id = id,
                name = f.str("name") ?: "",
                icon = f.str("icon"),
                color = f.str("color"),
                origin = f.str("origin") ?: FolderEntity.ORIGIN_MANUAL,
                sortIndex = f.int("sortIndex") ?: 0,
                pinnedAt = f.millis("pinnedAt"),
                // iOS names this field `desc` (ChatStore.Folder.desc); Android
                // wrote `description` (and since 1.14 writes both). Accept
                // either, or a folder restored from an iPhone silently loses
                // its one-line description.
                description = f.str("description") ?: f.str("desc"),
                createdAt = f.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
            ),
            isNew = existing == null,
        )
    }

    // -- Messages ---------------------------------------------------------------

    /** Null when id or sessionId is missing. The parent check is the caller's. */
    fun message(m: JsonObject): MessageEntity? {
        val id = m.str("id") ?: return null
        val sessionId = m.str("sessionId") ?: return null
        val createdAt = m.millis("createdAt") ?: 0
        return MessageEntity(
            id = id,
            sessionId = sessionId,
            role = m.str("role") ?: "user",
            // Re-serialised from the parsed element, so any part type this
            // build doesn't model is preserved verbatim.
            partsJson = (m["parts"]?.toString()) ?: "[]",
            createdAt = createdAt,
            tokenUsage = m["tokenUsage"]?.takeIf { it.toString() != "null" }?.toString(),
            sortOrder = m.int("sortOrder") ?: 0,
            reasoningContent = m.str("reasoningContent"),
            streamInterruptCount = m.int("streamInterruptCount") ?: 0,
            updatedAt = createdAt,
            // errorInfo is device-local (§0.2) and is never restored.
            errorInfo = null,
            // [T-token-attribution-snapshot] Absent in packages written before
            // this existed (and in any category the other platform hasn't
            // updated yet) — null then, which is exactly the "estimated" state
            // the Usage page renders.
            modelId = m.str("modelId"),
            modelDisplayName = m.str("modelDisplayName"),
            providerType = m.str("providerType"),
            providerInstanceId = m.str("providerInstanceId"),
        )
    }

    // -- Compact markers --------------------------------------------------------

    /** Null when id or sessionId is missing. The parent check is the caller's. */
    fun compactMarker(c: JsonObject): CompactMarkerEntity? {
        val id = c.str("id") ?: return null
        val sessionId = c.str("sessionId") ?: return null
        return CompactMarkerEntity(
            id = id,
            sessionId = sessionId,
            summary = c.str("summary") ?: "",
            firstKeptSortOrder = c.int("firstKeptSortOrder") ?: 0,
            compactedCount = c.int("compactedCount") ?: 0,
            createdAt = c.millis("createdAt") ?: 0,
            uiBoundarySortOrder = c.int("uiBoundarySortOrder"),
            boundaryMessageId = c.str("boundaryMessageId"),
            firstKeptMessageId = c.str("firstKeptMessageId"),
            lastCompactedMessageId = c.str("lastCompactedMessageId"),
            // [T-backup-marker-version] Without this a v2 marker restored as v1 and
            // resolved through the legacy firstKeptSortOrder chain (v2 writes
            // Int.MAX_VALUE there). Older packages carry no key: they are v1.
            version = c.int("version") ?: 1,
        )
    }

    // -- JSON helpers -------------------------------------------------------------

    /**
     * [T-android-restore-ios-session-nesting] Flatten `{outer…, key:{inner…}}`
     * into one object, inner winning.
     *
     * iOS wraps some records — a session arrives as
     * `{"memoryEnabled":true,"session":{"id":…,"title":…}}`. Merging instead
     * of choosing means one reader handles both shapes, and the wrapper's own
     * fields (`memoryEnabled`) stay reachable by their plain names.
     *
     * Returns `this` unchanged when [key] is absent or is not an object, so a
     * flat record costs nothing.
     */
    fun JsonObject.unwrapNested(key: String): JsonObject {
        val inner = (this[key] as? JsonObject) ?: return this
        return JsonObject(this.filterKeys { it != key } + inner)
    }

    fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it.toString() != "null" }?.runCatching { jsonPrimitive.content }
            ?.getOrNull()

    fun JsonObject.int(key: String): Int? =
        this[key]?.runCatching { jsonPrimitive.content.toInt() }?.getOrNull()

    fun JsonObject.bool(key: String): Boolean? =
        this[key]?.runCatching { jsonPrimitive.content.toBooleanStrict() }?.getOrNull()

    /**
     * Parse an ISO-8601 instant into epoch millis.
     *
     * iOS writes dates as ISO-8601 strings, but a package written by a future
     * build (or by a tool) could carry a numeric epoch, so both are accepted —
     * §2.2's tolerance rule applied to a value, not just a key.
     */
    fun JsonObject.millis(key: String): Long? {
        val raw = str(key) ?: return null
        raw.toLongOrNull()?.let { return it }
        for (pattern in ISO_PATTERNS) {
            runCatching {
                val f = SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                return f.parse(raw)?.time
            }
        }
        return null
    }

    private val ISO_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )
}
