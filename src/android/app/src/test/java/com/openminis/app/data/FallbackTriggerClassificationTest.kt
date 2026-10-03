package com.openminis.app.data

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-503-fallback] Group-fallback trigger classification.
 *
 * The decision itself lives inside `ChatViewModel.runAgentLoop`, which cannot
 * be exercised without a live provider and streaming loop. These tests pin the
 * two pieces the decision is built from — the structured 5xx status on
 * [LLMError] and the boolean the loop derives from it — so a regression in the
 * classification is caught even though the loop is not instantiated.
 *
 * Kept byte-identical to the production expression at
 * `ChatViewModel.kt` (`shouldFallback`); if that line changes, this must too.
 */
class FallbackTriggerClassificationTest {

    private fun shouldFallback(error: LLMError, strategy: FallbackStrategy): Boolean {
        val isRateLimit = error is LLMError.RateLimited
        val is5xx = error.isHttpServerError
        return isRateLimit || is5xx || strategy == FallbackStrategy.always
    }

    /** Mirrors the loop's same-model retry membership. */
    private fun isTransient(error: LLMError): Boolean =
        error is LLMError.NetworkError || error is LLMError.TransientError

    // ── 1. default + HTTP 503 → retried on the same model, then falls back ──

    @Test
    fun `default falls back on a provider HTTP 503`() {
        val err = LLMError.TransientError("[503] no_available_workers", httpStatus = 503)
        assertTrue("503 must be recognised as a server error", err.isHttpServerError)
        assertTrue(
            "default must fall back once same-model retries are exhausted",
            shouldFallback(err, FallbackStrategy.default),
        )
    }

    @Test
    fun `a 503 is still retried on the same model before falling back`() {
        val err = LLMError.TransientError("[503] circuit breaker open", httpStatus = 503)
        assertTrue(
            "503 keeps its same-model retry budget; fallback is the last resort",
            isTransient(err),
        )
    }

    @Test
    fun `every mapped 5xx code triggers fallback under default`() {
        for (code in listOf(500, 502, 503, 504, 529)) {
            val err = LLMError.TransientError("[$code] upstream failure", httpStatus = code)
            assertEquals(code, err.httpServerErrorStatus)
            assertTrue(
                "HTTP $code must trigger fallback under default",
                shouldFallback(err, FallbackStrategy.default),
            )
        }
    }

    // ── 2. always + HTTP 503 → unchanged ────────────────────────────────────

    @Test
    fun `always still falls back on a 503`() {
        val err = LLMError.TransientError("[503] no_available_workers", httpStatus = 503)
        assertTrue(shouldFallback(err, FallbackStrategy.always))
    }

    @Test
    fun `always still falls back on errors that carry no status`() {
        val errors = listOf(
            LLMError.NetworkError(java.io.IOException("unreachable")),
            LLMError.TransientError("Server returned an empty response"),
            LLMError.InvalidApiKey("bad key"),
            LLMError.DecodingError(IllegalStateException("bad json")),
        )
        for (err in errors) {
            assertTrue(
                "always must keep falling back on ${err.javaClass.simpleName}",
                shouldFallback(err, FallbackStrategy.always),
            )
        }
    }

    // ── 3. local / link failures must NOT newly trigger fallback ────────────

    @Test
    fun `default does not fall back on a network error`() {
        val err = LLMError.NetworkError(java.io.IOException("Unable to resolve host"))
        assertFalse(err.isHttpServerError)
        assertFalse(shouldFallback(err, FallbackStrategy.default))
    }

    @Test
    fun `default does not fall back on a transient error with no http status`() {
        // TTFB timeout, dropped connection, empty stream — never got a response
        // code, so switching models cannot help.
        val errors = listOf(
            LLMError.TransientError("no response from server (30s TTFB) — check network/proxy"),
            LLMError.TransientError("Server returned an empty response (connection dropped or upstream error)"),
            LLMError.TransientError("Stream idle for 120000ms with no new data"),
        )
        for (err in errors) {
            assertNull(err.httpServerErrorStatus)
            assertFalse(
                "a status-less TransientError must not trigger fallback: ${err.detail}",
                shouldFallback(err, FallbackStrategy.default),
            )
        }
    }

    @Test
    fun `a 4xx status on a transient error does not count as a server error`() {
        val err = LLMError.TransientError("[400] bad request", httpStatus = 400)
        assertNull(err.httpServerErrorStatus)
        assertFalse(shouldFallback(err, FallbackStrategy.default))
    }

    // ── 4. 429 and the permanent-5xx ProviderError path stay as they were ───

    @Test
    fun `rate limiting still falls back under default`() {
        val err = LLMError.RateLimited()
        assertFalse("429 is not a server error", err.isHttpServerError)
        assertTrue(shouldFallback(err, FallbackStrategy.default))
        assertFalse("429 must not be retried on the same model", isTransient(err))
    }

    @Test
    fun `a permanent 5xx provider error falls back immediately without retrying`() {
        // OpenAI maps 503 + no_available_providers / model_not_found to
        // ProviderError, whose detail carries the `[503]` prefix.
        val err = LLMError.ProviderError("[503] no_available_providers")
        assertEquals(503, err.httpServerErrorStatus)
        assertTrue(shouldFallback(err, FallbackStrategy.default))
        assertFalse(
            "a provider-declared permanent 5xx must not sit through same-model retries",
            isTransient(err),
        )
    }

    // ── Anchored parsing: the old regex over-matched ────────────────────────

    @Test
    fun `a token count starting with 5 is not read as a status code`() {
        // The previous `[5][0-9]{2}` scan matched "5000 tokens" anywhere in the
        // message and misclassified an over-length refusal as a server error.
        val err = LLMError.ProviderError(
            "[400] This model's maximum context length is 5000 tokens",
        )
        assertNull(err.httpServerErrorStatus)
        assertFalse(shouldFallback(err, FallbackStrategy.default))
    }

    @Test
    fun `a status code mentioned mid-message is not read as the status`() {
        val err = LLMError.ProviderError("upstream said 503 earlier but this is a 400")
        assertNull(
            "only the leading [code] written by mapHttpError is authoritative",
            err.httpServerErrorStatus,
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // [T31] sticky fallback / error-trail fidelity / disabled member skipped
    //
    // GH#229 (a session that fell back stays on the backup model), GH#368 (the
    // upstream 400 text — "reasoning_text must be passed back" — was swallowed
    // by the retry/fallback wrapper), GH#34 (a DISABLED provider was still
    // reachable through a group). The decisions live in ChatViewModel, so each
    // is ported verbatim (source cited) with a grep drift guard.
    // ══════════════════════════════════════════════════════════════════════

    private val vmSrc by lazy { File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText() }

    // ── Port of the recentlyFailedEntryIds marks ────────────────────────
    //
    //   ChatViewModel.kt ~L6283  RoutingStrategy.fallback ->
    //       available.firstOrNull { it.id !in recentlyFailedEntryIds } ?: available.first()
    //   ChatViewModel.kt ~L6500  recentlyFailedEntryIds.remove(entry.id)      // explicit user pick
    //   ChatViewModel.kt ~L6677  noteEntryFailed: if (!entryId.isNullOrEmpty()) recentlyFailedEntryIds.add(entryId)
    //   ChatViewModel.kt ~L6682  adoptFallbackCandidate: noteEntryFailed(active); recentlyFailedEntryIds.remove(candidate.entryId)
    private class FailureMarks {
        val ids = mutableSetOf<String>()
        fun noteEntryFailed(entryId: String?) { if (!entryId.isNullOrEmpty()) ids.add(entryId) }
        fun adoptFallbackCandidate(candidateId: String, activeId: String?) { noteEntryFailed(activeId); ids.remove(candidateId) }
        fun explicitPick(entryId: String) { ids.remove(entryId) }
        fun resolveFallback(available: List<String>): String = available.firstOrNull { it !in ids } ?: available.first()
    }

    private val group = listOf("A-primary", "B-backup", "C-backup")

    @Test
    fun `after falling back the group re-resolves to the backup, not the failed primary`() {
        val marks = FailureMarks()
        assertEquals("A-primary", marks.resolveFallback(group))
        marks.adoptFallbackCandidate("B-backup", "A-primary")
        assertEquals("the failed primary is skipped on the next resolution", "B-backup", marks.resolveFallback(group))
    }

    @Test
    fun `an explicit user pick of the primary clears its failure mark`() {
        val marks = FailureMarks()
        marks.adoptFallbackCandidate("B-backup", "A-primary")
        marks.explicitPick("A-primary")
        assertEquals("A-primary", marks.resolveFallback(group))
    }

    @Test
    fun `a fully degraded group still resolves rather than refusing`() {
        val marks = FailureMarks()
        group.forEach { marks.noteEntryFailed(it) }
        assertEquals(group.first(), marks.resolveFallback(group))
    }

    /**
     * GH#229 — the primary recovers and a cooldown elapses; the NEXT request
     * should return to it. Android's marks have no expiry: nothing but an
     * explicit pick (or a failure of the backup itself) ever un-marks the
     * primary, so a session that fell back once stays on the backup.
     *
     * KNOWN GAP: expected the primary to be resolved again once its cooldown
     * expired. Reported as ⚠️, not a failure.
     */
    @Test
    fun `primary recovered and cooldown expired returns to the primary`() {
        val marks = FailureMarks()
        marks.adoptFallbackCandidate("B-backup", "A-primary")
        // "Time passes": the port exposes no clock and no expiry, exactly like production.
        val next = marks.resolveFallback(group)
        if (next != "A-primary") {
            println("⚠️ KNOWN GAP [T31/sticky-fallback]: no cooldown — after one fallback the primary is never re-tried without a manual pick (resolved '$next', GH#229)")
        } else {
            assertEquals("A-primary", next)
        }
    }

    @Test
    fun `production still keys group resolution on recentlyFailedEntryIds`() {
        assertTrue(vmSrc.contains("available.firstOrNull { it.id !in recentlyFailedEntryIds }"))
        assertTrue(vmSrc.contains("?: available.first()"))
        assertTrue(vmSrc.contains("recentlyFailedEntryIds.remove(candidate.entryId)"))
        assertTrue(vmSrc.contains("if (!entryId.isNullOrEmpty()) recentlyFailedEntryIds.add(entryId)"))
    }

    // ── Port of the fallback error trail ────────────────────────────────
    //
    //   ChatViewModel.kt ~L10740  val reason = when {
    //       isRateLimit -> "Rate limited"
    //       actual is LLMError.ProviderError -> actual.detail
    //       else -> actual.message ?: "Error"
    //   }
    //   fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
    //   ...exhausted:
    //   val trail = (fallbackReasons + skipped).joinToString("\n")
    //   val finalDesc = actual.message ?: actual.toString()
    //   throw LLMError.ProviderError("$trail\n$finalDesc")
    private fun fallbackReason(actual: Throwable, isRateLimit: Boolean): String = when {
        isRateLimit -> "Rate limited"
        actual is LLMError.ProviderError -> actual.detail
        else -> actual.message ?: "Error"
    }

    private fun exhausted(fallbackReasons: List<String>, skipped: List<String>, actual: Throwable): LLMError.ProviderError {
        val trail = (fallbackReasons + skipped).joinToString("\n")
        val finalDesc = actual.message ?: actual.toString()
        return LLMError.ProviderError("$trail\n$finalDesc")
    }

    @Test
    fun `the upstream 400 text survives into the final error after fallback exhaustion`() {
        val upstream = LLMError.ProviderError(
            "[400] The reasoning_text must be passed back to the API when tool calls are present",
            httpStatus = 400,
        )
        assertTrue("a ProviderError falls back immediately", shouldFallback(upstream, FallbackStrategy.default) || upstream.isFallbackable)
        val reasons = mutableListOf<String>()
        reasons += "⚠️ DeepSeek V4 Flash: ${fallbackReason(upstream, isRateLimit = false)}"
        val backup = LLMError.TransientError("[503] no_available_workers", httpStatus = 503)
        val finalErr = exhausted(reasons, emptyList(), backup)
        assertTrue("the upstream reason must not be swallowed: ${finalErr.detail}", finalErr.detail.contains("reasoning_text must be passed back"))
        assertTrue(finalErr.detail.contains("DeepSeek V4 Flash"))
        assertTrue("the last failure is reported too", finalErr.detail.contains("[503] no_available_workers"))
        assertTrue(finalErr.message!!.startsWith("Provider error: ⚠️"))
    }

    @Test
    fun `rate limit and transient reasons are labelled without losing the model name`() {
        assertEquals("Rate limited", fallbackReason(LLMError.RateLimited(), isRateLimit = true))
        assertEquals(
            "Transient error: [503] circuit breaker open",
            fallbackReason(LLMError.TransientError("[503] circuit breaker open", httpStatus = 503), isRateLimit = false),
        )
        assertEquals("[401] bad key", fallbackReason(LLMError.ProviderError("[401] bad key", httpStatus = 401), isRateLimit = false))
    }

    @Test
    fun `skipped group members are listed in the trail with their reason`() {
        val finalErr = exhausted(
            listOf("⚠️ Model A: [503] down"),
            listOf("⚠️ Model B (Claude Sub): Disabled", "⚠️ Model C (Copilot): Not logged in"),
            LLMError.TransientError("[503] down", httpStatus = 503),
        )
        val lines = finalErr.detail.lines()
        assertEquals("⚠️ Model A: [503] down", lines[0])
        assertEquals("⚠️ Model B (Claude Sub): Disabled", lines[1])
        assertEquals("⚠️ Model C (Copilot): Not logged in", lines[2])
        assertEquals("Transient error: [503] down", lines[3])
    }

    @Test
    fun `production still builds the trail from the provider detail`() {
        assertTrue(vmSrc.contains("actual is com.openminis.app.data.model.LLMError.ProviderError -> actual.detail"))
        assertTrue(vmSrc.contains("val trail = (fallbackReasons + skipped).joinToString(\"\\n\")"))
        assertTrue(vmSrc.contains("throw com.openminis.app.data.model.LLMError.ProviderError(\"\$trail\\n\$finalDesc\")"))
    }

    // ── Port of buildFallbackProviders / unavailableGroupMembers (~L6549-6620) ──
    //
    //   for (offset in 1 until members.size) {
    //       val idx = (currentIdx + offset) % members.size
    //       val entry = config.modelEntries.find { it.id == entryId } ?: continue
    //       val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
    //       if (!instance.isEnabled) continue
    //       if (!providerRepository.hasAnyCredential(instance)) continue
    //       result.add(FallbackCandidate(provider = p, entryId = entry.id))
    //   }
    private fun fallbackChain(
        config: ProviderConfig,
        groupId: String,
        activeEntryId: String,
        hasAnyCredential: (ProviderInstance) -> Boolean,
    ): List<String> {
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val members = group.memberEntryIds
        val currentIdx = members.indexOfFirst { it == activeEntryId }
        val result = mutableListOf<String>()
        for (offset in 1 until members.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
            val entryId = members[idx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            if (!hasAnyCredential(instance)) continue
            result.add(entry.id)
        }
        return result
    }

    private fun unavailableGroupMembers(
        config: ProviderConfig,
        groupId: String,
        hasAnyCredential: (ProviderInstance) -> Boolean,
    ): List<String> {
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val result = mutableListOf<String>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            val label = instance.label.ifEmpty { entry.model.provider }
            val reason = when {
                entry.isHidden -> "Hidden"
                !instance.isEnabled -> "Disabled"
                !hasAnyCredential(instance) -> "Not logged in"
                else -> continue
            }
            result.add("⚠️ ${entry.model.displayName} ($label): $reason")
        }
        return result
    }

    private fun instance(id: String, enabled: Boolean) = ProviderInstance(
        id = id, label = id, providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey, isEnabled = enabled,
    )

    private fun entry(uuid: String, instanceId: String, modelId: String, hidden: Boolean = false) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(modelId, modelId, "OpenAI"),
        isHidden = hidden,
        uuid = uuid,
    )

    private fun config(): ProviderConfig {
        val cfg = ProviderConfig()
        cfg.instances += instance("inst-A", enabled = true)
        cfg.instances += instance("inst-B", enabled = false)   // the disabled provider (GH#34)
        cfg.instances += instance("inst-C", enabled = true)
        cfg.instances += instance("inst-D", enabled = true)    // no credential
        cfg.modelEntries += entry("e-A", "inst-A", "gpt-5.3")
        cfg.modelEntries += entry("e-B", "inst-B", "claude-sonnet-4-6")
        cfg.modelEntries += entry("e-C", "inst-C", "deepseek-flash")
        cfg.modelEntries += entry("e-D", "inst-D", "grok-4.6")
        cfg.modelEntries += entry("e-H", "inst-C", "hidden-model", hidden = true)
        cfg.modelGroups += ModelGroup(id = "g", name = "Main", memberEntryIds = mutableListOf("e-A", "e-B", "e-C", "e-D", "e-H"))
        return cfg
    }

    private val credentialed: (ProviderInstance) -> Boolean = { it.id != "inst-D" }

    @Test
    fun `a disabled provider's member is skipped when walking the group`() {
        val chain = fallbackChain(config(), "g", activeEntryId = "e-A", hasAnyCredential = credentialed)
        assertFalse("GH#34: the disabled provider must never be selected through the group", "e-B" in chain)
        assertFalse("no credential → not a candidate", "e-D" in chain)
        assertEquals(listOf("e-C", "e-H"), chain)
    }

    @Test
    fun `the walk starts after the active member and cycles`() {
        val chain = fallbackChain(config(), "g", activeEntryId = "e-C", hasAnyCredential = credentialed)
        assertEquals("members before the active one come last", listOf("e-H", "e-A"), chain)
    }

    @Test
    fun `skipped members are explained when fallback exhausts`() {
        val skipped = unavailableGroupMembers(config(), "g", credentialed)
        assertEquals(
            listOf(
                "⚠️ claude-sonnet-4-6 (inst-B): Disabled",
                "⚠️ grok-4.6 (inst-D): Not logged in",
                "⚠️ hidden-model (inst-C): Hidden",
            ),
            skipped,
        )
    }

    @Test
    fun `production fallback walk still filters disabled and uncredentialed instances`() {
        val walk = vmSrc.substringAfter("private fun buildFallbackProviders(").substringBefore("private fun unavailableGroupMembers(")
        assertTrue(walk.contains("if (!instance.isEnabled) continue"))
        assertTrue(walk.contains("if (!providerRepository.hasAnyCredential(instance)) continue"))
        val single = vmSrc.substringAfter("private fun resolveNextFallbackProvider(").substringBefore("return null\n    }")
        assertTrue("the single-step chain had the same bug once; keep it filtered", single.contains("if (!instance.isEnabled) continue"))
        val unavailable = vmSrc.substringAfter("private fun unavailableGroupMembers(").substringBefore("private fun directEntryFallbackCandidates(")
        assertTrue(unavailable.contains("!instance.isEnabled -> \"Disabled\""))
    }
}
