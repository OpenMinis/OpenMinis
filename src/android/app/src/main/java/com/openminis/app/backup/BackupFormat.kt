package com.openminis.app.backup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * [T-android-envvar-iso8601-wire] A `createdAt`-style timestamp that WRITES an
 * ISO-8601 UTC string (matching iOS `JSONEncoder.dateEncodingStrategy =
 * .iso8601`) but READS either form:
 *   - a JSON string → parsed as ISO-8601 (iOS packages, and new Android ones);
 *   - a JSON number → treated as epoch milliseconds (old Android packages,
 *     which wrote `createdAt` as a bare Long — that number is exactly what iOS
 *     rejected with "isn't in the correct format").
 *
 * The in-memory value stays epoch-millis (Long) so callers are unchanged; only
 * the wire representation moves to a string. Kept in BackupFormat so both the
 * exporter and importer share one definition.
 */
object Iso8601MillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Millis", PrimitiveKind.STRING)

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(fmt().format(value))
    }

    override fun deserialize(decoder: Decoder): Long {
        // Tolerate both a JSON string (ISO-8601) and a JSON number (legacy
        // epoch millis). Use JsonDecoder to peek at the actual element type so
        // an old numeric package doesn't throw.
        val jd = decoder as? JsonDecoder
            ?: return runCatching { fmt().parse(decoder.decodeString())?.time }.getOrNull() ?: 0L
        val el = jd.decodeJsonElement()
        val prim = el as? JsonPrimitive ?: return 0L
        prim.longOrNull?.let { return it } // legacy numeric epoch millis
        val s = prim.content
        return runCatching { fmt().parse(s)?.time }.getOrNull() ?: 0L
    }
}

/**
 * [T-android-provider-iso8601-wire] Nullable peer of [Iso8601MillisSerializer]
 * for optional timestamps (e.g. `ModelEntry.userModifiedAt`, which iOS decodes
 * as `Date?`). null round-trips as JSON null; otherwise identical rules —
 * writes ISO-8601, reads ISO strings or legacy epoch-millis numbers.
 */
object Iso8601MillisNullableSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601MillisNullable", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull()
        else Iso8601MillisSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val jd = decoder as? JsonDecoder ?: return Iso8601MillisSerializer.deserialize(decoder)
        val el = jd.decodeJsonElement()
        if (el is kotlinx.serialization.json.JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        prim.longOrNull?.let { return it }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(prim.content)?.time
        }.getOrNull()
    }
}

/**
 * On-the-wire types for the `.minisbak` backup package.
 *
 * Spec: `docs/backup-restore-design.md` §2 / §2.1. This is the FORMAT layer,
 * mirroring `src/ios/Agent/Backup/BackupFormat.swift` field-for-field —
 * §5.4 explicitly forbids inventing a second format, and the manifest keys
 * are sealed by `manifest_mac`, so every name here is wire-frozen.
 *
 * Two rules from §2.2 shape every type:
 *   - Unknown fields are ignored, missing fields get defaults. Every field
 *     that isn't structurally required has a default or is nullable; the
 *     shared [BackupFormat.json] instance is configured tolerant.
 *   - JSONL records carry `t` (type) and `v` (record version) so a reader can
 *     dispatch by type and migrate per record.
 */
object BackupFormat {
    /** Format major version. An unrecognised value must refuse the package. */
    const val CURRENT = "minisbak/1"

    /** File extension registered to the app for "open to import". */
    const val FILE_EXTENSION = "minisbak"

    /**
     * MIME type for a `.minisbak` package.
     *
     * Matches the `application/x-minisbak` iOS declares for the
     * `com.openminis.app.minisbak` UTI (src/ios/Info.plist), so both platforms
     * describe the same artefact. Used with SAF's CreateDocument, where the
     * MIME type drives the extension the picker appends: the generic
     * `application/octet-stream` made it save the package as `.bin`.
     */
    const val MIME_TYPE = "application/x-minisbak"

    /**
     * Cap for a single JSONL shard (§2). Beyond this the writer rolls over to
     * `messages-0002.jsonl` etc., so the importer never holds one giant file
     * in memory.
     */
    const val MAX_SHARD_BYTES = 64 * 1024 * 1024

    /**
     * Tolerant parser per §2.2 rule 2: unknown keys ignored, defaults filled,
     * `null` never emitted for absent optionals (iOS omits them entirely and
     * its hand-written decoders treat explicit null and absence the same).
     */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
}

/**
 * User-facing backup categories (§3). [key] is the manifest key and the
 * on-disk directory name, so renaming one is a format change.
 */
enum class BackupCategory(val key: String) {
    CHATS("chats"),
    SHARED_FILES("shared_files"),
    SKILLS("skills"),
    MEMORY("memory"),
    PROVIDERS("providers"),
    MCP_SERVERS("mcp_servers"),
    VOICE_CORRECTIONS("voice_corrections"),

    /**
     * Shell environment variables. The variable list (id/key/note) travels as
     * `data/env_vars.json` metadata; the VALUES ride in `secrets.json` under
     * the credential policy, exactly like iOS. Wire key must stay
     * `environment_variables` to match the iOS `BackupCategory` raw value so a
     * cross-platform package is decodable on both sides.
     */
    ENVIRONMENT_VARIABLES("environment_variables");

    /**
     * Categories that stream file trees through the blob store — the ones the
     * §3.4 size cap applies to.
     */
    val carriesFileTree: Boolean
        get() = when (this) {
            CHATS, SHARED_FILES, SKILLS -> true
            MEMORY, PROVIDERS, MCP_SERVERS, VOICE_CORRECTIONS,
            ENVIRONMENT_VARIABLES -> false
        }

    companion object {
        fun fromKey(key: String): BackupCategory? = entries.firstOrNull { it.key == key }

        /**
         * Categories a NEW backup may include. Voice Corrections is excluded on
         * the export side only (feature not mature enough to back up, 2026-08-15
         * decision) — the enum case stays because `voice_corrections` sections
         * exist in already-written packages and must remain decodable.
         */
        val backupable: List<BackupCategory>
            get() = entries.filter { it != VOICE_CORRECTIONS }
    }
}

/**
 * `manifest.json` — ALWAYS plaintext, even in an encrypted package (§2.1), so
 * the user can see what a package holds before being asked for a passphrase.
 *
 * Dates travel as ISO-8601 strings, exactly the bytes iOS's
 * `JSONEncoder.dateEncodingStrategy = .iso8601` writes. They are kept as
 * strings here rather than parsed eagerly: the manifest is MAC'd over raw
 * bytes, and a parse-reformat cycle is precisely what the sidecar MAC exists
 * to avoid.
 */
@Serializable
data class BackupManifest(
    val format: String = BackupFormat.CURRENT,
    @SerialName("created_at") val createdAt: String = "",
    /** Data cut-off instant; null on packages from older writers. */
    @SerialName("snapshot_at") val snapshotAt: String? = null,
    val app: AppInfo = AppInfo(),
    /** Display-only. Deliberately NOT the deviceId (§3.1). */
    @SerialName("device_name") val deviceName: String = "Unknown device",
    @SerialName("backup_id") val backupId: String = "",
    val categories: Map<String, CategoryStat> = emptyMap(),
    val limits: Limits = Limits(),
    /** Absent on unencrypted packages. */
    val encryption: Encryption? = null,
    /**
     * Path → SHA-256 of the bytes as stored in the package. For an encrypted
     * package that is the CIPHERTEXT hash, so integrity can be checked before
     * the passphrase is known (§5.3).
     */
    val integrity: Map<String, String> = emptyMap(),
    /**
     * Legacy embedded MAC over a canonical re-encoding of the manifest.
     * Android never writes it (Swift's re-encoding cannot be reproduced
     * byte-exactly here); the authoritative MAC is the raw-bytes sidecar
     * member `manifest.mac`, which both platforms' readers prefer.
     */
    @SerialName("manifest_mac") val manifestMac: String? = null,
) {
    @Serializable
    data class AppInfo(
        val platform: String = "unknown",
        val version: String = "?",
        val build: String = "?",
    )

    /** Per-category counters shown in the picker before anything is decrypted. */
    @Serializable
    data class CategoryStat(
        /**
         * The count in the UNIT THE USER THINKS IN for this category: messages
         * for chats, skills for skills, servers for MCP, provider instances for
         * providers. Never a file count for a category whose user-facing unit
         * is not "file" — a skills tree of 569 files across 12 skills reports
         * `entries = 12`, and the 569 goes in [files].
         */
        val entries: Int = 0,
        val bytes: Long = 0,
        val encrypted: Boolean = false,
        /** Chats only: split `entries` so the UI can say "342 messages + 1204 files". */
        val messages: Int? = null,
        /**
         * Files carried in this category's tree, when that differs from
         * [entries]. Chats and skills both set it. Optional so a package
         * written before it existed still decodes (the UI just omits the
         * detail).
         */
        val files: Int? = null,
        /** Providers only: false marks a "shared copy" with credentials stripped (§3.3). */
        @SerialName("includes_credentials") val includesCredentials: Boolean? = null,
        /**
         * Providers only: custom thinking rules travelling in the same
         * category.
         *
         * They ship inside `providers` because they have no category of their
         * own (adding one would change the cross-platform category set), but
         * they must NOT inflate `entries` — a user with 8 providers and 1 rule
         * saw "9 providers". Counted separately so the UI can name them.
         */
        @SerialName("thinking_rules") val thinkingRules: Int? = null,
    )

    /** §3.4. `maxFileBytes == null` means unlimited — the default. */
    @Serializable
    data class Limits(
        @SerialName("max_file_bytes") val maxFileBytes: Long? = null,
        @SerialName("skipped_files") val skippedFiles: Int = 0,
        @SerialName("skipped_bytes") val skippedBytes: Long = 0,
    )

    @Serializable
    data class Encryption(
        val scheme: String = "",
        val kdf: KDF,
        val verifier: String = "",
    ) {
        @Serializable
        data class KDF(
            /**
             * `alg` and `salt` genuinely have no safe default — a wrong guess
             * would derive the wrong key and surface as "wrong passphrase",
             * so these two stay required (decode throws when absent).
             */
            val alg: String,
            val salt: String,
            @SerialName("m_kib") val mKib: Int? = null,
            val t: Int? = null,
            val p: Int? = null,
            val iterations: Int? = null,
        )
    }
}

/**
 * One line of `files.index.jsonl` — the directory-tree index (§2).
 *
 * Exists ALONGSIDE `blobs.index.jsonl`: blobs.index is a content map and
 * structurally cannot express an empty directory, a skipped path, or the tree
 * shape itself — exactly what a restore needs to rebuild `<sid>/`.
 */
@Serializable
data class BackupFileIndexEntry(
    /** Package-relative logical path, e.g. `chats/<sid>/offloads/out.zip`. */
    val path: String,
    /** Original byte size — recorded even for skipped entries. */
    val size: Long = 0,
    /** null for directories and for size-skipped tombstones. */
    val sha256: String? = null,
    val category: String = "",
    /**
     * Why the content is absent from the package. Present ONLY on tombstones:
     * `"size"` (§3.4 cap), `"not_downloaded"` (cloud placeholder), or
     * `"unreadable"`.
     */
    val skipped: String? = null,
    val isDirectory: Boolean? = null,
) {
    companion object {
        fun file(path: String, size: Long, sha256: String, category: BackupCategory) =
            BackupFileIndexEntry(path, size, sha256, category.key)

        /**
         * A file the size cap excluded — no sha256 (the bytes aren't in the
         * package) but path and true size stay visible (§3.4 "tombstone,
         * don't silently drop").
         */
        fun sizeSkipped(path: String, size: Long, category: BackupCategory) =
            BackupFileIndexEntry(path, size, null, category.key, skipped = "size")

        fun unreadable(path: String, size: Long, category: BackupCategory) =
            BackupFileIndexEntry(path, size, null, category.key, skipped = "unreadable")

        /** Empty directories would otherwise vanish, since nothing references them. */
        fun directory(path: String, category: BackupCategory) =
            BackupFileIndexEntry(path, 0, null, category.key, isDirectory = true)
    }
}

/** One line of `blobs.index.jsonl` — content-addressed payload map (§2). */
@Serializable
data class BackupBlobIndexEntry(
    val sha256: String,
    val size: Long,
    /** First logical path this content was seen at; duplicates only add a files.index line. */
    val path: String,
    val sessionId: String? = null,
    val mime: String? = null,
)

/**
 * Generic JSONL record envelope, matching iOS `BackupRecordEnvelope` — `t` is a
 * type tag, `d` is the payload. Used by `thinking_rules.jsonl`.
 */
@Serializable
data class BackupRecordEnvelope<T>(
    val t: String,
    val d: T,
)

/**
 * One user-authored thinking rule on the wire (§ providers).
 *
 * IMPORTANT: field names ARE the JSON keys (camelCase) — iOS's
 * `BackupThinkingRuleRecord` uses Swift's synthesized `CodingKeys`, i.e. NO
 * snake_case remapping, unlike the manifest types. Do NOT add `@SerialName`
 * snake_case here or a cross-platform package stops decoding.
 *
 * `createdAt` / `updatedAt` are ISO-8601 strings (the bytes iOS's
 * `JSONEncoder.dateEncodingStrategy = .iso8601` writes). Kept as strings: the
 * Android `provider_thinking_rules` table has no time columns, so these are
 * carried for round-trip fidelity but not used for LWW on import (see
 * BackupImporter.importThinkingRules — Android does an id-keyed replace because
 * the local schema has no updated_at to compare against).
 */
@Serializable
data class BackupThinkingRuleRecord(
    val id: String,
    val instanceId: String,
    val sortOrder: Int = 0,
    val scopeKind: String = "allModels",
    val scopePattern: String? = null,
    /** Opaque ThinkingWireFormat JSON — carried verbatim, never parsed. */
    val wireFormatJson: String = "{}",
    val echoField: String? = null,
    val echoTiming: String? = null,
    val label: String = "",
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * Env-var metadata line of `data/env_vars.json` (an array). Mirrors iOS
 * `EnvVarEntry`'s exported shape: identity + note, NEVER the value. The value
 * lives in `secrets.json` (see [BackupSecrets.EnvVarSecret]) under the
 * credential policy.
 */
@Serializable
data class BackupEnvVarMeta(
    val id: String,
    val key: String,
    val note: String = "",
    /**
     * [T-android-envvar-iso8601-wire] Epoch millis in memory, but WRITTEN as an
     * ISO-8601 string on the wire so iOS `EnvVarEntry` (which decodes
     * `createdAt: Date` with `.iso8601`) can read Android packages. Reads both
     * ISO strings and legacy Android numeric epoch millis. Cosmetic on import
     * (Android restore keys env vars by name, not createdAt).
     */
    @Serializable(with = Iso8601MillisSerializer::class)
    val createdAt: Long = 0,
)

/**
 * `secrets.json` — the ONE credential-carrying member, encrypted with the
 * separate `secretsKey` subkey so "strip credentials" is a file deletion.
 * Every value here is base64 of the raw UTF-8 secret (stage-3a: base64, not
 * yet encrypted; the package encryption pass seals the whole file).
 *
 * Schema is wire-frozen to iOS `BackupSecrets` (BackupSecrets.swift) so a
 * package restores across platforms.
 */
@Serializable
data class BackupSecrets(
    val v: Int = 1,
    val providers: List<ProviderSecret> = emptyList(),
    val envVars: List<EnvVarSecret> = emptyList(),
    val mcpOAuth: List<MCPOAuthSecret> = emptyList(),
) {
    @Serializable
    data class ProviderSecret(
        val instanceId: String,
        val label: String? = null,
        val providerType: String? = null,
        val apiKey: String? = null,
        val manualOAuthToken: String? = null,
        val oauthToken: String? = null,
        val oauthEmail: String? = null,
        val oauthGcpProject: String? = null,
    ) {
        val isEmpty: Boolean
            get() = apiKey == null && manualOAuthToken == null &&
                oauthToken == null && oauthEmail == null && oauthGcpProject == null
    }

    /** base64 of the value's UTF-8 bytes. */
    @Serializable
    data class EnvVarSecret(val name: String, val value: String)

    @Serializable
    data class MCPOAuthSecret(
        val serverId: String,
        val token: String,
        val clientSecret: String? = null,
    )
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
