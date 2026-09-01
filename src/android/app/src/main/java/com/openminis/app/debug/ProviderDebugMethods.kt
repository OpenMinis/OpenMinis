package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only `provider.*` and `provider.groups.*` RPC handlers — Phase 1.
 *
 * Mirrors the read shape from `docs/debug-server-api.md`. Mutation handlers
 * (`provider.instances.create/update/delete/test`, `provider.models.*`,
 * `provider.groups.create/update/delete/setDefault`) land in Phase 2.
 *
 * Field naming is kept 1:1 with the iOS docs so the same automation scripts
 * work cross-platform; Android-only fields that have no iOS analogue (e.g.
 * `useResponsesAPI` on a single instance) are surfaced inline.
 */
internal object ProviderDebugMethods {

    private fun repo(context: Context): ProviderRepository {
        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")
        // Force the lazy load HERE, once, for every provider.* handler. These
        // handlers read `config.value` directly, and ProviderRepository loads
        // config lazily on first mutation/access — so on a cold process (app
        // just launched, user still on the session list) every read returned
        // an empty config. `provider.instances.list` answering `count: 0` for
        // a device with 20 configured providers looks exactly like data loss,
        // which is a far worse failure than a slow first call.
        app.providerRepository.ensureConfigLoaded()
        return app.providerRepository
    }

    fun types(): JSONObject {
        val arr = JSONArray()
        for (type in ProviderType.values()) {
            arr.put(typeSpec(type))
        }
        return JSONObject().put("types", arr)
    }

    private fun typeSpec(type: ProviderType): JSONObject {
        // Cross-reference iOS doc shape. Some fields don't have a direct
        // Android equivalent and are reported as null/absent rather than
        // synthesized — keeps "what's missing" honest for callers.
        val supportedCreds = JSONArray().apply {
            put("apiKey")
            if (type == ProviderType.anthropic || type == ProviderType.openAI) put("oauth")
        }
        val builtInIds = JSONArray()
        for (m in type.builtInModels) builtInIds.put(m.id)

        // T192: Anthropic now supports custom base URL too (Anthropic-
        // compatible endpoints like DeepSeek-anthropic, Bailian-anthropic).
        // iOS LLMProviderFactory.makeAnthropicProvider has always allowed
        // this — Android was the only platform reporting the field as
        // unsupported via RPC `provider.types`.
        val customBaseSupported = type == ProviderType.openAI || type == ProviderType.anthropic
        return JSONObject().apply {
            put("id", type.name)
            put("displayName", type.displayName)
            put("supportedCredentials", supportedCreds)
            put("oauthFlow", when (type) {
                ProviderType.anthropic -> "claude"
                ProviderType.openAI -> "codex"
                else -> JSONObject.NULL
            })
            put("defaultBaseURL", defaultBaseURL(type))
            put("customBaseURLSupported", customBaseSupported)
            put("appendV1SuffixConfigurable", customBaseSupported)
            put("builtInModelIds", builtInIds)
        }
    }

    private fun defaultBaseURL(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com"
        ProviderType.openAI -> "https://api.openai.com"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
        // [T-android-provider-type-parity] Responses API shares the OpenAI
        // host; undrivable types have no canonical base to report.
        ProviderType.openAIResponses -> "https://api.openai.com"
        ProviderType.antigravity, ProviderType.unsupported -> ""
    }

    fun instancesList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeDisabled = params.optBoolean("includeDisabled", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (inst in cfg.instances) {
            if (!includeDisabled && !inst.isEnabled) continue
            // [T-empty-key-compat-endpoints] Mirror what routing sees:
            // usableApiKey returns "" (usable) for keyless third-party
            // compatible endpoints, null only when genuinely unusable.
            val hasKey = repo.usableApiKey(inst) != null
            val entryCount = cfg.modelEntries.count { it.providerInstanceId == inst.id }
            arr.put(JSONObject().apply {
                put("id", inst.id)
                put("label", inst.label)
                put("providerType", inst.providerType.name)
                put("credentialType", inst.credentialType.name)
                put("isEnabled", inst.isEnabled)
                put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
                put("appendV1Suffix", inst.appendV1Suffix)
                put("useResponsesAPI", inst.useResponsesAPI)
                // [GH#68] Surface the image-endpoint picker state so harnesses
                // can verify persistence across restarts.
                put("imageEndpointMode", inst.imageEndpointMode.name)
                put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
                put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
                put("createdAt", inst.createdAt)
                put("hasCredential", hasKey)
                put("modelEntryCount", entryCount)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("instances", arr)
        }
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val includeHidden = params.optBoolean("includeHidden", false)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val all = repo.config.value.modelEntries.filter { it.providerInstanceId == instanceId }
        val visible = if (includeHidden) all else all.filter { !it.isHidden }
        val arr = JSONArray()
        for (entry in visible) {
            val effective: LLMModel = entry.model
            arr.put(JSONObject().apply {
                put("id", entry.id)
                put("modelId", effective.id)
                put("displayName", effective.displayName)
                put("baseModelDisplayName", entry.baseModel.displayName)
                put("isCustom", entry.isCustom)
                put("isHidden", entry.isHidden)
                put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
                put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
                val ov = JSONObject()
                ov.put("displayName", entry.overrides.displayName ?: JSONObject.NULL)
                ov.put("maxOutputTokens", entry.overrides.maxOutputTokens ?: JSONObject.NULL)
                ov.put("contextWindow", entry.overrides.contextWindow ?: JSONObject.NULL)
                put("overrides", ov)
                put("userModifiedAt", entry.userModifiedAt ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    /**
     * Export a provider instance + its model entries + stored credential as a
     * portable JSON document. Mirrors the UI Share button. The API key is
     * base64-wrapped (same as ProviderRepository.exportInstanceJSON) — that's
     * to survive JSON-string escaping of arbitrary key bytes, not for
     * security; callers of the 5321 RPC are trusted by definition.
     */
    fun export(context: Context, params: JSONObject): JSONObject {
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val repo = repo(context)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val raw = repo.exportInstanceJSON(instanceId)
            ?: throw RPCException(-32000, "Export failed for instance $instanceId")
        // Wrap the underlying JSON document under a versioned envelope so
        // forward-compatible importers can detect the schema explicitly.
        val payload = JSONObject(raw)
        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("platform", "android")
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("config", payload)
        }
    }

    /**
     * Import a provider instance from an export envelope (or the raw inner
     * config JSON, for cross-platform compatibility). The implementation
     * always allocates a new instance UUID — duplicate label is auto-renamed
     * by the repository.
     */
    fun import(context: Context, params: JSONObject): JSONObject {
        // Accept either the new envelope (`{version,config:{...}}`) or the
        // raw legacy config JSON. Be liberal on input — the export contract
        // is symmetric on the value side, and harnesses that capture from
        // older builds should still load.
        val raw = params.optString("configJson", "").ifEmpty {
            throw RPCException(-32602, "Missing 'configJson' param")
        }
        val parsed = try { JSONObject(raw) } catch (e: Exception) {
            throw RPCException(-32602, "Invalid configJson: ${e.message}")
        }
        val configJson = if (parsed.has("config") && parsed.optJSONObject("config") != null) {
            parsed.optJSONObject("config")!!.toString()
        } else {
            raw
        }
        val repo = repo(context)
        val resolvedLabel = repo.importInstanceJSON(configJson)
            ?: throw RPCException(-32000, "Import failed — invalid or unsupported configJson")
        // Find the freshly-created instance so we can return its id.
        val newInstance = repo.config.value.instances.lastOrNull { it.label == resolvedLabel }
            ?: throw RPCException(-32000, "Import succeeded but instance lookup failed")
        return JSONObject().apply {
            put("instanceId", newInstance.id)
            put("instanceLabel", newInstance.label)
            put("modelEntryCount", repo.entriesFor(newInstance.id).size)
            put("success", true)
        }
    }

    fun groupsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeMembers = params.optBoolean("includeMembers", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (group in cfg.modelGroups) {
            val isDefault = group.id == cfg.defaultPrimaryGroupId
            val obj = JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("strategy", group.strategy.name)
                put("fallbackStrategy", group.fallbackStrategy.name)
                put("isDefault", isDefault)
                put("inAgentLoop", group.id in cfg.agentLoopGroupIds)
                val ids = JSONArray()
                for (id in group.memberEntryIds) ids.put(id)
                put("memberEntryIds", ids)
            }
            if (includeMembers) {
                val members = JSONArray()
                for (memberId in group.memberEntryIds) {
                    // SystemVoiceEntries fallback: the default voice groups are
                    // seeded with "__builtin_system_speech__/…" members that are
                    // synthesized on demand and never stored in modelEntries, so
                    // matching modelEntries alone reported `members: []` for both
                    // voice groups while memberEntryIds listed two.
                    val entry = cfg.modelEntries.find { it.id == memberId }
                        ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(memberId)
                        ?: continue
                    val instLabel = cfg.instances.find { it.id == entry.providerInstanceId }?.label
                    members.put(JSONObject().apply {
                        put("entryId", entry.id)
                        put("modelId", entry.baseModel.id)
                        put("displayName", entry.model.displayName)
                        put("providerLabel", instLabel ?: "")
                    })
                }
                obj.put("members", members)
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("defaultGroupId", cfg.defaultPrimaryGroupId ?: JSONObject.NULL)
            put("count", arr.length())
            put("groups", arr)
        }
    }

    /**
     * [T-android-debug-quicktest] `provider.quickTest` — run the UI Quick Test
     * sheet's checks headlessly. Port of the iOS debug server's
     * `debug.providers.quickTest`, which Android lacked: verifying that N voice
     * models actually synthesize or transcribe otherwise meant N manual trips
     * through the picker, and there was no way to regression-test a fleet of
     * ASR/TTS providers at all.
     *
     * Calls the SAME `performTest` the sheet calls (made internal for this),
     * rather than reimplementing the probes — a second copy would drift and
     * then "passes over RPC, fails in the app" becomes possible.
     *
     * Audio/image payloads are reported as byte counts, not base64 blobs: the
     * question this answers is "did it produce output", and a 200KB WAV per
     * model would swamp a fleet run.
     */
    suspend fun quickTest(context: Context, params: JSONObject): JSONObject {
        val entryId = params.optString("entryId")
        if (entryId.isEmpty()) throw RPCException(-32602, "Invalid params: 'entryId' is required")
        val repo = repo(context)
        val entry = repo.config.value.modelEntries.find { it.id == entryId }
            ?: com.openminis.app.data.model.SystemVoiceEntries.resolve(entryId)
            ?: throw RPCException(-32602, "Model entry not found: $entryId")

        val requested = params.optJSONArray("kinds")
        val kinds: List<com.openminis.app.ui.components.QuickTestKind> = if (requested == null) {
            com.openminis.app.ui.components.applicableKinds(entry)
        } else {
            (0 until requested.length()).mapNotNull { i ->
                when (requested.optString(i).lowercase()) {
                    "text" -> com.openminis.app.ui.components.QuickTestKind.TEXT
                    "speechout", "speech_out", "tts" -> com.openminis.app.ui.components.QuickTestKind.SPEECH_OUT
                    "transcription", "asr" -> com.openminis.app.ui.components.QuickTestKind.TRANSCRIPTION
                    "imagegen", "image_gen", "image" -> com.openminis.app.ui.components.QuickTestKind.IMAGE_GEN
                    else -> null
                }
            }
        }
        if (kinds.isEmpty()) throw RPCException(-32602, "No applicable test kinds")

        val results = JSONArray()
        for (kind in kinds) {
            val t0 = System.currentTimeMillis()
            val state = try {
                com.openminis.app.ui.components.performTest(kind, entry, repo, context)
            } catch (e: Exception) {
                com.openminis.app.ui.components.QuickTestState.Failure(
                    e.message ?: e::class.java.simpleName,
                )
            }
            val elapsed = System.currentTimeMillis() - t0
            results.put(JSONObject().apply {
                put("kind", kind.name.lowercase())
                put("elapsedMs", elapsed)
                when (state) {
                    is com.openminis.app.ui.components.QuickTestState.TextReply -> {
                        put("status", "ok"); put("detail", state.text.take(400))
                    }
                    is com.openminis.app.ui.components.QuickTestState.AudioReply -> {
                        put("status", "ok")
                        put("bytes", state.data.size)
                        put("detail", "audio ${state.data.size} bytes")
                    }
                    is com.openminis.app.ui.components.QuickTestState.ImageReply -> {
                        put("status", "ok")
                        put("bytes", state.data.size)
                        put("detail", "image ${state.data.size} bytes")
                    }
                    is com.openminis.app.ui.components.QuickTestState.Failure -> {
                        put("status", "failed"); put("detail", state.message.take(600))
                    }
                    else -> { put("status", "unknown"); put("detail", state.toString().take(200)) }
                }
            })
        }
        val inst = repo.config.value.instances.find { it.id == entry.providerInstanceId }
        return JSONObject().apply {
            put("entryId", entry.id)
            put("modelId", entry.model.id)
            put("displayName", entry.model.displayName)
            put("providerLabel", inst?.label ?: "")
            put("results", results)
        }
    }
}
