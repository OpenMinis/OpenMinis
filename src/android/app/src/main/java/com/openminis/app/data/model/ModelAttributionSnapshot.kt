package com.openminis.app.data.model

/**
 * [T-token-attribution-snapshot] An immutable record of which model produced a
 * message, captured when the message is written.
 *
 * ## Why a snapshot rather than an id to look up later
 *
 * The Usage page used to derive a message's model by joining
 * `sessions.model_id` — one mutable column per session, rewritten on every
 * model switch (and by automatic failover, which the user never sees). The
 * join has no time dimension, so a session's entire history was re-attributed
 * to whatever model it happened to point at when the page was opened: run a
 * billion tokens through deepseek, switch to grok, and the billion moved to
 * grok. Switch back and it moved back.
 *
 * Storing the id alone would only half-fix it. An id still has to be resolved
 * against the live provider configuration, and that configuration is also
 * mutable: deleting a provider instance also removes its `modelEntries`, which
 * for a CUSTOM model is the only place its display name exists. The history
 * would survive but become unreadable — "gpt-4o-mine" collapsing into an
 * Unknown bucket.
 *
 * So this carries everything the Usage page needs to render a row without
 * consulting current config at all. Configuration describes what you can use
 * now; usage describes what happened. The second must not depend on the first.
 *
 * The cost is ~40-60 bytes per message and the fact that renaming a model does
 * not retroactively rename it in history. The latter is intended: history
 * should show the name that was in effect at the time.
 */
data class ModelAttributionSnapshot(
    /** `LLMModel.id`, e.g. `"deepseek-v4-pro"`. Stable across config edits. */
    val modelId: String,
    /** Display name as of the request, e.g. `"DeepSeek V4 Pro"`. */
    val displayName: String,
    /**
     * `ProviderType` **rawValue** (e.g. `"openAI"`), never a localized display
     * name. Grouping on display strings is what left the iOS Usage page with
     * `"Google"` / `"Gemini"` / `"Google Gemini"` as three separate sections.
     */
    val providerTypeRaw: String,
    /**
     * The provider instance uuid. Diagnostics and disambiguation only (the
     * same model id can exist under two instances); the UI never resolves
     * through it, so a deleted instance costs nothing.
     */
    val providerInstanceId: String?,
)
