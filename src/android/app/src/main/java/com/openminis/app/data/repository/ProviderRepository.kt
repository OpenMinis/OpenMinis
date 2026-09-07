package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.ProviderConfigSnapshot
import com.openminis.app.data.db.ProviderThinkingRuleEntity
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.isVoiceTemplateSeedShape
import com.openminis.app.data.model.withInferredVoiceModality
import com.openminis.app.provider.ModelReleaseIndex
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

// Modality bit layout — must match src/ios/Providers/LLMTypes.swift
// ModelModality OptionSet rawValue exactly. Used by export/import to
// transmit modality info as a single Int that iOS can decode.
private const val MODALITY_BIT_TEXT_IN = 1 shl 0
private const val MODALITY_BIT_TEXT_OUT = 1 shl 1
private const val MODALITY_BIT_IMG_IN = 1 shl 2
private const val MODALITY_BIT_PDF_IN = 1 shl 3
private const val MODALITY_BIT_AUD_IN = 1 shl 4
private const val MODALITY_BIT_VID_IN = 1 shl 5
private const val MODALITY_BIT_IMG_OUT = 1 shl 6
private const val MODALITY_BIT_AUD_OUT = 1 shl 7
private const val MODALITY_BIT_VID_OUT = 1 shl 8

class ProviderRepository(private val context: Context) {

    // [T-android-thinking-level-arch] coerceInputValues makes kotlinx.serialization
    // fall back to a property's DEFAULT when it can't decode the wire value —
    // crucially, this covers an unknown ENUM value (e.g. a ThinkingLevel a newer
    // build wrote, like "MAX"/"ULTRA", read by an older build). Without it the
    // JSON-mirror decode throws SerializationException on that enum, and since
    // that mirror is the fallback for a failed Room DB load, the whole config
    // would come back empty (all providers/groups vanish from the UI).
    // `ignoreUnknownKeys` only skips unknown object keys, NOT unknown enum
    // values — coerceInputValues is the piece that handles those. Fields carrying
    // ThinkingLevel are nullable with a null default, so an unknown value coerces
    // cleanly to null.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // [T-android-provider-room-store] Per-row provider config DB. Lives in
    // its own provider.db file so a downgrade to a build that doesn't know
    // about provider tables can't crash the main minis.db open. The legacy
    // SharedPreferences JSON mirror under "provider_config / config" is
    // still written on every save so older builds keep reading current
    // config — losing nothing on downgrade. See [loadConfig] for the
    // downgrade-and-back-up reconciliation logic.
    private val providerDao: ProviderConfigDao =
        ProviderDatabase.getInstance(context).providerConfigDao()

    companion object {
        /**
         * Per-instance model-cache TTL. Matches iOS's daily calendar-day
         * refresh window (24h rolling here — simpler than calendar-day math
         * and the behavioral difference at midnight is negligible). Used by
         * [triggerBackgroundRefreshIfStale] to decide whether a UI-triggered
         * stale-while-revalidate refresh should fire.
         */
        private const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** Per-instance `lastFetchAt` pref key. */
        private fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

        /** [T-newchat-default-model-fallback-android] Global last-used model entry id. */
        private const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"

        /**
         * [T-android-provider-voice] Normalize a base URL for shadow-voice
         * cross-instance de-dup: lowercased, trailing "/" and "/v1" stripped.
         * Mirrors iOS ProviderConfigStore.normalizedShadowKey.
         */
        fun normalizedShadowKey(baseURL: String?): String {
            var s = baseURL?.trim()?.lowercase() ?: return ""
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.endsWith("/v1")) s = s.dropLast(3)
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }
    }

    /** Record the time of a successful model fetch for [instanceId]. */
    private fun markInstanceFetched(instanceId: String) {
        prefs.edit().putLong(lastFetchKey(instanceId), System.currentTimeMillis()).apply()
    }

    /** Whether the cached model list for [instanceId] is older than the TTL. */
    private fun isInstanceStale(instanceId: String): Boolean {
        val last = prefs.getLong(lastFetchKey(instanceId), 0L)
        return last == 0L || (System.currentTimeMillis() - last) > MODEL_CACHE_TTL_MS
    }

    /**
     * Clear the stored `lastFetchAt` for [instanceId] so the next
     * [triggerBackgroundRefreshIfStale] / [refreshAllModelsIfNeeded] call
     * refreshes it immediately. Called when instance config changes in ways
     * that could alter the set of available models (e.g. base URL rewrite,
     * credential swap). Mirrors iOS's implicit invalidation on
     * `updateInstance` / `removeInstance`.
     */
    fun invalidateModelCache(instanceId: String) {
        prefs.edit().remove(lastFetchKey(instanceId)).apply()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: route through the self-healing
        // factory so a corrupted master key on Samsung One UI / Android
        // 16 doesn't crash the app at first read.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    // [T-android-startup-config-stall] #753: loadConfig() does a synchronous
    // SharedPreferences read (~3s first-touch as the XML is parsed) + a
    // Json.decodeFromString<ProviderConfig> (~8s on a large config). It used to
    // run INLINE in this field initializer, i.e. inside ProviderRepository's
    // constructor, which MinisApp.onCreate() invokes on the MAIN THREAD — so
    // cold start hung >11s. Now we start with an empty placeholder (instant, no
    // I/O) and load the real config on Dispatchers.IO, emitting it when ready.
    // Reactive consumers (config.collectAsState) update automatically on emit;
    // action-time `config.value` reads tolerate the brief empty window (a send
    // before load just has no model entry, exactly like a fresh install).
    private val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    /**
     * [T-android-startup-config-stall] False until the persisted config has
     * been read off-thread and emitted. First-screen code that branches on
     * `instances.isEmpty()` (the onboarding gate) reads this to avoid flashing
     * a "no providers" / onboarding state for an existing user during the load
     * window. Distinguishes "empty because not loaded yet" from "empty because
     * the user genuinely has no providers".
     */
    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    /** Internal scope for the one-shot async config load. */
    private val loadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Completes once the initial off-thread load has emitted (or determined
     * there's nothing persisted). Lets startup consumers that genuinely need
     * the config (e.g. the daily model refresh) wait instead of racing the
     * empty placeholder.
     */
    private val configLoadComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitConfigLoaded() = configLoadComplete.await()

    init {
        loadScope.launch {
            // [T-android-provider-empty-load-wipe] loadConfig() THROWS when the
            // store is unreadable (rather than returning an empty config that a
            // later save would write over real data). Contain it here: an
            // uncaught throw in this coroutine reaches the default uncaught
            // handler and crashes cold start — in a LOOP, for as long as the DB
            // stays unreadable, which is precisely the post-crash state the
            // refusal exists for. Swallowing it leaves _configLoaded false, so
            // ensureConfigLoaded retries on the next access.
            try {
                val loaded = loadConfig()
                synchronized(configLock) {
                    // Only adopt the disk config if nothing has written in the
                    // meantime. A write during the load window (rare — writes come
                    // from user/refresh actions that themselves need config) flips
                    // _configLoaded true and takes precedence; we must not clobber
                    // it with the stale on-disk snapshot.
                    if (!_configLoaded.value) {
                        _config.value = loaded
                        _configLoaded.value = true
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] async loader adopted ${loaded.instances.size} instances",
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] initial load failed; leaving config unloaded for retry: ${e.message}",
                )
            }
            // [T-android-thinking-rules-cold-cache] Prime the resolver's custom-rule
            // cache HERE, not only in ensureConfigLoaded(). That helper early-returns
            // once _configLoaded is true, and on a cold start THIS loader is what flips
            // the flag — so its warm-up call never ran and the resolver spent the whole
            // process lifetime with an empty cache. An empty cache means built-in rules
            // only: a relay whose model ids match no vendor pattern falls through to
            // `openai-compatible-default` (root reasoning_effort) and the endpoint 400s.
            // To the user that reads as "my custom thinking rule stopped working after a
            // restart" — and because every mutation republishes the cache, deleting and
            // re-adding the rule "fixed" it for the rest of that session. iOS primes the
            // same cache in ProviderConfigStore.adoptDB; this is the Android parity fix.
            warmThinkingRuleCache()
            // Completed in every path, including the failure one: awaitConfigLoaded()
            // callers (refreshAllModelsIfNeeded) would otherwise suspend forever.
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // [T-android-provider-voice] Reconcile voice-template seeds after
            // the initial load (add new template models, drop retired seeds,
            // heal corrupted modality). Mirrors iOS ProviderConfigStore.init.
            try {
                ensureVoiceTemplateModels()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[Voice] ensureVoiceTemplateModels failed: ${e.message}")
            }
        }
    }

    /**
     * Lock guarding all mutate-and-save sequences against concurrent writers
     * (notably the `for instance in enabled { scope.launch { autoRefreshModels(...) } }`
     * fan-out in [refreshAllModelsIfNeeded] / [triggerBackgroundRefreshIfStale]).
     *
     * Without this lock, two parallel `replaceEntries(instanceA, …)` and
     * `replaceEntries(instanceB, …)` calls share the same `_config.value`
     * reference (its inner ArrayLists are the same objects across reads),
     * so one's `removeAll` / `addAll` runs while the other's `saveConfig`
     * is mid-`Json.encodeToString` — that's the
     * `ConcurrentModificationException → ArrayList.next` crash captured on
     * Pixel 6 / 4a.
     */
    private val configLock = Any()

    private fun loadConfig(): ProviderConfig = runBlocking { loadConfigSuspending() }

    /**
     * [T-android-provider-room-store] DB-first load with three-way
     * reconciliation between provider.db and the legacy JSON mirror:
     *
     *   - DB has rows AND meta.json_sync_hash matches the live mirror's
     *     hash → DB is in sync with what we last wrote. Use DB.
     *   - DB has rows but the hash mismatches → an older app build was
     *     installed at some point, wrote through the JSON path, and
     *     bypassed our DB. The JSON is fresher. Re-import JSON → rewrite
     *     DB → resync hash.
     *   - DB is empty but JSON exists → first launch on a build that
     *     knows about the DB. One-shot import from JSON → DB.
     *   - Both empty → empty config (fresh install).
     *
     * The JSON mirror is the durable downgrade safety net: we keep
     * writing it on every save so the old build always sees current
     * config; if the user round-trips through an old build, the
     * hash check above re-syncs DB to whatever JSON looks like now.
     */
    private suspend fun loadConfigSuspending(): ProviderConfig {
        val rawJson = prefs.getString("config", null)
        // [T-android-provider-empty-load-wipe] Distinguish "the DB says zero"
        // from "we could not ask the DB". Swallowing the exception as 0 made a
        // transient DAO failure (locked/mid-write file after a crash) look
        // exactly like a fresh install: the loader fell through to an empty
        // ProviderConfig(), and the next mutation's persistToDbAndMirror wrote
        // that emptiness over a fully populated store — wiping every provider.
        // Observed on Pixel 6 after a ConcurrentModificationException crash
        // left provider.db mid-write: 18 instances became 5.
        var daoReadFailed = false
        val instanceCount = try {
            providerDao.instanceCount()
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] DAO instanceCount failed: ${e.message}")
            daoReadFailed = true
            0
        }
        android.util.Log.i(
            "ProviderRepo",
            "[ProviderStore] load: dbInstances=$instanceCount daoFailed=$daoReadFailed " +
                "mirrorBytes=${rawJson?.length ?: -1}",
        )

        val (dbConfig, dbHashStored) = if (instanceCount > 0) {
            try {
                val snapshot = ProviderConfigSnapshot(
                    instances = providerDao.loadInstances(),
                    entries = providerDao.loadEntries(),
                    groups = providerDao.loadGroups(),
                    loopIds = providerDao.loadAgentLoopIds(),
                    meta = providerDao.loadMeta(),
                )
                val cfg = snapshot.toProviderConfig(json)
                val storedHash = snapshot.meta.firstOrNull {
                    it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH
                }?.value
                cfg to storedHash
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] DB load failed, falling back to JSON: ${e.message}")
                daoReadFailed = true
                null to null
            }
        } else {
            null to null
        }

        if (dbConfig != null) {
            val liveHash = rawJson?.let(::hashJsonMirror)
            if (liveHash == dbHashStored) {
                return dbConfig
            }
            // Hash mismatch: JSON has been written by an older build during
            // a downgrade window. Re-import JSON → reseed DB so DB catches
            // up to the user's actual current config.
            android.util.Log.i(
                "ProviderRepo",
                "[ProviderStore] hash mismatch (stored=${dbHashStored?.take(8)} live=${liveHash?.take(8)}) — re-importing JSON mirror",
            )
        }

        if (rawJson != null) {
            val parsed = try {
                json.decodeFromString<ProviderConfig>(rawJson)
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] JSON decode failed: ${e.message}")
                null
            }
            if (parsed != null) {
                val mirrored = try {
                    persistToDbAndMirror(parsed)
                } catch (e: Exception) {
                    android.util.Log.w("ProviderRepo", "[ProviderStore] JSON→DB import failed: ${e.message}")
                    parsed
                }
                // Migrate the per-user lastUsedEntryId SharedPreferences key
                // from the legacy random-uuid entry id form to the new
                // composite "{instanceId}/{modelId}" shape, using the
                // pre-canonicalization `parsed` entries as the uuid→composite
                // dictionary. Without this, the user's last-picked model on
                // the upgrade-first-launch isn't recognized by
                // lastUsedVisibleEntry() and the next new chat falls through
                // to the newest-provider fallback — i.e. it looks like the
                // upgrade "forgot" the user's recent selection.
                val legacyLastUsed = prefs.getString(KEY_LAST_USED_ENTRY, null)
                if (legacyLastUsed != null && !legacyLastUsed.contains('/')) {
                    val rewritten = parsed.modelEntries
                        .firstOrNull { it.uuid == legacyLastUsed }
                        ?.let { compositeEntryKey(it.providerInstanceId, it.baseModel.id) }
                    if (rewritten != null) {
                        prefs.edit().putString(KEY_LAST_USED_ENTRY, rewritten).apply()
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] lastUsedEntryId rewritten ${legacyLastUsed.take(8)} → $rewritten",
                        )
                    }
                }
                android.util.Log.i(
                    "ProviderRepo",
                    "[ProviderStore] migrated/synced ${mirrored.instances.size} instances " +
                        "${mirrored.modelEntries.size} entries from JSON → Room",
                )
                return mirrored
            }
        }

        // [T-android-provider-room-store] Last-resort fallback. If DB had
        // rows but the live JSON mirror is unparseable (disk corruption,
        // interrupted write, etc.) AND we couldn't re-import, KEEP THE DB
        // — losing user config is worse than running with a stale mirror.
        // The next successful save will rewrite the mirror and resync the
        // hash. Returning ProviderConfig() here would let the very next
        // mutator's persistToDbAndMirror overwrite the populated DB with
        // an empty config, silently wiping the user's providers.
        if (dbConfig != null) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror unreadable + re-import failed; keeping " +
                    "${dbConfig.instances.size} DB instances as authoritative",
            )
            return dbConfig
        }

        // [T-android-provider-empty-load-wipe] Reaching here means BOTH stores
        // came back empty. That is legitimate on a fresh install — but if the
        // DB read actually FAILED (rather than honestly reporting zero rows),
        // an empty config is a lie we are about to persist over real data.
        // Refuse: throwing keeps _configLoaded false, so ensureConfigLoaded
        // retries on the next access instead of caching the empty value, and
        // no mutation can run against a phantom-empty config.
        if (daoReadFailed) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] REFUSING empty config — DB read failed and JSON mirror " +
                    "unusable; not overwriting a possibly-populated store",
            )
            throw IllegalStateException(
                "Provider store unreadable (DB read failed, JSON mirror unusable) — " +
                    "refusing to load an empty config that would overwrite existing providers",
            )
        }
        return ProviderConfig()
    }

    /**
     * Stable hash of the JSON mirror string, used as the in-DB synced-state
     * marker. SHA-256 hex so collisions are negligible. Returns null only
     * if [str] is null (caller normalizes).
     */
    private fun hashJsonMirror(str: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(Character.forDigit(v shr 4, 16))
                append(Character.forDigit(v and 0xF, 16))
            }
        }
    }

    /**
     * Atomically persist [config] to (DB) + (legacy JSON mirror), with
     * the meta.json_sync_hash kept in lockstep with the mirror we just
     * wrote. Returns the canonicalized config (entry uuids rewritten to
     * the composite "{instanceId}/{modelId}" shape via the snapshot
     * round-trip). Callers should treat the return value as the new
     * authoritative state — using the in-memory pre-call object would
     * leak the legacy uuid form into [_config.value].
     */
    private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
        // First serialize without the hash so the meta row reflects the
        // exact string we put into prefs (the hash sees the mirror that
        // older builds will read, not a hash-of-itself).
        val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
        val mirrorHash = hashJsonMirror(mirrorStr)
        val snapshot = config.toSnapshot(json, jsonSyncHash = mirrorHash)
        providerDao.replaceAll(
            instances = snapshot.instances,
            entries = snapshot.entries,
            groups = snapshot.groups,
            loopIds = snapshot.loopIds,
            meta = snapshot.meta,
        )
        // commit() not apply(): the json_sync_hash we just stored to DB is
        // a hash of THIS mirror string. If apply() queues the disk write
        // and the process dies before it flushes, the next launch sees
        // DB(hash=new) + JSON-on-disk(content=old) → the hash-mismatch
        // path interprets it as "old build wrote during downgrade" and
        // re-imports the stale JSON, blowing away the write that the DB
        // already persisted synchronously. commit() blocks the writer
        // (~5–30ms) but guarantees DB and JSON land together.
        //
        // commit() returns false (no exception) on disk-full / permission
        // failure / corrupted prefs XML. We log so the situation is
        // observable; the DB already holds the new state authoritatively,
        // and a subsequent successful save will resync the mirror + hash.
        // The downside if the next save never comes: a hash mismatch on
        // next cold-start would try to re-import the stale mirror — but
        // the dbConfig-fallback at the end of loadConfigSuspending keeps
        // the DB rows when re-import fails or is rejected, so the user's
        // data is not lost.
        val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
        if (!mirrorWritten) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror commit() returned false — DB updated " +
                    "but JSON write rejected (disk full? prefs corruption?); " +
                    "DB remains authoritative",
            )
        }
        // Return canonicalized form so the caller's _config.value reflects
        // entry uuids in composite-key shape from this write forward.
        return snapshot.toProviderConfig(json)
    }

    /**
     * [T-android-startup-config-stall] Guard for read-modify-write mutators.
     * If the async load hasn't applied yet, load synchronously NOW (on the
     * caller's thread) before the mutator reads `_config.value`. Without this a
     * write that lands during the startup load window would read the empty
     * placeholder, mutate it, and persist — WIPING the user's real config.
     * Idempotent and cheap once loaded (just a volatile read). Synchronized on
     * [configLock] so it can't race the async loader's emit.
     */
    // internal (was private): the debug server's read-only provider.* handlers
    // read `config.value` directly and must be able to force the lazy load —
    // on a cold process they otherwise report an EMPTY config, which reads as
    // "all your providers are gone" rather than "not loaded yet".
    internal fun ensureConfigLoaded() {
        if (_configLoaded.value) return
        synchronized(configLock) {
            if (_configLoaded.value) return
            // [T-android-provider-empty-load-wipe] loadConfig() throws when the
            // store is unreadable. Do NOT let that escape into the caller: this
            // runs from UI handlers (every read-modify-write mutator calls it),
            // and a throw there crashes whichever gesture triggered it. Leaving
            // _configLoaded false means the next access retries, and callers
            // see the empty placeholder — which is safe, because the mutators'
            // saves cannot overwrite a store the loader refused to read.
            val loaded = try {
                loadConfig()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] ensureConfigLoaded failed; staying unloaded for retry: ${e.message}",
                )
                return
            }
            _config.value = loaded
            _configLoaded.value = true
        }
        // [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache once
        // config is available, so the (sync) request builder can read user rules.
        loadAllThinkingRulesIntoCache()
        if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
    }

    private fun saveConfig(config: ProviderConfig) {
        // [T-android-provider-room-store] Double-write: DB + legacy JSON
        // mirror, both produced inside the same configLock window. The
        // mirror keeps older app builds able to read current config on
        // downgrade; the DB is the new authoritative store on this build.
        //
        // Serialize + persist + emit under [configLock] so we never serialize
        // a list that another writer is mutating. The fresh `.copy(…toMutableList())`
        // wrapper alone is not enough: data-class structural equals walks the
        // inner Lists and `prev` (already mutated in place by replaceEntries /
        // addEntry / removeEntry) compares equal to `next` → MutableStateFlow
        // suppresses the emission. T273 bumps `revision` so equals always
        // returns false and 18+ collectAsState callers see the new value.
        synchronized(configLock) {
            // [T-android-provider-empty-load-wipe] No "block empty saves" guard
            // here on purpose: mutators pass the SAME object as _config.value
            // and mutate it in place, so at this point an empty `config` and an
            // empty `_config.value` are the same list — a legitimate
            // "user deleted their last provider" is indistinguishable from a
            // phantom-empty load. The wipe is prevented at the source instead
            // (loadConfigSuspending refuses to return a blank config when the
            // DB read failed rather than honestly reporting zero rows).
            // persistToDbAndMirror returns the canonicalized config (entries'
            // uuid in composite "{instanceId}/{modelId}" form). Emit that so
            // subsequent in-memory reads — which compare entry.id by string
            // equality (e.g. group.memberEntryIds.contains(it.id)) — use one
            // consistent id shape rather than mixing legacy random uuids and
            // composite keys.
            //
            // Catch persistence failures so callers stay fire-and-forget
            // (matches the legacy `apply()` contract — pre-Room saveConfig
            // never threw). DB write fails are rare in practice (disk full,
            // SQLite corruption, transaction deadlock) but uncaught they'd
            // crash whichever UI handler triggered the mutation. Still
            // emit the in-memory state so the UI reflects the user's
            // intent even when the disk write didn't land; the next
            // successful save resyncs everything.
            val canonical = try {
                runBlocking { persistToDbAndMirror(config) }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] saveConfig persistence failed; emitting in-memory only: ${e.message}",
                    e,
                )
                config
            }
            _config.value = canonical.copy(
                instances = canonical.instances.toMutableList(),
                modelEntries = canonical.modelEntries.toMutableList(),
                modelGroups = canonical.modelGroups.toMutableList(),
                agentLoopModelEntryIds = canonical.agentLoopModelEntryIds.toMutableList(),
                agentLoopGroupIds = canonical.agentLoopGroupIds.toMutableList(),
                revision = ProviderConfig.nextRevision(),
            )
        }
    }

    // [T-android-provider-mutator-lock] Snapshot, not the live list. The
    // declared type is List, but the backing object is the MutableList that
    // mutators append to in place — so a caller iterating this (e.g.
    // ProvidersCollection.childIds) could take a ConcurrentModificationException
    // from a concurrent addInstance. Copying under the lock costs a few objects
    // and removes the hazard for every current and future caller.
    val instances: List<ProviderInstance>
        get() = synchronized(configLock) { _config.value.instances.toList() }

    /**
     * [T-android-provider-emitted-list-cow] A PRIVATE working copy of the
     * current config for a mutator to modify.
     *
     * The lock alone does not make mutation safe, because Compose readers do
     * not take it: `_config.value` is the object collectors are iterating, and
     * mutating its lists in place throws ConcurrentModificationException *in
     * the reader's* frame — reproduced on Pixel 6 as a crash inside
     * UnifiedModelPickerSheet's LazyColumn while three imports ran
     * concurrently:
     *
     *   ArrayList$Itr.checkForComodification → UnifiedModelPickerSheet
     *     → LazyListIntervalContent.<init> → Snapshot.observe
     *
     * saveConfig already EMITS defensive copies; the gap was that the next
     * mutator read that emitted object back and mutated it. Copy-on-write here
     * closes the loop: a mutator never touches a published object, so whatever
     * a reader is iterating stays frozen for the life of that iteration.
     */
    private fun workingCopy(): ProviderConfig {
        val live = _config.value
        return live.copy(
            instances = live.instances.toMutableList(),
            modelEntries = live.modelEntries.toMutableList(),
            // Deep-copy the groups too: ModelGroup.memberEntryIds is itself a
            // MutableList that removeEntry/removeGroup edit in place, so a
            // shallow list copy would still expose the published members to a
            // reader mid-iteration.
            modelGroups = live.modelGroups
                .map { g -> g.copy(memberEntryIds = g.memberEntryIds.toMutableList()) }
                .toMutableList(),
            agentLoopModelEntryIds = live.agentLoopModelEntryIds.toMutableList(),
            agentLoopGroupIds = live.agentLoopGroupIds.toMutableList(),
        )
    }

    fun addInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.instances.add(instance)
        // Seed built-in model entries ONLY when the seed is appropriate for this
        // instance. Mirrors iOS ProviderConfigStore.addInstance:
        //   - OAuth instances always get seeded (their /v1/models often requires
        //     a manual token or isn't reachable, so the static list is the
        //     baseline UX).
        //   - API-key instances on a third-party OpenAI-compatible base URL
        //     (e.g. xAI Grok https://api.x.ai, vLLM, Ollama, LiteLLM, DeepSeek
        //     via OpenAI shim) MUST NOT inherit `LLMModel.allOpenAI` — that's
        //     where the "Refresh on Grok returns GPT-5.5/5.3-codex" bug came
        //     from. For these, leave entries empty and let refreshModels()
        //     populate from the upstream /v1/models call.
        //   - API-key instances on an official endpoint (no customBaseURL, or
        //     a customBase that points at the canonical host) keep the seed so
        //     UI isn't blank during the first refresh round-trip.
        val shouldSeed = instance.credentialType == ProviderCredential.oauth ||
            !isThirdPartyOpenAICompat(instance)
        if (shouldSeed) {
            val entries = instance.providerType.builtInModels.map { model ->
                ModelEntry(providerInstanceId = instance.id, baseModel = model)
            }
            config.modelEntries.addAll(entries)
        } else {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] addInstance: skip built-in seed for third-party OpenAI-compat base " +
                    "(label=${instance.label} base=${instance.effectiveBaseURL}) — " +
                    "models will populate from upstream /v1/models on refresh",
            )
        }
        // [T-android-provider-voice] Seed voice-template mock models when the
        // base URL matches a voice vendor (MiMo / MiniMax / Doubao …). These
        // vendors have no /v1/models for their voices, so the template list is
        // the only source. Mirrors iOS addInstance + VoiceProviderTemplate.
        val voiceSeeds = VoiceProviderTemplate.mockEntries(instance)
            .filter { seed ->
                config.modelEntries.none {
                    it.providerInstanceId == instance.id && it.baseModel.id == seed.baseModel.id
                }
            }
        if (voiceSeeds.isNotEmpty()) {
            config.modelEntries.addAll(voiceSeeds)
            android.util.Log.i(
                "ProviderRepo",
                "[Voice] addInstance seeded ${voiceSeeds.size} voice-template models for ${instance.label}",
            )
        }
        saveConfig(config)
        // Stale from the start so the next background sweep (or an explicit
        // triggerBackgroundRefreshIfStale call) will fetch it.
        invalidateModelCache(instance.id)
    }

    /**
     * [T-android-provider-voice] Reconcile voice-template seed entries on every
     * launch: add template models a previous build didn't know, remove RETIRED
     * seeds, and heal corrupted modality. Straight port of iOS
     * ProviderConfigStore.ensureVoiceTemplateModels with its two hard-won
     * guards:
     *
     *  - [T-voice-seed-shape-exact] Stale-seed removal matches ONLY the exact
     *    single-flag seed shape (isVoiceTemplateSeedShape). API-fetched models
     *    always carry the paired text side (≥2 flags), so a future API ASR/TTS
     *    model can never be mistaken for a seed and wiped every launch
     *    (regression class of cffbec0e: MiMo launch model count 15→11).
     *  - Heal: a models refresh can overwrite a template entry with text-only
     *    modality from /v1/models; restore the template's authoritative
     *    modality when it diverged.
     */
    fun ensureVoiceTemplateModels() = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        var changed = false
        for (instance in config.instances) {
            val tpl = VoiceProviderTemplate.template(instance.customBaseURL) ?: continue
            val templateById = tpl.mockModels.associateBy { it.id }
            val instanceEntries = config.modelEntries.filter { it.providerInstanceId == instance.id }
            val existingIds = instanceEntries.map { it.baseModel.id }.toSet()

            // Remove stale template seeds (exact seed shape only) not in the
            // current template version.
            val staleSeedIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .map { it.baseModel.id }
                .filter { it !in templateById }
            if (staleSeedIds.isNotEmpty()) {
                config.modelEntries.removeAll {
                    it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds
                }
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: removed stale voice entries: ${staleSeedIds.sorted()}")
            }

            // Add template models missing from this instance.
            val toAdd = templateById.keys - existingIds
            if (toAdd.isNotEmpty()) {
                val newEntries = tpl.mockModels
                    .filter { it.id in toAdd }
                    .map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
                config.modelEntries.addAll(newEntries)
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: added ${newEntries.size} new entries: ${toAdd.sorted()}")
            }

            // Heal corrupted modality on surviving template entries.
            for (i in config.modelEntries.indices) {
                val e = config.modelEntries[i]
                if (e.providerInstanceId != instance.id) continue
                val tplModel = templateById[e.baseModel.id] ?: continue
                if (e.baseModel.inputModalities == tplModel.inputModalities &&
                    e.baseModel.outputModalities == tplModel.outputModalities
                ) continue
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: healing modality for ${e.baseModel.id}")
                config.modelEntries[i] = e.copy(baseModel = tplModel)
                changed = true
            }
        }
        if (changed) saveConfig(config)
    }

    /**
     * Whether [instance] points at a third-party OpenAI-compatible host
     * (xAI Grok, vLLM, Ollama, LiteLLM, DeepSeek via OpenAI shim, etc.).
     * For these instances we must never substitute `LLMModel.allOpenAI` as a
     * fallback / seed — those are GPT-only IDs that don't exist upstream.
     * Mirrors iOS `ProviderConfigStore.isThirdPartyOpenAICompat`.
     */
    private fun isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
        if (instance.providerType != ProviderType.openAI) return false
        val custom = instance.customBaseURL?.lowercase() ?: return false
        val officialHosts = listOf("api.openai.com", "chatgpt.com")
        return officialHosts.none { custom.contains(it) }
    }

    fun updateInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.instances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) {
            val prior = config.instances[idx]
            // [T-android-image-endpoint-mode] If the base URL or v1-suffix
            // changed, the previously probed image endpoint may not exist on the
            // new upstream — drop the cached resolution so auto mode re-probes.
            // Mirrors iOS ProviderConfigStore.updateInstance. Only touch it when
            // the caller didn't already set it (e.g. the UI clears it itself when
            // the user forces a non-auto mode).
            if ((prior.customBaseURL != instance.customBaseURL ||
                    prior.appendV1Suffix != instance.appendV1Suffix) &&
                instance.imageEndpointResolved != null
            ) {
                instance.imageEndpointResolved = null
            }
            config.instances[idx] = instance
            saveConfig(config)
            // Any change that could move the model list (base URL, credential
            // swap, enabled flag, API format) invalidates the cache. Cheap to
            // over-invalidate.
            if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
                prior.credentialType != instance.credentialType ||
                prior.isEnabled != instance.isEnabled ||
                prior.useResponsesAPI != instance.useResponsesAPI
            ) {
                invalidateModelCache(instance.id)
            }
        }
    }

    fun removeInstance(instanceId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        invalidateModelCache(instanceId)
        val config = workingCopy()
        val removedEntryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .map { it.id }
            .toSet()
        config.instances.removeAll { it.id == instanceId }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }

        if (removedEntryIds.isNotEmpty()) {
            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in removedEntryIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
        }
        val emptyGroupIds = config.modelGroups.filter { it.memberEntryIds.isEmpty() }.map { it.id }.toSet()
        if (emptyGroupIds.isNotEmpty()) {
            config.modelGroups.removeAll { it.id in emptyGroupIds }
            if (config.defaultPrimaryGroupId in emptyGroupIds) config.defaultPrimaryGroupId = null
            if (config.defaultSubGroupId in emptyGroupIds) config.defaultSubGroupId = null
        }

        saveConfig(config)
        deleteApiKey(instanceId)
        // [T-android-thinking-rules-phase2] The instance is gone — drop its custom
        // rules from Room and the resolver cache (they can never fire again).
        runCatching {
            runBlocking { providerDao.deleteThinkingRulesForInstance(instanceId) }
            ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
        }
    }

    /**
     * [T-android-image-endpoint-mode] Persist the endpoint that last worked for
     * `auto`-mode image generation on [instanceId]. Called by
     * ModelUseOffloadHandler after a successful /images/generations call (cache
     * `imagesGenerations`) or after a route-missing fallback (cache
     * `chatCompletions`). No-op when unchanged so we don't churn the config /
     * iCloud sync on every image call. Does not invalidate the model cache —
     * the endpoint choice never moves the model list.
     */
    // [T-android-provider-mutator-lock] `ProviderInstance` has mutable `var`
    // fields, so `workingCopy()`'s list copy is not enough on its own — the
    // instance objects inside it are still the published ones. Replace the
    // element with a `.copy()` rather than writing through to the shared
    // object. Called from ModelUseOffloadHandler on an offload worker thread
    // during image generation, so the unsynchronized write had no
    // happens-before with main-thread readers.
    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val idx = config.instances.indexOfFirst { it.id == instanceId }
            if (idx < 0) return@synchronized
            if (config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
            config.instances[idx] = config.instances[idx].copy(imageEndpointResolved = endpoint)
            saveConfig(config)
        }

    fun instance(id: String): ProviderInstance? =
        _config.value.instances.find { it.id == id }

    fun enabledInstances(providerType: ProviderType): List<ProviderInstance> =
        _config.value.instances.filter { it.providerType == providerType && it.isEnabled }

    fun entriesFor(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .sortedWith(releaseRankOrder)

    fun visibleEntries(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId && !it.isHidden }
            .sortedWith(releaseRankOrder)

    fun allVisibleEntries(): List<ModelEntry> =
        _config.value.let { config ->
            val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
            config.modelEntries
                .filter { it.providerInstanceId in enabledIds && !it.isHidden }
                .sortedWith(releaseRankOrder)
        }

    /**
     * [T-model-release-ranking] Newest / most capable model first, so a picker
     * never opens on a stale (or, as in OpenMinis#83, an uncallable) model.
     * These lists previously came back in raw config order, which is insertion
     * order from the provider's /models response — effectively arbitrary.
     * Falls back to the model id so the ordering is total and stable when two
     * entries rank identically; otherwise the list could visibly reshuffle
     * between reads. Mirrors iOS `ProviderConfigStore.releaseRankOrder`.
     */
    private val releaseRankOrder = Comparator<ModelEntry> { a, b ->
        val ra = ModelReleaseIndex.rank(
            a.baseModel.id, a.baseModel.displayName, a.baseModel.contextWindow
        )
        val rb = ModelReleaseIndex.rank(
            b.baseModel.id, b.baseModel.displayName, b.baseModel.contextWindow
        )
        val byRank = ModelReleaseIndex.comparator.compare(ra, rb)
        if (byRank != 0) byRank else a.baseModel.id.compareTo(b.baseModel.id)
    }

    // ── [T-newchat-default-model-fallback-android] last-used + newest-model ──

    /**
     * The model entry id the user last actively selected (model picker tap) or
     * sent a message with. Persisted globally (not per-session) so a brand-new
     * chat can fall back to "the model I was just using" when no default group
     * is configured. Null until the user has picked / used a model at least
     * once. Mirrors iOS #636 lastUsedModelEntryId.
     */
    var lastUsedEntryId: String?
        get() = prefs.getString(KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_USED_ENTRY) else putString(KEY_LAST_USED_ENTRY, value)
            }.apply()
        }

    /**
     * Resolve [lastUsedEntryId] to a still-valid, visible, enabled-provider
     * entry — or null when the recorded id was deleted / hidden / its provider
     * disabled. Callers treat null as "fall through to the next tier".
     */
    fun lastUsedVisibleEntry(): ModelEntry? {
        val id = lastUsedEntryId ?: return null
        return allVisibleEntries().firstOrNull { it.id == id }
    }

    /**
     * [T-newchat-default-model-fallback-android] Final-tier default for a new
     * chat: the newest text-output model from the newest-added enabled
     * provider. "Newest provider" = max [ProviderInstance.createdAt]; "newest
     * model" = last text-capable entry in add order (modelEntries is appended
     * in add order, so the instance's last matching entry is the most recently
     * added). Image/audio-only models are excluded — a fresh chat must default
     * to something that can produce a text reply.
     *
     * Walks providers newest→oldest so that if the newest provider somehow has
     * no text model (all image/audio), we still land on the newest text model
     * from the next provider rather than returning null.
     */
    fun newestProviderNewestTextEntry(): ModelEntry? {
        val config = _config.value
        val enabledProviders = config.instances
            .filter { it.isEnabled }
            .sortedByDescending { it.createdAt }
        for (instance in enabledProviders) {
            val textEntry = config.modelEntries
                .filter { it.providerInstanceId == instance.id && !it.isHidden && it.model.isTextOutput }
                .lastOrNull()
            if (textEntry != null) return textEntry
        }
        return null
    }

    /**
     * [T-disabled-provider-via-group-android] Members of [group] whose
     * provider instance is currently enabled. Used everywhere we previously
     * read `group.memberEntryIds` directly to resolve / display a model —
     * runtime selection must skip disabled providers, and the settings UI
     * uses this to surface "no available models" when every member sits
     * behind a disabled provider.
     *
     * Returns entries in the same order as [ModelGroup.memberEntryIds] so
     * the routing-strategy semantics (primary = first member) carry over.
     * Entries whose [ModelEntry.providerInstanceId] no longer maps to any
     * instance are also filtered — orphaned ids would otherwise pin the
     * group to a model that can't be instantiated.
     */
    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId }
            if (entry != null && entry.providerInstanceId in enabledIds) entry else null
        }
    }

    /** Convenience: first enabled member of [group] in declaration order. */
    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

    /**
     * [T-android-group-resolve-skip-uncredentialed] Whether [instance] has ANY
     * usable credential — API key, manual bearer, or a stored OAuth token.
     *
     * Mirrors iOS `ProviderInstance.hasAnyCredential`. The distinction that
     * matters here is OAuth: [usableApiKey] only reads `apikey_<id>`, so an
     * instance that is logged in via OAuth (Claude Code / Codex login) reads
     * as "no credential" to every `?: return`/`?: continue` call site even
     * though the chat path authenticates with it perfectly well. Routing must
     * not skip a provider the user is actually signed into.
     *
     * Deliberately synchronous and allocation-light: this runs inside model
     * selection on the main thread. It only probes credential STORAGE — it
     * never refreshes a token or performs I/O beyond an EncryptedSharedPrefs
     * read, so an expired-but-present token still counts as credentialed and
     * the refresh happens later on the request path (same as iOS).
     */
    fun hasAnyCredential(instance: ProviderInstance): Boolean {
        // API-key path, including the keyless-by-design compat endpoints that
        // [usableApiKey] already models via allowsEmptyAPIKey.
        if (usableApiKey(instance) != null) return true
        // OAuth path — token storage is keyed by instance id in the shared
        // oauth prefs, so this needs no OAuthManager instance. Reading the
        // prefs directly also covers provider types that
        // OAuthManager.forInstance deliberately omits (notably gemini),
        // which would otherwise be misreported as uncredentialed.
        return com.openminis.app.auth.OAuthManager.hasStoredCredential(context, instance.id)
    }

    /**
     * [T-android-group-resolve-skip-uncredentialed] Members of [group] that are
     * actually usable RIGHT NOW: not hidden, provider enabled, and provider
     * holding a credential.
     *
     * This is the Android counterpart of iOS `ModelGroupRouter.availableEntryIds`
     * and exists because [enabledMemberEntries] answers a narrower question
     * ("is the provider switched on"), which is the right filter for the
     * settings UI but too weak for routing: a member whose provider has no
     * credential cannot serve a request, so selecting it only produces an
     * immediate auth failure.
     *
     * Order is preserved from [ModelGroup.memberEntryIds] so "primary = first
     * member" still holds — the filter removes candidates, it never reorders.
     */
    fun availableMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId } ?: return@mapNotNull null
            if (entry.isHidden) return@mapNotNull null
            val instance = config.instances.find { it.id == entry.providerInstanceId }
                ?: return@mapNotNull null
            if (!instance.isEnabled) return@mapNotNull null
            if (!hasAnyCredential(instance)) return@mapNotNull null
            entry
        }
    }

    /**
     * [T-android-regenerate-title-submodel] The dedicated title-generation
     * sub-model entry: the first enabled member of the configured
     * `defaultSubGroupId` group. Returns null when no sub-group is configured or
     * every member sits behind a disabled provider (caller then falls back to
     * the primary model). Single source of truth for both the auto-title path
     * (ChatViewModel.resolveTitleProvider) and the manual Regenerate path
     * (SessionListViewModel.regenerateTitle), mirroring iOS resolveSubEntry.
     */
    fun resolveTitleSubEntry(): ModelEntry? {
        val subGroupId = defaultSubGroupId ?: return null
        val group = group(subGroupId) ?: return null
        // [T-android-group-resolve-skip-uncredentialed] Routing decision, so it
        // needs the credential-aware filter: firstEnabledMemberEntry would hand
        // back a member whose provider has no credential and title generation
        // would fail on it instead of using the next usable member.
        return availableMemberEntries(group).firstOrNull()
    }

    /**
     * [T-disabled-provider-via-group-android] True when [entryId] resolves
     * to an entry whose provider instance is currently enabled. Used by the
     * settings UI to dim members that won't be reachable at runtime.
     */
    fun isEntryProviderEnabled(entryId: String): Boolean {
        val config = _config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return false
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
        return instance.isEnabled
    }

    fun replaceEntries(instanceId: String, models: List<LLMModel>) = synchronized(configLock) {
        ensureConfigLoaded()
        // Hot path for concurrent autoRefreshModels coroutines (one per
        // enabled instance) — without this lock, two replaceEntries() calls
        // race on the shared config.modelEntries ArrayList. The working copy
        // additionally keeps this refresh off the list Compose is iterating.
        val config = workingCopy()
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val existingEntryIds = existing.map { it.id }.toSet()

        // Build lookup: baseModel.id → existing entry (prefer non-custom if duplicates exist)
        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existing) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        // Build new entries, carrying forward uuid / overrides / isHidden from prior entries
        val refreshedModelIds = models.map { it.id }.toSet()
        // [T-android-provider-voice] Template-sourced voice models carry an
        // authoritative modality that API-inferred modality (typically no audio
        // bits) must never overwrite. Mirrors iOS replaceEntries
        // templateModalityById guard (d55cd821).
        val instanceBaseURL = config.instances.firstOrNull { it.id == instanceId }?.customBaseURL
        val voiceTemplate = VoiceProviderTemplate.template(instanceBaseURL)
        val templateVoiceModelById = voiceTemplate?.mockModels
            ?.filter { it.hasVoiceModality }
            ?.associateBy { it.id }
            ?: emptyMap()
        val newEntries = models.map { model ->
            val prior = existingByModelId[model.id]
            // Dedicated ASR/TTS id/name patterns fill the exact voice shape when
            // the API returned no modality info; the template's shape wins last.
            var resolved = model.withInferredVoiceModality()
            templateVoiceModelById[model.id]?.let { tplModel ->
                resolved = resolved.copy(
                    inputModalities = tplModel.inputModalities,
                    outputModalities = tplModel.outputModalities,
                )
            }
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = resolved,
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
            )
        }

        // Keep custom entries that weren't in the refreshed list
        val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }

        // [T-android-provider-voice] Preserve voice-template SEED entries the
        // /models list didn't return. Vendors like MiMo never expose their
        // ASR/TTS voices via /v1/models, so without this every refresh wipes
        // the seeds (iOS regression cffbec0e: MiMo launch model count 15→11).
        // Only genuine template members are protected — never stray audio
        // entries. Mirrors iOS replaceEntries preservedVoice.
        val preservedVoice = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id in templateVoiceModelById
        }

        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        config.modelEntries.addAll(newEntries)
        config.modelEntries.addAll(remainingCustom)
        if (preservedVoice.isNotEmpty()) {
            config.modelEntries.addAll(preservedVoice)
            android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries preserved ${preservedVoice.size} voice-template seed entries: ${preservedVoice.map { it.baseModel.id }.take(10)}")
        }

        // Prune stale group member references
        val survivingEntryIds = config.modelEntries.map { it.id }.toSet()
        val prunedEntryIds = existingEntryIds - survivingEntryIds
        if (prunedEntryIds.isNotEmpty()) {
            val suspiciousShrink = existing.size >= 4 && models.size * 2 < existing.size
            if (suspiciousShrink) {
                android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK before=${existing.size} after=${models.size} — group references PRESERVED as stale")
            } else {
                for (i in config.modelGroups.indices) {
                    val before = config.modelGroups[i].memberEntryIds.size
                    config.modelGroups[i].memberEntryIds.removeAll { it in prunedEntryIds }
                    val removed = before - config.modelGroups[i].memberEntryIds.size
                    if (removed > 0) {
                        android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $removed stale refs from group '${config.modelGroups[i].name}'")
                    }
                }
                // T171: same cascade for agent-loop direct-entry pins —
                // mirrors iOS ProviderConfigStore.replaceEntries (L716).
                // Skipped under the suspicious-shrink branch alongside the
                // group-member preservation, so a transient API hiccup
                // never silently nukes the user's curated agent-loop set.
                val agentBefore = config.agentLoopModelEntryIds.size
                config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
                val agentRemoved = agentBefore - config.agentLoopModelEntryIds.size
                if (agentRemoved > 0) {
                    android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $agentRemoved stale agent-loop entry pins")
                }
            }
        }

        saveConfig(config)
        // Stamp so staleness checks know this instance just refreshed.
        markInstanceFetched(instanceId)
    }

    // --- Model Entry management ---
    //
    // [T-android-provider-mutator-lock] Every read-modify-write mutator below
    // holds configLock for its WHOLE body, not just the saveConfig at the end.
    //
    // These mutate `_config.value`'s inner MutableLists IN PLACE — the very
    // lists already handed to collectors (the pickers filter over
    // config.modelEntries on the main thread). Mutating them from an IO thread
    // while a composition iterates throws ConcurrentModificationException in
    // the READER. 5399fe270 removed one such walk (ProviderConfig.equals inside
    // StateFlow emission) but left every consumer exposed; the shared mutable
    // list is the actual defect, and the lock is what serialises it against
    // saveConfig's defensive copy (which only snapshots AFTER the mutation).
    // configLock is a plain JVM monitor, so the nested saveConfig re-entry is
    // fine — replaceEntries / ensureVoiceTemplateModels already rely on that.

    fun addEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        if (!entry.isCustom) {
            val exists = config.modelEntries.any {
                it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
            }
            // Explicit label: a bare `return` in an expression body reads as if
            // it might only leave the synchronized lambda.
            if (exists) return@addEntry
        }
        config.modelEntries.add(entry)
        saveConfig(config)
    }

    fun updateEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            config.modelEntries[idx] = entry.copy(userModifiedAt = System.currentTimeMillis())
            saveConfig(config)
        }
    }

    fun removeEntry(entryId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelEntries.removeAll { it.id == entryId }
        config.modelGroups.forEach { group ->
            group.memberEntryIds.removeAll { it == entryId }
        }
        // T171: cascade-clean the agent-loop direct-entry pin so the
        // AgentLoopModelsScreen never surfaces a checkmark on a model that
        // no longer exists. Mirrors iOS ProviderConfigStore.removeEntry
        // (Providers/ProviderConfigStore.swift L268).
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        saveConfig(config)
    }

    // --- Model Group management ---

    fun addGroup(group: ModelGroup): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelGroups.add(group)
        saveConfig(config)
    }

    fun updateGroup(group: ModelGroup): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelGroups.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            // Defensive re-wrap: callers routinely pass `published.copy(...)`,
            // and data-class copy() shares memberEntryIds BY REFERENCE with the
            // published group. Storing that object would put published inner
            // state back inside a working copy, quietly undoing the invariant.
            config.modelGroups[idx] = group.copy(
                memberEntryIds = group.memberEntryIds.toMutableList(),
            )
            saveConfig(config)
        }
    }

    fun removeGroup(groupId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.modelGroups.removeAll { it.id == groupId }
        if (config.defaultPrimaryGroupId == groupId) {
            config.defaultPrimaryGroupId = null
        }
        if (config.defaultSubGroupId == groupId) {
            config.defaultSubGroupId = null
        }
        if (config.voiceInputGroupId == groupId) {
            config.voiceInputGroupId = null
        }
        if (config.voiceOutputGroupId == groupId) {
            config.voiceOutputGroupId = null
        }
        if (config.visionGroupId == groupId) {
            config.visionGroupId = null
        }
        config.agentLoopGroupIds.removeAll { it == groupId }
        saveConfig(config)
    }

    /** Set the agent-loop-visible entry ID list (individual model entries). */
    fun setAgentLoopEntryIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopModelEntryIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (preserving
        // first-seen order). addAgentLoopEntry guards against dups, but any
        // path writing straight through this sink (cross-device sync merge,
        // reorder write-back, data migration) could otherwise persist a
        // duplicate id — which surfaces downstream as two pinnedEntries with
        // the same id, a duplicate LazyColumn key, and a crash on scroll.
        config.agentLoopModelEntryIds.addAll(ids.distinct())
        saveConfig(config)
    }

    /** Set the agent-loop-visible group ID list. */
    fun setAgentLoopGroupIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopGroupIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (see above) so
        // no write path can persist duplicate group ids → duplicate LazyColumn
        // key on the agent-loop groups list.
        config.agentLoopGroupIds.addAll(ids.distinct())
        saveConfig(config)
    }

    // T182: thin add/remove helpers used by AgentLoopModelsSection +
    // AddAgentLoopModelsSheet / AddAgentLoopGroupsSheet. These wrap the
    // set* functions so caller doesn't have to round-trip through the
    // existing list (mutating in place would race with a parallel
    // saveConfig). Keep insertion order — preserves the order the user
    // added items in the picker, mirrors iOS appendIfNeeded behaviour.

    /** Append [entryId] to the agent-loop direct-pin list if not already there. */
    fun addAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId in cur) return
        setAgentLoopEntryIds(cur + entryId)
    }

    /** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
    fun removeAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId !in cur) return
        setAgentLoopEntryIds(cur.filterNot { it == entryId })
    }

    /** Append [groupId] to the agent-loop group-pin list if not already there. */
    fun addAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId in cur) return
        setAgentLoopGroupIds(cur + groupId)
    }

    /** Remove [groupId] from the agent-loop group-pin list. No-op if absent. */
    fun removeAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId !in cur) return
        setAgentLoopGroupIds(cur.filterNot { it == groupId })
    }

    // T186: reorder helpers — UI keeps a local mutable copy during
    // drag and pushes the final order through here on drop. Validates
    // that [newOrder] is a permutation of the current list to defend
    // against a stale dragged-from-different-snapshot reorder slipping
    // in mid-write (e.g. a cascade-cleanup raced with the drag).
    fun reorderAgentLoopEntries(newOrder: List<String>) {
        val cur = _config.value.agentLoopModelEntryIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopEntryIds(newOrder)
    }

    fun reorderAgentLoopGroups(newOrder: List<String>) {
        val cur = _config.value.agentLoopGroupIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopGroupIds(newOrder)
    }

    /**
     * [T-android-provider-reorder] Reorder provider instances (drag-to-sort in
     * the Providers list). Mirrors iOS `ProviderConfigStore.reorderInstances`.
     *
     * [newOrder] is a list of instance ids. Unknown ids are dropped and any
     * instance missing from it is appended in its existing relative order, so a
     * caller that only knows about ONE provider-type section can pass just that
     * section's ids and leave the rest untouched — which is exactly what the
     * per-section drag in ProviderListScreen does.
     *
     * Persistence: `sort_order` is derived from list position at save time
     * (ProviderConfigMapping writes `sortOrder = idx`) and the DAO reads back
     * `ORDER BY sort_order ASC`, so permuting the list IS the persistence — no
     * per-row column write is needed.
     *
     * Unlike iOS there is no dirty-marking step here: Android keeps provider
     * config local-only (Room + the JSON mirror), with no CloudKit upload, so
     * the "pure reorder doesn't mark dirty" bug iOS had to fix has no analogue.
     * `saveConfig` bumps `revision`, which is the Android-side equivalent
     * concern — it guarantees the StateFlow re-emits even though a pure
     * permutation compares equal under data-class structural equality.
     */
    fun reorderInstances(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.instances.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ProviderInstance>(current.size)
        for (id in newOrder) {
            val inst = byId[id] ?: continue      // drop unknown ids
            if (!seen.add(id)) continue          // drop duplicates
            reordered.add(inst)
        }
        // Anything the caller didn't mention keeps its existing relative order.
        for (inst in current) {
            if (seen.add(inst.id)) reordered.add(inst)
        }

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        // [T-android-reorder-unlocked-mutation] Mutate under configLock.
        //
        // `config.instances` is the shared MutableList inside _config.value,
        // and clear()/addAll() ran unprotected here — exactly the window the
        // configLock KDoc above was written to close (it cites the
        // "ConcurrentModificationException → ArrayList.next" crash seen on
        // Pixel 6 / 4a). Concurrent readers iterate that same list with no
        // lock: hasFoldedShadowDuplicates() and shadowVoiceProviders() are
        // called from ProviderListScreen during composition — on the main
        // thread, and recomposition fires precisely BECAUSE this reorder just
        // emitted — while the background model-refresh fan-out reads it too.
        //
        // The empty window was the worse half: a saveConfig snapshotting
        // between clear() and addAll() would persist ZERO instances to Room
        // and the JSON mirror, wiping the user's providers.
        //
        // synchronized is reentrant, so the nested saveConfig (which takes the
        // same lock) is fine. Matches ensureVoiceTemplateModels / replaceEntries.
        synchronized(configLock) {
            config.instances.clear()
            config.instances.addAll(reordered)
            saveConfig(config)
        }
    }

    /**
     * [T-android-modelgroup-reorder] Reorder the user's Model Groups
     * (drag-to-sort in the Model Groups screen). Mirrors iOS
     * `ProviderConfigStore.reorderGroups` (4ba54ff5) and follows the exact
     * contract of [reorderInstances] above: unknown ids dropped, duplicates
     * collapsed, unmentioned groups appended in their existing relative order,
     * no-op guard, and the list mutation held under [configLock] — same
     * CME/empty-window rationale as documented on reorderInstances
     * ([T-android-reorder-unlocked-mutation]).
     *
     * Persistence needs nothing extra: ProviderConfigMapping writes each
     * group's `sort_order` from its list index at save time and the DAO reads
     * `ORDER BY sort_order ASC`, so list position IS the stored order.
     */
    fun reorderModelGroups(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.modelGroups.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ModelGroup>(current.size)
        for (id in newOrder) {
            val group = byId[id] ?: continue     // drop unknown ids
            if (!seen.add(id)) continue          // drop duplicates
            reordered.add(group)
        }
        // Anything the caller didn't mention keeps its existing relative order.
        for (group in current) {
            if (seen.add(group.id)) reordered.add(group)
        }

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        synchronized(configLock) {
            config.modelGroups.clear()
            config.modelGroups.addAll(reordered)
            saveConfig(config)
        }
    }

    /**
     * Resolve the effective model entries visible to the agent loop (minis-model-use).
     * Expands groups to their members, unions with individual entries, dedupes by ID,
     * and filters to entries of enabled provider instances. Mirrors iOS
     * ProviderConfigStore.resolvedAgentLoopEntries.
     */
    fun resolvedAgentLoopEntries(): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ModelEntry>()

        fun consider(entry: ModelEntry) {
            if (entry.providerInstanceId !in enabledIds) return
            if (!seen.add(entry.id)) return
            out.add(entry)
        }

        // Individual entries first (preserves the order the user arranged in UI)
        for (id in config.agentLoopModelEntryIds) {
            config.modelEntries.find { it.id == id }?.let(::consider)
        }
        // Then group members, in group order
        for (gid in config.agentLoopGroupIds) {
            val group = config.modelGroups.find { it.id == gid } ?: continue
            for (memberId in group.memberEntryIds) {
                config.modelEntries.find { it.id == memberId }?.let(::consider)
            }
        }
        return out
    }

    fun group(id: String): ModelGroup? =
        _config.value.modelGroups.find { it.id == id }

    var defaultPrimaryGroupId: String?
        get() = _config.value.defaultPrimaryGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultPrimaryGroupId = value
            saveConfig(config)
        }

    var defaultSubGroupId: String?
        get() = _config.value.defaultSubGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultSubGroupId = value
            saveConfig(config)
        }

    // --- Voice groups [T-android-provider-voice] ---

    var voiceInputGroupId: String?
        get() = _config.value.voiceInputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.voiceInputGroupId = value
            saveConfig(config)
        }

    var voiceOutputGroupId: String?
        get() = _config.value.voiceOutputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.voiceOutputGroupId = value
            saveConfig(config)
        }

    // --- Vision group [T-android-vision-group / GH#182] ---

    var visionGroupId: String?
        get() = _config.value.visionGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.visionGroupId = value
            saveConfig(config)
        }

    /** True when a Vision Group is bound AND still exists. Gates read_image
     *  tool exposure for main models that cannot natively see images. */
    fun hasVisionGroupConfigured(): Boolean {
        val gid = _config.value.visionGroupId ?: return false
        return _config.value.modelGroups.any { it.id == gid }
    }

    /** Bound Vision group's display name, or null. */
    fun visionGroupName(): String? {
        val gid = _config.value.visionGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * [T-android-vision-group] Ordered vision-capable fail-over candidates from
     * the bound Vision Group. Mirrors resolveVoiceInputCandidates: filters
     * members to enabled instances whose model declares image input, honours
     * the group's routing strategy (`fallback` keeps order; `loadBalance`
     * rotates the start by [loadBalanceSeed] so separate reads spread across
     * members). Returns [] when no group is bound or no member is usable — the
     * caller (ReadImageTool) then returns a clear failure text.
     */
    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasImageInput) return null
            return inst to entry
        }

        val gid = config.visionGroupId ?: return emptyList()
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()
        var members = group.memberEntryIds.mapNotNull { providerEntry(it) }
        if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
            val offset = kotlin.math.abs(loadBalanceSeed) % members.size
            members = members.drop(offset) + members.take(offset)
        }
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (m in members) {
            if (out.none { it.second.id == m.second.id }) out.add(m)
        }
        return out
    }

    // --- Thinking rules (custom) [T-android-thinking-rules-phase2] ---
    //
    // User-authored rules live in provider.db (provider_thinking_rules), keyed by
    // provider-instance id, in sort_order priority order. Built-in vendor rules are
    // never stored. On every mutation we publish the instance's rules into the
    // ThinkingRuleResolver cache so the (sync) request-builder can read them.

    /** Load one instance's custom rules from Room, in stored order. */
    fun thinkingRules(instanceId: String): List<ThinkingRule> = runBlocking {
        runCatching { providerDao.loadThinkingRules(instanceId).map { ThinkingRuleCoding.toRule(it) } }
            .getOrDefault(emptyList())
    }

    /** The persisted ids for one instance's custom rules, parallel to [thinkingRules]. */
    fun thinkingRuleIds(instanceId: String): List<String> = runBlocking {
        runCatching { providerDao.loadThinkingRules(instanceId).map { it.id } }.getOrDefault(emptyList())
    }

    /** First model id served by [instanceId], for the resolution-trace sample. Null if none. */
    fun firstModelId(instanceId: String): String? {
        ensureConfigLoaded()
        return _config.value.modelEntries.firstOrNull { it.providerInstanceId == instanceId }?.model?.id
    }

    /** Warm the resolver cache with every instance's custom rules (called on config load). */
    fun loadAllThinkingRulesIntoCache() {
        runBlocking { warmThinkingRuleCache() }
    }

    /**
     * [T-android-thinking-rules-cold-cache] Suspend twin of [loadAllThinkingRulesIntoCache]
     * for callers already inside a coroutine — notably the init loader, which must warm the
     * cache on the cold-start path it owns (nesting runBlocking there would block an IO
     * thread on another IO thread for no reason). Failure is swallowed exactly like the sync
     * wrapper's: an unreadable rules table degrades to built-in behaviour, never to a crash.
     */
    private suspend fun warmThinkingRuleCache() {
        runCatching {
            val rows = providerDao.loadAllThinkingRules()
            val byInstance = rows.groupBy { it.providerInstanceId }
                .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { ThinkingRuleCoding.toRule(it) } }
            ThinkingRuleResolver.setAllCustomRules(byInstance)
            android.util.Log.i(
                "ProviderRepo",
                "[ThinkingRules] resolver cache warmed: ${byInstance.values.sumOf { it.size }} " +
                    "custom rule(s) across ${byInstance.size} instance(s)",
            )
        }.onFailure { e ->
            android.util.Log.w(
                "ProviderRepo",
                "[ThinkingRules] cache warm failed; resolving with built-ins only: ${e.message}",
            )
        }
    }

    private fun republishThinkingCache(instanceId: String) {
        ThinkingRuleResolver.setCustomRules(instanceId, thinkingRules(instanceId))
    }

    /**
     * Insert or update a custom rule. [id] null ⇒ new rule minted at the TOP of the
     * list (position 0) — a rule overriding a built-in is useless below it; existing
     * rules shift down. A non-null [id] updates in place, preserving position.
     * Returns the rule id.
     */
    fun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String = runBlocking {
        val existing = providerDao.loadThinkingRules(instanceId).toMutableList()
        val ruleId = id ?: java.util.UUID.randomUUID().toString()
        val idx = existing.indexOfFirst { it.id == ruleId }
        if (idx >= 0) {
            // Update in place at its current sort_order.
            existing[idx] = ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, existing[idx].sortOrder)
        } else {
            // New rule at the top; everything else shifts down.
            existing.add(0, ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, 0))
        }
        val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }
        providerDao.replaceThinkingRules(instanceId, renumbered)
        republishThinkingCache(instanceId)
        ruleId
    }

    /** Delete a custom rule by id. Hard delete — Android provider config is local-only,
     *  so there is no sync channel that could resurrect it (no tombstone needed). */
    fun deleteThinkingRule(instanceId: String, id: String) = runBlocking {
        providerDao.deleteThinkingRule(id)
        // Renumber survivors so sort_order stays dense.
        val survivors = providerDao.loadThinkingRules(instanceId)
            .sortedBy { it.sortOrder }
            .mapIndexed { i, e -> e.copy(sortOrder = i) }
        providerDao.replaceThinkingRules(instanceId, survivors)
        republishThinkingCache(instanceId)
    }

    /** Reorder an instance's custom rules to match [orderedIds] (a permutation). */
    fun reorderThinkingRules(instanceId: String, orderedIds: List<String>) = runBlocking {
        val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }
        val reordered = orderedIds.mapNotNull { byId[it] }
            .mapIndexed { i, e -> e.copy(sortOrder = i) }
        // Keep any id the caller omitted (defensive against a partial list) appended.
        val omitted = byId.values.filter { it.id !in orderedIds }.map { it }
        providerDao.replaceThinkingRules(instanceId, reordered + omitted)
        republishThinkingCache(instanceId)
    }

    /**
     * Built-in rules relevant to THIS instance, for the Provider-detail UI. Mirrors iOS
     * builtInRulesForDisplay: resolve the vendor context from the instance's base URL,
     * then keep every AllModels-scoped rule (endpoint/provider-type defaults) plus any
     * ModelPattern rule the provider actually serves a matching model for. An empty
     * catalog keeps everything (list must not be mysteriously empty before first fetch).
     */
    fun builtInThinkingRulesForDisplay(instanceId: String): List<ThinkingRule> {
        ensureConfigLoaded()
        val config = _config.value
        val inst = config.instances.find { it.id == instanceId } ?: return emptyList()
        val base = (inst.effectiveBaseURL ?: "").lowercase()
        val ctx = com.openminis.app.provider.thinking.ThinkingResolveContext(
            modelId = "",
            supportsReasoning = null,
            declaredEffortValues = null,
            level = com.openminis.app.data.model.ThinkingLevel.OFF,
            maxTokens = 0,
            isOpenRouter = base.contains("openrouter.ai"),
            usesUnifiedReasoningEffort = base.contains("volces") || base.contains("ark.") || base.contains("venice.ai"),
            isMistral = base.contains("mistral.ai"),
            isDashScope = base.contains("dashscope"),
            offEffort = null,
        )
        val modelIds = config.modelEntries.filter { it.providerInstanceId == instanceId }.map { it.model.id }
        return ThinkingRuleResolver.builtInRules(ctx).filter { rule ->
            when (rule.scope) {
                is ThinkingRule.Scope.AllModels -> true
                is ThinkingRule.Scope.ModelPattern ->
                    modelIds.isEmpty() || modelIds.any { rule.scope.matches(it) }
            }
        }
    }

    /**
     * Ensure a default Voice INPUT group exists and is bound when the user
     * hasn't configured one. Seeds "Voice Input" with the System ASR sentinel
     * entries (device SpeechRecognizer online/offline). Sentinel composite ids
     * match iOS so a config moved cross-platform keeps its selection. No-op
     * when a valid binding already exists. Mirrors iOS
     * ProviderConfigStore.ensureDefaultVoiceInputGroup.
     */
    fun ensureDefaultVoiceInputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.voiceInputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Input",
            memberEntryIds = mutableListOf(
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
            ),
        )
        config.modelGroups.add(group)
        config.voiceInputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Input group ${group.id.take(8)} [System ASR online+offline]")
        return group.id
    }

    /**
     * Ensure a default Voice OUTPUT group exists and is bound. Seeds
     * "Voice Output" with the System TTS auto sentinel (device TextToSpeech,
     * best voice per reply language). Mirrors iOS ensureDefaultVoiceOutputGroup.
     */
    fun ensureDefaultVoiceOutputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.voiceOutputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Output",
            memberEntryIds = mutableListOf("$sentinel/${SystemVoiceIds.SYSTEM_TTS}"),
        )
        config.modelGroups.add(group)
        config.voiceOutputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Output group ${group.id.take(8)} [System Voice (Auto)]")
        return group.id
    }

    /**
     * [T-android-voice-panel] Explicit voice-input engine override picked in
     * the panel's model selector (mirrors iOS VoiceSelectionStore.inputEntryId).
     * Values: a System sentinel composite id ("<sentinel>/system-asr-online" /
     * "-offline"), a provider entry composite id, or null = follow the Voice
     * Input group binding. Per-device (prefs), not part of the synced config.
     */
    var voiceInputOverrideEntryId: String?
        get() = prefs.getString("voice.input.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.input.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * The resolved ACTIVE voice-input choice for the panel: either the on-device
     * System engine (with an online/offline preference) or a provider entry.
     * Resolution order mirrors iOS VoiceProviderResolver: explicit override
     * first, then the Voice Input group's members in fallback order (a System
     * sentinel member selects the System engine), then the System default.
     */
    data class VoiceInputChoice(
        /** null → provider-backed; non-null → System engine (true = prefer offline). */
        val systemPreferOffline: Boolean?,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    fun resolveVoiceInputChoice(): VoiceInputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun systemChoice(memberId: String): VoiceInputChoice = VoiceInputChoice(
            systemPreferOffline = memberId.endsWith("/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"),
            entry = null,
        )

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasAudioInput) return null
            return inst to entry
        }

        voiceInputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(override)
            providerEntry(override)?.let { return VoiceInputChoice(null, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(memberId)
                providerEntry(memberId)?.let { return VoiceInputChoice(null, it) }
            }
        }
        return VoiceInputChoice(systemPreferOffline = null, entry = null)
    }

    /** Bound Voice Input group's display name, or null (chip's "Group · Model"). */
    fun voiceInputGroupName(): String? {
        val gid = _config.value.voiceInputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * [T-android-provider-voice] Resolve the current provider-backed voice
     * INPUT selection: honours the explicit override first, then walks the
     * Voice Input group's members in order (fallback semantics). System
     * sentinel members are skipped — they are served by
     * SystemSpeechRecognitionEngine, not a cloud provider.
     */
    fun resolveVoiceInputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceInputChoice().entry

    /**
     * [T-voice-asr-group-failover] Ordered ASR fail-over candidates: the
     * explicit override first (if usable and provider-backed), then every
     * usable member of the Voice Input group, ordered per the group's routing
     * strategy — `fallback` keeps group order, `loadBalance` rotates the start
     * position by [loadBalanceSeed] so separate capture sessions spread across
     * members (mirrors ModelGroupRouter's per-session rotation for text chat,
     * and iOS VoiceProviderResolver.resolvedInputCandidates). An explicit
     * System override returns [] — the caller uses the System engine directly.
     * System sentinel group members are skipped: they never serve cloud ASR.
     */
    fun resolveVoiceInputCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasAudioInput) return null
            return inst to entry
        }

        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        voiceInputOverrideEntryId?.let { override ->
            // Explicit System override → System engine, no cloud candidates.
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return emptyList()
            providerEntry(override)?.let { out.add(it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            var members = group.memberEntryIds
                .filter { !it.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID) }
                .mapNotNull { providerEntry(it) }
            if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
                val offset = kotlin.math.abs(loadBalanceSeed) % members.size
                members = members.drop(offset) + members.take(offset)
            }
            for (m in members) {
                if (out.none { it.second.id == m.second.id }) out.add(m)
            }
        }
        return out
    }

    // --- Voice OUTPUT resolution [T-android-voice-output-resolver] ---
    //
    // Mirror of the voice-INPUT resolution above. Before this existed,
    // `voiceOutputGroupId` was write-only: the user could bind a Voice Output
    // group in settings and it was persisted, but nothing at runtime ever read
    // it back, so read-aloud always fell through to the on-device engine and
    // every provider TTS voice was unreachable outside the Quick Test sheet.

    /**
     * Explicit voice-output voice override picked in a selector (mirrors iOS
     * VoiceSelectionStore.outputEntryId, and [voiceInputOverrideEntryId] on the
     * input side). Values: a System sentinel composite id
     * ("<sentinel>/system-tts"), a provider entry composite id, or null =
     * follow the Voice Output group binding. Per-device (prefs), not part of
     * the synced config.
     */
    var voiceOutputOverrideEntryId: String?
        get() = prefs.getString("voice.output.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.output.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * The resolved ACTIVE voice-output choice for read-aloud: either the
     * on-device System TextToSpeech or a provider entry. Same shape and
     * resolution order as [VoiceInputChoice].
     */
    data class VoiceOutputChoice(
        /** null → provider-backed; true → System TextToSpeech. */
        val isSystemEngine: Boolean,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    /**
     * Resolution order mirrors [resolveVoiceInputChoice] exactly: explicit
     * override first, then the Voice Output group's members in fallback order
     * (a System sentinel member selects the device engine), then the System
     * default as the terminal fallback.
     *
     * The capability gate is `hasAudioOutput` (vs `hasAudioInput` on the input
     * side) — a model that only *accepts* audio must never be picked to
     * *produce* it.
     */
    fun resolveVoiceOutputChoice(): VoiceOutputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasAudioOutput) return null
            return inst to entry
        }

        voiceOutputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                return VoiceOutputChoice(isSystemEngine = true, entry = null)
            }
            providerEntry(override)?.let { return VoiceOutputChoice(false, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceOutputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                    return VoiceOutputChoice(isSystemEngine = true, entry = null)
                }
                providerEntry(memberId)?.let { return VoiceOutputChoice(false, it) }
            }
        }
        return VoiceOutputChoice(isSystemEngine = true, entry = null)
    }

    /**
     * [T-android-voice-picker-active] Which MEMBER of a bound voice group is
     * the one that would actually serve the next request — the id the picker
     * marks "Active" when no explicit override is set.
     *
     * Deliberately derived here rather than recomputed in the picker: this is
     * the same first-usable-member-in-group-order walk that
     * [resolveVoiceInputChoice] / [resolveVoiceOutputChoice] perform, and a
     * second copy in the UI would silently drift from the runtime rule the
     * moment either resolver's usability test changes. Returns null when no
     * member is usable (the group can serve nothing).
     *
     * Note this reports the group's *static* first choice. Under
     * `loadBalance`, or after a fail-over, the request may land on a later
     * member; the badge answers "which one leads", which is what the row's
     * fallback-strategy chip already promises.
     */
    fun activeVoiceGroupMemberId(output: Boolean): String? {
        ensureConfigLoaded()
        val config = _config.value
        val gid = if (output) config.voiceOutputGroupId else config.voiceInputGroupId
        val group = config.modelGroups.find { it.id == gid } ?: return null
        for (memberId in group.memberEntryIds) {
            // System sentinels are always usable — the on-device engine needs
            // no instance and is never disabled.
            if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return memberId
            val entry = config.modelEntries.find { it.id == memberId } ?: continue
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!inst.isEnabled) continue
            val usable = if (output) entry.model.hasAudioOutput else entry.model.hasAudioInput
            if (usable) return memberId
        }
        return null
    }

    /** Bound Voice Output group's display name, or null. */
    fun voiceOutputGroupName(): String? {
        val gid = _config.value.voiceOutputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * Resolve the current provider-backed voice OUTPUT selection, or null when
     * read-aloud should use the on-device engine. Counterpart of
     * [resolveVoiceInputEntry].
     */
    fun resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceOutputChoice().entry

    // --- Shadow Voice Providers [T-android-provider-voice] ---
    // Port of iOS [T-mimo-shadow-voice]: a "Voice Service" is NOT a stored
    // entity — it's a runtime read-only MIRROR of any ordinary instance that
    // owns audio-modality model entries (dual-purpose vendors like MiMo serve
    // text + voice on one host). Voice ability is a per-MODEL concern; no
    // base-URL "voice-only" whitelist.

    /** True if [instanceId] has ANY model entry with an audio modality. */
    fun hasVoiceModels(instanceId: String): Boolean =
        _config.value.modelEntries.any {
            it.providerInstanceId == instanceId && it.model.hasVoiceModality
        }

    /**
     * Per-instance "hide from Voice Services" flag ("I only want the text
     * models"). Absent = shadow shown by default. Stored in prefs (mirrors iOS
     * UserDefaults voiceShadowDisabled.<id>), not the synced config.
     */
    fun isVoiceShadowDisabled(instanceId: String): Boolean =
        prefs.getBoolean("voiceShadowDisabled.$instanceId", false)

    fun setVoiceShadowDisabled(instanceId: String, disabled: Boolean) {
        prefs.edit().putBoolean("voiceShadowDisabled.$instanceId", disabled).apply()
        // Bump revision so Compose collectors re-read the shadow list.
        // The read must be INSIDE the lock: reading _config.value outside it
        // and re-publishing that snapshot afterwards would silently revert any
        // save that landed in between (lost update). The other two
        // revision-bump sites already read inside their block.
        synchronized(configLock) {
            _config.value = _config.value.copy(revision = ProviderConfig.nextRevision())
        }
    }

    /**
     * A read-only mirror of an instance's voice capability, surfaced in Voice
     * Services. Shares the underlying instance's credential + endpoint.
     */
    data class ShadowVoiceProvider(
        val instanceId: String,
        val displayName: String,
        val inputModels: List<ModelEntry>,   // audio-in entries (ASR)
        val outputModels: List<ModelEntry>,  // audio-out entries (TTS)
    )

    /**
     * All shadow voice providers: one per enabled instance that has audio
     * models and isn't shadow-disabled, FOLDED by normalized base URL so two
     * instances on one host show a single deterministic representative row.
     */
    fun shadowVoiceProviders(): List<ShadowVoiceProvider> {
        ensureConfigLoaded()
        val config = _config.value
        // [T-android-reorder-unlocked-mutation] Snapshot first — same
        // main-thread composition reader as hasFoldedShadowDuplicates.
        val candidates = config.instances.toList().filter { inst ->
            inst.isEnabled && hasVoiceModels(inst.id) && !isVoiceShadowDisabled(inst.id) &&
                com.openminis.app.provider.voice.VoiceProviderFactory.supports(inst, loadApiKey(inst.id))
        }
        val byKey = candidates.groupBy { inst ->
            normalizedShadowKey(inst.customBaseURL).ifEmpty { "id:${inst.id}" }
        }

        fun mostRecentModified(instanceId: String): Long =
            config.modelEntries
                .filter { it.providerInstanceId == instanceId }
                .mapNotNull { it.userModifiedAt }
                .maxOrNull() ?: Long.MIN_VALUE

        return byKey.values.map { insts ->
            // Representative selection — deterministic across devices: enabled
            // first, then most-recently-modified entries, then oldest createdAt,
            // then id.
            val rep = insts.sortedWith(
                compareByDescending<ProviderInstance> { it.isEnabled }
                    .thenByDescending { mostRecentModified(it.id) }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            ).first()
            val entries = config.modelEntries.filter { it.providerInstanceId == rep.id }
            ShadowVoiceProvider(
                instanceId = rep.id,
                displayName = rep.label,
                inputModels = entries.filter { it.model.hasAudioInput },
                outputModels = entries.filter { it.model.hasAudioOutput },
            )
        }.sortedWith(compareBy({ it.displayName }, { it.instanceId }))
    }

    /**
     * True when ≥2 enabled instances share a normalized base URL AND have voice
     * models — the folded-duplicate case; UI shows a non-destructive hint.
     */
    fun hasFoldedShadowDuplicates(): Boolean {
        val seen = mutableSetOf<String>()
        // [T-android-reorder-unlocked-mutation] Snapshot before iterating.
        // This runs on the MAIN thread from ProviderListScreen's composition,
        // so taking configLock here would block the UI behind a DB write.
        // toList() copies under no contention and removes the CME risk
        // outright — the writer's structural edits can no longer be observed
        // mid-iteration.
        for (inst in _config.value.instances.toList()) {
            if (!inst.isEnabled || !hasVoiceModels(inst.id)) continue
            val key = normalizedShadowKey(inst.customBaseURL)
            if (key.isEmpty()) continue
            if (!seen.add(key)) return true
        }
        return false
    }


    suspend fun refreshModels(instance: ProviderInstance) {
        // [T-android-refresh-models-empty-key] usableApiKey, NOT loadApiKey.
        //
        // A self-hosted OpenAI/Anthropic-compatible endpoint (ollama, LM
        // Studio, LiteLLM, an unauthenticated internal gateway) legitimately
        // has no key, so nothing is stored and `loadApiKey` returns null. Step
        // 1 below is gated on `apiKey != null`, so refresh skipped the provider
        // call entirely and fell through to the models.dev lookup — which knows
        // nothing about a private host — leaving the user with a failure whose
        // message described the fallback, not the server they were pointing at.
        //
        // `usableApiKey` substitutes "" exactly where an empty key is a valid
        // configuration (`ProviderInstance.allowsEmptyAPIKey`: apiKey mode +
        // custom base URL + OpenAI/Anthropic family). It still returns null for
        // an official endpoint with no key, and for an OAuth instance with no
        // token — both of which must keep short-circuiting — so the "provider
        // types that genuinely require a key" case is unchanged.
        //
        // This also makes refresh agree with the send path: ChatViewModel and
        // VisionGroupResolver already resolve credentials through
        // `usableApiKey`, so a keyless local server could be chatted with but
        // not refreshed. That inconsistency was the bug.
        var apiKey = usableApiKey(instance)

        // For OAuth providers, try to refresh the token before using it (mirrors iOS validAccessToken)
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth && apiKey != null) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = manager?.validAccessToken()
                if (freshToken != null && freshToken != apiKey) {
                    saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                    android.util.Log.i("ProviderRepo", "refreshModels: OAuth token refreshed")
                }
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "OAuth token refresh failed: ${e.message}")
            }
        }

        android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL}")

        // OpenAI Codex OAuth: use static model list (OAuth tokens can't call /v1/models)
        if (instance.providerType == ProviderType.openAI
            && instance.credentialType == ProviderCredential.oauth
        ) {
            val models = OpenAIModelsApi.fetchModelsOAuth()
            if (models.isNotEmpty()) {
                replaceEntries(instance.id, models)
                return
            }
        }

        // Step 1: Try provider API (requires API key)
        val customBase = instance.customBaseURL
        val isThirdParty = customBase != null
            && !customBase.lowercase().let {
                it.contains("api.openai.com") || it.contains("chatgpt.com") || it.contains("openrouter.ai")
            }

        if (apiKey != null) {
            val baseURL = instance.effectiveBaseURL
            val models = try {
                when (instance.providerType) {
                    ProviderType.anthropic -> AnthropicModelsApi.fetchModels(
                        apiKey, baseURL,
                        isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                        // [T-provider-custom-user-agent] models-list UA override.
                        customUserAgent = instance.customUserAgent,
                    )
                    ProviderType.gemini -> GeminiModelsApi.fetchModels(apiKey)
                    // [T-provider-custom-user-agent] models-list UA override.
                    // [T-android-provider-type-parity] openAIResponses lists
                    // models from the same /v1/models endpoint — only the
                    // completion endpoint differs.
                    ProviderType.openAI, ProviderType.openAIResponses ->
                        OpenAIModelsApi.fetchModels(apiKey, baseURL, customUserAgent = instance.customUserAgent)
                    ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(apiKey)
                    // [T-provider-dynamic-catalog-reconcile] xAI: fetch the live
                    // catalog, fall back to the built-in list.
                    //
                    // This used to return the static list unconditionally, with
                    // a comment saying a dynamic endpoint was hypothetical ("if
                    // xAI later exposes a dynamic /v1/models endpoint this is
                    // the place"). It exists and is OpenAI-compatible: an
                    // unauthenticated GET https://api.x.ai/v1/models answers 401
                    // with `{"code":"invalid-argument","error":"Incorrect API
                    // key…"}` — an auth challenge, not a 404. So the built-in
                    // set was the ONLY set anyone could ever get, on both the
                    // OAuth and the API-key path, and any model shipped after a
                    // release (GH#265: grok-4.6) stayed invisible until the app
                    // itself was updated. Manual Refresh could not help because
                    // it runs this same code.
                    //
                    // `fetchModels` treats api.x.ai as a custom base, so on any
                    // failure it returns EMPTY rather than a wrong-vendor guess;
                    // the `models.isNotEmpty()` gate below then leaves the
                    // seeded catalog in place. That makes the live call additive
                    // — it can enrich the list but never empty it.
                    //
                    // The `?:` default is load-bearing, not cosmetic: an xAI
                    // instance added through the OAuth button carries no
                    // customBaseURL, so `effectiveBaseURL` is null — and
                    // `OpenAIModelsApi.buildURL(null)` resolves to
                    // api.openai.com/v1/models. Passing the null straight
                    // through would send an xAI token to OpenAI and, on the
                    // official-endpoint path, fall back to `LLMModel.allOpenAI`,
                    // i.e. show GPT models under a Grok provider. That is the
                    // same class of bug the addInstance seeding comment already
                    // warns about ("Refresh on Grok returns GPT-5.5").
                    ProviderType.xAI -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "https://api.x.ai/v1",
                        customUserAgent = instance.customUserAgent,
                    ).ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
                    // [T-kimi-oauth] Kimi Code: unlike Codex OAuth, the Kimi
                    // OAuth token CAN call the models endpoint — real fetch
                    // from GET /coding/v1/models (OpenAI-compatible shape).
                    // The upstream lineup shifts across generations, so the
                    // live list replaces the minimal built-in fallback.
                    ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                        customUserAgent = instance.customUserAgent,
                    )
                    // [T-android-provider-type-parity] No models endpoint to
                    // query for a type this build cannot drive; the instance
                    // keeps whatever entries the restore brought with it.
                    ProviderType.antigravity, ProviderType.unsupported -> emptyList()
                }
            } catch (e: Exception) {
                android.util.Log.e("ProviderRepo", "refreshModels fetch error: ${e.message}", e)
                emptyList()
            }
            android.util.Log.i("ProviderRepo", "refreshModels: got ${models.size} models")

            // Step 2: If API returned results, use them
            if (models.isNotEmpty()) {
                replaceEntries(instance.id, models)
                return
            }
        }

        // Step 3: Fallback to models.dev by base URL.
        // We try this even for third-party hosts (DashScope/Bailian, etc.) — the
        // models.dev registry covers many "Anthropic-compatible" or "OpenAI-compatible"
        // gateways by hostname, and when there's no match `fetchModels` returns
        // empty so the existing list (vLLM/Ollama on a private host) is preserved.
        val fallbackBaseURL = modelsDevBaseURL(instance)
        val fallbackModels = ModelsDevApi.fetchModels(fallbackBaseURL)
        if (fallbackModels.isNotEmpty()) {
            android.util.Log.i("ProviderRepo", "models.dev fallback returned ${fallbackModels.size} models for ${instance.label}")
            replaceEntries(instance.id, fallbackModels)
        } else if (isThirdParty) {
            android.util.Log.i("ProviderRepo", "Third-party endpoint, no models.dev match — preserving existing models for ${instance.label}")
        }
    }

    /**
     * Auto-refresh variant: skips instances where the user has added custom models,
     * so we never overwrite hand-edited entries. Mirrors iOS `autoRefreshModels(for:)`.
     */
    private suspend fun autoRefreshModels(instance: ProviderInstance) {
        val hasCustom = _config.value.modelEntries.any {
            it.providerInstanceId == instance.id && it.isCustom
        }
        if (hasCustom) {
            android.util.Log.i("ProviderRepo", "[ModelList] autoRefresh SKIP ${instance.label} — has custom models")
            return
        }
        refreshModels(instance)
    }

    /**
     * Stale-while-revalidate helper for UI code. Callers (e.g. the model
     * picker sheet) invoke this on open: the currently-persisted
     * `config.modelEntries` is returned immediately via the StateFlow
     * (no waiting), and if any instance's cache is older than
     * [MODEL_CACHE_TTL_MS] we kick a background refresh that updates the
     * StateFlow when the network fetch completes.
     *
     * Concurrent-call safe: per-instance `autoRefreshModels` is idempotent
     * and the global daily-refresh flag prevents cold-start double fetch.
     * This helper bypasses the daily flag because it runs per-instance — it's
     * meant for targeted "user is looking at this picker now" revalidation.
     */
    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) {
        val stale = _config.value.instances.filter { it.isEnabled && isInstanceStale(it.id) }
        if (stale.isEmpty()) return
        android.util.Log.i("ProviderRepo", "[ModelList] SWR refresh — ${stale.size} stale instance(s)")
        for (instance in stale) {
            scope.launch { autoRefreshModels(instance) }
        }
    }

    /**
     * Refresh model lists for all enabled instances, at most once per calendar day.
     * Mirrors iOS `refreshAllModelsIfNeeded()` — called from Application.onCreate.
     * Refreshes run in parallel; failures are logged but don't block other instances.
     */
    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
        val key = "lastModelsRefreshDate"
        val lastMs = prefs.getLong(key, 0L)
        val now = System.currentTimeMillis()
        if (lastMs > 0L && isSameCalendarDay(lastMs, now)) {
            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — already refreshed today")
            return
        }

        // [T-android-startup-config-stall] Config now loads asynchronously, so
        // at cold start `_config.value` may still be the empty placeholder when
        // this fires from MinisApp.onCreate. Wait for the load before reading
        // the enabled-instance set, otherwise the daily refresh would no-op on
        // "no enabled instances" and skip this launch entirely. Runs on the
        // caller's (IO) scope — does not touch the main thread.
        scope.launch {
            awaitConfigLoaded()
            val enabled = _config.value.instances.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — no enabled instances")
                return@launch
            }

            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded FIRE — ${enabled.size} instances")
            prefs.edit().putLong(key, now).apply()

            for (instance in enabled) {
                scope.launch { autoRefreshModels(instance) }
            }
        }
    }

    private fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = aMs
        val aYear = cal.get(java.util.Calendar.YEAR)
        val aDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = bMs
        return aYear == cal.get(java.util.Calendar.YEAR)
            && aDay == cal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Resolve the models.dev lookup base URL for an instance. */
    private fun modelsDevBaseURL(instance: ProviderInstance): String {
        instance.effectiveBaseURL?.let { return it }
        return when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com/v1"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            // [T-android-provider-type-parity] Responses API instances point at
            // the same OpenAI host; only the completion path differs.
            ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
            // No canonical host for a type this build cannot drive. Callers
            // reaching here have already exhausted effectiveBaseURL.
            ProviderType.antigravity, ProviderType.unsupported -> "https://api.openai.com/v1"
        }
    }

    // API Key management
    fun saveApiKey(instanceId: String, key: String) {
        encryptedPrefs.edit().putString("apikey_$instanceId", key).apply()
    }

    fun loadApiKey(instanceId: String): String? {
        return encryptedPrefs.getString("apikey_$instanceId", null)
    }

    /**
     * [T-empty-key-compat-endpoints] The credential the ROUTING layers should
     * use: the stored key, or "" for instances where an empty key is a valid
     * configuration (third-party OpenAI/Anthropic-compatible endpoint — see
     * ProviderInstance.allowsEmptyAPIKey). Returns null only when the instance
     * genuinely has no usable credential, so existing `?: return/continue`
     * call sites keep their skip semantics for everything else (notably OAuth
     * instances without a token, which must stay unauthenticated).
     */
    fun usableApiKey(instance: ProviderInstance): String? =
        loadApiKey(instance.id) ?: if (instance.allowsEmptyAPIKey) "" else null

    fun deleteApiKey(instanceId: String) {
        encryptedPrefs.edit().remove("apikey_$instanceId").apply()
    }

    // -- Import / Export --

    /**
     * [T-android-provider-export-oauth-token] OAuth manager for [instance],
     * covering EVERY OAuth provider type — including gemini / antigravity, which
     * OAuthManager.forInstance deliberately omits (it's tuned for the
     * login/logout/manual-bearer UI paths). Used only by the export/import
     * round-trip so we don't widen forInstance's shared behavior.
     */
    private fun oauthManagerFor(
        instance: ProviderInstance,
    ): com.openminis.app.auth.OAuthManager? = when (instance.providerType) {
        ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
        ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
        ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
        ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
        ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
        else -> null
    }

    /** Export an instance as shareable JSON (includes base64-encoded API key). */
    fun exportInstanceJSON(instanceId: String): String? {
        ensureConfigLoaded()
        val instance = instance(instanceId) ?: return null
        val entries = visibleEntries(instanceId) + _config.value.modelEntries.filter {
            it.providerInstanceId == instanceId && it.isHidden
        }

        val obj = JSONObject().apply {
            put("providerType", instance.providerType.name)
            put("label", instance.label)
            put("credentialType", instance.credentialType.name)
            val modelsArr = JSONArray()
            for (entry in entries) {
                modelsArr.put(JSONObject().apply {
                    put("modelId", entry.baseModel.id)
                    put("displayName", entry.baseModel.displayName)
                    put("isHidden", entry.isHidden)
                    if (entry.isCustom) put("isCustom", true)
                    entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                    entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                    entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                    // [T-provider-export-model-overrides] Serialize the FULL
                    // ModelOverrides layer, not just displayName/maxOutputTokens.
                    // contextWindow / supportsReasoning / modality (input+output)
                    // were previously dropped — a hand-corrected proxied model
                    // lost those edits on round-trip. Each key is additive +
                    // optional: older builds ignore unknown keys, and import
                    // below reads each independently so a partial override
                    // object restores exactly the fields present.
                    //
                    // For cross-platform interop with iOS we ALSO write a
                    // `modalityOverride` bitfield (Int, matching
                    // ios/Providers/LLMTypes.swift ModelModality OptionSet):
                    // textInput=1, textOutput=2, imageInput=4, pdfInput=8,
                    // audioInput=16, videoInput=32, imageOutput=64,
                    // audioOutput=128, videoOutput=256. Android natively
                    // carries inputModalities/outputModalities as string lists;
                    // the bitfield is purely an interop wire-format that iOS
                    // can consume directly. On import, the native list fields
                    // win when present (Android↔Android lossless), bitfield is
                    // the iOS→Android fallback.
                    if (!entry.overrides.isEmpty) {
                        val o = JSONObject()
                        entry.overrides.displayName?.let { o.put("displayName", it) }
                        entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
                        entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
                        entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
                        entry.overrides.inputModalities?.let {
                            o.put("inputModalities", JSONArray(it))
                        }
                        entry.overrides.outputModalities?.let {
                            o.put("outputModalities", JSONArray(it))
                        }
                        val bitfield = modalityBitfieldFromLists(
                            entry.overrides.inputModalities,
                            entry.overrides.outputModalities,
                        )
                        if (bitfield != 0) o.put("modalityOverride", bitfield)
                        put("overrides", o)
                    }
                    // Mirror iOS export of baseModel.modalityOverride — when
                    // the base model itself carries explicit modality info,
                    // serialize an interop bitfield so iOS can faithfully
                    // restore it. Android's native baseModel uses string
                    // lists too; this is purely additive for iOS readers.
                    run {
                        val bf = modalityBitfieldFromLists(
                            entry.baseModel.inputModalities,
                            entry.baseModel.outputModalities,
                        )
                        if (bf != 0) put("modalityOverride", bf)
                    }
                    entry.baseModel.inputModalities?.let {
                        put("inputModalities", JSONArray(it))
                    }
                    entry.baseModel.outputModalities?.let {
                        put("outputModalities", JSONArray(it))
                    }
                })
            }
            put("models", modelsArr)
            loadApiKey(instanceId)?.let { key ->
                put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
            }
            // Export manual OAuth bearer token (mirrors iOS `manualOAuthToken` key).
            // Stored per-instance via OAuthManager; only present for OAuth providers
            // where the user pasted a static token via the Manual Bearer Token UI.
            run {
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val manual = mgr?.loadManualBearerToken()
                if (!manual.isNullOrEmpty()) {
                    put("manualOAuthToken", Base64.encodeToString(manual.toByteArray(), Base64.NO_WRAP))
                }
            }
            // [T-android-provider-export-oauth-token] (XIN 38955) Export the
            // STRUCTURED OAuth-login credential (access_token / refresh_token /
            // expire_at) saved by the OAuth login flow under a separate pref than
            // apiKey / manualOAuthToken. Previously omitted, so an OAuth-logged-in
            // Claude / OpenAI / Gemini / xAI provider exported with no usable
            // credential and imported as not-authenticated. The whole token is one
            // JSON blob on Android (OAuthManager.loadStoredTokens); JSON-encode +
            // base64 it under "oauthToken", matching iOS 703ff4bc's field name and
            // the existing apiKey / manualOAuthToken base64 encoding. Covers every
            // OAuth provider type via oauthManagerFor (not just the forInstance set).
            run {
                val mgr = oauthManagerFor(instance)
                mgr?.exportStoredTokensJson()?.let { tokenJson ->
                    put("oauthToken", Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP))
                }
                // Gemini also stores the resolved account email + GCP project as
                // separate OAuth strings (mirrors iOS oauthEmail / oauthGcpProject);
                // carry them so the imported instance can call the API.
                if (instance.providerType == ProviderType.gemini && mgr != null) {
                    mgr.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                    mgr.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                }
            }
            instance.customBaseURL?.let { put("customBaseURL", it) }
            if (!instance.appendV1Suffix) put("appendV1Suffix", false)
            if (instance.useResponsesAPI) put("useResponsesAPI", true)
            // [T-provider-custom-user-agent] Additive, optional. Only written
            // when set; old/new readers without the key decode to null →
            // default UA. Field name matches iOS for cross-platform interop.
            instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
        }
        return obj.toString(2)
    }

    /**
     * [T-android-backup-secrets] Collect ONE instance's credentials for the
     * backup `secrets.json`, base64-encoded exactly as [exportInstanceJSON]
     * does (same keychain + OAuth-manager access paths, same field semantics).
     * Returns null when the instance carries no usable credential, so the
     * caller can skip empty entries — matching iOS `BackupSecretsCollector`'s
     * `if !secret.isEmpty` guard.
     */
    fun collectBackupProviderSecret(
        instance: ProviderInstance,
    ): BackupSecrets.ProviderSecret? {
        fun b64(s: String): String =
            Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

        val apiKey = loadApiKey(instance.id)?.let(::b64)
        val manualOAuth = com.openminis.app.auth.OAuthManager
            .forInstance(context, instance)?.loadManualBearerToken()
            ?.takeIf { it.isNotEmpty() }?.let(::b64)

        var oauthToken: String? = null
        var oauthEmail: String? = null
        var oauthGcpProject: String? = null
        val mgr = oauthManagerFor(instance)
        if (mgr != null) {
            oauthToken = mgr.exportStoredTokensJson()?.let(::b64)
            if (instance.providerType == ProviderType.gemini) {
                oauthEmail = mgr.exportOAuthString("email")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
                oauthGcpProject = mgr.exportOAuthString("gcp_project")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
            }
        }

        val secret = BackupSecrets.ProviderSecret(
            instanceId = instance.id,
            label = instance.label,
            providerType = instance.providerType.name,
            apiKey = apiKey,
            manualOAuthToken = manualOAuth,
            oauthToken = oauthToken,
            oauthEmail = oauthEmail,
            oauthGcpProject = oauthGcpProject,
        )
        return if (secret.isEmpty) null else secret
    }

    /**
     * [T-android-backup-secrets] Restore one instance's credentials from a
     * backup `secrets.json` entry. Returns true if any credential was WRITTEN,
     * false if the instance already had a key (kept) or the secret was empty —
     * this true/false is what the importer accumulates into
     * `providersRestored` vs `providersSkippedExisting` (the counts that drive
     * the restore-complete credentials message, iOS parity).
     *
     * Keep-existing policy mirrors iOS: an API key already on this device is
     * NOT overwritten, so restoring a backup onto its origin device reports
     * "kept" rather than "restored".
     */
    fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean {
        fun deb64(s: String?): String? = s?.let {
            runCatching { String(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull()
        }
        val instance = instance(secret.instanceId) ?: return false
        var wrote = false

        deb64(secret.apiKey)?.let { key ->
            if (loadApiKey(instance.id) == null) {
                saveApiKey(instance.id, key)
                wrote = true
            }
        }
        deb64(secret.manualOAuthToken)?.let { tok ->
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            if (mgr != null && mgr.loadManualBearerToken().isNullOrEmpty()) {
                mgr.saveManualBearerToken(tok)
                wrote = true
            }
        }
        val mgr = oauthManagerFor(instance)
        if (mgr != null) {
            deb64(secret.oauthToken)?.let { json ->
                if (mgr.exportStoredTokensJson().isNullOrEmpty()) {
                    mgr.importStoredTokensJson(json)
                    wrote = true
                }
            }
            if (instance.providerType == ProviderType.gemini) {
                deb64(secret.oauthEmail)?.let {
                    if (mgr.exportOAuthString("email").isNullOrEmpty()) {
                        mgr.importOAuthString("email", it); wrote = true
                    }
                }
                deb64(secret.oauthGcpProject)?.let {
                    if (mgr.exportOAuthString("gcp_project").isNullOrEmpty()) {
                        mgr.importOAuthString("gcp_project", it); wrote = true
                    }
                }
            }
        }
        return wrote
    }

    /**
     * [T-android-backup-restore-order] Non-destructive, ORDER-PRESERVING union
     * merge of a restored [remote] ProviderConfig into the live config. This is
     * the Android port of iOS `mergeProviderConfigFallback`
     * (BackupImporter+Categories.swift), including the fix `T-backup-restore-order`
     * (b33eb1ff6): the package decides POSITION for the items it carries, so a
     * restore reproduces the backed-up arrangement instead of re-sorting by
     * createdAt.
     *
     * Rules (identical to iOS):
     *  - instances / modelGroups: rebuilt in PACKAGE order. For each remote id,
     *    if the id already exists locally keep the LOCAL element (content wins),
     *    else take the remote element. Local-only ids are appended afterwards in
     *    their original relative order.
     *  - modelEntries: additive by id (append remote ids not present locally);
     *    order not significant (entries are looked up by id, ordered per group).
     *  - agentLoop bindings: set-union.
     *  - per-device pointers (default / voice / vision group ids, session
     *    bindings) are NOT part of ProviderConfig here and are untouched.
     *
     * Returns (beforeInstances, afterInstances) so the caller derives
     * imported = after-before and skipped = package.count - imported — the same
     * counting iOS uses (fix 93cad55ae: union-by-id already-present is SKIPPED,
     * not "updated").
     */
    fun mergeBackupProviderConfig(remote: ProviderConfig): Pair<Int, Int> {
        ensureConfigLoaded()
        return synchronized(configLock) {
            val local = _config.value
            val before = local.instances.size

            val orderedInstances = mutableListOf<ProviderInstance>()
            val placedInstances = mutableSetOf<String>()
            for (ri in remote.instances) {
                val existing = local.instances.firstOrNull { it.id == ri.id }
                orderedInstances.add(existing ?: ri)
                placedInstances.add(ri.id)
            }
            for (li in local.instances) {
                if (li.id !in placedInstances) orderedInstances.add(li)
            }

            val mergedEntries = local.modelEntries.toMutableList()
            val entryIds = mergedEntries.map { it.id }.toMutableSet()
            for (entry in remote.modelEntries) {
                if (entry.id !in entryIds) {
                    mergedEntries.add(entry)
                    entryIds.add(entry.id)
                }
            }

            val orderedGroups = mutableListOf<ModelGroup>()
            val placedGroups = mutableSetOf<String>()
            for (rg in remote.modelGroups) {
                val existing = local.modelGroups.firstOrNull { it.id == rg.id }
                orderedGroups.add(existing ?: rg)
                placedGroups.add(rg.id)
            }
            for (lg in local.modelGroups) {
                if (lg.id !in placedGroups) orderedGroups.add(lg)
            }

            val mergedAgentEntries =
                (local.agentLoopModelEntryIds + remote.agentLoopModelEntryIds).distinct()
            val mergedAgentGroups =
                (local.agentLoopGroupIds + remote.agentLoopGroupIds).distinct()

            val merged = local.copy(
                instances = orderedInstances,
                modelEntries = mergedEntries,
                modelGroups = orderedGroups,
                agentLoopModelEntryIds = mergedAgentEntries.toMutableList(),
                agentLoopGroupIds = mergedAgentGroups.toMutableList(),
            )
            saveConfig(merged)
            val after = orderedInstances.size
            android.util.Log.i(
                "ProviderRepo",
                "[Restore] provider merge: instances $before→$after " +
                    "entries=${mergedEntries.size} groups=${orderedGroups.size}",
            )
            before to after
        }
    }

    /**
     * [T-android-backup-thinking-rules] Restore custom thinking rules by
     * id-keyed replace. Returns (written, skipped).
     *
     * Divergence from iOS, called out deliberately: iOS does `updated_at` LWW
     * (local equal-or-newer wins). Android's `provider_thinking_rules` table has
     * NO time columns, so there is nothing to compare — a rule already present
     * by id is left as-is (skipped), an absent one is inserted. This is the
     * closest faithful behaviour the local schema allows; the record's carried
     * createdAt/updatedAt are ignored on import.
     */
    fun restoreBackupThinkingRules(
        rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
    ): Pair<Int, Int> = runBlocking {
        if (rules.isEmpty()) return@runBlocking 0 to 0
        val existing = providerDao.loadAllThinkingRules().map { it.id }.toSet()
        var written = 0
        var skipped = 0
        for (r in rules) {
            if (r.id in existing) { skipped++; continue }
            providerDao.upsertThinkingRule(
                com.openminis.app.data.db.ProviderThinkingRuleEntity(
                    id = r.id,
                    providerInstanceId = r.instanceId,
                    label = r.label,
                    scopeKind = r.scopeKind,
                    scopePattern = r.scopePattern,
                    wireFormatJson = r.wireFormatJson.takeIf { it.isNotBlank() && it != "{}" },
                    reasoningEchoJson = null,
                    sortOrder = r.sortOrder,
                )
            )
            written++
        }
        loadAllThinkingRulesIntoCache()
        written to skipped
    }

    /**
     * Import a provider from exported JSON. Returns the new instance label on success.
     * - Auto-renames on label conflict
     * - Decodes base64-encoded API key (falls back to plain text)
     */
    fun importInstanceJSON(jsonStr: String): String? {
        ensureConfigLoaded()
        val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
        val label = dict.optString("label", "").ifEmpty { return null }

        val credentialType = try {
            ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
        } catch (_: Exception) { ProviderCredential.apiKey }

        // Resolve label conflict
        val existingLabels = _config.value.instances.map { it.label }.toSet()
        var resolvedLabel = label
        if (resolvedLabel in existingLabels) {
            var suffix = 2
            while ("$label ($suffix)" in existingLabels) suffix++
            resolvedLabel = "$label ($suffix)"
        }

        val customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null }
        val appendV1 = dict.optBoolean("appendV1Suffix", true)
        val useResponsesAPI = dict.optBoolean("useResponsesAPI", false)
        // [T-provider-custom-user-agent] Additive: old exports lack the key →
        // empty → null → default UA. Field name matches iOS.
        val customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null }

        val instance = ProviderInstance(
            id = java.util.UUID.randomUUID().toString(),
            label = resolvedLabel,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1,
            useResponsesAPI = useResponsesAPI,
            customUserAgent = customUserAgent,
        )
        addInstance(instance)

        // Decode API key (base64 or plain text)
        val keyValue = dict.optString("apiKey", "").ifEmpty { null }
        if (keyValue != null) {
            val apiKey = try {
                String(Base64.decode(keyValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                keyValue // plain text fallback
            }
            saveApiKey(instance.id, apiKey)
        }

        // Decode manual OAuth bearer token (mirrors iOS `manualOAuthToken`).
        // base64-encoded UTF-8 string, with plain-text fallback for older exports.
        val manualTokenValue = dict.optString("manualOAuthToken", "").ifEmpty { null }
        if (manualTokenValue != null) {
            val manualToken = try {
                String(Base64.decode(manualTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                manualTokenValue
            }
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            mgr?.saveManualBearerToken(manualToken)
        }

        // [T-android-provider-export-oauth-token] (XIN 38955) Restore the
        // structured OAuth-login credential so the imported instance is
        // authenticated. Decode base64 → JSON → write back via the OAuth
        // manager. Mirrors iOS 703ff4bc; purely additive alongside the
        // apiKey / manualOAuthToken restore above.
        val oauthTokenValue = dict.optString("oauthToken", "").ifEmpty { null }
        if (oauthTokenValue != null) {
            val tokenJson = try {
                String(Base64.decode(oauthTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                oauthTokenValue // plain-text fallback for hand-edited exports
            }
            oauthManagerFor(instance)?.importStoredTokensJson(tokenJson)
        }
        // Gemini account email + GCP project (base64-encoded OAuth strings).
        if (instance.providerType == ProviderType.gemini) {
            val mgr = oauthManagerFor(instance)
            dict.optString("oauthEmail", "").ifEmpty { null }?.let { b64 ->
                val email = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("email", email)
            }
            dict.optString("oauthGcpProject", "").ifEmpty { null }?.let { b64 ->
                val project = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("gcp_project", project)
            }
        }

        // Import models (replace built-in defaults)
        val models = dict.optJSONArray("models")
        if (models != null && models.length() > 0) {
            val entries = mutableListOf<ModelEntry>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val modelId = m.optString("modelId", "")
                if (modelId.isEmpty()) continue
                val displayName = m.optString("displayName", modelId)
                val isCustom = m.optBoolean("isCustom", false)
                val isHidden = m.optBoolean("isHidden", false)
                val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
                val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
                val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
                val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
                // [T-provider-export-model-overrides] Restore baseModel
                // modalities. Android-native list fields win when present;
                // otherwise fall back to iOS's `modalityOverride` bitfield so
                // a provider exported on iOS retains its capability info.
                val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
                val model = LLMModel(
                    id = modelId,
                    displayName = displayName,
                    provider = providerType.displayName,
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = supportsReasoning,
                    interleavedReasoningField = interleavedReasoningField,
                    inputModalities = baseIn,
                    outputModalities = baseOut,
                )
                val overridesObj = m.optJSONObject("overrides")
                val overrides = if (overridesObj != null) {
                    // [T-provider-export-model-overrides] Read the full
                    // overrides layer. Each key is read independently — a
                    // missing key (old export, partial object) simply stays
                    // null → the field falls back to baseModel / defaults.
                    val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
                    ModelOverrides(
                        displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                        maxOutputTokens = if (overridesObj.has("maxOutputTokens")) overridesObj.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                        contextWindow = if (overridesObj.has("contextWindow")) overridesObj.optInt("contextWindow").takeIf { it > 0 } else null,
                        supportsReasoning = if (overridesObj.has("supportsReasoning")) overridesObj.optBoolean("supportsReasoning") else null,
                        inputModalities = ovIn,
                        outputModalities = ovOut,
                    )
                } else {
                    ModelOverrides()
                }
                entries.add(ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = model,
                    overrides = overrides,
                    isCustom = isCustom,
                    isHidden = isHidden,
                ))
            }
            // Import replaces built-in entries directly (not via replaceEntries which takes LLMModel list).
            // [T-android-provider-mutator-lock] Lock + working copy, like every
            // other mutator. This function is the actual provider.import /
            // Share-import path, and it read `_config.value` back — the object
            // addInstance had just PUBLISHED — and mutated its live list. That
            // is the exact race behind both device CMEs; the earlier sweep
            // missed it because the audit went by function name and this one
            // isn't called add*/update*/remove*.
            synchronized(configLock) {
                val cfg = workingCopy()
                cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                cfg.modelEntries.addAll(entries)
                saveConfig(cfg)
            }
        }

        return resolvedLabel
    }

    // -- Modality interop with iOS ----------------------------------------
    //
    // iOS encodes ModelModality as a single Int bitfield (OptionSet rawValue);
    // Android carries inputModalities / outputModalities as bare string lists
    // ("text" / "image" / "pdf" / "audio" / "video"). The export/import path
    // writes both encodings so the wire format is portable in either
    // direction without losing fidelity:
    //   - Android → Android: the native string lists round-trip exactly.
    //   - Android → iOS:    iOS reads `modalityOverride` Int and ignores
    //                       the unknown list keys (forward-compatible).
    //   - iOS → Android:    Android prefers the native list keys when
    //                       present (Android-original export); otherwise
    //                       decodes `modalityOverride` Int back into lists.
    //
    // Bit layout constants live at file scope above the class (Kotlin
    // forbids a second companion object, and ProviderRepository already
    // has one).

    private fun modalityBitfieldFromLists(
        inputs: List<String>?,
        outputs: List<String>?,
    ): Int {
        var bits = 0
        inputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_IN
                "image" -> bits = bits or MODALITY_BIT_IMG_IN
                "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
                "audio" -> bits = bits or MODALITY_BIT_AUD_IN
                "video" -> bits = bits or MODALITY_BIT_VID_IN
            }
        }
        outputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
                "image" -> bits = bits or MODALITY_BIT_IMG_OUT
                "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
                "video" -> bits = bits or MODALITY_BIT_VID_OUT
            }
        }
        return bits
    }

    private fun modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
        if (bits == 0) return null to null
        val inputs = buildList {
            if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
            if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
            if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
            if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
            if (bits and MODALITY_BIT_VID_IN != 0) add("video")
        }
        val outputs = buildList {
            if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
            if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
            if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
            if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
        }
        return inputs.ifEmpty { null } to outputs.ifEmpty { null }
    }

    /**
     * Read modality info from a JSON object. Returns (inputs, outputs):
     *   - native `inputModalities` / `outputModalities` list keys take
     *     precedence (Android-original export — lossless).
     *   - if neither list is present, decode iOS's `modalityOverride`
     *     bitfield as the fallback.
     *   - if neither shape is present, returns null pair (caller treats
     *     as "no modality info" → baseModel defaults apply).
     */
    private fun readModalitiesWithBitfieldFallback(
        obj: JSONObject,
    ): Pair<List<String>?, List<String>?> {
        val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
        if (!obj.has("modalityOverride")) return null to null
        val bits = obj.optInt("modalityOverride", 0)
        return modalityListsFromBitfield(bits)
    }
}
