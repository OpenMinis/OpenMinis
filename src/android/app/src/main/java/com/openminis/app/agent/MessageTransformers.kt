package com.openminis.app.agent

import java.io.File

/**
 * [T-message-transformers] Send-path text sanitizers (RikkaHub
 * MessageTransformer, scoped down to what our own stream pipeline can
 * legitimately leak) + user-message context injections.
 *
 * Two seams:
 *  - [transformAssistant] cleans assistant texts inside
 *    `effectiveAgentHistory` so leaked thinking tags and control artifacts
 *    cannot be imitated and amplified turn over turn.
 *  - [transformUser] enriches the LATEST user message with optional context
 *    the model otherwise forgets to check: the real current time, and a
 *    workspace pointer so long sessions keep finding their files.
 *    Both are designed to be prefix-cache-friendly: they only touch the
 *    final user message, never the static prompt head.
 */
interface MessageTransformer {
    val id: String
    fun transform(text: String): String

    /**
     * [T-display-strip-unterminated] Display/hydration variant of [transform].
     * Default = same behavior; a transformer whose send-path semantics are
     * too aggressive for painting (deleting content rather than cleaning
     * spelling) overrides this with a conservative mode. Wired via
     * [MessageTransformerChain.applyForDisplay].
     */
    fun transformForDisplay(text: String): String = transform(text)
}

/** Optional per-message context enrichment hook (RikkaHub TimeReminder / WorkspaceReminder family). */
interface UserMessageTransformer {
    val id: String
    /** Returns null when nothing to inject (keeps the request byte-identical). */
    fun transformUser(text: String, context: UserTransformContext): String?
}

/** Call-site context for [UserMessageTransformer] — provider-free so the chain is pure-JVM testable. */
data class UserTransformContext(
    /** Epoch millis of "now". */
    val nowMillis: Long = System.currentTimeMillis(),
    /** Set when the session's user-made workspace path is resolvable on the host. */
    val workspaceHint: String? = null,
)

object MessageTransformerChain {
    val transformers: List<MessageTransformer> = listOf(
        ReasoningTagStripper,
    )

    val userTransformers: List<UserMessageTransformer> = listOf(
        TimeReminder,
        WorkspaceReminder,
    )

    /** Clean an assistant reply before it is replayed to the model. */
    fun apply(text: String): String = foldTransformers(text) { tr, t -> tr.transform(t) }

    /**
     * [T-display-strip-unterminated] Clean a restored assistant text for
     * PAINTING (ChatViewModel hydration). Same chain, same crash fallback,
     * but each transformer runs its display variant. The send path may
     * delete aggressively (an unterminated reasoning prefix must never be
     * replayed); the display path must never delete prose — a reply that
     * bare-mentions a tag spelling lost its whole tail to the send-path
     * rule and rendered as thinking-card-only.
     */
    fun applyForDisplay(text: String): String = foldTransformers(text) { tr, t -> tr.transformForDisplay(t) }

    private inline fun foldTransformers(text: String, run: (MessageTransformer, String) -> String): String =
        transformers.fold(text) { t, tr ->
            // A transformer that throws on a pathological assistant turn (the
            // reported StringIndexOutOfBoundsException — begin 0, end N, length
            // N-1 — surfaces here, inside the fence/inline-code masking) must
            // not kill the whole agent loop, and must not leave the exception's
            // own message persisted as the turn's content (which is what made
            // the crash recur on every replay of that message). Fall back to
            // the text as it stood BEFORE this transformer and log the
            // offending shape so the real boundary bug can be found: the
            // transform is a cosmetic cleanup, the untransformed text is
            // always safe to send.
            try {
                run(tr, t)
            } catch (e: Exception) {
                com.openminis.app.logging.AppLogger.warning(
                    "MsgTransform",
                    "transformer ${tr.id} threw ${e.javaClass.simpleName}: ${e.message} " +
                        "on text.length=${t.length} head=\"${t.take(120)}\" — using untransformed text",
                )
                t
            }
        }

    /**
     * Enrich a user message before send. Returns the original text when no
     * transformer has anything to add — the empty-append case must not
     * grow the payload.
     *
     * [T-user-transformers-wired] Wired into the send path
     * (ChatViewModelSendExt.modelBody). The enrichments are REQUEST-side
     * only — never persisted — so every rebuild (retry, rerun, reload)
     * re-injects exactly once against a clean base. Per-transformer sentinels
     * still guard re-application when a caller hands us an already-enriched
     * text (see each transformer's skip marker).
     */
    fun applyUser(text: String, context: UserTransformContext): String {
        val expanded = expandResourceReferences(text)
        val additions = userTransformers.mapNotNull { it.transformUser(expanded, context) }
        if (expanded == text && additions.isEmpty()) return text
        if (additions.isEmpty()) return expanded
        return expanded + "\n\n" + additions.joinToString("\n\n")
    }

    /**
     * Strips thinking/reasoning tag blocks that occasionally leak into the
     * visible answer body (QwQ/DeepSeek/Gemini variants): paired
     * `<thinking>…</thinking>` / `<思考>…</思考>` / special-token
     * `<|thinking|>…<|/thinking|>` forms, case-insensitive, across newlines.
     *
     * [T-universal-think-tag-history] The variant list comes from the shared
     * [com.openminis.app.text.ReasoningTagVariants] table — the stripper and
     * the stream parser once carried two diverging copies, and paired blocks
     * spelled `<antThinking>`/`<inner_thought>`/`<scratchpad>` survived the
     * history path while the live stream stripped them ("刷新后思考块原文重现").
     *
     * [T-think-tag-code-fence] Matching is fenced: the transform runs only on
     * prose, never inside ``` / ~~~ fences or inline-code spans. Without
     * this, a reply that merely *explains* the tag — "Use the `<think>` tag
     * to mark reasoning" — had its prose deleted BEFORE replay to the model
     * (the exact defect the stream parser's start-anchored design fixed; the
     * history path kept committing it because regexes have no anchoring).
     */
    private object ReasoningTagStripper : MessageTransformer {
        override val id = "reasoning-tag-strip"

        // [T-regex-bomb] The actual stripping moved to the linear scanner
        // [ReasoningTagStrip]: the regex implementation this delegate used to
        // carry created a fresh ICU RegexMatcher (native heap, finalizer
        // freed) on every replace call of every assistant message of every
        // request assembly, and its unterminated-block lookahead patterns
        // backtracked deep enough to blow the 8 MB thread stack on
        // pathological turns. See ReasoningTagStrip's KDoc for the
        // tombstone forensics; behavior is pinned unchanged by
        // MessageTransformerChainTest / ReasoningTagStripperCoverageTest /
        // FenceAwareTagStripperTest / ReasoningTagStripTest.
        override fun transform(text: String): String = ReasoningTagStrip.strip(text)

        // [T-display-strip-unterminated] Unterminated opens keep their tail
        // in display mode — see ReasoningTagStrip.stripForDisplay.
        override fun transformForDisplay(text: String): String = ReasoningTagStrip.stripForDisplay(text)
    }

    /**
     * [T-time-reminder] Injects the wall-clock time into the LATEST user
     * message. Long agent turns (multi-tool, queued prompts, goal
     * continuations) can run far past the moment the user sent the text; a
     * model answering "what's the weather now" or "remind me in an hour"
     * without knowing the current time answers from the conversation's start.
     * The system prompt's Runtime context only updates when the prompt is
     * rebuilt, so this catches intra-loop drift.
     */
    private object TimeReminder : UserMessageTransformer {
        override val id = "time-reminder"

        private val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        override fun transformUser(text: String, context: UserTransformContext): String? {
            // [T-user-transformers-wired] Idempotency sentinel: already stamped
            // once this request — never stamp twice (cross-hour retries would
            // otherwise accumulate conflicting timestamps).
            if (text.contains("<system-note>当前时间")) return null
            // Skip for messages that clearly aren't time-sensitive
            // (pure code/file operations don't benefit from a clock stamp).
            val needsTime = TIME_SENSITIVE_HINTS.any { hint -> text.contains(hint, ignoreCase = true) }
            if (!needsTime) return null
            val now = java.time.Instant.ofEpochMilli(context.nowMillis)
                .atZone(java.util.TimeZone.getDefault().toZoneId())
            return "<system-note>当前时间: ${format.format(now)}</system-note>"
        }

        private val TIME_SENSITIVE_HINTS = listOf(
            "现在", "now", "today", "今天", "几点", "时间", "weather", "天气",
            "remind", "提醒", "schedule", "安排", "分钟", "小时", "minute", "hour",
        )
    }

    /**
     * [T-workspace-reminder] When the caller resolves the user's workspace
     * (i.e. files actually live somewhere), remind the model where its own
     * outputs go. Prevents the classic long-session drift where the agent
     * keeps writing into a stale or wrong directory after offloads.
     */
    private object WorkspaceReminder : UserMessageTransformer {
        override val id = "workspace-reminder"

        override fun transformUser(text: String, context: UserTransformContext): String? {
            // [T-user-transformers-wired] Idempotency sentinel (see TimeReminder).
            if (text.contains("<system-note>工作目录")) return null
            val hint = context.workspaceHint ?: return null
            return "<system-note>工作目录: $hint — 读取和写入用户文件时优先使用该目录。</system-note>"
        }
    }

    /**
     * [T-document-as-prompt] Rewrites minis:// resource links in a user
     * message into explicit read instructions. Some models silently ignore
     * bare `minis://…` URLs; telling them "this is a readable file, use
     * file_read with this path" converts a passive link into an actionable
     * prompt — the RikkaHub DocumentAsPrompt idea, but resolution stays on
     * the caller side (no inline file content, so no context blowup).
     */
    fun expandResourceReferences(text: String): String {
        val resourceLinks = Regex("""minis://(workspace|shared|attachments|offloads)/([A-Za-z0-9._%+/\-]+)""")
        val matches = resourceLinks.findAll(text).toList()
        // [T-user-transformers-wired] Idempotency sentinel: a second pass over
        // already-expanded text must not nest a second <system-note> wrapper.
        if (text.contains("<system-note>消息中引用了文件")) return text
        if (matches.isEmpty()) return text
        val sb = StringBuilder()
        val additions = StringBuilder()
        matches.forEach { m ->
            val hostPath = "/var/minis/${m.groupValues[1]}/${m.groupValues[2]}"
            if (additions.isEmpty()) additions.append("\n\n<system-note>消息中引用了文件，读取时请用: file_read path=\"$hostPath\"")
            else additions.append("；另可读 $hostPath")
        }
        if (additions.isEmpty()) return text
        sb.append(text).append(additions).append("</system-note>")
        return sb.toString()
    }
}

/** Host-side workspace resolution hook (Android-specific; kept out of the pure chain). */
fun interface WorkspaceHintResolver {
    fun resolve(): String?
}
