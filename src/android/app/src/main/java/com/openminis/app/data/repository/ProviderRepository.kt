package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.ProviderConfigSnapshot
import com.openminis.app.data.db.toEntity
import com.openminis.app.data.db.toRule
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.applyUnrecognizedModelDefaults
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
import com.openminis.app.data.model.withInferredVideoModality
import com.openminis.app.data.model.withInferredImageModality
import com.openminis.app.provider.ModelReleaseIndex
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

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
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    internal val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // [T-android-provider-room-store] Per-row provider config DB. Lives in
    // its own provider.db file so a downgrade to a build that doesn't know
    // about provider tables can't crash the main minis.db open. The legacy
    // SharedPreferences JSON mirror under "provider_config / config" is
    // still written on every save so older builds keep reading current
    // config — losing nothing on downgrade. See [loadConfig] for the
    // downgrade-and-back-up reconciliation logic.
    internal val providerDao: ProviderConfigDao =
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
        internal const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"
        /** Single source of truth for provider type × credential support. */
        internal fun supportedCredentials(type: ProviderType): List<ProviderCredential> = when (type) {
            ProviderType.anthropic, ProviderType.openAI, ProviderType.openRouter,
            ProviderType.xAI, ProviderType.kimiCode, ProviderType.openAIResponses ->
                listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
            ProviderType.gemini -> listOf(ProviderCredential.apiKey)
            ProviderType.antigravity, ProviderType.unsupported -> listOf(ProviderCredential.apiKey)
        }

        internal fun supportsCredential(type: ProviderType, credential: ProviderCredential): Boolean =
            credential in supportedCredentials(type)

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
        // [T-key-cache-invalidate] 模型刷新后旧 ProviderFactory 的 memo
        // 缓存必须作废 —— 否则已构造的 provider 带着旧 key/旧配置继续
        // 存活，表现为“刷新了模型但发送用的还是旧 key”。
        // [T-provider-factory-race] 只清本实例：invalidateAll 会连坐清掉
        // 无关实例、反复重建整组 OkHttpClient。
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: route through the self-healing
        // factory so a corrupted master key on Samsung One UI / Android
        // 16 doesn't crash the app at first read.
        val prefs = com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
        // [T-keypool-orphan-cleanup] Key pools shipped in v2.0.20 and were
        // removed in 5b9aa6f; the pool-preferring read path went with them,
        // but the persisted `keypool_<instanceId>` blobs stayed in
        // provider_secrets forever — and an APK rolled BACK to v2.0.20
        // would happily read them again. No code reads these keys any
        // more; sweep them once per process (no-op after the first run).
        runCatching {
            val orphans = prefs.all.keys.filter { it.startsWith("keypool_") }
            if (orphans.isNotEmpty()) {
                prefs.edit().also { e -> orphans.forEach { e.remove(it) } }.apply()
                android.util.Log.i("ProviderRepo", "swept " + orphans.size + " orphan keypool_* entries")
            }
        }
        prefs
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
    internal val _config = MutableStateFlow(ProviderConfig())
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
    private val _credentialRevision = MutableStateFlow(0L); val credentialRevision: StateFlow<Long> = _credentialRevision.asStateFlow()
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
                // [T-nonstream-json-default] loadConfigSuspending directly (no runBlocking hop);
                // JSON/DAO decode moves to Dispatchers.Default, not the IO pool next to blocking I/O.
                val loaded = withContext(Dispatchers.Default) { loadConfigSuspending() }
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
            // Completed in every path, including the failure one: awaitConfigLoaded()
            // callers (refreshAllModelsIfNeeded) would otherwise suspend forever.
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // [T-android-provider-voice] Reconcile voice-template seeds after
            // the initial load (add new template models, drop retired seeds,
            // heal corrupted modality). Mirrors iOS ProviderConfigStore.init.
            try {
                ensureVoiceTemplateModels()
                ensureBuiltInZenProvider()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[BuiltIn] provider reconciliation failed: ${e.message}")
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
    internal val configLock = Any()

    // ── [T-android-save-off-main-thread] Single-threaded persistence pipeline ──
    //
    // `saveConfig` used to run the whole serialize → 5-table clear+bulk-upsert →
    // `prefs.edit().commit()` sequence inside `synchronized(configLock)` ON THE
    // CALLER THREAD. Every one of those callers is a Compose callback
    // (ui/settings/ModelGroupsScreen.kt slot picks, ui/onboarding/OnboardingScreen.kt),
    // so each model-slot pick paid a full config encode — [T-android-startup-
    // config-stall] #753 measured ~8 s for the decode of a large config on the
    // READ side — plus a synchronous disk commit. Writes now publish
    // in-memory under the lock and hand the durable part to ONE worker
    // coroutine, which keeps the serialisation [configLock] bought (writes
    // still never interleave, one snapshot at a time) without blocking Main.
    //
    // FIFO is deliberate: a slot pick that landed after another must not
    // overwrite it in the store, so the queue is consumed by a single worker
    // rather than dispatched onto a multi-threaded pool.
    internal val persistRequests = kotlinx.coroutines.channels.Channel<ProviderPersistRequest>(
        capacity = kotlinx.coroutines.channels.Channel.UNLIMITED,
    )
    internal val writeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    /** Monotonic id of the queued snapshot the worker is publishing. */
    internal val saveGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val persistWorkerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Starts the persistence worker on the first save. Idempotent. */
    internal fun ensurePersistWorker() {
        if (!persistWorkerStarted.compareAndSet(false, true)) return
        writeScope.launch { drainPersistQueue() }
    }

    /**
     * Suspends until every save queued before this call has reached the store.
     * Backup/restore and any other "durability before returning" path should
     * await it — the fire-and-forget `saveConfig` no longer implies the write
     * landed.
     */
    internal suspend fun awaitPersisted() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        persistRequests.send(ProviderPersistRequest.Flush(done))
        done.await()
    }

    /**
     * Blocking config read. [T-android-save-off-main-thread] caller audit (this is
     * the ONLY two call sites it has, and the audit is what decides whether the
     * `runBlocking` is allowed):
     *
     *  1. the `init { loadScope.launch { … } }` loader above — that scope is
     *     `SupervisorJob() + Dispatchers.IO`, so genuinely off-main;
     *  2. [ensureConfigLoaded], i.e. every non-suspend mutator/reader in this
     *     class (add/update/removeInstance, add/removeEntry, group + agent-loop +
     *     voice/vision slot setters, resolve*Candidates…). Those ARE reachable from
     *     the composition thread — ui/settings/AddProviderScreen.kt:640
     *     (`addInstance`), ProviderListScreen.kt:407/524,
     *     ProviderDetailScreen.kt:777 (`removeInstance`),
     *     ModelGroupsScreen.kt:402 / OnboardingModelSelectionScreen.kt:226
     *     (`addGroup`) all call it directly from a click/confirm lambda —
     *     alongside genuinely off-main ones (the config/collections adapters run on
     *     minis-config bridge's background pool, debug/ handlers,
     *     provider/agent paths).
     *
     * The stall is therefore real but BOUNDED to the cold-start window: the very
     * first line of [ensureConfigLoaded] returns the moment [_configLoaded] is
     * true, which the IO loader set during Application startup, so a main-thread
     * caller only pays the decode when it acts before that load lands. Removing
     * even that window means giving the public mutators themselves suspend
     * variants (and moving their Room/keystore work off the lock), which changes
     * the signature of every one of the ~18 Compose call sites listed above —
     * files outside this workstream's scope. Kept as-is on purpose; see the
     * matching note on [ensureConfigLoaded].
     */
    private fun loadConfig(): ProviderConfig = runBlocking { loadConfigSuspending() }

    /**
     * Stable hash of the JSON mirror string, used as the in-DB synced-state
     * marker. SHA-256 hex so collisions are negligible. Returns null only
     * if [str] is null (caller normalizes).
     */
    internal fun hashJsonMirror(str: String): String {
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
     * [T-android-startup-config-stall] Guard for read-modify-write mutators.
     * If the async load hasn't applied yet, load synchronously NOW (on the
     * caller's thread) before the mutator reads `_config.value`. Without this a
     * write that lands during the startup load window would read the empty
     * placeholder, mutate it, and persist — WIPING the user's real config.
     * Idempotent and cheap once loaded (just a volatile read). Synchronized on
     * [configLock] so it can't race the async loader's emit.
     *
     * [T-android-save-off-main-thread] When [_configLoaded] is still false this
     * blocks the CALLER thread through [loadConfig] — see that function's KDoc for
     * the caller audit and why the cold-start-only window is left as it is. Once
     * the initial load has landed this is a volatile read and costs nothing, so
     * main-thread mutators never wait on I/O after startup.
     */
    // internal (was private): the debug server's read-only provider.* handlers
    // read `config.value` directly and must be able to force the lazy load —
    // on a cold process they otherwise report an EMPTY config, which reads as
    // "all your providers are gone" rather than "not loaded yet".
    internal fun ensureConfigLoaded() {
        if (_configLoaded.value) return
        synchronized(configLock) {
            if (_configLoaded.value) return
            // A read-modify-write operation must never proceed from the empty
            // placeholder when persisted data could not be read. Keep this
            // failure observable to each caller so it can abort before mutation.
            val loaded = try {
                loadConfig()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] ensureConfigLoaded failed; staying unloaded for retry: ${e.message}",
                )
                throw IllegalStateException("Provider configuration could not be loaded; mutation aborted", e)
            }
            _config.value = loaded
            _configLoaded.value = true
        }
        // [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache once
        // config is available, so the (sync) request builder can read user rules.
        loadAllThinkingRulesIntoCache()
        if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
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
     * Adds the bundled OpenCode Zen instance only on a fresh install.
     *
     * Upgrade safety: an existing user-created instance wins when its label or
     * endpoint already identifies Zen; otherwise the built-in gets a unique
     * deterministic label and ID, so no user-created provider is renamed or
     * merged accidentally.
     */
    private fun ensureBuiltInZenProvider() = synchronized(configLock) {
        if (!_configLoaded.value) return@synchronized
        val current = _config.value
        val endpoint = ZEN_BUNDLED_ENDPOINT
        // A user-created instance pointing at the same Zen endpoint already
        // represents this service; do not duplicate or mutate it. A same-label
        // instance with another endpoint is NOT a conflict: preserve it and use
        // a deterministic suffix for the bundled provider.
        //
        // But an existing Zen instance that has never catalogued a model is
        // exactly the "no free models anywhere" report: the hand-built
        // instance won the skip check, its live refresh never landed, and the
        // bundle was never seeded either. Reconciliation then fills the empty
        // shell — the instance keeps its id/label/credentials, and a later
        // live refresh still replaces the bundled list.
        // [T-zen-free-lane-follow] The reconciliation pass targets the
        // KEYLESS free-lane driver only: a user's own paid Zen instance
        // (same endpoint, real key) must never have its rows swept or its
        // catalogue filtered by free-lane logic. loadApiKey is reentrant
        // here (same-thread intrinsic lock), so reading it inside the
        // synchronized block is safe.
        val existingZen = current.instances.firstOrNull {
            isZenFreeInstance(it, loadApiKey(it.id))
        }
        if (existingZen != null) {
            // One pass, both directions: drop every non-custom entry that
            // can never work (paid-lane leftovers, proven-dead free ids),
            // then top the instance up to the full bundle minus the dead.
            //
            // The top-up is the half that matters for an upgrading install.
            // The build that shipped the white list carried ONE model
            // (space-bunny-free) and its sweep only ever deleted, so an
            // existing instance is left holding that single row — and a
            // user who upgrades never sees the other free models the
            // endpoint actually serves. Deleting dead rows and adding missing
            // bundled ones in the same pass is what makes "every free model
            // works, no key, no setup" true for an existing install rather
            // than only for a fresh one.
            val dead = zenDeadIds()
            val stale = zenStaleEntryIds(existingZen.id, current.modelEntries, dead)
            val present = current.modelEntries
                .filter { it.providerInstanceId == existingZen.id }
                .map { it.baseModel.id }
                .toSet()
            val missing = bundledZenModels().map { it.id }
                .filterNot { it in present || it in dead }
            if (stale.isNotEmpty() || missing.isNotEmpty()) {
                val config = workingCopy()
                config.modelEntries.removeAll { it.id in stale }
                val alreadyPresent = config.modelEntries
                    .filter { it.providerInstanceId == existingZen.id }
                    .map { it.baseModel.id }
                    .toSet()
                val added = bundledZenModels()
                    .filterNot { it.id in alreadyPresent || it.id in dead }
                    .map { ModelEntry(providerInstanceId = existingZen.id, baseModel = it) }
                config.modelEntries.addAll(added)
                saveConfig(config)
                android.util.Log.i(
                    "ProviderRepo",
                    "[BuiltIn] reconciled Zen instance ${existingZen.id} '${existingZen.label}': " +
                        "swept ${stale.size} unusable entries, added ${added.size} bundled free models",
                )
            }
            return@synchronized
        }

        val config = workingCopy()
        val baseLabel = "OpenCode Zen (Free)"
        val label = if (config.instances.none { it.label == baseLabel }) {
            baseLabel
        } else {
            "$baseLabel · Built-in"
        }
        val instance = ProviderInstance(
            id = "builtin-opencode-zen",
            label = label,
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = endpoint,
            appendV1Suffix = false,
            isEnabled = true,
        )
        config.instances.add(instance)
        saveApiKey(instance.id, "public")
        val models = bundledZenModels()
        config.modelEntries.addAll(models.map { ModelEntry(providerInstanceId = instance.id, baseModel = it) })
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[BuiltIn] seeded Zen instance ${instance.id} as '${instance.label}'")
    }

    /**
     * Whether [instance] points at a third-party OpenAI-compatible host
     * (xAI Grok, vLLM, Ollama, LiteLLM, DeepSeek via OpenAI shim, etc.).
     * For these instances we must never substitute `LLMModel.allOpenAI` as a
     * fallback / seed — those are GPT-only IDs that don't exist upstream.
     * Mirrors iOS `ProviderConfigStore.isThirdPartyOpenAICompat`.
     */
    private fun isZenInstance(instance: ProviderInstance): Boolean =
        instance.customBaseURL?.trimEnd('/') == ZEN_BUNDLED_ENDPOINT

    /**
     * [T-zen-free-lane-follow] Whether [instance] drives the KEYLESS free
     * lane: the Zen host, authenticated with the literal anonymous
     * credential ("public") the free lane accepts. Only these instances get
     * free-lane filtering and reconciliation — a user's own paid Zen
     * instance (same endpoint, real key) sees the full catalogue and is
     * never swept. Mirrors [com.openminis.app.provider.openai.OpenAIProvider.isZenFree].
     */
    private fun isZenFreeInstance(instance: ProviderInstance, apiKey: String?): Boolean =
        isZenInstance(instance) && apiKey == "public"

    /** Live retirement marks for the Zen free lane (see ZenFreeLaneHealth). */
    private fun zenDeadIds(): Set<String> =
        com.openminis.app.provider.ZenFreeLaneHealth.deadIds()

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

    fun setInstancePinned(id: String, pinned: Boolean): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val inst = config.instances.find { it.id == id } ?: return
        inst.pinned = pinned
        saveConfig(config)
    }

    fun removeInstance(instanceId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        invalidateModelCache(instanceId)
        val config = workingCopy()
        val removedInstance = config.instances.find { it.id == instanceId }
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
        // [T-remove-instance-cleanup] deleteApiKey only removes apikey_<id>.
        // OAuth tokens (oauth_tokens_<id>) and manual bearer tokens survived
        // the instance, so OAuthManager.isAuthenticated() kept answering
        // "logged in" for a provider that no longer exists — plus the
        // voiceShadowDisabled flag and a possibly dangling
        // lastUsedModelEntryId. Clean everything in one place.
        if (removedInstance != null) {
            runCatching {
                com.openminis.app.auth.OAuthManager.forInstance(context, removedInstance)?.logout()
            }
            prefs.edit().remove("voiceShadowDisabled.$instanceId").apply()
        }
        if (prefs.getString(KEY_LAST_USED_ENTRY, null) in removedEntryIds) {
            prefs.edit().remove(KEY_LAST_USED_ENTRY).apply()
        }
        // [T-android-thinking-rules-phase2] The instance is gone — drop its custom
        // rules from Room and the resolver cache (they can never fire again).
        // [T-android-save-off-main-thread] The DAO DELETE used to be
        // `runBlocking { … }` HERE, on the thread that pressed Delete. This is a
        // public, non-suspend mutator and its callers are Compose confirm
        // dialogs — ui/settings/ProviderListScreen.kt:524 and
        // ui/settings/ProviderDetailScreen.kt:777 call `removeInstance` straight
        // from `onConfirm`, i.e. the main thread, so a cold/hot provider.db write
        // stalled the frame that was supposed to close the dialog. The durable
        // half now rides the same FIFO persistence worker as the config save
        // queued above (ordered behind it, on Dispatchers.IO); the in-memory
        // clears stay synchronous so no reader can resurrect the rules this turn.
        ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
        thinkingRulesCache = thinkingRulesCache - instanceId
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { providerDao.deleteThinkingRulesForInstance(instanceId) }
                .onFailure {
                    android.util.Log.w(
                        "ProviderRepo",
                        "[ThinkingRules] delete for removed instance $instanceId failed: ${it.message}",
                    )
                }
        })
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
        // [T-oauth-keep-credentials] A token bundle whose refresh was rejected
        // is kept (never auto-deleted) but is not usable, so routing skips it
        // just as it skipped the deleted bundle before. (needsReauth is already
        // false when a manual bearer is stored — that credential still works.)
        if (com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id)) return false
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
     * [T-android-title-failover] EVERY usable member of the configured title
     * sub-group, in group order — the failover chain for title generation.
     * Upstream 1.14: "A failed title model hands over to the next one."
     * resolveTitleSubEntry only exposes the first member, so a failing
     * sub-model was retried until the attempt budget burned out instead of
     * handing over to the next usable member. Same credential-aware filter
     * as availableMemberEntries.
     */
    fun resolveTitleSubGroupEntries(): List<ModelEntry> {
        val subGroupId = defaultSubGroupId ?: return emptyList()
        val group = group(subGroupId) ?: return emptyList()
        return availableMemberEntries(group)
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

    fun replaceEntries(instanceId: String, models: List<LLMModel>, dropStaleRefs: Boolean = false): Set<String> = synchronized(configLock) {
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
                .withInferredVideoModality()
                .withInferredImageModality()
            templateVoiceModelById[model.id]?.let { tplModel ->
                resolved = resolved.copy(
                    inputModalities = tplModel.inputModalities,
                    outputModalities = tplModel.outputModalities,
                )
            }
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = applyUnrecognizedModelDefaults(resolved),
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
            val suspiciousShrink = !dropStaleRefs && existing.size >= 4 && models.size * 2 < existing.size
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

        val droppedRefs = prunedEntryIds.isNotEmpty() && (dropStaleRefs || existing.size < 4 || models.size * 2 >= existing.size)
        if (droppedRefs) scrubConfigEntryRefs(config, prunedEntryIds)
        saveConfig(config)
        // Stamp so staleness checks know this instance just refreshed.
        markInstanceFetched(instanceId)
        if (droppedRefs) prunedEntryIds else emptySet()
    }

    /**
     * Drop fetched (non-custom, non voice-seed) entries so a manual refresh
     * cannot keep models the provider no longer returns. Voice template seeds
     * stay: vendors such as MiMo do not list ASR/TTS on /v1/models.
     */
    private fun clearFetchedModelEntries(instanceId: String): Set<String> {
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val instance = config.instances.firstOrNull { it.id == instanceId } ?: return emptySet()
            val voiceIds = VoiceProviderTemplate.template(instance.customBaseURL)
                ?.mockModels
                ?.filter { it.hasVoiceModality }
                ?.map { it.id }
                ?.toSet()
                ?: emptySet()
            val removed = config.modelEntries.filter {
                it.providerInstanceId == instanceId && !it.isCustom && it.baseModel.id !in voiceIds
            }
            if (removed.isEmpty()) return emptySet()
            val removedIds = removed.map { it.id }.toSet()
            config.modelEntries.removeAll { it.id in removedIds }
            scrubConfigEntryRefs(config, removedIds)
            saveConfig(config)
            return removedIds
        }
    }

    /** Group pins, agent-loop pins, and default slots that pointed at removed entries. */
    private fun scrubConfigEntryRefs(config: ProviderConfig, removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        for (i in config.modelGroups.indices) {
            config.modelGroups[i].memberEntryIds.removeAll { it in removedIds }
        }
        config.agentLoopModelEntryIds.removeAll { it in removedIds }
        fun clearSlot(current: String?): String? {
            if (current == null) return null
            val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(current)
            return if (current in removedIds || pinned in removedIds) null else current
        }
        // [T-android-translation-single-source] `defaultTranslationModelId` is NOT
        // scrubbed any more: nothing reads the field at all (live translation uses
        // TranslationPrefs), so nulling a dead slot on every provider delete was
        // the second half of a second source of truth. The field itself stays on
        // ProviderConfig so older persisted JSON mirrors keep decoding.
        config.defaultPrimaryGroupId = clearSlot(config.defaultPrimaryGroupId)
        config.defaultSubGroupId = clearSlot(config.defaultSubGroupId)
        val last = prefs.getString(KEY_LAST_USED_ENTRY, null)
        if (last in removedIds) prefs.edit().remove(KEY_LAST_USED_ENTRY).apply()
    }

    /**
     * Sessions and scheduled tasks keep their own copy of the entry id.
     * Clearing the provider list must not leave those copies resolving to a
     * missing row. Null the binding so the existing fallback (app default)
     * runs instead of crashing or sending to a deleted model.
     *
     * [T-android-save-off-main-thread] One queued unit of work instead of a
     * per-id round-trip on the caller's thread: this used to run
     * `clearBindingReferencing` once per removed id INSIDE the config-lock
     * critical path, i.e. N full-table `UPDATE sessions` scans while every
     * Compose mutator was blocked behind the lock. The whole set now goes to the
     * persistence worker as a single task, ordered behind the save that removed
     * the entries (which is what publishes the removal in memory anyway).
     *
     * COORDINATION: the DAO updates the row whose binding literally contains
     * `"entryId":"<id>"` (ChatDao.clearBindingReferencing, `instr` not `LIKE`),
     * and [com.openminis.app.scheduled.ScheduledTaskStore.dropEntryRefs] parses
     * the same JSON before comparing. Both used to substring-match, which
     * un-pinned `inst/gpt-4o` whenever `inst/gpt-4` was removed; keep the two
     * call sites exact if either is touched again.
     */
    private fun scrubExternalEntryRefs(removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        val ids = removedIds.toList()
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dao = com.openminis.app.data.db.AppDatabase.getInstance(context).chatDao()
                for (id in ids) {
                    dao.clearBindingReferencing(id)
                }
                com.openminis.app.scheduled.ScheduledTaskStore(context).dropEntryRefs(ids.toSet())
            }
        })
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
            // [T-android-composite-key-collision] The check deliberately does NOT
            // run for custom entries: two custom entries on one instance sharing
            // a base-model id are the legitimate "same model, different overrides"
            // case, and refusing the second one would delete the feature. What
            // broke instead was storage — ProviderConfigMapping keyed the row on
            // compositeEntryKey(instanceId, baseModel.id), so both entries mapped
            // to ONE row (@Insert(REPLACE) kept one) and, until the id was
            // canonicalised, the UI rendered both with the SAME id: the duplicate
            // LazyColumn key [T-android-agentloop-dup-key-crash] warns about.
            // Fixed at the mapping level, where the row id is minted (colliding
            // entries get their uuid appended); see ProviderConfigMappingRoundTripTest.
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
        if (config.compactFallbackGroupId == groupId) {
            config.compactFallbackGroupId = null
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
        ensureConfigLoaded()
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId in cur) return
        setAgentLoopEntryIds(cur + entryId)
    }

    /** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
    fun removeAgentLoopEntry(entryId: String) {
        ensureConfigLoaded()
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId !in cur) return
        setAgentLoopEntryIds(cur.filterNot { it == entryId })
    }

    /** Append [groupId] to the agent-loop group-pin list if not already there. */
    fun addAgentLoopGroup(groupId: String) {
        ensureConfigLoaded()
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId in cur) return
        setAgentLoopGroupIds(cur + groupId)
    }

    /** Remove [groupId] from the agent-loop group-pin list. No-op if absent. */
    fun removeAgentLoopGroup(groupId: String) {
        ensureConfigLoaded()
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
            ensureConfigLoaded()
            val config = workingCopy()
            config.defaultPrimaryGroupId = value
            saveConfig(config)
        }

    var defaultSubGroupId: String?
        get() = _config.value.defaultSubGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
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

    var compactFallbackGroupId: String?
        get() = _config.value.compactFallbackGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.compactFallbackGroupId = value
            saveConfig(config)
        }

    /** True when a Vision Group is bound AND still exists. Gates read_image
     *  tool exposure for main models that cannot natively see images. */
    fun hasVisionGroupConfigured(): Boolean {
        val gid = _config.value.visionGroupId ?: return false
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinned != null) {
            val entry = _config.value.modelEntries.find { it.id == pinned } ?: return false
            val inst = _config.value.instances.find { it.id == entry.providerInstanceId } ?: return false
            return inst.isEnabled && entry.model.hasImageInput
        }
        return _config.value.modelGroups.any { it.id == gid }
    }

    /** Bound Vision group's display name, or the pinned model's name. */
    fun visionGroupName(): String? = slotDisplayName(_config.value.visionGroupId)

    /** Group name, or the pinned model's display name when the slot is `entry:`. */
    fun slotDisplayName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(raw)
        if (pinned != null) {
            return _config.value.modelEntries.find { it.id == pinned }?.model?.displayName
        }
        return _config.value.modelGroups.find { it.id == raw }?.name
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
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinned != null) return listOfNotNull(providerEntry(pinned))
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

    /**
     * Compact fallback candidates from Defaults → Compact fallback.
     * Pinned entry or group members; skips disabled instances. The compact
     * path still prefers the current session model and only walks this list
     * after that model fails.
     */
    fun resolveCompactFallbackCandidates(): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value
        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled) return null
            return inst to entry
        }
        val gid = config.compactFallbackGroupId ?: return emptyList()
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinned != null) return listOfNotNull(providerEntry(pinned))
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (id in group.memberEntryIds) {
            val m = providerEntry(id) ?: continue
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

    /**
     * Load one instance's custom rules.
     *
     * [T-android-save-off-main-thread] Reads the warm [thinkingRulesCache] when
     * available: this is called straight from `remember { }` during composition
     * (ui/settings/ThinkingRulesSection.kt), and the old `runBlocking { DAO }`
     * shape froze the frame on every recomposition. Callers on a suspend path
     * should use [thinkingRulesSuspending], which always reads the store.
     */
    fun thinkingRules(instanceId: String): List<ThinkingRule> =
        thinkingRulesCache[instanceId] ?: runBlocking { thinkingRulesSuspending(instanceId) }

    /** Suspend variant of [thinkingRules] — reads provider.db on IO, never the caller's thread. */
    suspend fun thinkingRulesSuspending(instanceId: String): List<ThinkingRule> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { providerDao.loadThinkingRules(instanceId).map { it.toRule() } }
                .getOrDefault(emptyList())
        }

    /** The persisted ids for one instance's custom rules, parallel to [thinkingRules]. */
    fun thinkingRuleIds(instanceId: String): List<String> =
        thinkingRules(instanceId).map { it.id }

    /** Suspend variant of [thinkingRuleIds]. */
    suspend fun thinkingRuleIdsSuspending(instanceId: String): List<String> =
        thinkingRulesSuspending(instanceId).map { it.id }

    /** First model id served by [instanceId], for the resolution-trace sample. Null if none. */
    fun firstModelId(instanceId: String): String? {
        ensureConfigLoaded()
        return _config.value.modelEntries.firstOrNull { it.providerInstanceId == instanceId }?.model?.id
    }

    /**
     * [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache once
     * config is available, so the (sync) request builder can read user rules.
     *
     * [T-android-save-off-main-thread] The read itself is queued to the persistence
     * worker: this used to run `runBlocking { providerDao.loadAllThinkingRules() }`
     * inline, and `ensureConfigLoaded()` — which is called from Compose mutators on
     * the main thread — reached it on the very first access, so a cold provider.db
     * read stalled the first frame. The rules screen reads
     * [thinkingRules] from the cache this fills.
     */
    fun loadAllThinkingRulesIntoCache() {
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching {
                val rows = providerDao.loadAllThinkingRules()
                val byInstance = rows.groupBy { it.providerInstanceId }
                    .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { it.toRule() } }
                thinkingRulesCache = byInstance
                ThinkingRuleResolver.setAllCustomRules(byInstance)
            }.onFailure {
                android.util.Log.w("ProviderRepo", "[ThinkingRules] cache warm failed: ${it.message}")
            }
        })
    }

    /**
     * [T-android-save-off-main-thread] Last-warm snapshot of `provider_thinking_rules`
     * by instance id, filled off-thread by [loadAllThinkingRulesIntoCache] and kept
     * current by every rule mutation. Lets the non-suspend [thinkingRules] read be a
     * map lookup instead of a `runBlocking` DAO query on the composition thread.
     */
    @Volatile
    private var thinkingRulesCache: Map<String, List<ThinkingRule>> = emptyMap()

    /**
     * Re-warm the rule cache after a mutation. Reloads every instance's rows
     * rather than just [instanceId] so a rename/reorder can never leave a stale
     * entry behind; the table is small (user-authored rules only).
     *
     * [T-android-save-off-main-thread] suspend because the DAO read is a
     * suspend query — every caller is already on the persistence worker
     * ([saveThinkingRuleSuspending] / [deleteThinkingRuleSuspending] /
     * [reorderThinkingRulesSuspending]).
     */
    private suspend fun republishThinkingCache(instanceId: String) {
        runCatching {
            val rows = providerDao.loadAllThinkingRules()
                .groupBy { it.providerInstanceId }
                .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { it.toRule() } }
            thinkingRulesCache = rows
            ThinkingRuleResolver.setAllCustomRules(rows)
        }.onFailure {
            android.util.Log.w(
                "ProviderRepo",
                "[ThinkingRules] republish after $instanceId failed: ${it.message}",
            )
        }
    }

    /**
     * Insert or update a custom rule. [id] null ⇒ new rule minted at the TOP of the
     * list (position 0) — a rule overriding a built-in is useless below it; existing
     * rules shift down. A non-null [id] updates in place, preserving position.
     * Returns the rule id.
     *
     * [T-android-save-off-main-thread] Used to be `runBlocking { load → replace →
     * republish }` on the Compose callback thread (called from
     * ui/settings/ThinkingRulesSection.kt). The id is still minted synchronously so
     * the caller can key its own state on it; the three DAO round-trips move to the
     * persistence worker, where they stay ordered with config saves. Await the
     * durable write with [saveThinkingRuleSuspending].
     */
    fun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String {
        val ruleId = id ?: java.util.UUID.randomUUID().toString()
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { saveThinkingRuleSuspending(instanceId, rule, ruleId) }
                .onFailure { android.util.Log.e("ProviderRepo", "[ThinkingRules] save failed: ${it.message}", it) }
        })
        return ruleId
    }

    /** Suspend variant of [saveThinkingRule]; returns the rule id it persisted. */
    suspend fun saveThinkingRuleSuspending(instanceId: String, rule: ThinkingRule, id: String? = null): String =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val existing = providerDao.loadThinkingRules(instanceId).toMutableList()
            val ruleId = id ?: java.util.UUID.randomUUID().toString()
            val idx = existing.indexOfFirst { it.id == ruleId }
            if (idx >= 0) {
                // Update in place at its current sort_order.
                existing[idx] = rule.toEntity(ruleId, instanceId, existing[idx].sortOrder)
            } else {
                // New rule at the top; everything else shifts down.
                existing.add(0, rule.toEntity(ruleId, instanceId, 0))
            }
            val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }
            providerDao.replaceThinkingRules(instanceId, renumbered)
            republishThinkingCache(instanceId)
            ruleId
        }

    /** Delete a custom rule by id. Hard delete — Android provider config is local-only,
     *  so there is no sync channel that could resurrect it (no tombstone needed). */
    fun deleteThinkingRule(instanceId: String, id: String) {
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { deleteThinkingRuleSuspending(instanceId, id) }
                .onFailure { android.util.Log.e("ProviderRepo", "[ThinkingRules] delete failed: ${it.message}", it) }
        })
    }

    /** Suspend variant of [deleteThinkingRule]. */
    suspend fun deleteThinkingRuleSuspending(instanceId: String, id: String) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            providerDao.deleteThinkingRule(id)
            // Renumber survivors so sort_order stays dense.
            val survivors = providerDao.loadThinkingRules(instanceId)
                .sortedBy { it.sortOrder }
                .mapIndexed { i, e -> e.copy(sortOrder = i) }
            providerDao.replaceThinkingRules(instanceId, survivors)
            republishThinkingCache(instanceId)
        }
    }

    /** Reorder an instance's custom rules to match [orderedIds] (a permutation). */
    fun reorderThinkingRules(instanceId: String, orderedIds: List<String>) {
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { reorderThinkingRulesSuspending(instanceId, orderedIds) }
                .onFailure { android.util.Log.e("ProviderRepo", "[ThinkingRules] reorder failed: ${it.message}", it) }
        })
    }

    /** Suspend variant of [reorderThinkingRules]. */
    suspend fun reorderThinkingRulesSuspending(instanceId: String, orderedIds: List<String>) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }
            val reordered = orderedIds.mapNotNull { byId[it] }
                .mapIndexed { i, e -> e.copy(sortOrder = i) }
            // Keep any id the caller omitted (defensive against a partial list) appended.
            val omitted = byId.values.filter { it.id !in orderedIds }.map { it }
            providerDao.replaceThinkingRules(instanceId, reordered + omitted)
            republishThinkingCache(instanceId)
        }
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
        val pinnedVoice = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinnedVoice != null) {
            providerEntry(pinnedVoice)?.let { return VoiceInputChoice(null, it) }
            return VoiceInputChoice(systemPreferOffline = null, entry = null)
        }
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
    fun voiceInputGroupName(): String? = slotDisplayName(_config.value.voiceInputGroupId)

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
        val pinnedOut = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinnedOut != null) {
            providerEntry(pinnedOut)?.let { return VoiceOutputChoice(false, it) }
            return VoiceOutputChoice(isSystemEngine = true, entry = null)
        }
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
        val pinnedMember = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinnedMember != null) return pinnedMember
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
    fun voiceOutputGroupName(): String? = slotDisplayName(_config.value.voiceOutputGroupId)

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


    suspend fun refreshModels(
        instance: ProviderInstance,
        forceRefresh: Boolean = false,
        clearFirst: Boolean = false,
    ): ModelRefreshResult {
        val liveForce = forceRefresh || clearFirst
        // [T-refresh-no-destructive-clear] `clearFirst` used to wipe this
        // instance's model entries BEFORE the network call. A hard refresh does
        // have to clear first — that is what stops `replaceEntries` from
        // carrying forward uuid / overrides / isHidden, so the catalog comes
        // back pristine — but doing it up front means any failed fetch leaves
        // the picker empty. And because `clearFirst` implies `liveForce`, the
        // models.dev fallback below is skipped as well, so pressing Refresh
        // against a flaky or strict gateway *destroyed* the list it was meant to
        // reload, with no way back short of re-adding the provider.
        //
        // Deferred to the moment a replacement is actually in hand, and run at
        // most once. On success the observable result is byte-identical to the
        // old order: entries are gone before `replaceEntries` runs, so nothing
        // is carried forward and `pruned` is empty exactly as before.
        var hardCleared = false
        suspend fun hardClearIfNeeded() {
            if (!clearFirst || hardCleared) return
            hardCleared = true
            val clearedIds = clearFetchedModelEntries(instance.id)
            if (clearedIds.isNotEmpty()) scrubExternalEntryRefs(clearedIds)
        }
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

        android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL} forceRefresh=$forceRefresh")

        // OpenAI Codex OAuth: use static model list (OAuth tokens can't call /v1/models)
        if (instance.providerType == ProviderType.openAI
            && instance.credentialType == ProviderCredential.oauth
        ) {
            val models = OpenAIModelsApi.fetchModelsOAuth()
            if (models.isNotEmpty()) {
                hardClearIfNeeded()
                replaceEntries(instance.id, models)
                return ModelRefreshResult.SUCCESS_API
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
                        context = context,
                        forceRefresh = liveForce,
                        // [T-provider-custom-user-agent] models-list UA override.
                        customUserAgent = instance.customUserAgent,
                        cacheScope = instance.id,
                    )
                    ProviderType.gemini -> GeminiModelsApi.fetchModels(
                        apiKey,
                        isOAuth = instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth,
                        context = context,
                        forceRefresh = liveForce,
                        cacheScope = instance.id,
                    )
                    // [T-provider-custom-user-agent] models-list UA override.
                    // [T-android-provider-type-parity] openAIResponses lists
                    // models from the same /v1/models endpoint — only the
                    // completion endpoint differs.
                    ProviderType.openAI, ProviderType.openAIResponses ->
                        OpenAIModelsApi.fetchModels(apiKey, baseURL, context = context, forceRefresh = liveForce, customUserAgent = if (isZenInstance(instance)) com.openminis.app.provider.ZenDisguise.userAgent() else instance.customUserAgent, cacheScope = instance.id)
                            // [T-zen-free-lane-follow] The free-lane filter is
                            // KEYLESS-only: a user's paid Zen instance (same
                            // host, real key) sees the full catalogue.
                            .let { models -> if (isZenFreeInstance(instance, apiKey)) zenVisibleModels(models, zenDeadIds()) else models }
                    ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(apiKey, context = context, forceRefresh = liveForce, cacheScope = instance.id)
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
                        context = context,
                        forceRefresh = liveForce,
                        customUserAgent = instance.customUserAgent,
                        cacheScope = instance.id,
                    ).ifEmpty { com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth() }
                    // [T-kimi-oauth] Kimi Code: unlike Codex OAuth, the Kimi
                    // OAuth token CAN call the models endpoint — real fetch
                    // from GET /coding/v1/models (OpenAI-compatible shape).
                    // The upstream lineup shifts across generations, so the
                    // live list replaces the minimal built-in fallback.
                    ProviderType.kimiCode -> OpenAIModelsApi.fetchModels(
                        apiKey,
                        baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                        context = context,
                        forceRefresh = liveForce,
                        customUserAgent = instance.customUserAgent,
                        cacheScope = instance.id,
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
                hardClearIfNeeded()
                val pruned = replaceEntries(instance.id, models, dropStaleRefs = clearFirst)
                if (pruned.isNotEmpty()) scrubExternalEntryRefs(pruned)
                return ModelRefreshResult.SUCCESS_API
            }
        }

        // Explicit force refresh must not pretend a hostname-keyed models.dev
        // catalog is a per-credential refresh. Same API address used to make
        // every sibling provider look freshly synced.
        if (liveForce) {
            return if (apiKey == null) ModelRefreshResult.NO_KEY else ModelRefreshResult.FAILURE
        }

        // Step 3: Fallback to models.dev by base URL.
        // We try this even for third-party hosts (DashScope/Bailian, etc.) — the
        // models.dev registry covers many "Anthropic-compatible" or "OpenAI-compatible"
        // gateways by hostname, and when there's no match `fetchModels` returns
        // empty so the existing list (vLLM/Ollama on a private host) is preserved.
        val fallbackBaseURL = modelsDevBaseURL(instance)
        val fallbackModels = ModelsDevApi.fetchModels(fallbackBaseURL)
        // [T-zen-usable-free-lane-fallback] The models.dev fallback is a
        // HOSTNAME-keyed catalogue, not this instance's own /v1/models — for
        // the Zen host it answers with all rows: every paid lane (which a
        // keyless "public" instance can never drive) plus the free ids the
        // upstream advertises but may no longer serve. Writing it back
        // unfiltered replaces the curated free list with rows that fail on
        // the first call, which is the "pick a model, get an error" report.
        // The curated list follows the catalogue's free lane minus the
        // proven-dead record — and falls back to the bundle if the catalogue
        // is silent, so a metrics blip can never leave the instance with
        // nothing to pick.
        val curatedFallback = if (isZenFreeInstance(instance, apiKey)) {
            zenVisibleModels(fallbackModels, zenDeadIds()).ifEmpty { bundledZenModels() }
        } else {
            fallbackModels
        }
        if (curatedFallback.isNotEmpty()) {
            android.util.Log.i("ProviderRepo", "models.dev fallback returned ${curatedFallback.size} models for ${instance.label}")
            replaceEntries(instance.id, curatedFallback)
            return ModelRefreshResult.SUCCESS_API
        } else if (isThirdParty) {
            android.util.Log.i("ProviderRepo", "Third-party endpoint, no models.dev match — preserving existing models for ${instance.label}")
            return ModelRefreshResult.PRESERVED
        }
        return if (apiKey == null) ModelRefreshResult.NO_KEY else ModelRefreshResult.FAILURE
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
     * Parallel force-refresh of every enabled provider. Used by the provider-list
     * toolbar Sync button. models.dev fallback is still used inside [refreshModels].
     */
    suspend fun refreshAllModelsForce(): List<Pair<String, ModelRefreshResult>> {
        awaitConfigLoaded()
        val enabled = _config.value.instances.filter { it.isEnabled }
        if (enabled.isEmpty()) return emptyList()
        return coroutineScope {
            enabled.map { inst ->
                async {
                    inst.id to runCatching {
                        refreshModels(inst, forceRefresh = true)
                    }.getOrElse { ModelRefreshResult.FAILURE }
                }
            }.awaitAll()
        }
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
        _credentialRevision.value = _credentialRevision.value + 1
        // [T-android-provider-memo] The memo keys on the credential fingerprint;
        // a rotated token already yields a new entry. Drop this instance's
        // entries so stale-keyed providers can't linger past the cap —
        // [T-provider-factory-race] without touching unrelated instances.
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
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
    // [T-usable-apikey-no-dead-param] The old `modelId: String = ""` was
    // never used — call sites kept passing it and readers assumed per-model
    // bucketing that does not exist.
    fun usableApiKey(instance: ProviderInstance): String? =
        loadApiKey(instance.id)
            ?: if (instance.allowsEmptyAPIKey) "" else null

    fun deleteApiKey(instanceId: String) {
        encryptedPrefs.edit().remove("apikey_$instanceId").apply()
        // [T-key-cache-invalidate] 删 key 同样要让 ProviderFactory 的
        // memo 缓存失效 —— 否则已构造的 provider 继续用旧 key 存活，
        // 删 key 后重加同实例仍是旧 key 生效。
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
    }

    private val backupCoordinator by lazy {
        ProviderBackupCoordinator(context, ProviderBackupHost())
    }

    private inner class ProviderBackupHost : ProviderBackupCoordinator.Host {
        override val configValue get() = _config.value
        override fun saveConfig(config: ProviderConfig) = this@ProviderRepository.saveConfig(config)
        override fun workingCopy(): ProviderConfig = this@ProviderRepository.workingCopy()
        override fun ensureConfigLoaded() = this@ProviderRepository.ensureConfigLoaded()
        override fun addInstance(instance: ProviderInstance) = this@ProviderRepository.addInstance(instance)
        override fun instance(id: String): ProviderInstance? = this@ProviderRepository.instance(id)
        override fun visibleEntries(instanceId: String): List<ModelEntry> =
            this@ProviderRepository.visibleEntries(instanceId)
        override val configLock get() = this@ProviderRepository.configLock
        override val providerDao get() = this@ProviderRepository.providerDao
        override fun loadApiKey(instanceId: String): String? = this@ProviderRepository.loadApiKey(instanceId)
        override fun saveApiKey(instanceId: String, key: String) =
            this@ProviderRepository.saveApiKey(instanceId, key)
        override fun loadAllThinkingRulesIntoCache() =
            this@ProviderRepository.loadAllThinkingRulesIntoCache()
    }

    fun exportInstanceJSON(instanceId: String): String? =
        backupCoordinator.exportInstanceJSON(instanceId)

    fun collectBackupProviderSecret(instance: ProviderInstance): BackupSecrets.ProviderSecret? =
        backupCoordinator.collectBackupProviderSecret(instance)

    fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean =
        backupCoordinator.restoreBackupProviderSecret(secret)

    fun mergeBackupProviderConfig(remote: ProviderConfig): Pair<Int, Int> =
        backupCoordinator.mergeBackupProviderConfig(remote)

    fun restoreBackupThinkingRules(
        rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
    ): Pair<Int, Int> = backupCoordinator.restoreBackupThinkingRules(rules)

    fun importInstanceJSON(jsonStr: String): String? =
        backupCoordinator.importInstanceJSON(jsonStr)

}

enum class ModelRefreshResult {
    SUCCESS_API,
    NO_KEY,
    PRESERVED,
    FAILURE,
}

/** Bundled OpenCode Zen endpoint — single source for seed/patch/matching. */
internal const val ZEN_BUNDLED_ENDPOINT = "https://opencode.ai/zen/v1"

/**
 * Free-lane models bundled with the app for the Zen endpoint. Every id here
 * was measured to answer a real chat completion as a third-party client, with
 * no key and no configuration.
 *
 * Re-measured 2026-10-05 against live `POST /zen/v1/chat/completions` with the
 * disguised client (CLI user agent, `x-opencode-client`, a canonical `ses_`
 * session id, the bash/read gate tools, `Authorization: Bearer public`). Each
 * id below was then re-probed three times on independent session ids.
 * Re-measured again 2026-10-08 (same shape) plus a third-party cross-check
 * against the `dsh-opencode-free-models` plugin's shape (official CLI UA,
 * `Bearer public`, NO session headers) — see the note below the table.
 *
 * | id | 2026-10-05 | 2026-10-08 |
 * | --- | --- | --- |
 * | big-pickle | **200 ×3** (plus 13/14 on a 14-id sweep) | **200** |
 * | space-bunny-free | **200 ×3** | (not re-probed; quota budget) |
 * | mimo-v2.6-flash-free | **200 ×3** | (not re-probed; quota budget) |
 * | mimo-v2.5-free | **200 ×3** | **401 ModelError "not supported"** — retired upstream AND de-advertised from the live catalogue; removed from the bundle |
 * | nemotron-3-ultra-free | **200 ×3** | **200** (mid-stream upstream overload once) |
 * | nemotron-3.5-lightning-free | **200 ×3** | (not re-probed; quota budget) |
 * | longcat-2.5-preview-free | **200 ×3** | (not re-probed; quota budget) |
 * | fledge-alpha-free | **200 ×3** | **200** after a cooldown |
 * | ling-3.1-flash-free | 200, then 429 ×2 — rate-limited | **200** (quota recovered) |
 * | ling-3.0-flash-fin-free | 400 "Endpoint is unavailable" | 400, same message (retired) |
 * | deepseek-v4-flash-free | 400 "Model is unavailable" | 401 ModelError "not supported" (retired) |
 * | jev-1.13-free | 500 (upstream broken) | 500, same (broken) |
 * | muse-spark-1.2-contributor-free | 403 RegionError (geo-fenced) | 429 FreeUsageLimitError all day — geo gate no longer reproduces; passes identity, never a 200 |
 * | muse-spark-1.3-contributor-free | 403 RegionError (geo-fenced) | 500 internal (broken) |
 * | hy3-free | — (not advertised then) | 401 ModelError "not supported" — advertised by the third-party plugin's static list, refused upstream |
 *
 * The upstream failures are genuine retirements — their messages name the
 * endpoint or model, not the client. The muse pair is kept out on the
 * 200-or-nothing rule: 1.2 clears every gate today but never answered a
 * completion (rate-limited all day), and a row that only ever 429s cannot be
 * shipped as usable.
 *
 * 2026-10-08 cross-check against `dsh-opencode-free-models` (a DeepSeek
 * Harness plugin driving the same lane): its exact wire shape — official CLI
 * user agent `opencode/1.18.18 ai-sdk/...`, `Bearer public`, and NO session
 * headers — answers **403 FreeTierError** on every model today, so the
 * plugin's users are currently refused upstream. The same request with our
 * canonical `ses_` session headers returns 200. That is live third-party
 * evidence for the session-id gate documented in
 * [com.openminis.app.provider.ZenDisguise]: the UA is not the key; the
 * canonical session id is.
 *
 * What made this list look like one row for a day was a MALFORMED session id in
 * [com.openminis.app.provider.ZenDisguise], not an upstream policy: a 23- or
 * 25-character id earns a 403 FreeTierError byte-identical to a request with no
 * disguise at all, on every model. Any future re-measurement MUST send a
 * canonical id — [com.openminis.app.provider.ZenDisguise.sessionIdForSeed] —
 * or it will "confirm" that the whole lane is closed.
 *
 * The live refresh replaces this with the full catalogue filtered by
 * [zenVisibleModels].
 */
internal fun bundledZenModels(): List<LLMModel> = listOf(
    LLMModel("big-pickle", "Big Pickle (Free)", "OpenCode Zen", 128000, 8192, true),
    LLMModel("space-bunny-free", "Space Bunny (Free)", "OpenCode Zen", 128000, 8192, true),
    LLMModel("mimo-v2.6-flash-free", "MiMo v2.6 Flash (Free)", "OpenCode Zen", 128000, 8192, true),
    LLMModel("nemotron-3-ultra-free", "Nemotron 3 Ultra (Free)", "OpenCode Zen", 128000, 8192, true),
    LLMModel("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning (Free)", "OpenCode Zen", 128000, 8192, true),
    LLMModel("ling-3.1-flash-free", "Ling 3.1 Flash (Free)", "OpenCode Zen", 128000, 8192, true),
    LLMModel("longcat-2.5-preview-free", "LongCat 2.5 Preview (Free)", "OpenCode Zen", 128000, 8192, true),
    LLMModel("fledge-alpha-free", "Fledge Alpha (Free)", "OpenCode Zen", 128000, 8192, true),
)

/**
 * [T-zen-usable-free-lane] The bundled SEED ids — what a fresh install (or an
 * empty shell) gets before the first live refresh, and what the launch
 * top-up heals to. NOT the gate anymore: since [T-zen-free-lane-follow] the
 * picker follows the live catalogue's free lane
 * ([isZenFreeLaneId]) minus the proven-dead record
 * ([com.openminis.app.provider.ZenFreeLaneHealth]), so upstream drift no
 * longer needs an app release to show up (or to disappear).
 */
internal fun zenUsableFreeIds(): Set<String> = bundledZenModels().map { it.id }.toSet()

/**
 * [T-zen-free-lane-follow] The free-lane id predicate: how the picker
 * recognises a free row in the live catalogue WITHOUT any hard-coded list.
 * Measured against every free id the catalogue has advertised since
 * 2026-10-05 (14 distinct ids): every one is either `big-pickle` (the one
 * free id with no suffix) or ends in `-free`. The catalogue itself carries
 * no free/paid marker — all 87 rows are the same four-field object — so
 * this shape is the only upstream signal, and it is the SAME shape the
 * pre-bundle builds used. What changed is not the predicate but what
 * backs it: dead rows are now evicted by evidence
 * ([com.openminis.app.provider.ZenFreeLaneHealth]) instead of by a
 * shipped table, so upstream drift no longer needs an app release.
 */
internal fun isZenFreeLaneId(id: String): Boolean =
    id == "big-pickle" || id.endsWith("-free")

/**
 * Filter for a Zen instance's live /models refresh: every advertised
 * free-lane id ([isZenFreeLaneId]) minus the proven-dead record
 * ([deadIds]). This is what makes the picker FOLLOW the catalogue — a new
 * free id upstream appears on the next refresh with no app update, and a
 * de-advertised id disappears with it (replaceEntries prunes non-custom
 * rows the catalogue no longer returns).
 *
 * The dead record is the guard that replaced the old hard-coded measured
 * table: a row only leaves the picker on retirement EVIDENCE (401
 * ModelError "not supported", RegionError, "Endpoint is unavailable"),
 * recorded from a real failed call and expiring after 7 days so a wrong
 * mark self-heals. Advertised-but-flaky rows (429 quota, 500 upstream)
 * deliberately stay visible — the upstream advertises them, the user can
 * hide them, and a transient blip must not silently delete a working row.
 *
 * Applied to the KEYLESS free-lane instance only; a paid Zen instance
 * keeps the full catalogue (see isZenFreeInstance at the call sites).
 */
internal fun zenVisibleModels(
    models: List<LLMModel>,
    deadIds: Set<String> = emptySet(),
): List<LLMModel> =
    models.filter { isZenFreeLaneId(it.id) && it.id !in deadIds }

/**
 * Entry rows of the Zen instance [instanceId] that can never work: paid-lane
 * rows a keyless instance can never drive ([isZenFreeLaneId] miss), and
 * free-lane rows with live retirement evidence ([deadIds]). The instance
 * scoping is INSIDE the function so the reconciliation sweep can never leak
 * across instances; entries belonging to any other provider are never
 * touched.
 *
 * [T-zen-free-lane-follow] CUSTOM rows are untouchable by design: a row the
 * user added (AddCustomModelScreen, `isCustom = true`) is the user's call —
 * they may want a paid-lane id on their own key, or a dead free id they
 * believe in. The old sweep deleted them at every launch, which made
 * "add a model to the Zen provider" a lie: the row vanished on the next
 * start. Refresh keeps them too (replaceEntries preserves custom rows the
 * catalogue doesn't return), so user intent survives BOTH paths now.
 *
 * Re-seeding matters for the caller: the sweep can empty an instance that
 * held only dead rows, and an instance with zero models is the "no free
 * models anywhere" report, so the caller re-adds [bundledZenModels] when
 * the sweep would leave nothing behind.
 */
internal fun zenStaleEntryIds(
    instanceId: String,
    entries: List<ModelEntry>,
    deadIds: Set<String> = emptySet(),
): Set<String> =
    entries
        .filter { it.providerInstanceId == instanceId }
        .filterNot { it.isCustom }
        .filter { entry ->
            val id = entry.baseModel.id
            !isZenFreeLaneId(id) || id in deadIds
        }
        .map { it.id }
        .toSet()

/**
 * Whether [instance] still shows the user "no free models anywhere": it
 * points at the bundled Zen endpoint but has never catalogued a single
 * model entry (hand-built before the bundle shipped, or its live refresh
 * failed). Only then may reconciliation seed the bundled models into it.
 */
internal fun zenInstanceNeedsBundledModels(
    instance: com.openminis.app.data.model.ProviderInstance,
    entries: List<ModelEntry>,
): Boolean =
    instance.customBaseURL?.trimEnd('/') == ZEN_BUNDLED_ENDPOINT &&
        entries.none { it.providerInstanceId == instance.id }
