package com.openminis.app.ui.chat

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * [T-titlegen-group-order] Which models title generation tries, and in what
 * order. iOS parity: `regenerateTitleCandidates` + `expandSourceForSubTasks`
 * (987f1f70d, 26ccf9cb4).
 *
 * A title call used to reach ONE model. The auto path resolved the title
 * group's first member (or fell back to the chat's model) and called it once
 * per turn; manual Regenerate took the title group's first member, the
 * session's model id, then every other entry. So a rate-limited title model
 * left the chat on "New Chat" until the first-message fallback, and the other
 * members of the group the user had ordered precisely for this — the point of
 * a fallback group — were never asked.
 *
 * Order, deduped by entry id, every candidate available (provider enabled +
 * credentialed + entry visible) and title-eligible:
 *   1  title sub group (`defaultSubGroupId`) — the WHOLE group, router order
 *   2  the session's primary source — its whole group, or its pinned entry
 *   2b only if nothing so far: the session row's model id
 *   2c the default primary group — whole group, router order
 *   3  every other available eligible entry
 *
 * "Router order" is the order the agent loop walks a group on provider
 * failure: the member it would pick first (the preferred/last-used member
 * when still available, else the strategy's pick — first available for
 * `fallback`, session-id hash for `loadBalance`), then the members after it,
 * wrapping around. See ChatViewModel.resolveProviderFromGroup +
 * buildFallbackProviders.
 */
internal object TitleCandidates {

    /**
     * Cap on models tried per title request: enough to exhaust a typical
     * 6-member title group before the first-message fallback, without letting
     * a systemic outage spray every configured model. iOS auto path: 6.
     */
    const val MAX_TRIES = 6

    /** Pause between auto-title tries: rate limits recover in seconds. */
    const val AUTO_PAUSE_MS = 1_500L

    /** Where the session's primary model comes from (ChatSessionEntity.modelBinding). */
    sealed class PrimarySource {
        data class Group(val groupId: String, val preferredEntryId: String?) : PrimarySource()
        data class Entry(val entryId: String) : PrimarySource()

        companion object {
            /** Parse the persisted binding JSON; null for none / unreadable. */
            fun parse(bindingJson: String?): PrimarySource? {
                if (bindingJson.isNullOrBlank()) return null
                return try {
                    val obj = org.json.JSONObject(bindingJson)
                    when (obj.optString("type")) {
                        "group" -> obj.optString("groupId").takeIf { it.isNotEmpty() }
                            ?.let { Group(it, obj.optString("lastEntryId").takeIf { e -> e.isNotEmpty() }) }
                        "entry" -> obj.optString("entryId").takeIf { it.isNotEmpty() }?.let { Entry(it) }
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * T334 title-eligibility, shared by both paths: the model must output
     * text (no declared modalities counts as text) and its id must not name a
     * non-chat capability — those endpoints reject the chat schema or stream
     * nothing useful.
     */
    fun isTitleEligible(entry: ModelEntry): Boolean {
        val outs = entry.model.outputModalities
        val outputsText = outs == null || outs.isEmpty() || outs.contains("text")
        val idLower = entry.model.id.lowercase()
        val nonChatId = listOf("tts", "voiceclone", "voicedesign", "embedding", "embed-", "whisper", "image", "video")
            .any { idLower.contains(it) }
        return outputsText && !nonChatId
    }

    /**
     * [group]'s [available] members (already filtered, in member order) in
     * router order: the first pick, then the rest wrapping around.
     */
    fun expandGroup(
        group: ModelGroup,
        available: List<ModelEntry>,
        sessionId: String,
        preferredEntryId: String? = null,
    ): List<ModelEntry> {
        if (available.isEmpty()) return emptyList()
        val start = available.indexOfFirst { it.id == preferredEntryId }.takeIf { it >= 0 }
            ?: when (group.strategy) {
                RoutingStrategy.loadBalance -> Math.floorMod(sessionId.hashCode(), available.size)
                RoutingStrategy.fallback -> 0
            }
        return List(available.size) { available[(start + it) % available.size] }
    }

    /**
     * Assemble the ordered list from the tiers (see the class doc). Pure: the
     * caller supplies lookups, so the ordering is unit-tested without a
     * repository.
     */
    fun order(
        sessionId: String,
        subGroup: ModelGroup?,
        primary: PrimarySource?,
        sessionModelId: String?,
        defaultPrimaryGroup: ModelGroup?,
        pool: List<ModelEntry>,
        group: (String) -> ModelGroup?,
        entry: (String) -> ModelEntry?,
        availableMembers: (ModelGroup) -> List<ModelEntry>,
        isAvailable: (ModelEntry) -> Boolean,
    ): List<ModelEntry> {
        val ordered = mutableListOf<ModelEntry>()
        val seen = HashSet<String>()
        fun push(e: ModelEntry?) {
            if (e == null || !seen.add(e.id)) return
            if (!isAvailable(e) || !isTitleEligible(e)) return
            ordered += e
        }
        fun pushGroup(g: ModelGroup?, preferred: String? = null) {
            if (g == null) return
            expandGroup(g, availableMembers(g), sessionId, preferred).forEach(::push)
        }

        pushGroup(subGroup)
        when (primary) {
            is PrimarySource.Group -> pushGroup(group(primary.groupId), primary.preferredEntryId)
            is PrimarySource.Entry -> push(entry(primary.entryId))
            null -> Unit
        }
        if (ordered.isEmpty() && !sessionModelId.isNullOrEmpty()) {
            push(pool.firstOrNull { it.model.id == sessionModelId && isAvailable(it) })
        }
        pushGroup(defaultPrimaryGroup)
        pool.forEach(::push)
        return ordered
    }

    /** [order] against the live provider configuration. */
    fun forSession(
        repo: ProviderRepository,
        sessionId: String,
        primary: PrimarySource?,
        sessionModelId: String?,
    ): List<ModelEntry> {
        val config = repo.config.value
        // One credential check per INSTANCE, not per entry: hasAnyCredential
        // reads the (encrypted) key store, and a large catalog has hundreds of
        // entries on a handful of instances — measured 12.5 s for 901 entries
        // on a Pixel 4a before this, on the thread finishing the agent turn.
        val instanceUsable = HashMap<String, Boolean>()
        fun isAvailable(e: ModelEntry): Boolean {
            if (e.isHidden) return false
            return instanceUsable.getOrPut(e.providerInstanceId) {
                val inst = config.instances.find { it.id == e.providerInstanceId }
                inst != null && inst.isEnabled && repo.hasAnyCredential(inst)
            }
        }
        val entriesById = config.modelEntries.associateBy { it.id }
        return order(
            sessionId = sessionId,
            subGroup = repo.defaultSubGroupId?.let { repo.group(it) },
            primary = primary,
            sessionModelId = sessionModelId,
            defaultPrimaryGroup = repo.defaultPrimaryGroupId?.let { repo.group(it) },
            pool = repo.allVisibleEntries(),
            group = { repo.group(it) },
            entry = { id -> entriesById[id] },
            // Same filter as ProviderRepository.availableMemberEntries (hidden /
            // disabled / uncredentialed dropped, member order kept), through
            // the memoized check.
            availableMembers = { g -> g.memberEntryIds.mapNotNull { entriesById[it] }.filter(::isAvailable) },
            isAvailable = ::isAvailable,
        )
    }

    /**
     * Build a provider for one candidate: usable key (keyless self-hosted
     * endpoints and OAuth instances included), OAuth token refreshed first so
     * an expired cached token does not 401 the title call, tagged with the
     * chat id (OpenCode Go needs `x-opencode-session`). Null when the entry
     * cannot be driven at all — the walk then moves on.
     */
    suspend fun providerFor(
        repo: ProviderRepository,
        context: Context,
        entry: ModelEntry,
        sessionId: String,
    ): LLMProvider? {
        val instance = repo.instance(entry.providerInstanceId) ?: return null
        var apiKey = repo.usableApiKey(instance)
            ?: if (repo.hasAnyCredential(instance)) "" else return null
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val fresh = manager?.validAccessToken()
                if (fresh != null && fresh != apiKey) {
                    repo.saveApiKey(instance.id, fresh)
                    apiKey = fresh
                }
            } catch (e: Exception) {
                Log.w("TitleGen", "OAuth refresh failed for ${entry.model.id}: ${e.message}")
            }
        }
        return try {
            ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = sessionId, overrides = entry.overrides)
        } catch (e: Exception) {
            Log.w("TitleGen", "provider creation failed for ${entry.model.id}: ${e.message}")
            null
        }
    }

    /**
     * [T-android-titlegen-stream-error-walk] Whether [e] is the caller being
     * cancelled, rather than one model failing.
     *
     * Providers report a mid-stream failure (an inline SSE `error` frame such
     * as OpenRouter's 429, `response.failed`, a stream that closed empty) by
     * cancelling their producer with the real LLMError as the cause, so the
     * collector sees a CancellationException that is NOT a cancellation of
     * this coroutine. Rethrowing every CancellationException ended the whole
     * walk on the first such error and never tried the next title model — the
     * exact report 0047dc76c set out to fix. Same rule as the agent loop
     * (`e is CancellationException && e.cause == null`), plus the coroutine's
     * own state, so a real cancellation that happens to carry a cause still
     * propagates.
     */
    suspend fun isRealCancellation(e: kotlinx.coroutines.CancellationException): Boolean =
        e.cause == null || !kotlinx.coroutines.currentCoroutineContext().isActive

    /**
     * Walk [candidates] (at most [maxTries]) until [tryOne] returns non-null.
     * A throw or a null result is a failure and moves on to the next model —
     * an empty or unparseable title counts too. [shouldStop] is checked before
     * each try (e.g. the user renamed the chat meanwhile). [pauseMs] separates
     * tries. Returns the first success, or null when every try failed.
     */
    suspend fun <T : Any> walk(
        candidates: List<ModelEntry>,
        origin: String,
        maxTries: Int = MAX_TRIES,
        pauseMs: Long = 0L,
        shouldStop: suspend () -> Boolean = { false },
        tryOne: suspend (ModelEntry) -> T?,
    ): T? {
        val tries = candidates.take(maxTries)
        for ((idx, entry) in tries.withIndex()) {
            if (shouldStop()) {
                AppLogger.info("TitleGen", "walk origin=$origin stopped before candidate ${idx + 1}/${tries.size}")
                return null
            }
            AppLogger.info("TitleGen", "walk origin=$origin candidate ${idx + 1}/${tries.size} model=${entry.model.id}")
            val result = try {
                tryOne(entry)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && isRealCancellation(e)) throw e
                AppLogger.warning(
                    "TitleGen",
                    "walk origin=$origin FAILED ${idx + 1}/${tries.size} model=${entry.model.id} " +
                        "${e.javaClass.simpleName}: ${e.message?.take(200)}",
                )
                null
            }
            if (result != null) {
                if (idx > 0) AppLogger.info("TitleGen", "walk origin=$origin succeeded on fallback candidate ${idx + 1} (${entry.model.id})")
                return result
            }
            if (pauseMs > 0 && idx + 1 < tries.size) delay(pauseMs)
        }
        AppLogger.warning("TitleGen", "walk origin=$origin all ${tries.size} candidate(s) failed (of ${candidates.size})")
        return null
    }
}
