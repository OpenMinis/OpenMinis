package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.repository.ProviderRepository

/**
 * Exposes ModelEntry overrides under `models.<entryUUID>.…`. Mirrors
 * iOS `ModelsCollection`.
 *
 * `add` creates a custom entry (`isCustom = true`). `remove` is allowed
 * only for those custom entries — API-reported models can be hidden
 * (`isHidden = true`) but never deleted, since the same id may
 * reappear on the next refresh and any prior hide/override should
 * resume.
 */
class ModelsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "models"
    override val displayName: String get() = "LLM models"
    override val description: String get() =
        "Per-model overrides (display name, max output tokens, hidden flag, modalities, context window) and custom-model add/remove."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> =
        repo.config.value.modelEntries.map { it.id }

    override fun fields(forId: String): List<ConfigField> {
        val entry = entry(forId) ?: return emptyList()
        return listOf(
            providerInstanceId(forId),
            modelId(forId),
            isCustom(forId),
            displayNameField(forId),
            maxOutputTokensField(forId),
            isHiddenField(forId),
            modalitiesField(forId),
            modalitiesOverrideField(forId),
            contextWindowField(forId),
            contextWindowOverrideField(forId),
            voiceOverrideField(forId),
            supportsToolsField(forId),
            supportsVisionField(forId),
        )
    }

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")

        // Accept `instance_id` (canonical, matches iOS) or `provider_id`
        // (CLI-facing alias documented in `minis-config` help). Either
        // must be a non-empty UUID of an existing provider instance.
        val instanceId = (
            (obj["instance_id"] as? ConfigValue.Str)?.value
                ?: (obj["provider_id"] as? ConfigValue.Str)?.value
            )
            ?: throw ConfigError.InvalidValue("`instance_id` (or `provider_id`) required")
        if (instanceId.isEmpty()) throw ConfigError.InvalidValue("`instance_id` (or `provider_id`) required")

        val modelIdStr = (obj["model_id"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`model_id` required")
        if (modelIdStr.isEmpty()) throw ConfigError.InvalidValue("`model_id` required")

        if (repo.instance(instanceId) == null) {
            throw ConfigError.InvalidValue("No provider instance with id $instanceId")
        }
        // Refuse silent dedupe: the spec contract is that re-adding the
        // same provider+model pair surfaces an `already_exists` error so
        // the agent knows the prior add already succeeded.
        val duplicate = repo.config.value.modelEntries.firstOrNull {
            it.providerInstanceId == instanceId && it.baseModel.id == modelIdStr
        }
        if (duplicate != null) {
            throw ConfigError.AlreadyExists(
                "Model '$modelIdStr' already exists on provider '$instanceId' (entry id ${duplicate.id})."
            )
        }
        val displayName = (obj["display_name"] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }
            ?: modelIdStr
        val contextWindow = (obj["context_window"] as? ConfigValue.Int)?.value?.takeIf { it > 0 }
        val maxOut = (obj["max_output_tokens"] as? ConfigValue.Int)?.value?.takeIf { it > 0 }
        val supportsReasoning = (obj["supports_reasoning"] as? ConfigValue.Bool)?.value

        val model = LLMModel(
            id = modelIdStr,
            displayName = displayName,
            provider = instanceId,
            contextWindow = contextWindow,
            maxOutputTokens = maxOut,
            supportsReasoning = supportsReasoning,
        )
        val entry = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model,
            isCustom = true,
            isHidden = false,
            userModifiedAt = System.currentTimeMillis(),
        )
        repo.addEntry(entry)
        return entry.id
    }

    override fun remove(id: String) {
        val entry = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        if (!entry.isCustom) {
            throw ConfigError.PermissionDenied(
                "Only custom-added models can be removed; use `set models.$id.isHidden true` for API-reported models"
            )
        }
        repo.removeEntry(id)
    }

    // -- Field factories --

    private fun entry(id: String): ModelEntry? =
        repo.config.value.modelEntries.firstOrNull { it.id == id }

    private fun mutate(id: String, apply: (ModelEntry) -> ModelEntry) {
        val e = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        repo.updateEntry(apply(e))
    }

    private fun providerInstanceId(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.providerInstanceId",
            displayName = "Provider instance",
            description = "Owning provider instance (read-only).",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Str(e.providerInstanceId)
            },
        )

    private fun modelId(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.modelId",
            displayName = "Model id",
            description = "API model identifier (read-only).",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Str(e.baseModel.id)
            },
        )

    private fun isCustom(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.isCustom",
            displayName = "Is custom",
            description = "True for entries created via `models add`; false for API-reported.",
            valueSchema = ConfigSchema.Bool,
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Bool(e.isCustom)
            },
        )

    private fun displayNameField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.displayName",
            displayName = "Display name",
            description = "User-visible label override. Empty restores the API default.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(e.overrides.displayName ?: e.baseModel.displayName)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { e ->
                    val newOverride = if (s.isEmpty() || s == e.baseModel.displayName) null else s
                    e.copy(overrides = e.overrides.copy(displayName = newOverride))
                }
            },
        )

    private fun maxOutputTokensField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.maxOutputTokens",
            displayName = "Max output tokens",
            description = "Override the API-reported max. 0 restores the default.",
            valueSchema = ConfigSchema.Int(min = 0, max = 1_000_000),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Int(e.overrides.maxOutputTokens ?: e.baseModel.maxOutputTokens ?: 0)
            },
            writer = { v ->
                val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
                mutate(id) { e ->
                    e.copy(overrides = e.overrides.copy(maxOutputTokens = if (i == 0) null else i))
                }
            },
        )

    private fun isHiddenField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.isHidden",
            displayName = "Hidden",
            description = "When true, the model is excluded from pickers and agent loop.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Bool(e.isHidden)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                mutate(id) { it.copy(isHidden = b) }
            },
        )

    // -- Capability fields (modalities / contextWindow / derived) --

    /**
     * Canonical names matching `minis-model-use list` so the two tools
     * agree on terminology. Order is meaningful for [encodeModalities]'
     * stable output. Mirrors iOS `modalityNamesInOrder`.
     */
    private val modalityNamesInOrder = listOf(
        "text_input", "text_output",
        "image_input", "pdf_input", "audio_input", "video_input",
        "image_output", "audio_output", "video_output",
    )
    private val allowedModalities = modalityNamesInOrder.toSet()

    /**
     * Build the *effective* modality list: union of override (if any)
     * with baseModel input/output lists, intersected with the allowed
     * names so we never emit junk like "image" without a direction.
     */
    private fun effectiveModalities(e: ModelEntry): List<String> {
        val ovInputs = e.overrides.inputModalities
        val ovOutputs = e.overrides.outputModalities
        val baseInputs = e.baseModel.inputModalities ?: emptyList()
        val baseOutputs = e.baseModel.outputModalities ?: emptyList()
        val inputs = ovInputs ?: baseInputs
        val outputs = ovOutputs ?: baseOutputs

        // Translate models.dev short tokens (e.g. "text", "image") into
        // direction-suffixed names that match `minis-model-use list`.
        val out = LinkedHashSet<String>()
        for (m in inputs) appendDirectional(out, m, "input")
        for (m in outputs) appendDirectional(out, m, "output")

        // Stable order — matches modalityNamesInOrder.
        return modalityNamesInOrder.filter { it in out }
    }

    private fun appendDirectional(out: MutableSet<String>, raw: String, direction: String) {
        val short = raw.lowercase()
        // Canonical "text" → "text_input"/"text_output".
        if (short == "text") {
            out.add("text_$direction"); return
        }
        if (short == "image") {
            out.add("image_$direction"); return
        }
        if (short == "pdf") {
            out.add("pdf_$direction"); return
        }
        if (short == "audio") {
            out.add("audio_$direction"); return
        }
        if (short == "video") {
            out.add("video_$direction"); return
        }
        // Already directional? Use as-is when it's in our allowed set.
        if (short in allowedModalities) out.add(short)
    }

    /** Split the user-supplied flat list into input/output components. */
    private fun decodeModalitiesOverride(names: List<String>): Pair<List<String>, List<String>> {
        val inputs = ArrayList<String>()
        val outputs = ArrayList<String>()
        for (n in names) {
            val low = n.lowercase()
            if (low !in allowedModalities) {
                throw ConfigError.InvalidValue(
                    "Unknown modality '$n'. Allowed: ${modalityNamesInOrder.joinToString(", ")}"
                )
            }
            when {
                low.endsWith("_input") -> inputs.add(low.removeSuffix("_input"))
                low.endsWith("_output") -> outputs.add(low.removeSuffix("_output"))
            }
        }
        return inputs to outputs
    }

    private fun modalitiesField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.modalities",
            displayName = "Modalities",
            description = "Effective modality list (override applied if set, otherwise inferred from provider / models.dev).",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Arr(effectiveModalities(e).map { ConfigValue.Str(it) })
            },
        )

    private fun modalitiesOverrideField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.modalitiesOverride",
            displayName = "Modalities override",
            description = "User override list. Pass [] or null to clear and fall back to inferred. Allowed: text_input, text_output, image_input, pdf_input, audio_input, video_input, image_output, audio_output, video_output.",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                val ov = e.overrides
                if (ov.inputModalities == null && ov.outputModalities == null) {
                    return@ClosureField ConfigValue.Arr(emptyList())
                }
                val out = LinkedHashSet<String>()
                for (m in (ov.inputModalities ?: emptyList())) appendDirectional(out, m, "input")
                for (m in (ov.outputModalities ?: emptyList())) appendDirectional(out, m, "output")
                ConfigValue.Arr(modalityNamesInOrder.filter { it in out }.map { ConfigValue.Str(it) })
            },
            writer = { v ->
                val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                val names = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                if (names.isEmpty()) {
                    mutate(id) { e ->
                        e.copy(overrides = e.overrides.copy(inputModalities = null, outputModalities = null))
                    }
                    return@ClosureField
                }
                val (inputs, outputs) = decodeModalitiesOverride(names)
                mutate(id) { e ->
                    e.copy(
                        overrides = e.overrides.copy(
                            inputModalities = inputs.ifEmpty { null },
                            outputModalities = outputs.ifEmpty { null },
                        )
                    )
                }
            },
        )

    private fun contextWindowField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.contextWindow",
            displayName = "Context window (tokens)",
            description = "Effective context window in tokens (override applied if set, otherwise from models.dev / provider API).",
            valueSchema = ConfigSchema.Int(),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                val effective = e.overrides.contextWindow ?: e.baseModel.contextWindow ?: 0
                ConfigValue.Int(effective)
            },
        )

    private fun contextWindowOverrideField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.contextWindowOverride",
            displayName = "Context window override (tokens)",
            description = "User override in tokens. 0 clears the override and restores the API value.",
            valueSchema = ConfigSchema.Int(min = 0, max = 10_000_000),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Int(e.overrides.contextWindow ?: 0)
            },
            writer = { v ->
                val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
                mutate(id) { e ->
                    e.copy(overrides = e.overrides.copy(contextWindow = if (i == 0) null else i))
                }
            },
        )

    private fun voiceOverrideField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.voiceOverride",
            displayName = "Voice override",
            description = "User-set TTS voice id. Sent as the `voice` field of /v1/audio/speech (or audio-modal requests). Empty restores the provider default.",
            valueSchema = ConfigSchema.Str(maxLength = 256),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                val v = e.overrides.voiceOverride
                if (v.isNullOrEmpty()) ConfigValue.Null else ConfigValue.Str(v)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { e -> e.copy(overrides = e.overrides.copy(voiceOverride = s.ifEmpty { null })) }
            },
        )

    private fun supportsToolsField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.supportsTools",
            displayName = "Supports tools",
            description = "Derived. True when the model can produce text output.",
            valueSchema = ConfigSchema.Bool,
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Bool("text_output" in effectiveModalities(e))
            },
        )

    private fun supportsVisionField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.supportsVision",
            displayName = "Supports vision",
            description = "Derived. True when the model accepts image input.",
            valueSchema = ConfigSchema.Bool,
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Bool("image_input" in effectiveModalities(e))
            },
        )
}
