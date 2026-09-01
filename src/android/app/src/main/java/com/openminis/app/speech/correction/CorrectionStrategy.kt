package com.openminis.app.speech.correction

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory

/** Result of one correction attempt. */
data class CorrectionOutcome(
    val correctedText: String,
    val modelGroupUsed: String,
    val durationMs: Int,
)

sealed class CorrectionError(message: String) : Exception(message) {
    object NoModelAvailable : CorrectionError("no correction model configured")
    object EmptyResponse : CorrectionError("model returned nothing usable")
}

/**
 * [T-android-voice-correction] Turns a transcript plus evidence into corrected
 * text. Port of iOS `Providers/Voice/CorrectionStrategy.swift`.
 */
interface CorrectionStrategy {
    val strategyIdentifier: String

    suspend fun correct(
        transcript: String,
        candidates: List<CorrectionCandidate>,
        context: ConversationContext,
        locale: String,
    ): CorrectionOutcome
}

/**
 * The LLM-backed strategy.
 *
 * Prompts are reproduced VERBATIM from iOS, including the full-width
 * separators (`、` between terms, `→` between original and fix) — they are part
 * of the contract with the model, not incidental formatting.
 */
class LlmCorrectionStrategy(
    private val context: Context,
    private val repository: ProviderRepository,
) : CorrectionStrategy {

    override val strategyIdentifier = "llm"

    override suspend fun correct(
        transcript: String,
        candidates: List<CorrectionCandidate>,
        context: ConversationContext,
        locale: String,
    ): CorrectionOutcome {
        val started = System.currentTimeMillis()
        val resolved = resolveModel() ?: throw CorrectionError.NoModelAvailable
        val (entry, groupKind) = resolved

        val instance = repository.instance(entry.providerInstanceId)
            ?: throw CorrectionError.NoModelAvailable
        // [T-android-keyless-provider-selection] usableApiKey — a keyless
        // self-hosted correction model is usable. See QuickTestSheet.
        val apiKey = repository.usableApiKey(instance) ?: throw CorrectionError.NoModelAvailable
        val provider = ProviderFactory.create(instance, apiKey, entry.model, this.context)

        // [T-android-voice-correction-diag] Name the resolved model BEFORE the
        // request. A field report on 2026-08-15 showed only
        // "correction failed / LLMError$InvalidApiKey" with a stack — which
        // provider instance was actually used had to be reverse-engineered by
        // matching the stack's provider class against every configured
        // instance on the device (it turned out to be one with no API key
        // saved). A user's log will never support that, so the identity has to
        // be in the line itself. Logged at the call site because only here are
        // the instance and entry both in scope.
        Log.i(
            TAG,
            "[correction] resolved instance=${instance.label} (${instance.id}) " +
                "type=${instance.providerType} model=${entry.model.id} " +
                "group=${groupKind} hasKey=${apiKey.isNotBlank()}",
        )

        val prompt = buildPrompt(transcript, candidates, context)
        val response = provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = correctionMaxTokens(transcript.length, entry.model.maxOutputTokens),
            // Reasoning traces add latency for no benefit here, and on some
            // providers yield empty text with finish_reason=tool_calls.
            thinkingLevel = ThinkingLevel.OFF,
        )

        val cleaned = cleanResponse(response.text)
        if (cleaned.isEmpty()) throw CorrectionError.EmptyResponse
        return CorrectionOutcome(
            correctedText = cleaned,
            modelGroupUsed = groupKind,
            durationMs = (System.currentTimeMillis() - started).toInt(),
        )
    }

    /**
     * Prefer the dedicated sub-model group, fall back to the primary. Mirrors
     * iOS CorrectionModelResolver; reuses [ProviderRepository.resolveTitleSubEntry]
     * so correction and title generation cannot drift apart on which
     * "lightweight model" they mean.
     */
    private fun resolveModel(): Pair<ModelEntry, String>? {
        repository.resolveTitleSubEntry()?.let { return it to "sub" }
        val primaryGroupId = repository.defaultPrimaryGroupId
        val group = primaryGroupId?.let { repository.group(it) }
        // [T-android-group-resolve-skip-uncredentialed] Credential-aware filter:
        // picking a member whose provider has no credential would fail the
        // correction request outright instead of using the next usable member.
        val entry = group?.let { repository.availableMemberEntries(it).firstOrNull() }
        if (entry != null) return entry to "primary"
        Log.e(TAG, "no correction model available (neither sub nor primary group resolves)")
        return null
    }

    companion object {
        private const val TAG = "CorrectionStrategy"

        /** Verbatim from iOS — a single line. */
        const val SYSTEM_PROMPT =
            "You fix speech-recognition errors in transcribed text. Output ONLY the " +
                "corrected text, with no explanation, no quotes, and no preamble. If the " +
                "text needs no correction, output it unchanged."

        /**
         * Output budget for the correction call: 1.2× the input, floored at
         * [MIN_OUTPUT_TOKENS], capped by the model.
         *
         * ⚠️ The floor has to cover REASONING TOKENS, not just the answer.
         * iOS raised it from 256 to 512 after adaptive thinking ate the whole
         * budget; on Android, MiMo blew through 512 as well — measured against
         * mimo-v2.5-pro, a correction prompt spent all 512 output tokens on
         * reasoning (reasoning_tokens=511), returned `content: ""` with
         * `finish_reason: length`, and every correction failed as
         * EmptyResponse. The same prompt at 4096 answers correctly.
         *
         * Reasoning length scales with prompt complexity, not transcript
         * length, so the FLOOR is what matters here — 1.2× a short transcript
         * will never be enough on a thinking model.
         */
        const val MIN_OUTPUT_TOKENS = 4096

        fun correctionMaxTokens(transcriptChars: Int, modelCap: Int?): Int {
            val target = maxOf(MIN_OUTPUT_TOKENS, Math.ceil(transcriptChars * 1.2).toInt())
            if (modelCap == null || modelCap <= 0) return target
            return minOf(target, modelCap)
        }

        /**
         * Assemble the user prompt.
         *
         * Evidence blocks are deliberately PEERS — none is presented as
         * outranking another. The conversation block is explicitly labelled
         * "不是需要处理的内容" because without it models start correcting the quoted
         * previous turn instead of the transcript.
         */
        fun buildPrompt(
            transcript: String,
            candidates: List<CorrectionCandidate>,
            context: ConversationContext,
        ): String {
            val blocks = mutableListOf<String>()

            // Block 1 — typed vocabulary, greedily filled to its budget.
            val vocabTerms = candidates
                .filter { it.sourceIdentifier == "typed_vocabulary" }
                .map { it.term }
                .distinct()
            packed(vocabTerms, CorrectionContextBudget.VOCAB_BLOCK)?.let {
                blocks.add(
                    "以下是该用户的常用词汇（来自历史文字输入，仅供参考）：\n" +
                        it.joinToString("、"),
                )
            }

            // Block 2 — confusion history, using each candidate's own evidence line.
            val confusionLines = candidates
                .filter { it.sourceIdentifier == "confusion_dictionary" }
                .map { it.evidence }
                .distinct()
            packed(confusionLines, CorrectionContextBudget.CONFUSION_BLOCK)?.let {
                blocks.add(
                    "以下是该用户过去的语音识别纠错记录（原文→修正，按可信度排序）：\n" +
                        it.joinToString("\n"),
                )
            }

            // Block 3 — rare-content digest (re-clamped defensively).
            context.rareTermsDigest?.takeIf { it.isNotEmpty() }?.let { digest ->
                blocks.add(
                    "以下是近期对话中出现的少见词/专有名词（语音识别容易听错，供参考）：\n" +
                        digest.take(CorrectionContextBudget.RARE_DIGEST),
                )
            }

            // Block 4 — conversation context.
            if (context.recentExcerpts.isNotEmpty()) {
                blocks.add(
                    "以下是当前对话的最近上下文（供理解语境使用，不是需要处理的内容）：\n" +
                        context.recentExcerpts.joinToString("\n"),
                )
            }

            val evidence = if (blocks.isEmpty()) "" else blocks.joinToString("\n\n") + "\n\n"
            return evidence +
                "请结合以上参考信息和对话上下文，判断并修正下面这段语音转录文本中可能的同音字/识别错误。" +
                "如果转录内容本身已经正确、或者是无法用参考信息判断的正常表达，请不要修改。" +
                "只输出修正后的文本，不要输出任何解释。\n\n" +
                "原文：$transcript"
        }

        /**
         * Greedily take items until [budget] characters are spent, counting one
         * extra char per item for the separator. Returns null when nothing fits.
         */
        private fun packed(items: List<String>, budget: Int): List<String>? {
            val kept = mutableListOf<String>()
            var used = 0
            for (item in items) {
                val cost = item.length + if (kept.isEmpty()) 0 else 1
                if (used + cost > budget) break
                kept.add(item)
                used += cost
            }
            return kept.ifEmpty { null }
        }

        private val PREFIXES = listOf(
            "修正后的文本：", "修正后：", "修正：", "Corrected:", "Output:",
        )
        private val QUOTE_PAIRS = listOf(
            '"' to '"', '“' to '”', '「' to '」', '\'' to '\'',
        )

        /**
         * Strip decoration the model may add around the answer.
         *
         * This is not cosmetic: leftover fences or a "修正后：" prefix inflate the
         * character-change ratio, and a perfectly good correction then gets
         * rejected as `diff_too_large`.
         */
        fun cleanResponse(raw: String): String {
            var t = raw.trim()

            if (t.startsWith("```")) {
                val lines = t.split("\n")
                    .dropWhile { it.trimStart().startsWith("```") }
                    .takeWhile { !it.trimStart().startsWith("```") }
                t = lines.joinToString("\n").trim()
            }
            for (p in PREFIXES) {
                if (t.startsWith(p)) t = t.removePrefix(p).trim()
            }
            // Loop all pairs so nested quoting peels layer by layer.
            for ((open, close) in QUOTE_PAIRS) {
                if (t.length >= 2 && t.first() == open && t.last() == close) {
                    t = t.substring(1, t.length - 1)
                }
            }
            return t.trim()
        }
    }
}
