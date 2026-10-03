package com.openminis.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
enum class ProviderType(val displayName: String) {
    anthropic("Anthropic"),
    gemini("Google Gemini"),
    openAI("OpenAI"),
    openRouter("OpenRouter"),
    xAI("xAI (Grok)"),
    // [T-kimi-oauth] Kimi Code (Coding Plan) — RFC 8628 device-code OAuth,
    // OpenAI-compatible upstream at api.kimi.com/coding/v1. DB round-trip is
    // name-based (ProviderCredential.valueOf), so appending is migration-safe.
    kimiCode("Kimi Code"),

    /**
     * [T-copilot-provider] GitHub Copilot via device-flow OAuth. Chat is
     * OpenAI-compatible at api.githubcopilot.com, but auth is two-tier (see
     * CopilotOAuthManager), which is why it cannot reuse the API-key path.
     *
     * ⚠️ Unofficial: borrows the VS Code client identity, and GitHub's terms
     * treat proxying Copilot as grounds for account restriction. Gated behind
     * CopilotFeatureFlag (default off) and an explicit in-app risk notice.
     *
     * Appended last — DB round-trips are name-based (ProviderCredential.valueOf),
     * so adding a case is migration-safe.
     */
    githubCopilot("GitHub Copilot"),

    // [T-android-provider-type-parity] The cases below exist on iOS but were
    // missing here. They are declared so a cross-platform restore or sync can
    // DECODE them: without a case, kotlinx.serialization throws on the unknown
    // name and takes the whole provider_config.json with it — one iOS-only
    // provider silently cost the user every provider in the package,
    // credentials included.
    //
    // Declared but not offered: `addableProviderTypes` in AddProviderScreen is
    // a curated list, so these never appear as something the user can create
    // here; they arrive only from an iOS package or a newer build.

    /**
     * OpenAI Responses API (`/v1/responses`). iOS `openAIResponses`.
     *
     * Fully usable on Android: it is exactly "OpenAI, forced to the Responses
     * endpoint", which the existing OpenAI path already expresses through
     * `ProviderInstance.useResponsesAPI` (iOS spells the same thing
     * `forceResponsesAPI`). So a restored iOS instance of this type WORKS
     * rather than merely surviving the import.
     */
    openAIResponses("Responses API (v3)"),

    /**
     * iOS `antigravity`. Decode-only here — Android has no implementation, so
     * an instance restores and is visible but cannot serve a request.
     */
    antigravity("Antigravity"),

    /**
     * Sentinel for a provider type THIS build doesn't recognize — e.g. a newer
     * build's package naming a type added after this release. Decoding to this
     * preserves the instance (shown as unusable) instead of destroying the file
     * it arrived in. Mirrors iOS `ProviderType.unsupported`.
     *
     * Never write this back as an instance's type where the original string is
     * still available; it is a read-side fallback, not a real provider.
     */
    unsupported("Unsupported");

    /**
     * [T-android-provider-type-parity] True for types this build can decode and
     * display but cannot actually drive a request with. Callers that need a
     * working provider must check this rather than assuming every enum case is
     * usable.
     */
    val isUsable: Boolean
        get() = when (this) {
            // openAIResponses included: it routes through the OpenAI provider
            // with the Responses endpoint forced on.
            anthropic, gemini, openAI, openRouter, xAI, kimiCode, openAIResponses,
            githubCopilot -> true
            antigravity, unsupported -> false
        }

    val builtInModels: List<LLMModel>
        get() = when (this) {
            anthropic -> LLMModel.allAnthropic
            gemini -> LLMModel.allGemini
            openAI -> LLMModel.allOpenAI
            openRouter -> LLMModel.allOpenRouter
            xAI -> LLMModel.allXAI
            kimiCode -> LLMModel.allKimi
            // No built-in catalog for the decode-only types; models restored
            // alongside the instance still appear as custom entries.
            // [T-copilot-provider] Copilot's catalog is fetched live from
            // /models — a hardcoded list would offer models the signed-in
            // account may not be entitled to.
            githubCopilot, openAIResponses, antigravity, unsupported -> emptyList()
        }

    companion object {
        /**
         * [T-android-provider-type-parity] Decode a raw provider-type string,
         * never throwing: an unrecognized value maps to [unsupported].
         *
         * Mirrors iOS `ProviderType.decoded(_:)`. Use this anywhere a value
         * originates OUTSIDE this build — backup packages, sync payloads,
         * config files — so one unknown string can't fail the whole document.
         */
        fun decoded(raw: String): ProviderType =
            entries.firstOrNull { it.name == raw } ?: unsupported
    }
}

@Serializable
enum class ProviderCredential {
    apiKey,
    oauth,
}

@Serializable(with = ThinkingLevelSerializer::class)
enum class ThinkingLevel {
    // [T-android-thinking-level-arch] New cases MUST be appended at the end.
    // Kotlinx Serialization encodes enums by NAME string ("OFF"/"LOW"/...),
    // not declaration-order ordinal, so appending does not corrupt already-
    // persisted data. GPT-5.6 sol/terra reach ULTRA, luna reaches MAX.
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA;

    val isEnabled: Boolean get() = this != OFF

    val displayName: String
        get() = when (this) {
            OFF -> "Off"
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
            XHIGH -> "XHigh"   // was "Max"; the label now belongs to the new MAX case
            MAX -> "Max"
            ULTRA -> "Ultra"
        }

    /** Intensity ordinal used for intersection / clamp comparisons —
     *  follows the enum declaration order. */
    val rank: Int get() = ordinal

    companion object {
        /**
         * [T-android-thinking-level-arch] Safe deserialization fallback for a
         * level string that this app build doesn't recognize — e.g. an older
         * build reading a token a NEWER build persisted (Room DB / JSON mirror).
         * Mirrors the `runCatching { … }.getOrNull()` guard already used for
         * ImageEndpointMode (ProviderConfigMapping.kt). NEVER throws: an unknown
         * value clamps to the highest level THIS build knows (XHIGH) rather than
         * letting the caller handle an exception or drop the whole config. Use
         * this for every "read from persisted data" path.
         */
        fun decoded(raw: String): ThinkingLevel = parseOrNull(raw) ?: XHIGH

        /**
         * [T-android-restore-thinking-level] Case-insensitive parse, or null
         * when the value names no level. Accepts both spellings of a level:
         * Android's enum name (`"HIGH"`) and iOS's raw value (`"high"`).
         */
        fun parseOrNull(raw: String): ThinkingLevel? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * [T-android-restore-thinking-level] Reads a level in either spelling; writes
 * the enum name, exactly as the generated enum serializer did.
 *
 * Both platforms define the same seven levels, but iOS encodes them as its
 * `String` raw values (`"high"`) and Android by enum name (`"HIGH"`). With the
 * generated serializer, an iOS value was an unknown enum constant — and
 * `BackupFormat.json`'s `coerceInputValues` turns that into `null` on a
 * nullable property, silently. Every group level, per-model ceiling and sub
 * agent override in an iOS backup restored on Android as "no level".
 *
 * Read side only, on purpose. The same serializer writes the Room blobs
 * (ModelOverrides, SubAgentDefinition) and the ProviderConfig JSON mirror, so
 * changing what is written would make an older build misread them after a
 * downgrade. An unrecognized value (a level from a newer build) becomes XHIGH,
 * the clamp [ThinkingLevel.decoded] and iOS `ThinkingLevel.decoded` already
 * use; it can no longer be coerced to null, because this descriptor is a plain
 * string, not an enum.
 */
object ThinkingLevelSerializer : KSerializer<ThinkingLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openminis.app.data.model.ThinkingLevel", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ThinkingLevel) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ThinkingLevel = ThinkingLevel.decoded(decoder.decodeString())
}

@Serializable
enum class RoutingStrategy {
    fallback,
    loadBalance,
}

/**
 * [T-android-image-endpoint-mode] How an OpenAI-compatible provider routes
 * image-generation requests. Mirrors iOS `ImageEndpointMode`
 * (ProviderInstance.swift). Wire values (`images_generations` /
 * `chat_completions`) match iOS so JSON export/import interops cross-platform.
 *
 * - [auto]: try `/v1/images/generations` first; on a route-missing 4xx fall
 *   back to `/v1/chat/completions` and cache the working endpoint in
 *   `imageEndpointResolved` so the next call skips the probe.
 * - [imagesGenerations]: always `/v1/images/generations`.
 * - [chatCompletions]: always `/v1/chat/completions` (multimodal output) —
 *   i.e. the normal chat path, no special handling.
 */
@Serializable
enum class ImageEndpointMode {
    auto,
    @SerialName("images_generations") imagesGenerations,
    @SerialName("chat_completions") chatCompletions,
}

/**
 * Controls when fallback to the next model in the group is triggered.
 * - default: only on rate limiting (429) or server errors (5xx)
 * - always: on any error, including network errors, auth failures, etc.
 */
@Serializable
enum class FallbackStrategy {
    default,
    always,
}

@Serializable
data class ModelGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val memberEntryIds: MutableList<String> = mutableListOf(),
    var strategy: RoutingStrategy = RoutingStrategy.fallback,
    var fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    // T312: per-group session defaults. Mirrors iOS ModelGroup fields.
    // null = "no default override" — sessions bound to this group keep
    // OFF / unlimited unless the user opts in. Pre-T312 persisted JSON
    // simply lacks both keys; kotlinx.serialization fills them with the
    // declared defaults so older configs round-trip cleanly.
    var defaultThinkingLevel: ThinkingLevel? = null,
    var contextLimitTokens: Int? = null,
    // T-ctxslider 54ab8e93: persisted memory of the last user-selected context
    // limit, so toggling the "Limit Context Window" switch OFF→leave→ON
    // restores the previous value instead of snapping back to 128K. Default
    // null lets old JSON deserialize cleanly (kotlinx.serialization).
    var lastContextLimitTokens: Int? = null,
)

@Serializable
data class ProviderInstance(
    val id: String,
    var label: String,
    val providerType: ProviderType,
    val credentialType: ProviderCredential,
    var isEnabled: Boolean = true,
    /**
     * [T-android-provider-iso8601-wire] Epoch millis in memory, but serialized
     * as an ISO-8601 string. iOS `ProviderInstance.createdAt` is a `Date`
     * decoded with `.iso8601`, so a bare epoch number made iOS's
     * `importProviders` fail to decode the whole `provider_config.json` and
     * report `Unreadable: 1` for the Providers category. Reads both forms, so
     * the existing local JSON mirror and older Android backups still load.
     */
    @Serializable(with = com.openminis.app.backup.Iso8601MillisSerializer::class)
    val createdAt: Long = System.currentTimeMillis(),
    var customBaseURL: String? = null,
    var appendV1Suffix: Boolean = true,
    // [T-provider-custom-user-agent] Optional per-provider User-Agent
    // override. Some relay gateways only accept requests whose UA looks like
    // an official client (e.g. Claude Code); Minis' default UA gets rejected.
    // null/blank → keep the default UA; non-blank → replace the User-Agent
    // header on every outbound request (chat / models / responses) for this
    // instance. Only surfaced in the UI for custom-base OpenAI-/Anthropic-
    // compat providers. Field name matches iOS for cross-platform
    // export/import interop.
    var customUserAgent: String? = null,
    // OpenAI-only: when true, traffic goes through /v1/responses instead of
    // /v1/chat/completions. Mirrors iOS `ProviderType.openAIResponses` flag,
    // but modeled here as a switch on the instance so an existing OpenAI
    // provider can be re-pointed without changing its type.
    var useResponsesAPI: Boolean = false,
    // [T-android-image-endpoint-mode] User-selected image-generation routing
    // for OpenAI-compatible providers (see ImageEndpointMode). Defaults keep
    // old persisted JSON (which lacks both keys) round-tripping cleanly:
    // kotlinx.serialization fills them with these declared defaults.
    var imageEndpointMode: ImageEndpointMode = ImageEndpointMode.auto,
    // Cached probe result for `auto` mode: the endpoint that last worked, so
    // subsequent calls skip the /images/generations probe. Cleared whenever
    // the user forces a mode. null = not yet probed.
    var imageEndpointResolved: ImageEndpointMode? = null,
    // [T-android-azure-openai] Azure OpenAI mode. When true, OpenAIProvider
    // auths with the `api-key:` header (not `Authorization: Bearer`) and treats
    // the custom base URL as an Azure endpoint (the user pastes the full Azure
    // URL including `?api-version=…`; the request routes as
    // {endpoint}/openai/deployments/{model}/{path}). Orthogonal to the
    // Chat/Responses API-format choice — both work under Azure. Defaults false
    // so existing OpenAI instances are completely unaffected. Field name
    // matches iOS for cross-platform export/import interop.
    var azureMode: Boolean = false,

) {
    /** Returns the effective API base URL, applying v1 suffix if configured. */
    val effectiveBaseURL: String?
        get() {
            val base = customBaseURL?.trimEnd('/') ?: return null
            return if (appendV1Suffix && !base.endsWith("/v1")) "$base/v1" else base
        }

    /**
     * [T-empty-key-compat-endpoints] Whether an EMPTY API key is a valid
     * configuration for this instance. True only for API-key-mode instances
     * pointing at a third-party OpenAI/Anthropic-compatible endpoint (custom
     * base URL set): local gateways, ollama, LM Studio, LiteLLM and many
     * relay deployments require no key, and forcing a dummy one is friction.
     * Deliberately NOT extended to official endpoints (no custom base URL —
     * an empty key against the official API is always a misconfiguration) or
     * OAuth instances (their credential is the token). Mirrors iOS
     * `ProviderInstance.allowsEmptyAPIKey`; Android has no `openAIResponses`
     * type — the `useResponsesAPI` flag rides on `.openAI`, so the type gate
     * is just {openAI, anthropic}.
     */
    val allowsEmptyAPIKey: Boolean
        get() = credentialType == ProviderCredential.apiKey &&
            !customBaseURL.isNullOrBlank() &&
            (providerType == ProviderType.openAI || providerType == ProviderType.anthropic)

    /**
     * [T-android-image-endpoint-mode] Whether the "Image Generation" endpoint
     * picker is surfaced for this instance. Mirrors iOS
     * `supportsImageEndpointSetting`. Android has no `openAIResponses` provider
     * type — an OpenAI instance carries the `useResponsesAPI` flag instead, so
     * the gate is just the three OpenAI-compatible types.
     */
    val supportsImageEndpointSetting: Boolean
        get() = providerType == ProviderType.openAI ||
            providerType == ProviderType.openRouter ||
            providerType == ProviderType.xAI

    /**
     * [T-android-azure-openai] Whether the Azure toggle applies to this
     * instance — only OpenAI instances using an API key (Azure auths with an
     * api-key header). Covers both Chat-Completions and Responses formats since
     * Android models Responses as the `useResponsesAPI` flag on an openAI
     * instance rather than a separate provider type. Mirrors iOS
     * supportsAzureMode.
     */
    val supportsAzureMode: Boolean
        get() = providerType == ProviderType.openAI && credentialType == ProviderCredential.apiKey


    /**
     * [T-android-thinking-rules-phase2 / parity with iOS 93eb4090] Whether custom
     * thinking rules are meaningful for this instance, i.e. its requests actually run
     * through the Chat Completions path that consults [ThinkingRuleResolver].
     *
     * Anthropic and Gemini use their own thinking emitters and never read the rule
     * registry. An OpenAI instance in Responses mode (useResponsesAPI) builds its
     * `reasoning` inline, and a Codex-shaped OAuth instance (oauth, no custom base)
     * resolves to the Responses backend — neither consults user rules. So on all of
     * those the UI must show an explanatory notice, NOT an interactive list that
     * promises behaviour the request path ignores.
     */
    val supportsCustomThinkingRules: Boolean
        get() = when (providerType) {
            ProviderType.anthropic, ProviderType.gemini -> false
            else -> {
                val codexOAuth = credentialType == ProviderCredential.oauth &&
                    customBaseURL.isNullOrBlank()
                !useResponsesAPI && !codexOAuth
            }
        }
}

@Serializable
data class ModelOverrides(
    val displayName: String? = null,
    val maxOutputTokens: Int? = null,
    // Override the baseModel's context window (custom models only on iOS, but
    // we allow it on any entry here — mirrors iOS LLMModel.contextWindowTokens
    // being writable on custom entries).
    val contextWindow: Int? = null,
    // Override reasoning support flag (custom entries only, per iOS behavior).
    val supportsReasoning: Boolean? = null,
    // Modality overrides — mirror iOS ModelModality flags. Non-null means the
    // user explicitly flipped this dimension; null means "inherit baseModel".
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    // [T-android-thinking-level-arch] User-set ceiling for this model's thinking
    // intensity. Highest-priority source in the resolution chain (overrides the
    // built-in ThinkingLevelCatalog rule). null = inherit the catalog default.
    // Adding an optional field is safe for kotlinx.serialization deserialization
    // of older JSON (unlike adding an enum case) — old configs simply lack the
    // key and it defaults to null.
    val maxThinkingLevel: ThinkingLevel? = null,
    // [T-android-model-custom-params] Per-model request tuning. All four are
    // nullable with a `= null` default, which is what lets an OLDER config or
    // backup — written before these keys existed — decode without throwing
    // MissingFieldException. kotlinx.serialization only tolerates a missing key
    // when the property has a default, so the default is load-bearing, not
    // decoration.
    //
    // null means "inherit / send nothing", NOT "use 0". That distinction
    // matters for temperature especially: 0.0 is a legitimate, meaningful value
    // (fully deterministic), so it cannot double as the absent marker.
    val temperature: Double? = null,
    val topP: Double? = null,
    /** Extra headers merged into this model's requests. null = none. */
    val customHeaders: Map<String, String>? = null,
    /** Raw JSON merged into the request body for provider-specific knobs. */
    val extraBodyParams: JsonObject? = null,
) {
    val isEmpty: Boolean
        get() = displayName == null
            && maxOutputTokens == null
            && contextWindow == null
            && supportsReasoning == null
            && inputModalities == null
            && outputModalities == null
            && maxThinkingLevel == null
            // [T-android-model-custom-params] The new fields MUST be part of
            // this predicate. `isEmpty` gates whether the overrides object is
            // written at all on export (ProviderRepository ~2734), so omitting
            // them here would make an entry carrying ONLY a temperature look
            // empty and get dropped on backup/round-trip.
            && temperature == null
            && topP == null
            && customHeaders == null
            && extraBodyParams == null
}

@Serializable
data class ModelEntry(
    val providerInstanceId: String,
    @SerialName("model")
    val baseModel: LLMModel,
    val overrides: ModelOverrides = ModelOverrides(),
    val isCustom: Boolean = false,
    val isHidden: Boolean = false,
    val uuid: String = UUID.randomUUID().toString(),
    /** [T-android-provider-iso8601-wire] See ProviderInstance.createdAt — iOS
     *  `ModelEntry.userModifiedAt` is a `Date?` decoded with `.iso8601`. */
    @Serializable(with = com.openminis.app.backup.Iso8601MillisNullableSerializer::class)
    val userModifiedAt: Long? = null,
    /**
     * [T-android-model-absence-grace] When the provider's /v1/models first
     * stopped listing this model, or null while it is still being reported.
     * Mirrors iOS `ModelEntry.absentSince`.
     *
     * Exists because "this refresh did not list the model" and "this model is
     * gone" are different facts, and [ProviderRepository.replaceEntries] used
     * to treat them as one: a catalog entry missing from a single response was
     * deleted outright, taking its [overrides] with it. Relay/aggregator
     * endpoints drop a model from the list transiently — upstream quota, a
     * backend rotation, a partial outage — and list it again minutes later.
     *
     * While set, the entry is kept and shown as unavailable rather than
     * deleted; it is only really removed once the absence outlives
     * [ProviderRepository.MODEL_ABSENCE_GRACE_MS]. A model that comes back is
     * cleared to null and is indistinguishable from one that never left,
     * overrides included.
     *
     * DELIBERATELY excluded from [isUserModified]: absence is a per-device
     * observation (each device refreshes on its own schedule against possibly
     * different upstream state), not user intent, so it must not make an
     * otherwise-untouched entry look user-modified. Same ISO-8601 wire shape as
     * [userModifiedAt] so the field round-trips with iOS rather than becoming a
     * platform-only dialect.
     */
    @Serializable(with = com.openminis.app.backup.Iso8601MillisNullableSerializer::class)
    val absentSince: Long? = null,
) {
    val id: String get() = uuid

    /** True while the provider is not currently listing this model. */
    val isUnavailableFromProvider: Boolean get() = absentSince != null

    /**
     * Effective model as seen by the rest of the app: baseModel with overrides
     * applied.
     *
     * [T-android-modality-provider-fallback] The two modality fields resolve
     * through `effective*Modalities`, i.e. the full chain
     *   user override > model's own declared list > provider default table.
     * Every other field keeps the plain "null override means inherit baseModel
     * verbatim" semantics — only modality has a provider-level default to fall
     * back to.
     *
     * This property is what the REST OF THE APP reads (chat gating, Vision
     * Group membership, hasImageInput and friends). 1be751fdc added the
     * fallback but only wired it into ModelEntryDetailScreen, so the detail
     * screen alone showed the corrected switches while every real consumer
     * still saw the raw nulls — which is why reinstalling and refreshing
     * models changed nothing for Fable 5.1.
     *
     * The `overrides.isEmpty` early return had to go, and that was the actual
     * bug: `isEmpty` only asks whether the USER set anything, so the common
     * case (new model, no override) returned baseModel without ever calling
     * copy(), leaving no opportunity to apply any fallback at all. It is now
     * gated on "nothing to apply from EITHER source" — no overrides AND no
     * modality fallback pending — so the allocation is still skipped for the
     * genuinely-unchanged case (every already-catalogued model, every provider
     * outside the default table).
     */
    val model: LLMModel
        get() {
            val effIn = baseModel.effectiveInputModalities
            val effOut = baseModel.effectiveOutputModalities
            // "Did the provider table contribute anything?" — asked as
            // was-null-and-now-is-not, NOT by comparing list identity:
            // effective* runs the list through normalizeModalities(), whose
            // `.map {}` allocates a fresh list every call, so a referential
            // check would be true even when nothing was added and the early
            // return would be dead code.
            val fallbackPending =
                (baseModel.inputModalities == null && effIn != null) ||
                    (baseModel.outputModalities == null && effOut != null)
            if (overrides.isEmpty && !fallbackPending) return baseModel
            return baseModel.copy(
                displayName = overrides.displayName ?: baseModel.displayName,
                maxOutputTokens = overrides.maxOutputTokens ?: baseModel.maxOutputTokens,
                contextWindow = overrides.contextWindow ?: baseModel.contextWindow,
                supportsReasoning = overrides.supportsReasoning ?: baseModel.supportsReasoning,
                // [T-android-modality-normalize-override] (GH#305) Normalize the
                // OVERRIDE too, not just the fallback.
                //
                // `effIn`/`effOut` come from effective*Modalities, which already
                // runs normalizeModalities(). The override branch did not, so a
                // user who ticked audio-input by hand stored the suffixed spelling
                // ("audio_input", the OpenAI/OpenRouter form the detail screen
                // writes) and every `contains("audio")` reader — hasAudioInput,
                // hasImageInput, the Vision Group filter — compared it against the
                // bare name and answered false. The switch showed ON while the
                // capability stayed off, so the manual override silently did
                // nothing: exactly the workaround a user reaches for when
                // inference misses their model.
                //
                // [T-android-modality-override-off] …but an EMPTY override is
                // user intent too: the detail screen saves only the ticked
                // non-text modalities, so switching the last one off stores
                // `[]`. normalizeModalities() maps empty to null, which fell
                // back to inference and made it impossible to switch a
                // mis-inferred TTS/ASR model OFF (the switch re-opened ON).
                // Only a null override (never set) falls back.
                inputModalities = overrides.inputModalities
                    ?.let { it.normalizeModalities() ?: emptyList() } ?: effIn,
                outputModalities = overrides.outputModalities
                    ?.let { it.normalizeModalities() ?: emptyList() } ?: effOut,
            )
        }

    /** True when this entry carries user intent beyond API-reported defaults. */
    val isUserModified: Boolean
        get() = isCustom || isHidden || !overrides.isEmpty
}

@Serializable
data class ProviderConfig(
    val instances: MutableList<ProviderInstance> = mutableListOf(),
    val modelEntries: MutableList<ModelEntry> = mutableListOf(),
    val modelGroups: MutableList<ModelGroup> = mutableListOf(),
    var defaultPrimaryGroupId: String? = null,
    var defaultSubGroupId: String? = null,
    // [T-android-provider-voice] Voice Input / Voice Output group bindings —
    // mirrors iOS ProviderConfig.voiceInputGroupId / voiceOutputGroupId
    // (per-device, provider_local_kv on iOS; meta KV rows here). Old persisted
    // JSON lacks the keys and deserializes to null (ignoreUnknownKeys +
    // declared defaults), so adding them is downgrade/round-trip safe.
    var voiceInputGroupId: String? = null,
    var voiceOutputGroupId: String? = null,
    // [T-android-vision-group / GH#182] Vision Group binding — the group whose
    // vision-capable members read images on behalf of a main model that cannot
    // see pixels. Per-device pointer at an ordinary ModelGroup, mirroring
    // voiceInputGroupId (meta KV row, not synced CRDT member maps). Absent in
    // old persisted JSON → deserializes to null (ignoreUnknownKeys + default).
    var visionGroupId: String? = null,
    // [T-sub-agents-v1] The named sub agents the main model can delegate to.
    // Mirrors iOS ProviderConfig.subAgents — deliberately carried on the
    // existing provider-config blob rather than as a new synced type, so it
    // rides the same path the groups above already do. Absent in old persisted
    // JSON → deserializes to an empty list (ignoreUnknownKeys + default), which
    // SubAgentRoster.normalize turns into "built-in only".
    val subAgents: MutableList<SubAgentDefinition> = mutableListOf(),
    // Models and groups exposed to the agent loop (minis-model-use terminal
    // command) — mirrors iOS agentLoopModelEntryIds / agentLoopGroupIds.
    val agentLoopModelEntryIds: MutableList<String> = mutableListOf(),
    val agentLoopGroupIds: MutableList<String> = mutableListOf(),
    // T273: bumped by ProviderRepository.saveConfig on every mutation so
    // data-class structural equals returns false even when callers mutate
    // inner MutableLists in place. Without this, MutableStateFlow's
    // distinct-until-changed (uses equals, not ===) suppresses emission
    // and ProviderDetailScreen / ModelGroupDetailScreen miss refreshes.
    // @Transient: revision is in-memory only, never persisted to prefs
    // or iCloud sync.
    //
    // [T-android-providerconfig-cme] Defaults to a globally-unique value, NOT
    // 0. equals() compares revision alone, so two configs sharing a revision
    // are indistinguishable — and with a 0 default, a freshly LOADED config
    // (revision 0) compared equal to the initial empty placeholder (revision
    // 0), so MutableStateFlow's distinct-until-changed silently DISCARDED the
    // assignment and the app ran with zero providers despite a populated DB.
    // Every construction now gets its own id; saveConfig still bumps it
    // explicitly, which remains correct because any new number is also unique.
    @kotlinx.serialization.Transient var revision: Long = nextRevision(),
) {
    companion object {
        private val revisionSeq = java.util.concurrent.atomic.AtomicLong(1L)

        /** Monotonic, process-unique id for a ProviderConfig value. */
        fun nextRevision(): Long = revisionSeq.getAndIncrement()
    }

    /**
     * [T-android-providerconfig-cme] Hand-written equals that tests [revision]
     * FIRST and never walks the mutable lists.
     *
     * The generated data-class equals compares fields in DECLARATION order, so
     * it reached `instances` / `modelEntries` / `modelGroups` long before the
     * revision short-circuit at the end could help. Those are MutableLists that
     * writers (addInstance, provider.import, replaceEntries…) mutate IN PLACE
     * on a background thread, while StateFlowImpl.collect calls equals() on the
     * main thread to decide whether to emit — so the comparison walked a list
     * that was being appended to and threw ConcurrentModificationException,
     * crashing the app from inside StateFlow's own emission path:
     *
     *   ArrayList.checkForComodification → ArrayList.equals
     *     → ProviderConfig.equals → StateFlowImpl.collect
     *
     * Reproduced by importing a provider over the debug server while a picker
     * was collecting config (crash-2026-08-16_19-59-40 / _20-00-21).
     *
     * revision is bumped by saveConfig on EVERY mutation, so it is already the
     * authoritative "did anything change" signal — comparing it alone is both
     * correct for change detection and immune to concurrent mutation. Identity
     * is checked first so a value always equals itself.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderConfig) return false
        return revision == other.revision
    }

    override fun hashCode(): Int = revision.hashCode()
}
