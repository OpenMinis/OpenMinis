package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Mutation handlers for the `provider.*` and `provider.groups.*` RPC methods —
 * Phase 2.
 *
 * Each method routes through the existing [ProviderRepository] surface, which
 * is the same code path the in-app Settings screens use. Mutations are written
 * synchronously to disk; Keychain-equivalent (EncryptedSharedPreferences)
 * writes happen alongside JSON config writes — a failed credential write is
 * surfaced as -32000.
 */
internal object ProviderMutationMethods {

    private fun repo(context: Context): ProviderRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).providerRepository

    // ─── Instances ──────────────────────────────────────────────────────────

    fun instancesCreate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val typeRaw = params.optString("providerType", "").ifEmpty {
            throw RPCException(-32602, "Missing 'providerType' param")
        }
        val type = try { ProviderType.valueOf(typeRaw) } catch (_: Exception) {
            throw RPCException(-32602, "Unknown providerType: $typeRaw")
        }
        val label = params.optString("label", "").ifEmpty {
            throw RPCException(-32602, "Missing 'label' param")
        }
        val credTypeStr = params.optString("credentialType", "apiKey").ifEmpty { "apiKey" }
        val credentialType = try { ProviderCredential.valueOf(credTypeStr) } catch (_: Exception) {
            throw RPCException(-32602, "Unknown credentialType: $credTypeStr")
        }
        val customBaseURL = params.optString("customBaseURL", "").ifEmpty { null }
        val appendV1Suffix = params.optBoolean("appendV1Suffix", true)
        val isEnabled = params.optBoolean("isEnabled", true)
        val useResponsesAPI = params.optBoolean("useResponsesAPI", false)
        val seedBuiltInModels = params.optBoolean("seedBuiltInModels", true)

        // Mirrors iOS gating: customBaseURL only honored on OpenAI-shape types.
        if (customBaseURL != null && type != ProviderType.openAI) {
            throw RPCException(-32602, "customBaseURL only supported for providerType=openAI")
        }

        // [T-ua-block-oauth-provider] customUserAgent only for apiKey providers.
        val customUserAgent = params.optString("customUserAgent", "").ifEmpty { null }
        if (customUserAgent != null && credentialType != ProviderCredential.apiKey) {
            throw RPCException(-32602, "Custom User-Agent is only supported for third-party API-key providers. OAuth providers (Anthropic/Codex) use their own authentication UA and cannot be overridden.")
        }

        val instance = ProviderInstance(
            id = UUID.randomUUID().toString(),
            label = label,
            providerType = type,
            credentialType = credentialType,
            isEnabled = isEnabled,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1Suffix,
            useResponsesAPI = useResponsesAPI,
            customUserAgent = customUserAgent,
        )

        // [T-android-provider-mutator-lock] Delegate straight to
        // repo.addInstance. This used to add the instance and its seeds to
        // `repo.config.value`'s lists FIRST, then remove them again, then call
        // addInstance anyway — a no-op round trip whose only lasting effect was
        // mutating the PUBLISHED lists, unlocked, from the debug server's HTTP
        // thread. That is the one writer left outside the repository's
        // copy-on-write discipline, and it crashed a reader on device:
        //
        //   ConcurrentModificationException
        //     at ChatViewModel$showFastModeToggle$1.invokeSuspend  (combine over
        //     providerRepository.config, iterating config.modelEntries)
        //
        // addInstance already applies the built-in seeding rule internally, so
        // nothing is lost by dropping the inline copy; the seedBuiltInModels
        // opt-out is still honoured by the sweep below.
        repo.addInstance(instance)
        if (!seedBuiltInModels) {
            // addInstance always seeds — if user opted out, sweep the seeds.
            for (entry in repo.entriesFor(instance.id).toList()) repo.removeEntry(entry.id)
        }

        // Credential write (write-only — never returned).
        val apiKey = params.optString("apiKey", "").ifEmpty { null }
        val oauthToken = params.optString("oauthToken", "").ifEmpty { null }
        val secret = apiKey ?: oauthToken
        if (secret != null) repo.saveApiKey(instance.id, secret)

        return JSONObject().put("instance", instanceToJson(repo, instance))
    }

    fun instancesUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val current = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")

        // [T-ua-block-oauth-provider] customUserAgent only applies to third-party
        // API-key providers. OAuth providers (Anthropic/Codex) ride their own
        // authentication request chain with a fixed client UA — overriding it can
        // break the handshake or be rejected upstream. Reject any write (including
        // the empty-string clear) for non-apiKey instances, mirroring the
        // minis-config writer guard in ProvidersCollection.kt.
        val customUserAgent: String? = if (params.has("customUserAgent")) {
            if (current.credentialType != ProviderCredential.apiKey) {
                throw RPCException(-32602, "Custom User-Agent is only supported for third-party API-key providers. OAuth providers (Anthropic/Codex) use their own authentication UA and cannot be overridden.")
            }
            if (params.isNull("customUserAgent")) null
            else params.optString("customUserAgent", "").ifEmpty { null }
        } else current.customUserAgent

        // [GH#68] Optional imageEndpointMode patch. Accepts the Kotlin enum
        // name or the wire SerialName (images_generations / chat_completions).
        // Forcing a non-auto mode clears the cached probe result, mirroring the
        // picker UI, so a stale auto-probe can't shadow the user's choice.
        val imageEndpointMode = if (params.has("imageEndpointMode")) {
            when (val raw = params.optString("imageEndpointMode", "")) {
                "auto" -> ImageEndpointMode.auto
                "imagesGenerations", "images_generations" -> ImageEndpointMode.imagesGenerations
                "chatCompletions", "chat_completions" -> ImageEndpointMode.chatCompletions
                else -> throw RPCException(-32602, "imageEndpointMode must be auto | images_generations | chat_completions (got '$raw')")
            }
        } else current.imageEndpointMode

        val updated = current.copy(
            label = if (params.has("label")) params.optString("label", current.label) else current.label,
            customBaseURL = if (params.has("customBaseURL")) {
                if (params.isNull("customBaseURL")) null
                else params.optString("customBaseURL", "").ifEmpty { null }
            } else current.customBaseURL,
            appendV1Suffix = if (params.has("appendV1Suffix")) params.optBoolean("appendV1Suffix", current.appendV1Suffix) else current.appendV1Suffix,
            useResponsesAPI = if (params.has("useResponsesAPI")) params.optBoolean("useResponsesAPI", current.useResponsesAPI) else current.useResponsesAPI,
            isEnabled = if (params.has("isEnabled")) params.optBoolean("isEnabled", current.isEnabled) else current.isEnabled,
            customUserAgent = customUserAgent,
            imageEndpointMode = imageEndpointMode,
            imageEndpointResolved = if (params.has("imageEndpointMode") && imageEndpointMode != ImageEndpointMode.auto) {
                null
            } else current.imageEndpointResolved,
        )
        repo.updateInstance(updated)

        // Credential refresh — pass `""` to clear, missing to leave alone.
        if (params.has("apiKey")) {
            val v = params.optString("apiKey", "")
            if (v.isEmpty()) repo.deleteApiKey(id) else repo.saveApiKey(id, v)
        }
        if (params.has("oauthToken")) {
            val v = params.optString("oauthToken", "")
            if (v.isEmpty()) repo.deleteApiKey(id) else repo.saveApiKey(id, v)
        }

        return JSONObject().put("instance", instanceToJson(repo, updated))
    }

    fun instancesDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete an instance")
        }
        val current = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")
        val priorEntryCount = repo.entriesFor(id).size
        repo.removeInstance(id)
        return JSONObject().apply {
            put("instanceId", id)
            put("deletedModelEntries", priorEntryCount)
            put("deleted", true)
        }
    }

    suspend fun instancesTest(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")
        val timeoutMs = params.optInt("timeoutMs", 10_000).coerceIn(1_000, 30_000)

        val baseURL = instance.effectiveBaseURL ?: when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            // [T-android-provider-type-parity] Responses API shares the host.
            ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
            ProviderType.antigravity, ProviderType.unsupported -> ""
        }
        val probeURL = when (instance.providerType) {
            ProviderType.anthropic -> "$baseURL/v1/models"
            ProviderType.gemini -> "$baseURL/v1beta/models?key=" + (repo.loadApiKey(id) ?: "")
            ProviderType.openAI, ProviderType.openAIResponses ->
                if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
            ProviderType.openRouter -> "$baseURL/models"
            // xAI exposes an OpenAI-compatible /models endpoint at the same base.
            ProviderType.xAI -> if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
            // Kimi Coding: OpenAI-compatible /models under /coding/v1.
            ProviderType.kimiCode -> if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
            // No probe endpoint for a type this build cannot drive.
            ProviderType.antigravity, ProviderType.unsupported -> baseURL
        }
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val builder = okhttp3.Request.Builder().url(probeURL).get()
        val key = repo.loadApiKey(id)
        when (instance.providerType) {
            ProviderType.anthropic -> if (!key.isNullOrEmpty()) builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            ProviderType.openAI, ProviderType.openAIResponses ->
                if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            ProviderType.openRouter -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            ProviderType.gemini -> { /* key in URL */ }
            // xAI: OpenAI-compat bearer header. Manual API key OR OAuth
            // access token can fill this slot.
            ProviderType.xAI -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            // Kimi Coding: OpenAI-compat bearer (OAuth access token or key).
            ProviderType.kimiCode -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            // [T-android-provider-type-parity] No auth scheme known for a type
            // this build cannot drive; the probe will simply fail.
            ProviderType.antigravity, ProviderType.unsupported -> { /* no auth */ }
        }
        val start = System.currentTimeMillis()
        try {
            val resp = client.newCall(builder.build()).execute()
            val ok = resp.isSuccessful
            val latency = System.currentTimeMillis() - start
            val bodyStr = resp.body?.string() ?: ""
            resp.close()
            // Best-effort model count parse — Anthropic/OpenAI/OpenRouter all
            // wrap models in a top-level `data` array; Gemini uses `models`.
            val modelCount: Int = try {
                val obj = JSONObject(bodyStr)
                val arr = obj.optJSONArray("data") ?: obj.optJSONArray("models")
                arr?.length() ?: -1
            } catch (_: Exception) { -1 }
            JSONObject().apply {
                put("ok", ok)
                put("httpStatus", resp.code)
                put("latencyMs", latency)
                if (modelCount >= 0) put("reachableModelCount", modelCount)
                put("error", JSONObject.NULL)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("httpStatus", JSONObject.NULL)
                put("latencyMs", System.currentTimeMillis() - start)
                put("error", JSONObject().apply {
                    put("code", "network")
                    put("message", e.message ?: e.javaClass.simpleName)
                })
            }
        }
    }

    private fun instanceToJson(repo: ProviderRepository, inst: ProviderInstance): JSONObject {
        val cfg = repo.config.value
        val entryCount = cfg.modelEntries.count { it.providerInstanceId == inst.id }
        return JSONObject().apply {
            put("id", inst.id)
            put("label", inst.label)
            put("providerType", inst.providerType.name)
            put("credentialType", inst.credentialType.name)
            put("isEnabled", inst.isEnabled)
            put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
            put("appendV1Suffix", inst.appendV1Suffix)
            put("useResponsesAPI", inst.useResponsesAPI)
            put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
            put("createdAt", inst.createdAt)
            put("hasCredential", repo.loadApiKey(inst.id)?.isNotEmpty() == true)
            put("modelEntryCount", entryCount)
        }
    }

    // ─── Models ─────────────────────────────────────────────────────────────

    fun modelsAdd(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val modelId = params.optString("modelId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'modelId' param")
        }
        val displayName = params.optString("displayName", "").ifEmpty {
            // Prettify: "deepseek-v4-pro" → "Deepseek V4 Pro" — same heuristic
            // the iOS doc describes; cheap inline impl matches the result for
            // typical hyphenated model ids without dragging in iOS-specific
            // formatting helpers.
            modelId.split('-', '_', '/').joinToString(" ") { piece ->
                if (piece.isEmpty()) "" else piece.replaceFirstChar { it.uppercase() }
            }.trim()
        }
        val contextWindow = if (params.has("contextWindow") && !params.isNull("contextWindow")) params.optInt("contextWindow") else null
        val maxOut = if (params.has("maxOutputTokens") && !params.isNull("maxOutputTokens")) params.optInt("maxOutputTokens") else null
        val supportsReasoning = if (params.has("supportsReasoning") && !params.isNull("supportsReasoning")) params.optBoolean("supportsReasoning") else null
        val inputModalities = mutableListOf<String>()
        if (params.optBoolean("supportsImageInput", false)) inputModalities.add("image")
        if (params.optBoolean("supportsAudioInput", false)) inputModalities.add("audio")
        if (params.optBoolean("supportsVideoInput", false)) inputModalities.add("video")
        if (params.optBoolean("supportsPDFInput", false)) inputModalities.add("pdf")
        val outputModalities = mutableListOf<String>()
        if (params.optBoolean("supportsImageOutput", false)) outputModalities.add("image")

        val model = LLMModel(
            id = modelId,
            displayName = displayName,
            provider = instance.providerType.displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOut,
            supportsReasoning = supportsReasoning,
            inputModalities = inputModalities.takeIf { it.isNotEmpty() },
            outputModalities = outputModalities.takeIf { it.isNotEmpty() },
        )
        val entry = ModelEntry(providerInstanceId = instanceId, baseModel = model, isCustom = true)
        repo.addEntry(entry)
        return entryToJson(entry)
    }

    fun modelsUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        val cfg = repo.config.value
        val current = cfg.modelEntries.firstOrNull { it.id == entryId }
            ?: throw RPCException(-32602, "Entry not found: $entryId")

        // modelId only mutable on custom entries.
        if (params.has("modelId")) {
            if (!current.isCustom) {
                throw RPCException(-32602, "built-in model ID is immutable")
            }
        }
        val newModel = if (params.has("modelId")) {
            val mid = params.optString("modelId", current.baseModel.id)
            current.baseModel.copy(id = mid)
        } else current.baseModel

        val newOverrides = current.overrides.copy(
            displayName = if (params.has("displayName")) {
                if (params.isNull("displayName")) null else params.optString("displayName", "").ifEmpty { null }
            } else current.overrides.displayName,
            maxOutputTokens = if (params.has("maxOutputTokens")) {
                if (params.isNull("maxOutputTokens")) null else params.optInt("maxOutputTokens").takeIf { it > 0 }
            } else current.overrides.maxOutputTokens,
            contextWindow = if (params.has("contextWindow")) {
                if (params.isNull("contextWindow")) null else params.optInt("contextWindow").takeIf { it > 0 }
            } else current.overrides.contextWindow,
        )
        val newHidden = if (params.has("isHidden")) params.optBoolean("isHidden", current.isHidden) else current.isHidden

        val updated = current.copy(baseModel = newModel, overrides = newOverrides, isHidden = newHidden)
        repo.updateEntry(updated)
        return entryToJson(updated)
    }

    fun modelsDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete an entry")
        }
        val current = repo.config.value.modelEntries.firstOrNull { it.id == entryId }
            ?: throw RPCException(-32602, "Entry not found: $entryId")
        if (!current.isCustom) {
            throw RPCException(-32602, "Built-in entries can't be deleted — set isHidden=true via provider.models.update instead")
        }
        repo.removeEntry(entryId)
        return JSONObject().apply {
            put("entryId", entryId)
            put("deleted", true)
        }
    }

    suspend fun modelsRefresh(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")

        val before = repo.entriesFor(instanceId).map { it.baseModel.id }.toSet()
        val start = System.currentTimeMillis()
        try {
            repo.refreshModels(instance)
        } catch (e: Exception) {
            throw RPCException(-32000, "refreshModels failed: ${e.message}")
        }
        val after = repo.entriesFor(instanceId).map { it.baseModel.id }.toSet()
        val added = (after - before).size
        val disappeared = (before - after).size
        return JSONObject().apply {
            put("instanceId", instanceId)
            put("added", added)
            put("disappeared", disappeared)
            put("total", after.size)
            put("durationMs", System.currentTimeMillis() - start)
        }
    }

    fun modelsSetAgentLoop(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        if (!params.has("inLoop")) throw RPCException(-32602, "Missing 'inLoop' param")
        val want = params.optBoolean("inLoop", false)
        val cfg = repo.config.value
        if (cfg.modelEntries.none { it.id == entryId }) {
            throw RPCException(-32602, "Entry not found: $entryId")
        }
        val current = cfg.agentLoopModelEntryIds.toMutableList()
        current.removeAll { it == entryId }
        if (want) current.add(entryId)
        repo.setAgentLoopEntryIds(current)
        return JSONObject().apply {
            put("entryId", entryId)
            put("inLoop", want)
        }
    }

    private fun entryToJson(entry: ModelEntry): JSONObject {
        val effective = entry.model
        return JSONObject().apply {
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
        }
    }

    // ─── Groups ─────────────────────────────────────────────────────────────

    fun groupsCreate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val name = params.optString("name", "").ifEmpty {
            throw RPCException(-32602, "Missing 'name' param")
        }
        val membersRaw = params.optJSONArray("memberEntryIds")
        val members = mutableListOf<String>()
        if (membersRaw != null) {
            val knownEntryIds = repo.config.value.modelEntries.map { it.id }.toSet()
            for (i in 0 until membersRaw.length()) {
                val id = membersRaw.optString(i)
                if (id.isNotEmpty() && id in knownEntryIds) members.add(id)
            }
        }
        val strategy = parseStrategy(params.optString("strategy", "fallback"))
        val fallback = parseFallback(params.optString("fallbackStrategy", "default"))

        val group = ModelGroup(
            name = name,
            memberEntryIds = members,
            strategy = strategy,
            fallbackStrategy = fallback,
        )
        repo.addGroup(group)
        return JSONObject().put("group", groupToJson(repo, group))
    }

    fun groupsUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("groupId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'groupId' param")
        }
        val published = repo.group(id)
            ?: throw RPCException(-32602, "Group not found: $id")

        // [T-android-provider-mutator-lock] Edit a COPY. `repo.group(id)`
        // returns the live object out of the published config, so writing its
        // fields — and especially `memberEntryIds.clear()` — mutated state that
        // Compose readers (ModelGroupDetailScreen, enabledMemberEntries) are
        // iterating, from the debug server's HTTP thread. The clear() was the
        // worse half: it opens a window where the group has ZERO members, and a
        // concurrent saveConfig from any other mutator would snapshot and
        // persist that empty group — silent data loss, not just a CME.
        val current = published.copy(memberEntryIds = published.memberEntryIds.toMutableList())

        if (params.has("name")) {
            val n = params.optString("name", "")
            if (n.isEmpty()) throw RPCException(-32602, "Group name must be non-empty")
            current.name = n
        }
        if (params.has("memberEntryIds")) {
            val arr = params.optJSONArray("memberEntryIds") ?: JSONArray()
            val knownIds = repo.config.value.modelEntries.map { it.id }.toSet()
            current.memberEntryIds.clear()
            for (i in 0 until arr.length()) {
                val mid = arr.optString(i)
                if (mid.isNotEmpty() && mid in knownIds) current.memberEntryIds.add(mid)
            }
        }
        if (params.has("strategy")) current.strategy = parseStrategy(params.optString("strategy"))
        if (params.has("fallbackStrategy")) current.fallbackStrategy = parseFallback(params.optString("fallbackStrategy"))
        repo.updateGroup(current)
        return JSONObject().put("group", groupToJson(repo, current))
    }

    fun groupsDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("groupId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'groupId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete a group")
        }
        val current = repo.group(id)
            ?: throw RPCException(-32602, "Group not found: $id")
        val wasDefault = repo.config.value.defaultPrimaryGroupId == id
        repo.removeGroup(id)
        return JSONObject().apply {
            put("groupId", id)
            put("deleted", true)
            put("wasDefault", wasDefault)
        }
    }

    fun groupsSetDefault(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        if (!params.has("groupId")) throw RPCException(-32602, "Missing 'groupId' param")
        val id = if (params.isNull("groupId")) null else params.optString("groupId", "").ifEmpty { null }
        if (id != null && repo.group(id) == null) {
            throw RPCException(-32602, "Group not found: $id")
        }
        repo.defaultPrimaryGroupId = id
        return JSONObject().put("defaultGroupId", id ?: JSONObject.NULL)
    }

    fun groupsSetAgentLoop(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("groupId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'groupId' param")
        }
        if (!params.has("inLoop")) throw RPCException(-32602, "Missing 'inLoop' param")
        val want = params.optBoolean("inLoop", false)
        if (repo.group(id) == null) throw RPCException(-32602, "Group not found: $id")
        val current = repo.config.value.agentLoopGroupIds.toMutableList()
        current.removeAll { it == id }
        if (want) current.add(id)
        repo.setAgentLoopGroupIds(current)
        return JSONObject().apply {
            put("groupId", id)
            put("inLoop", want)
        }
    }

    private fun parseStrategy(s: String): RoutingStrategy = when (s) {
        "loadBalance" -> RoutingStrategy.loadBalance
        else -> RoutingStrategy.fallback
    }

    private fun parseFallback(s: String): FallbackStrategy = when (s) {
        "always" -> FallbackStrategy.always
        else -> FallbackStrategy.default
    }

    private fun groupToJson(repo: ProviderRepository, group: ModelGroup): JSONObject {
        val cfg = repo.config.value
        return JSONObject().apply {
            put("id", group.id)
            put("name", group.name)
            put("strategy", group.strategy.name)
            put("fallbackStrategy", group.fallbackStrategy.name)
            put("isDefault", group.id == cfg.defaultPrimaryGroupId)
            put("inAgentLoop", group.id in cfg.agentLoopGroupIds)
            val ids = JSONArray()
            for (m in group.memberEntryIds) ids.put(m)
            put("memberEntryIds", ids)
        }
    }
}
