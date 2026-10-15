package com.openminis.app.ui.chat

import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.chat.ChatViewModel.CompactAttempt

internal fun ChatViewModel.compactStagePlan(): List<CompactAttempt> {
    val sessionProvider = currentProvider
    val sessionModel = currentModel
    if (sessionProvider == null || sessionModel == null) return emptyList()
    val sessionName = sessionModel.displayName.ifBlank { sessionModel.id }
    val sessionTemperature = samplingTemperature(_activeEntryId.value)
    // [T-compact-fallback-walk] Every usable member of the compact fallback
    // group becomes its own stage — upstream 1.15-beta.3: "failure falls back
    // to other models in the group". Taking only the first candidate made a
    // single broken fallback model indistinguishable from having no fallback
    // configured at all.
    val fallbacks = providerRepository.resolveCompactFallbackCandidates()
        .filter { it.second.model.id != sessionModel.id }
        .mapNotNull { (instance, entry) ->
            val apiKey = runCatching { providerRepository.usableApiKey(instance) }.getOrNull()
            val provider = apiKey?.let {
                runCatching { ProviderFactory.create(instance, it, entry.model, context) }.getOrNull()
            } ?: return@mapNotNull null
            CompactAttempt(
                kind = "fallback",
                label = entry.model.displayName.ifBlank { entry.model.id },
                provider = provider,
                model = entry.model,
                nextHint = "truncate",
                temperature = entry.overrides.temperature,
            )
        }
    return wireCompactStages(
        session = CompactAttempt(
            kind = "session",
            label = sessionName,
            provider = sessionProvider,
            model = sessionModel,
            nextHint = "truncate",
            temperature = sessionTemperature,
        ),
        fallbacks = fallbacks,
    )
}

/**
 * Pure stage-list assembly for the two-shot compaction walk.
 *
 * Contract:
 * - The session model is always stage 1.
 * - Each fallback group member gets one stage, in group order.
 * - With no usable fallback, the second stage retries the session model
 *   (the pre-walk behaviour) so the two-shot shape is preserved.
 * - `nextHint` tells the progress headline what happens if THIS stage
 *   fails: "fallback" = another model follows, "session-retry" = the
 *   current model is retried, "truncate" = this is the last stage.
 */
internal fun wireCompactStages(
    session: CompactAttempt,
    fallbacks: List<CompactAttempt>,
): List<CompactAttempt> {
    if (fallbacks.isEmpty()) {
        return listOf(
            session.copy(nextHint = "session-retry"),
            session.copy(kind = "session-retry", nextHint = "truncate"),
        )
    }
    return listOf(session.copy(nextHint = "fallback")) +
        fallbacks.mapIndexed { index, stage ->
            stage.copy(nextHint = if (index == fallbacks.lastIndex) "truncate" else "fallback")
        }
}
