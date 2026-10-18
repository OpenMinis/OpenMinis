package com.openminis.app.tools

/**
 * Same-page AI group chat. Each model speaks as itself. The host only frames
 * the question and writes the closing report; it does not impersonate the others.
 */
object GroupChat {
    const val SPEAKER_PART = "speaker"
    const val VENDOR_UNKNOWN = "unknown"

    /**
     * Model family, not the wire protocol. Delegates to
     * [ModelBrandResolver.resolveExact] — ONE keyword table, one truth: this
     * used to keep its own 22-brand alias table that silently drifted from the
     * picker's 366-keyword table (PR70 lesson: two implementations = divergent
     * behaviour). Unrecognized references return [VENDOR_UNKNOWN] so the avatar
     * degrades to its initial letter instead of wearing a wrong vendor's mark.
     */
    fun vendorKey(modelId: String?, displayName: String?): String =
        com.openminis.app.ui.chat.ModelBrandResolver.resolveExact(modelId, displayName)
            ?: VENDOR_UNKNOWN

    /**
     * Generic text normalizer for speaker addressing and close-request
     * matching (strip punctuation/separators, lowercase). Brand resolution no
     * longer uses it — that lives in ModelBrandResolver.
     */
    internal fun compactVendorText(raw: String?): String =
        raw.orEmpty().lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "")

    data class Line(val speaker: String, val text: String)

    fun transcript(lines: List<Line>): String =
        lines.joinToString("\n\n") { "${it.speaker}：${it.text.trim()}" }
            .ifBlank { "（还没有发言）" }

    fun isPass(text: String): Boolean {
        val normalized = text.trim().trimEnd('.', '。', '!', '！')
        return normalized.equals("PASS", ignoreCase = true) ||
            normalized == "无补充" ||
            normalized == "（无补充）"
    }

    /** A visible stance, not a one-word agreement or a pass. */
    fun isSubstantive(text: String): Boolean {
        if (isPass(text)) return false
        return text.trim().length >= 8
    }

    /**
     * Reasoning models often leave the stance in thinking and a stub such as
     * 「对」 in the text channel. Keep a real text answer; otherwise use the
     * last substantial thinking paragraph. A stub with no thinking is not a speech.
     */
    fun recoverUtterance(text: String, thinking: String): String {
        val spoken = text.trim()
        if (isSubstantive(spoken)) return spoken
        val thought = thinking.trim()
        if (thought.length < 12) return ""
        val paragraphs = thought.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.length >= 12 }
        val chosen = paragraphs.lastOrNull() ?: thought
        return chosen.takeLast(400).trim()
    }

    /**
     * One loaded chat row, reduced so the round boundary can be tested without
     * Android message types. [sourceIds] are the persisted row ids. The UI id
     * changes across a reload and must not be the only marker.
     */
    data class ContextMessage(
        val id: String,
        val role: String,
        val content: String,
        val speakerName: String? = null,
        val sourceIds: List<String> = emptyList(),
        val queued: Boolean = false,
        val awaiting: Boolean = false,
    )

    fun isHostSpeaker(speaker: String?, hostSuffix: String): Boolean {
        val name = speaker?.trim().orEmpty()
        val suffix = hostSuffix.trim()
        if (name.isEmpty() || suffix.isEmpty()) return false
        return name == suffix || name.endsWith(" · $suffix")
    }

    fun matchesMarker(message: ContextMessage, marker: String): Boolean {
        if (marker.isBlank()) return false
        return message.id == marker || message.sourceIds.any { it == marker }
    }

    /**
     * First index that belongs to the current group round.
     *
     * A stored close id often misses after reload. Starting at 0 in that case
     * feeds the previous group chat into the new round, so members pass and
     * the host still writes a report from the old transcript. Use the latest
     * resolved marker or host report instead. If this window contains neither,
     * it is already the current tail.
     */
    fun roundStartIndex(
        messages: List<ContextMessage>,
        markers: List<String?>,
        hostSuffix: String = "",
    ): Int {
        val found = markers.mapNotNull { marker ->
            val id = marker?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            messages.indexOfLast { matchesMarker(it, id) }.takeIf { it >= 0 }
        }
        val host = if (hostSuffix.isBlank()) {
            -1
        } else {
            messages.indexOfLast { isHostSpeaker(it.speakerName, hostSuffix) }
        }
        val boundaries = found + listOfNotNull(host.takeIf { it >= 0 })
        if (boundaries.isEmpty()) return 0
        return boundaries.max() + 1
    }

    fun currentSpeeches(
        messages: List<ContextMessage>,
        markers: List<String?>,
        hostSuffix: String = "",
        hiddenTexts: Set<String> = emptySet(),
    ): List<Line> {
        val hidden = hiddenTexts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return messages.drop(roundStartIndex(messages, markers, hostSuffix)).mapNotNull { message ->
            if (message.queued || message.awaiting) return@mapNotNull null
            val speaker = message.speakerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = message.content.trim()
            if (text.isBlank() || isPass(text) || text in hidden || isHostSpeaker(speaker, hostSuffix)) {
                null
            } else {
                Line(speaker, text)
            }
        }
    }

    /**
     * Main-chat turns only. Group bubbles and the previous host report carry a
     * speaker name; taking the last few rows made a new round discuss that
     * previous round instead of the conversation the user is looking at.
     */
    fun currentConversation(
        messages: List<ContextMessage>,
        maxMessages: Int = 6,
        maxCharsPerMessage: Int = 2000,
        maxChars: Int = 8000,
    ): String {
        val selected = messages.filter { message ->
            !message.queued &&
                message.speakerName.isNullOrBlank() &&
                (message.role == "user" || message.role == "assistant") &&
                message.content.isNotBlank()
        }.takeLast(maxMessages.coerceAtLeast(1))
        val clipped = selected.map { message ->
            message.copy(content = message.content.trim().take(maxCharsPerMessage.coerceAtLeast(1)))
        }
        val kept = mutableListOf<ContextMessage>()
        var used = 0
        for (message in clipped.asReversed()) {
            val extra = message.content.length + if (kept.isEmpty()) 0 else 1
            if (kept.isNotEmpty() && used + extra > maxChars) break
            kept += message
            used += extra
        }
        return kept.asReversed().joinToString("\n") { message ->
            "${message.role}: ${message.content}"
        }
    }

    /** Group utterances stay separate so each model keeps its own bubble. */
    fun shouldMergeAssistantTurns(prevSpeaker: String?, nextSpeaker: String?): Boolean =
        prevSpeaker.isNullOrBlank() && nextSpeaker.isNullOrBlank()

    /** Ordered roles so later speakers do not repeat the same essay. */
    fun stance(index: Int): String = STANCES[index.mod(STANCES.size).let { if (it < 0) it + STANCES.size else it }]

    fun isCloseRequest(text: String): Boolean {
        val compact = compactVendorText(text)
        if (compact.isEmpty()) return false
        return CLOSE_REQUESTS.any { request ->
            val token = compactVendorText(request)
            compact == token || compact.startsWith(token)
        }
    }

    /**
     * One participant the composer can @. [name] is the speaker label used in
     * bubbles; [modelId] is an extra alias so `@mimo-v2.6-pro` still finds a
     * model whose display name is different.
     */
    data class MentionCandidate(
        val name: String,
        val modelId: String,
        val vendor: String,
        val host: Boolean,
        /** Visible identity that is not the model name, such as the host soul name. */
        val extra: String = "",
    )

    /**
     * Canonical speaker plus every string the user may type after @.
     * [key] distinguishes two models that share a display name.
     */
    data class Addressable(
        val name: String,
        val aliases: List<String> = listOf(name),
        val key: String = name,
    )

    /**
     * The first @ that names a participant, anywhere after whitespace.
     * Emails (`a@b.com`) and unmatched tokens, including skill paths, stay
     * ordinary text so the whole group still answers.
     */
    fun addressedName(text: String, names: List<String>): String? =
        resolveAddress(text, names.map { Addressable(it) })

    fun resolveAddress(text: String, targets: List<Addressable>): String? =
        resolveTarget(text, targets)?.name

    fun resolveTarget(text: String, targets: List<Addressable>): Addressable? {
        if (text.isEmpty() || targets.isEmpty()) return null
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if ((ch == '@' || ch == '＠') && (i == 0 || text[i - 1].isWhitespace())) {
                matchRest(text.substring(i + 1), targets)?.let { return it }
            }
            i++
        }
        return null
    }

    fun mentionCandidates(host: MentionCandidate, others: List<MentionCandidate>): List<MentionCandidate> {
        val hostName = host.name.trim().ifBlank { host.modelId.trim() }.ifBlank { "Host" }
        val hostRow = host.copy(name = hostName, host = true)
        val seen = mutableSetOf(hostRow.name to hostRow.modelId)
        val rest = others.mapNotNull { other ->
            val name = other.name.trim().ifBlank { other.modelId.trim() }
            if (name.isEmpty()) return@mapNotNull null
            val row = other.copy(name = name, host = false)
            if (!seen.add(row.name to row.modelId)) return@mapNotNull null
            row
        }
        return listOf(hostRow) + rest
    }

    fun filterMentions(candidates: List<MentionCandidate>, filter: String): List<MentionCandidate> {
        val query = compactVendorText(filter)
        if (query.isEmpty()) return candidates
        return candidates.filter { candidate ->
            compactVendorText(candidate.name).contains(query) ||
                compactVendorText(candidate.modelId).contains(query) ||
                compactVendorText(candidate.extra).contains(query)
        }
    }

    /** Unique display name when possible; model id only when names collide. */
    fun mentionInsertToken(candidate: MentionCandidate, roster: List<MentionCandidate>): String {
        val name = candidate.name.trim()
        if (name.isNotEmpty() && roster.count { it.name == candidate.name } == 1) return name
        val id = candidate.modelId.trim()
        if (id.isNotEmpty()) return id
        return name.ifBlank { "Host" }
    }

    private fun matchRest(rest: String, targets: List<Addressable>): Addressable? {
        if (rest.isEmpty()) return null
        var best: Addressable? = null
        var bestLen = 0
        for (target in targets) {
            for (alias in target.aliases) {
                val len = boundaryPrefixLength(rest, alias)
                if (len > bestLen) {
                    best = target
                    bestLen = len
                }
            }
        }
        if (best != null) return best
        val token = Regex("""^([^\s:：,，]{1,80})""").find(rest)?.groupValues?.getOrNull(1) ?: return null
        val compactToken = compactVendorText(token)
        if (compactToken.length < 2) return null
        return targets.maxByOrNull { target ->
            target.aliases.maxOfOrNull { aliasScore(compactToken, it) } ?: 0
        }?.takeIf { target ->
            target.aliases.any { aliasScore(compactToken, it) > 0 }
        }
    }

    private fun boundaryPrefixLength(rest: String, alias: String): Int {
        val name = alias.trim()
        if (name.length < 2 || rest.length < name.length) return 0
        if (!rest.regionMatches(0, name, 0, name.length, ignoreCase = true)) return 0
        val next = rest.getOrNull(name.length)
        if (next != null && !next.isWhitespace() && next !in ",，:：。.!！?？") return 0
        return name.length
    }

    private fun aliasScore(compactToken: String, alias: String): Int {
        val compactName = compactVendorText(alias)
        if (compactName.isEmpty() || compactToken.length < 2) return 0
        return when {
            compactName == compactToken -> 1000 + compactName.length
            compactName.startsWith(compactToken) -> 800 + compactToken.length
            compactToken.startsWith(compactName) -> 600 + compactName.length
            compactToken.length >= 3 && compactName.contains(compactToken) -> 400 + compactToken.length
            else -> 0
        }
    }

    fun opinionPrompt(name: String, stance: String, userText: String, prior: String, context: String): String = """
        你是 $name。按名单顺序发言，你的角色是「$stance」，不要改成和其他人一样的综述。
        ${stanceGuide(stance)}
        先读已有发言。主持人的开场只是焦点，不是观点，不要和主持人辩论。
        可以同意或反对其他模型，但必须写出依据。不要扮演其他人，不要只回一个字。
        需要查资料时可以调用工具；工具过程不会展示，正文里不要描述工具调用。
        用用户的语言，80 到 180 字。没有把握的地方直接说不确定。

        用户：
        $userText

        已有发言：
        ${prior.ifBlank { "（还没有其他人发言）" }}

        近期上下文：
        ${context.ifBlank { "（无）" }}
    """.trimIndent()

    fun directPrompt(name: String, userText: String, prior: String, context: String): String = """
        你是 $name。用户点名让你回答，其他模型本轮不发言。
        直接回答，不要写群聊总结，不要扮演别人。
        需要查资料可以调用工具，正文不要描述工具过程。
        用用户的语言，120 到 260 字。

        用户：
        $userText

        已有讨论：
        ${prior.ifBlank { "（无）" }}

        近期上下文：
        ${context.ifBlank { "（无）" }}
    """.trimIndent()

    fun replyPrompt(name: String, userText: String, prior: String): String = """
        你是 $name。阅读其他人的发言，补充他们没说到、或你认为说错了的点（可以补多个点）。
        如果没有新的观点，只回复 PASS。
        有内容时用用户的语言写，500 字以内，不要调用工具，不要重复自己的上一轮。

        用户：
        $userText

        已有发言：
        $prior
    """.trimIndent()

    fun openingPrompt(userText: String, context: String): String = """
        你是主持人，只负责这场讨论的开场。后面的模型会按顺序发言。
        不要表态，不要给结论，不要参与后面的讨论。
        用用户的语言，60 到 120 字：点明用户在问什么，列出要分清的 2 到 3 个焦点。
        不要扮演其他模型，不要调用工具，不要只回一个字。

        用户：
        $userText

        近期上下文：
        ${context.ifBlank { "（无）" }}
    """.trimIndent()

    fun summaryPrompt(userText: String, prior: String): String = """
        你是主持人。请把这场群聊整理成给用户的汇报，不要编造没人说过的观点。
        用用户的语言，分成三段：共识、分歧、建议。分歧要写清是谁和谁不同。
        这是讨论的结束汇报，不要再向其他模型提问。
        不要调用工具。

        用户：
        $userText

        讨论记录：
        $prior
    """.trimIndent()

    const val OPENING_SYSTEM =
        "你是 AI 群聊的主持人。开场只框定问题和焦点，不表态，不给结论，不扮演其他模型，也不参与后面的讨论。"

    const val HOST_DIRECT_SYSTEM =
        "你是这场群聊的主持人。只有用户点名时才直接回答，不参与其他人的讨论，不扮演其他模型。"

    const val HOST_SYSTEM = "你是 AI 群聊的主持人。只在讨论结束时汇报，不扮演其他模型，不编造他人没说过的话。"

    private val STANCES = listOf("主张", "质疑", "补漏", "落地")

    private val CLOSE_REQUESTS = listOf(
        "结束讨论",
        "结束这场讨论",
        "总结一下",
        "请总结",
        "出个结论",
        "收束讨论",
    )

    private fun stanceGuide(stance: String): String = when (stance) {
        "质疑" -> "指出最危险的假设或错误，不要重复别人的结论。"
        "补漏" -> "只补别人没覆盖的边界、成本或失败场景。"
        "落地" -> "写出下一步可以执行的做法，不要再展开原则。"
        else -> "给出一个明确主张：你推荐什么，以及为什么。"
    }

    fun memberSystem(name: String): String = """
        你是 $name，正在和其他模型的同一场群聊里发言。
        只代表你自己。可以调用分析工具。界面会显示你当前的思考或工具，正文出来后这些状态会收起。
        正文不要出现工具名、参数或“正在调用”。不要用一个字或 PASS 代替发言。
    """.trimIndent()
}
