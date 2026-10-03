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
        val provider = ProviderFactory.create(instance, apiKey, entry.model, this.context, overrides = entry.overrides)

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

        val profile = CorrectionLanguageProfile.resolve(locale, transcript)
        val prompt = buildPrompt(transcript, candidates, context, profile)
        val response = provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
            systemPrompt = systemPrompt(profile),
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

        /**
         * [T-android-voice-mixed-term-restore] The rules half of the prompt. Port of
         * iOS 8622ea275 (T-voice-mixed-term-restore); the English text is iOS's,
         * verbatim.
         *
         * It used to be the output contract alone, and the user prompt asked only
         * for "同音字/识别错误". A Chinese recognizer's commonest miss on mixed
         * speech is not a homophone: an English term spoken inside a Chinese
         * sentence comes back as look-alike hanzi. On iOS, "下一个 Issue" became
         * "下一个遗穴", and with "Issue" on screen ten times the model still
         * answered "议题" — it searched Chinese words only and picked the one
         * that MEANS issue.
         *
         * Three layers: the output contract; the setting (the transcript is a
         * message to the agent, usually a reply that picks or names something the
         * agent just offered); and how recognition fails, per language
         * ([CorrectionLanguageProfile]). The method is shared: sound it out,
         * restore the reference's spelling, never translate.
         */
        fun systemPrompt(profile: CorrectionLanguageProfile): String =
            "You fix speech-recognition errors in transcribed text. Output ONLY the corrected text, " +
                "with no explanation, no quotes, and no preamble. If the text needs no correction, output it unchanged.\n\n" +
                "The situation: the user is talking to an AI agent in a chat app by voice, and the " +
                "transcript is the message they are about to send it. Read the transcript as one whole " +
                "message to that agent and work out what the user most likely means by it, given the " +
                "conversation. Most of the time they are answering or acting on what the agent just said: " +
                "picking one of the options it offered, naming something it mentioned (a noun, a file, an " +
                "item in a list, an issue number, a step), or referring to content it described. Those " +
                "names and options are the likeliest words in the transcript, so a misheard span that sounds " +
                "like one of them almost certainly is that one.\n\n" +
                "How recognition goes wrong for this user:\n" +
                profile.failureModes + "\n\n" +
                "How to fix it:\n" +
                "- A span that reads oddly in its sentence is a suspect. Sound it out and compare it with " +
                "the terms in the reference material: what is on screen, the recent conversation, the rare " +
                "terms, the user's vocabulary and their past corrections. If a term there, in any language, " +
                "sounds like the span and fits the sentence, replace the span with that term, written " +
                "exactly as the reference material writes it (same spelling, letter case and script).\n" +
                "- Restore the term itself; never translate it into another language or swap it for a " +
                "word that merely means the same.\n" +
                "- Use only terms supported by the reference material or common knowledge. Leave natural, " +
                "plausible wording alone, and do not rephrase or polish."

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
            profile: CorrectionLanguageProfile = CorrectionLanguageProfile.resolve("zh", transcript),
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

            // [T-android-voice-viewport-context] Blocks 5 + 6 — what the user
            // is looking at. Kept apart from block 4 (the newest turns): after
            // scrolling back, the two differ, and the screen is what the
            // dictation is about.
            val screen = context.screen
            if (screen != null && screen.viewportLines.isNotEmpty()) {
                blocks.add(
                    "以下是用户说话时屏幕上可见的对话内容（以屏幕中心为基准由近到远选取，按时间顺序排列；" +
                        "供理解语境使用，不是需要处理的内容）：\n" +
                        screen.viewportLines.joinToString("\n"),
                )
            }
            if (screen?.latestReply != null) {
                val where = if (screen.latestReplyOnScreen) "，此刻也显示在屏幕上" else "，此刻不在屏幕可见范围内"
                blocks.add(
                    "以下是本会话最新一条 AI 回复$where（供理解语境使用，不是需要处理的内容）：\n" +
                        screen.latestReply,
                )
            }

            val evidence = if (blocks.isEmpty()) "" else blocks.joinToString("\n\n") + "\n\n"
            // [T-android-voice-mixed-term-restore] Frame the transcript as a message
            // to the AI, and name this language's error kinds: "同音字" alone steered
            // the model to Chinese-only substitutes (iOS 8622ea275).
            val errorKinds = profile.errorKinds
            val lead = if (screen != null && screen.viewportLines.isNotEmpty()) {
                "请结合以上参考信息，尤其是用户说话时屏幕上可见的对话内容，" +
                    "推断用户这句要发给 AI 的话的整体意图（常常是在回应、选择或提及 AI 刚提到的名词、内容或选项），" +
                    "据此判断并修正下面这段语音转录文本中可能的$errorKinds。"
            } else {
                "请结合以上参考信息和对话上下文，推断用户这句要发给 AI 的话的整体意图，" +
                    "判断并修正下面这段语音转录文本中可能的$errorKinds。"
            }
            Log.i(TAG, "[CorrectionPrompt] profile=${profile.name.lowercase()} evidenceBlocks=${blocks.size} transcript=${transcript.length}")
            return evidence +
                lead +
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

/**
 * [T-android-voice-mixed-term-restore] How speech recognition fails differs by
 * language, so the prompt's failure-mode rules are kept per language instead of
 * one Chinese-centred list applied to everyone. Port of iOS
 * `CorrectionLanguageProfile` (8622ea275); the prompt text is iOS's, verbatim.
 *
 * Chosen from what the transcript is written in, with the recognizer locale as
 * the tie-breaker: a user on a zh recognizer who dictates an English sentence
 * gets the Western rules, and a kanji-only Japanese transcript still reads as
 * Japanese because the locale says so.
 */
enum class CorrectionLanguageProfile {
    CHINESE, JAPANESE, KOREAN, WESTERN, GENERIC;

    /** The "How recognition goes wrong" bullets of the system prompt. */
    val failureModes: String
        get() = when (this) {
            CHINESE ->
                "- They speak Chinese mixed with English words, product names and code identifiers. " +
                    "A Chinese recognizer often writes such an English word as Chinese characters that only " +
                    "sound like it (for example \"遗穴\" for \"Issue\", \"扣米特\" for \"commit\", \"杰森\" for \"JSON\").\n" +
                    "- Chinese names, product names and jargon come back as common words with the same or a " +
                    "similar sound (同音字 / 近音词), often words that are valid on their own.\n" +
                    "- If the context says \"Issue\", write \"Issue\", not a Chinese word with the same meaning " +
                    "such as \"议题\" or \"问题\"."
            JAPANESE ->
                "- They speak Japanese mixed with English words, product names and code identifiers. " +
                    "The recognizer often writes such a term in katakana (\"イシュー\" for \"Issue\", \"コミット\" for " +
                    "\"commit\") or splits it into unrelated words.\n" +
                    "- It picks the wrong kanji among homophones (同音異義語, e.g. 機能/昨日, 公開/後悔) and " +
                    "chooses between kana and kanji inconsistently.\n" +
                    "- Write a term the way the reference material writes it: Latin letters if it is written " +
                    "in Latin letters there, katakana if it is written in katakana there."
            KOREAN ->
                "- They speak Korean mixed with English words, product names and code identifiers. " +
                    "The recognizer often writes such a term in Hangul (\"이슈\" for \"Issue\", \"커밋\" for " +
                    "\"commit\") or splits it into unrelated words.\n" +
                    "- It confuses words that sound alike, and misplaces word spacing (띄어쓰기) around " +
                    "terms. Fix spacing only around a term you restore.\n" +
                    "- Write a term the way the reference material writes it: Latin letters if it is written " +
                    "in Latin letters there, Hangul if it is written in Hangul there."
            WESTERN ->
                "- Product names, code identifiers and file names are split into or merged with ordinary " +
                    "words (\"get hub\" for \"GitHub\", \"cube cuddle\" for \"kubectl\", \"read me dot MD\" for " +
                    "\"README.md\"), and lose their letter case and punctuation.\n" +
                    "- Names from other languages (often Chinese) are spelled out phonetically.\n" +
                    "- Homophones and near-homophones are swapped (their/there, accept/except, " +
                    "four/for). Leave spelling variants, grammar and style alone."
            GENERIC ->
                "- Names, product names, code identifiers and words from other languages are misheard as " +
                    "ordinary words, or spelled out phonetically in the user's script.\n" +
                    "- Words that sound alike are swapped."
        }

    /** What the user prompt's lead asks the model to fix (the user prompt is Chinese for every language). */
    val errorKinds: String
        get() = when (this) {
            CHINESE -> "同音字、近音词，以及被识别成普通词或音译汉字的专有名词（中文或英文，英文请按参考信息中的原拼写还原，不要翻译成中文）"
            JAPANESE -> "同音异义的汉字，以及被写成片假名、被拆开或被识别成普通词的术语和专有名词（按参考信息中的写法还原，不要翻译）"
            KOREAN -> "近音词，以及被写成韩文字母、被拆开或被识别成普通词的术语和专有名词及其周围的空格（按参考信息中的写法还原，不要翻译）"
            WESTERN -> "同音/近音词，以及被拆开、合并或按读音拼写的专有名词、产品名和代码标识符（按参考信息中的原写法还原，包括大小写和符号）"
            GENERIC -> "同音/近音词，以及被识别成普通词或按读音拼写的专有名词（按参考信息中的写法还原，不要翻译）"
        }

    companion object {
        /** Hangul > kana > han > Latin/Cyrillic/Greek, then the locale. Same order as iOS. */
        fun resolve(locale: String, transcript: String): CorrectionLanguageProfile {
            val key = PhoneticNormalizerRegistry.normalizedLocaleKey(locale)
            var han = false; var kana = false; var hangul = false; var western = false
            var i = 0
            while (i < transcript.length) {
                val cp = transcript.codePointAt(i)
                when (cp) {
                    in 0x3040..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9D -> kana = true
                    in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> hangul = true
                    in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> han = true
                    in 0x41..0x5A, in 0x61..0x7A, in 0xC0..0x24F, in 0x370..0x3FF, in 0x400..0x4FF -> western = true
                }
                i += Character.charCount(cp)
            }
            if (hangul) return KOREAN
            if (kana) return JAPANESE
            if (han) return if (key == "ja") JAPANESE else CHINESE
            if (western) return WESTERN
            return when (key) {
                "zh", "yue", "wuu" -> CHINESE
                "ja" -> JAPANESE
                "ko" -> KOREAN
                else -> GENERIC
            }
        }
    }
}
