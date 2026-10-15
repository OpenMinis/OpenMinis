package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val TITLE_MAX_ATTEMPTS = 3
// [T-android-auto-grouping-injection] Bounds on the group list folded into
// the title-generation prompt. 30 groups × ~140 chars keeps the segment
// well under a KB even in the worst case; the description cap matches
// FolderEntity.DESC_MAX_CHARS, which is enforced at creation time but not
// on rows written by older builds or by the AI-Suggest path.
private val GROUP_CONTEXT_MAX = 30
private val GROUP_NAME_MAX = 40
private val GROUP_DESC_MAX = 100

internal fun ChatViewModel.generateSessionTitleIfNeeded() {
    // [T-android-titlegen-diag-logging] Unified "TitleGen" trail across
    // every path of this function — XIN 40454 reported sessions silently
    // staying "New Chat" and the failure paths were under-logged.
    // Logging only; no logic change.
    AppLogger.info(
        "TitleGen",
        "enter session=${realSessionId.ifEmpty { sessionId }} attempts=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
            "inFlight=$titleGenerationInFlight currentTitle='${_sessionTitle.value.take(200)}'",
    )
    if (titleGenerationInFlight || titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
        AppLogger.info(
            "TitleGen",
            "skip guard=${if (titleGenerationInFlight) "inFlight" else "max-attempts ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS)"}",
        )
        return
    }
    // Skip if title already set (not "New Chat")
    if (_sessionTitle.value != "New Chat" && _sessionTitle.value.isNotEmpty()) {
        AppLogger.info("TitleGen", "skip guard=title-already-set title='${_sessionTitle.value.take(200)}'")
        return
    }
    // Prefer dedicated sub-models (cheap, non-OAuth) — mirrors iOS resolveSubEntry.
    // [T-android-title-failover] Upstream 1.14: "A failed title model hands
    // over to the next one." The chain is EVERY usable sub-group member in
    // group order, then the primary provider — a failing candidate (request
    // error / timeout / empty / unparseable response) advances to the next
    // candidate instead of retrying the same one until the attempt budget
    // burns out. First-message fallback stays the terminal state.
    val candidates = titleCandidateChain()
    if (candidates.isEmpty()) {
        AppLogger.warning("TitleGen", "no title candidate available (sub-group + current both empty) — fallback-to-first-message path")
        viewModelScope.launch(Dispatchers.IO) {
            applyFallbackTitleFromFirstMessage("no provider available")
        }
        return
    }

    titleGenerationInFlight = true
    titleGenerationAttempts++

    // [T-titlegen-context-first-last-pair] Build the summary from the first
    // user + first assistant message, and — when the session has more than
    // one user turn — also the last user + last assistant message, each
    // truncated to 200 chars. This lets the title adapt when the topic
    // shifts later in a long session, instead of only seeing the opener.
    val msgs = _messages.value
    val userMessages = msgs.filter { it.role == "user" }
    val firstUser = userMessages.firstOrNull()
    if (firstUser == null) {
        AppLogger.warning("TitleGen", "skip guard=no-user-message (nothing to summarize)")
        titleGenerationInFlight = false
        return
    }
    val userText = firstUser.content.take(200)
    // First/last assistant *text* message — skip tool-only capsules whose
    // content is blank so the summary carries real assistant prose.
    val assistantTextMessages = msgs.filter { it.role == "assistant" && it.content.isNotBlank() }
    val firstAssistantText = assistantTextMessages.firstOrNull()?.content?.take(200) ?: ""
    // Only append the last pair when there is more than one user turn (i.e.
    // the first and last user messages differ) — avoids duplicating the
    // opener when the session is a single exchange.
    val hasMultipleUserTurns = userMessages.size > 1
    val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.content?.take(200) ?: "" else ""
    val lastAssistantText = if (hasMultipleUserTurns) assistantTextMessages.lastOrNull()?.content?.take(200) ?: "" else ""

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Mirror iOS callSubModelForTitle prompt shape: short cacheable system
            // prompt + user-message payload. Using the exact iOS strings keeps the
            // Anthropic prompt cache warm across title-gen calls.
            // [T-android-auto-grouping] Fold the group question into THIS
            // call — no second round-trip. With the toggle off, or with no
            // groups to offer, the prompt is byte-identical to the
            // title-only form (so the Anthropic prompt cache stays warm).
            // Port of iOS callSubModelForTitle's folderSegment.
            val autoGroupOn = com.openminis.app.ui.settings
                .autoGroupingEnabled(context)
            val groupContext: List<String> = if (!autoGroupOn) emptyList() else {
                // [T-android-auto-grouping-injection] Bound the group list.
                // Group names are free-form user input with no length or
                // content limit (only the description is capped, and only
                // at creation time), and they are interpolated into an
                // instruction — a name containing `" ].` can close the
                // bracket and inject directives. Unbounded COUNT is the
                // other half: 200 groups would add multiple KB to EVERY
                // title generation. Newest-first (listFolders is ORDER BY
                // updated_at DESC), so the cut drops the stalest groups.
                //
                // Dedup on the RENDERED name, not the raw one: sanitizing
                // and truncating to GROUP_NAME_MAX can map two distinct
                // folders onto the same string ("…Machine Learning Reading"
                // and "…Machine Learning Writing" share a 40-char prefix).
                // Offering that string twice would make the model's answer
                // unresolvable, so drop every name that is ambiguous after
                // rendering rather than filing the chat into a coin-flip
                // winner. Same reason the apply side refuses ambiguous
                // matches.
                val rendered = chatRepository.listFolders()
                    .map { f -> f to ChatViewModel.promptSafe(f.name, GROUP_NAME_MAX) }
                    .filter { (_, n) -> n.isNotEmpty() }
                val ambiguous = rendered
                    .groupingBy { (_, n) -> n.lowercase() }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                rendered
                    .filterNot { (_, n) -> n.lowercase() in ambiguous }
                    .take(GROUP_CONTEXT_MAX)
                    .map { (f, n) ->
                        // "name — one-sentence description" when the group
                        // has one; the description exists precisely to
                        // sharpen this membership judgment.
                        val d = f.description?.takeIf { it.isNotBlank() }
                            ?.let { ChatViewModel.promptSafe(it, GROUP_DESC_MAX) }
                            ?.takeIf { it.isNotEmpty() }
                        if (d != null) "\"$n\" — $d" else "\"$n\""
                    }
            }
            val prompt = buildString {
                append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                if (groupContext.isNotEmpty()) {
                    // The null path is spelled out and given its own
                    // example: a closed option list pushes sub-models
                    // toward always picking something, and a wrongly-filed
                    // session costs far more than a wrong category (which
                    // only drives a row icon).
                    append("The user organizes chats into groups. Existing groups: [")
                    append(groupContext.joinToString("; "))
                    append("]. If this conversation clearly belongs to one of these groups, ")
                    append("set \"folder\" to that exact group name. ")
                    append("If it does not clearly match any group, or you are unsure, set \"folder\" to null. ")
                    append("Never invent a new group name.\n\n")
                }
                append("You MUST respond with valid JSON only. Example:\n")
                if (groupContext.isEmpty()) {
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                } else {
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\", \"folder\": null}\n\n")
                }
                append("Conversation:\n")
                append("User: $userText\n")
                if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                append(titleLanguageDirective())
            }
            // [T-android-titlegen-systemprompt-unify] Shared with the manual
            // Regenerate path (SessionListViewModel.regenerateTitle) via the
            // single TITLE_GEN_SYSTEM_PROMPT constant so the two never drift.
            // Passed bare: for OAuth Anthropic instances,
            // AnthropicProvider.resolveSystemPrompt force-prepends the Claude
            // Code prefix block at the provider layer (and strips a
            // caller-supplied one), so no caller-side prepend is needed — the
            // previous manual prefix branch here was redundant.
            val effectiveSystemPrompt = TITLE_GEN_SYSTEM_PROMPT

            // [T-android-title-failover] Walk the candidate chain: every
            // usable sub-group member in group order, then the primary
            // provider. A failing candidate (request error / timeout /
            // empty / unparseable response) hands over to the next one —
            // upstream 1.14 parity. The first-message fallback only fires
            // after the WHOLE chain is exhausted.
            var lastFailureReason = "unknown"
            for ((candIdx, cand) in candidates.withIndex()) {
                val provider = cand.provider
                try {
                    AppLogger.info(
                        "TitleGen",
                        "dispatch attempt=$titleGenerationAttempts candidate=${candIdx + 1}/${candidates.size} " +
                            "provider=${provider.javaClass.simpleName} model=${provider.model.id} source=${cand.source}",
                    )
                    // [T-android-titlegen-reasoning] Match iOS callSubModelForTitle:
                    // explicitly disable thinking (thinkingLevel = OFF). The provider
                    // layer's injectThinkingParams honors OFF — e.g. DeepSeek V4 gets
                    // an explicit {"thinking":{"type":"disabled"}}, o-series/gpt-5
                    // omit reasoning_effort, Anthropic sends no thinking block — so a
                    // reasoning sub-model doesn't burn the whole budget on hidden
                    // thinking and return empty text. As a belt-and-suspenders for
                    // models where OFF is still a no-op (e.g. Qwen3, which thinks by
                    // default), keep the T334 budget bump so it can finish thinking
                    // and still emit the JSON. Unified with regenerateTitle's ladder.
                    val titleMaxTokens = if (provider.model.supportsReasoning == true) 2048 else 100
                    val response = provider.sendMessage(
                        messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                        systemPrompt = effectiveSystemPrompt,
                        maxTokens = titleMaxTokens,
                        temperature = cand.temperature,
                        thinkingLevel = ThinkingLevel.OFF,
                    )

                    AppLogger.info(
                        "TitleGen",
                        "response stopReason=${response.stopReason} textLen=${response.text.length} " +
                            "raw='${response.text.take(200).replace("\n", "\\n")}'",
                    )
                    val (title, category, folderName) = parseTitleResponse(response.text)
                    if (title.isNotEmpty()) {
                        val sid = realSessionId.ifEmpty { sessionId }
                        chatRepository.updateSessionTitleAndCategory(sid, title, category)
                        withContext(Dispatchers.Main) {
                            _sessionTitle.value = title
                            _sessionCategory.value = category
                        }
                        AppLogger.info("TitleGen", "outcome=set title='$title' category='$category'")
                        // [T-android-auto-grouping] Group assignment is best-effort
                        // and strictly SUBORDINATE to the title: a name that
                        // resolves to nothing is silently dropped (never create a
                        // group from a model's answer), and setFolderIfUnfiled
                        // means a hand-filed session is never overridden by the
                        // model's guess. Mirrors iOS.
                        if (!folderName.isNullOrEmpty()) {
                            // findFolderByName rather than a local equals: it trims
                            // BOTH sides (models routinely echo "Work " with a
                            // trailing space, which an exact equals silently
                            // rejects — the feature then looks intermittently
                            // broken), and because listFolders is ORDER BY
                            // updated_at DESC its first hit is the most recently
                            // updated duplicate. That matters when two devices
                            // created a same-named group offline: an arbitrary pick
                            // files the chat into the group the user isn't looking
                            // at. Same resolver the AI-Suggest flow uses.
                            val match = chatRepository.findFolderByName(folderName)
                                // The prompt shows SANITIZED names, so a group whose
                                // real name contains stripped characters comes back
                                // in its sanitized form and won't match directly.
                                // Fall back to comparing sanitized-to-sanitized —
                                // but REFUSE an ambiguous hit rather than taking the
                                // first. Two folders can render to the same string
                                // (truncation at GROUP_NAME_MAX, or names differing
                                // only in stripped characters); filing into an
                                // arbitrary one is a silent wrong-group move the
                                // user gets no signal about. Leaving it ungrouped is
                                // the recoverable outcome.
                                ?: chatRepository.listFolders().filter {
                                    ChatViewModel.promptSafe(it.name, GROUP_NAME_MAX)
                                        .equals(folderName.trim(), ignoreCase = true)
                                }.singleOrNull()
                            if (match != null) {
                                val applied = chatRepository.setFolderIfUnfiled(match.id, sid)
                                AppLogger.info(
                                    "TitleGen",
                                    "auto-group '$folderName' -> ${match.id.take(8)} applied=$applied",
                                )
                            } else {
                                AppLogger.info(
                                    "TitleGen",
                                    "auto-group: model offered '$folderName' but no group matches — leaving ungrouped",
                                )
                            }
                        }
                        // [T-android-title-failover] Success — stop walking the chain.
                        return@launch
                    } else {
                        // [T-android-title-failover] The request succeeded but
                        // yielded no usable title — empty body or a response
                        // parseTitleResponse couldn't extract a title from (e.g.
                        // reasoning model that spent its whole budget thinking, or
                        // non-JSON output). Hand over to the next candidate; the
                        // first-message fallback only fires after the whole chain
                        // is exhausted.
                        lastFailureReason = "empty/unparseable title response (stopReason=${response.stopReason} textLen=${response.text.length})"
                        AppLogger.warning(
                            "TitleGen",
                            "outcome=candidate-failed candidate=${candIdx + 1}/${candidates.size} " +
                                "model=${provider.model.id} $lastFailureReason",
                        )
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // Propagate cancellation to the outer handler — the
                    // finally block's !isActive path lands the fallback.
                    throw ce
                } catch (e: Exception) {
                    // [T-android-title-failover] Request error / timeout /
                    // provider failure on THIS candidate — contained here,
                    // the chain advances to the next one.
                    lastFailureReason = "${e.javaClass.simpleName}: ${e.message?.take(200)}"
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=candidate-failed candidate=${candIdx + 1}/${candidates.size} " +
                            "model=${provider.model.id} $lastFailureReason",
                    )
                }
            }
            // [T-android-title-failover] Chain exhausted — every candidate
            // failed. Terminal state: first-message fallback.
            AppLogger.warning(
                "TitleGen",
                "outcome=gave-up — all ${candidates.size} title candidates failed ($lastFailureReason) — applying first-message fallback title",
            )
            applyFallbackTitleFromFirstMessage("all title candidates failed: $lastFailureReason")
        } catch (e: Exception) {
            // [T-title-gen-fallback-first-message-android] Only
            // CancellationException escapes the per-candidate loop now —
            // request failures are contained per candidate above. Keep the
            // terminal logging for the cancellation path.
            AppLogger.warning(
                "TitleGen",
                "outcome=exception attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)}",
            )
            if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                applyFallbackTitleFromFirstMessage("request failed: ${e.message?.take(200)}")
            }
        } finally {
            titleGenerationInFlight = false
            // [GH#210] Cancellation used to be the one exit that produced
            // NOTHING — no title, no fallback, and no terminal log line, so
            // a dispatched attempt simply vanished. That silence is how
            // this bug stayed invisible: the logs showed 6 dispatches and
            // 5 outcomes with no failure in between.
            //
            // Now a cancelled attempt still lands the first-user-message
            // fallback, so leaving the chat mid-request can no longer strand
            // a session on "New Chat".
            //
            // NonCancellable is load-bearing: we are already in a cancelled
            // scope, so without it the very first suspension point inside
            // applyFallbackTitleFromFirstMessage (the DB write) would throw
            // CancellationException again and write nothing — a fallback
            // that silently never runs is worse than none, because the log
            // line would claim it did.
            if (!isActive) {
                AppLogger.warning(
                    "TitleGen",
                    "outcome=cancelled attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                        "session=${(realSessionId.ifEmpty { sessionId }).take(8)} " +
                        "reason=scope-cancelled — applying first-message fallback",
                )
                withContext(NonCancellable) {
                    applyFallbackTitleFromFirstMessage("scope cancelled")
                }
            }
        }
    }
}

/**
 * [T-title-gen-fallback-first-message-android] Set the session title to a
 * cleaned-up truncation of the first user message when LLM title generation
 * fails (request error / timeout / empty / parse failure / model
 * unavailable). Strips the trailing `<user-attached-files>` XML block,
 * collapses whitespace/newlines to single spaces, and clamps to ~30 chars
 * with an ellipsis — matching the title norm (single-line, short). No-op
 * (logged) when there's no usable first-message text.
 *
 * [GH#210] Re-reads the session from the DB first and bails if it already
 * carries a real title, mirroring iOS `applyFallbackTitle` (76e4c07bc).
 * This is not defensive noise: the title request runs 22–51s against a real
 * provider, and every caller of this function is a FAILURE exit reached at
 * the end of that window. The user has had all that time to rename the
 * session by hand, and a manual rename must always win over a machine
 * fallback derived from the opening message.
 */
private suspend fun ChatViewModel.applyFallbackTitleFromFirstMessage(reason: String) {
    val sidForCheck = realSessionId.ifEmpty { sessionId }
    val existing = chatRepository.getSession(sidForCheck)?.title?.trim()
    if (!existing.isNullOrEmpty() && existing != "New Chat") {
        AppLogger.info(
            "TitleGen",
            "outcome=fallback-skipped session=${sidForCheck.take(8)} reason=already-titled",
        )
        return
    }
    val raw = _messages.value.firstOrNull { it.role == "user" }?.content
    var text = raw ?: ""
    // Drop the <user-attached-files> XML the composer appends so the title
    // reflects what the user actually typed, not the attachment manifest.
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx >= 0) {
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        text = if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }
    // Collapse all whitespace (incl. newlines) to single spaces, trim.
    val cleaned = text.replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) {
        AppLogger.warning(
            "TitleGen",
            "outcome=fallback-unavailable session=${sidForCheck.take(8)} " +
                "reason=first-user-message-empty-after-cleanup ($reason)",
        )
        return
    }
    val fallbackTitle = if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
    chatRepository.updateSessionTitle(sidForCheck, fallbackTitle)
    withContext(Dispatchers.Main) {
        _sessionTitle.value = fallbackTitle
    }
    // Length only — never the user's prompt text.
    AppLogger.info(
        "TitleGen",
        "outcome=fallback session=${sidForCheck.take(8)} titleLen=${fallbackTitle.length} reason=$reason",
    )
}

/**
 * The temperature override for a model entry (null = provider default).
 * Shared by the title failover chain and other sampling paths.
 */
internal fun ChatViewModel.samplingTemperature(entryId: String?): Double? {
    if (entryId.isNullOrBlank()) return null
    return providerRepository.config.value.modelEntries
        .firstOrNull { it.id == entryId }
        ?.overrides
        ?.temperature
}

/** One failover candidate for title generation. */
private data class TitleCandidate(
    val provider: LLMProvider,
    val temperature: Double?,
    /** "sub" (sub-group member) or "primary" (current conversation provider). */
    val source: String,
)

/**
 * [T-android-title-failover] Pure ordering/dedup core for the title-model
 * failover chain, extracted so the contract is unit-testable without a VM:
 * sub-group order preserved, duplicates on the same instance+model dropped
 * (first occurrence wins), primary appended once at the tail. Generic in the
 * candidate payload so tests can drive it with plain strings.
 */
internal object TitleFailoverChain {
    fun <T> plan(subs: List<Pair<String, T>>, primary: Pair<String, T>?): List<T> {
        val out = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        for ((key, candidate) in subs) {
            if (seen.add(key)) out.add(candidate)
        }
        if (primary != null && seen.add(primary.first)) out.add(primary.second)
        return out
    }
}

/**
 * [T-android-title-failover] The title-model failover chain: every usable
 * member of the configured sub-group in group order, then the primary
 * provider. Deduped on instance+model so a primary that is also a sub-group
 * member is not tried twice. Upstream 1.14 parity: a failed title model
 * hands over to the next one instead of being retried until the attempt
 * budget burns out.
 */
private fun ChatViewModel.titleCandidateChain(): List<TitleCandidate> {
    val subs: List<Pair<String, TitleCandidate>> = providerRepository.resolveTitleSubGroupEntries()
        .mapNotNull { entry ->
            buildTitleProviderForEntry(entry)?.let { provider ->
                "${entry.providerInstanceId}:${entry.model.id}" to
                    TitleCandidate(provider, entry.overrides.temperature, "sub")
            }
        }
    val primaryPair: Pair<String, TitleCandidate>? = currentProvider?.let { p ->
        val activeEntry = providerRepository.config.value.modelEntries
            .firstOrNull { it.id == _activeEntryId.value }
        "${activeEntry?.providerInstanceId ?: "primary"}:${p.model.id}" to
            TitleCandidate(p, samplingTemperature(_activeEntryId.value), "primary")
    }
    return TitleFailoverChain.plan(subs, primaryPair)
}

/**
 * Build a provider for one sub-group entry with the OAuth pre-refresh the
 * single-candidate path had. Returns null when the instance or its
 * credential is unusable — the chain then skips this member.
 */
private fun ChatViewModel.buildTitleProviderForEntry(entry: ModelEntry): LLMProvider? {
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
    // [T-android-keyless-provider-selection] usableApiKey — a keyless
    // self-hosted sub-model is usable; loadApiKey returned null and made
    // title generation silently fall back. See QuickTestSheet.
    var apiKey = providerRepository.usableApiKey(instance) ?: return null

    // [T-android-titlegen-oauth-refresh] Refresh the OAuth token before
    // building the provider — matches the manual Regenerate path
    // (SessionListViewModel.regenerateTitle) and iOS. Without this, an
    // OAuth sub-model (e.g. OAuth Anthropic Claude Code) with an expired
    // cached token would 401 during auto-title-gen and silently drop to the
    // first-message fallback title. A refresh failure is non-fatal: log it
    // and proceed with the stale token so the request's own 401 flows into
    // the existing error/fallback handling. runBlocking is safe here — this
    // is only reached from the suspend agent loop on a background thread.
    if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
        try {
            val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            val freshToken = kotlinx.coroutines.runBlocking { manager?.validAccessToken() }
            if (freshToken != null && freshToken != apiKey) {
                providerRepository.saveApiKey(instance.id, freshToken)
                apiKey = freshToken
            }
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "TitleGen OAuth refresh failed: ${e.message}")
        }
    }
    return ProviderFactory.create(instance, apiKey, entry.model, context)
}

/** Parse LLM response for title/category JSON. Multiple fallback strategies. */
private fun ChatViewModel.parseTitleResponse(text: String): TitleGenResult {
    val cleaned = text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    // Try JSON parse
    try {
        val json = JSONObject(cleaned)
        val title = json.optString("title", "").trim()
        val category = json.optString("category", "").trim().ifEmpty { null }
        // [T-android-auto-grouping] `folder` is absent whenever
        // auto-grouping is off, and JSON null when the model declined to
        // file the chat — optString maps both to "" → null here.
        val folder = json.optString("folder", "").trim()
            .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        if (title.isNotEmpty()) return TitleGenResult(title, category, folder)
    } catch (_: Exception) {}
    // Regex fallback: extract "title" value
    val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    val folderMatch = Regex("\"folder\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    if (titleMatch != null) {
        return TitleGenResult(
            titleMatch.groupValues[1].trim(),
            catMatch?.groupValues?.getOrNull(1)?.trim(),
            folderMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { !it.equals("null", ignoreCase = true) },
        )
    }
    // Plain text fallback: use first line
    val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
    return TitleGenResult(firstLine.take(50), null, null)
}

/** Parsed title-generation payload. [folder] is non-null only when
 *  auto-grouping asked for it AND the model named an existing group. */
private data class TitleGenResult(
    val title: String,
    val category: String?,
    val folder: String?,
)
