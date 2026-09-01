package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.BashismReminder
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

class ChatViewModel(
    internal val sessionId: String,
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel() {

    companion object {
        internal const val TAG = "ChatViewModel"

        // ── [T-android-compact-runaway] Compaction budgets ──────────────
        //
        // Compaction had no ceiling of any kind. Its only time bound was the
        // provider's OkHttp readTimeout (10 minutes on every provider), and
        // the split-retry path could issue up to 1+2+4+8 = 15 SEQUENTIAL leaf
        // calls before depth 3 stopped it. Slow-but-not-timing-out calls (a
        // rate-limited or queued model at ~80s each) therefore added up to
        // roughly 20 minutes of apparent hang — which matches the report.
        //
        // Three independent ceilings now bound it, because each catches a case
        // the others miss: the call budget stops fan-out, the wall-clock
        // timeout stops slow-but-few calls, and the existing depth cap stops
        // recursion.

        /**
         * Leaf LLM calls one compaction may issue in total, across every
         * segment. The depth-3 cap alone permits 15; this cuts the worst case
         * to a third of that while still allowing a full first split (1+2) plus
         * one deeper rescue.
         */
        internal const val MAX_COMPACT_LLM_CALLS = 6

        /** Floor for the dynamic wall-clock timeout. */
        internal const val COMPACT_TIMEOUT_BASE_MS = 90_000L

        /**
         * Added per 10k characters of transcript, so a long first compaction is
         * not cut off by a limit tuned for a short one.
         */
        internal const val COMPACT_TIMEOUT_PER_10K_CHARS_MS = 30_000L

        /**
         * Hard ceiling. Deliberately under the providers' 10-minute
         * readTimeout: past this point the run is aborted by us — with the lock
         * released and a clear message — rather than sitting on a socket that
         * may never answer.
         */
        internal const val COMPACT_TIMEOUT_MAX_MS = 300_000L

        /**
         * Wall-clock budget for compacting a transcript of [transcriptChars].
         * Grows with input so long histories get room, capped so nothing can
         * hang indefinitely.
         */
        internal fun compactTimeoutMsFor(transcriptChars: Int): Long {
            val growth = (transcriptChars / 10_000L) * COMPACT_TIMEOUT_PER_10K_CHARS_MS
            return (COMPACT_TIMEOUT_BASE_MS + growth).coerceAtMost(COMPACT_TIMEOUT_MAX_MS)
        }

        /**
         * Should a failed summary attempt be retried by splitting the input in
         * half? Pure predicate, in the companion so it is testable without an
         * Android-bound ViewModel; [isSegmentRetryableError] delegates here.
         *
         * Splitting only helps when the failure was caused by the SIZE of the
         * request. Unclassified errors still split — an over-length refusal
         * arrives as an untyped ProviderError on most providers, and a summary
         * built from halves beats no summary — but the classes known to be
         * size-independent are excluded, because for those a split turns one
         * failure into up to 15 sequential slow calls. That amplification is
         * what produced the 15-20 minute apparent hang.
         */
        internal fun shouldSplitOnError(error: Throwable): Boolean {
            if (error is CancellationException) return false
            if (error is LLMError) {
                return when (error) {
                    // Never worth a smaller payload:
                    //  - Cancelled: the user stopped it; retrying fights that.
                    //  - NetworkError: never reached a model, size is irrelevant.
                    //  - RateLimited (429): refusing on quota, not length —
                    //    halving just doubles the rejected calls under backoff.
                    //  - TransientError (5xx): server-side fault, payload
                    //    independent; retrying smaller multiplies the outage.
                    //  - InvalidApiKey: auth, not size.
                    is LLMError.Cancelled,
                    is LLMError.NetworkError,
                    is LLMError.RateLimited,
                    is LLMError.TransientError,
                    is LLMError.InvalidApiKey,
                    -> false
                    // ProviderError / DecodingError / Unknown stay retryable:
                    // an over-length refusal arrives as a ProviderError on most
                    // providers, and that is the case splitting exists for.
                    else -> true
                }
            }
            // Raw OkHttp/socket failures are the Android equivalent of iOS's
            // NSURLErrorDomain bail-out: offline / DNS / TLS / timeout, all
            // payload-size independent.
            if (error is java.io.IOException) return false
            return true
        }

        /**
         * [T-android-append-to-input-eats-draft] Join the composer's current
         * [draft] with an appended [snippet]. Returns null when there is
         * nothing to append (the caller then leaves the draft untouched).
         *
         * Trims the incoming SNIPPET only. The old code called
         * `draft.trimEnd()` and assigned that trimmed copy back, so "Add to
         * input" silently rewrote the user's existing draft: a deliberate
         * trailing newline — a paragraph break they had just typed — was
         * swallowed and replaced by the separator space. The draft is the
         * user's own text and must come back byte-for-byte.
         *
         * The emptiness test still runs on a trimmed VIEW of the draft (a
         * whitespace-only draft counts as empty, rather than producing a
         * leading blank run), but that trimmed value drives the DECISION
         * only — it is never assigned back. Mirrors iOS `e6c0ace6a`.
         *
         * Pure and side-effect free so it can be unit-tested without an
         * Android runtime; see `AppendToInputTest`.
         */
        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            if (draft.isBlank()) return "$cleaned "
            // Preserve the draft verbatim; only add a separator when it does
            // not already end in whitespace. A trailing newline is already a
            // separator, and adding a space after it would indent the new line.
            val separator = if (draft.last().isWhitespace()) "" else " "
            return draft + separator + cleaned + " "
        }

        /**
         * [T-android-auto-grouping-injection] Strip the characters that would let
         * user-authored text escape its slot in the prompt's group list, then
         * bound the length.
         *
         * The list is rendered as `"name" — desc; "name2" — desc2`, so a quote,
         * bracket or semicolon inside a value can terminate the list early and the
         * remainder reads as instruction. Newlines do the same at the line level.
         * Collapses whitespace so a name padded with tabs/newlines can't blow the
         * budget either.
         *
         * Deliberately NOT escaping instead of stripping: the sanitized name has to
         * survive a round trip (the model echoes it back and we match it against
         * the real folder name), and an escape sequence would come back escaped.
         * Stripping keeps the value matchable — findFolderByName's trim +
         * case-fold absorbs the difference for every realistic group name.
         */
        internal fun promptSafe(raw: String, max: Int): String =
            raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
                // Unicode quote lookalikes: a model reads curly and CJK
                // brackets as quoting just as readily as ASCII, so leaving
                // them in re-opens the break-out the ASCII strip closes.
                .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
                // Format/bidi controls (RLO, LRO, ZWJ...) — invisible in code
                // review, and they can reorder how the rendered line reads.
                .replace(Regex("\\p{Cf}"), "")
                // WHITESPACE: Kotlin Regex is java.util.regex WITHOUT
                // UNICODE_CHARACTER_CLASS, so plain \\s is only
                // [ \\t\\n\\x0B\\f\\r] — U+2028 LINE SEPARATOR, U+2029
                // PARAGRAPH SEPARATOR and U+0085 NEL slip through as REAL line
                // breaks, which is exactly the multi-line break-out this
                // sanitizer exists to stop. \\p{Z} additionally covers NBSP
                // (U+00A0) and the ideographic space, neither of which
                // Kotlin's trim() removes either.
                .replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
                .trim()
                .take(max)
                // Trim AGAIN after the cut: take() can leave a trailing space,
                // and the round-trip matcher compares trimmed values.
                .trim()

        // [T-preflight-tool-title-nonblocking] Fields kept in each tool's
        // `required` list (so the schema keeps nudging the model to emit them —
        // tool_title drives the live pill header) but which must NOT block the
        // call when absent: they carry no execution semantics, so rejecting the
        // whole call over a missing one is pure downside. Preflight skips these
        // when checking for missing required fields. Mirrors iOS
        // AIChatViewModel.preflightNonBlockingFields.
        private val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

        /**
         * (tool name → field names) where an EMPTY STRING is a semantically
         * valid value and must not be treated as "missing".
         *
         * Distinct from [PREFLIGHT_NON_BLOCKING_FIELDS], which skips the
         * missing-field check entirely: these fields must still be PRESENT in
         * args — they are just allowed to hold "" as their content.
         *
         * The canonical case is `file_edit.new_string`, whose schema documents
         * "Use empty string to delete old_string". Blocking it broke a promised
         * deletion workflow and pushed the model into shell_execute + python
         * file-rewrite workarounds. Mirrors iOS
         * AIChatViewModel.preflightEmptyStringAllowedFields.
         * [T-preflight-empty-string-allowed]
         */
        private val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
            "file_edit" to setOf("new_string"),
        )

        /** True when "" is a legal value for this exact (tool, field) pair. */
        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        /**
         * Reject tool calls that have empty args or are missing required fields
         * BEFORE [executeTool] runs. Returns null when the call is well-formed,
         * or a human-readable reason string when it should be blocked.
         *
         * Driven off the canonical [AgentToolDefinition.required] list so the
         * validator never drifts from the schema published to the model. For
         * string fields we additionally require non-blank content — the model
         * occasionally emits `{"path": ""}` which passes the "key exists" check
         * but is just as broken as a missing key. We do NOT validate type beyond
         * string-emptiness here; richer schema checks (enum, regex, integer
         * range) belong in each tool's own helper because they need tool-specific
         * context.
         *
         * Mirror of iOS preflightValidateToolCall in AIChatViewModel.swift.
         *
         * Lives in the companion (and is `internal`) because it is PURE — it reads
         * only its parameters and companion constants — so unit tests can exercise
         * it without constructing a ChatViewModel and its dependency graph. Mirrors
         * the same `nonisolated static` move on iOS.
         */
        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            // Unknown tool names go through to the existing `else` branch in
            // executeTool() which returns "Unknown tool: …". Preflight stays
            // silent so we don't double-fail.
            val toolDef = tools.firstOrNull { it.name == name } ?: return null
            // Required fields that actually gate execution (everything except the
            // non-blocking ones like tool_title — see PREFLIGHT_NON_BLOCKING_FIELDS).
            val enforced = toolDef.required.filter { it !in PREFLIGHT_NON_BLOCKING_FIELDS }
            // Empty args on a tool that requires anything → block. Gate on
            // `enforced` so a tool whose only required field is non-blocking isn't
            // rejected for empty args, and the message lists only real blockers.
            if (args.length() == 0 && enforced.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
            }
            val missing = mutableListOf<String>()
            for (field in enforced) {
                // Absent — or present as an explicit JSON null. org.json reports
                // has() == true for `{"x": null}` and opt() hands back
                // JSONObject.NULL, which is not a String, so a null previously
                // slipped through BOTH checks and reached the tool as a non-String
                // value. Both spellings are genuinely missing.
                if (!args.has(field) || args.isNull(field)) {
                    missing.add(field)
                    continue
                }
                val raw = args.opt(field)
                // Only the truly-empty literal "" is rejected — NOT whitespace.
                // The earlier `.trim().isEmpty()` over-rejected legitimate payloads,
                // most notably file_edit with `new_string: "\n"` (replace a block
                // with a newline) or `old_string: "  "` (match consecutive spaces).
                // Both are valid edits, neither is stream corruption.
                //
                // And even "" is legal for whitelisted (tool, field) pairs:
                // file_edit.new_string == "" is the documented "delete old_string"
                // form, not a missing value. [T-preflight-empty-string-allowed]
                if (raw is String && raw.isEmpty() &&
                    !preflightEmptyStringAllowed(name, field)
                ) {
                    missing.add(field)
                }
            }
            if (missing.isNotEmpty()) {
                return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            }
            return null
        }
        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        private const val NEWLINE_FLUSH_MIN_CHARS = 50
        private const val NEWLINE_FLUSH_MAX_LEN = 5_000
        // [T-android-larky-longsession-followup] see uiMessages / hasOlderMessages.
        /** Tail window size used by [uiMessages] when a session exceeds it. */
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        /** Each "load older" tap grows the cap by this many messages. */
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        /**
         * Sessions with this many or fewer messages bypass the windowing
         * machinery entirely — the derived `uiMessages` returns the same
         * list reference as `messages`, so Compose sees identity-equal
         * snapshots and the existing flat/stream pipeline is untouched.
         */
        const val LONG_SESSION_THRESHOLD: Int = 300
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        private val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        private const val TAG_STREAM = "ChatVMStream"
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        private const val MAX_AGENT_TURNS = 200
        private const val MIN_MAX_TOKENS = 1024
        /**
         * Hard ceiling on max_tokens we ever send to a provider, regardless
         * of what the model itself claims. Some models advertise 128K+
         * output windows that in practice produce wandering, low-signal
         * responses and burn through context budget; cap so a single turn
         * can't run away. Mirrors iOS AIChatViewModel.globalMaxTokensCeiling.
         * [T-android-global-max-tokens-128k] Raised 64K → 128K (iOS 8a401ab6):
         * 64K clipped newer large-output models AND the number-budget thinking
         * tiers whose budget is carved out of max_tokens (Anthropic legacy
         * high/xhigh/max, Qwen thinking_budget — DashScope clamps it strictly
         * below max_completion_tokens). Raising only lifts the upper bound —
         * the value is still clamped by the model's own maxOutputTokens and
         * the remaining context window in dynamicMaxTokens().
         */
        private const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Sentinel prefix on synthetic tool_result output marking
         * user-cancelled calls. Aligned with iOS
         * AIChatViewModel.swift:5163 so a session sync'd between
         * platforms shows the same `<system-reminder>…` text the model
         * sees on the next API call (rather than "[cancelled by user]"
         * which iOS would treat as opaque tool output).
         */
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        /**
         * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
         * tool-block restore can still recognise rows persisted by
         * earlier app versions and surface them as CANCELLED instead
         * of FAILED. Never emitted by this version.
         */
        private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        private const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        /// Max per-tool-call retained `accumulated` JSON snapshots from
        /// `ToolInputDelta`. Drained on preflight failure for diagnosis.
        private const val TOOL_INPUT_CHUNK_RING_MAX = 10
        /** Auto-retry backoff schedule (seconds). Mirrors iOS retryDelays, scaled to task spec: 1s → 2s → 4s. */
        private val AUTO_RETRY_DELAYS_SEC = intArrayOf(1, 2, 4)

        /**
         * Factory for use with `viewModel(factory = ...)`. Binds the ChatViewModel
         * to a NavBackStackEntry's ViewModelStore so the streaming job survives
         * configuration changes (rotation) and re-entering the chat screen while
         * the backstack entry is alive.
         */
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = sessionId,
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ) as T
            }
        }
    }

    private val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: keep `_messages` as the canonical full list (every legacy
    // caller — compact / fork / regenerate / agentHistory / send pipeline —
    // still sees the whole thing) and expose a derived `uiMessages` that
    // takes the TAIL N. ChatScreen consumes `uiMessages`; everything else
    // keeps reading `messages`. When the list is short (<= cap) the derived
    // value IS the source list (same reference), so this is zero-overhead
    // for normal sessions.
    //
    // Users scroll up through the windowed slice; when they reach the top
    // of the tail-window AND older messages exist, [loadOlderMessages]
    // bumps the cap by [WINDOW_STEP] and the derived flow re-emits with
    // the older slice included.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    private val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
    /**
     * Current tail cap. Reflective via [uiMessages]; bump with
     * [loadOlderMessages] when the user scrolls past the windowed top.
     * Reset to [INITIAL_VISIBLE_MESSAGE_CAP] each time [loadSession]
     * (re)mounts a session — different sessions shouldn't inherit each
     * other's caps.
     */
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    /**
     * Tail-windowed view of [messages] for ChatScreen's LazyColumn. For
     * sessions with `count <= LONG_SESSION_THRESHOLD` or `count <= cap`
     * this returns the EXACT SAME list reference as `_messages.value` —
     * Compose / collectAsState gets identity-equal snapshots, no extra
     * allocation, no behavior change for normal sessions.
     */
    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it —
            // the Android analog of iOS applySnapshot (T-bridge-message-ui-leak).
            // Today the bridge lives in agentHistory only (never in _messages),
            // so this is defensive; it guards against a future refactor routing
            // the bridge into _messages. Only allocate a new list when a bridge
            // is actually present, keeping the identity-equal fast path intact.
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            // [T-android-uimessages-sublist-cme] `.toList()` is defensive
            // hardening, NOT a proven fix for the reported crash. Read the
            // measured facts before changing it back.
            //
            // `subList` returns a live VIEW sharing the parent's modCount, and
            // emitting it puts that view in Compose state (ChatScreen collects
            // `uiMessages`). That is a latent hazard worth closing on its own.
            //
            // MEASURED, so nobody re-derives it: a SubList only throws
            // ConcurrentModificationException when its PARENT is structurally
            // mutated IN PLACE (add/removeAt/clear). Every write here is
            // `_messages.value = <new list>` via `+` / filterNot / map, and all
            // of those ALLOCATE A FRESH ArrayList rather than mutating — so the
            // old view's parent is never touched and no CME results. Verified on
            // a JVM probe (`base + x`, `filterNot`, `map` all return a new
            // java.util.ArrayList; comparing a stale window after such a write
            // returned OK, not CME).
            //
            // Also verified end-to-end on device (Pixel 4a, build with this
            // `.toList()` deliberately REVERTED): create a multi-turn session,
            // long-press a middle user message → 编辑 → send. `truncateBeforeEdit`
            // provably ran (8 messages → 4), storing a live SubList as
            // `_messages.value`, and a further message was sent — NO crash. The
            // next `+` copies the SubList back into a plain ArrayList, so the
            // view stops being the state before anything can invalidate it.
            //
            // The user's crash (ArrayList$SubList.equals, main thread, realme
            // RMX5010 / Android 16, 2026-08-10/11/12) therefore still has an
            // UNIDENTIFIED trigger: something must mutate a subList's parent in
            // place. That site was not found in ChatViewModel; look next at
            // ChatFlatItems / ChatScreen and at any long-lived mutableListOf
            // whose contents reach Compose.
            //
            // Keep the copy regardless: the window is a snapshot by definition,
            // so copying is also the correct semantics. Only long sessions past
            // the cap allocate; the common path above still returns `raw`
            // unchanged and stays identity-equal.
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */
    val hasOlderMessages: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /**
     * Bump the visible cap by [VISIBLE_MESSAGE_CAP_STEP], saturating at
     * the total message count. Safe to call when there are no older
     * messages — it's a no-op (cap clamps to size). Called by the
     * LazyColumn's "load older" header when the user reaches the top of
     * the windowed slice.
     */
    fun loadOlderMessages() {
        val totalNow = _messages.value.size
        if (totalNow <= LONG_SESSION_THRESHOLD) return
        val next = (_visibleMessageCap.value + VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
        if (next != _visibleMessageCap.value) {
            _visibleMessageCap.value = next
        }
    }

    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    private val _streamingById = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    /**
     * [T-android-stream-flush-dualpath] Per-message streaming-flush state for
     * the dual-path throttle in [updateAssistantMessage]. Keyed by messageId so
     * the throttle accumulator survives the high-frequency token calls (the
     * earlier per-fragment produceState version reset every fragment rebuild and
     * so never actually throttled — diagnostics showed every tick flushing).
     * Mirrors iOS AIChatViewModel+SSEStream's lastTextDeltaFlush/…Length.
     */
    private class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        var trailingJob: Job? = null
        // [T-android-stream-flush-review] Freshest suppressed delta. Updated on
        // EVERY throttled tick so the trailing job publishes the latest content
        // (not the stale value captured when the job was first scheduled) — a
        // burst of sub-throttle deltas followed by a pause would otherwise leave
        // the side channel several deltas behind.
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }
    private val streamFlushStates = HashMap<String, StreamFlushState>()

    /**
     * [T-android-stream-flush-review] Cancel a message's pending trailing flush
     * and drop its throttle accumulator. Call from EVERY stream-termination
     * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
     * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
     * therefore NOT cancelled by streamJob.cancel() — can't fire after the
     * side channel was drained and re-revive a stale "thinking" overlay row.
     */
    private fun clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }
    private fun clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }
    /** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
    private fun retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    // Dual-path flush thresholds — ported from iOS. Time tiers scale with total
    // length; the newline fast-path flushes immediately on a line break once
    // enough new chars have accumulated, gated to short docs so dense
    // box-drawing streams don't pin the flush rate to the per-token cadence.
    private fun streamFlushThrottleMs(len: Int): Long = when {
        len < 500 -> 200L
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    /** Read-and-clear the pending caret so it applies exactly once. */
    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the LazyListState on the
     * session-scoped VM (kept alive by ChatViewModelStore) guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */
    val listState: LazyListState = LazyListState(0, 0)

    fun setInputText(value: String) {
        _inputText.value = value
    }

    /**
     * [T-selection-add-to-input] Append [snippet] to the chat composer
     * with a single trailing space:
     *   - composer empty → `"<snippet> "`
     *   - composer non-empty → `"<existing><separator><snippet> "`
     *
     * [T-android-append-to-input-eats-draft] Trim the incoming SNIPPET only.
     * The old code called `current.trimEnd()` and assigned that trimmed copy
     * back, so "Add to input" silently rewrote the user's existing draft: a
     * deliberate trailing newline (a paragraph break they had just typed) was
     * swallowed and replaced by the separator space. The draft is the user's
     * own text and must come back byte-for-byte.
     *
     * The emptiness test still runs on a trimmed view — a draft of only
     * whitespace should be treated as empty rather than producing a leading
     * blank run — but that trimmed value drives the DECISION only, never the
     * assignment. Mirrors iOS `e6c0ace6a`.
     */
    fun appendToInputText(snippet: String) {
        val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
        _inputText.value = joined
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to ChatViewModelUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    private val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /**
     * [T-android-group-pause-badge-restamp] Marks the ONE `_canResume = true`
     * assignment that is a RE-DETECTION of an interruption that already
     * happened (loadSession finding a still-unfinished DB tail, possibly days
     * old) rather than a live new interruption. Read by the badge collector to
     * decide whether the badge's entry timestamp may be overwritten — see the
     * collector's comment for why the badge must NOT be re-stamped there.
     *
     * Why a COUNTER and not a plain boolean set-then-cleared around the
     * assignment: the collector is an async `collect` on a StateFlow, running
     * on its own coroutine. A boolean cleared right after the assignment is
     * very likely already `false` by the time the collector is resumed and
     * observes the `true`, so the annotation would be lost and the stale badge
     * re-stamped anyway — the exact bug being fixed. Instead the flag is
     * STICKY: the detecting site raises it BEFORE assigning and never clears
     * it; the collector clears it only once it has actually consumed the
     * emission it annotates. The generation counter makes that consumption
     * unambiguous even if several loads race — the collector compares the
     * value it latched against the current one.
     *
     * StateFlow conflation is also handled by this shape: if `_canResume` is
     * already `true`, the re-detection assignment emits nothing at all, so the
     * collector never runs and never re-stamps — which is the desired outcome
     * (no push, no stamp change). The pending mark simply stays raised and is
     * consumed by the next `true` emission, which for this VM instance can
     * only come from the same load path re-running (every live-interruption
     * site is preceded by a `false`, i.e. by a real run that clears it — see
     * `markLiveInterruption`).
     */
    @Volatile private var redetectingInterruptedTailGen: Long = 0L
    @Volatile private var consumedRedetectGen: Long = 0L

    /**
     * Raise the re-detection mark for the next `_canResume = true` emission.
     * Mirrors iOS `isRedetectingInterruptedTail = true` at the +Persistence
     * detection site.
     */
    private fun markRedetectingInterruptedTail() {
        redetectingInterruptedTailGen += 1
    }

    /**
     * Cancel any pending re-detection mark. Called by every LIVE interruption
     * path before it sets `_canResume = true`, so an unconsumed mark left over
     * from a load (e.g. the load found the tail interrupted while `_canResume`
     * was already true, so nothing was emitted) can never leak onto a genuine
     * new interruption and suppress its re-stamp.
     */
    private fun markLiveInterruption() {
        consumedRedetectGen = redetectingInterruptedTailGen
    }

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call truncates the conversation from this message (inclusive)
     * before persisting the new content as a fresh user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    private val sessionLoaded = MutableStateFlow(false)

    private val _sessionTitle = MutableStateFlow("New Chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    private val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    /**
     * [T-android-paste-placeholder] Long pasted blocks folded out of the
     * composer, keyed by the `[Pasted#N]` marker left in its place.
     *
     * Scoped to this ViewModel, so it is per-session by construction: the
     * store hands each session its own instance, and switching chats cannot
     * leak an id from one buffer into another's placeholders. Memory-only —
     * see [PastedText] for why persisting it would be worse than not.
     */
    private val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()

    /**
     * Next placeholder number. Monotonic for the session's lifetime and never
     * reused, even after entries are consumed or deleted: a recycled id would
     * let a stale marker left in the draft ("I pasted, deleted the chip, then
     * pasted again") silently expand to the WRONG text. Numbers are cheap.
     */
    private var nextPasteId: Int = 1

    /**
     * [T-android-paste-placeholder] Buffer [text], returning the marker to put
     * in the composer in its place.
     */
    fun stashPastedText(text: String): String {
        val entry = PastedText(id = nextPasteId++, text = text)
        _pastedTexts.value = _pastedTexts.value + entry
        AppLogger.info(TAG, "[Paste] stashed #${entry.id} (${text.length} chars)")
        return entry.placeholder
    }

    /**
     * [T-android-paste-oversize] Turn a very large paste into a real `.txt`
     * document attachment instead of a placeholder.
     *
     * Past [PASTE_AS_FILE_THRESHOLD] the user is effectively attaching a
     * document, and the placeholder path is the wrong shape for it: the block
     * would sit in memory for the whole draft and then have to be written out
     * anyway. Routing it through the ordinary attachment pipeline instead means
     * it inherits preview, removal, the `<user-attached-files>` inventory the
     * model can `cat`, and the same upload handling as a file the user picked —
     * none of which the buffer offers.
     *
     * The bytes go to `cacheDir/pasted_text`, matching where
     * [addAttachmentFromStagedShare] puts share-inbound copies: the composer may
     * hold this for a long time before send, so it must not live anywhere the
     * system might reclaim mid-draft.
     *
     * Returns null if the write fails, and the caller then leaves the paste in
     * the text field verbatim — worse-looking than a chip, but nothing is lost.
     */
    fun stashPastedTextAsFile(text: String): InputAttachment? {
        val dir = java.io.File(context.cacheDir, "pasted_text").apply { mkdirs() }
        // Timestamp + short uuid: sorts chronologically in a file listing and
        // cannot collide when two pastes land in the same millisecond.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "Pasted_$stamp-${java.util.UUID.randomUUID().toString().take(8)}.txt"
        val file = java.io.File(dir, name)
        return try {
            file.writeText(text)
            val attachment = InputAttachment(
                fileName = name,
                uri = android.net.Uri.fromFile(file),
                mimeType = "text/plain",
                kind = InputAttachment.Kind.DOCUMENT,
            )
            addAttachment(attachment)
            AppLogger.info(
                TAG,
                "[Paste] oversize paste -> file attachment $name (${text.length} chars)",
            )
            attachment
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Paste] failed to write oversize paste: ${e.message}")
            null
        }
    }

    /**
     * Drop one buffered entry (the chip's delete button). The caller is
     * responsible for also removing the marker from the composer text — see
     * ChatScreen, which does both in one edit so the two never disagree.
     */
    fun removePastedText(id: Int) {
        _pastedTexts.value = _pastedTexts.value.filterNot { it.id == id }
    }

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    private val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    private val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * [T-android-tool-autoscroll] Fire-and-forget edge events that ask the
     * ChatScreen to scroll the LazyColumn to the visual bottom (index 0 under
     * reverseLayout). Distinct from the streaming-auto-follow collector — that
     * pipeline needs growth ticks to advance its distinctUntilChanged tuple,
     * but agent-loop START events (sendMessage, resume / "Continue", retry)
     * produce only a brief thinking placeholder before any content streams.
     * Without an explicit edge signal, the placeholder + composer interaction
     * area sits behind the input bar until the model's first token arrives
     * and the regular auto-follow finally fires. Each ViewModel entry that
     * starts a fresh agent-loop turn emits to this flow.
     */
    private val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    /**
     * [T-android-readaloud-stop-stale] Emitted on the FIRST text delta of a new
     * reply, so any Read Aloud still playing from the previous reply is
     * stopped before the new content starts arriving.
     *
     * Deferred to the first delta rather than fired from send(): the old reply
     * should keep playing while the model is still thinking, and only yield
     * once there is actually new text to supersede it. Mirrors iOS
     * `d2fdc784f`, which sets `hasStoppedPreviousTTS` at the same point.
     *
     * The player is screen-scoped (ChatScreen owns it), so this is a signal
     * rather than a direct call — the ViewModel has no reference to it.
     */
    private val _stopStaleReadAloud = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopStaleReadAloud: SharedFlow<Unit> = _stopStaleReadAloud.asSharedFlow()

    private val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    private val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    private val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    private val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    private val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    private val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    /**
     * Latest compact summary for the current session, loaded from the DB on
     * [loadSession] and re-populated after [compactAll] finishes. When non-null,
     * [effectiveAgentHistory] prepends it as a `<context-summary>` user message
     * so the model sees a condensed recap of the turns we folded away while
     * keeping the full [agentHistory] on disk as an audit trail. Mirrors iOS
     * Phase-B compact semantics (summary synthesized at inference time, never
     * baked back into agentHistory).
     */
    private val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    /**
     * [T-android-compact-progress] Live progress of the in-flight compaction.
     *
     * Compaction could previously run for many minutes behind a single
     * unchanging "compacting" flag, which is indistinguishable from a hang —
     * the reported symptom was users staring at it for 15-20 minutes with no
     * way to tell whether it was working or wedged. This carries enough state
     * for the UI to show real movement: elapsed seconds, which segment of a
     * split is running, and how deep the split went.
     */
    data class CompactProgress(
        /** When the whole compaction started, for elapsed-time display. */
        val startedAtMs: Long,
        /** Recursion depth currently executing (0 = whole history, >0 = a split half). */
        val depth: Int = 0,
        /** Leaf LLM calls issued so far, across all segments. */
        val callsIssued: Int = 0,
        /** Total leaf calls allowed before the budget aborts the run. */
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        /** Seconds the whole run is allowed before it is cancelled. */
        val timeoutSeconds: Int = 0,
    )

    private val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

    /**
     * Leaf LLM calls issued by the current compaction. Reset at the start of
     * each run; read/incremented from the split recursion, which can interleave
     * across suspension points, hence atomic.
     */
    private val compactCallsIssued = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The running compaction's job, so the UI can offer a Cancel affordance.
     * Cancelling routes through the same `finally` that clears the lock, so a
     * user-cancelled compaction leaves no state behind.
     */
    private var compactJob: Job? = null

    /** Cancel an in-flight compaction. No-op when nothing is running. */
    fun cancelCompact() {
        val job = compactJob ?: return
        if (!job.isActive) return
        AppLogger.info(TAG, "[Compact] cancelled by user")
        job.cancel(CancellationException("compact cancelled by user"))
    }

    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    private val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    private val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    // [T-android-stale-streamjob-clears-isstreaming] @Volatile so cross-coroutine
    // reads (the orphaned previous streamJob's tail block running on a different
    // dispatcher) see the latest assignment. Without it, an old job's
    // `if (streamJob === thisJob)` guard could read a cached reference and
    // wrongly reset _isStreaming on the new live job — the exact race XIN hit
    // 2026-06-12 20:22:26 / 20:23:25 (cancel → resume → cancel → retry, where
    // the cancelled resume's finally fired ~2s after the new retry was already
    // streaming, hiding the Stop button while the new turn was live).
    @Volatile
    private var streamJob: Job? = null
    private var currentProvider: LLMProvider? = null
    private var currentModel: LLMModel? = null

    /**
     * Does the CURRENTLY RESOLVED main model natively consume image pixels?
     *
     * Single source of truth for the three decisions that must agree: whether
     * `read_image` is exposed at all ([agentTools]), whether an attached image
     * is replaced by a Vision Group placeholder ([visionPlaceholderFor]), and
     * whether `read_image` returns pixels or routes through the Vision Group
     * ([executeReadImageTool]).
     *
     * [T-android-vision-native-check-misses-image_input] These three each used
     * to inline `inputModalities.map { it.lowercase() }.contains("image")`,
     * which only LOWERCASES. Provider APIs disagree on the spelling: OpenAI /
     * OpenRouter report "image_input", models.dev reports bare "image" (see
     * [normalizeModalityName]). So a model advertising "image_input" was read
     * as vision-capable by [hasImageInput] — which normalizes, and is what
     * `ProviderRepository.resolveVisionCandidates` filters Vision Group members
     * by — but as text-only here. A model that could see perfectly well would
     * therefore get its `read_image` result detoured through the Vision Group
     * and come back as a second-hand text description, exactly the complaint in
     * the 2026-08-28 report (which turned out to have a different cause).
     *
     * Reusing [hasImageInput] keeps this check and the Vision Group's member
     * filter on one definition, so the two can no longer disagree.
     */
    private val currentModelHasNativeVision: Boolean
        get() = currentModel?.hasImageInput == true

    /**
     * [T-token-attribution-snapshot] Which model actually served the turn being
     * persisted, for the message's attribution columns.
     *
     * Built from `currentModel` + `_activeEntryId` — the live request context —
     * and deliberately NOT from the session row. Automatic failover rewrites
     * `sessions.model_id` mid-turn (see the fallback path that reassigns
     * `_activeEntryId` / `currentModel` when a candidate fails), so a session
     * read at persist time can name a model that never produced this message.
     * Both fields here are updated by that same fallback path, so they always
     * describe the model that actually responded.
     */
    private fun currentModelSnapshot(): com.openminis.app.data.model.ModelAttributionSnapshot? {
        val model = currentModel ?: return null
        val entry = _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }
        val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        return com.openminis.app.data.model.ModelAttributionSnapshot(
            modelId = model.id,
            displayName = model.displayName,
            // `.name` is the enum's stable rawValue, never the localized
            // displayName — grouping on display strings is what produced the
            // duplicate "Google" / "Gemini" / "Google Gemini" sections.
            providerTypeRaw = instance?.providerType?.name ?: "",
            providerInstanceId = entry?.providerInstanceId,
        )
    }

    /** Structured agent history for the agent loop (contentParts-based). */
    private val agentHistory = mutableListOf<LLMMessage>()

    /**
     * All agent tool definitions, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    private val agentTools: List<AgentToolDefinition>
        get() = AgentTools.makeAgentTools(
            // [T-android-vision-group / GH#182] The main model's own vision
            // capability. When false but a Vision Group is configured, read_image
            // is still exposed and routes through the group (see
            // executeReadImageTool). Note pre-vision-group Android always passed
            // the default `true` here, so read_image was already always exposed;
            // threading the real flag lets a text-only model without a Vision
            // Group correctly LOSE the tool (iOS parity), while a configured
            // Vision Group keeps it.
            supportsImageInput = currentModelHasNativeVision,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = _memoryEnabled.value,
        )

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    private val toolLoopDetector = ToolLoopDetector()

    /**
     * Cached reference to the lazily-created [BrowserTabPool] so
     * [ensureSession] can re-point it at the real session id after a rename.
     * Read only through [browserTabPool]; the backing `by lazy` fills this in.
     */
    @Volatile
    private var _browserTabPoolRef: BrowserTabPool? = null

    /** Browser tab pool for browser_use tool. Lazily created on first access. */
    val browserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(context).also {
            it.setSession(activeSessionId)
            // Surface download start/finish/failure as system-info notices in
            // this chat. May fire from the pool's IO scope — hop to Main since
            // appendSystemInfo does a read-modify-write on _messages.
            it.onDownloadEvent = { text ->
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    appendSystemInfo(text, "info")
                }
            }
            _browserTabPoolRef = it
        }
    }

    internal val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()

    // [T-android-split-chat] toggleBrowserSheet / dismissBrowserSheet /
    // openBrowserSheetForUrl moved to ChatViewModelUiStateExt.kt.

    internal val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    private val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    fun ackClearChatConfirmRequest() {
        _clearChatConfirmRequested.value = false
    }

    private val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    /**
     * Revoke a previously recorded memory_write by removing its entry from
     * today's or yesterday's daily log on disk, and dropping the row from
     * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
     *
     * Returns the repository result so the UI can show a success / not-found
     * / I/O error dialog. The original ChatMessage tool block stays in the
     * conversation history untouched — only the on-disk entry and the
     * op-log row are mutated.
     */
    fun revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val written = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.revokeEntry(written)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value - record
        }
        return result
    }

    /**
     * T149: revoke every `memory_write` tool block embedded in the supplied
     * messages. Used when a retry path truncates the conversation — the
     * deleted assistant turns may have written entries to today's daily
     * memory log, and leaving them on disk after the conversation rewinds
     * means user-visible history is gone but the side effects remain.
     *
     * We match by the `content` field of the tool args against
     * [MemoryToolRecord.writtenContent] (which is what `revokeMemoryRecord`
     * keys on). If multiple records share the same content body — possible
     * if the agent wrote the same note twice — we revoke them in the
     * reverse insertion order so the most recent disk write is removed
     * first; the repository's revokeEntry only removes the first match
     * each call, so subsequent records may end up NotFound on disk but
     * still get pulled from the in-memory record list.
     */
    private fun revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
        if (memoryRepository == null) return
        val deletedContents = mutableListOf<String>()
        for (msg in deletedMessages) {
            for (block in msg.toolBlocks) {
                if (block.kind != "tool_use") continue
                if (block.toolName != "memory_write") continue
                val content = try {
                    JSONObject(block.toolArgs).optString("content", "")
                } catch (_: Exception) { "" }
                if (content.isNotBlank()) deletedContents.add(content)
            }
        }
        if (deletedContents.isEmpty()) return
        Log.i(TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
        for (content in deletedContents.asReversed()) {
            // Find the latest matching record so revoke targets the most
            // recent disk entry first. Snapshot value because revoke mutates
            // the flow.
            val record = _memoryToolRecords.value.lastOrNull {
                it.isWrite && it.writtenContent == content
            } ?: continue
            val result = revokeMemoryRecord(record)
            Log.i(TAG, "  revoke result: ${result::class.simpleName}")
        }
    }

    /**
     * Replace the body of a previously recorded memory_write with
     * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
     * On success, also updates the in-memory [MemoryToolRecord] so a
     * subsequent revoke or revisit sees the new body.
     */
    fun replaceMemoryRecord(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = memoryRepository
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val old = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.replaceEntryBody(old, newContent)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value.map {
                if (it === record) it.copy(
                    writtenContent = newContent,
                    preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
                ) else it
            }
        }
        return result
    }

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────

    // [T-memory-global-toggle-settings-ui-android] Seed from the global
    // pref so a fresh draft VM honors the user's "memory off by default"
    // choice from Settings. For loaded sessions, `loadSession()` later
    // overwrites this with the per-session DB value, which takes
    // precedence — the global pref only applies to drafts.
    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * AnthropicProvider is stamped with `enhancedCache = true` just before the
     * request (see the streamMessage choke point).
     */
    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
    fun isEnhancedCacheConfirmed(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

    /**
     * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
     * dialog was accepted (records the durable acknowledgement) and flips the
     * in-memory toggle on.
     */
    fun confirmAndEnableEnhancedCache() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

    /**
     * [T-android-enhanced-cache] Toggle the switch when confirmation is not
     * required (turning it OFF, or turning it ON after the user already
     * acknowledged). The confirmation-gated first enable is handled in the UI.
     */
    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    internal val _fastModeEnabled =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.isEnabled())
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()

    fun setFastModeEnabled(enabled: Boolean) {
        com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
        _fastModeEnabled.value = enabled
    }

    /**
     * Auto-compact toggle state. APP-LEVEL and persisted
     * (AutoCompactPrefs / iOS UserDefaults "autoCompactOnThreshold").
     *
     * When on, crossing the compact threshold before a send compacts silently
     * and then sends; when off, the user is asked first. Mirrors iOS
     * `AIChatViewModel.autoCompactEnabled`.
     */
    internal val _autoCompactEnabled =
        MutableStateFlow(com.openminis.app.data.AutoCompactPrefs.isEnabled())
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    fun setAutoCompactEnabled(enabled: Boolean) {
        com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
        _autoCompactEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Whether the Fast Mode menu row (and, when enabled,
     * the nav ⚡ badge) is shown. Mirrors iOS activeModelSupportsFastMode
     * (838ba929): the active model id contains "gpt" (case-insensitive —
     * matches the official fast catalog gpt-5.6-sol/terra/luna, gpt-5.5,
     * gpt-5.4) AND the request travels the Responses path — the instance has
     * useResponsesAPI on (any credential/base; Responses relays like sub2api
     * pass the tier through) OR it's the Codex OAuth route (OpenAI type +
     * oauth credential + no custom base URL). Chat-completions providers stay
     * excluded. Recomputes on entry/config changes like the Enhanced Cache
     * gate above.
     *
     * [T-android-xai-priority] xAI is a second, independent branch: xAI's
     * Priority Processing is the same `service_tier: "priority"` wire field
     * and the same user-facing promise (lower latency, higher price), so it
     * reuses this one global toggle rather than adding a competing per-provider
     * switch. The "gpt" model-id test deliberately does NOT apply — xAI's
     * models are the grok family — and neither does the Responses-path test,
     * because xAI serves Priority Processing on its Chat Completions endpoint,
     * which is the path ProviderFactory always resolves xAI to.
     */
    val showFastModeToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
                instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            val isXAI = instance?.providerType == com.openminis.app.data.model.ProviderType.xAI
            entry != null && instance != null &&
                (
                    isXAI ||
                        (
                            entry.model.id.contains("gpt", ignoreCase = true) &&
                                (instance.useResponsesAPI || isCodexOAuth)
                            )
                    )
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */
    internal var savedInputBeforeSlash: String? = null

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans /var/minis/{workspace,attachments,shared,
     * skills,memory}/<sessionId>/ on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */
    val fileMentionIndex: FileMentionIndex by lazy {
        // T219: provide the SAF-mounted external folders so `@<mountName>`
        // resolves to /var/minis/mounts/<name>/... in the chat composer.
        // PRootKernel holds the MountedFoldersStore reference (set at app
        // launch by MinisApp); reading via a closure means the index sees
        // an up-to-date snapshot on every rescan without a manual refresh.
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = {
                com.openminis.app.sandbox.PRootKernel
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */
    internal val _mentionAnchor = MutableStateFlow(-1)

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    private val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    /**
     * [T-context-window-live-read] Effective context window for capacity
     * judgment (compaction warnings, tool-output offload, empty-response
     * heuristic, Token Usage sheet). Reads LIVE state on every call instead of
     * the `currentModel` snapshot, so editing the model's context window or
     * the bound group's `contextLimitTokens` takes effect on the very next
     * judgment without re-picking the model/group (mirrors iOS fcc22b66):
     *   1. the active entry's model is re-resolved from the current repository
     *      config (folds ModelOverrides live), falling back to the snapshot
     *      only when the entry can't be found (e.g. synced sessions before
     *      config finished loading);
     *   2. the result is clamped by the bound group's `contextLimitTokens`
     *      (null / <=0 = unlimited). Pre-fix that group field was write-only
     *      on Android — persisted by the group editor but never consulted at
     *      runtime.
     */
    private fun effectiveContextWindowTokens(): Int? {
        val config = providerRepository.config.value
        val liveModel = _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
        return if (groupLimit != null) minOf(window, groupLimit) else window
    }

    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
    )

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    /** Read-only view of the current thinking configuration for the model. */
    fun thinkingInfo(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning == true
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

    /**
     * Load session-level token aggregates from the database. Suspend so the
     * Token Usage sheet can fetch on demand without keeping a live subscription
     * — token data rarely changes mid-view, and we want to avoid reactive
     * overhead per token chunk.
     */
    suspend fun loadSessionTokenStats(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        for (json in usages) {
            try {
                val obj = org.json.JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                output += obj.optLong("outputTokens", 0L)
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
            } catch (_: Exception) { /* skip malformed row */ }
        }
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops)
    }

    // [T-android-split-chat] toggleMemorySheet / dismissMemorySheet moved to ChatViewModelUiStateExt.kt.

    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state. */
    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = Icons.Default.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = Icons.Default.Psychology,
            title = "Memory",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = Icons.Default.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
    )

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to ChatViewModelSlashExt.kt as ChatViewModel extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to ChatViewModelMentionExt.kt as ChatViewModel extension functions.

    /**
     * Execute a slash command. Returns the text the composer should hold
     * afterward (caret via [pendingCaret] when relevant).
     *
     * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
     * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
     * original text): a skill row prepends "/<skill> " to the original; an
     * action command (clear/compact/…) runs as a side effect and restores the
     * original (stripping the injected "/ "). Typed-"/" (no saved original):
     * a skill fills "/<skill> ", an action clears the input. The original body
     * is always preserved — never discarded (no regression of e48fe7a0).
     *
     * [currentInput] is retained for call-site compatibility; the body text is
     * sourced from [savedInputBeforeSlash], not the live string.
     */
    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
        val saved = savedInputBeforeSlash
        // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
        // they're a typing aid. Fill the composer with the literal slash
        // command; the user then taps Send and the model handles the skill via
        // the existing SKILL.md fragment injection in runAgentLoop.
        if (cmd.isSkill) {
            AppLogger.info(TAG, "[Slash] tap skill id=${cmd.id} title=${cmd.title} → composer fill only")
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            val prefix = "/${cmd.title} "
            // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
            // (saved != null) → PREPEND "/<skill> " to the original, so the
            // composer reads "/<skill> <original>" with the original as args,
            // caret right after the prefix (before the original). Typed-"/"
            // (saved == null) → just "/<skill> " (the input WAS the partial
            // command). Trailing space lets the user type "/<skill> <args>".
            return if (saved != null) {
                _pendingCaret.value = prefix.length
                prefix + saved
            } else {
                prefix
            }
        }
        AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1

        when (cmd.id) {
            "compact" -> compactAll()
            "memory" -> toggleMemoryEnabled()
            "thinking" -> toggleThinking()
            "clear" -> _clearChatConfirmRequested.value = true
            else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
        }
        // [T-android-slash-menu-align-ios-prepend] Action command: restore the
        // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
        // survives — never the live "/ <original>". Typed-"/" path → clear.
        if (saved != null) {
            _pendingCaret.value = saved.length
            return saved
        }
        return ""
    }

    /** Toggle memory writes on/off, persist to DB, and append a system-info message. */
    private fun toggleMemoryEnabled() {
        val newValue = !_memoryEnabled.value
        _memoryEnabled.value = newValue
        viewModelScope.launch {
            // Toggling before the first message means the row doesn't exist
            // yet — materialize the session row so the preference lands on
            // the persisted id instead of silently updating zero rows under
            // the draft key.
            val sid = ensureSession()
            chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
        }
        appendSystemInfo(
            text = "Memory writes ${if (newValue) "enabled" else "disabled"}. Reads are unaffected.",
            iconKind = "memory",
        )
    }

    /** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics). */
    private fun toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = "The current model does not support deep thinking.",
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = "Thinking set to ${newLevel.displayName.lowercase()}.",
            iconKind = "thinking",
        )
    }

    /**
     * Set thinking level explicitly. Used by the inline level picker in the
     * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
     * ignored when the current model doesn't support reasoning.
     */
    fun setThinkingLevel(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
        // already filters to availableThinkingLevels, but never fully trust the
        // caller — cap to the current model's ceiling so a stale/over-range
        // request can't persist a level the model can't reach.
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        if (_thinkingLevel.value == clamped) return
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

    /**
     * T239: write the user's explicit thinking-level choice back to the
     * sessions row so it survives cold-start. Stored as enum name; null
     * means "no override" (legacy behaviour). We always store a non-null
     * value here — including OFF — because the user's explicit "turn it
     * off for this session" must persist as distinct from "never set".
     *
     * Uses [ensureSession] so toggling on a draft (no DB row yet) first
     * materialises the row, mirroring how toggleMemoryEnabled lands its
     * preference on the persisted id rather than the `__new__…` draft key.
     */
    private fun persistThinkingOverride(level: ThinkingLevel) {
        viewModelScope.launch {
            val sid = ensureSession()
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * If `text` is a slash command literal (e.g. "/compact"), run it and
     * return true so the caller can skip the normal send path. Mirrors iOS
     * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
     * case-insensitively against [availableSlashCommands].
     *
     * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
     * Japanese IMEs auto-substitute the full-width form when the user types
     * `/` while a CJK keyboard layout is active. We treat them identically.
     */
    fun tryExecuteInputAsSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val name = trimmed.drop(1).lowercase()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
            ?: return false
        executeSlashCommand(cmd)
        return true
    }

    /**
     * Append a system-info block to the conversation. Not persisted — matches the
     * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
     * stream. Future work: wire real conversation compaction through the LLM.
     */
    private fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
            // this carries the full summary text so the UI can show an info-icon
            // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
            toolArgs = payload.orEmpty(),
        )
        _messages.value = _messages.value + ChatMessage(
            id = "sysinfo_${System.currentTimeMillis()}",
            role = "system",
            content = "",
            toolBlocks = listOf(block),
        )
    }

    /**
     * Fold the current session history into a single summary stored in
     * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
     *
     *   1. Build a compact conversation transcript (role + parts preview).
     *   2. Call the **current provider's non-streaming `sendMessage`** with a
     *      hardcoded summarization system prompt that emphasises preserving
     *      paths/commands/IDs/decisions/errors/open tasks.
     *   3. Persist a `CompactMarkerEntity` via the DAO; publish via
     *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
     *   4. agentHistory itself is NOT truncated — the audit trail stays.
     *
     * Concurrency: gated by [_isCompacting] so the slash command can't
     * overlap with an in-flight streaming turn (`_isStreaming`) or another
     * compact. Runs on [Dispatchers.IO].
     */
    /**
     * Public entrypoint used by the debug RPC (`chat.session.compact`) to
     * trigger compaction without going through the ChatScreen slash-command
     * UI path. Mirrors what [executeSlashCommand]("compact") does — just
     * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
     * back to false to know the run finished, and read [compactSummary] for
     * the resulting summary text.
     */
    fun runCompactNow() {
        compactAll()
    }

    /**
     * Public entrypoint for "compact up through this message" (mirrors iOS
     * AIChatViewModel.compactBefore). The chat list's long-press menu and
     * the debug RPC `chat.compact.before` route through here.
     *
     * @param dbMessageId the DB message id to use as the new marker's
     *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
     *   where anchorIdx is the agentHistory position of this id.
     * @param includesBoundary accepted for ABI compatibility with iOS, but
     *   in v2 the anchor IS the caller-supplied message regardless — the
     *   flag is logged and ignored. (iOS made the same simplification.)
     *
     * If the id can't be resolved to an agentHistory entry, this falls
     * back to compactAll() behaviour so the user's gesture isn't lost.
     */
    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }

    /**
     * [T-android-auto-compact-inloop] Compact the session.
     *
     * [allowDuringProcessing] lets the in-loop guard in [runAgentLoop] compact
     * BETWEEN agent iterations, where `_isStreaming` is legitimately true. All
     * user-initiated paths keep the default (false) so the "can't compact while
     * a turn is running" guard is unchanged for them. Re-entrancy is still
     * covered by [_isCompacting]. Mirrors iOS f70ac173.
     *
     * [onFinished] fires on the IO coroutine once the compaction attempt has
     * settled (success or failure), so the loop can await it before issuing the
     * next API call — the function itself is fire-and-forget.
     */
    /**
     * Public entry: guarantees [onFinished] is invoked exactly once even when a
     * precondition rejects the request before any work is launched. The inner
     * implementation has many early returns; wrapping it here is safer than
     * threading a callback through each one, and it means an in-loop caller can
     * never hang waiting for a callback that was skipped.
     */
    private fun compactAll(
        anchorIdxOverride: Int? = null,
        allowDuringProcessing: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        var started = false
        compactAllImpl(anchorIdxOverride, allowDuringProcessing, onFinished) { started = true }
        if (!started) onFinished?.invoke(false)
    }

    private inline fun compactAllImpl(
        anchorIdxOverride: Int?,
        allowDuringProcessing: Boolean,
        noinline onFinished: ((Boolean) -> Unit)?,
        markStarted: () -> Unit,
    ) {
        AppLogger.info(TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride inLoop=$allowDuringProcessing")
        if (_isStreaming.value && !allowDuringProcessing) {
            AppLogger.info(TAG, "[Compact] aborted: stream in progress")
            appendSystemInfo(
                text = "Cannot compact while a turn is in progress. Stop the current response first.",
                iconKind = "compact",
            )
            return
        }
        if (_isCompacting.value) {
            AppLogger.info(TAG, "[Compact] aborted: another compact already in flight")
            appendSystemInfo(
                text = "A compact is already in progress. Please wait for it to finish.",
                iconKind = "compact",
            )
            return
        }
        val provider = currentProvider ?: run {
            appendSystemInfo("No provider configured. Cannot compact.", "compact")
            return
        }
        val history = agentHistory.toList()
        if (history.isEmpty()) {
            appendSystemInfo("Nothing to compact — the session is empty.", "compact")
            return
        }
        // ─── v2 unified anchor model ───────────────────────────────────
        //
        // anchor = last active agentHistory entry. The compacted range is
        // `[prev marker anchor + 1, anchor]` (or `[0, anchor]` if no prev),
        // so each compact "extends" the latest summary forward to cover all
        // new turns. effectiveAgentHistory then re-injects the LAST N
        // user-text turns LEADING UP TO the anchor as fresh context, so the
        // model still sees recent verbatim content alongside the summary.
        //
        // Mirrors iOS post-Phase-v2: anchor = last active message, no
        // "auto-keep tail" baked into the compacted range — that's a
        // read-side decoration done by effectiveAgentHistory.
        //
        // anchor must be a persisted entry (have a non-null dbMessageId).
        // The strict iOS check also requires id ∈ rawMessages DB, but DAO
        // is suspend and we'd have to relocate range calculation into the
        // launch below. As a compromise we do the dbMessageId-non-empty
        // pre-check here (catches most stale-id cases at this stage), and
        // do the rawDbIds-membership check inside the launch before the
        // marker is written. Mirrors iOS AIChatViewModel+Compaction.swift:
        // 644-657 "walk back through agentHistory looking for dbMessageId
        // AND allRaw.contains" — split across two phases to honor suspend
        // boundaries.
        val anchorIdx: Int = if (anchorIdxOverride != null) {
            // compactBefore() supplied a specific anchor — walk back from
            // there to the closest entry with a dbMessageId (mirrors the
            // tail-walk-back logic but bounded to [0..override]).
            var i = anchorIdxOverride.coerceIn(0, history.lastIndex)
            while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
            i
        } else {
            // compactAll() — walk back from the tail to the closest
            // persisted entry. iOS compactAll calls compactBefore with the
            // last active UI message; we go through agentHistory directly
            // since Android's agentHistory and UI list are tighter-coupled.
            var i = history.lastIndex
            while (i >= 0 && history[i].dbMessageId.isNullOrEmpty()) i -= 1
            i
        }
        if (anchorIdx < 0) {
            appendSystemInfo("Cannot compact: no persisted messages yet.", "compact")
            return
        }

        // Slice to compact = (prev marker's anchor + 1) … anchorIdx inclusive.
        // For v2 prev markers, lastCompactedMessageId IS the prev anchor —
        // start at prevIdx + 1. For v1 prev markers, firstKeptMessageId points
        // at "first kept" — start AT prevIdx (it was exclusive on right edge).
        val prev = _cachedLatestMarker
        val effectiveStartIdx: Int = if (prev == null) {
            0
        } else {
            val prevAnchorOrFirstKept: String? = if (prev.version >= 2) {
                prev.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            } else {
                prev.firstKeptMessageId?.takeIf { it.isNotEmpty() }
                    ?: prev.boundaryMessageId?.takeIf { it.isNotEmpty() }
            }
            val prevIdx = prevAnchorOrFirstKept?.let { id ->
                history.indexOfFirst { it.dbMessageId == id }
            } ?: -1
            if (prevIdx < 0) 0   // prev anchor not in current history — restart from top
            else if (prev.version >= 2) prevIdx + 1
            else prevIdx
        }
        if (effectiveStartIdx > anchorIdx) {
            appendSystemInfo("Already compacted up to this point.", "compact")
            return
        }
        val toCompact = history.subList(effectiveStartIdx, anchorIdx + 1)
        if (toCompact.isEmpty()) {
            appendSystemInfo("Nothing to compact.", "compact")
            return
        }
        // Past every precondition — from here the launch below owns the
        // onFinished callback.
        markStarted()
        _isCompacting.value = true
        // [T-android-compact-runaway] Size the wall-clock budget off the actual
        // transcript, so a long first compaction is not cut off by a limit
        // tuned for a short one. Measured on the same truncated transcript the
        // request will carry, not the raw history.
        val transcriptChars = buildConversationTextForSummary(toCompact).length
        val timeoutMs = compactTimeoutMsFor(transcriptChars)
        compactCallsIssued.set(0)
        _compactProgress.value = CompactProgress(
            startedAtMs = System.currentTimeMillis(),
            depth = 0,
            callsIssued = 0,
            callBudget = MAX_COMPACT_LLM_CALLS,
            timeoutSeconds = (timeoutMs / 1000L).toInt(),
        )
        AppLogger.info(
            TAG,
            "[Compact] starting: ${toCompact.size} entries, ${transcriptChars} transcript chars, " +
                "timeout=${timeoutMs / 1000}s, callBudget=$MAX_COMPACT_LLM_CALLS",
        )
        compactJob = viewModelScope.launch(Dispatchers.IO) {
            // [T-android-compact-queued-drain] Only a SUCCESSFUL compact kicks
            // the queued-prompt drain below; failure/cancel/empty-summary paths
            // keep today's behavior (queued bubbles stay pending + cancellable).
            var compactSucceeded = false
            // Distinguishes "we gave up on time" from other failures so the
            // user-facing message can say so and invite a retry.
            var timedOut = false
            try {
                val existing = _compactSummary.value
                // Mirrors iOS `generateCompactSummaryWithSplitting` — when the
                // joined transcript exceeds the model's context window, halve
                // the message list and summarize each half independently, then
                // merge. depth cap=3 prevents pathological recursion.
                //
                // [T-android-compact-runaway] withTimeout bounds the WHOLE run,
                // including every split segment. Without it the only ceiling was
                // the provider's 10-minute readTimeout multiplied by however
                // many sequential segments the split produced.
                val summary = withTimeout(timeoutMs) {
                    generateCompactSummaryWithSplitting(
                        messages = toCompact,
                        previousSummary = existing,
                        depth = 0,
                    )
                }.trim()
                if (summary.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendSystemInfo("Compaction produced no output — try again later.", "compact")
                    }
                    return@launch
                }

                val sid = realSessionId.ifEmpty { sessionId }
                // v2 marker: lastCompactedMessageId IS the anchor — single
                // source of truth. The anchor we resolved above is guaranteed
                // to have a persisted dbMessageId. Legacy fields (firstKept /
                // boundary / sortOrder) stay null/MAX so a downgraded reader
                // sees "everything compacted, nothing kept" as a graceful
                // fallback rather than a stale boundary.
                // Re-resolve anchor: now that we're inside an IO coroutine
                // we can read the messages DB to verify the dbMessageId is
                // actually persisted, not just set on the in-memory
                // LLMMessage. iOS does this belt-and-suspenders check
                // (AIChatViewModel+Compaction.swift:644-657). Walk back from
                // the original anchorIdx until we find an entry whose id is
                // both non-empty AND present in rawDbIds.
                val rawDbIds: Set<String> = try {
                    chatRepository.dao.loadMessages(sid).map { it.id }.toSet()
                } catch (e: Exception) {
                    Log.w(TAG, "[Compact] loadMessages for raw-id verify failed: ${e.message}")
                    emptySet()
                }
                val verifiedAnchorIdx: Int = if (rawDbIds.isEmpty()) {
                    // DB read failed; trust the in-memory walk-back result.
                    anchorIdx
                } else {
                    var i = anchorIdx
                    while (i >= 0) {
                        val id = history[i].dbMessageId
                        if (!id.isNullOrEmpty() && id in rawDbIds) break
                        i -= 1
                    }
                    i
                }
                if (verifiedAnchorIdx < 0) {
                    Log.w(TAG, "[Compact] No agentHistory entry has a DB-persisted dbMessageId; aborting")
                    withContext(Dispatchers.Main) {
                        appendSystemInfo("Compact failed: could not anchor to a persisted message.", "compact")
                    }
                    return@launch
                }
                if (verifiedAnchorIdx != anchorIdx) {
                    AppLogger.warning(
                        TAG,
                        "[Compact] anchor walked back from idx=$anchorIdx to idx=$verifiedAnchorIdx " +
                            "(closest with id in rawDbIds). Unsynced tail entries will fall on the active side of the divider.",
                    )
                }
                val lastCompactedDbId = history[verifiedAnchorIdx].dbMessageId
                    ?: run {
                        Log.w(TAG, "[Compact] verified anchor at idx=$verifiedAnchorIdx lost dbMessageId; aborting")
                        withContext(Dispatchers.Main) {
                            appendSystemInfo("Compact failed: anchor message id unavailable.", "compact")
                        }
                        return@launch
                    }
                val marker = CompactMarkerEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = sid,
                    summary = summary,
                    firstKeptSortOrder = Int.MAX_VALUE,   // legacy field; v2 ignores
                    compactedCount = toCompact.size,
                    createdAt = System.currentTimeMillis(),
                    uiBoundarySortOrder = null,
                    boundaryMessageId = null,
                    firstKeptMessageId = null,
                    lastCompactedMessageId = lastCompactedDbId,
                    version = 2,
                )
                runCatching { chatRepository.dao.insertCompactMarker(marker) }
                    .onFailure {
                        Log.w(TAG, "Failed to persist compact marker: ${it.message}")
                    }
                _compactSummary.value = summary
                // Keep the marker in memory so effectiveAgentHistory() can
                // resolve the boundary on the very next outgoing turn.
                // Mirrors iOS `cachedLatestMarker = marker`.
                _cachedLatestMarker = marker
                withContext(Dispatchers.Main) {
                    // Gray out everything in the compacted range; the kept
                    // tail (last N user turns + tool/assistant follow-ups)
                    // stays full opacity. Determined by walking _messages
                    // until we pass the row whose id == lastCompactedDbId.
                    //
                    // Also drop any prior compact-divider system rows — a
                    // session shows at most one divider (the latest marker).
                    // Those old dividers are stored as system messages with
                    // a "compact" iconKind in toolBlocks[0].toolName.
                    val cutoffId: String = lastCompactedDbId
                    var passedCutoff = false   // anchor is guaranteed non-null in v2
                    val cleaned = _messages.value
                        .filterNot { msg ->
                            // Drop prior compact-divider rows; appendSystemInfo
                            // below will re-add the new one.
                            msg.role == "system" &&
                                msg.toolBlocks.firstOrNull()?.toolName == "compact"
                        }
                        .map { msg ->
                            if (msg.role == "system") msg
                            else if (passedCutoff) msg
                            else {
                                val grayed = if (msg.isCompactedHistory) msg
                                    else msg.copy(isCompactedHistory = true)
                                if (msg.id == cutoffId) passedCutoff = true
                                grayed
                            }
                        }
                    // T84: count UI bubbles in this pass's compacted range.
                    // Filters: role != system (dividers/notices don't count).
                    // Range: everything up to and including the cutoff row,
                    // since the kept-tail starts immediately after.
                    // Falls back to "all non-system" when cutoffId is null
                    // (compact-everything path), matching iOS dividerInsertIdx
                    // == messages.count behavior.
                    //
                    // We deliberately do NOT exclude `isCompactedHistory` rows.
                    // Back-to-back compacts (or compact after restoring a prior
                    // marker on session reload) leave the in-range rows already
                    // grayed; excluding them produced "0 messages compacted"
                    // even though `toCompact.size` was nonzero. The divider's
                    // count should reflect the size of THIS pass's range, not
                    // the delta of newly-grayed rows.
                    val cutoffIdx = cleaned.indexOfLast { it.id == cutoffId }
                    val compactedUICount = if (cutoffIdx < 0) {
                        cleaned.count { it.role != "system" }
                    } else {
                        cleaned.take(cutoffIdx + 1).count { it.role != "system" }
                    }
                    _messages.value = cleaned
                    AppLogger.info(TAG, "[Compact] divider: $compactedUICount UI bubbles compacted (history entries: ${toCompact.size})")
                    appendSystemInfo(
                        text = "$compactedUICount messages compacted",
                        iconKind = "compact",
                        payload = summary,
                    )
                }
                compactSucceeded = true
            } catch (e: TimeoutCancellationException) {
                // [T-android-compact-runaway] MUST precede the CancellationException
                // arm — TimeoutCancellationException extends it, so the generic
                // re-throw would otherwise swallow our own timeout and surface it
                // as a silent cancel with no message.
                timedOut = true
                val elapsed = (timeoutMs / 1000L).toInt()
                val calls = compactCallsIssued.get()
                Log.w(TAG, "[Compact] timed out after ${elapsed}s ($calls model call(s) issued)")
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        text = "Compaction timed out after ${elapsed}s " +
                            "($calls model call(s) attempted). The model may be slow or " +
                            "rate-limited — you can try compacting again.",
                        iconKind = "compact",
                    )
                }
            } catch (e: CancellationException) {
                // User-initiated (cancelCompact) or scope teardown. Tell the
                // user only if they are still around to read it; the `finally`
                // below releases the lock either way.
                if (compactJob?.isCancelled == true) {
                    runCatching {
                        withContext(NonCancellable + Dispatchers.Main) {
                            appendSystemInfo("Compaction cancelled.", "compact")
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Compact failed", e)
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        text = "Compaction failed: ${e.message ?: e.javaClass.simpleName}",
                        iconKind = "compact",
                    )
                }
            } finally {
                _isCompacting.value = false
                _compactProgress.value = null
                AppLogger.info(
                    TAG,
                    "[Compact] finished: success=$compactSucceeded timedOut=$timedOut " +
                        "calls=${compactCallsIssued.get()}",
                )
                // [T-android-auto-compact-inloop] Signal the awaiting in-loop
                // caller. In `finally` so a thrown/cancelled compaction can
                // never strand the agent loop waiting on a callback.
                onFinished?.invoke(compactSucceeded)
            }
            // [T-android-compact-queued-drain] A successful compact must let
            // any queued prompts proceed — previously nothing re-triggered the
            // drain after compact (loop-end / cancel / tool-boundary are the
            // only drain triggers), so a prompt sitting in the queue when a
            // compact ran stayed in the dashed "queued" state forever. Reuse
            // resumeQueueAfterCancel: it re-checks queue-non-empty + not-
            // streaming + not-compacting after its grace delay (so an ✕ tap at
            // the compact-finish instant is a clean no-op), refreshes OAuth,
            // and drains through the normal stream-slot machinery — no new
            // reentrancy path. Runs after `finally` so isCompacting is already
            // false. Mirrors the iOS fix for the same report.
            if (compactSucceeded && _promptQueue.value.isNotEmpty()) {
                AppLogger.info(TAG, "[Compact] success with ${_promptQueue.value.size} queued prompt(s) — kicking drain")
                resumeQueueAfterCancel()
            }
        }
    }

    /**
     * Revert the most recent compact on this session.
     *
     * Drops the latest CompactMarker (its summary is discarded), refreshes
     * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
     * null), and rebuilds the message list so the UI reflects the new (or
     * absent) divider. Effect by design:
     *   - If a previous (older) marker exists, divider snaps back to that
     *     marker's anchor; effectiveAgentHistory replays that summary.
     *   - If no previous marker exists, divider disappears, full history
     *     flows to the model again.
     *
     * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
     */
    fun revertCompact() {
        if (_isStreaming.value) {
            appendSystemInfo("Cannot revert compact while a response is in progress.", "compact")
            return
        }
        if (_isCompacting.value) {
            appendSystemInfo("Cannot revert compact while compaction is in progress.", "compact")
            return
        }
        val current = _cachedLatestMarker ?: run {
            appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
            return
        }
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
            val removed = runCatching { chatRepository.dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0
            if (removed <= 0) {
                Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Revert failed: marker not found in DB.", "compact")
                }
                return@launch
            }

            // Refresh cache to next-most-recent marker (or null).
            val next = chatRepository.dao.latestCompactMarker(sid)
            _cachedLatestMarker = next
            _compactSummary.value = next?.summary

            // Rebuild UI from DB so the previous marker's divider re-emerges
            // (or all dividers vanish if there are no remaining markers).
            // Drop any stale compact-divider system rows first; the reload
            // path will re-insert one only if the new latest marker calls
            // for it.
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filterNot { msg ->
                    msg.role == "system" &&
                        msg.toolBlocks.firstOrNull()?.toolName == "compact"
                }
            }

            // Reload session messages — the existing path runs Phase 2.5
            // graying via applyCompactMarkerGraying() with the new cached
            // marker, so divider position falls back to the previous one
            // (or disappears entirely). loadSession() launches its own
            // viewModelScope job, so call from the Main thread.
            withContext(Dispatchers.Main) {
                reloadSessionFromDb()
            }

            if (next != null) {
                AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
            } else {
                AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
            }
        }
    }

    /**
     * Re-load the current session's UI message list from disk so any
     * cached-marker change (revert) gets re-applied through Phase-2.5-
     * style restore. Defers to the existing [loadSession] entry; that
     * function reads `_cachedLatestMarker` we just refreshed and routes
     * through [applyCompactMarkerGraying] to (re)position the divider.
     */
    private fun reloadSessionFromDb() {
        if (realSessionId.isEmpty() && sessionId.isEmpty()) return
        loadSession()
    }

    /**
     * Produce the LLM-facing view of agentHistory. Mirrors iOS
     * `effectiveAgentHistory` (AIChatViewModel.swift:3843-3876):
     *
     *   1) No marker / no summary → full agentHistory (zero-copy).
     *   2) Marker has a `firstKeptMessageId` (compactBefore at boundary) →
     *      `[summary] + agentHistory[boundaryIdx ...]`. The boundary message
     *      itself is the first kept entry.
     *   3) compactAll marker (`firstKeptMessageId = null`) → only summary +
     *      messages persisted AFTER the marker, located by
     *      `lastCompactedMessageId`. Messages inserted post-compact (the
     *      user's follow-up turn + the assistant's response) survive; the
     *      summary stands in for everything older.
     *   4) Marker present but no boundary resolvable in current history (e.g.
     *      the boundary message was deleted) → fall through to full history,
     *      same safety net iOS uses.
     *
     * Critically, we do NOT include `agentHistory[< boundaryIdx]` for case
     * (2/3) — that's how the model context stays clean after compact.
     * Earlier behaviour was [summary] + entire agentHistory, which both
     * over-stuffed the context AND duplicated tool_use/tool_result pairs the
     * marker had already replaced; that's what made follow-up turns appear
     * to lose continuity (the model got confused by the dual representation).
     */
    /**
     * Apply the request-level image-byte budget to a fully-resolved
     * message list before handing it to a provider. Images that don't
     * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
     * replaced in-place with a text placeholder that, when the original
     * bytes were offloaded to disk, points the model back to the linux
     * path so it can re-fetch via `read_image` if needed. Images that
     * never had a linuxPath are spilled to
     * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
     * still carries an addressable reference.
     *
     * Returns the budgeted message list. When nothing was elided this
     * is the same instance as [messages].
     *
     * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
     * user knows older images were compacted into placeholders.
     */
    private fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
        // Collect every image in chronological order so the planner can
        // walk in reverse and protect the most recent images.
        data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
        val images = mutableListOf<ImageRef>()
        messages.forEachIndexed { mi, msg ->
            msg.contentParts.forEachIndexed { pi, part ->
                when (part) {
                    is AgentContentPart.ImageData -> {
                        images.add(
                            ImageRef(
                                mi, pi,
                                ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType),
                            )
                        )
                    }
                    is AgentContentPart.ToolResult -> {
                        val img = part.imageData
                        if (img != null) {
                            images.add(
                                ImageRef(
                                    mi, pi,
                                    ImageBudget.BudgetImage(
                                        img,
                                        part.imageLinuxPath,
                                        part.imageMimeType ?: "image/jpeg",
                                    ),
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (images.isEmpty()) return messages

        val plan = ImageBudget.planRequestBudget(images.map { it.image })
        if (!plan.mutated) return messages

        // For dropped images without a linuxPath, lazily spill to disk so
        // the placeholder still gives the model an addressable reference.
        val attachmentsRoot = activeSessionId?.let { sid ->
            java.io.File(context.filesDir, "minis-sessions/$sid/attachments")
        }
        val resolvedPaths = HashMap<ImageBudget.ImagePartId, String?>()
        for (ref in images) {
            val id = ImageBudget.ImagePartId.of(ref.image.data)
            if (id !in plan.droppedIds) continue
            val existing = ref.image.linuxPath
            if (existing != null) {
                resolvedPaths[id] = existing
            } else if (attachmentsRoot != null) {
                resolvedPaths[id] = ImageBudget.ensureSpillover(
                    attachmentsRoot, ref.image.data, ref.image.mimeType,
                )
            } else {
                resolvedPaths[id] = null
            }
        }

        // Build a new message list with dropped image parts replaced by
        // text placeholders. Same-message multiple drops collapse cleanly
        // because we never touch parts whose ids weren't in droppedIds.
        val byMsg = images.groupBy { it.msgIdx }
        val mutated = messages.toMutableList()
        for ((mi, refs) in byMsg) {
            val msg = mutated[mi]
            val newParts = msg.contentParts.toMutableList()
            for (ref in refs) {
                val id = ImageBudget.ImagePartId.of(ref.image.data)
                if (id !in plan.droppedIds) continue
                val path = resolvedPaths[id]
                val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
                val originalPart = newParts[ref.partIdx]
                newParts[ref.partIdx] = when (originalPart) {
                    is AgentContentPart.ImageData -> placeholder
                    is AgentContentPart.ToolResult -> originalPart.copy(
                        // Strip the bytes but keep the structural ToolResult
                        // role; append the elision marker into content so
                        // the model sees it next to the rest of the tool
                        // output. linux path remains in the part for any
                        // subsequent diagnostic round-trip.
                        imageData = null,
                        imageMimeType = null,
                        content = originalPart.content +
                            (if (originalPart.content.isEmpty()) "" else "\n") +
                            ImageBudget.elidedImagePlaceholder(path),
                    )
                    else -> originalPart
                }
            }
            mutated[mi] = msg.copy(contentParts = newParts)
        }

        _requestBudgetEvent.tryEmit(plan)
        AppLogger.info(
            TAG,
            "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
        )
        return mutated
    }

    /**
     * [T-android-compact-orphan-toolcall] The outgoing history, with tool
     * call/result pairing repaired. Every request goes through here — see
     * [dropOrphanedToolParts] for why the sweep exists and what it can and
     * cannot fix.
     */
    private fun effectiveAgentHistory(): List<LLMMessage> =
        dropOrphanedToolParts(effectiveAgentHistoryUncounted())

    private fun effectiveAgentHistoryUncounted(): List<LLMMessage> {
        val summary = _compactSummary.value
        val marker = _cachedLatestMarker
        // No compact in play → return full history untouched.
        if (summary.isNullOrBlank() || marker == null) return agentHistory.toList()

        val summaryWrappedText = "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

        // ─── v2 markers (id-only anchor model) ─────────────────────────
        //
        // anchor = lastCompactedMessageId. What we send to the model:
        //   1. last [COMPACT_KEEP_RECENT_USER_TURNS] user-text turns BEFORE
        //      anchor (inclusive of anchor) — recent verbatim warm-up
        //   2. the summary, INLINED as a `<context-summary>` text part
        //      prepended to the first user message AFTER anchor (preserves
        //      strict role alternation — no synthetic standalone user turn)
        //   3. all messages strictly after anchor (the kept-tail "active"
        //      region — typically empty right after compact, populated as
        //      the user sends new prompts)
        //
        // If anchor unresolvable, degrade to full history (over-inform
        // beats summary-only; the M-Team session bug taught us that a lone
        // summary message paired with hot tools makes the model loop).
        if (marker.version >= 2) {
            val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val anchorIdx = anchorId?.let { id ->
                agentHistory.indexOfLast { it.dbMessageId == id }
            } ?: -1
            if (anchorIdx < 0) {
                Log.w(TAG, "[Compact] effectiveAgentHistory v2: anchorId=${anchorId?.take(8) ?: "nil"} not in agentHistory(size=${agentHistory.size}) — degrading to full history (no summary)")
                return agentHistory.toList()
            }

            // Step 1: walk back from anchor collecting user-text turns. Stop
            // when EITHER we've collected N user-text turns OR including the
            // next turn would push preAnchor over 100 messages. Decisions
            // happen only at user-message boundaries so we never split a
            // user/assistant/tool round in half (which would orphan a
            // tool_use with no matching tool_result).
            //
            // [T-compact-preanchor-prune, port iOS 8b76cd74]
            val keepN = COMPACT_KEEP_RECENT_USER_TURNS
            val preAnchorCap = 100
            val walkBack = walkBackUserTurnsBounded(
                anchorIdx = anchorIdx,
                maxUserTextTurns = keepN,
                maxMessages = preAnchorCap,
            )
            val priorIdxResolved: Int? = walkBack.priorIdx
            val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
            if (walkBack.stopReason != "userTextTargetMet") {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
            }

            // PRE-ANCHOR PRUNE (tool-heavy session fix):
            // The walk-back-N-user-text strategy pulls in everything between
            // the Nth-last and last user-text turn — in a heavy tool-call
            // session that can be many messages of tool_result / tool_use,
            // tens of thousands of tokens that the summary already covers.
            // Drop any tool_result > 1000 chars in the preAnchor slice and
            // strip the matching tool_use part (same id) from the assistant
            // message so the model never sees a dangling tool_use/result.
            val preAnchorRaw: List<LLMMessage> =
                if (priorIdx <= anchorIdx) agentHistory.subList(priorIdx, anchorIdx + 1).toList()
                else emptyList()

            val droppedToolIds = mutableSetOf<String>()
            var droppedToolResultCount = 0
            for (msg in preAnchorRaw) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                        droppedToolIds.add(part.id)
                        droppedToolResultCount += 1
                    }
                }
            }

            val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
            for (msg in preAnchorRaw) {
                if (msg.contentParts.isEmpty()) {
                    // Plain text-only message — nothing to prune.
                    preAnchorPruned.add(msg)
                    continue
                }
                val kept = msg.contentParts.filter { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                        is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                        else -> true
                    }
                }
                if (kept.isEmpty()) continue // skip empty shells
                preAnchorPruned.add(msg.copy(contentParts = kept))
            }

            if (droppedToolResultCount > 0) {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
            }

            // ROLE ALIGNMENT: the API requires the first message to be `user`.
            // After clamp (cap may land on assistant) and after prune (the
            // head user may have been emptied), peel any leading non-user
            // messages so preAnchor starts on a user turn.
            while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
                preAnchorPruned.removeAt(0)
            }

            // Step 2 & 3: copy the lookback window (post-prune), then splice
            // in the summary as parts[0] of the first post-anchor user msg.
            val result = mutableListOf<LLMMessage>()
            result.addAll(preAnchorPruned)

            val postAnchor = if (anchorIdx + 1 < agentHistory.size) {
                agentHistory.subList(anchorIdx + 1, agentHistory.size)
            } else {
                emptyList()
            }

            // DIAG: explain how the slice was sized using post-prune /
            // post-alignment counts so the log reflects what actually
            // reaches the model.
            val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
            val priorIdxSource =
                if (priorIdxResolved == null) "fallback=empty(<$keepN user-text turns before anchor or cap hit)"
                else "userTextWalkBack(N=$keepN)"
            AppLogger.info(TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx agentHistory.size=${agentHistory.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

            val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
            if (firstUserOffset >= 0) {
                if (firstUserOffset > 0) {
                    result.addAll(postAnchor.subList(0, firstUserOffset))
                }
                val target = postAnchor[firstUserOffset]
                // Prepend `<context-summary>...` to the user content. We
                // edit `content` directly because Android LLMMessage uses
                // `content: String` as the canonical text payload; any
                // contentParts the message also carries get preserved.
                val injected = target.copy(
                    content = summaryWrappedText + "\n\n" + target.content,
                )
                result.add(injected)
                if (firstUserOffset + 1 < postAnchor.size) {
                    result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
                }
            } else {
                // Rare: no user message after anchor. Append everything
                // post-anchor (typically empty) then a standalone summary
                // user turn. Safe — no later user follows it to break
                // alternation.
                result.addAll(postAnchor)
                result.add(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
            }
            return result
        }

        // ─── v1 (legacy) markers ──────────────────────────────────────
        //
        // Original behavior preserved unchanged so old markers keep
        // rendering / sending data the same way they always did.
        val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

        if (firstKeptId != null) {
            val keepStart = agentHistory.indexOfFirst { it.dbMessageId == firstKeptId }
            if (keepStart >= 0) {
                return buildList(agentHistory.size - keepStart + 1) {
                    add(summaryHead)
                    addAll(agentHistory.subList(keepStart, agentHistory.size))
                }
            }
            // Fall through to safety net.
        } else {
            val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val lcmIdx = lcmId?.let { id ->
                agentHistory.indexOfLast { it.dbMessageId == id }
            } ?: -1
            val postCompactStart = lcmIdx + 1
            return buildList(agentHistory.size - postCompactStart + 1) {
                add(summaryHead)
                if (postCompactStart < agentHistory.size) {
                    addAll(agentHistory.subList(postCompactStart, agentHistory.size))
                }
            }
        }

        Log.w(TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in agentHistory (size=${agentHistory.size}); returning full history")
        return agentHistory.toList()
    }

    /**
     * [T-android-compact-orphan-toolcall] Last line of defence before a history
     * slice becomes a provider request: every ToolResult (function_call_output)
     * must have a matching ToolUse (function_call) in the same slice, and vice
     * versa.
     *
     * An unmatched pair is a hard 400 on OpenAI-compatible APIs —
     *     No tool call found for function call output with call_id …
     * — and because the slice is recomputed deterministically, it repeats on
     * every retry AND every fallback model, wedging the conversation until the
     * user clears the session. Port of iOS `dropOrphanedToolParts`
     * (AIChatViewModel+Persistence.swift, c7f6a299e).
     *
     * Any orphan reaching here is an upstream bug (the walk-back boundary is
     * supposed to preserve pairing), so this logs loudly rather than silently
     * papering over it:
     *   - orphaned result → drop it; its call is gone from the slice and
     *     nothing can reconstruct it.
     *   - orphaned call → synthesise an error result rather than deleting the
     *     call, because deleting would silently discard the assistant's own
     *     reasoning. The placeholder keeps the turn intact and tells the model
     *     that round failed.
     * Messages emptied by the drop are removed — a parts-less message is itself
     * invalid on several providers.
     */
    private fun dropOrphanedToolParts(history: List<LLMMessage>): List<LLMMessage> {
        val toolUseIds = HashSet<String>()
        val toolResultIds = HashSet<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> toolUseIds.add(part.id)
                    is AgentContentPart.ToolResult -> toolResultIds.add(part.id)
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        // [T-android-compact-orphan-toolcall] IN-FLIGHT EXEMPTION (iOS
        // 5d346dc2e). The tool_uses in the FINAL assistant message are not
        // orphans while the loop sits between "model asked for tools" and
        // "results appended" — agentHistory legitimately looks unpaired for
        // that whole window (the assistant turn is appended at ~7500 and its
        // tool results only at ~7517). Any snapshot taken inside that gap would
        // otherwise carry fabricated "interrupted" results for tools that were
        // about to run normally, telling the model its tools had failed.
        // Trailing unanswered calls need no repair anyway: a request ending on
        // an assistant tool_use is exactly what the API expects mid-round.
        val last = history.lastOrNull()
        if (last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(part.id)
            }
        }

        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

        AppLogger.warning(
            TAG,
            "[CompactDiag] orphan tool parts in OUTGOING history — repairing. " +
                "orphanedOutputs=${orphanedResults.size} [${orphanedResults.sorted().take(3).joinToString(",")}] " +
                "orphanedCalls=${orphanedUses.size} [${orphanedUses.sorted().take(3).joinToString(",")}] " +
                "historyCount=${history.size}",
        )

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(part.id) else true
            }
            // Only drop the message when it HAD parts and lost them all. A
            // plain text message legitimately carries no contentParts and must
            // survive untouched.
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            // Follow an assistant turn holding orphaned calls with the
            // placeholder results it never got, so the pair is complete.
            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(it.id) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = "Tool execution was interrupted by an unexpected error.",
                                isError = true,
                            )
                        },
                    ),
                )
            }
        }
        return cleaned
    }

    /** Latest in-memory compact marker, used by [effectiveAgentHistory] to
     * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
     * on every compactAll write and on session reload. */
    @Volatile
    private var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    /**
     * Result of a bounded walk-back. `priorIdx` is the agentHistory index
     * the caller should use as the start of preAnchor; `null` means even
     * the first user turn including anchor would exceed `maxMessages`, so
     * preAnchor should be empty.
     *
     * Mirrors iOS `WalkBackResult` in AIChatViewModel.swift (8b76cd74).
     */
    private data class WalkBackResult(
        val priorIdx: Int?,
        val userTextTurnsFound: Int,
        val messageCount: Int,
        /** "userTextTargetMet" | "messageCapWouldExceed" | "reachedStart" | "invalidAnchor" */
        val stopReason: String,
    )

    /**
     * Walk back from `anchorIdx` toward 0, deciding ONLY at user-message
     * boundaries whether to include the next round. Stops when:
     * - we've collected `maxUserTextTurns` user-text turns (success), OR
     * - including the next user round would push total messages over
     *   `maxMessages` (cap reason — don't split a user/assistant/tool round
     *   in the middle, otherwise a tool_use would be orphaned without its
     *   tool_result), OR
     * - we hit index 0 (start of history).
     *
     * Port of iOS `walkBackUserTurnsBounded` (AIChatViewModel.swift, 8b76cd74).
     */
    private fun walkBackUserTurnsBounded(
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): WalkBackResult {
        if (anchorIdx < 0 || anchorIdx >= agentHistory.size) {
            return WalkBackResult(null, 0, 0, "invalidAnchor")
        }
        var acceptedPriorIdx: Int? = null
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0

        var i = anchorIdx
        while (i >= 0) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.USER) {
                i -= 1
                continue
            }
            // [T-android-compact-orphan-toolcall] A user message CARRYING a
            // tool result is the second half of a round, not the start of one.
            // This walk-back's whole premise is that `role == USER` marks a
            // round boundary — but tool results are themselves persisted as
            // USER messages (see the agentHistory.add at the end of the tool
            // dispatch loop), so stopping on one cuts between an assistant's
            // tool_use and its own tool_result. The call is then discarded with
            // pre-history while the result survives in preAnchor and goes out
            // alone, which every OpenAI-compatible provider answers with
            //     400 No tool call found for function call output with call_id …
            // and, since the slice is recomputed identically on every retry and
            // fallback, the session wedges permanently. Port of iOS c7f6a299e.
            if (msg.contentParts.any { it is AgentContentPart.ToolResult }) {
                i -= 1
                continue
            }
            val candidateMessageCount = anchorIdx - i + 1
            if (candidateMessageCount > maxMessages) {
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = "messageCapWouldExceed",
                )
            }
            // Accept this user as the new tentative priorIdx.
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            val hasText = msg.content.isNotBlank() ||
                msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
            if (hasText) {
                acceptedUserTextTurns += 1
                if (acceptedUserTextTurns >= maxUserTextTurns) {
                    return WalkBackResult(
                        priorIdx = acceptedPriorIdx,
                        userTextTurnsFound = acceptedUserTextTurns,
                        messageCount = acceptedMessageCount,
                        stopReason = "userTextTargetMet",
                    )
                }
            }
            i -= 1
        }
        return WalkBackResult(
            priorIdx = acceptedPriorIdx,
            userTextTurnsFound = acceptedUserTextTurns,
            messageCount = acceptedMessageCount,
            stopReason = "reachedStart",
        )
    }

    /**
     * Format the agent history as a plain-text transcript for the
     * summarisation LLM. Keeps role prefixes and truncates long tool arg /
     * output bodies so we stay well under any context window. Mirrors iOS
     * `buildConversationTextForSummary`.
     */
    private fun buildConversationTextForSummary(history: List<LLMMessage>): String = buildString {
        for (msg in history) {
            val role = msg.role.name.lowercase()
            val text = msg.content.take(500)
            if (text.isNotEmpty()) {
                append(role).append(": ").append(text).append('\n')
            }
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> {
                        append(role).append(": ").append(part.text.take(500)).append('\n')
                    }
                    is AgentContentPart.ToolUse -> {
                        val preview = part.input.toString().take(200)
                        append(role).append(" [tool:").append(part.name).append("]: ")
                            .append(preview).append('\n')
                    }
                    is AgentContentPart.ToolResult -> {
                        append(role).append(" [result:").append(part.name).append("]: ")
                            .append(part.content.take(500)).append('\n')
                    }
                    is AgentContentPart.ImageData -> {
                        append(role).append(" [image: ").append(part.mimeType).append("]\n")
                    }
                }
            }
        }
    }

    /**
     * Summarize [messages], recursively halving when a whole-input attempt
     * fails. Mirrors iOS `generateCompactSummaryWithSplitting`.
     *
     * Depth cap = 3 (matches iOS) so a pathologically large conversation
     * still terminates instead of fanning out indefinitely. At each split we
     * halve by message count, summarize each half independently, then
     * concatenate the partial summaries oldest-first. The concatenation is a
     * plain string join, NOT a further LLM call — see the comment at the join
     * for why the extra round-trip was removed. Both platforms must keep this
     * the same, or the summary a session carries differs by device.
     */
    private suspend fun generateCompactSummaryWithSplitting(
        messages: List<LLMMessage>,
        previousSummary: String? = null,
        depth: Int = 0,
    ): String {
        val transcript = buildConversationTextForSummary(messages)
        val conversationText = if (previousSummary.isNullOrBlank()) {
            transcript
        } else {
            "Previous context summary:\n$previousSummary\n\n" +
                "New conversation to merge:\n$transcript"
        }
        // [T-android-compact-runaway] Spend one unit of the run's call budget.
        // The depth cap bounds how DEEP the recursion goes; this bounds how
        // WIDE it gets in total, which is what actually determines wall-clock
        // time when each call is slow rather than failing fast.
        val spent = compactCallsIssued.incrementAndGet()
        if (spent > MAX_COMPACT_LLM_CALLS) {
            throw IllegalStateException(
                "compaction exceeded its budget of $MAX_COMPACT_LLM_CALLS model calls"
            )
        }
        // [T-android-compact-progress] Publish before the call so the UI shows
        // the segment that is actually running, not the one that just finished.
        _compactProgress.value = _compactProgress.value?.copy(
            depth = depth,
            callsIssued = spent,
        )
        return try {
            generateCompactSummary(conversationText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isSegmentRetryableError(e) || messages.size < 2 || depth >= 3) {
                throw e
            }
            // Don't start a split we cannot afford to finish: a half that
            // immediately throws on budget would discard the sibling's work.
            if (compactCallsIssued.get() + 2 > MAX_COMPACT_LLM_CALLS) {
                AppLogger.info(
                    TAG,
                    "[Compact] not splitting at depth=$depth — " +
                        "${compactCallsIssued.get()}/$MAX_COMPACT_LLM_CALLS calls already spent",
                )
                throw e
            }
            val mid = messages.size / 2
            val firstHalf = messages.subList(0, mid).toList()
            val secondHalf = messages.subList(mid, messages.size).toList()
            AppLogger.info(
                TAG,
                "[Compact] Splitting ${messages.size} messages into ${firstHalf.size} + ${secondHalf.size} (depth=$depth)",
            )
            val summary1 = generateCompactSummaryWithSplitting(firstHalf, null, depth + 1)
            val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)
            // Join the partials textually — the caller stores a single summary
            // string, so segmentation stays invisible downstream.
            //
            // This used to be a THIRD LLM call that re-summarised the two
            // partials. Dropped, because the size premise behind it does not
            // hold: each segment's output is already hard-capped (see
            // maxOutputTokens in generateCompactSummary), so two partials are
            // nowhere near a context boundary and not worth another round-trip
            // to shrink.
            //
            // It was also the one genuinely fragile step: the merge went
            // through generateCompactSummary directly, with no depth and no
            // split retry of its own, so a failure there threw away the
            // segments that had just succeeded. The mechanism that exists to
            // rescue a failing compaction ended its own happy path on an
            // unprotected call. A string join cannot fail.
            //
            // What is lost is the merge prompt's cross-part editing (prefer the
            // newer half, de-duplicate shared background). Accepted: the parts
            // are already ordered oldest-first, which is the same signal in
            // positional form, and each is internally coherent because it was
            // summarised under the full system prompt.
            summary1 + "\n\n" + summary2
        }
    }

    /**
     * Single-shot LLM call that turns [conversationText] into a structured
     * summary. Throws on provider error so the splitter above can detect
     * context-too-large failures and retry with halved input.
     */
    private suspend fun generateCompactSummary(conversationText: String): String {
        // Wrap the transcript in explicit BEGIN/END framing so the model
        // treats it as material to summarize rather than as a chat turn to
        // continue. Mirrors iOS AIChatViewModel+Compaction.swift
        // `compactUserMessage` construction. Without this wrapper, fast models
        // (e.g. deepseek-v4-flash) tend to "answer" whatever the last user
        // turn in the transcript said — producing a single-line continuation
        // instead of a structured summary.
        val userMessage = buildString {
            append("Compact this conversation into a context summary:\n\n")
            append(conversationText)
            append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
            append(
                "Now generate a structured context summary following the system prompt " +
                    "instructions. Do NOT continue the conversation above — summarize it. " +
                    "Write everything in past tense, framed as \"what was discussed / what " +
                    "was done\", NOT as an ongoing goal or todo list."
            )
        }
        val model = currentModel
        val contextWindow = model?.contextWindow ?: 128_000
        val estimatedInput = userMessage.length / 4
        val maxOut = maxOf(1024, minOf(8192, contextWindow - estimatedInput))
        val provider = currentProvider
            ?: throw IllegalStateException("No LLM provider available for compaction")
        val response = provider.sendMessage(
            messages = listOf(
                LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
            ),
            systemPrompt = compactSummarySystemPrompt,
            maxTokens = maxOut,
            // Mirror iOS AIChatViewModel.swift:12926 — null lets the
            // provider/model use its default. gpt-5.x family rejects any
            // temperature != 1 with HTTP 400, and Android
            // OpenAIProvider.buildRequestBody omits the field entirely when
            // temperature is null.
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )
        return response.text
    }

    /**
     * Should a failed summary attempt be retried by splitting the input in half?
     *
     * Ported from iOS `isSegmentRetryableError`
     * (AIChatViewModel+Compaction.swift:1010, T-compact-segment-retry-any-error).
     *
     * Everything EXCEPT the two cases where a smaller request cannot help:
     *   - cancellation — the user (or a session switch) stopped the work, so a
     *     retry would fight that and immediately throw again;
     *   - network/offline — the request never reached a model, so payload size
     *     is irrelevant and splitting just doubles the failed round-trips.
     *
     * This deliberately REPLACES [isContextTooLargeError] on the split path.
     * That substring allow-list tried to enumerate how every provider words an
     * over-length refusal and was provably incomplete — OpenMinis#133's
     * `[context_length_exceeded] Your input exceeds the context window of this
     * model` slipped past several variants — and every miss silently disabled
     * splitting, so compaction failed outright instead of retrying smaller.
     *
     * Splitting on an unclassified error is still the default, for that reason:
     * a summary built from halves beats no summary, so the burden of proof is
     * on NOT retrying.
     *
     * [T-android-compact-runaway] What changed is that "unclassified" no longer
     * means "everything". Splitting only helps when the failure was caused by
     * the SIZE of the request, and two error classes are known not to be:
     *
     *   - [LLMError.RateLimited] (429). The model is refusing on quota, not on
     *     length. Halving turns one rejected call into two rejected calls, each
     *     still subject to the provider's backoff — this is the exact shape
     *     that turned a single failure into ~15 sequential slow calls and the
     *     15-20 minute apparent hang users reported.
     *   - [LLMError.TransientError] (5xx / upstream). A server-side fault is
     *     independent of payload; retrying smaller just multiplies the outage.
     *
     * Both are better served by failing fast and letting the user retry the
     * whole compaction once conditions change.
     */
    private fun isSegmentRetryableError(error: Throwable): Boolean =
        shouldSplitOnError(error)

    /**
     * Match provider error text against the substring set iOS
     * `isContextTooLargeError` used before T-compact-segment-retry-any-error.
     *
     * NO LONGER gates segment retry — [isSegmentRetryableError] does, for the
     * reasons documented there. Retained only for user-facing wording, where
     * guessing wrong costs a less specific message rather than a failed
     * compaction.
     */
    @Suppress("unused")
    private fun isContextTooLargeError(error: Throwable): Boolean {
        val desc = (error.message ?: error.toString()).lowercase()
        return desc.contains("too many tokens") ||
            desc.contains("context length") ||
            desc.contains("max_tokens") ||
            desc.contains("content is too long") ||
            desc.contains("exceeds the model") ||
            desc.contains("request too large") ||
            desc.contains("prompt is too long") ||
            desc.contains("token limit") ||
            desc.contains("context window")
    }

    /**
     * Consult [ContextPolicy] before sending. Returns true to proceed. The
     * Android MVP doesn't surface a "Compact before send" dialog (iOS does),
     * so we only warn via [appendSystemInfo] at the `needsCompact` /
     * `exhausted` boundaries and still allow the send. That gives the user
     * a signal to invoke `/compact` explicitly without blocking their turn.
     */
    private fun checkContextBeforeSend(): PreSendContextAction {
        val tokens = _lastTurnContextTokens.value
        if (tokens <= 0) return PreSendContextAction.PROCEED
        // [T-context-window-live-read] Live window (entry re-resolved + group
        // contextLimitTokens folded in) — not the currentModel snapshot.
        val window = effectiveContextWindowTokens() ?: return PreSendContextAction.PROCEED
        val policy = ContextPolicy.forContextWindow(window)
        return when (policy.check(tokens, window)) {
            ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

            // Mirrors iOS AIChatViewModel.swift:2224. Previously Android only
            // appended a notice here and sent anyway, which meant the very
            // request that tripped the threshold still went out over-length —
            // the warning arrived alongside the failure it was meant to avoid.
            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (com.openminis.app.data.AutoCompactPrefs.isEnabled()) {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                    )
                    PreSendContextAction.COMPACT_THEN_SEND
                } else {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                    )
                    PreSendContextAction.ASK_USER
                }
            }

            // Exhausted tiers have compactThreshold = 0 by policy: the window is
            // too small for a summary to pay for itself, so compacting is not
            // on offer. Keep the existing advisory-and-proceed behaviour rather
            // than blocking the user out of their own chat.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                appendSystemInfo(
                    text = "Context is near the model's limit ($tokens / $window tokens). Start a new chat or /compact to continue reliably.",
                    iconKind = "compact",
                )
                PreSendContextAction.PROCEED
            }
        }
    }

    /** What the pre-send context check decided. Mirrors iOS's send() branch. */
    private enum class PreSendContextAction {
        /** Under threshold (or nothing useful to do) — send as normal. */
        PROCEED,

        /** Auto-compact is on — compact silently, then send. */
        COMPACT_THEN_SEND,

        /** Auto-compact is off — raise the dialog and let the user choose. */
        ASK_USER,
    }

    /**
     * Text + attachments held back while the "Context Near Capacity" dialog is
     * up. Mirrors iOS `pendingSendText` / `pendingSendAttachments`.
     */
    private var pendingSendText: String? = null

    private val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    /**
     * Dialog action: compact the history, then send what the user was holding.
     * [alsoEnableAutoCompact] backs iOS's one-tap opt-in button, which compacts
     * now AND remembers the choice for every future conversation.
     */
    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) {
        if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        viewModelScope.launch {
            val ok = awaitCompaction()
            if (!ok) {
                AppLogger.warning(TAG, "[Context] pre-send compaction failed — sending anyway")
            }
            sendMessage(text, skipContextCheck = true)
        }
    }

    /** Dialog action: send without compacting. */
    fun sendPendingWithoutCompacting() {
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        sendMessage(text, skipContextCheck = true)
    }

    /** Dialog dismissed — restore the text to the composer so it isn't lost. */
    fun cancelCompactBeforeSend() {
        _showCompactBeforeSendPrompt.value = false
        pendingSendText?.let { _inputText.value = it }
        pendingSendText = null
    }

    /**
     * [T-android-auto-compact-inloop] What the in-loop context guard decided.
     */
    private enum class InLoopContextAction {
        /** Under threshold — issue the next API call as normal. */
        PROCEED,

        /** History was compacted in place; re-run the iteration. */
        COMPACTED,

        /** Cannot recover — stop the turn safely and let the user resume. */
        STOP,
    }

    /**
     * [T-android-auto-compact-inloop] Max in-loop compactions per runAgentLoop.
     * Bounds compact-thrash within a single turn; the MAX_AGENT_TURNS ceiling is
     * never reset by compaction, so this is a second, tighter backstop.
     */
    private val maxInLoopCompactions = 3

    /**
     * [T-android-auto-compact-inloop] Re-evaluate [ContextPolicy] between agent
     * iterations and act on it (iOS f70ac173).
     *
     * Why this exists: [checkContextBeforeSend] only runs at the SEND entry
     * point. A single turn that fans out into many tool iterations can cross the
     * compact/exhausted thresholds mid-loop, and offload alone cannot recover
     * when the bulk is the model's own text — the turn then slams into the
     * provider's context ceiling.
     *
     * Blocks until the compaction attempt settles, because the next API call
     * must read the freshly-compacted history.
     */
    private suspend fun inLoopContextCheck(compactionsSoFar: Int): InLoopContextAction {
        val tokens = _lastTurnContextTokens.value
        if (tokens <= 0) return InLoopContextAction.PROCEED
        val window = effectiveContextWindowTokens() ?: return InLoopContextAction.PROCEED
        val policy = ContextPolicy.forContextWindow(window)
        return when (policy.check(tokens, window)) {
            ContextPolicy.CheckResult.OK -> InLoopContextAction.PROCEED

            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (compactionsSoFar >= maxInLoopCompactions) {
                    AppLogger.warning(
                        TAG,
                        "[AutoCompact] still over threshold after $compactionsSoFar compaction(s) — stopping",
                    )
                    return InLoopContextAction.STOP
                }
                // NOTE: deliberately NOT gated on AutoCompactPrefs. That flag
                // governs the SEND-time decision (compact silently vs. ask
                // first) — mid-loop there is nobody to ask, and the alternative
                // to compacting is aborting the user's turn outright. iOS makes
                // the same call: its in-loop branch
                // (AIChatViewModel.swift:4739) never consults
                // autoCompactEnabled either.
                AppLogger.info(
                    TAG,
                    "[AutoCompact] mid-loop compact #${compactionsSoFar + 1}: $tokens / $window tokens " +
                        "(autoCompactPref=${com.openminis.app.data.AutoCompactPrefs.isEnabled()}, not a gate here)",
                )
                appendSystemInfo(
                    text = "Context is filling up ($tokens / $window tokens) — compacting to continue.",
                    iconKind = "compact",
                )
                val ok = awaitCompaction()
                if (!ok) return InLoopContextAction.STOP
                // [T-android-auto-compact-inloop] Invalidate the stale reading.
                // `_lastTurnContextTokens` is only refreshed by a usage chunk,
                // which needs a COMPLETED API call — but this path compacts and
                // `continue`s without one. Leaving the pre-compaction value in
                // place made the very next iteration read the same number and
                // compact again immediately, burning the whole budget in
                // seconds (observed on device: two compactions 3s apart, both
                // logging an identical 66358). Zeroing it makes the guard
                // PROCEED once, so the next real response measures the
                // post-compaction size and the decision is made on fresh data.
                _lastTurnContextTokens.value = 0
                InLoopContextAction.COMPACTED
            }

            // EXHAUSTED is only ever returned by `exhaustedOnly` tiers — windows
            // under 64K, where ContextPolicy sets compactThreshold = 0 precisely
            // BECAUSE the window is too small for auto-compact to pay for itself
            // (the summary plus re-appended recent turns would eat the headroom
            // it just freed). Attempting a "rescue" compaction here would
            // contradict the policy, so stop and let the user decide.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] exhausted on a no-auto-compact tier ($tokens / $window) — stopping",
                )
                InLoopContextAction.STOP
            }
        }
    }

    /**
     * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and
     * suspend until it settles. Returns whether it actually compacted.
     *
     * `compactAll` is fire-and-forget (it launches its own IO coroutine), so the
     * loop cannot simply call it and continue — the next API call would read the
     * pre-compaction history and the guard would fire again immediately.
     */
    private suspend fun awaitCompaction(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var resumed = false
            compactAll(allowDuringProcessing = true) { ok ->
                // compactAll guarantees exactly one callback, but guard anyway:
                // resuming a continuation twice throws.
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(ok) { _, _, _ -> }
                }
            }
        }

    /**
     * System prompt for the single-shot summarisation call. Matches iOS
     * wording so cross-device summaries stay stylistically aligned.
     */
    private val compactSummarySystemPrompt: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()

    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // viewModelScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Whether this is a draft session (not yet persisted to DB). */
    private val isDraft: Boolean = sessionId.startsWith("__new__")

    /** Model group ID from long-press FAB, encoded in the draft session ID.
     *  substringBefore strips the folder marker in case both are present. */
    private val initialGroupId: String? =
        sessionId.substringAfter("__grp__", "").substringBefore("__fld__")
            .takeIf { it.isNotEmpty() }

    /** Session-group (folder) id from the folder card's "New Chat in Group"
     *  menu item, encoded in the draft id. Filed at draft promotion — the
     *  folder_id row can only exist once the session does (iOS defers the
     *  same way via pendingFolderDraft). */
    private val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__")
            .takeIf { it.isNotEmpty() }

    /** The real session ID (same as sessionId for existing sessions, generated on first message for drafts). */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    init {
        loadSession()
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    // [T-android-group-pause-badge-restamp] Only a REAL
                    // interruption re-stamps the badge's entry time. This
                    // collector is the single chokepoint over every
                    // `_canResume` setter, so it ALSO fires when loadSession
                    // merely RE-DETECTS an old interrupted tail — that is not
                    // a new entry into the paused state, and re-stamping it
                    // there is what let a days-old pause keep looking "fresh"
                    // to the group card's 24h window forever (the more often
                    // the user opened the chat, the less able it was to
                    // expire). The detecting site raises a sticky generation
                    // mark before its assignment; we consume it here, once the
                    // annotated emission has actually been observed.
                    val pendingGen = redetectingInterruptedTailGen
                    val isRedetection = pendingGen != consumedRedetectGen
                    if (isRedetection) consumedRedetectGen = pendingGen
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                        restamp = !isRedetection,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        viewModelScope.launch {
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the default group's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // T278: _availableGroups feeds the model picker sheet — it must
                // track the latest config on every emission, even after the user
                // has selected a model (currentProvider != null). The guard below
                // is for the fallback-resolution path which CAN trample the user's
                // selection; _availableGroups has no such risk because the sheet
                // re-reads it on each open.
                _availableGroups.value = config.modelGroups
                // [T-android-disabled-provider-still-selectable-via-group #34]
                // Runtime re-resolution when a GROUP-bound session's currently
                // active member has its provider DISABLED mid-session. The
                // selection paths (resolveProviderFromGroup → enabledMemberEntries)
                // already skip disabled members, but they only run while
                // currentProvider == null (cold start / fallback). Once a group
                // member is resolved, currentProvider is cached and the guard
                // below short-circuits — so if the user then disables that
                // member's provider (e.g. a Coding Plan whose quota ran out,
                // turned off to force fallback to the next provider), the stale
                // currentProvider keeps routing to the disabled provider's
                // pay-as-you-go model and bills them. Mirror iOS resolveCurrentEntry
                // (a306ce08): when the active entry's provider is no longer
                // enabled, re-resolve the group to its next enabled member. Only
                // for group bindings — a deliberate direct-entry pick is left
                // untouched (it has no in-group alternative to fall back to).
                val groupBound = _selectedGroupId.value
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        // Persist the re-resolved member so a reload doesn't snap
                        // back to the disabled one. resolveProviderFromGroup set
                        // _activeEntryId to the actually-resolved member.
                        _activeEntryId.value?.let {
                            persistBinding("""{"type":"group","groupId":"$groupBound","lastEntryId":"$it"}""")
                        }
                    } else {
                        // Whole group is now unavailable (all members disabled /
                        // credential-less) — fall through to the default group /
                        // new-chat fallback chain by clearing the cached provider
                        // so the guard below re-runs the standard resolution.
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled and group has no enabled member — falling back",
                        )
                        currentProvider = null
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    // T306: re-attempt the persisted binding now that config
                    // has entries. For an existing session whose loadSession
                    // ran before config finished (so restoreFromBinding fell
                    // through), the binding pointed at the right entry all
                    // along — we just couldn't resolve it. Try it again
                    // before falling back to the default group, so the
                    // fallback target survives a cold start that races
                    // ProviderRepository's async load.
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.getSession(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                    var resolved = false
                    if (effectiveGroupId != null) {
                        resolved = resolveProviderFromGroup(effectiveGroupId)
                        if (resolved) {
                            _selectedGroupId.value = effectiveGroupId
                        }
                    }
                    if (!resolved) {
                        // [T-newchat-default-model-fallback-android] Same
                        // new-chat fallback chain as the draft branch in
                        // loadSession: last-used → newest-provider/newest-text.
                        // Was allVisibleEntries().firstOrNull().
                        applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts, browser artifacts, and
     * the PersistentShell all land in a single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
     *  current session so the shared edit-title sheet (reused from the session
     *  list) can be opened from the in-chat title pill. Returns null for
     *  drafts that haven't been persisted yet. */
    suspend fun loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.getSession(sid) }.getOrNull()
    }

    /** T-chat-title-pill-edit: update title + category from the in-chat
     *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
     *  also refreshes the local StateFlows so the pill updates immediately
     *  without waiting for a session reload. */
    fun updateTitleAndCategory(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(sid, title, category)
            _sessionTitle.value = title.ifBlank { "New Chat" }
            _sessionCategory.value = category
        }
    }

    /** Ensure the session exists in the database. Called before first message. */
    private suspend fun ensureSession(): String {
        if (realSessionId.isNotEmpty()) return realSessionId
        val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
        // [T-memory-global-toggle-settings-ui-android] Snapshot the
        // current in-memory `_memoryEnabled` into the new row. For a
        // draft VM this matches the global default we seeded at
        // construction; if the user flipped /memory on the draft
        // before first send, that choice wins.
        val session = chatRepository.createSession(
            modelId = modelId,
            memoryEnabled = _memoryEnabled.value,
        )
        realSessionId = session.id
        // "New Chat in Group": file the just-promoted draft into its folder.
        // Unconditional (vs iOS setFolderIfUnfiled) — the session is seconds
        // old and nothing else can have filed it yet.
        initialFolderId?.let { chatRepository.setFolderForSessions(it, listOf(session.id)) }
        // Move our cached VM from the draft key ("__new__...") to the real
        // sessionId so re-entering the session reuses the same instance.
        if (isDraft) {
            ChatViewModelStore.rename(sessionId, session.id)
            // Bring every disk/shell resource that was opened with the draft
            // id over to the real id *before* agent tools start running against
            // the persisted session — otherwise the first tool call (e.g.
            // yt-dlp writing into /var/minis/attachments) would land in
            // minis-sessions/__new__*/… and be orphaned when the user
            // re-enters the session and everything is resolved via the real
            // id. See debug report 2026-04-21 (TikTok Chinese filename).
            migrateDraftResources(fromDraft = sessionId, toReal = session.id)
            // [T-android-session-skill-override-init-timing] Re-point any
            // session_skill_overrides / mcp_session_overrides rows written
            // pre-first-message (against `__new__<uuid>`) onto the real
            // session id, mirroring the disk-resource hop above. Without
            // this, a skill or MCP server the user toggled on the draft
            // session sheet vanishes the next time the same chat is opened
            // (the prop carries the real id by then, but the override row
            // is still stranded under the draft key). Aligns with iOS
            // ed861471 (T-ios-session-skill-override-init-timing). Cheap
            // no-op when no rows match.
            skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            mcpRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            // Re-point the lazily-created BrowserTabPool if it was already
            // instantiated against the draft key (e.g. user opened the browser
            // sheet before sending a message). Without this, cookies and
            // downloads keep flowing into the draft directory.
            _browserTabPoolRef?.setSession(session.id)
        }
        // Persist the current model binding so it survives re-entry
        val groupId = _selectedGroupId.value
        val entryId = _activeEntryId.value
        val binding = when {
            groupId != null && entryId != null -> """{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"""
            groupId != null -> """{"type":"group","groupId":"$groupId"}"""
            entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
            else -> null
        }
        if (binding != null) {
            chatRepository.updateSessionBinding(realSessionId, binding, modelId)
        }
        return realSessionId
    }

    /**
     * Move every per-session disk resource from the draft directory to the
     * real one, and tear down any shell that was started against the draft id.
     *
     * The draft key leaks into persistent shells (`ExecutionCoordinator`),
     * browser artifacts (`persistBrowserArtifact`), and the `BrowserTabPool`'s
     * cookie/state store. Before this migration ran, a tool invocation that
     * happened before the user's first message would write into the draft's
     * `minis-sessions/__new__{uuid}` directory and become invisible the
     * moment the VM was recreated under the real id — exactly the symptom
     * observed with the Chinese-named TikTok download that appeared to
     * "disappear" after `yt-dlp` reported success.
     */
    private fun migrateDraftResources(fromDraft: String, toReal: String) {
        // Stop any shell that was already spun up against the draft id; its
        // -b mount arguments were frozen to the draft directory at launch, so
        // we can't reuse it after the migration.
        runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

        val base = java.io.File(context.filesDir, "minis-sessions")
        val draftBase = java.io.File(base, fromDraft)
        if (!draftBase.isDirectory) return
        val realBase = java.io.File(base, toReal).apply { mkdirs() }

        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val src = java.io.File(draftBase, subdir)
            if (!src.isDirectory) return@forEach
            val dst = java.io.File(realBase, subdir).apply { mkdirs() }
            src.listFiles()?.forEach { child ->
                val target = java.io.File(dst, child.name)
                runCatching {
                    if (!target.exists() && !child.renameTo(target)) {
                        copyRecursive(child, target)
                    }
                }.onFailure {
                    android.util.Log.w("ChatViewModel",
                        "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
                }
            }
        }
        runCatching { draftBase.deleteRecursively() }

        // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
        // Otherwise the pool will load empty state on the next re-entry and the
        // user loses their open tabs even though the URLs never truly "went away".
        val tabsDir = java.io.File(context.filesDir, "browser_tabs")
        val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
        if (draftTabs.exists()) {
            val realTabs = java.io.File(tabsDir, "$toReal.json")
            runCatching {
                if (!realTabs.exists()) {
                    if (!draftTabs.renameTo(realTabs)) {
                        draftTabs.copyTo(realTabs, overwrite = false)
                        draftTabs.delete()
                    }
                }
            }
        }
    }

    private fun copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)

    private fun loadSession() {
        // T-android-crash-detected-halt: when CrashFrequencyDetector
        // tripped (#459, ≥3 crashes in last hour), skip the heavy
        // session-restore path entirely. Re-running the same persisted
        // state is exactly what produced the burst, so we'd just feed
        // a re-crash loop while the user is staring at the share dialog.
        // The flag clears the moment the dialog closes (share / dismiss /
        // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(TAG, "loadSession: safe-mode active, skipping session restore")
            // [T-android-perf-logging] Surface the skip on the Perf timeline
            // too — when a crash_or_stall recovery loop is suspected, this
            // distinguishes "loadSession ran and was slow" from "loadSession
            // was skipped (safe-mode), so the stall is elsewhere".
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.skipped",
                "reason=safeMode",
            )
            return
        }
        viewModelScope.launch {
            // [T-HANG-DIAG] timing markers to localise where session entry
            // stalls. Sentinel-tagged so a single grep -v can strip them
            // when this diagnostic is removed. Declared OUTSIDE the try
            // block so the EXIT log in `finally` can still read it after
            // an early-return / exception path.
            val tHangDiagStart = System.currentTimeMillis()
            println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
            try {
            val config = providerRepository.config.value
            _availableGroups.value = config.modelGroups

            if (isDraft) {
                // Draft session: just set up provider using default group or first entry
                _sessionTitle.value = "New Chat"
                _sessionCategory.value = null
                val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                var resolved = false
                if (effectiveGroupId != null) {
                    resolved = resolveProviderFromGroup(effectiveGroupId)
                    if (resolved) {
                        _selectedGroupId.value = effectiveGroupId
                        // T312: pull group session defaults onto the new draft.
                        // ensureSession will persist the override once the
                        // first message is sent and the DB row materialises.
                        applyGroupSessionDefaults(effectiveGroupId)
                    }
                }
                if (!resolved) {
                    // [T-newchat-default-model-fallback-android] No default
                    // group (or it had no usable model) → last-used model, then
                    // newest-provider/newest-text-model. Was firstOrNull().
                    applyNewChatDefaultModel()
                }
                return@launch
            }

            // Existing session: load from DB
            val session = chatRepository.getSession(sessionId) ?: return@launch
            _sessionTitle.value = session.title ?: "New Chat"
            _sessionCategory.value = session.category
            _memoryEnabled.value = session.memoryEnabled != 0
            // T239: hydrate persisted thinking-mode override. null = unset
            // (use OFF as the legacy default); non-null = explicit user
            // choice persisted across cold-start. runCatching guards against
            // a stale enum name from a future rename — fall back silently
            // rather than crashing the session load.
            _thinkingLevel.value = session.thinkingOverride
                ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
                ?: ThinkingLevel.OFF

            // Priority 1: restore from persisted model_binding (group or entry)
            var resolved = restoreFromBinding(session.modelBinding)

            // Priority 2: fall back to stored model_id
            if (!resolved) {
                val entry = findModelEntry(session.modelId)
                if (entry != null) {
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _activeEntryId.value = entry.id
                    val instance = providerRepository.instance(entry.providerInstanceId)
                    if (instance != null) {
                        // [T-android-group-resolve-skip-uncredentialed] Gate on
                        // hasAnyCredential — keying off the API key alone left a
                        // session whose model lives on an OAuth provider unable
                        // to restore, despite being signed in.
                        val apiKey = providerRepository.usableApiKey(instance) ?: ""
                        if (providerRepository.hasAnyCredential(instance)) {
                            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
                            _providerName.value = instance.label.ifEmpty { entry.model.provider }
                            resolved = true
                            // No binding row (e.g. a synced session that only
                            // carried model_id). If the entry belongs to the
                            // default group, adopt that group so group fallback
                            // works — otherwise buildFallbackProviders returns
                            // empty and provider errors never fall back. NOT
                            // applied to an explicit "entry" binding (user pin),
                            // which restoreFromBinding handles above. Mirrors
                            // the iOS runAgentLoop group-discovery fix.
                            val defaultGroupId = providerRepository.defaultPrimaryGroupId
                            if (defaultGroupId != null &&
                                providerRepository.group(defaultGroupId)?.memberEntryIds?.contains(entry.id) == true
                            ) {
                                _selectedGroupId.value = defaultGroupId
                            }
                        }
                    }
                }
            }

            // Priority 3: fall back to default group
            if (!resolved) {
                val defaultGroupId = providerRepository.defaultPrimaryGroupId
                if (defaultGroupId != null) {
                    resolved = resolveProviderFromGroup(defaultGroupId)
                    if (resolved) _selectedGroupId.value = defaultGroupId
                }
            }

            // [T-HANG-DIAG] measure DB load + transform separately so a long
            // load on one stage is obvious in the trace.
            //
            // T-android-gc-storm-hang-crash (P0, issue #17): on a 405-message
            // session with one 397KB user row, loadMessages + toChatMessages
            // + the agentHistory rebuild below ran on Main and triggered a
            // GC storm (34MB freed, repeated) that blocked the frame loop for
            // 58s → crash_or_stall restart. Hoist the heavy DB + JSON-parse
            // work off Main so the UI thread stays responsive even when one
            // row is large. Stays inside the existing safe-mode guard above
            // (#466/#470) — we only move work, not gating.
            val tHangDiagBeforeLoad = System.currentTimeMillis()
            data class LoadedSessionData(
                val messages: List<com.openminis.app.data.db.MessageEntity>,
                val ordered: List<ChatMessage>,
                val llmHistory: List<LLMMessage>,
                val loadMs: Long,
                val transformMs: Long,
            )
            com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
            val loaded = withContext(Dispatchers.IO) {
                val tIoBeforeLoad = System.currentTimeMillis()
                val rows = chatRepository.loadMessages(sessionId)
                val tIoAfterLoad = System.currentTimeMillis()
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "db.query.end",
                    "count=${rows.size}",
                )
                val chatUi = rows.toChatMessages()
                val tIoAfterTransform = System.currentTimeMillis()
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "toChatMessages.end",
                    "count=${chatUi.size}",
                )
                // Pre-build the LLM history list off-Main too — toLLMMessage
                // re-parses partsJson for every row, which is the second
                // contributor to the GC storm. Build into a local list and
                // bulk-append to `agentHistory` on Main below; loadSession
                // runs once at init before any other writer touches
                // agentHistory, so a bulk addAll is race-free.
                val llm = ArrayList<LLMMessage>(rows.size)
                var totalPartsChars = 0L
                for (entity in rows) {
                    totalPartsChars += entity.partsJson.length
                    llm.add(entity.toLLMMessage())
                }
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "toLLMMessage.end",
                    "count=${llm.size} totalPartsChars=$totalPartsChars",
                )
                LoadedSessionData(
                    messages = rows,
                    ordered = chatUi,
                    llmHistory = llm,
                    loadMs = tIoAfterLoad - tIoBeforeLoad,
                    transformMs = tIoAfterTransform - tIoAfterLoad,
                )
            }
            val messages = loaded.messages
            val ordered = loaded.ordered
            val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
            val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
            println(
                "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                    "tookMs=${loaded.loadMs}",
            )
            println(
                "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
            )
            // Per-message size sketch + oversize-row scan. Pure diagnostics —
            // does a full second pass over partsJson with several substring
            // searches per row, so on a 405-row session with 1MB total it
            // adds material main-thread time. Fire-and-forget on the IO
            // dispatcher so it can't contribute to the GC-storm hang the
            // rest of this task is trying to fix.
            viewModelScope.launch(Dispatchers.IO) {
                var totalChars = 0L
                var maxChars = 0
                var withTools = 0
                var withAttachments = 0
                for (m in messages) {
                    val len = m.partsJson.length
                    totalChars += len
                    if (len > maxChars) maxChars = len
                    // ContentPart serialises its discriminator in camelCase
                    // ("toolUse" / "toolResult" — see ContentPart.PartType), so
                    // the snake_case probe this used to run matched NOTHING and
                    // reported toolMessages=0 on every session, including ones
                    // whose history is almost entirely tool traffic. That is
                    // the opposite of the signal this diagnostic exists to give
                    // — it is here to finger oversized tool_result inlines as
                    // the GC-storm culprit, and it was reporting them absent.
                    if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) {
                        withTools++
                    }
                    // Same casing trap: attachments serialise as "mediaRef",
                    // never as "image"/"attachment".
                    if (m.partsJson.contains("\"mediaRef\"")) {
                        withAttachments++
                    }
                }
                println(
                    "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                        "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                        "attachmentMessages=$withAttachments",
                )

                // [T-HANG-DIAG] for any message ≥ 50_000 chars, log size /
                // role / createdAt / structural type markers only — NEVER
                // the partsJson content (or any prefix/suffix of it). Earlier
                // versions echoed head500/tail500 to localise the culprit;
                // now that the cause is known (oversized tool_result inlines)
                // and FileReadTool / AIChatViewModel.executeFileRead enforce
                // an 80 KB hard cap upstream, only metadata is needed for
                // future audits.
                val OVERSIZE_THRESHOLD = 50_000
                val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
                if (oversized.isNotEmpty()) {
                    println(
                        "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                            "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                    )
                    for (m in oversized) {
                        val raw = m.partsJson
                        val len = raw.length
                        val hasToolUse = raw.contains("\"toolUse\"")
                        val hasToolResult = raw.contains("\"toolResult\"")
                        val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                        val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                        println(
                            "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                                "createdAt=${m.createdAt} len=$len " +
                                "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                                "hasImage=$hasImage hasBase64=$hasBase64 " +
                                "streamInterrupts=${m.streamInterruptCount}",
                        )
                    }
                }
            }

            // Rebuild agentHistory from persisted messages.
            // Pre-built off-Main inside the withContext(Dispatchers.IO) block
            // above to avoid re-parsing partsJson on the UI thread. Safe to
            // bulk-addAll here because loadSession runs once at init before
            // any sender writes into agentHistory.
            agentHistory.addAll(loaded.llmHistory)
            val tHangDiagAfterAgentHistory = System.currentTimeMillis()
            println(
                "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
            )

            // Restore the most-recent compact summary, if any, so the first
            // outgoing turn after reopening a compacted session still sees
            // the folded-away context via [effectiveAgentHistory]. Also gray
            // out every UI message that falls before the marker's boundary —
            // mirrors iOS Phase 2.5 restore (AIChatViewModel.swift:3360+).
            val marker = runCatching { chatRepository.dao.latestCompactMarker(sessionId) }
                .onFailure { Log.w(TAG, "latestCompactMarker failed: ${it.message}") }
                .getOrNull()
            _compactSummary.value = marker?.summary
            _cachedLatestMarker = marker

            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "stateflow.emit.begin",
                "count=${ordered.size}",
            )
            // [T-android-larky-longsession-followup] Reset the tail
            // window to its initial cap on every session (re)load. Without
            // this a freshly opened session would inherit the previous
            // session's enlarged cap (set via loadOlderMessages), defeating
            // the windowing intent on the first paint of every new session.
            _visibleMessageCap.value = INITIAL_VISIBLE_MESSAGE_CAP
            _messages.value = if (marker == null) {
                ordered
            } else {
                // Phase 2.5: build the historyDbIds set used by the
                // createdAt self-heal to filter to anchors that are
                // actually represented in agentHistory. Mirrors iOS
                // AIChatViewModel+Persistence.swift:406-408.
                val historyDbIds: Set<String> = buildSet {
                    for (m in loaded.llmHistory) {
                        m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                applyCompactMarkerGraying(ordered, marker, loaded.messages, historyDbIds)
            }

            // Cold-start interrupt detection: an agent loop that was killed by
            // the OS (or app force-quit) leaves agentHistory in one of four
            // tell-tale shapes. Detecting any of them lets the user tap
            // Resume to pick up where the model left off — the in-memory
            // [_canResume] flag set by [handleUserCancelledCleanup] is lost
            // across cold starts so we have to re-derive it from the DB.
            // Mirrors iOS AIChatViewModel.loadSession lines 3546-3581.
            //   Case A: last entry is user with all-toolResult parts —
            //           tools completed but the next model call never fired.
            //   Case B: last entry is assistant with any tool_use parts —
            //           the model requested tools that never executed.
            //   Case C: last entry is user with the synthetic "Continue"
            //           reminder text — text-cancel handler committed it
            //           but [resume] never re-entered the agent loop.
            //   Case D: last entry is a PLAIN-TEXT user turn that never got a
            //           reply at all — see below (GH#262/#263).
            val lastEntry = agentHistory.lastOrNull()
            // [T-android-orphan-user-tail GH#262/#263] `isActive` covers the
            // case this VM cannot see: another VM (or the foreground service)
            // is driving this very session, so `_isStreaming` is false HERE
            // while a request is genuinely in flight THERE. Without it, Case D
            // would light Resume on a turn that is merely still waiting.
            val trackerActive = SessionActivityTracker.isActive(activeSessionId)
            if (lastEntry != null && !_isStreaming.value && !trackerActive) {
                val isInterrupted = when (lastEntry.role) {
                    LLMMessage.Role.USER -> {
                        val parts = lastEntry.contentParts
                        val allToolResults = parts.isNotEmpty() &&
                            parts.all { it is AgentContentPart.ToolResult }
                        val isContinueReminder = parts.size == 1 &&
                            (parts.first() as? AgentContentPart.Text)?.text
                                ?.contains("The user stopped the previous response") == true
                        // Case D — a user turn with NO reply after it at all.
                        //
                        // How it is produced: send() persists the user row
                        // (~5605) BEFORE the reply lands. If the process dies
                        // in between — Android reclaiming a backgrounded app is
                        // the reported case — the assistant side never reaches
                        // the store, and it cannot be reconstructed later
                        // because persistAssistantTurn() drops any row with no
                        // parts (~9112, the guard that stops us POSTing a
                        // content-less assistant message back to the API).
                        // An in-app first-turn network failure lands here too:
                        // setInlineError() attaches the error to the last
                        // ASSISTANT row and is a no-op when none exists (~5789),
                        // so that tail is equally reply-less and equally stuck.
                        //
                        // Before this case, such a tail reported canResume=false
                        // — no PAUSED badge, no Resume banner, and retryLast()
                        // bailing at its own `lastAssistantIdx < 0` guard
                        // (~5906). The session had NO recovery affordance and
                        // the user could only start a new chat.
                        //
                        // Deliberately LAST: A and C describe a turn that was
                        // mid-flight; D describes one that never started. Order
                        // keeps their more specific semantics (and logging)
                        // intact for tails that match both.
                        //
                        // False-positive safety — this must never fire on a turn
                        // that is simply still waiting. Three gates hold:
                        //   1. `!_isStreaming` (above) — send() sets it true at
                        //      ~5564, BEFORE persisting the user row at ~5605,
                        //      and clears it only in the stream epilogue, so the
                        //      whole in-flight window is excluded in-process.
                        //   2. `!trackerActive` (above) — the cross-VM case.
                        //   3. This block runs only from loadSession(), never
                        //      mid-stream.
                        // A cold start after a kill satisfies all three exactly
                        // because the process that was streaming no longer
                        // exists.
                        val isUnansweredUserTurn = !allToolResults && !isContinueReminder
                        allToolResults || isContinueReminder || isUnansweredUserTurn
                    }
                    LLMMessage.Role.ASSISTANT -> {
                        lastEntry.contentParts.any { it is AgentContentPart.ToolUse }
                    }
                    else -> false
                }
                if (isInterrupted) {
                    // [T-android-group-pause-badge-restamp] This is a
                    // RE-DETECTION of an interruption that already happened
                    // (possibly days ago) — the persisted tail still looks
                    // unfinished. It is NOT a new entry into the paused state,
                    // so the badge must keep its original entry timestamp;
                    // otherwise merely opening or cold-start scanning an old
                    // chat resets the group card's 24h freshness window and a
                    // long-stale pause flags its group forever. Raised BEFORE
                    // the assignment (the collector runs asynchronously — see
                    // the field's doc) and consumed by the collector, not here.
                    markRedetectingInterruptedTail()
                    _canResume.value = true
                    // Name the shape, not just the role: Case D (reply-less
                    // user tail) is the one that used to be invisible, so a
                    // field log has to be able to tell it from A/C.
                    val shape = when {
                        lastEntry.role == LLMMessage.Role.ASSISTANT -> "B/assistant-toolUse"
                        lastEntry.contentParts.isNotEmpty() &&
                            lastEntry.contentParts.all { it is AgentContentPart.ToolResult } -> "A/toolResult-tail"
                        lastEntry.contentParts.size == 1 &&
                            (lastEntry.contentParts.first() as? AgentContentPart.Text)?.text
                                ?.contains("The user stopped the previous response") == true -> "C/continue-reminder"
                        else -> "D/unanswered-user-turn"
                    }
                    Log.i(TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry.role} shape=$shape)")
                }
            }
            } finally {
                // T201: open the gate even on early `return@launch` (draft path,
                // missing-session path) and on exception, so the init-time
                // config.collect can never deadlock waiting for us.
                sessionLoaded.value = true
                // [T-HANG-DIAG] total time spent in loadSession from ENTER to
                // either successful completion or early return. tHangDiagStart
                // was captured just inside `try` so this covers the whole
                // body the user perceives as "loading".
                println(
                    "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                        "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
                com.openminis.app.diagnostics.PerfLongCtx.step(
                    sessionId,
                    "loadSession.exit",
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
                )
            }
        }
    }

    /**
     * Mark every non-system UI message that falls before [marker]'s boundary
     * as [ChatMessage.isCompactedHistory]. Mirrors iOS Phase 2.5 boundary
     * resolution (AIChatViewModel.swift:3380-3411) but with one improvement
     * over iOS for the compactAll case:
     *
     *   1) `firstKeptMessageId` — first kept message (divider goes BEFORE it)
     *   2) `boundaryMessageId`  — legacy alias of firstKeptMessageId
     *   3) Both null → compactAll. iOS naively places the divider at the end
     *      and grays every loaded UI message, which incorrectly gray-scales
     *      messages persisted AFTER the marker (e.g. follow-up turns sent
     *      between compact and reload). We instead use
     *      `lastCompactedMessageId` to find the last message included in the
     *      compacted range — anything after it stays active. The divider is
     *      placed immediately after that boundary.
     */
    /**
     * Phase 2.5 marker restore (Android port of iOS
     * AIChatViewModel+Persistence.swift:236+).
     *
     * Resolution order (mirrors iOS exactly):
     *   1. v2 marker (`version >= 2`) — use `lastCompactedMessageId`
     *      via sourceDbIds range → divider AFTER that UI row
     *   2. v1 compactAll-shape (firstKept/boundary both null,
     *      lcmId set) — same as 1
     *   3. v1 compactBefore (firstKeptMessageId / boundaryMessageId
     *      set) — divider BEFORE that boundary row
     *   4. **createdAt self-heal** — find the last raw with
     *      `createdAt < marker.createdAt` whose id is still in
     *      agentHistory, use it as the new anchor, REWRITE the
     *      marker as v2 + write back to DB. Next load takes the
     *      v2 fast path (no heal needed).
     *   5. Final fallback — insert divider at idx=0, gray NOTHING.
     *      This deliberately differs from the pre-T-compact-v2
     *      behaviour of "divider at bottom, gray everything" which
     *      grayed newly-sent messages on every reload (the
     *      user-reported "divider at top, new messages keep
     *      turning gray" symptom).
     *
     * Suspending because the self-heal path writes back through
     * the DAO. Caller (loadSession) is already on a coroutine.
     */
    private suspend fun applyCompactMarkerGraying(
        messages: List<ChatMessage>,
        marker: com.openminis.app.data.db.CompactMarkerEntity,
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        historyDbIds: Set<String>,
    ): List<ChatMessage> {
        // Some legacy rows have empty-string boundaries instead of NULL —
        // treat both as "no boundary" so the compactAll path below kicks in.
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
        val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

        // ─── Resolve insertIdx ────────────────────────────────────────
        //
        // insertIdx semantics: messages[0 until insertIdx] become grayed
        // (isCompactedHistory=true); the divider sits at insertIdx;
        // messages[insertIdx..] stay active.
        //
        // Special value -1 → "unresolved": skip the rewrite below and
        // return the messages untouched with no divider (the marker is
        // effectively invisible until the user reverts or self-heals).
        // Used when even createdAt fallback fails — better to show no
        // divider than to incorrectly gray live messages.
        var insertIdx = -1
        var healedMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

        // Helper: locate the UI message whose sourceDbIds (or id) contains
        // the given dbId. Matches iOS uiIndexForAnchorRaw, which scans by
        // sourceSortOrder range; Android's equivalent is sourceDbIds.
        fun uiIdxForDbId(dbId: String): Int =
            messages.indexOfLast { msg -> dbId in msg.sourceDbIds || msg.id == dbId }

        if (firstKeptId == null) {
            // v2 OR v1 compactAll-shape — anchored by lcmId.
            val lcmIdx = lcmId?.let { uiIdxForDbId(it) } ?: -1
            if (lcmIdx >= 0) {
                // Happy path: lcmId resolves directly. Divider AFTER anchor.
                insertIdx = lcmIdx + 1
            } else {
                // lcmId missing or orphaned. Try createdAt self-heal.
                val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
                val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
                if (heal != null && healUiIdx >= 0) {
                    insertIdx = healUiIdx + 1
                    healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 self-heal: orphaned lcmId=${lcmId?.take(8) ?: "nil"} " +
                            "→ newAnchor=${heal.id.take(8)} (createdAt=${heal.createdAt}) " +
                            "→ uiIdx=$healUiIdx insertIdx=$insertIdx",
                    )
                } else {
                    // Even createdAt heal failed. Place divider at top
                    // with NO graying — this is iOS's "insertIdx=0, no
                    // gray" branch (Persistence.swift:350-351). The
                    // pre-T-compact-v2 behaviour of "cutoff = lastIndex,
                    // gray everything" produced the user-reported bug:
                    // every new message also fell within [0..cutoff]
                    // and was repeatedly grayed on each reload.
                    insertIdx = 0
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 unresolved (heal failed): marker.id=${marker.id.take(8)} " +
                            "lcmId=${lcmId?.take(8) ?: "nil"} — divider at top, no graying",
                    )
                }
            }
        } else {
            // v1 compactBefore — anchored by firstKeptId. Divider BEFORE
            // the boundary; boundary is the first active message.
            val bIdx = messages.indexOfFirst { msg ->
                firstKeptId in msg.sourceDbIds || msg.id == firstKeptId
            }
            if (bIdx >= 0) {
                insertIdx = bIdx
            } else {
                // Boundary deleted / orphaned. Try createdAt self-heal —
                // same path as compactAll, then divider AFTER the healed
                // anchor (treating this as an upgrade to v2 compactAll
                // semantics).
                val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
                val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
                if (heal != null && healUiIdx >= 0) {
                    insertIdx = healUiIdx + 1
                    healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                            "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                    )
                } else {
                    insertIdx = 0
                    AppLogger.warning(
                        TAG,
                        "[Compact] Phase2.5 v1 unresolved (heal failed): firstKeptId=${firstKeptId.take(8)} — " +
                            "divider at top, no graying",
                    )
                }
            }
        }

        // ─── Persist healed marker (if any) ───────────────────────────
        //
        // Run BEFORE building the UI list so a future loadSession() picks
        // up the v2 fast path. Failure here is non-fatal — UI still
        // renders against the in-memory healed pointer.
        if (healedMarker != null) {
            runCatching { chatRepository.dao.updateCompactMarker(healedMarker) }
                .onFailure { Log.w(TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
            // Refresh in-memory cache so effectiveAgentHistory and the
            // next compact pass see the upgraded marker. The caller
            // (loadSession) sets _cachedLatestMarker = marker BEFORE
            // calling us, so overwrite with the healed one now.
            _cachedLatestMarker = healedMarker
            _compactSummary.value = healedMarker.summary
        }

        // ─── Apply graying ────────────────────────────────────────────
        val grayed: List<ChatMessage> = if (insertIdx <= 0) {
            // No graying — either explicit no-gray branch or boundary at
            // index 0 (nothing to gray).
            messages
        } else {
            messages.mapIndexed { idx, msg ->
                if (idx >= insertIdx) msg
                else if (msg.role == "system") msg
                else if (msg.isCompactedHistory) msg
                else msg.copy(isCompactedHistory = true)
            }
        }

        // ─── Insert divider row ───────────────────────────────────────
        // T126-marker: match iOS `"\(insertIdx) messages compacted"`
        // (AIChatViewModel.swift:3432). Count = number of UI bubbles
        // above the divider, not marker.compactedCount (which counts raw
        // agentHistory entries — tool_use/tool_result pairs that never
        // appear as their own UI bubble).
        val compactedUICount = (0 until insertIdx.coerceIn(0, grayed.size))
            .count { grayed[it].role != "system" }
        val dividerLabel = "$compactedUICount messages compacted"
        val markerForDivider = healedMarker ?: marker
        val dividerBlock = AssistantBlock(
            id = "compact-divider-${markerForDivider.id}",
            kind = "info",
            content = dividerLabel,
            toolName = "compact",
            toolArgs = markerForDivider.summary,
        )
        val dividerMsg = ChatMessage(
            id = "compact-divider-msg-${markerForDivider.id}",
            role = "system",
            content = "",
            toolBlocks = listOf(dividerBlock),
        )
        val withDivider = grayed.toMutableList()
        withDivider.add(insertIdx.coerceIn(0, withDivider.size), dividerMsg)
        return withDivider
    }

    /**
     * createdAt self-heal: return the LAST raw message whose
     * `createdAt < markerCreatedAt` AND whose id is still represented in
     * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
     * empty (no dbIds collected — unusual), the filter degrades to "just
     * the createdAt predicate" so we still recover SOMETHING.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:125.
     */
    private fun anchorByCreatedAt(
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        markerCreatedAt: Long,
        historyDbIds: Set<String>,
    ): com.openminis.app.data.db.MessageEntity? {
        return rawMessages.lastOrNull { raw ->
            raw.createdAt < markerCreatedAt &&
                (historyDbIds.isEmpty() || raw.id in historyDbIds)
        }
    }

    /**
     * Build a healed v2 marker that preserves identity (id, sessionId,
     * summary, createdAt, compactedCount) but swaps `lastCompactedMessageId`
     * to the recomputed anchor, zeroes legacy fields, and bumps `version`
     * to 2. Future loads resolve through the corrected lcmId directly
     * without re-running the createdAt fallback.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:150.
     */
    private fun rewriteMarkerForHeal(
        original: com.openminis.app.data.db.CompactMarkerEntity,
        newAnchor: com.openminis.app.data.db.MessageEntity,
        lastRaw: com.openminis.app.data.db.MessageEntity?,
    ): com.openminis.app.data.db.CompactMarkerEntity {
        // Legacy sort-order fallback writes a past-end sentinel so any
        // hypothetical v1 reader sees "everything compacted, nothing
        // kept" (graceful degradation, no overlap with live tail).
        // Android's MessageEntity doesn't carry a sortOrder column —
        // use Int.MAX_VALUE like the original compactAll write path.
        return original.copy(
            firstKeptSortOrder = Int.MAX_VALUE,
            boundaryMessageId = null,
            firstKeptMessageId = null,
            lastCompactedMessageId = newAnchor.id,
            uiBoundarySortOrder = null,
            version = 2,
        )
    }

    /** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
    private fun restoreFromBinding(bindingJson: String?): Boolean {
        bindingJson ?: return false
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "group" -> {
                    val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                    val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                    val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                    if (resolved) _selectedGroupId.value = groupId
                    resolved
                }
                "entry" -> {
                    val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
                    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                    // [T-android-group-resolve-skip-uncredentialed] An explicit
                    // entry pin on an OAuth provider must restore too.
                    if (!providerRepository.hasAnyCredential(instance)) return false
                    val apiKey = providerRepository.usableApiKey(instance) ?: ""
                    currentModel = entry.model
                    _modelName.value = entry.model.displayName
                    _providerName.value = instance.label.ifEmpty { entry.model.provider }
                    _selectedGroupId.value = null
                    _selectedGroupName.value = ""
                    _activeEntryId.value = entry.id
                    currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveProviderFromGroup(groupId: String, preferredEntryId: String? = null): Boolean {
        val group = providerRepository.group(groupId) ?: return false
        // [T-android-group-resolve-skip-uncredentialed] FILTER FIRST, THEN
        // PICK — mirroring iOS `ModelGroupRouter.resolve`.
        //
        // This used to take `enabledMemberEntries.first()` and only THEN check
        // the credential, bailing out of the whole group with `?: return false`
        // when that one member had none. So a group whose first member sat on a
        // provider without a credential resolved to nothing at all, even when
        // later members were perfectly usable — and the caller fell through to
        // the new-chat default chain, which picks by "most recently added
        // provider" and therefore landed on a model that was not in the group
        // the user had selected (field report: a group of Claude+GPT entries
        // resolving to an unrelated self-hosted model on every new chat).
        //
        // The same repo already got this right one layer down:
        // buildFallbackProviders skips an uncredentialed member with
        // `?: continue`. Selection and fallback now agree.
        //
        // Note availableMemberEntries also drops HIDDEN entries, matching iOS.
        // enabledMemberEntries (still used by the settings UI) deliberately
        // does not — "switched on" is the right question there, "usable right
        // now" is the right question here.
        val available = providerRepository.availableMemberEntries(group)
        if (available.isEmpty()) return false

        // preferredEntryId comes from a prior session binding ("user picked
        // this entry inside the group last time"). Honor it only if it is
        // still available; otherwise fall through to the strategy so the
        // session can still proceed on a now-degraded group.
        val targetEntry = available.firstOrNull { it.id == preferredEntryId }
            ?: when (group.strategy) {
                // [T-android-group-resolve-skip-uncredentialed] Honor the
                // group's routing strategy, which initial selection previously
                // ignored entirely — loadBalance silently behaved as fallback.
                // Hashing the session id keeps the choice STABLE for a given
                // session (re-entering it must not reshuffle the model) while
                // spreading distinct sessions across members, matching iOS.
                RoutingStrategy.loadBalance ->
                    available[
                        Math.floorMod(
                            realSessionId.ifEmpty { sessionId }.hashCode(),
                            available.size,
                        ),
                    ]
                RoutingStrategy.fallback -> available.first()
            }

        val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
        // Non-null by construction: availableMemberEntries already required a
        // credential. An OAuth instance has no API key to pass — the factory
        // reads its token from storage — so "" is the correct argument there.
        val apiKey = providerRepository.usableApiKey(instance) ?: ""

        currentModel = targetEntry.model
        _modelName.value = targetEntry.model.displayName
        _providerName.value = instance.label.ifEmpty { targetEntry.model.provider }
        _selectedGroupName.value = group.name
        _activeEntryId.value = targetEntry.id
        currentProvider = ProviderFactory.create(instance, apiKey, targetEntry.model, context)
        return true
    }

    fun selectGroup(groupId: String) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId"}""")
            applyGroupSessionDefaults(groupId)
        }
    }

    /** Select a specific entry within a group (keeps group selected). */
    fun selectGroupEntry(groupId: String, entryId: String) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId, entryId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
            applyGroupSessionDefaults(groupId)
            // [T-newchat-default-model-fallback-android] Record the actually-
            // resolved active entry as last-used (resolveProviderFromGroup may
            // fall back off a disabled member, so _activeEntryId is the truth).
            _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
        }
    }

    /**
     * T312: mirrors iOS `AIChatViewModel.applyGroupSessionDefaults`.
     * When a session newly binds to a group (user picks the group, or a
     * draft session resolves the default group), copy the group's
     * `defaultThinkingLevel` into the session's persisted thinking_override.
     * Context limit is in-memory only on iOS; Android has no equivalent
     * runtime field yet, so we only handle thinking level here.
     *
     * Skips when the group has no default override (null) — leaves the
     * session's existing override untouched so manual user choices on a
     * pre-bound chat aren't clobbered by a later group re-select that
     * happens to land on the same default state.
     */
    private fun applyGroupSessionDefaults(groupId: String) {
        val group = providerRepository.group(groupId) ?: return
        val level = group.defaultThinkingLevel ?: return
        if (_thinkingLevel.value == level) return
        _thinkingLevel.value = level
        viewModelScope.launch {
            val sid = ensureSession()
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] Resolve and apply the default
     * model for a NEW chat when no default group produced a model. Fallback
     * chain tiers 2→3 (tier 1, the default group, is handled by the caller
     * before this runs):
     *
     *   2) last-used model — the entry the user last actively selected / used,
     *      if it still exists, is visible, and its provider is enabled.
     *   3) newest provider's newest text-output model — the final catch-all so
     *      a first-ever chat with providers but no group/last-used still gets a
     *      sensible, text-capable default (image/audio-only models excluded).
     *
     * Sets currentModel / currentProvider / the name + activeEntry state flows.
     * Returns true when a model was applied. Mirrors iOS #636. The legacy
     * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
     * which ignored both last-used and add-order — replaced by this chain.
     */
    private fun applyNewChatDefaultModel(): Boolean {
        val entry = providerRepository.lastUsedVisibleEntry()
            ?: providerRepository.newestProviderNewestTextEntry()
            ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        // [T-android-group-resolve-skip-uncredentialed] Build the provider for
        // an OAuth instance too — otherwise this tier set the model name in the
        // UI but left currentProvider null, and the first send failed.
        if (providerRepository.hasAnyCredential(instance)) {
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        }
        return true
    }

    /** Select a specific model entry (bypasses group selection). */
    fun selectEntry(entryId: String) {
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return
        // [T-android-group-resolve-skip-uncredentialed] The user explicitly
        // tapped this model; refusing it because the API-key slot is empty
        // made OAuth models unselectable from the picker.
        if (!providerRepository.hasAnyCredential(instance)) return
        val apiKey = providerRepository.usableApiKey(instance) ?: ""

        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        persistBinding("""{"type":"entry","entryId":"$entryId"}""")
        // [T-newchat-default-model-fallback-android] Remember this as the
        // global last-used model so the NEXT new chat (when no default group
        // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
        providerRepository.lastUsedEntryId = entryId
    }

    /** Persist the model binding to the DB session (no-op for draft sessions). */
    private fun persistBinding(bindingJson: String) {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
        val modelId = currentModel?.id ?: return
        viewModelScope.launch {
            chatRepository.updateSessionBinding(sid, bindingJson, modelId)
        }
    }

    private fun findModelEntry(modelId: String) =
        providerRepository.allVisibleEntries().find { it.model.id == modelId }

    /**
     * Build the ordered list of fallback providers for the current group,
     * starting AFTER the primary provider in the member list and cycling around.
     * This ensures that models already tried (before the primary) are at the end,
     * not the beginning — so retry doesn't re-trigger the same fallback chain.
     */
    /**
     * [T-android-fallback-entry-identity] A fallback candidate, carrying the
     * ENTRY it was built from.
     *
     * The entry id is the only unambiguous identity: two different provider
     * instances can expose the SAME `model.id` (observed in the field:
     * `deepseek-v4-flash` exists under both "DeekSeak" — api.deepseek.com — and
     * "Bailian OpenAI" — dashscope.aliyuncs.com). Recovering the entry after the
     * fact by matching `model.id` therefore picks whichever entry happens to
     * come first in `modelEntries`, which is not necessarily the one that served
     * the request.
     */
    private data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )

    private fun buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val members = group.memberEntryIds
        // Find current provider's position in the group.
        // [T-android-fallback-entry-identity] Prefer the ACTIVE ENTRY id — the
        // model-id match below is ambiguous when two instances share a model id
        // and would anchor the cycle at the wrong member.
        val activeEntry = _activeEntryId.value
        val currentIdx = members.indexOfFirst { it == activeEntry }.takeIf { it >= 0 }
            ?: members.indexOfFirst { entryId ->
                config.modelEntries.find { it.id == entryId }?.model?.id == primaryProvider.model.id
            }
        val result = mutableListOf<FallbackCandidate>()
        // Iterate starting from the entry AFTER the primary, cycling around
        for (offset in 1 until members.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
            val entryId = members[idx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            // [T-android-group-resolve-skip-uncredentialed] Credential test via
            // hasAnyCredential so an OAuth-logged-in provider is kept as a
            // fallback candidate; usableApiKey alone reads only the API-key
            // slot and dropped every OAuth member from the chain.
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            val p = try {
                ProviderFactory.create(instance, apiKey, entry.model, context)
            } catch (_: Exception) { continue }
            result.add(FallbackCandidate(provider = p, entryId = entry.id))
        }
        return result
    }

    /**
     * Group members that fallback skipped (disabled instance / missing
     * credential / hidden entry), with reasons. Mirrors iOS
     * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
     * needs to know WHY the other group members never got tried — e.g. the
     * Claude subscription was logged out, so every Anthropic entry was
     * silently filtered and fallback kept cycling OpenAI-only.
     */
    private fun unavailableGroupMembers(): List<String> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val result = mutableListOf<String>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            val label = instance.label.ifEmpty { entry.model.provider }
            val reason = when {
                entry.isHidden -> "Hidden"
                !instance.isEnabled -> "Disabled"
                // [T-android-group-resolve-skip-uncredentialed] Must match the
                // routing filter, or a provider the user IS signed into gets
                // reported as "Not logged in".
                !providerRepository.hasAnyCredential(instance) -> "Not logged in"
                else -> continue
            }
            result.add("⚠️ ${entry.model.displayName} ($label): $reason")
        }
        return result
    }

    private fun resolveNextFallbackProvider(): LLMProvider? {
        val groupId = _selectedGroupId.value ?: return null
        val group = providerRepository.group(groupId) ?: return null
        val currentEntryId = _activeEntryId.value ?: return null
        val currentIdx = group.memberEntryIds.indexOf(currentEntryId)
        if (currentIdx < 0) return null

        val config = providerRepository.config.value
        // Try next entries in the group
        for (i in 1 until group.memberEntryIds.size) {
            val nextIdx = (currentIdx + i) % group.memberEntryIds.size
            val entryId = group.memberEntryIds[nextIdx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
            // [T-disabled-provider-via-group-android] Skip disabled
            // providers when walking the group's fallback chain so a
            // disabled provider sitting after the current entry doesn't
            // get picked up. buildFallbackProviders already does this; the
            // single-step variant here had the same bug.
            if (!instance.isEnabled) continue
            // [T-android-group-resolve-skip-uncredentialed] Same credential
            // notion as buildFallbackProviders — OAuth members belong in the
            // single-step chain too.
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""

            currentModel = entry.model
            _modelName.value = entry.model.displayName
            _activeEntryId.value = entry.id
            val provider = ProviderFactory.create(instance, apiKey, entry.model, context)
            currentProvider = provider
            return provider
        }
        return null
    }

    // [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
    // moved to ChatViewModelUiStateExt.kt (extension functions).

    /**
     * T137: Wipe in-memory and on-disk message state for the current session
     * without touching the session's chat files (workspace/, attachments/,
     * offloads/). Mirrors iOS [AIChatViewModel.clearChat] — same surface area,
     * same "files survive" guarantee.
     *
     * Cancels any in-flight stream first so the UI doesn't race the wipe.
     */
    fun clearChat() {
        if (_isStreaming.value) cancelStream()
        val sid = activeSessionId
        // T-streaming-side-channel: ensure no stale stream delta survives a
        // session wipe; the messages list is about to be cleared, so any
        // pending key would be orphaned.
        // [T-android-stream-flush-review] also cancel pending trailing flushes
        // so none re-adds an orphan side-channel entry after the wipe.
        clearAllStreamFlushStates()
        _streamingById.value = emptyMap()
        // Memory state — match iOS clearChat() field list one-for-one.
        _messages.value = emptyList()
        agentHistory.clear()
        _error.value = null
        _cachedLatestMarker = null
        toolLoopDetector.reset()
        _canResume.value = false
        _attachments.value = emptyList()
        _promptQueue.value = emptyList()
        _hasInjectedShareContent.value = false
        // T261: tool-detail sheet is per-session UI state — clear it so a
        // newly cleared chat doesn't briefly flash a stale tool's sheet
        // before the existence-guard catches up.
        _selectedToolDetailId.value = null
        // Drop any browser tabs the agent spawned for this session, and
        // delete the persisted tab snapshot so a future open starts clean.
        // iOS calls BrowserTabPool.deletePersistedData(for:) +
        // BrowserUseOffloadBridge.releasePool(forSession:); on Android the
        // pool is per-VM (lazy), so releasing tabs here is sufficient.
        _browserTabPoolRef?.releaseAllTabs()
        runCatching {
            java.io.File(context.filesDir, "browser_tabs/$sid.json").delete()
        }
        // Persist: drop messages + compact markers. Files (workspace,
        // attachments, offloads) intentionally retained.
        viewModelScope.launch {
            chatRepository.dao.deleteMessages(sid)
            chatRepository.dao.deleteCompactMarkers(sid)
            Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
        }
    }

    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    private val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    /**
     * Convert a staged share file (under filesDir/share_extension/) into
     * an [InputAttachment] and add it to the composer. Called by
     * ChatScreen when draining a [com.openminis.app.share.PendingShare].
     */
    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
                   else InputAttachment.Kind.DOCUMENT
        // T185 fix: ChatScreen wipes the share-extension directory right
        // after this call returns (`SharedShareStore.cleanSharedFiles`),
        // so a `Uri.fromFile(<staged file>)` would dangle by the time the
        // user actually sends — the byte-read in prepareUserAttachments
        // then fails to open the stream and the image never makes it into
        // the LLM payload, leaving the model staring at "what is this?" with no
        // picture. Copy the staged bytes into our own private dir so the
        // attachment outlives the share-extension cleanup.
        val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
        val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
        try {
            file.inputStream().use { input ->
                durable.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
            return null
        }
        val attachment = InputAttachment(
            fileName = file.name,
            uri = android.net.Uri.fromFile(durable),
            mimeType = mime,
            kind = kind,
        )
        addAttachment(attachment)
        return attachment
    }

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    /**
     * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
     * bubble id that currently owns the tool block with [blockId] (== its
     * tool_use id). Returns null when no live bubble holds it. Used by the
     * debug RPC ([com.openminis.app.debug.HeadlessChatRunner.rerunFromToolBlock])
     * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
     * (not the DB row id a caller would read from `chat.messages.list`), so the
     * harness can't supply it directly.
     */
    fun assistantMessageIdForToolBlock(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

    /**
     * [T-android-rerun-from-tool-block-position] Re-run the conversation from
     * the exact point a specific tool_use block was about to be issued —
     * BLOCK-boundary, not turn-boundary. Keeps the blocks BEFORE the target
     * tool_use in the same assistant turn; drops the target block + every
     * later block in that turn + its tool_result + all later turns, then
     * re-runs so the model re-decides from that point.
     *
     * Ported from iOS `retryFromToolBlock` (commit 0149457e). Anchor is the
     * block's tool_use id ([blockId], which for a tool_use [AssistantBlock]
     * equals its `id`) — stable + unique, NOT a positional count, so streaming
     * / merged-turn alignment can't drift the cut point.
     *
     * Degenerate case: when the target is the FIRST real block of its turn
     * (nothing precedes it), this is equivalent to truncating at the preceding
     * user message — delegate to [retryFromMessage] (the existing whole-turn
     * path) and skip the sub-message DB rewrite.
     *
     * Android does the cut DB-first (delete rows after the trimmed assistant
     * row, then rewrite that row's parts in place via
     * [ChatRepository.updateMessageParts]) and rebuilds agentHistory from the
     * trimmed DB state. The UI is trimmed in-memory (same as
     * [retryFromMessage]'s `retainedHead`, so compact-marker graying isn't
     * disturbed). Because the agent loop persists each turn as its own row and
     * `toChatMessages` merges consecutive assistant rows into one bubble, the
     * surviving trimmed turn and the new generation coalesce on the next
     * reload — no duplicate header (iOS needed an explicit resume-into-turn
     * fix for the same; Android gets it from the merge). The thinking
     * indicator shows immediately via [runAgentLoop]'s awaiting placeholder.
     *
     * No-op (returns false) when streaming, when the message/block isn't
     * found, or when the block isn't a tool_use. The caller gates the menu
     * item with the same `!isStreaming` rule, but the guard here is the source
     * of truth.
     */
    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {
        if (_isStreaming.value) return false
        val messages = _messages.value
        val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
        if (asstIdx < 0) return false
        val asstMsg = messages[asstIdx]
        val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
        if (blockIdx < 0) return false
        val targetBlock = asstMsg.toolBlocks[blockIdx]
        // Only a real tool_use block anchors a block cut — its id is the
        // tool_use id we match against in agentHistory / parts_json.
        if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) return false
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        val targetToolUseId = targetBlock.id

        // Degenerate: nothing of substance precedes the target in this turn —
        // a block cut here is identical to truncating at the preceding user
        // message, so reuse the existing whole-turn path. "Substance" = any
        // earlier block that isn't an empty text block (mirrors iOS
        // hasPrecedingContent).
        val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
            if (blk.isText) blk.content.isNotEmpty() else true
        }
        // [T-android-rerun-from-tool-deletes-earlier-turns] The degenerate
        // shortcut is ONLY equivalent to truncating at the preceding user
        // message when there is NOTHING between that user message and this
        // assistant turn. If an EARLIER assistant turn/bubble sits right before
        // this one (asstIdx-1 is also assistant), retryFromMessage(precedingUser)
        // would delete that earlier turn's tools too — exactly the "rerun from
        // the last tool wiped the tools above it / re-ran from the very start"
        // bug (logged: historySize 29 → 3 on the 2nd consecutive rerun). In
        // that case fall through to the DB-precise cut below, which keeps every
        // row before the target row (its cutPartIdx==0 branch deletes only the
        // target row onward) and preserves the earlier turns.
        val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
        if (!hasPrecedingContent && precededByUserOnly) {
            val userMsg = (asstIdx - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: return false
            Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
            retryFromMessage(userMsg.id)
            return true
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return false
        }
        _canResume.value = false
        _error.value = null

        // T149 parity: revoke memory_writes in the parts we're about to drop
        // so the on-disk daily log doesn't keep entries the user rewound past.
        // The dropped range is: the target turn's blocks FROM the target
        // onward (the target tool_use itself + any later same-turn blocks) +
        // every later message. The surviving earlier blocks of the target turn
        // are kept, so they're excluded.
        val droppedTargetTail = asstMsg.copy(
            toolBlocks = asstMsg.toolBlocks.drop(blockIdx),
        )
        val deletedMessages = listOf(droppedTargetTail) +
            messages.subList(asstIdx + 1, messages.size).toList()

        // Claim the streaming flag synchronously so a rapid second tap is
        // rejected by the entry guard (same rationale as retryFromMessage T145).
        AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

                // Locate the DB assistant row holding the target tool_use, and
                // the parts-array index of that tool_use within it.
                val dbMessages = chatRepository.loadMessages(sid)
                var cutRow: MessageEntity? = null
                var cutPartIdx = -1
                outer@ for (entity in dbMessages) {
                    if (entity.role != "assistant") continue
                    val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") != "toolUse") continue
                        val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                        if (tuId == targetToolUseId) {
                            cutRow = entity
                            cutPartIdx = i
                            break@outer
                        }
                    }
                }
                val row = cutRow
                if (row == null || cutPartIdx < 0) {
                    // Anchor not in DB (shouldn't happen for a rendered tool
                    // block). Abort cleanly without a half-applied truncation.
                    Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                    return@launch
                }

                // Trim the row's parts to those strictly before the target
                // tool_use, preserving array order (parts_json mirrors block
                // order). An assistant turn may hold text + several tool_use
                // parts; we keep everything ahead of the matched index.
                val srcArr = org.json.JSONArray(row.partsJson)
                val keptArr = org.json.JSONArray()
                for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

                if (cutPartIdx == 0) {
                    // Nothing precedes the target in its DB row — trimming would
                    // leave an empty assistant row. Drop the whole row instead
                    // (keepCount = its sort_order). The UI degenerate guard
                    // above normally catches this, but a merged-bubble layout
                    // could route a first-in-row tool_use here; handle it so we
                    // never persist a phantom empty assistant message.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder)
                    Log.i(TAG, "rerunFromToolBlock cut at row start (empty trim) tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder} row=${row.id.take(8)}")
                } else {
                    // Delete every row after the trimmed assistant row, then
                    // rewrite the trimmed row in place. deleteMessagesAfter
                    // keeps rows with sort_order < keepCount, so keepCount =
                    // thisRow.sortOrder + 1 drops the following tool_result row
                    // + all later turns while keeping (then overwriting) this one.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder + 1)
                    chatRepository.updateMessageParts(row.id, keptArr.toString())
                    Log.i(TAG, "rerunFromToolBlock sub-message cut tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder + 1} partIdx=$cutPartIdx trimmedRow=${row.id.take(8)}")
                }

                // T149 parity: revoke memory writes in the dropped range.
                revokeMemoryWritesInDeletedMessages(deletedMessages)

                // Trim the UI in-memory (same approach as retryFromMessage's
                // `_messages.value = retainedHead`, which doesn't reload from
                // DB and so doesn't disturb compact-marker graying): keep the
                // target assistant message with only its blocks BEFORE the
                // target, and drop every later message. Block trim mirrors the
                // parts trim above so UI ↔ history stay in lockstep.
                withContext(Dispatchers.Main) {
                    val cur = _messages.value
                    val ai = cur.indexOfFirst { it.id == assistantMessageId }
                    if (ai >= 0) {
                        val keptBlocks = cur[ai].toolBlocks.take(blockIdx)
                        if (keptBlocks.isEmpty()) {
                            // [T-android-rerun-from-tool-deletes-earlier-turns]
                            // Target was the first block of its bubble — the DB
                            // side dropped the whole row (cutPartIdx==0). Drop
                            // the bubble in the UI too instead of leaving an
                            // empty assistant message; earlier bubbles (the
                            // turns that precede this one) are preserved by
                            // subList(0, ai).
                            _messages.value = cur.subList(0, ai).toList()
                        } else {
                            // Recompute `content` from the surviving text blocks
                            // so it doesn't keep text the renderer just dropped.
                            // The chat list renders ordering from toolBlocks, but
                            // `content` feeds previews / copy, so keep it in sync.
                            val keptText = keptBlocks.filter { it.isText }
                                .joinToString("") { it.content }
                            val trimmed = cur[ai].copy(
                                content = keptText,
                                toolBlocks = keptBlocks,
                                isStreaming = false,
                            )
                            _messages.value = cur.subList(0, ai).toList() + trimmed
                        }
                    }
                }
                val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
                retainStreamFlushStates(keptIds)
                if (_streamingById.value.isNotEmpty()) {
                    _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
                }

                // Rebuild agentHistory from the trimmed DB state.
                agentHistory.clear()
                toolLoopDetector.reset()
                for (entity in chatRepository.loadMessages(sid)) {
                    agentHistory.add(entity.toLLMMessage())
                }

                streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
        return true
    }

    /**
     * Retry from a specific user message: truncate all messages after it
     * (including the assistant response), rebuild agent history, and resend.
     * Mirrors iOS's edit/retry behavior — no duplicate user messages.
     */
    fun retryFromMessage(messageId: String) {
        if (_isStreaming.value) return
        _canResume.value = false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        if (message.role != "user" || message.content.isBlank()) return

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        val provider: LLMProvider = initialProvider
        _error.value = null

        // T149: snapshot messages about to be truncated so we can revoke any
        // memory_write tool blocks they contain. Without this, a retry leaves
        // the on-disk daily log with entries the user has just rewound past.
        val deletedMessages = messages.subList(index + 1, messages.size).toList()

        // Truncate UI messages: keep up to and including this user message.
        // T189: if the retried bubble was still in the queued state (manual
        // retry of a queued message before resumeQueueAfterCancel's grace
        // window — or fallback when auto-resume is disabled), flip it out of
        // queued visuals and drop its queue entry so the upcoming send
        // doesn't double up against a later auto-drain.
        val retainedHead = messages.subList(0, index + 1).map { m ->
            if (m.id == messageId && m.isQueued) {
                m.queuedPromptId?.let { pid ->
                    _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
                }
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }
        _messages.value = retainedHead
        // T-streaming-side-channel: scrub stream deltas pointing at
        // messages we just truncated so they can't resurface later.
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        if (_streamingById.value.isNotEmpty()) {
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        // T145: claim the streaming flag SYNCHRONOUSLY so a rapid second tap
        // (or any concurrent send/retry attempt) is rejected by the entry
        // guard. Previously this was set inside the suspended outer launch,
        // leaving a multi-second window during DB cleanup + OAuth refresh
        // where two retries could slip through and spawn duplicate streamJobs.
        // The orphaned first job's `_isStreaming = false` at completion would
        // then flip the UI to "stopped" while the second job was still running.
        AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            // If setup throws before the inner streamJob is launched, the
            // streaming flag would be stuck true forever. Reset on the
            // unhappy paths; happy path resets in the streamJob's tail.
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // Find the DB sort_order cutoff for this user message.
            // UI visible user messages are the N-th user msg with actual text content.
            // Count which visible user message this is (0-based).
            val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
            val dbMessages = chatRepository.loadMessages(sid)
            // Walk DB rows, counting visible user messages (those with non-toolResult text)
            var visibleUserCount = 0
            var cutoffSortOrder = -1
            for (entity in dbMessages) {
                if (entity.role == "user") {
                    // Check if this user message has visible text (not toolResult-only).
                    // [T-ios-retry-anchor-synthetic-user] Synthetic user rows the
                    // agent loop persists WITHOUT a UI bubble — resume()'s
                    // stop-continue "<system-reminder>" message — must not count,
                    // or the cutoff anchors one user message too early and the
                    // retried bubble (plus the whole last turn) is silently
                    // dropped from the rebuilt history (mirrors the iOS fix).
                    val hasText = try {
                        val arr = org.json.JSONArray(entity.partsJson)
                        (0 until arr.length()).any { i ->
                            val o = arr.getJSONObject(i)
                            val v = o.optString("value", "")
                            o.optString("type") == "text" && v.isNotBlank() &&
                                !v.trimStart().startsWith("<system-reminder>")
                        }
                    } catch (_: Exception) { true }
                    if (hasText) {
                        if (visibleUserCount == visibleUserIndex) {
                            cutoffSortOrder = entity.sortOrder + 1
                            break
                        }
                        visibleUserCount++
                    }
                }
            }
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            // Rebuild agentHistory from remaining DB messages
            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) {
                agentHistory.add(entity.toLLMMessage())
            }

            streamLaunched = runRerunStreamTail(provider, "retryFromMessage")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    /**
     * [T-android-delete-from-here] Delete [messageId] and every message after
     * it, leaving the conversation as it stood immediately before that turn.
     * Mirrors iOS `6717c0ab0`.
     *
     * Deliberately close to — but not the same as — [retryFromMessage]. Both
     * rewind the conversation to a point, so this reuses that method's DB
     * anchoring: walk the persisted rows counting only user messages that have
     * VISIBLE text, because the agent loop also persists synthetic
     * `<system-reminder>` user rows that never rendered a bubble. Counting
     * those would anchor the cut one turn too early and silently take an extra
     * exchange with it (the iOS retry-anchor fix, ported here already).
     *
     * The one deliberate difference is the cut point: retry keeps the target
     * user message and re-sends it, so it cuts at `sortOrder + 1`. Delete From
     * Here removes the target too, so it cuts at `sortOrder`. Off-by-one in
     * either direction is silent and destructive — one strands the message the
     * user asked to delete, the other eats the preceding turn.
     *
     * No stream is started and `_isStreaming` is never claimed: this is a pure
     * truncation. It is still refused while a turn is in flight, because
     * deleting rows out from under a live agent loop would leave
     * [agentHistory] describing messages that no longer exist.
     */
    fun deleteFromMessage(messageId: String) {
        if (_isStreaming.value) return
        _canResume.value = false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        // Snapshot what's about to go so any memory_write tool blocks inside
        // can be revoked — otherwise the on-disk daily log keeps entries from
        // messages the user just deleted.
        val deletedMessages = messages.subList(index, messages.size).toList()

        // Truncate the UI to everything BEFORE the target message, and drop
        // any queued prompts that belonged to the deleted range so a later
        // auto-drain can't resurrect them.
        val retainedHead = messages.subList(0, index)
        for (m in deletedMessages) {
            m.queuedPromptId?.let { pid ->
                _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
            }
        }
        _messages.value = retainedHead

        // Scrub stream deltas pointing at truncated messages so they can't
        // resurface into a row that no longer exists.
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        if (_streamingById.value.isNotEmpty()) {
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = activeSessionId ?: return
        viewModelScope.launch {
            val target = messages[index]
            val cutoffSortOrder = resolveDeleteCutoffSortOrder(sid, messages, index, target)
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            // Rebuild agentHistory from what survived, so the next turn is
            // built on the truncated conversation rather than a stale list.
            agentHistory.clear()
            toolLoopDetector.reset()
            val remaining = chatRepository.loadMessages(sid)
            for (entity in remaining) {
                agentHistory.add(entity.toLLMMessage())
            }
            // Refresh the session's last-message preview; otherwise the
            // session list keeps quoting a message that no longer exists.
            // An empty remainder clears it rather than leaving the stale text.
            runCatching {
                chatRepository.updateSessionPreview(sid, remaining.lastOrNull()?.partsJson ?: "[]")
            }
            AppLogger.info(
                TAG,
                "deleteFromMessage: cut at sortOrder=$cutoffSortOrder, " +
                    "${deletedMessages.size} message(s) removed, ${remaining.size} remain",
            )
        }
    }

    /**
     * [T-android-delete-from-here] Resolve the DB `sort_order` to cut at so
     * that [index] and everything after it is removed.
     *
     * Anchoring is by visible-user-message ordinal rather than by row id
     * because the UI list and the persisted rows are not 1:1 — see
     * [deleteFromMessage]'s note on synthetic `<system-reminder>` rows.
     *
     * When the target is an ASSISTANT message the cut is anchored to the user
     * turn it belongs to: we find the last visible user message at or before
     * [index], cut just after it, and thereby drop the assistant reply and
     * everything following. Returns -1 when no anchor can be resolved, which
     * the caller treats as "leave the DB alone".
     */
    private suspend fun resolveDeleteCutoffSortOrder(
        sid: String,
        messages: List<ChatMessage>,
        index: Int,
        target: ChatMessage,
    ): Int {
        val dbMessages = chatRepository.loadMessages(sid)
        // Which visible user message anchors the cut, 0-based.
        // Both a user target and an assistant target anchor to the same
        // ordinal — the last visible user message at or before `index`. What
        // differs is only whether the cut lands on that row or just after it,
        // resolved at the return below.
        val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
        // No user turn at or before the target (e.g. deleting from a leading
        // assistant/system row): everything goes.
        if (visibleUserIndex < 0) return 0
        var visibleUserCount = 0
        for (entity in dbMessages) {
            if (entity.role != "user") continue
            val hasText = try {
                val arr = org.json.JSONArray(entity.partsJson)
                (0 until arr.length()).any { i ->
                    val o = arr.getJSONObject(i)
                    val v = o.optString("value", "")
                    o.optString("type") == "text" && v.isNotBlank() &&
                        !v.trimStart().startsWith("<system-reminder>")
                }
            } catch (_: Exception) { true }
            if (!hasText) continue
            if (visibleUserCount == visibleUserIndex) {
                // Target is that user message → cut AT it (removing it).
                // Target is a later assistant reply → cut just AFTER it
                // (keeping the user turn, removing the reply onward).
                return if (target.role == "user") entity.sortOrder else entity.sortOrder + 1
            }
            visibleUserCount++
        }
        return -1
    }

    /**
     * [T-android-rerun-from-tool-block-position] Shared streaming tail used by
     * both [retryFromMessage] and [rerunFromToolBlock]: refresh the OAuth
     * token if needed, build the (OAuth-prefixed) system prompt, and launch
     * the agent-loop stream job. Callers must have already (a) claimed
     * `_isStreaming = true` synchronously, (b) truncated UI + DB to the desired
     * re-entry point, and (c) rebuilt [agentHistory]. Returns true once the
     * stream job is launched (the caller's outer `finally` resets
     * `_isStreaming` only when this returns false / throws first).
     */
    private suspend fun runRerunStreamTail(
        initialProvider: LLMProvider,
        label: String,
    ): Boolean {
        var provider = initialProvider
        // Refresh OAuth token if needed
        if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            try {
                val activeEntryId = _activeEntryId.value
                val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                if (instance != null) {
                    val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val freshToken = manager?.validAccessToken()
                    if (freshToken != null) {
                        val storedKey = providerRepository.loadApiKey(instance.id)
                        if (freshToken != storedKey) {
                            providerRepository.saveApiKey(instance.id, freshToken)
                            provider = com.openminis.app.provider.ProviderFactory.create(
                                instance, freshToken, currentModel ?: provider.model, context
                            )
                            currentProvider = provider
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // _isStreaming was already set synchronously by the caller.
        val launchedProvider = provider
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
            try {
                SessionConcurrencyManager.acquireSlot(activeSessionId)
                AppLogger.debug(TAG_STREAM, "$label streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: com.openminis.app.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(launchedProvider)
                try {
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop CALL")
                    runAgentLoop(
                        provider = launchedProvider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop RETURN normal")
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "$label runAgentLoop CANCELLED")
                    Log.d(TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(TAG, "Agent loop error ($label)", e)
                    setInlineError(e.message ?: "Unknown error")
                    // T298: flag the upcoming setInactive() so the
                    // background completion notifier renders the ❌
                    // variant instead of a clean success.
                    SessionActivityTracker.markStreamError(activeSessionId)
                } finally {
                    AppLogger.info(TAG_STREAM, "$label streamJob FINALLY enter")
                    // [T-android-overlay-reply-status-34599] Surface
                    // the assistant's most recent reply text to the
                    // overlay BEFORE setInactive so the post-completion
                    // overlay state (no-running, has-outcome) carries a
                    // non-null excerpt. Reading _messages here is safe:
                    // we're in the finally block of the agent loop and
                    // the stream has already flushed its last delta.
                    publishOverlayReplyExcerpt(activeSessionId)
                    SessionActivityTracker.setInactive(activeSessionId)
                    SessionConcurrencyManager.releaseSlot(activeSessionId)
                    AppLogger.info(TAG_STREAM, "$label streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
                Log.d(TAG, "Cancelled while waiting for concurrency slot")
            }
            // [T-android-stale-streamjob-clears-isstreaming] Only the current
            // streamJob is allowed to flip _isStreaming false. An orphaned
            // earlier job (cancelled but its finally still draining downstream
            // I/O) reaching this tail AFTER a fresh send/resume/retry has
            // already taken over would otherwise hide the Stop button while
            // the new turn is still streaming. See `var streamJob` KDoc and
            // XIN 2026-06-12 log (20:22:26 / 20:23:25).
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(TAG_STREAM, "$label _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(TAG_STREAM, "$label _isStreaming SKIPPED (stale job; current=${streamJob?.hashCode()} this=${coroutineContext[Job]?.hashCode()})")
            }
            AppLogger.info(TAG_STREAM, "$label streamJob EXIT")
        }
        return true
    }

    /**
     * T187: enter edit mode for [messageId]. Returns the cleaned text the
     * caller should drop into the composer (with any
     * `<user-attached-files>` XML stripped), or null when the message
     * cannot be edited (streaming in progress, message missing, or not
     * a user turn). Setting `_editingMessageId` is what flips the
     * composer into edit-mode UI; the next sendMessage call sees the
     * non-null id and truncates the conversation from that point.
     * Mirrors iOS AIChatViewModel.editMessage(_:) (L2468).
     */
    fun editMessage(messageId: String): String? {
        if (_isStreaming.value) return null
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
        if (msg.role != "user") return null
        var text = msg.content
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim()
            } else {
                text.substring(0, startIdx).trim()
            }
        }
        // [T-android-edit-loses-attachments] Restore the message's attachments
        // into the composer alongside its text.
        //
        // Without this, editing a message that carried an image silently
        // dropped it: the composer showed only the text, and re-sending
        // produced a turn the model could no longer see the picture in. The
        // XML strip above is what makes the loss invisible — the
        // `<user-attached-files>` block naming the files is removed from the
        // text, so nothing on screen hints that anything was attached.
        //
        // iOS has done this since AIChatViewModel.editMessage (L3891-3920);
        // this side only ever returned the text. Android needs no copy step,
        // unlike iOS: `imageUris`/`attachmentUris` on a restored message
        // already point at files inside the app's own media store (see the
        // `mediaRef` branch of loadSessionMessages, which resolves them
        // against mediaStore.mediaBaseDir and skips any that no longer
        // exist), and that is exactly what the send path re-reads.
        //
        // Ordering matches ChatMessage's own convention — images first, then
        // files — so the composer's preview row shows them the same way the
        // sent bubble did. Names come from `attachmentNames`, which is built
        // image-first to align with these two lists; it is indexed
        // defensively anyway, since a row persisted by an older build could
        // be short.
        val restored = mutableListOf<InputAttachment>()
        msg.imageUris.forEachIndexed { i, uri ->
            val name = msg.attachmentNames.getOrNull(i) ?: uri.lastPathSegment ?: "image"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "image/*"),
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        }
        msg.attachmentUris.forEachIndexed { i, uri ->
            // The non-image names occupy the suffix of attachmentNames, after
            // the imageUris-many image entries.
            val name = msg.attachmentNames.getOrNull(msg.imageUris.size + i)
                ?: uri.lastPathSegment ?: "file"
            restored.add(
                InputAttachment(
                    fileName = name,
                    uri = uri,
                    mimeType = guessMimeType(name, fallback = "application/octet-stream"),
                    kind = InputAttachment.Kind.DOCUMENT,
                ),
            )
        }
        // Replace rather than append: edit mode reloads a specific message, so
        // whatever the composer held was a different draft. Assigning even when
        // empty keeps that true for a message that genuinely had no files.
        _attachments.value = restored

        _editingMessageId.value = messageId
        AppLogger.info(
            TAG_STREAM,
            "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch " +
                "attachments=${restored.size}",
        )
        return text
    }

    /**
     * [T-android-edit-attachments] Best-effort MIME for a restored attachment,
     * derived from its file extension.
     *
     * The exact type only has to be good enough for the composer chip and the
     * send path's image/non-image split; the kind is already decided by which
     * list the URI came out of, so a miss here cannot misroute an attachment.
     */
    private fun guessMimeType(fileName: String, fallback: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return fallback
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: fallback
    }

    /**
     * T187: leave edit mode without sending. Just clears the id flag —
     * caller (ChatScreen) is responsible for clearing inputText. iOS
     * parity: AIChatViewModel.cancelEdit (L2522).
     */
    fun cancelEdit() {
        if (_editingMessageId.value != null) {
            AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
            // [T-android-edit-loses-attachments] Drop the attachments
            // editMessage restored, mirroring how the caller clears the text.
            //
            // Symmetry with editMessage is the whole point: it REPLACES the
            // composer's attachments with the edited message's, so leaving
            // them behind on cancel would strand files the user never picked
            // in a composer they thought they had backed out of — and the next
            // ordinary send would silently attach them.
            //
            // Guarded by the same non-null check as the log so a stray call
            // outside edit mode cannot wipe a draft's real attachments.
            _attachments.value = emptyList()
        }
        _editingMessageId.value = null
    }

    /**
     * T187: drop the message at [messageId] *and* every later message
     * (in UI, in agentHistory, and on disk) so the new sendMessage()
     * call below this can persist the edited text as a fresh user
     * turn at the same position. Reuses the cutoff-search machinery
     * from retryFromMessage but offsets by `entity.sortOrder` (not
     * +1) — retry preserves the original turn, edit replaces it.
     */
    private suspend fun truncateBeforeEdit(messageId: String) {
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val deletedMessages = messages.subList(index, messages.size).toList()
        // [T-android-uimessages-sublist-cme] Defensive: without `.toList()` this
        // stores a live subList VIEW as `_messages.value`.
        //
        // Reproduced on device with this `.toList()` reverted (Pixel 4a): the
        // truncation ran (8 messages → 4) and a further message was sent, and it
        // did NOT crash — the next `+` copies the view into a plain ArrayList
        // before anything can invalidate it. So this line is hardening, not the
        // proven cause of the reported CME. See the long note on `uiMessages`.
        // (`deletedMessages` above already copies; this line did not.)
        val kept = messages.subList(0, index).toList()
        _messages.value = kept
        if (_streamingById.value.isNotEmpty()) {
            val keptIds = kept.mapTo(mutableSetOf()) { it.id }
            retainStreamFlushStates(keptIds)
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }
        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        // Visible-user index of the *edited* message — count user turns
        // strictly before `index`, which is the 0-based ordinal of the
        // edited turn itself.
        val visibleUserIndex = messages.subList(0, index).count { it.role == "user" }
        val dbMessages = chatRepository.loadMessages(sid)
        var visibleUserCount = 0
        var cutoffSortOrder = -1
        for (entity in dbMessages) {
            if (entity.role == "user") {
                val hasText = try {
                    val arr = org.json.JSONArray(entity.partsJson)
                    (0 until arr.length()).any { i ->
                        val o = arr.getJSONObject(i)
                        // [T-android-retry-attachment-loss] Exclude the now-
                        // persisted <user-attached-files> XML text part so this
                        // "is this a visible user bubble?" count stays identical
                        // to pre-XML-persistence behaviour. An attachments-only
                        // turn must NOT flip to hasText just because the XML
                        // inventory is now a text part — that would shift the
                        // retry/edit cutoff onto the wrong message.
                        // [T-ios-retry-anchor-synthetic-user] Likewise exclude
                        // resume()'s synthetic stop-continue <system-reminder>
                        // user row — it has no UI bubble, so counting it shifts
                        // the cutoff one user message too early.
                        o.optString("type") == "text" &&
                            stripAttachedFilesXml(o.optString("value", "")).isNotBlank() &&
                            !o.optString("value", "").trimStart().startsWith("<system-reminder>")
                    }
                } catch (_: Exception) { true }
                if (hasText) {
                    if (visibleUserCount == visibleUserIndex) {
                        // ChatDao.deleteMessagesAfter is `sort_order >= keepCount`
                        // → passing this row's sortOrder deletes IT and everything
                        // after, which is exactly what edit semantics want.
                        cutoffSortOrder = entity.sortOrder
                        break
                    }
                    visibleUserCount++
                }
            }
        }
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }
        agentHistory.clear()
        toolLoopDetector.reset()
        val remaining = chatRepository.loadMessages(sid)
        for (entity in remaining) {
            agentHistory.add(entity.toLLMMessage())
        }
        AppLogger.info(
            TAG_STREAM,
            "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
        )
    }

    /**
     * Enqueue a prompt to be injected into the currently running agent loop.
     * The message appears immediately in the chat with isQueued=true; when the
     * current agent loop finishes, drainQueuedPrompts() consumes the queue.
     * Mirrors iOS AIChatViewModel.enqueuePrompt().
     */
    fun enqueuePrompt(text: String) {
        val trimmed = text.trim()
        val pendingAttachments = _attachments.value
        if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return

        val prompt = QueuedPrompt(
            id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
            attachments = pendingAttachments,
        )
        _promptQueue.value = _promptQueue.value + prompt

        val attachmentNames = pendingAttachments.map { it.fileName }
        val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
        val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
        val chatMsg = ChatMessage(
            id = "queued_msg_${prompt.id}",
            role = "user",
            content = trimmed,
            imageUris = imageUris,
            attachmentNames = attachmentNames,
            attachmentUris = attachmentUris,
            isQueued = true,
            queuedPromptId = prompt.id,
        )
        _messages.value = _messages.value + chatMsg
        clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments), queue=${_promptQueue.value.size}")
    }

    /** Remove a queued prompt and its chat message by prompt id. */
    fun removeQueuedPrompt(promptId: String) {
        _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
        _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
    }

    /** Withdraw a queued message before it gets injected into the agent loop. */
    fun withdrawQueuedMessage(messageId: String) {
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.isQueued) return
        val pid = msg.queuedPromptId ?: return
        _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
        _messages.value = _messages.value.filterNot { it.id == messageId }
        Log.i(TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
    }

    /**
     * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
     * interrupt: take everything in [_promptQueue] right now, finalize the
     * just-finished assistant bubble in the UI, persist a fresh user
     * message carrying the queued text + attachments, append an assistant
     * "bridge" entry into [agentHistory] (so Anthropic's
     * mergeConsecutiveSameRole doesn't fold the queued user msg into the
     * preceding tool_result), and spawn a new assistant placeholder for
     * the next iteration's response.
     *
     * Returns an [InjectedTurn] carrying the new assistantId (which the
     * caller swaps into its loop-scope `assistantId` before `continue`-ing
     * the agent loop), or `null` if every queued prompt was empty after
     * attachment processing (caller falls through to a normal next-turn
     * dispatch in that case).
     *
     * Mirrors iOS `injectQueuedPromptsAsNewTurn`
     * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
     * entry — its sole purpose is to break up the consecutive-user run for
     * the next API call; chat history reconstruction would just hide it.
     */
    private data class InjectedTurn(val newAssistantId: String)

    private suspend fun injectQueuedPromptsAsNewTurn(
        finishedAssistantId: String,
        finishedAccumulatedText: String,
        finishedAllToolBlocks: List<AssistantBlock>,
    ): InjectedTurn? {
        if (_promptQueue.value.isEmpty()) return null
        val queued = _promptQueue.value
        _promptQueue.value = emptyList()

        // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
        // placeholder bubbles (the ones enqueuePrompt added with
        // id="queued_msg_…") for the prompts we're injecting. Step (c) below
        // appends a single combined user bubble (id=userEntity.id) for the same
        // text — so flipping isQueued=false and KEEPING the placeholders (the
        // old behaviour) rendered the message TWICE: once as the un-queued
        // placeholder, once as the injected bubble. drainQueuedPrompts reuses
        // its placeholders and never re-appends, so it didn't dupe; this mid-
        // loop inject path appends a fresh bubble, so the placeholders must go.
        val queuedIds = queued.map { it.id }.toSet()
        val msgsAfterUnqueue = _messages.value.filterNot { m ->
            m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
        }

        // Build the combined user message from all queued prompts.
        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)

        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            if (prompt.text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(prompt.text)
                combinedParts.add(AgentContentPart.Text(prompt.text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
            combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        // Guard: every queued prompt produced no content (no text, no
        // image). An empty user msg is a 400 from every provider. Skip —
        // the caller falls through to a normal next-turn dispatch so the
        // loop doesn't spin.
        if (combinedParts.isEmpty()) {
            AppLogger.warning(
                TAG_STREAM,
                "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
            )
            return null
        }

        // Bridge entry into agentHistory ONLY (not persisted). The tail
        // before this call is user(tool_result); without the bridge the
        // queued user message becomes two consecutive user roles and the
        // provider merges them — exactly the regression iOS hit at #579.
        // Empty/whitespace-only bridge text would itself be merged out by
        // some sanitizers; keep a small visible string for parity with iOS.
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
                contentParts = listOf(
                    AgentContentPart.Text("(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"),
                ),
            ),
        )

        // Persist the queued user message as its own DB row + append to
        // agentHistory so the next API call carries it.
        val userText = combinedText.toString()
        // [T-android-paste-mediaref] Queued prompts carry markers too.
        //
        // sendMessage hands off to enqueuePrompt whenever a turn is already
        // streaming, and it no longer expands markers before doing so — so
        // without this the queued row would persist a literal `[Pasted#3]` and
        // the model would receive the marker instead of the text.
        val queuedPaste = buildPastedParts(userText, sid)
        if (queuedPaste != null) {
            _pastedTexts.value = _pastedTexts.value.filterNot { it.id in queuedPaste.consumedIds }
        }
        val userPartsJson = buildUserPartsJson(
            userText,
            prepared.mediaRefPartsJson,
            prepared.attachedFilesXml,
            bodyPartsJson = queuedPaste?.partsJson,
        )
        val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                // Expanded for the model; the persisted row above stays small.
                content = queuedPaste?.modelText ?: userText,
                imageParts = prepared.imageParts,
                contentParts = queuedPaste?.let { p ->
                    // Replace the whole LEADING RUN of text parts with the one
                    // expanded body, then keep everything after it.
                    //
                    // Not "index 0": each queued prompt contributes its own text
                    // part, and combinedText joined them with blank lines —
                    // p.modelText is the expansion of that join, so it stands
                    // for all of them. The image and <user-attached-files> parts
                    // that follow must survive untouched.
                    val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                    listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
                } ?: combinedParts,
                dbMessageId = userEntity.id,
            ),
        )

        // Finalize the just-finished assistant bubble in the UI on Main:
        // (a) un-queue the queued chat bubbles, (b) flush the side-channel
        // delta into the canonical row and clear isStreaming /
        // isAwaitingModelResponse, then (c) append the freshly-created
        // queued user ChatMessage + a NEW empty assistant placeholder so
        // the next iteration's streaming writes target the new bubble.
        val newAssistantId = "assistant_${System.currentTimeMillis()}"
        withContext(Dispatchers.Main) {
            // (a) + (b) one emit: build the post-finalize list.
            _messages.value = msgsAfterUnqueue
            updateAssistantMessage(
                finishedAssistantId,
                finishedAccumulatedText,
                false,
                finishedAllToolBlocks,
                isAwaitingModelResponse = false,
            )
            // (c) — append the queued user bubble + the new assistant
            // placeholder. Mirrors sendMessage's user-bubble append shape so
            // attachments / images / file chips render the same.
            val queuedUserMsg = ChatMessage(
                id = userEntity.id,
                role = "user",
                content = userText,
                imageUris = prepared.imageUris,
                attachmentNames = prepared.attachmentNames,
                attachmentUris = prepared.nonImageUris,
            )
            val nextAssistantMsg = ChatMessage(
                id = newAssistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = _thinkingLevel.value,
            )
            _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
            // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
            // ScrollPin send-grace window) is updated reactively by
            // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
            // when messages.size grows — appending the queuedUserMsg above
            // bumps the size, so the pin window opens just like a normal
            // send. No direct write needed from here (and we couldn't —
            // `lastUserAppendMs` lives in ChatScreen's composition scope).
        }

        AppLogger.info(
            TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
                "finishedId=$finishedAssistantId newId=$newAssistantId",
        )
        return InjectedTurn(newAssistantId)
    }

    /**
     * Drain queued prompts after an agent loop finishes. Each queued prompt is
     * appended to agentHistory, persisted, and re-runs the agent loop.
     * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
     */
    private suspend fun drainQueuedPrompts(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate>,
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy,
    ) {
        while (_promptQueue.value.isNotEmpty()) {
            val queued = _promptQueue.value
            _promptQueue.value = emptyList()
            Log.i(TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
                queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

            // Flip isQueued=false on corresponding chat messages so they render as sent.
            // T189: also clear queuedPromptId so a later retry of this bubble
            // doesn't try to drop a phantom queue entry (and so the field state
            // matches what retryFromMessage's truncate path now produces).
            val queuedIds = queued.map { it.id }.toSet()
            _messages.value = _messages.value.map { m ->
                if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                    m.copy(isQueued = false, queuedPromptId = null)
                } else m
            }

            // Build a combined user message (text + images from all queued prompts).
            // Persist as a single row.
            val sid = ensureSession()
            val combinedAttachments = queued.flatMap { it.attachments }
            val prepared = prepareUserAttachments(combinedAttachments, sid)

            // T132: same shape as sendMessage — caption(s) first, then for each
            // image emit "[attached image: <path>]" + ImageData, finally the
            // <user-attached-files> XML. Keeps caption adjacent to image and
            // lets the agent re-read the file via read_image.
            val combinedParts = mutableListOf<AgentContentPart>()
            val combinedText = StringBuilder()
            for (prompt in queued) {
                if (prompt.text.isNotEmpty()) {
                    if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                    combinedText.append(prompt.text)
                    combinedParts.add(AgentContentPart.Text(prompt.text))
                }
            }
            prepared.imageParts.forEachIndexed { idx, part ->
                val path = prepared.imageUploadPaths.getOrNull(idx)
                if (path != null) combinedParts.add(AgentContentPart.Text("[attached image: $path]"))
                combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
            }
            prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

            val userText = combinedText.toString()
            // [T-android-paste-mediaref] Same marker handling as the mid-loop
            // inject path above — see the note there for why queued prompts
            // need it at all.
            val drainPaste = buildPastedParts(userText, sid)
            if (drainPaste != null) {
                _pastedTexts.value =
                    _pastedTexts.value.filterNot { it.id in drainPaste.consumedIds }
            }
            val userPartsJson = buildUserPartsJson(
                userText,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = drainPaste?.partsJson,
            )
            chatRepository.appendMessage(sid, "user", userPartsJson)

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = drainPaste?.modelText ?: userText,
                imageParts = prepared.imageParts,
                contentParts = drainPaste?.let { p ->
                    val bodyCount = combinedParts.takeWhile { it is AgentContentPart.Text }.size
                    listOf(AgentContentPart.Text(p.modelText)) + combinedParts.drop(bodyCount)
                } ?: combinedParts,
            ))

            try {
                runAgentLoop(
                    provider = provider,
                    systemPrompt = systemPrompt,
                    fallbackProviders = fallbackProviders,
                    fallbackStrategy = fallbackStrategy,
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "Agent loop (queued-drain) cancelled")
                // Cancel mid-drain: cancelStream() will check _promptQueue
                // and call resumeQueueAfterCancel() if anything's still pending,
                // so just propagate.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop (queued-drain) error", e)
                setInlineError(e.message ?: "Unknown error")
                break
            }
        }
    }

    fun sendMessage(text: String) = sendMessage(text, skipContextCheck = false)

    /**
     * @param skipContextCheck set by the pre-send context dialog's own actions,
     *   which have already made the compact decision. Without it the re-entrant
     *   send would re-evaluate the same (still stale until the next usage
     *   chunk) token count and pop the dialog again — iOS guards the identical
     *   re-entry with `skipCompactCheck`.
     */
    private fun sendMessage(text: String, skipContextCheck: Boolean) {
        // [T-android-paste-mediaref] `[Pasted#N]` markers are NOT expanded here
        // any more.
        //
        // They used to be: this funnel substituted the full text inline, so the
        // persisted message held one enormous `text` part. That is the same
        // shape that made huge tool results freeze the app — every time the
        // bubble scrolled into view, TextKit had to lay out the whole block on
        // the main thread. The markers now survive down to the parts-building
        // step below, where each becomes its own `text/plain` mediaRef; the full
        // content is re-attached to the REQUEST from disk (see toLLMMessage and
        // the fresh-send contentParts), so the model still sees everything while
        // the bubble stays small.
        //
        // The buffer is cleared where the mediaRefs are actually written, not
        // here — clearing at this point would strip the content out from under
        // a send that then bails on the context-check paths below.
        val trimmed = text.trim()
        // While streaming, enqueue instead of silently dropping (iOS: send vs enqueuePrompt).
        if (_isStreaming.value) {
            enqueuePrompt(text)
            return
        }
        // T180: allow attachments-only sends (no caption). Mirrors iOS, where
        // an empty text + non-empty attachments still produces a valid user
        // message. Without this an image-only "look at this" send dropped.
        if (trimmed.isBlank() && _attachments.value.isEmpty()) return
        if (_isCompacting.value) {
            appendSystemInfo(
                text = "Wait for the current compact to finish before sending.",
                iconKind = "compact",
            )
            return
        }
        // Context pressure check. Unlike before, needsCompact now HOLDS the
        // send: either compact silently (auto-compact on) or ask first. The
        // whole point is that the request which tripped the threshold must not
        // be the one that goes out over-length.
        if (!skipContextCheck) {
            when (checkContextBeforeSend()) {
                PreSendContextAction.PROCEED -> {}
                PreSendContextAction.COMPACT_THEN_SEND -> {
                    pendingSendText = text
                    _inputText.value = ""
                    compactAndSendPending()
                    return
                }
                PreSendContextAction.ASK_USER -> {
                    // Park the text on the VM (not the composer) so the dialog
                    // owns it; cancelCompactBeforeSend puts it back.
                    pendingSendText = text
                    _inputText.value = ""
                    _showCompactBeforeSendPrompt.value = true
                    return
                }
            }
        }
        // A fresh send supersedes any pending resume — mirror iOS which clears
        // canResume at the top of send().
        _canResume.value = false
        // T185: clear the share-injected flag the moment the user actually
        // sends. Without this, the "Move to…" capsule (gated on
        // hasInjectedShareContent) keeps floating over the user-message row
        // after the share content has been committed — it then visually
        // collides with the user-attachment chips, which renders as the
        // "image attachment shows up as Move to" symptom in T185. Mirrors
        // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
        // inside the send button's tap closure).
        if (_hasInjectedShareContent.value) _hasInjectedShareContent.value = false

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        var provider: LLMProvider = initialProvider

        _error.value = null

        val currentAttachments = _attachments.value
        clearAttachments()

        // T145: claim _isStreaming synchronously so a rapid second tap can't
        // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
        AppLogger.info(TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send
        // only reaches here when no turn is streaming (the _isStreaming guard
        // at the top routes mid-stream sends to enqueuePrompt). So any residual
        // _streamingById entry is an orphan stranded by a prior turn that
        // exited without draining it (e.g. a late delta re-added the entry
        // after finalizeAtTurnLimit / cancel cleared it). mergeStreamingOverlay
        // forces isStreaming=true on any message holding such an entry, so an
        // orphan would render a second "thinking" row alongside the new turn's.
        // Flush them into the canonical messages (isStreaming=false) before the
        // new streaming message is created — no two messages ever stream at once.
        if (_streamingById.value.isNotEmpty()) {
            AppLogger.warning(TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
            flushAllStreamingDeltas()
        }

        // T187: when the user is editing a previous message, truncate the
        // conversation from that message (inclusive) before persisting the
        // edited text as a fresh user turn. Snapshot + clear the id here so
        // any error in the truncate path doesn't leave the composer stuck
        // in edit mode.
        val editingId = _editingMessageId.value
        if (editingId != null) _editingMessageId.value = null

        viewModelScope.launch {
            var streamLaunched = false
            try {
            // Ensure session exists in DB (creates on first message for draft sessions)
            val activeSessionId = ensureSession()

            if (editingId != null) {
                truncateBeforeEdit(editingId)
            }

            val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

            // [T-android-paste-mediaref] Fold `[Pasted#N]` markers out to disk
            // BEFORE persisting, so the stored message carries a mediaRef per
            // paste instead of one huge text part.
            val pasted = buildPastedParts(trimmed, activeSessionId)
            if (pasted != null) {
                // Safe to clear now: the content is on disk and the parts JSON
                // below references it, so nothing depends on the buffer any more.
                _pastedTexts.value = _pastedTexts.value.filterNot { it.id in pasted.consumedIds }
            }

            // Save user message — text + persisted mediaRef parts so images survive
            // a session reload (T128). Non-image attachments still only contribute
            // their name (rendered as a file tile) and are not persisted.
            val userPartsJson = buildUserPartsJson(
                trimmed,
                prepared.mediaRefPartsJson,
                prepared.attachedFilesXml,
                bodyPartsJson = pasted?.partsJson,
            )
            val persistedUser = chatRepository.appendMessage(activeSessionId, "user", userPartsJson)

            val userMsg = ChatMessage(
                id = persistedUser.id,
                role = "user",
                // The bubble shows the SHORT body with markers removed; the
                // pasted blocks appear beside it as file cards (below).
                // Strip the consumed markers from the visible caption — the
                // file cards now stand for them. Only ids that actually
                // resolved are removed, so a literal the user typed for an
                // unknown id survives as text, matching how it is persisted.
                content = pasted?.let { p ->
                    p.consumedIds.fold(trimmed) { acc, id ->
                        acc.replace(PastedText.placeholderFor(id), "")
                    }.trim()
                } ?: trimmed,
                imageUris = prepared.imageUris,
                // Pasted blocks render exactly like attached documents: append
                // them to the non-image suffix, preserving the
                // images-first/files-after ordering that
                // ChatMessage.attachmentNames depends on.
                attachmentNames = prepared.attachmentNames + (pasted?.uiNames ?: emptyList()),
                attachmentUris = prepared.nonImageUris + (pasted?.uiUris ?: emptyList()),
            )
            _messages.value = _messages.value + userMsg
            val imageParts = prepared.imageParts

            // T132: build the user contentParts in iOS order — caption first
            // (only if non-empty), then per image emit
            //   text("[attached image: /var/minis/attachments/uploads/<f>]")
            //   ImageData(<bytes>, <mime>)
            // so the caption sits adjacent to the image in the wire payload,
            // and the agent's read_image tool can resolve the same path back
            // to bytes. Trailing <user-attached-files> XML block lets the
            // model see filenames/sizes without needing tool calls.
            // [T-android-paste-mediaref] The MODEL gets the fully expanded body
            // even though the bubble and the DB row do not. This is the whole
            // point of the split: local rendering stays cheap, the prompt is
            // unchanged from what it used to be.
            //
            // On later turns the same expansion is rebuilt from disk by
            // toLLMMessage's mediaRef branch, so history replay (retry, rerun,
            // session reload, compaction) sees the identical text.
            val modelBody = pasted?.modelText ?: trimmed

            val userContentParts = mutableListOf<AgentContentPart>()
            if (modelBody.isNotEmpty()) userContentParts.add(AgentContentPart.Text(modelBody))
            imageParts.forEachIndexed { idx, part ->
                val path = prepared.imageUploadPaths.getOrNull(idx)
                if (path != null) userContentParts.add(AgentContentPart.Text("[attached image: $path]"))
                userContentParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
            }
            prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = modelBody,
                imageParts = imageParts,
                contentParts = userContentParts,
                dbMessageId = persistedUser.id,
            ))

            // Refresh OAuth token if needed before sending (mirrors iOS validAccessToken)
            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                try {
                    val activeEntryId = _activeEntryId.value
                    val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                    val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                    if (instance != null) {
                        val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                        val freshToken = manager?.validAccessToken()
                        if (freshToken != null) {
                            val storedKey = providerRepository.loadApiKey(instance.id)
                            if (freshToken != storedKey) {
                                providerRepository.saveApiKey(instance.id, freshToken)
                                // Recreate provider with fresh token
                                provider = com.openminis.app.provider.ProviderFactory.create(
                                    instance, freshToken, currentModel ?: provider.model, context
                                )
                                currentProvider = provider
                                android.util.Log.i(TAG, "OAuth token refreshed before send")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "OAuth token refresh failed: ${e.message}")
                }
            }

            // Build system prompt
            // Anthropic OAuth requires the Claude Code prefix in the system prompt
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

            // Start agent loop with fallback. _isStreaming was set synchronously at top.
            streamLaunched = true
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "send streamJob ENTER sid=$activeSessionId")
                try {
                    // [T-STALL-DIAG] Snapshot BEFORE the (possibly blocking)
                    // acquire. If the log shows this line and then no "slot
                    // acquired", the turn is parked in acquireSlot — and this
                    // line already records who was holding the slots, so the
                    // leak is diagnosable from a single log.
                    println(
                        "[T-STALL-DIAG] send PRE-ACQUIRE sid=$activeSessionId " +
                            SessionConcurrencyManager.diagSnapshot(),
                    )
                    // Acquire concurrency slot (suspends if at max)
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "send streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                    // Resolve the active group's fallback strategy
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }

                    // Build full fallback provider list upfront (mirrors iOS triedEntries approach)
                    val fallbackProviders = buildFallbackProviders(provider)

                    try {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "send runAgentLoop RETURN normal")
                        // Drain any prompts the user queued while this loop was running.
                        // Skipped on cancel: cancelled job won't reach here.
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "send drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "send runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "send runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (all fallbacks exhausted)", e)
                        setInlineError(e.message ?: "Unknown error")
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } finally {
                        AppLogger.info(TAG_STREAM, "send streamJob FINALLY enter")
                        // [T-android-overlay-reply-status-34599] Surface
                        // the assistant's most recent reply text to the
                        // overlay BEFORE setInactive so the post-completion
                        // overlay state (no-running, has-outcome) carries a
                        // non-null excerpt. Reading _messages here is safe:
                        // we're in the finally block of the agent loop and
                        // the stream has already flushed its last delta.
                        publishOverlayReplyExcerpt(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "send streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard — see
                // `var streamJob` KDoc; identical pattern as runRerunStreamTail.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "send _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "send _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "send streamJob EXIT")
            }
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "send _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    /** Set error inline on the last assistant message (iOS: message.error).
     *
     *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
     *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
     *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
     *  on screen even though streaming is over. The flag is per-message and
     *  is not implicitly cleared by isStreaming=false. */
    private fun setInlineError(errorText: String) {
        // [T-error-persist-android] Never let an empty/blank error string reach
        // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
        // would render an EMPTY error banner, and (now that errors persist) it
        // would stick across reloads. An exception with a blank `message`
        // (`e.message ?: "Unknown error"` only guards null, not "") is the
        // realistic source. Coalesce to a generic non-empty message.
        val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
        // T-streaming-side-channel: before mutating the canonical message,
        // drain any in-flight streaming delta so the error frame carries
        // the actual accumulated content (otherwise the user sees content
        // snap back to a pre-stream prefix when the error banner appears).
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            val msg = msgs[lastAssistantIdx]
            msgs[lastAssistantIdx] = msg.copy(
                error = safeError,
                isStreaming = false,
                isAwaitingModelResponse = false,
            )
            _messages.value = msgs
            // [T-error-persist-android] Persist the terminal error onto the
            // session's last assistant DB row so the inline error + Retry button
            // survive a session reload. This is a targeted UPDATE (not a fresh
            // insert): the in-memory bubble id differs from the persisted row id,
            // so we address the row by "last assistant" — matching the load-side
            // merge that keeps the last assistant row's identity. No-op when the
            // failing turn never persisted a row (first-turn failure).
            val sid = realSessionId.ifEmpty { sessionId }
            if (sid.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try { chatRepository.updateLastAssistantError(sid, safeError) }
                    catch (e: Exception) { Log.w(TAG, "persist error_info failed: ${e.message}") }
                }
            }
        } else {
            // No assistant message yet — fall back to top-level error
            _error.value = safeError
        }
    }

    /**
     * Show a transient error on the last assistant message while keeping isStreaming=true
     * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
     * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
     */
    private fun setTransientInlineError(errorText: String) {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(error = errorText)
        _messages.value = msgs
    }

    /** Clear any inline error on the last assistant message (used after successful retry). */
    private fun clearInlineError() {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        if (msg.error == null) return
        msgs[lastAssistantIdx] = msg.copy(error = null)
        _messages.value = msgs
        // [T-error-persist-android] Clear the persisted sticker too, so a
        // recovered turn doesn't resurrect the error banner on the next reload.
        // Clear by the message's source DB rows when known (the in-memory bubble
        // maps to one or more persisted rows via sourceDbIds); fall back to the
        // last-assistant-row update otherwise.
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            val dbIds = msg.sourceDbIds
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (dbIds.isNotEmpty()) {
                        dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                    } else {
                        chatRepository.updateLastAssistantError(sid, null)
                    }
                } catch (e: Exception) { Log.w(TAG, "clear error_info failed: ${e.message}") }
            }
        }
    }

    /**
     * [T-error-persist-android] Fire-and-forget: clear the persisted error
     * sticker on the session's last assistant row. Called from the resume / retry
     * entrypoints that drop the in-memory error but don't go through
     * [clearInlineError], so a recovered turn can't merge-resurrect the old
     * banner on the next reload. No-op when there's no session/row yet.
     */
    private fun clearPersistedLastAssistantError() {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { chatRepository.updateLastAssistantError(sid, null) }
            catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
        }
    }

    /** Retry the last agent turn (triggered by inline error Retry button).
     *
     *  T258: ports iOS AIChatViewModel.retry() (AIChatViewModel.swift:2079).
     *  Earlier behaviour blew away the entire failed assistant ChatMessage —
     *  including its already-completed tool_use cards — and reset
     *  agentHistory back to the last "real" user message, so on Retry every
     *  succeeded tool re-executed from scratch (the bug the user reported).
     *
     *  New behaviour:
     *   - Keep the assistant ChatMessage in the UI; clear its error sticker
     *     and the streaming/awaiting flags. Drop only tool blocks still in
     *     STREAMING / PENDING / RUNNING state — those have no matching
     *     tool_result and would orphan the request body.
     *   - From agentHistory, pop ONLY a trailing assistant entry (i.e. the
     *     turn whose stream errored). If the tail is already user(tool_result),
     *     the failure happened on the NEXT LLM call before any output —
     *     history is already valid, leave it.
     *   - GC orphaned tool_result rows whose tool_use is no longer in
     *     agentHistory (defends against the API "unexpected tool_use_id" 400).
     *   - Sync the DB: if we popped a trailing assistant, drop just its
     *     persisted row so a re-load doesn't resurrect the failed turn.
     */
    fun retryLast() {
        if (_isStreaming.value) return
        // T-streaming-side-channel: belt-and-suspenders flush in case any
        // delta survived an earlier abnormal exit; retryLast is gated on
        // !isStreaming so this is normally a no-op.
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)

        // 1. Keep the assistant message; clear error + streaming flags + drop
        //    in-flight tool blocks (STREAMING args / PENDING dispatch /
        //    RUNNING execution all have no tool_result, so they'd orphan).
        val lastMsg = msgs[lastAssistantIdx]
        val keptToolBlocks = lastMsg.toolBlocks.filter { block ->
            block.toolStatus !in IN_FLIGHT_TOOL_STATUSES
        }
        msgs[lastAssistantIdx] = lastMsg.copy(
            error = null,
            isStreaming = false,
            isAwaitingModelResponse = false,
            toolBlocks = keptToolBlocks,
        )
        _messages.value = msgs
        // [T-error-persist-android] Clear the persisted error sticker on the last
        // assistant row up-front. The DB-sync below only DELETES the trailing
        // assistant row when a trailing assistant was popped (Case A); in the
        // Case B path (tail = user(tool_result), next LLM call errored) the
        // stamped row is an EARLIER completed turn that is NOT deleted, so
        // without this clear the new successful turn would merge-resurrect the
        // old error banner on reload (msg.error ?: prev.error). Harmless in
        // Case A too — the row is deleted moments later regardless.
        clearPersistedLastAssistantError()

        // 2. Pop ONLY a trailing assistant entry from agentHistory (mirrors
        //    iOS retry() :2107-2109). If the tail is already user(tool_result),
        //    the next-turn LLM call errored — leave history alone.
        val poppedAssistant = if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            val last = agentHistory.removeAt(agentHistory.size - 1)
            last
        } else null

        // 3. GC orphaned tool_result parts whose tool_use is gone (mirrors
        //    iOS retry() :2114-2128). Walks backward so removeAt is safe.
        val liveToolUseIds = agentHistory.flatMap { m ->
            m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        }.toSet()
        for (i in agentHistory.indices.reversed()) {
            val m = agentHistory[i]
            if (m.role != LLMMessage.Role.USER) continue
            val cleanedParts = m.contentParts.filter { p ->
                p !is AgentContentPart.ToolResult || p.id in liveToolUseIds
            }
            when {
                cleanedParts.isEmpty() && m.contentParts.isNotEmpty() ->
                    agentHistory.removeAt(i)
                cleanedParts.size < m.contentParts.size ->
                    agentHistory[i] = m.copy(contentParts = cleanedParts)
            }
        }

        val initialProvider = currentProvider ?: return
        var provider: LLMProvider = initialProvider
        _error.value = null

        // T145: claim _isStreaming synchronously — see retryFromMessage for rationale.
        AppLogger.info(TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // T258: only sync the DB when step 2 popped a trailing assistant
            // entry from agentHistory. In that case the persisted partial-
            // assistant row would resurrect the failed turn on next session
            // load — drop it (and only it) by deleting from its sort_order.
            // Completed assistant + tool_result rows for earlier turns are
            // unchanged and stay persisted, so retry preserves their cards.
            // toolLoopDetector keeps its accumulated state — completed tools
            // shouldn't be unlearned just because the next turn errored.
            if (poppedAssistant != null) {
                val dbMessages = chatRepository.loadMessages(sid)
                val trailingAssistantSortOrder = dbMessages
                    .lastOrNull { it.role == "assistant" }?.sortOrder
                if (trailingAssistantSortOrder != null) {
                    chatRepository.deleteMessagesAfter(sid, trailingAssistantSortOrder)
                    AppLogger.info(
                        TAG_STREAM,
                        "retryLast: deleted trailing assistant row sortOrder=$trailingAssistantSortOrder, kept ${trailingAssistantSortOrder} prior rows",
                    )
                }
            } else {
                AppLogger.info(
                    TAG_STREAM,
                    "retryLast: agentHistory tail was user(tool_result) — no DB cleanup needed",
                )
            }

            // Refresh OAuth token if needed
            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                try {
                    val activeEntryId = _activeEntryId.value
                    val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                    val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                    if (instance != null) {
                        val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                        val freshToken = manager?.validAccessToken()
                        if (freshToken != null) {
                            val storedKey = providerRepository.loadApiKey(instance.id)
                            if (freshToken != storedKey) {
                                providerRepository.saveApiKey(instance.id, freshToken)
                                provider = ProviderFactory.create(instance, freshToken, currentModel ?: provider.model, context)
                                currentProvider = provider
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OAuth token refresh failed: ${e.message}")
                }
            }

            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

            // _isStreaming was already set synchronously at the top.
            streamLaunched = true
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
                try {
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "retryLast streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)
                    try {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "retryLast drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "retryLast runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "retryLast runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (retryLast)", e)
                        setInlineError(e.message ?: "Unknown error")
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } finally {
                        AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY enter")
                        // [T-android-overlay-reply-status-34599] Surface
                        // the assistant's most recent reply text to the
                        // overlay BEFORE setInactive so the post-completion
                        // overlay state (no-running, has-outcome) carries a
                        // non-null excerpt. Reading _messages here is safe:
                        // we're in the finally block of the agent loop and
                        // the stream has already flushed its last delta.
                        publishOverlayReplyExcerpt(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "retryLast streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "retryLast streamJob EXIT")
            }
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    /**
     * Unwrap exceptions thrown inside callbackFlow.
     * callbackFlow wraps internal throws into CancellationException(cause=original).
     * This extracts the original LLMError if present.
     */
    /**
     * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
     * Mirrors iOS AIChatViewModel pre-API validation.
     *
     * Ensures: every assistant message with tool_use is immediately followed by a user
     * message containing the matching tool_result(s). Handles:
     * - Duplicate tool IDs across messages (from provider fallback/retry)
     * - Orphaned tool_use without any tool_result
     * - Orphaned tool_result without matching tool_use
     * - Assistant text after tool_use in the same message (Anthropic rejects this)
     */
    private fun sanitizeAgentHistory() {
        // Walk through history sequentially, checking each assistant message.
        // For each assistant message with tool_use blocks, verify the NEXT message
        // is a user message with matching tool_result blocks. If not, inject them.
        var i = 0
        while (i < agentHistory.size) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.ASSISTANT) { i++; continue }

            val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
            if (toolUses.isEmpty()) { i++; continue }

            val toolUseIds = toolUses.map { it.id }.toSet()

            // Check next message for matching tool_results
            val next = agentHistory.getOrNull(i + 1)
            val nextResultIds = next?.contentParts
                ?.filterIsInstance<AgentContentPart.ToolResult>()
                ?.map { it.id }?.toSet() ?: emptySet()

            val missingIds = toolUseIds - nextResultIds
            if (missingIds.isEmpty()) { i++; continue }

            // Some tool_uses have no matching tool_result in the next message.
            // If next message is a user message, add the missing results to it.
            // Otherwise, inject a new user message with placeholder results.
            val placeholders = toolUses.filter { it.id in missingIds }.map { use ->
                AgentContentPart.ToolResult(
                    id = use.id, name = use.name,
                    content = "Tool execution was interrupted by an unexpected error.",
                    isError = true,
                )
            }
            Log.w(TAG, "sanitize: injecting ${placeholders.size} placeholder tool_result(s) after history[$i]")

            if (next != null && next.role == LLMMessage.Role.USER &&
                next.contentParts.any { it is AgentContentPart.ToolResult }) {
                // Append missing results to the existing user message
                agentHistory[i + 1] = next.copy(
                    contentParts = next.contentParts + placeholders
                )
            } else {
                // Insert a new user message with just the placeholder results
                agentHistory.add(i + 1, LLMMessage(
                    role = LLMMessage.Role.USER, content = "",
                    contentParts = placeholders,
                ))
            }
            i++
        }

        // Remove orphaned tool_results (result IDs not found in any tool_use)
        val allToolUseIds = agentHistory.flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
        val iter = agentHistory.listIterator()
        while (iter.hasNext()) {
            val msg = iter.next()
            if (msg.role != LLMMessage.Role.USER) continue
            val cleaned = msg.contentParts.filter { part ->
                part !is AgentContentPart.ToolResult || part.id in allToolUseIds
            }
            if (cleaned.isEmpty() && msg.content.isBlank()) {
                iter.remove()
            } else if (cleaned.size < msg.contentParts.size) {
                iter.set(msg.copy(contentParts = cleaned))
            }
        }
    }

    private fun unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    /**
     * Compute max output tokens that fits within the remaining context window.
     * Logic mirrors iOS's dynamicMaxTokens():
     *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
     *
     * @param provider The current LLM provider (carries defaultMaxTokens).
     * @param lastContextTokens API-reported input token count from the last call (0 = first call).
     */
    private fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
        val model = currentModel ?: return minOf(GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
        // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
        // The global cap means we never send more than 128K regardless of
        // what the model claims it can output.
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
        // Context window: model.contextWindow if known, else the shared
        // model-id heuristic. [T-anthropic-context-window] Route through
        // LLMModel.contextWindowTokens so the corrected Claude-1M / Gemini-1M
        // values apply here too, instead of the stale local "everything 200K"
        // copy that under-reported modern Claude/Gemini windows.
        val contextWindow = model.contextWindowTokens
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        val result = minOf(maxOutputCeiling, clamped)
        if (result < maxOutputCeiling) {
            android.util.Log.i(TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
        }
        return result
    }

    // ─── Context Window Offload ──────────────────────────────────────────────
    //
    // Mirrors iOS `AIChatViewModel.swift`:
    //   - estimateContextTokens()        (line 7451)
    //   - offloadContextIfNeeded()       (line 7481)
    // Per-tool writers live in [com.openminis.app.data.ContextOffload].
    //
    // The agent loop calls [offloadContextIfNeeded] once per turn just before
    // the next API call. When token usage crosses the policy threshold, large
    // tool outputs in older messages are written to disk under
    // `filesDir/minis-sessions/<sid>/offloads/tools/` and replaced in
    // [agentHistory] by `[CONTEXT OFFLOADED] … <linux path>` stubs. The model
    // can later `file_read` the path to retrieve the original content.
    //
    // Why this matters: without offloading, a session that runs many large
    // shell tools fills the context window and either trips compact (lossy)
    // or hits the model's context-exhausted error. Offload is lossless —
    // the data still exists, just on disk instead of in-prompt.

    /**
     * Char-based fallback estimate when the API hasn't reported a token
     * baseline yet (first call in a turn). Mirrors iOS line 7451.
     *
     * Uses ~3.5 chars per token for mixed text + adds the tokenizer's
     * image-aware count for image bytes. Underestimates JSON-heavy tool
     * inputs slightly but is adequate as a "should we offload" gate —
     * offload itself uses precise [BPETokenizer.countTokens] per-part
     * for the candidate ranking.
     */
    private fun estimateContextTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    /**
     * Approximate token count for a single agent content part. Used to rank
     * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
     * — text uses BPE, images use the grid-cell heuristic.
     */
    private fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }

    /**
     * Offload candidate descriptor. `msgIdx` and `partIdx` index back into
     * [agentHistory] so we can mutate the part in place after writing the
     * stub to disk.
     */
    private data class OffloadCandidate(
        val msgIdx: Int,
        val partIdx: Int,
        val tokens: Int,
        val bytes: Int,
        val toolId: String,
        val toolName: String,
    )

    /**
     * Walk [agentHistory], identify large tool outputs in the older
     * (non-protected) message range, and offload the highest-token ones to
     * disk until we're back under [ContextPolicy.offloadTarget]. Mirrors iOS
     * `offloadContextIfNeeded(model:lastContextTokens:force:)` (line 7481).
     *
     * Protection rules (parity with iOS line 7535):
     *   - Last 4 messages are never offloaded — the model needs them
     *     verbatim to plan the current turn coherently.
     *   - Already-offloaded parts (prefix [ContextOffload.OFFLOADED_PREFIX])
     *     are skipped — second pass would rewrite the stub uselessly.
     *
     * Eligibility (parity with iOS lines 7556-7596):
     *   - `ToolResult` with content > 500 chars OR image data > 1 KB
     *   - `ToolUse` for `file_write` / `file_edit` whose `content` arg > 500 chars
     *   - bare `ImageData` part > 1 KB
     *
     * Candidates are sorted by token count descending and offloaded greedily
     * until current usage drops below [policy.offloadTarget] (or all
     * candidates are exhausted). When [force] is true, all eligible
     * candidates are offloaded regardless of remaining headroom — used by
     * post-compact code paths to slim down the kept-tail aggressively.
     */
    private fun offloadContextIfNeeded(
        contextWindow: Int,
        lastContextTokens: Int,
        force: Boolean = false,
    ) {
        val sid = activeSessionId
        val policy = ContextPolicy.forContextWindow(contextWindow)

        if (!force && policy.offloadThreshold == 0) {
            // Small-window tier: offload disabled — UI surfaces "exhausted"
            // when the user crosses the threshold. Nothing to do here.
            return
        }

        val effectiveTokens =
            if (lastContextTokens > 0) lastContextTokens else estimateContextTokens()

        if (!force && effectiveTokens < policy.offloadThreshold) {
            // Below threshold — no work needed. Caller logs at debug level
            // via dynamicMaxTokens; we stay silent to keep logs readable.
            return
        }

        val targetTokens = if (force) 0 else policy.offloadTarget
        val beforeTokens = effectiveTokens
        var currentTokens = effectiveTokens
        val pct = (effectiveTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        val remaining = contextWindow - beforeTokens

        AppLogger.info(TAG, "━━━ Context Offload Triggered ━━━")
        AppLogger.info(TAG, "  Window: $contextWindow tokens")
        AppLogger.info(TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
        if (force) {
            AppLogger.info(TAG, "  Mode: FORCE — offloading all eligible candidates")
        } else {
            AppLogger.info(TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
            AppLogger.info(TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
        }
        AppLogger.info(TAG, "  Agent history: ${agentHistory.size} messages")

        val protectedCount = minOf(4, agentHistory.size)
        val candidateUpper = agentHistory.size - protectedCount
        AppLogger.info(TAG, "  Scanning messages 0..<$candidateUpper (last $protectedCount protected)")

        val candidates = mutableListOf<OffloadCandidate>()
        var skippedAlreadyOffloaded = 0
        var skippedTooSmall = 0

        for (msgIdx in 0 until candidateUpper) {
            val msg = agentHistory[msgIdx]
            for ((partIdx, part) in msg.contentParts.withIndex()) {
                when (part) {
                    is AgentContentPart.ToolResult -> {
                        if (part.content.startsWith(ContextOffload.OFFLOADED_PREFIX)) {
                            skippedAlreadyOffloaded++
                            continue
                        }
                        val hasLargeContent = part.content.length > 500
                        val hasLargeImage = (part.imageData?.size ?: 0) > 1024
                        if (!hasLargeContent && !hasLargeImage) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = countPartTokens(part)
                        val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                            (part.imageData?.size ?: 0)
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ToolUse -> {
                        if (part.name != "file_write" && part.name != "file_edit") continue
                        val content = part.input.optString("content", "")
                        if (content.length <= 500) continue
                        val tokens = countPartTokens(part)
                        val bytes = content.toByteArray(Charsets.UTF_8).size
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                    }
                    is AgentContentPart.ImageData -> {
                        if (part.data.size <= 1024) {
                            skippedTooSmall++
                            continue
                        }
                        val tokens = countPartTokens(part)
                        // Synthesize a tool id since bare images don't carry one.
                        val synthId = "img${msgIdx}_$partIdx"
                        candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                    }
                    is AgentContentPart.Text -> Unit
                }
            }
        }

        candidates.sortByDescending { it.tokens }
        val totalCandidateTokens = candidates.sumOf { it.tokens }
        AppLogger.info(TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens tokens total)")
        AppLogger.info(TAG, "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small")

        var offloadedCount = 0
        var freedTokens = 0

        for (candidate in candidates) {
            if (currentTokens <= targetTokens) break

            val msg = agentHistory[candidate.msgIdx]
            val parts = msg.contentParts.toMutableList()
            val part = parts[candidate.partIdx]
            var linuxPath = ""

            val newPart: AgentContentPart? = when (part) {
                is AgentContentPart.ToolResult -> {
                    if (part.content.length > 500) {
                        linuxPath = ContextOffload.offloadContent(
                            context, sid, part.content,
                            toolId = part.id, toolName = part.name,
                        )
                    }
                    val imgPath = part.imageData?.let { data ->
                        if (data.size > 1024) {
                            ContextOffload.offloadImage(
                                context, sid, data,
                                toolId = part.id,
                                mimeType = part.imageMimeType ?: "image/png",
                            )
                        } else ""
                    } ?: ""
                    if (linuxPath.isEmpty()) linuxPath = imgPath
                    val stub = ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath)
                    part.copy(content = stub, imageData = null, imageMimeType = null)
                }
                is AgentContentPart.ToolUse -> {
                    val content = part.input.optString("content", "")
                    linuxPath = ContextOffload.offloadContent(
                        context, sid, content,
                        toolId = part.id, toolName = part.name,
                    )
                    val newInput = org.json.JSONObject(part.input.toString())
                    newInput.put(
                        "content",
                        ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath),
                    )
                    part.copy(input = newInput)
                }
                is AgentContentPart.ImageData -> {
                    linuxPath = ContextOffload.offloadImage(
                        context, sid, part.data,
                        toolId = candidate.toolId,
                        mimeType = part.mimeType,
                    )
                    // Bare ImageData has no toolUseId pairing — replace with a
                    // text part carrying the stub. Mirrors iOS line 7653.
                    AgentContentPart.Text(
                        ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath),
                    )
                }
                is AgentContentPart.Text -> null
            }

            if (newPart == null) continue
            parts[candidate.partIdx] = newPart
            agentHistory[candidate.msgIdx] = msg.copy(contentParts = parts)

            currentTokens -= candidate.tokens
            freedTokens += candidate.tokens
            offloadedCount++
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(
                TAG,
                "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~${candidate.tokens} tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
            )
        }

        if (offloadedCount > 0) {
            val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
            AppLogger.info(TAG, "━━━ Context Offload Complete ━━━")
            AppLogger.info(TAG, "  Parts offloaded: $offloadedCount")
            AppLogger.info(TAG, "  Tokens freed: ~$freedTokens")
            AppLogger.info(TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
            AppLogger.info(TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
            AppLogger.info(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    private suspend fun runAgentLoop(
        provider: LLMProvider,
        systemPrompt: String?,
        fallbackProviders: List<FallbackCandidate> = emptyList(),
        fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
    ) {
        AppLogger.info(TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
        // [T-android-mem-probe-trust] Send-path context shape. The existing
        // `messages-shape` probe only runs on session LOAD, so the 2026-08-15
        // log described the session as it was opened, never as it was sent —
        // and the send is where the memory goes. `historySize` alone says
        // nothing about payload: 17 messages carrying a 100 KB tool_result each
        // is a very different request from 1500 short ones. Logged once per
        // agent loop (not per turn) to stay cheap; the walk is O(parts) over
        // already-resident strings.
        runCatching {
            var chars = 0L
            var maxOne = 0
            var toolResults = 0
            var images = 0
            var imageBytes = 0L
            var audioChars = 0L
            var biggestRole = ""
            for (m in agentHistory) {
                var perMsg = m.content.length
                for (p in m.contentParts) {
                    when (p) {
                        is AgentContentPart.ToolResult -> {
                            perMsg += p.content.length
                            toolResults++
                            // Inline image bytes never reach the char count, so
                            // track them separately — an image-heavy request is
                            // a different failure shape from a text-heavy one.
                            p.imageData?.let { images++; imageBytes += it.size }
                        }
                        is AgentContentPart.Text -> perMsg += p.text.length
                        is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                        else -> {}
                    }
                }
                for (a in m.audioParts) audioChars += a.base64Data.length
                images += m.imageParts.size
                chars += perMsg
                if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
            }
            AppLogger.info(
                TAG_STREAM,
                "[CtxShape] historySize=${agentHistory.size} totalChars=$chars " +
                    "maxMsgChars=$maxOne maxMsgRole=$biggestRole toolResultParts=$toolResults " +
                    "imageParts=$images imageBytes=$imageBytes audioB64Chars=$audioChars " +
                    "approxTokens=${chars / 4} " +
                    "${com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()}",
            )
        }
        // [T-android-queued-message-interrupt-on-toolclose] `assistantId` is
        // normally a single message id for the whole agent loop (iOS-parity:
        // multiple tool/text turns folded into one bubble). It is reassigned
        // ONLY when a queued mid-loop prompt is injected as a new turn: the
        // just-finished bubble is sealed and a fresh assistantId starts so the
        // queued user message renders BETWEEN them. `allToolBlocks` and
        // `accumulatedText` are also reset at that point so the new bubble
        // starts empty and `buildTurnParts(allToolBlocks, turnStartBlockIndex,
        // toolInputMap)` continues to slice only the current turn's blocks
        // (turnStartBlockIndex is captured at iteration start to 0 after reset).
        var assistantId = "assistant_${System.currentTimeMillis()}"
        val allToolBlocks = mutableListOf<AssistantBlock>()
        // Per-tool ring of the most recent `accumulated` JSON snapshots emitted
        // by `LLMStreamChunk.ToolInputDelta`. Capped at TOOL_INPUT_CHUNK_RING_MAX
        // entries per tool id so memory stays bounded even on long streams.
        // The preflight validator below drains this on a blocked call so we
        // can reconstruct how the model assembled (or failed to assemble) the
        // args.
        val toolInputChunkRings: MutableMap<String, MutableList<String>> = mutableMapOf()
        var accumulatedText = ""
        var lastContextTokens = 0  // updated each turn from API usage

        // T94 fix 2: throttle text-delta UI updates to ~20fps (50ms).
        // Pre-T94 the LLMStreamChunk.Text branch hopped to Dispatchers.Main
        // for every chunk — Anthropic SSE on a slow turn fires 50-100 deltas
        // per second, each one triggering a full _messages.value reassignment
        // and a Compose recomposition of the whole chat list. The combined
        // Main-thread cost is what saturated the touch-event queue and
        // produced the "Waited 5001ms for MotionEvent" ANRs we saw on
        // host.example.com. We coalesce deltas in `pendingChunkText` and only
        // flip the UI on a 50ms timer; the per-stream end and per-retry
        // rollback paths flush whatever's pending so no characters are lost.
        // T256: tiered streaming throttle, mirrors iOS AIChatViewModel.swift
        // 6135-6155. The fixed 50ms window saturated the Pixel 4a UI thread
        // (95p frame 77ms / 29% janky). 6-segment ladder lets short replies
        // stay snappy (150ms ≈ 6.5 fps which is fine for <500-char snippets)
        // while long-form output (>32k chars) drops to 0.5-2s gates.
        // Newline fast-path keeps short messages flowing at human-readable
        // pace while still avoiding the per-token recompose storm.
        var lastUiUpdateMs = 0L
        var lastFlushedLen = 0
        // T307: per-delta String += chunk.text on Pixel-class heaps was O(n²)
        // — every SSE chunk allocated a fresh String the size of turnText so
        // far, then GC walked the entire char[]. DeepSeek V4 emitting long
        // multilingual + emoji turns blew past the 256 MB heap on Pixel 4a,
        // showing up as `AbstractStringBuilder.append:548` in
        // `ChatViewModel$runAgentLoop$5.emit`. Switch the three hot per-delta
        // accumulators (`pendingChunkText`, `turnText`, and the trailing
        // text-block's growing `content`) to StringBuilder so growth is
        // amortised O(n). Cross-turn `accumulatedText` is unaffected — it
        // grows per turn, not per delta.
        val pendingChunkSb = StringBuilder()
        // T256 tier 2: per-tool-kind input-delta gates. file_write/file_edit
        // pills churn JSON the user can't read anyway — 1Hz update is plenty;
        // other tools get 5Hz so command/url previews stay legible.
        var lastFileToolInputMs = 0L
        var lastOtherToolInputMs = 0L
        fun textDeltaThrottleMs(len: Int): Long = when {
            len < 500     -> 150L
            len < 2_000   -> 300L
            len < 32_000  -> 500L
            len < 64_000  -> 1_000L
            len < 128_000 -> 1_500L
            else          -> 2_000L
        }

        // Fallback state — mirrors iOS streamWithGroupFallback
        var currentProvider = provider
        val remainingFallbacks = fallbackProviders.toMutableList()
        val fallbackReasons = mutableListOf<String>()

        // Accumulate tool inputs across all turns (so persist includes all, not just current turn)
        val allToolInputs = mutableMapOf<String, String>()

        // Add placeholder assistant message (once). Mark as awaiting so the
        // "Minis is thinking" indicator shows during the initial request gap
        // before the first stream chunk arrives. Mirrors iOS isAwaitingModelResponse.
        // T300: snapshot the user's current thinking level at message
        // creation so the renderer can hide Deep Thinking blocks for
        // turns the user explicitly asked not to surface, even when a
        // forced-reasoning model still streams reasoning_content.
        val turnThinkingLevel = _thinkingLevel.value
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + ChatMessage(
                id = assistantId, role = "assistant", content = "", isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = turnThinkingLevel,
            )
        }

        // Tracks whether the loop was exited via a `break` (any reason — no
        // tool calls, msgIdx safety, etc.) or fell off the end of the range.
        // Set false by every break path that *isn't* "the model wanted to
        // keep going past MAX_AGENT_TURNS". Without this flag the post-loop
        // tail can't tell the runaway path apart from a normal turn ending,
        // which previously slapped a fake "200 turns hit" error on every
        // ordinary completion.
        var loopExitedNormally = false
        // [T-android-auto-compact-inloop] How many times the in-loop guard has
        // compacted during THIS runAgentLoop. Bounds compact-thrash: once the
        // cap is hit, a still-over-threshold history stops the turn rather than
        // compacting forever. Mirrors iOS maxInLoopCompactions.
        var inLoopCompactions = 0
        // [T-android-empty-after-toolresult-reminder] One-shot guard for the
        // "<system-reminder> + retry one round" recovery when the server returns
        // an empty response right after a tool result. Fires at most once per
        // runAgentLoop so it can never loop; if the reminder round is also empty
        // we surface a real error instead of a silent blank bubble. Mirrors iOS
        // AIChatViewModel.didInjectEmptyToolReminderThisRun.
        var didInjectEmptyToolReminder = false
        // [T-android-readaloud-stop-stale] One-shot per REPLY (not per turn):
        // the first text delta stops any Read Aloud still playing from the
        // previous reply. Scoped outside the turn loop so a tool-loop reply
        // that emits text across several turns doesn't re-fire it and cut off
        // its own speech mid-sentence.
        var didStopStaleReadAloud = false
        for (turn in 0 until MAX_AGENT_TURNS) {
            // Sanitize history before each API call (mirrors iOS pre-API validation)
            sanitizeAgentHistory()

            // Context window management: offload large tool outputs in older
            // messages to disk when the policy threshold for this model's
            // context window is crossed. Stubs in agentHistory still tell the
            // model where to file_read the original content. Mirrors iOS
            // AIChatViewModel.swift:4549.
            // [T-anthropic-context-window] Use contextWindowTokens (heuristic-
            // backed) instead of the raw nullable field, so offload triggers at
            // the correct fraction for heuristic-only Claude/Gemini models (1M)
            // rather than never firing when contextWindow is unset.
            // [T-context-window-live-read] Live read per loop turn — a stale
            // snapshot inside a long-running agent turn is exactly the iOS
            // fcc22b66 item-3 bug.
            effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
                offloadContextIfNeeded(
                    contextWindow = window,
                    lastContextTokens = lastContextTokens,
                )
            }

            // [T-android-auto-compact-inloop] In-loop context guard (iOS
            // f70ac173). checkContextBeforeSend only runs at the SEND entry
            // point, so a single turn that fans out into many tool iterations
            // could blow past the thresholds mid-loop. Offload alone can't
            // recover when the bulk is the model's own text, and the turn would
            // slam into the provider's context ceiling.
            //
            // Runs AFTER offload so it judges the post-offload size.
            when (inLoopContextCheck(inLoopCompactions)) {
                InLoopContextAction.PROCEED -> {}
                InLoopContextAction.COMPACTED -> {
                    // The next API call reads the freshly-compacted
                    // effectiveAgentHistory automatically — compaction already
                    // re-appends the recent turns, so no resume handoff is
                    // needed. A compaction iteration is space management, not
                    // task progress, so it must NOT consume a turn slot:
                    // decrementing cancels this iteration's advance. The
                    // MAX_AGENT_TURNS ceiling is never reset, and
                    // maxInLoopCompactions bounds compact-thrash within a turn,
                    // so a loop that keeps compacting cannot defeat the runaway
                    // backstop.
                    inLoopCompactions++

                    // [T-android-inloop-compact-divider-order / GH#235] Seal the
                    // bubble this run has been writing into and continue in a
                    // FRESH one below the divider.
                    //
                    // `assistantId` is normally ONE bubble for the whole agent
                    // loop, appended before the loop starts. compactAll() then
                    // tail-appends the "N messages compacted" divider, which
                    // lands AFTER that still-streaming bubble — and the loop
                    // `continue`s and keeps appending thinking / tool blocks
                    // into it. So every token produced after the compaction
                    // rendered ABOVE the divider, reading as if fresh output had
                    // been filed into already-compacted history. That is the
                    // reported symptom.
                    //
                    // Starting a new bubble rather than moving the divider is
                    // plan B, matching the iOS fix (e65540b69) so both platforms
                    // carry the same semantics: output produced BEFORE the
                    // compaction genuinely is pre-compaction history and belongs
                    // above the line; output after it belongs below. It also
                    // leaves compactAll()/appendSystemInfo untouched, so the
                    // user-initiated `/compact` path — which anchors on a
                    // finished message and is already correct — takes zero
                    // regression risk.
                    //
                    // Reuses the seal+swap contract the queued-prompt injection
                    // path already established (see injectQueuedPromptsAsNewTurn
                    // and its call site): flush the finished bubble, clear the
                    // per-turn accumulators, and point `assistantId` at a fresh
                    // placeholder so subsequent writes target it.
                    val sealedId = assistantId
                    val freshAssistantId = "assistant_${System.currentTimeMillis()}"
                    withContext(Dispatchers.Main) {
                        // Flush whatever the sealed bubble accumulated and stop
                        // it streaming, so it renders as finished history.
                        updateAssistantMessage(
                            sealedId,
                            accumulatedText,
                            false,
                            allToolBlocks,
                            isAwaitingModelResponse = false,
                        )
                        // If compaction fired before the bubble produced
                        // anything, it would render as an empty row stranded
                        // above the divider — drop it. Mirrors the iOS branch.
                        val sealed = _messages.value.firstOrNull { it.id == sealedId }
                        if (sealed != null &&
                            sealed.content.isEmpty() &&
                            sealed.toolBlocks.isEmpty()
                        ) {
                            _messages.value = _messages.value.filterNot { it.id == sealedId }
                            AppLogger.info(
                                TAG,
                                "[Compact] in-loop: dropped empty sealed bubble $sealedId",
                            )
                        }
                        _messages.value = _messages.value + ChatMessage(
                            id = freshAssistantId,
                            role = "assistant",
                            content = "",
                            isStreaming = true,
                            isAwaitingModelResponse = true,
                            thinkingLevel = turnThinkingLevel,
                        )
                    }
                    clearStreamFlushState(sealedId)
                    if (_streamingById.value.containsKey(sealedId)) {
                        _streamingById.value = _streamingById.value - sealedId
                    }
                    // Same loop-scope reset the queued-prompt swap performs, so
                    // the new bubble starts empty and buildTurnParts slices only
                    // the new turn's blocks.
                    assistantId = freshAssistantId
                    accumulatedText = ""
                    allToolBlocks.clear()
                    allToolInputs.clear()
                    toolInputChunkRings.clear()
                    AppLogger.info(
                        TAG,
                        "[Compact] in-loop: sealed $sealedId, continuing in $freshAssistantId below the divider",
                    )
                    continue
                }
                InLoopContextAction.STOP -> {
                    // The loop cannot present a modal mid-flight, so stop
                    // safely: user-visible notice + resumable, without the
                    // turn-limit error overwrite.
                    AppLogger.warning(
                        TAG,
                        "[AutoCompact] stopping turn: context exhausted and compaction cannot recover",
                    )
                    // [T-android-inloop-stop-thinking-orphan] Finalize the
                    // assistant message before leaving the loop.
                    //
                    // The placeholder was created with isStreaming = true /
                    // isAwaitingModelResponse = true. Only updateAssistantMessage
                    // (isStreaming = false) or finalizeAtTurnLimit ever clears
                    // those, and this branch reaches NEITHER: appendSystemInfo
                    // appends a SEPARATE system row and never touches the
                    // placeholder, while `loopExitedNormally = true` below
                    // deliberately skips finalizeAtTurnLimit at the loop tail.
                    //
                    // Without this the bubble stays on "Minis is thinking"
                    // forever — the streamJob's finally only clears the GLOBAL
                    // _isStreaming, not the per-message flags. Reachable with no
                    // failure at all: ContextPolicy gives every model with a
                    // context window under 64K `exhaustedOnly = true`, so
                    // crossing the exhaust line lands here directly.
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(
                            assistantId, accumulatedText, false, allToolBlocks,
                            isAwaitingModelResponse = false,
                        )
                        // Same orphan guard finalizeAtTurnLimit carries: the loop
                        // ran on IO while this hops to Main, so a late delta can
                        // re-add the side-channel entry after the drain, and
                        // mergeStreamingOverlay would then force isStreaming=true
                        // again with no further writer left to clear it.
                        clearStreamFlushState(assistantId)
                        if (_streamingById.value.containsKey(assistantId)) {
                            _streamingById.value = _streamingById.value - assistantId
                        }
                    }
                    // No persistAssistantTurn here: this guard runs BEFORE the
                    // turn body, so nothing new has been produced yet and the
                    // per-turn accumulators it would need
                    // (turnStartBlockIndex / lastUsage / turnReasoningContent)
                    // are not in scope. Everything from previous turns was
                    // already persisted by those turns.
                    appendSystemInfo(
                        text = "Context is full and could not be reduced further. " +
                            "Tap Continue to resume, or start a new chat.",
                        iconKind = "compact",
                    )
                    // [T-android-group-pause-badge-restamp] A LIVE interruption just
                    // happened: this is a real entry into the paused state, so the
                    // badge's 24h freshness stamp must be refreshed. Cancel any
                    // unconsumed re-detection mark left by a prior load so it cannot
                    // suppress the re-stamp here.
                    markLiveInterruption()
                    _canResume.value = true
                    // Android's equivalent of iOS's `hitTurnLimit = false`: this
                    // is a deliberate stop, NOT the runaway-ceiling path, so the
                    // post-loop tail must not slap a fake "hit 200 turns" error
                    // on it. finalizeAtTurnLimit is skipped; the notice above is
                    // the user-visible explanation.
                    loopExitedNormally = true
                    break
                }
            }

            // Mark where this turn's blocks start in allToolBlocks so we can persist
            // only the NEW parts from this turn (not the full accumulated history).
            // Matches iOS's per-turn RawMessage persistence.
            val turnStartBlockIndex = allToolBlocks.size
            // T307: per-delta StringBuilder for the running turn text + the
            // currently-open trailing text block. `turnText` snapshots are
            // taken (via .toString()) at flush boundaries only, never per
            // delta. `currentTextBlockSb` mirrors the trailing text block's
            // growing content; reset to a fresh builder whenever a new text
            // block opens (which happens after a tool_use / thinking break
            // interrupts the text run).
            val turnTextSb = StringBuilder()
            var currentTextBlockSb: StringBuilder? = null
            // [T-android-tool-splits-reply-fix] Index (into allToolBlocks) of
            // THIS turn's single text block, used only when the provider's
            // streamed content is monolithic (streamTextIsMonolithic — OpenAI
            // Chat Completions). -1 until the turn's first text delta. The
            // merge scope is ONE streamed response: text arriving after a
            // tool RESULT round-trip belongs to the NEXT agent-loop turn,
            // which is a separate assistant message — so genuine
            // multi-segment turns are unaffected by the merge.
            var turnTextBlockIdx = -1
            // One-shot observability: future endpoints that adopt qwen-style
            // post-tool_calls content chunking show up in the log.
            var loggedPostToolTextMerge = false
            // Materialise the active text block's StringBuilder into its
            // immutable content. Monolithic mode targets the tracked turn
            // text block — which may NOT be the last block once trailing
            // content arrived after tool_calls; ordered mode keeps the
            // original trailing-block behaviour.
            fun materializeActiveTextBlock() {
                val sb = currentTextBlockSb ?: return
                val idx = if (currentProvider.streamTextIsMonolithic) turnTextBlockIdx else allToolBlocks.lastIndex
                if (idx >= 0 && idx < allToolBlocks.size && allToolBlocks[idx].kind == "text") {
                    allToolBlocks[idx] = allToolBlocks[idx].copy(content = sb.toString())
                }
            }
            val turnThinking = StringBuilder()
            // Opaque reasoning_content blob captured from the provider's
            // ReasoningContent stream chunk. When set (including empty string),
            // takes precedence over turnThinking concatenation so the exact
            // server-emitted value round-trips on the next request — DeepSeek V4
            // emits "" legitimately and fabricated text would be in-context-learned.
            var turnReasoningBlob: String? = null
            // T321: capture finish_reason from LLMStreamChunk.Finished so we can
            // log it at turn-end alongside the empty-turn warning.
            var turnFinishReason: String? = null
            var lastUsage: LLMUsage? = null
            val maxTokens = dynamicMaxTokens(provider, lastContextTokens)
            val toolCalls = mutableListOf<Triple<String, String, JSONObject>>() // id, name, args
            // [T-android-gemini3-thoughtsig / #179] toolCallId -> Gemini 3.x
            // thoughtSignature for this turn's calls (null for other providers).
            val toolCallSignatures = mutableMapOf<String, String>()

            // [T-dedupe-toolcallid 03fbcbfd] Per-turn dedupe of tool_call_id.
            // Some upstream OpenAI-compatible gateways occasionally emit
            // multiple parallel tool_calls with the SAME id but different
            // name/args. Sending both back unchanged trips the receiver's
            // uniqueness check (HTTP 400 "duplicate tool_call_id"). Mirror
            // the iOS fix: the FIRST occurrence keeps the raw id, second
            // becomes "<id>-2", third "<id>-3", etc.
            //
            // Three pieces of state because Android routes ToolInputDelta
            // by chunk.id (iOS routes by name) and OpenAI emits ALL completes
            // together after finish_reason — so we can't drop the
            // "currently in-flight" map by the time completes arrive.
            //
            //   dedupeStartCounts    raw id → # ToolUseStart events seen
            //   dedupeCompleteCounts raw id → # ToolCallComplete events seen
            //   inFlightRenamedId    raw id → renamed id of the tool currently
            //                        streaming deltas (overwritten on each start)
            //
            // Start/complete ordering match: OpenAI streams emit tools in
            // `index` order at finish_reason, mirroring start order.
            val dedupeStartCounts = mutableMapOf<String, Int>()
            val dedupeCompleteCounts = mutableMapOf<String, Int>()
            val inFlightRenamedId = mutableMapOf<String, String>()
            fun dedupeToolStartId(raw: String): String {
                val n = (dedupeStartCounts[raw] ?: 0) + 1
                dedupeStartCounts[raw] = n
                val renamed = if (n == 1) raw else "$raw-$n"
                if (n > 1) {
                    AppLogger.warning(TAG_STREAM, "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'")
                }
                inFlightRenamedId[raw] = renamed
                return renamed
            }
            fun dedupeToolInputId(raw: String): String =
                inFlightRenamedId[raw] ?: raw
            fun dedupeToolCompleteId(raw: String): String {
                val n = (dedupeCompleteCounts[raw] ?: 0) + 1
                dedupeCompleteCounts[raw] = n
                return if (n == 1) raw else "$raw-$n"
            }

            // Stream the response — with auto-retry on transient errors, then fallback.
            // callbackFlow wraps throws into CancellationException(cause=LLMError),
            // so we catch at collect level and unwrap.
            var collectDone = false
            var retryAttempt = 0  // per-turn auto-retry counter (resets on each new turn)
            while (!collectDone) {
                try {
                    // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                    // Cache flag onto the active provider here — the single
                    // choke point every turn passes through, regardless of how
                    // currentProvider was (re)assigned by the fallback loop.
                    // Non-Anthropic providers ignore it (cast fails silently).
                    (currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                        ?.enhancedCache = _enhancedCacheEnabled.value
                    // [T-STALL-DIAG] First-chunk watchdog. The reported symptom
                    // is "new session shows thinking… forever, UI empty, stop
                    // button still armed" — which is indistinguishable, in the
                    // current logs, between (a) the request never left, (b) it
                    // left and the provider never answered, and (c) it answered
                    // and the chunks were routed to the wrong session's UI.
                    //
                    // Stamp the request as it goes out, then have a detached
                    // watchdog report every 10s while NOT ONE chunk has arrived.
                    // Silence here + no network error = the request is hung
                    // below the provider (DNS/TCP/TLS/read), which no existing
                    // log covers. `firstChunkSeen` is flipped in the collector.
                    val streamStartMs = android.os.SystemClock.elapsedRealtime()
                    val firstChunkSeen = java.util.concurrent.atomic.AtomicBoolean(false)
                    val diagSid = activeSessionId
                    println(
                        "[T-STALL-DIAG] stream REQUEST-OUT sid=$diagSid turn=$turn " +
                            "provider=${currentProvider.javaClass.simpleName} " +
                            "historySize=${agentHistory.size}",
                    )
                    val firstChunkWatchdog = viewModelScope.launch(Dispatchers.IO) {
                        var waited = 0L
                        while (!firstChunkSeen.get()) {
                            kotlinx.coroutines.delay(10_000L)
                            if (firstChunkSeen.get()) break
                            waited += 10_000L
                            println(
                                "[T-STALL-DIAG] stream NO-FIRST-CHUNK sid=$diagSid turn=$turn " +
                                    "waitedMs=$waited provider=${currentProvider.javaClass.simpleName} " +
                                    "— request sent, provider has returned NOTHING (not even message_start)",
                            )
                        }
                    }
                    try {
                    // Route through effectiveAgentHistory() so a populated
                    // [_compactSummary] is prepended as a `<context-summary>`
                    // user message. Falls through to the raw agentHistory when
                    // no compact has happened, so the common path stays zero-copy.
                    currentProvider.streamMessage(
                        applyRequestImageBudget(effectiveAgentHistory()),
                        systemPrompt, dynamicMaxTokens(currentProvider, lastContextTokens),
                        tools = agentTools,
                        thinkingLevel = if (currentModelSupportsReasoning) _thinkingLevel.value else ThinkingLevel.OFF,
                    ).collect { chunk ->
                // [T-STALL-DIAG] Mark first byte back from the provider and log
                // the time-to-first-chunk once per turn.
                if (firstChunkSeen.compareAndSet(false, true)) {
                    println(
                        "[T-STALL-DIAG] stream FIRST-CHUNK sid=$diagSid turn=$turn " +
                            "ttfbMs=${android.os.SystemClock.elapsedRealtime() - streamStartMs} " +
                            "kind=${chunk.javaClass.simpleName}",
                    )
                }
                when (chunk) {
                    is LLMStreamChunk.ThinkingDelta -> {
                        turnThinking.append(chunk.text)
                        // Update thinking block in UI
                        val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx < 0) {
                            allToolBlocks.add(AssistantBlock(
                                id = "thinking_$turn",
                                kind = "thinking",
                                content = turnThinking.toString(),
                                toolTitle = "Thinking",
                            ))
                        } else {
                            allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                        }
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                        }
                    }
                    is LLMStreamChunk.Text -> {
                        // [T-android-readaloud-stop-stale] First actual text of
                        // this reply — stop the previous reply's Read Aloud so
                        // old and new audio don't overlap. Deferred to here
                        // rather than fired from send() on purpose: while the
                        // model is still thinking there is nothing to supersede
                        // the old speech with, so it keeps playing until real
                        // new text arrives.
                        if (!didStopStaleReadAloud) {
                            didStopStaleReadAloud = true
                            _stopStaleReadAloud.tryEmit(Unit)
                        }
                        // Mark thinking block as done when text starts flowing
                        val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                            allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                        }
                        // T307: append-only on the StringBuilder; .toString()
                        // is taken once below at flush time, not per delta.
                        turnTextSb.append(chunk.text)
                        // Append to the trailing text block — or open a new one if the last
                        // block isn't a text block (i.e. a tool call or thinking was in between).
                        // This preserves the chronological interleaving of text and tool calls
                        // across a single assistant turn. The block's `content` field stays
                        // immutable String — we keep a parallel StringBuilder for the active
                        // block and materialise via .toString() only on flush.
                        val lastIdx = allToolBlocks.lastIndex
                        val monolithic = currentProvider.streamTextIsMonolithic
                        val activeSb = if (monolithic && turnTextBlockIdx >= 0 && currentTextBlockSb != null) {
                            // [T-android-tool-splits-reply-fix] Chat Completions
                            // content is ONE string per response — a content
                            // delta arriving after tool_calls deltas (qwen
                            // chunking artifact) is still part of the same
                            // pre-tool sentence. Merge it back instead of
                            // fabricating a post-tool text block, which split
                            // sentences mid-word in the chat UI. Scope: this
                            // streamed response only (see turnTextBlockIdx).
                            if (!loggedPostToolTextMerge &&
                                allToolBlocks.subList(turnTextBlockIdx + 1, allToolBlocks.size).any { it.kind == "tool_use" }
                            ) {
                                loggedPostToolTextMerge = true
                                AppLogger.info(
                                    TAG_STREAM,
                                    "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${currentProvider.model.id})",
                                )
                            }
                            currentTextBlockSb!!.append(chunk.text)
                            currentTextBlockSb!!
                        } else if (!monolithic && lastIdx >= 0 && allToolBlocks[lastIdx].kind == "text" && currentTextBlockSb != null) {
                            currentTextBlockSb!!.append(chunk.text)
                            currentTextBlockSb!!
                        } else {
                            // New text run — either first text after a tool_use/thinking
                            // break, or first text in this turn. Open a fresh block AND
                            // a fresh accumulator. The new block's content carries the
                            // first delta verbatim; subsequent deltas append to the SB.
                            val freshSb = StringBuilder(chunk.text)
                            currentTextBlockSb = freshSb
                            val block = AssistantBlock(
                                id = "text_${turn}_${allToolBlocks.size}",
                                kind = "text",
                                content = chunk.text,
                            )
                            if (monolithic) {
                                // Single text block per response. If tool blocks
                                // already arrived (content-after-tool_calls
                                // chunking with no preface text), insert BEFORE
                                // the first tool block of this turn so the
                                // persisted order matches the canonical
                                // {content, tool_calls} message shape.
                                val firstToolIdx = (turnStartBlockIndex until allToolBlocks.size)
                                    .firstOrNull { allToolBlocks[it].kind == "tool_use" }
                                if (firstToolIdx != null) {
                                    allToolBlocks.add(firstToolIdx, block)
                                    turnTextBlockIdx = firstToolIdx
                                } else {
                                    allToolBlocks.add(block)
                                    turnTextBlockIdx = allToolBlocks.lastIndex
                                }
                            } else {
                                allToolBlocks.add(block)
                            }
                            freshSb
                        }
                        // T94 fix 2 + T256: tiered text-delta throttle. Mutate local
                        // state every delta (above) so block boundaries stay correct
                        // for ToolUseStart / ToolInputDelta which read allToolBlocks
                        // directly. Only push to _messages when the length-aware gate
                        // opens (or a newline lands during a short reply). Pending
                        // text lives in `pendingChunkSb` so the stream-end final
                        // flush at line ~3580 can drain it.
                        pendingChunkSb.append(chunk.text)
                        val len = turnTextSb.length
                        val unflushed = len - lastFlushedLen
                        val throttle = textDeltaThrottleMs(len)
                        val newlineFlush = len < 5_000 && chunk.text.contains('\n') && unflushed >= 50
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastUiUpdateMs >= throttle || newlineFlush) {
                            lastUiUpdateMs = nowMs
                            lastFlushedLen = len
                            pendingChunkSb.setLength(0)
                            // Materialise SB → String for both the active block's
                            // content (so Compose sees an immutable snapshot) and
                            // for the assistant message body. These are O(n) calls
                            // but happen at throttled cadence, not per delta.
                            // (activeSb === currentTextBlockSb by construction.)
                            materializeActiveTextBlock()
                            val turnSnap = turnTextSb.toString()
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.ToolUseStart -> {
                        // [T-dedupe-toolcallid] Rewrite duplicate id ASAP — the
                        // renamed value drives the AssistantBlock.id used by
                        // ToolCallComplete / ToolInputDelta lookups and ends
                        // up as the persisted tool_call_id on the next request.
                        val toolUseId = dedupeToolStartId(chunk.id)
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolUseStart id=$toolUseId name=${chunk.name}")
                        // Mark thinking block as done when tool use starts
                        val thinkIdx = allToolBlocks.indexOfFirst { it.kind == "thinking" && it.id == "thinking_$turn" }
                        if (thinkIdx >= 0 && allToolBlocks[thinkIdx].toolStatus != ToolBlockStatus.SUCCESS) {
                            allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(toolStatus = ToolBlockStatus.SUCCESS)
                        }
                        // T154: when the last few text deltas landed inside the 50ms throttle
                        // window, the UI hadn't yet been pushed with the trailing text — and
                        // adding the tool_use block before that push freezes the preceding
                        // text fragment in StreamingMarkdownText (its `messageIsStreaming`
                        // flag flips off the next layout pass) with chars chopped off the
                        // end. Mirror iOS AnthropicAgentProvider.swift Step 1 / Step 2:
                        // first push the latest accumulated text *unthrottled* so the text
                        // block freezes at its complete value, yield to let Compose render
                        // it, then add the tool_use block in a separate transaction. The
                        // pendingChunkText/lastUiUpdateMs reset mirrors the throttle path
                        // so the next text delta doesn't try to flush stale state.
                        if (turnTextSb.isNotEmpty() && pendingChunkSb.isNotEmpty()) {
                            pendingChunkSb.setLength(0)
                            lastUiUpdateMs = System.currentTimeMillis()
                            lastFlushedLen = turnTextSb.length
                            // T307: pre-tool-use flush also materialises the
                            // active text block + a turn-text snapshot.
                            materializeActiveTextBlock()
                            // [T-android-tool-splits-reply-fix] Ordered mode:
                            // the tool block breaks the text run, so the next
                            // text delta opens a new block. Monolithic mode
                            // keeps the accumulator alive — same-response
                            // content deltas arriving after tool_calls merge
                            // back into the pre-tool text block instead.
                            if (!currentProvider.streamTextIsMonolithic) {
                                currentTextBlockSb = null
                            }
                            val turnSnap = turnTextSb.toString()
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                            }
                            yield()
                        }
                        // T256 tier 2: force the next ToolInputDelta to flush
                        // immediately by zeroing both gate timestamps. iOS does the
                        // same in .startToolUse (AIChatViewModel.swift:6075-6116) so
                        // the user sees the pill name/title arrive without waiting
                        // out the 1s/200ms gate.
                        lastFileToolInputMs = 0L
                        lastOtherToolInputMs = 0L
                        // Guard: only add if not already present (prevent duplicate blocks from repeated ToolUseStart)
                        if (allToolBlocks.none { it.id == toolUseId }) {
                            allToolBlocks.add(AssistantBlock(
                                id = toolUseId,
                                kind = "tool_use",
                                toolName = chunk.name,
                                toolStatus = ToolBlockStatus.STREAMING,
                                toolTitle = friendlyToolTitle(chunk.name),
                                startTimeMs = System.currentTimeMillis(),
                            ))
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.ToolInputDelta -> {
                        // [T-dedupe-toolcallid] Translate to the currently-in-flight
                        // renamed id so the per-tool ring + block lookup match
                        // the block that ToolUseStart created.
                        val toolInputId = dedupeToolInputId(chunk.id)
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=${chunk.accumulated.length}")
                        // Maintain a per-tool ring of the most recent `accumulated`
                        // snapshots so the preflight validator below can dump them
                        // when an empty/invalid call is detected. Cheap (single
                        // append + bounded trim) and lives outside any throttle so
                        // every delta lands here.
                        val ring = toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                        ring.add(chunk.accumulated)
                        if (ring.size > TOOL_INPUT_CHUNK_RING_MAX) {
                            // Drop from the front so we keep the most recent N.
                            ring.subList(0, ring.size - TOOL_INPUT_CHUNK_RING_MAX).clear()
                        }
                        val idx = allToolBlocks.indexOfFirst { it.id == toolInputId }
                        if (idx >= 0) {
                            val prev = allToolBlocks[idx]
                            // Stream-parse partial JSON (mirrors iOS extractPartialStringValue):
                            //   - pull "tool_title" out early so the pill header updates live
                            //   - keep the raw accumulated JSON in toolArgs so detail-sheet
                            //     renderers (extractShellCommand, args.optString("command"), …)
                            //     can pick up fields as they appear.
                            //   - leave content empty during streaming (real output arrives
                            //     after ToolCallComplete).
                            val partialTitle = extractPartialStringValue("tool_title", chunk.accumulated)
                            val liveTitle = when {
                                !partialTitle.isNullOrEmpty() -> partialTitle
                                prev.toolTitle.isNotEmpty() && prev.toolTitle != prev.toolName -> prev.toolTitle
                                else -> friendlyToolTitle(prev.toolName)
                            }
                            allToolBlocks[idx] = prev.copy(
                                toolArgs = chunk.accumulated,
                                toolTitle = liveTitle,
                                content = "",
                            )
                            // T256 tier 2: gate UI push by tool kind. file_write/file_edit
                            // pump multi-KB JSON through the SSE — pushing every delta
                            // pegs the UI thread for no readable benefit (the user can't
                            // skim a partial JSON blob anyway). Mirrors iOS
                            // AIChatViewModel.swift:6229-6259 (1s file / 200ms other).
                            // Local state above is mutated unconditionally so when the
                            // gate eventually opens — or ToolCallComplete force-flushes —
                            // the latest accumulated args are pushed.
                            val toolName = prev.toolName
                            val isHeavyFileTool = toolName == "file_write" || toolName == "file_edit"
                            val gateMs = if (isHeavyFileTool) 1_000L else 200L
                            val nowMs = System.currentTimeMillis()
                            val lastTs = if (isHeavyFileTool) lastFileToolInputMs else lastOtherToolInputMs
                            if (nowMs - lastTs >= gateMs) {
                                if (isHeavyFileTool) lastFileToolInputMs = nowMs
                                else lastOtherToolInputMs = nowMs
                                withContext(Dispatchers.Main) {
                                    updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                                }
                            }
                        }
                    }
                    is LLMStreamChunk.ToolCallComplete -> {
                        // [T-dedupe-toolcallid] Rewrite duplicate id so the
                        // persisted tool_calls list, the block lookup, and
                        // the downstream tool-result join all key on the
                        // same value (matches the rename applied at start).
                        val toolCompleteId = dedupeToolCompleteId(chunk.id)
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${chunk.args.toString().take(300)}")
                        toolCalls.add(Triple(toolCompleteId, chunk.name, chunk.args))
                        // [T-android-gemini3-thoughtsig / #179] Stash the Gemini
                        // 3.x thought signature keyed by the (deduped) tool call id.
                        chunk.thoughtSignature?.let { toolCallSignatures[toolCompleteId] = it }
                        val idx = allToolBlocks.indexOfFirst { it.id == toolCompleteId }
                        if (idx >= 0) {
                            val providedTitle = chunk.args.optString("tool_title", "").takeIf { it.isNotEmpty() }
                            val title = providedTitle ?: friendlyToolTitle(chunk.name)
                            // PENDING — JSON params fully received, waiting for execution
                            // dispatcher to invoke the tool. executeTool() flips to RUNNING.
                            allToolBlocks[idx] = allToolBlocks[idx].copy(
                                toolStatus = ToolBlockStatus.PENDING,
                                toolTitle = title,
                                toolArgs = chunk.args.toString(),
                                content = "", // Clear ToolInputDelta JSON accumulation before real output arrives
                                // [T-android-gemini3-thoughtsig / #179] Persist the
                                // signature onto the block so buildTurnParts (the DB
                                // path) round-trips it. Preserve any prior value if
                                // this chunk lacked one.
                                thoughtSignature = chunk.thoughtSignature ?: allToolBlocks[idx].thoughtSignature,
                            )
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                            }
                        }
                    }
                    is LLMStreamChunk.Usage -> {
                        lastUsage = chunk.usage
                        // Update context token count for next turn's dynamicMaxTokens()
                        // and publish to _lastTurnContextTokens so the ContextPolicy
                        // gate in [checkContextBeforeSend] can see the latest pressure
                        // without a DB round-trip.
                        if (chunk.usage.latestContextTokens > 0) {
                            lastContextTokens = chunk.usage.latestContextTokens
                        } else if (chunk.usage.inputTokens > 0) {
                            // Fallback when a provider omits latestContextTokens: inputTokens is
                            // now fresh-only (cached portion subtracted in the parser), so add the
                            // cache back to recover the true context size — otherwise a high
                            // cache-hit turn would under-report context pressure and skip offload.
                            lastContextTokens = chunk.usage.inputTokens +
                                (chunk.usage.cacheReadInputTokens ?: 0) +
                                (chunk.usage.cacheCreationInputTokens ?: 0)
                        }
                        if (lastContextTokens > 0) {
                            _lastTurnContextTokens.value = lastContextTokens
                        }
                    }
                    is LLMStreamChunk.ReasoningContent -> {
                        // Opaque reasoning blob (DeepSeek/Kimi reasoning_content) — record
                        // on the last assistant turn so it echoes back on the next request.
                        // Empty strings are preserved (DeepSeek V4 emits "" on non-thinking
                        // turns and we must round-trip exactly that). No live UI surface;
                        // the thinking panel is driven by ThinkingDelta events above.
                        turnReasoningBlob = chunk.content
                    }
                    is LLMStreamChunk.Finished -> {
                        // T321: stash for empty-turn diagnostic logging below.
                        turnFinishReason = chunk.stopReason
                    }
                    is LLMStreamChunk.Started -> { /* no-op */ }
                    is LLMStreamChunk.MediaAttachment -> {
                        // [T-codex-gpt-image2-oauth-android] Model-generated
                        // media (gpt-image-2 image). Inline chat display is out
                        // of scope for this change — the image is delivered via
                        // sendMessage→LLMResponse.mediaAttachments for the
                        // minis-model-use CLI path. No-op here so the chat agent
                        // loop compiles with the new chunk variant.
                    }
                }
                    }  // end collect
                    // T94 fix 2: flush any text that landed in the throttle
                    // window after the last UI tick. The retry-rollback /
                    // turn-finalize paths below assume _messages reflects all
                    // accumulated text-deltas, so we must not leave the last
                    // 0-50ms worth on the floor.
                    if (pendingChunkSb.isNotEmpty()) {
                        pendingChunkSb.setLength(0)
                        // T307: also flush the active text block's pending
                        // tail and snapshot turnText.
                        materializeActiveTextBlock()
                        val turnSnap = turnTextSb.toString()
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText + turnSnap, true, allToolBlocks)
                        }
                    }
                    // T256: reset throttle bookkeeping for the next turn so the
                    // first delta of the next assistant message fires immediately
                    // rather than coalescing against this turn's stale baseline.
                    lastFlushedLen = 0
                    lastUiUpdateMs = 0L
                    lastFileToolInputMs = 0L
                    lastOtherToolInputMs = 0L
                    collectDone = true
                    // Stream completed without error — clear any lingering retry UI state.
                    if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
                        _autoRetryAttempt.value = 0
                        _autoRetryCountdown.value = 0
                    }
                    } finally {
                        // [T-STALL-DIAG] Always stop the first-chunk watchdog —
                        // success, error, or cancellation — so it can never
                        // outlive its turn and spam the log.
                        firstChunkWatchdog.cancel()
                        if (!firstChunkSeen.get()) {
                            println(
                                "[T-STALL-DIAG] stream ENDED-WITHOUT-CHUNK sid=$diagSid turn=$turn " +
                                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - streamStartMs}",
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && e.cause == null) throw e  // real job cancellation
                    val actual = unwrapFlowException(e)
                    val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited
                    val is5xx = actual is com.openminis.app.data.model.LLMError.ProviderError &&
                        actual.detail.contains(Regex("[5][0-9]{2}"))
                    // Auto-retry on transient network/5xx/transient errors on the SAME provider
                    // before considering a fallback (mirrors iOS streamWithAutoRetry).
                    // Rate limits are provider-level signals that should trigger fallback immediately,
                    // not retry on the same provider.
                    val isTransient = actual is com.openminis.app.data.model.LLMError.NetworkError ||
                        actual is com.openminis.app.data.model.LLMError.TransientError ||
                        is5xx
                    if (isTransient && retryAttempt < AUTO_RETRY_DELAYS_SEC.size) {
                        val delaySec = AUTO_RETRY_DELAYS_SEC[retryAttempt]
                        retryAttempt += 1
                        val errDesc = actual.message ?: actual.javaClass.simpleName
                        Log.w(TAG, "🔁 Transient error on ${currentProvider.model.displayName}, retry $retryAttempt/${AUTO_RETRY_DELAYS_SEC.size} in ${delaySec}s: $errDesc")
                        withContext(Dispatchers.Main) {
                            _autoRetryAttempt.value = retryAttempt
                            // Show the error inline on the streaming assistant message during countdown.
                            // Keeps isStreaming=true so the UI doesn't tear down the streaming state.
                            setTransientInlineError("$errDesc — retrying ($retryAttempt/${AUTO_RETRY_DELAYS_SEC.size})…")
                        }
                        try {
                            for (remaining in delaySec downTo 1) {
                                _autoRetryCountdown.value = remaining
                                kotlinx.coroutines.delay(1000)
                            }
                        } finally {
                            _autoRetryCountdown.value = 0
                        }
                        // Clear inline error so the retry attempt can start cleanly.
                        withContext(Dispatchers.Main) {
                            clearInlineError()
                        }
                        // Roll back partial blocks from the failed stream attempt so the retried
                        // stream's deltas don't double-append on top of stale content. Previous
                        // turns (everything before turnStartBlockIndex) are preserved.
                        if (allToolBlocks.size > turnStartBlockIndex) {
                            while (allToolBlocks.size > turnStartBlockIndex) {
                                allToolBlocks.removeAt(allToolBlocks.size - 1)
                            }
                            // [T-android-fallback-text-rewind] Keep this turn's
                            // already-streamed text on screen across the rollback.
                            // `accumulatedText` only folds in `turnTextSb` after the
                            // while loop completes successfully, so passing bare
                            // `accumulatedText` here would visibly rewind everything
                            // the user already read this turn. The next attempt
                            // streams into a fresh `turnTextSb` and re-publishes
                            // `accumulatedText + newTurnText`, so this transient
                            // value is overwritten cleanly (no duplication).
                            withContext(Dispatchers.Main) {
                                updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                            }
                        }
                        // T307: SB-based per-turn accumulators reset.
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        // [T-android-tool-splits-reply-fix] The tracked turn
                        // text block was just rolled back with the rest of
                        // this turn's partial blocks.
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                        // T94 fix 2 + T256: throttle bookkeeping is per-stream
                        // attempt; reset alongside the partial-block rollback so
                        // the next attempt's first delta fires through immediately
                        // rather than coalescing against stale baselines.
                        pendingChunkSb.setLength(0)
                        lastUiUpdateMs = 0L
                        lastFlushedLen = 0
                        lastFileToolInputMs = 0L
                        lastOtherToolInputMs = 0L
                        continue  // retry on same provider
                    }
                    // Retries exhausted or non-retryable — proceed to fallback / throw.
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                    // [T-android-timeout-while-running] Clear any transient
                    // inline error from the prior retry attempts before we
                    // either fall back (loop continues with a new provider)
                    // or throw (terminal setInlineError below re-sets it
                    // with the final non-retryable message). Without this,
                    // a transient banner from the previous attempt could
                    // linger as the new provider starts streaming — the
                    // updateAssistantMessage(isStreaming=true) defense
                    // catches it on the next delta, but clearing here
                    // makes the intent explicit and avoids a one-frame
                    // flash of the stale banner.
                    withContext(Dispatchers.Main) { clearInlineError() }
                    val shouldFallback = isRateLimit || is5xx ||
                        fallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always
                    val nextCandidate = if (shouldFallback) remainingFallbacks.removeFirstOrNull() else null
                    val next = nextCandidate?.provider
                    if (next != null && nextCandidate != null) {
                        val reason = when {
                            isRateLimit -> "Rate limited"
                            actual is com.openminis.app.data.model.LLMError.ProviderError -> actual.detail
                            else -> actual.message ?: "Error"
                        }
                        // [T-android-model-indicator-flash-on-endpoint-retry]
                        // Same-model recovery is a TRANSPARENT retry, not a real
                        // model switch. A model group can hold several entries
                        // for the SAME modelId behind different provider
                        // instances/endpoints (e.g. deepseek-v4-flash via a dead
                        // hub.oaifree.com key + via api.deepseek.com). When the
                        // first 401s, group-fallback moves to the next instance —
                        // same modelId, different endpoint — which should recover
                        // silently. Only flash the model capsule when the
                        // resolved modelId ACTUALLY changes; an endpoint/instance-
                        // only change must not surface to the UI.
                        val isRealModelChange = next.model.id != currentProvider.model.id
                        fallbackReasons.add("⚠️ ${currentProvider.model.displayName}: $reason")
                        Log.i(TAG, "🔀 $reason on ${currentProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
                        currentProvider = next
                        // Also update class-level provider so the next sendMessage() starts from here
                        this@ChatViewModel.currentProvider = next
                        // Update top bar model info + active entry. (For a same-
                        // model endpoint recovery these are no-ops on the visible
                        // model name, but still keep activeEntryId / provider name
                        // in sync with the instance we actually used.)
                        _modelName.value = currentProvider.model.displayName
                        // Update activeEntryId so model picker reflects the switch.
                        // [T-android-fallback-entry-identity] Look the entry up by
                        // its OWN id, carried on the candidate. The previous
                        // `find { it.model.id == currentProvider.model.id }` was
                        // ambiguous: two instances can expose the same model id, so
                        // it returned whichever entry sits earlier in modelEntries.
                        // Observed in the field — falling back onto
                        // `deepseek-v4-flash` served by "DeekSeak" showed the
                        // provider as "Bailian OpenAI", because Bailian also has a
                        // `deepseek-v4-flash` entry and happened to be found first.
                        // That also poisoned activeEntryId and the persisted
                        // binding, so re-entering the session resumed on the WRONG
                        // instance.
                        val newEntry = providerRepository.config.value.modelEntries.find {
                            it.id == nextCandidate.entryId
                        }
                        if (newEntry != null) {
                            _activeEntryId.value = newEntry.id
                            currentModel = newEntry.model
                            val newInstance = providerRepository.instance(newEntry.providerInstanceId)
                            if (newInstance != null) {
                                _providerName.value = newInstance.label.ifEmpty { newEntry.model.provider }
                            }
                        }
                        // Flash ONLY on a genuine model switch — never on a
                        // transparent same-model endpoint retry.
                        if (isRealModelChange) _fallbackTrigger.value++
                        // Persist the fallback model so re-entering the session starts from here
                        val groupId = _selectedGroupId.value
                        if (groupId != null && newEntry != null) {
                            persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${newEntry.id}"}""")
                        } else if (newEntry != null) {
                            persistBinding("""{"type":"entry","entryId":"${newEntry.id}"}""")
                        }
                        val infoText = fallbackReasons.joinToString("\n") + "\n🔄 Switched to ${currentProvider.model.displayName}"
                        allToolBlocks.removeAll { it.kind == "info" }
                        allToolBlocks.add(0, AssistantBlock(
                            id = "fallback_info_$turn",
                            kind = "info",
                            content = infoText,
                            toolTitle = "Switched model",
                            toolStatus = ToolBlockStatus.SUCCESS,
                        ))
                        // [T-android-fallback-text-rewind] Same as the retry-
                        // rollback path above: preserve this turn's streamed text
                        // (`turnTextSb`) on screen while we switch providers.
                        // `accumulatedText` hasn't folded it in yet, so bare
                        // `accumulatedText` would rewind the visible reply. The new
                        // provider streams into a fresh `turnTextSb` (reset just
                        // below) and re-publishes `accumulatedText + newTurnText`.
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks)
                        }
                        // Reset turn state for retry with new provider
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        // [T-android-tool-splits-reply-fix] Fresh stream from a
                        // different provider — and the add(0, info) above
                        // shifted every block index anyway.
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                        // loop continues — will retry collect with currentProvider
                    } else {
                        // All fallbacks exhausted. Surface the trail of tried
                        // models AND the group members that were silently
                        // skipped (disabled / not logged in / hidden) so the
                        // user can see why fallback never reached them —
                        // mirrors iOS streamWithGroupFallback exhausted path.
                        if (shouldFallback) {
                            val skipped = unavailableGroupMembers()
                            if (fallbackReasons.isNotEmpty() || skipped.isNotEmpty()) {
                                val trail = (fallbackReasons + skipped).joinToString("\n")
                                val finalDesc = actual.message ?: actual.toString()
                                throw com.openminis.app.data.model.LLMError.ProviderError("$trail\n$finalDesc")
                            }
                        }
                        throw actual  // re-throw unwrapped, all fallbacks exhausted
                    }
                }
            }  // end while (!collectDone)

            // T307: materialise the per-turn StringBuilder ONCE at the
            // turn boundary. After this point everything is plain String
            // semantics — `turnText` participates in cross-turn accumulation
            // and gets persisted into agentHistory below.
            val turnText = turnTextSb.toString()
            // Accumulate text across turns
            accumulatedText += turnText

            // Build assistant contentParts for history
            val assistantParts = mutableListOf<AgentContentPart>()
            if (turnText.isNotEmpty()) {
                assistantParts.add(AgentContentPart.Text(turnText))
            }
            for ((id, name, args) in toolCalls) {
                // [T-android-gemini3-thoughtsig / #179] Attach the captured Gemini
                // 3.x signature so it round-trips through persistence and replay.
                assistantParts.add(AgentContentPart.ToolUse(id, name, args, thoughtSignature = toolCallSignatures[id]))
            }

            // Map toolUseId -> input JSON string for persistence (accumulated across turns)
            toolCalls.forEach { (id, _, args) -> allToolInputs[id] = args.toString() }
            val toolInputMap = allToolInputs
            // Prefer the opaque blob from LLMStreamChunk.ReasoningContent when the
            // provider emitted one — that path preserves empty strings (DeepSeek V4
            // `reasoning_content: ""` on non-thinking turns). Fall back to the
            // ThinkingDelta concatenation only when no blob arrived; in that case
            // an empty buffer becomes null (no field to round-trip).
            val turnReasoningContent: String? = turnReasoningBlob
                ?: turnThinking.toString().takeIf { it.isNotEmpty() }

            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = turnText,
                contentParts = assistantParts,
                reasoningContent = turnReasoningContent,
            ))

            // T321: empty-turn diagnostic — fires when GPT-5.5 (or any other
            // provider) returns a turn with no visible text AND no tool calls.
            // Log only; UI behavior unchanged. Pair with OpenAIProvider SSE
            // logs to triage server-empty vs parser-drop vs swallowed-exception.
            if (turnText.isEmpty() && toolCalls.isEmpty()) {
                AppLogger.warning(
                    TAG_STREAM,
                    "empty turn detected: turn=$turn finishReason=$turnFinishReason " +
                        "reasoningLen=${turnThinking.length} reasoningBlobLen=${turnReasoningBlob?.length ?: -1} " +
                        "model=${provider.model.id} provider=${provider.name}"
                )
            }

            // If no tool calls, we're done
            if (toolCalls.isEmpty()) {
                AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=$turnFinishReason)")
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                }
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta)
                // [T-error-persist-android] Empty-response hint: the model ended a
                // turn (finish=stop/end_turn) with no visible text anywhere in the
                // reply and no tool blocks — the user just sees a blank bubble.
                // Surface a hint instead. When the context is near full, point at
                // compaction; otherwise suggest retry/switch. setInlineError
                // attaches + persists onto the (empty) assistant row so the hint
                // survives a reload too.
                val hasVisibleContent = accumulatedText.isNotBlank() ||
                    allToolBlocks.any { it.kind == "tool_use" || (it.kind == "text" && it.content.isNotBlank()) }

                // [T-android-silent-stream-drop] A turn's stopReason comes ONLY
                // from the SSE terminal event, which always carries a concrete
                // reason. A null therefore means the stream closed WITHOUT one —
                // the connection dropped mid-flight (a mid-flight throw would
                // have gone down the fallback/retry path instead, not here).
                //
                // Previously null was folded into `finishedCleanly`, so a
                // PARTIAL reply — bytes arrived, then the socket died — was
                // persisted silently as if complete. The reply just stopped with
                // no error and no way to retry: the "断流" reports. Mirrors iOS
                // d6604021.
                // Only the PARTIAL case is handled here. An EMPTY turn with a null
                // stopReason keeps falling through to the empty-turn handling
                // below (system-reminder retry, then the empty-response hint),
                // which already covers it well — re-routing it here would lose
                // that recovery.
                if (turnFinishReason == null && hasVisibleContent) {
                    AppLogger.warning(
                        TAG_STREAM,
                        "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                            "surfacing as an interrupted reply (turn=$turn)",
                    )
                    withContext(Dispatchers.Main) {
                        setInlineError(
                            context.getString(R.string.chat_error_stream_dropped_partial),
                        )
                    }
                    // [T-android-group-pause-badge-restamp] A LIVE interruption just
                    // happened: this is a real entry into the paused state, so the
                    // badge's 24h freshness stamp must be refreshed. Cancel any
                    // unconsumed re-detection mark left by a prior load so it cannot
                    // suppress the re-stamp here.
                    markLiveInterruption()
                    _canResume.value = true
                    // Deliberate stop, not the runaway ceiling — keep the
                    // post-loop tail from adding a fake turn-limit error.
                    loopExitedNormally = true
                    break
                }

                // A null stopReason reaching here means an EMPTY turn, which the
                // empty-turn path below is designed to recover; treat it as
                // "clean" for that purpose exactly as before.
                val finishedCleanly = turnFinishReason == null ||
                    turnFinishReason == "stop" || turnFinishReason == "end_turn"
                if (!hasVisibleContent && finishedCleanly) {
                    // [T-android-empty-after-toolresult-reminder] Special case: the
                    // server returned an empty turn right after a tool result. The
                    // model owes a follow-up (next tool call or a final answer) but
                    // stalled — the user sees a blank bubble with no explanation.
                    // Inject a one-shot <system-reminder> into that tool result and
                    // retry ONE round. The guard fires at most once per run, so it
                    // can never loop; the SECOND empty falls through to the error
                    // hint below. Mirrors iOS AIChatViewModel.swift empty-after-
                    // tool-result path.
                    //
                    // The empty assistant turn was just appended (above) — drop it
                    // so the tool result is the last message and the model gets a
                    // clean "continue from here" prompt on the retry.
                    val priorIsToolResult = agentHistory.size >= 2 &&
                        agentHistory[agentHistory.size - 2].contentParts.isNotEmpty() &&
                        agentHistory[agentHistory.size - 2].contentParts.all { it is AgentContentPart.ToolResult }
                    if (!didInjectEmptyToolReminder && priorIsToolResult) {
                        didInjectEmptyToolReminder = true
                        AppLogger.warning(TAG_STREAM, "empty turn after tool result — injecting <system-reminder> and retrying one round (turn=$turn)")
                        // Remove the empty assistant turn we just added.
                        agentHistory.removeAt(agentHistory.size - 1)
                        // Inject the reminder into the last tool result's content.
                        val trIdx = agentHistory.size - 1
                        val trMsg = agentHistory[trIdx]
                        val reminder = "\n\n<system-reminder>The previous response was empty. A tool result was just provided and you MUST continue: respond with the next tool call(s) if more work is needed, or a final text answer for the user. Do not return an empty response.</system-reminder>"
                        val newParts = trMsg.contentParts.toMutableList()
                        val lastTrPartIdx = newParts.indexOfLast { it is AgentContentPart.ToolResult }
                        if (lastTrPartIdx >= 0) {
                            val part = newParts[lastTrPartIdx] as AgentContentPart.ToolResult
                            newParts[lastTrPartIdx] = part.copy(content = part.content + reminder)
                            agentHistory[trIdx] = trMsg.copy(contentParts = newParts)
                        }
                        // Retry a fresh model round with the nudged history.
                        continue
                    }
                    val window = effectiveContextWindowTokens()
                    val usedCtx = lastUsage?.latestContextTokens ?: 0
                    val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                        usedCtx.toDouble() / window.toDouble() > 0.70
                    val hint = when {
                        // Reminder already fired and the retry was ALSO empty — this
                        // is a genuine stall, not a transient blank. Point the user
                        // at retry/switch explicitly.
                        didInjectEmptyToolReminder ->
                            context.getString(R.string.error_empty_response_after_tool)
                        contextNearFull ->
                            context.getString(R.string.error_empty_response_context_large)
                        else ->
                            context.getString(R.string.error_empty_response_generic)
                    }
                    withContext(Dispatchers.Main) { setInlineError(hint) }
                }
                // Auto-title after first exchange
                if (turn == 0) generateSessionTitleIfNeeded()
                loopExitedNormally = true
                break
            }
            AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")

            // [T-android-session-last-message-live-tool-call] Push a live
            // preview to the session list NOW, before the (possibly long-
            // running) tools execute. The authoritative assistant row isn't
            // written until turn end (persistAssistantTurn below), so without
            // this the home list shows a stale preview — or "No messages yet"
            // for a turn that opened with a tool call and no prior text —
            // for the entire tool duration. extractTextPreview prefers the
            // assistant's partial text and falls back to the tool summary, so
            // the list reflects exactly what the model just emitted. Mirrors
            // iOS overlaying the live VM's last message over the DB value.
            run {
                val livePreviewParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                val liveMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                if (livePreviewParts.isNotEmpty()) {
                    chatRepository.updateSessionPreview(
                        realSessionId.ifEmpty { sessionId },
                        buildAssistantPartsJson(livePreviewParts, liveMeta),
                    )
                }
            }

            // Execute all tool calls
            val resultParts = mutableListOf<AgentContentPart>()
            for ((id, name, args) in toolCalls) {
                // [T-android-overlay-tool-title] Pull tool_title uniformly
                // from args for ALL tools — without this browser_use's
                // tool_title never reached the overlay (only shell_execute
                // had a per-tool status override that surfaced it). Reading
                // it here also means new tools added later automatically
                // get title-in-overlay behavior without per-call plumbing.
                val dispatchToolTitle = try {
                    args.optString("tool_title", "").takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                SessionActivityTracker.updateToolStatus(
                    status = "Running: $name",
                    toolName = name,
                    isRunning = true,
                    toolTitle = dispatchToolTitle,
                )
                // JSON repair (T-tool-json-repair b2c4f8a6): salvage truncated /
                // type-mismatched / typo'd args BEFORE preflight rejects them.
                // Mutates `args` in place; downstream argsStr and preflight see
                // the repaired payload. Mirrors iOS repairToolArgs in
                // AIChatViewModel.swift.
                val repairs = com.openminis.app.provider.ToolJsonRepair.repair(
                    name, args, toolInputChunkRings[id]?.lastOrNull(), agentTools,
                )
                if (repairs.isNotEmpty()) {
                    AppLogger.warning(
                        "ToolPreflight",
                        "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString(", ")}] " +
                            "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                            "rawTail=<<<${toolInputChunkRings[id]?.lastOrNull()?.take(500) ?: ""}>>>"
                    )
                }
                // [T-truncated-args-visibility #119] Non-null when THIS call's
                // arguments arrived truncated and were auto-closed. Only the
                // truncation strategy means the VALUE was cut short; coercion
                // and fuzzy-name repairs fix the shape of a complete argument.
                // Mirrors iOS truncationRepairTag.
                val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith("truncation+") }

                // [T-truncated-args-visibility #119] Refuse truncated WRITES.
                // Auto-closing an unterminated JSON string is indistinguishable
                // from the model ending `content` there, so a half file lands on
                // disk while UI and tool result both report success. For writes a
                // partial artifact is silent corruption of user data and is worse
                // than no write at all; read-only and shell tools keep the
                // repair-and-run behaviour. Mirrors iOS ConcurrentTools.
                if (truncationRepairTag != null && (name == "file_write" || name == "file_edit")) {
                    val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
                    AppLogger.warning(
                        "ToolPreflight",
                        "[ToolRepair] REFUSED truncated write tool=$name id=$id strategy=$truncationRepairTag path=$path"
                    )
                    val modelMessage = buildString {
                        append("Error: This call was NOT executed. Its argument stream was truncated ")
                        append("in transit (repair strategy: $truncationRepairTag), so the `content` ")
                        append("your client sent was cut short and would have written an incomplete file")
                        if (path.isNotBlank()) append(" to $path")
                        append(". Nothing was written to disk — the target file is unchanged.\n\n")
                        append("The most likely cause is the response hitting its output-token limit ")
                        append("mid-argument. Re-issue this write in smaller pieces: write the first ")
                        append("part, then append the rest with follow-up calls, rather than repeating ")
                        append("the same oversized call.")
                    }
                    val refusedIdx = allToolBlocks.indexOfFirst { it.id == id }
                    if (refusedIdx >= 0) {
                        val elapsed = System.currentTimeMillis() - allToolBlocks[refusedIdx].startTimeMs
                        allToolBlocks[refusedIdx] = allToolBlocks[refusedIdx].copy(
                            toolStatus = ToolBlockStatus.FAILED,
                            content = "Blocked: arguments were truncated in transit",
                            durationMs = elapsed,
                        )
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                        }
                    }
                    toolLoopDetector.record(name, parseToolParams(args.toString()),
                        result = null, errorMessage = modelMessage, toolCallId = id)
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = modelMessage,
                        isError = true,
                    ))
                    toolInputChunkRings.remove(id)
                    continue
                }
                val argsStr = args.toString()
                val paramsMap = parseToolParams(argsStr)
                // Flip PENDING → RUNNING right before the execute dispatch so the UI
                // (tool pill spinner) shows the exact moment execution begins.
                val preIdx = allToolBlocks.indexOfFirst { it.id == id }
                if (preIdx >= 0 && allToolBlocks[preIdx].toolStatus == ToolBlockStatus.PENDING) {
                    allToolBlocks[preIdx] = allToolBlocks[preIdx].copy(toolStatus = ToolBlockStatus.RUNNING)
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                    }
                }

                // Loop-detector check BEFORE execution. CRITICAL outcomes short-circuit
                // the call: synthesize an error result so the tool_use/tool_result pair
                // stays balanced and the LLM sees the block reason.
                val precheck = toolLoopDetector.check(name, paramsMap)
                if (precheck.level == Level.CRITICAL) {
                    val blockedMsg = precheck.message ?: "[LOOP BLOCKED] tool execution blocked"
                    android.util.Log.w("ToolChain[VM]",
                        "[turn=$turn] tool BLOCKED by loop detector name=$name msg=$blockedMsg")
                    AppLogger.warning("ChatViewModel",
                        "tool blocked by loop detector name=$name reason=$blockedMsg")
                    val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                    if (blockIdx >= 0) {
                        val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                        allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                            toolStatus = ToolBlockStatus.FAILED,
                            content = blockedMsg,
                            durationMs = elapsed,
                        )
                    }
                    // Record the blocked attempt so consecutive blocks still
                    // count toward the unknown-tool / circuit-breaker windows.
                    toolLoopDetector.record(name, paramsMap,
                        result = null, errorMessage = blockedMsg, toolCallId = id)
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = blockedMsg,
                        isError = true,
                    ))
                    continue
                }

                // Preflight: reject empty / missing-required-field tool calls
                // BEFORE the UI flips to RUNNING and BEFORE executeTool() does
                // any actual work. Mirrors iOS preflightValidateToolCall in
                // AIChatViewModel.swift. Synthesizes a tool_result error so the
                // model can self-correct on the next turn without us spawning
                // shells or touching the filesystem on `{}` args.
                val preflightError = preflightValidateToolCall(name, args, agentTools)
                if (preflightError != null) {
                    val chunkRing: List<String> = toolInputChunkRings.remove(id) ?: emptyList()
                    AppLogger.warning(
                        "ToolPreflight",
                        "BLOCKED tool=$name id=$id reason=\"$preflightError\" " +
                            "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                            "chunkCount=${chunkRing.size} " +
                            "lastChunk=<<<${chunkRing.lastOrNull()?.take(500) ?: ""}>>>"
                    )
                    chunkRing.forEachIndexed { i, snap ->
                        AppLogger.warning(
                            "ToolPreflight",
                            "  chunk[$i] bytes=${snap.toByteArray(Charsets.UTF_8).size} raw=<<<${snap.take(500)}>>>"
                        )
                    }
                    // English literal — string resource lookup intentionally
                    // avoided to keep this commit independent of any in-flight
                    // strings.xml refactor in other sessions. Promote to a
                    // localized R.string entry in a follow-up if needed.
                    val uiMessage = "Blocked invalid tool call"
                    val modelMessage = "Error: Tool call rejected before execution. $preflightError The arguments your client sent were empty or missing required fields — re-issue the call with all required parameters filled in. Do not retry with the same empty arguments."
                    val blockIdxPre = allToolBlocks.indexOfFirst { it.id == id }
                    if (blockIdxPre >= 0) {
                        val elapsedPre = System.currentTimeMillis() - allToolBlocks[blockIdxPre].startTimeMs
                        allToolBlocks[blockIdxPre] = allToolBlocks[blockIdxPre].copy(
                            toolStatus = ToolBlockStatus.FAILED,
                            content = uiMessage,
                            durationMs = elapsedPre,
                        )
                    }
                    toolLoopDetector.record(
                        toolName = name, params = paramsMap,
                        result = null, errorMessage = modelMessage, toolCallId = id
                    )
                    resultParts.add(AgentContentPart.ToolResult(
                        id = id, name = name,
                        content = modelMessage,
                        isError = true,
                    ))
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                    }
                    continue
                }

                android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool START name=$name args=${argsStr.take(200)}")
                val result = executeTool(name, argsStr, id, allToolBlocks, assistantId, accumulatedText)
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool END name=$name success=${result.success} title=${result.toolTitle} outputLen=${result.output.length} output=${result.output.take(200)}")

                // Record post-execution. WARNING text is appended to the tool
                // result so the model sees it on its next turn. No block here —
                // CRITICAL only fires from check() and we already returned above.
                val errMsgForDetector = if (!result.success) result.output else null
                val postRecord = toolLoopDetector.record(
                    toolName = name,
                    params = paramsMap,
                    result = if (result.success) result.output else null,
                    errorMessage = errMsgForDetector,
                    toolCallId = id,
                )
                val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                    AppLogger.debug("ChatViewModel",
                        "appending loop-warning to tool result name=$name key=${postRecord.warningKey}")
                    "${result.output}\n\n${postRecord.message}"
                } else {
                    result.output
                }

                val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdx >= 0) {
                    val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                    // Keep live-streamed content if it has more data than the truncated result.
                    // T263: takeLast(80) was applied uniformly, but it was sized for
                    // shell_execute (long stdout streams where the tail is what
                    // matters). For tools whose first line carries metadata —
                    // file_read's `[path | N bytes | M lines | showing A-B of M]`
                    // banner, file_write/file_edit confirmations, memory_* /
                    // browser_use structured headers — clipping the head dropped
                    // the banner entirely. iOS routes file_read through a
                    // dedicated branch (AIChatViewModel.swift:5229) and avoids
                    // this; mirror that intent by gating the trim to shell_execute.
                    val existingContent = allToolBlocks[blockIdx].content
                    val resultContent = if (name == "shell_execute") {
                        result.output.lines().takeLast(80).joinToString("\n")
                    } else {
                        result.output
                    }
                    val finalContent = if (existingContent.length > resultContent.length) existingContent else resultContent
                    // [T-truncated-args-visibility #119] A call built from
                    // truncated args must not render as a clean success — that
                    // silence is the reported bug. Show it with the same weight
                    // as the blocked path. Mirrors iOS ConcurrentTools.
                    val finalStatus = when {
                        result.success && truncationRepairTag != null -> ToolBlockStatus.FAILED
                        result.success -> ToolBlockStatus.SUCCESS
                        result.timedOut -> ToolBlockStatus.TIMEOUT
                        else -> ToolBlockStatus.FAILED
                    }
                    // T-bg-overlay phase 1: tool finished — drop the
                    // notification's indeterminate progress bar so the
                    // user can tell streaming has paused (LLM step) vs
                    // a tool is in flight.
                    // [T-overlay-glyph-typed-outcome] Pass the typed
                    // outcome so the bg overlay glyph reflects the real
                    // SUCCESS / TIMEOUT / FAILED result instead of
                    // text-sniffing the stale "Running: foo" status.
                    val toolOutcome = when (finalStatus) {
                        ToolBlockStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
                        ToolBlockStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
                        ToolBlockStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
                        else -> com.openminis.app.service.ToolOutcome.Unknown
                    }
                    SessionActivityTracker.clearToolRunning(toolOutcome)
                    android.util.Log.d("ToolChain[VM]", "[turn=$turn] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
                    allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                        toolStatus = finalStatus,
                        content = finalContent,
                        toolTitle = result.toolTitle.ifEmpty { allToolBlocks[blockIdx].toolTitle },
                        durationMs = elapsed,
                        browserURL = result.pageURL ?: allToolBlocks[blockIdx].browserURL,
                        imageFilePath = result.imageFilePath ?: allToolBlocks[blockIdx].imageFilePath,
                    )
                }

                // [T-truncated-args-visibility #119] Tell the MODEL its own
                // arguments were altered. Writes never reach here (refused
                // above); this covers the tools we still run repaired, where the
                // model would otherwise assume the args it emitted were the args
                // that ran. Mirrors iOS ConcurrentTools.
                val outputForLLMWithNote = if (truncationRepairTag != null) {
                    outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
                        "truncated in transit and auto-closed by the client (repair strategy: " +
                        "$truncationRepairTag) before execution. The arguments actually used may be " +
                        "incomplete — verify the result and re-issue the call with complete " +
                        "arguments if anything is missing.</system-reminder>"
                } else {
                    outputForLLM
                }

                resultParts.add(AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = outputForLLMWithNote,
                    isError = !result.success,
                    imageData = result.imageData,
                    imageMimeType = result.imageMimeType,
                    imageLinuxPath = result.imageLinuxPath,
                ))
            }

            // Update UI with tool statuses. Mark as awaiting the next model
            // response so "Minis is thinking" shows during the network gap
            // between tool results being sent and the next turn's first chunk.
            // Mirrors iOS isAwaitingModelResponse.
            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, true, allToolBlocks,
                    isAwaitingModelResponse = true,
                )
            }

            // Persist the assistant+tools turn (with full input JSON and thinking).
            // Capture the persisted DB id so we can back-fill agentHistory's last
            // assistant entry — compact-marker boundary resolution depends on it.
            val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
            val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
            val assistantDbId = persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta)
            if (assistantDbId != null) {
                val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
                if (lastIdx >= 0) {
                    agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
                }
            }

            // Persist tool results as user-role message (mirrors iOS)
            val toolResultDbId = persistToolResultMessage(resultParts)

            // Add tool results to history
            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = resultParts,
                dbMessageId = toolResultDbId,
            ))

            // Auto-title after first exchange (mirrors iOS generateSessionTitleIfNeeded)
            if (turn == 0) {
                generateSessionTitleIfNeeded()
            }

            // [T-android-queued-message-interrupt-on-toolclose] iOS d14174d3
            // parity. User report: "怎么样了" queued bubble (dashed border,
            // red X) stayed pending behind a long sync→export→read→gh-issue
            // tool chain — drainQueuedPrompts() only fires when the WHOLE
            // tool loop converges, so the queued prompt waited for the
            // entire plan to finish even though the user wanted to
            // interrupt the moment a tool closed.
            //
            // Fix: at the post-tool-result boundary (we just appended the
            // tool_result to agentHistory above), if there's anything in
            // the queue, abandon the rest of the running plan and inject
            // the queued prompt as a fresh user turn — the next iteration
            // makes a brand-new API call whose response targets the
            // queued prompt directly.
            //
            // Why not just append-and-continue: the agentHistory tail is
            // user(tool_result). Anthropic's mergeConsecutiveSameRole would
            // fold a directly-appended user(queued_text) into that
            // tool_result, so the model would read the queued prompt as
            // in-loop context for the previous turn (#579 / iOS regression).
            // Inject a minimal assistant bridge first so the sequence is
            //   …user(tool_result) → assistant(bridge) → user(queued) →
            //   …assistant(responds-to-queued).
            // The bridge lives in agentHistory only (NOT persisted) —
            // it's purely a wire-format spacer for the API call.
            if (_promptQueue.value.isNotEmpty()) {
                AppLogger.info(
                    TAG_STREAM,
                    "📨[QueueInterrupt] turn=$turn ${_promptQueue.value.size} queued prompt(s) — interrupting after current tool call to start a standalone turn",
                )
                val handled = try {
                    injectQueuedPromptsAsNewTurn(
                        finishedAssistantId = assistantId,
                        finishedAccumulatedText = accumulatedText,
                        finishedAllToolBlocks = allToolBlocks,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "injectQueuedPromptsAsNewTurn failed", e)
                    null
                }
                if (handled != null) {
                    // Switch loop-scope state to the new bubble. Subsequent
                    // iterations populate `handled.newAssistantId` and slice
                    // `allToolBlocks` from the freshly-zeroed start index
                    // (turnStartBlockIndex captures allToolBlocks.size at
                    // iteration top, so clearing means new turn's blocks
                    // span [0..size).
                    assistantId = handled.newAssistantId
                    accumulatedText = ""
                    allToolBlocks.clear()
                    allToolInputs.clear()
                    toolInputChunkRings.clear()
                    _canResume.value = false
                    continue
                }
                // null return = empty-after-build / drain rejected; fall
                // through to normal next-turn dispatch so the queue doesn't
                // pin the loop indefinitely.
            }
        }
        // Two ways to leave the for-loop above:
        //   (a) `break` from the "no tool calls" happy-path → loopExitedNormally=true,
        //       updateAssistantMessage(...false...) already cleared streaming state.
        //   (b) `for (turn in 0 until MAX_AGENT_TURNS)` exhausted → flag stays false,
        //       which means the model kept asking for tool calls past the ceiling.
        //
        // (b) is the only case that needs the inline-error/Resume hand-holding;
        // (a) must NOT be touched or every normal completion gets a fake "hit
        // 200 turns" sticker (the bug user hit at v1.4.0-dev tip).
        if (!loopExitedNormally) {
            AppLogger.warning(
                TAG_STREAM,
                "runAgentLoop EXIT — hit MAX_AGENT_TURNS=$MAX_AGENT_TURNS, finalizing as resumable",
            )
            withContext(Dispatchers.Main) {
                finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks)
            }
        } else {
            AppLogger.info(TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
        }
    }

    /**
     * Finalize the current assistant message when [runAgentLoop] hits the
     * MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
     * "thinking" indicator clears, writes an inline error explaining *why*
     * we stopped, and arms canResume so the user can continue from here.
     * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
     */
    private fun finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
    ) {
        updateAssistantMessage(
            assistantId, text, false, blocks,
            isAwaitingModelResponse = false,
        )
        // [T-android-thinking-indicator-linger] updateAssistantMessage drains
        // _streamingById[assistantId] above, but the agent loop ran on
        // Dispatchers.IO while this finalize hops to Main — a late streaming
        // delta can re-add the side-channel entry AFTER the drain, and since
        // the loop has now exited no further isStreaming=false write will ever
        // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
        // on any message with a side-channel entry, so that orphan keeps the
        // "thinking" row alive forever. Defensively drop the entry here as the
        // last Main-thread write of this turn.
        // [T-android-stream-flush-review] Cancel the trailing flush too, so it
        // can't re-add this orphan entry after we drop it on the error path.
        clearStreamFlushState(assistantId)
        if (_streamingById.value.containsKey(assistantId)) {
            _streamingById.value = _streamingById.value - assistantId
        }
        setInlineError(
            "Stopped after $MAX_AGENT_TURNS agent turns to prevent runaway " +
            "tool use. The model kept calling tools without finishing — tap " +
            "Resume to continue from here, or send a new message to start over.",
        )
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    }

    /**
     * Instance entry point used by the tool-dispatch path. The real logic lives
     * in the companion so tests can reach it without a ChatViewModel.
     */
    private fun preflightValidateToolCall(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? = preflightValidateToolCallImpl(name, args, tools)

    private suspend fun executeTool(
        name: String,
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        // T330: tri-state permission gating moved into the offload IPC
        // handler (OffloadGate). The CLIs land there whether the LLM
        // emitted a named tool call or a raw shell command, so the gate
        // is consistent across both paths. The pre-check that lived here
        // (`permissionTools = {calendar, location, …}`) was effectively
        // dead since these tools have no native ChatViewModel executor
        // — they always fall through to shell_execute or the offload
        // bridge, which is now where checkPermission runs.
        val toolTitle = try { JSONObject(argsJson).optString("tool_title", name) } catch (_: Exception) { name }

        return when (name) {
            FileReadTool.NAME -> {
                val result = FileReadTool.execute(argsJson, activeSessionId, context)
                // Record skill usage when SKILL.md under /var/minis/skills/<id>/ is read.
                if (result.success) {
                    runCatching {
                        val readPath = JSONObject(argsJson).optString("path", "")
                        if (readPath.isNotEmpty()) {
                            skillRepository?.skillIdFromPath(readPath)?.let { sid ->
                                skillRepository.recordSkillUse(sid)
                            }
                        }
                    }
                }
                result
            }
            FileWriteTool.NAME -> FileWriteTool.execute(argsJson, activeSessionId, context).also {
                if (it.success) maybeReloadSkillsForPath(argsJson)
            }
            FileEditTool.NAME -> FileEditTool.execute(argsJson, activeSessionId, context).also {
                if (it.success) maybeReloadSkillsForPath(argsJson)
            }
            // T178: pass sessionId + context so read_image routes through
            // resolveSessionHostPath like file_read/write/edit do — without
            // these, the tool consults the global last-writer-wins
            // bindMounts map and would surface another session's
            // /var/minis/{workspace,attachments,offloads,browser} files.
            ReadImageTool.NAME -> executeReadImageTool(argsJson)
            "shell_execute" -> executeShellCommand(argsJson, toolId, toolBlocks, assistantId, currentText)
            "browser_use" -> executeBrowserUseTool(argsJson)
            "memory_write" -> executeMemoryWriteTool(argsJson)
            "memory_get" -> executeMemoryGetTool(argsJson)
            else -> ToolExecutionResult("Unknown tool: $name", false)
        }
    }

    /**
     * [T-android-vision-group / GH#182] Placeholder text for an image the CURRENT
     * main model can't natively see, to be carried on the outgoing image part and
     * substituted by the provider's T264 branch. Returns null when the main model
     * has native vision (pixels are attached, no placeholder needed) OR no Vision
     * Group is configured (provider falls back to its historical literal). When a
     * Vision Group IS configured, returns a hint naming [path] and steering the
     * model to call read_image — closing the loop with executeReadImageTool.
     */
    private fun visionPlaceholderFor(path: String?): String? {
        if (currentModelHasNativeVision) return null
        if (!com.openminis.app.tools.VisionGroupResolver.isConfigured(providerRepository, context)) return null
        return com.openminis.app.tools.VisionGroupResolver.noVisionImagePlaceholder(path)
    }

    /**
     * [T-android-vision-group / GH#182] read_image dispatch.
     *
     * Native-vision main models keep the original behaviour exactly: the tool
     * returns the pixels and the provider attaches them.
     *
     * A main model WITHOUT native image input only reaches here because a Vision
     * Group is configured (that's the tool-exposure gate in [agentTools]). For
     * that case we do NOT return pixels — a text-only model can't decode them and
     * the provider (OpenAIProvider T264) silently drops them to a placeholder.
     * Instead we hand the bytes to the Vision Group, get a text DESCRIPTION back,
     * and return that as the tool output. `imageData` is left null so no pixels
     * are attached, but `imageFilePath` is preserved so the on-screen tool block
     * still shows the image the user's model "read". Mirrors iOS
     * AIChatViewModel+ConcurrentTools read_image branch.
     */
    private suspend fun executeReadImageTool(argsJson: String): ToolExecutionResult {
        val base = ReadImageTool.execute(argsJson, activeSessionId, context)
        // [T-android-vision-group / GH#182] Optional caller instruction focusing
        // what to learn from the image.
        val customPrompt = try {
            JSONObject(argsJson).optString("prompt", "").trim().ifEmpty { null }
        } catch (_: Exception) { null }
        // Failed decode / missing file → unchanged.
        if (!base.success || base.imageData == null) return base
        if (currentModelHasNativeVision) {
            // The model sees the pixels itself; a prompt adds no routing here, but
            // echo it as context so the tool block reflects the model's intent.
            return if (customPrompt != null) {
                base.copy(output = base.output + "\n\n[Requested focus: " + customPrompt + "]")
            } else base
        }

        val bytes = base.imageData
        val mime = base.imageMimeType ?: "image/jpeg"
        val result = com.openminis.app.tools.VisionGroupResolver.describe(
            repo = providerRepository,
            context = context,
            imageData = bytes,
            mimeType = mime,
            seed = kotlin.math.abs(argsJson.hashCode()),
            customPrompt = customPrompt,
            // [T-vision-group-attribution / GH#182] iOS rewrites the tool block's
            // live content here so the card names the model as it works. Android
            // has no equivalent channel — no tool streams partial output to its
            // card, and the progress label ("Minis is reading Image",
            // ChatToolFormatting.kt:102) is a static per-tool string. Building
            // that plumbing is a separate change, so for now the per-attempt
            // signal goes to the log, where a fallback is still traceable. The
            // RESULT-side attribution (which model answered, what was tried
            // first) is fully implemented and is what the user actually reads.
            onAttempt = { a ->
                android.util.Log.i(
                    "VisionGroup",
                    "[Vision] attempt ${a.index}/${a.total} via ${a.modelName}",
                )
            },
        )
        val framed = when (result) {
            is com.openminis.app.tools.VisionGroupResolver.VisionResult.Success ->
                com.openminis.app.tools.VisionGroupResolver.framedDescription(
                    result,
                    com.openminis.app.tools.VisionGroupResolver.groupName(providerRepository),
                    question = customPrompt,
                )
            is com.openminis.app.tools.VisionGroupResolver.VisionResult.Failure ->
                com.openminis.app.tools.VisionGroupResolver.failureText(result.reason)
        }
        // Deliberately still success=true even on describe failure: an errored
        // tool result tends to make models retry in a loop, whereas this lets the
        // model plainly tell the user the image couldn't be analyzed.
        return base.copy(
            output = base.output + "\n\n" + framed,
            imageData = null,
            imageMimeType = null,
        )
    }

    /**
     * Mirror of iOS AIChatViewModel post-tool hook (Agent/Chat/AIChatViewModel.swift:5387 / :5408):
     * when the agent writes or edits a SKILL.md inside a `/skills/` directory
     * we ask SkillRepository to re-scan disk so the new skill is visible
     * immediately, without waiting for app restart.
     */
    private fun maybeReloadSkillsForPath(argsJson: String) {
        runCatching {
            val path = JSONObject(argsJson).optString("path", "")
            if (path.contains("/skills/") && path.endsWith("SKILL.md")) {
                skillRepository?.reloadFromDisk()
            }
        }
    }

    /** Sentinel returned by the bash wrapper when bash is missing at run time,
     *  distinct from a script that legitimately exits 127 (T-bash-on-demand M5). */
    private val BASH_MISSING_SENTINEL = 119

    /** Wrap a script to run under bash via a guest-side self-written temp file
     *  (base64, single line, self-cleaning), guarding on `command -v bash` so a
     *  vanished bash is detected precisely for inline self-heal.
     *
     *  The whole wrapper runs inside a SUBSHELL `( … )`. This is load-bearing on
     *  Android: PersistentShell drives commands as `{cmd}; echo …_EXIT_$?…` and
     *  reads the exit code from that marker line. A bare `|| exit 119` would exit
     *  the persistent shell process itself BEFORE the marker echo runs, so no
     *  marker is emitted and PersistentShell.parseExitCode falls back to -1 —
     *  the M5 self-heal sentinel check (== 119 / 30464) then never matches and a
     *  vanished bash is never re-installed. Wrapping in a subshell makes
     *  `exit 119` leave only the subshell, so `$?` = 119 reaches the marker. */
    private fun wrapForBash(script: String): String {
        // [T-heredoc-trailing-newline] A heredoc that ends the decoded file with
        // no trailing newline fails with "unexpected end of file". Guarantee one.
        val normalized = if (script.endsWith("\n")) script else script + "\n"
        val b64 = android.util.Base64.encodeToString(
            normalized.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "( command -v bash >/dev/null 2>&1 || exit $BASH_MISSING_SENTINEL; " +
            "printf %s '$b64' | base64 -d > /tmp/.minis-exec-\$\$.sh && " +
            "bash /tmp/.minis-exec-\$\$.sh; rc=\$?; rm -f /tmp/.minis-exec-\$\$.sh; exit \$rc )"
    }

    private suspend fun executeShellCommand(
        argsJson: String,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>,
        assistantId: String,
        currentText: String,
    ): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            var command = args.optString("command", "")
            val timeoutSec = args.optInt("timeout", 900).coerceIn(1, 900)
            val delaySec = args.optInt("delay", 0).coerceAtLeast(0)
            val toolTitle = args.optString("tool_title", "shell_execute")

            if (command.isBlank()) {
                return ToolExecutionResult("Error: 'command' is required", false, toolTitle = toolTitle)
            }

            // [T-android-overlay-finalize item 1] Removed the
            // shell-specific status hack ("shell: $toolTitle"). Since the
            // dispatch loop (~5003) now surfaces `tool_title` in the overlay
            // label uniformly via SessionActivityTracker.updateToolStatus(
            // status, toolName, isRunning, toolTitle), the per-tool override
            // produced redundant "shell / shell: <title>" rows. Lifecycle
            // status ("Running: shell_execute") set by the dispatch loop is
            // sufficient.

            // Delay execution: block the agent flow without occupying the shell,
            // allowing other concurrent tasks to use it during the wait period.
            if (delaySec > 0) {
                for (remaining in delaySec downTo 1) {
                    val idx = toolBlocks.indexOfFirst { it.id == toolId }
                    if (idx >= 0) {
                        val mm = remaining / 60
                        val ss = remaining % 60
                        val countdown = if (mm > 0) String.format("%d:%02d", mm, ss) else "${ss}s"
                        toolBlocks[idx] = toolBlocks[idx].copy(content = "⏳ Waiting $countdown before executing...")
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
                val idx = toolBlocks.indexOfFirst { it.id == toolId }
                if (idx >= 0) {
                    toolBlocks[idx] = toolBlocks[idx].copy(content = "")
                }
            }

            // [diag] sessionId vs realSessionId mismatch was the root cause
            // of the Chinese-emoji filename "disappears" bug. `activeSessionId`
            // resolves to the persisted id once `ensureSession()` has run, so
            // every shell runs in a directory that survives VM recreation.
            val dispatchSessionId = activeSessionId
            android.util.Log.w("ShellExecDiag",
                "executeShell dispatch=$dispatchSessionId rawSessionId=$sessionId realSessionId=$realSessionId isDraft=$isDraft cmd=${command.take(120).replace('\n', ' ')}")

            // [T-bash-on-demand] Detect busybox-ash-incompatible bash syntax and,
            // if found, transparently install + switch to bash. Install time is
            // NOT charged against the command timeout (OnDemandBash has its own
            // budget). `command` is rewritten to the bash-wrapped form on the S/E
            // path; `bashReminder` is attached if we fall back to sh. Only this
            // agent path runs here; the in-app terminal is untouched.
            BashismDetector.ensureLoaded(context)
            val bashism = BashismDetector.detect(command)
            var bashReminder: String? = null
            val originalCommand = command
            var bashScript: String? = null   // set when we bash-wrapped; enables M5 self-heal retry
            if (bashism.needsBash) {
                val executor = OnDemandBash.Executor { c, t ->
                    ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
                }
                when (val outcome = OnDemandBash.ensureBash(context, executor)) {
                    is OnDemandBash.Outcome.Available -> {
                        if (bashism.mustSwitchInterpreter) {
                            // §3.2 M3: self-write the script in the guest (base64,
                            // single line, self-cleaning) and run it under bash.
                            // The `command -v bash || exit 119` guard detects a
                            // bash that vanished after our cache check (M5) so we
                            // can self-heal below instead of failing.
                            command = wrapForBash(command)
                            bashScript = originalCommand // remember for self-heal retry
                        }
                        // T1-only (script invokes bash itself) → run as-is under sh.
                    }
                    is OnDemandBash.Outcome.Unavailable ->
                        bashReminder = BashismReminder.build(bashism.hits, outcome.reason)
                }
            }

            var result = ExecutionCoordinator.execute(
                sessionId = dispatchSessionId,
                command = command,
                timeout = timeoutSec * 1000L,
                lineCallback = lc@{ rawLine ->
                    // Strip any OSC MinisOpenURL markers emitted by
                    // /usr/local/bin/minis-open and forward the captured
                    // URLs to the broker so the chat screen can present the
                    // in-app preview. Lines that were *entirely* a marker
                    // (nothing visible afterwards) are dropped so the tool
                    // output doesn't grow blank rows.
                    val (cleanedLine, capturedUrls) = MinisUrlMarker.extract(rawLine)
                    for (raw in capturedUrls) MinisOpenUrlBroker.offer(raw)
                    if (cleanedLine.isEmpty() && rawLine.isNotEmpty()) return@lc

                    val idx = toolBlocks.indexOfFirst { it.id == toolId }
                    if (idx >= 0) {
                        val current = toolBlocks[idx].content
                        val updated = if (current.isEmpty()) cleanedLine else "$current\n$cleanedLine"
                        // Keep last 50 lines for display
                        val trimmed = updated.lines().takeLast(50).joinToString("\n")
                        toolBlocks[idx] = toolBlocks[idx].copy(content = trimmed)
                        viewModelScope.launch(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, currentText, true, toolBlocks)
                        }
                    }
                },
            )

            // [T-bash-on-demand] M5 self-heal: our bash wrapper returns sentinel
            // 119 when bash vanished (user apk del'd) after we cached it
            // available. Re-probe + reinstall once and rerun THIS command under
            // bash inline, so it still succeeds instead of failing.
            // Accept both the raw sentinel (119) and the wait(2)-encoded status
            // (119 << 8 = 30464) the coordinator may surface.
            if ((result.exitCode == BASH_MISSING_SENTINEL ||
                    result.exitCode == (BASH_MISSING_SENTINEL shl 8)) && bashScript != null) {
                OnDemandBash.markDisappeared()
                val executor = OnDemandBash.Executor { c, t ->
                    ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
                }
                val healed = OnDemandBash.ensureBash(context, executor)
                command = if (healed is OnDemandBash.Outcome.Available) wrapForBash(bashScript!!) else bashScript!!
                result = ExecutionCoordinator.execute(
                    sessionId = dispatchSessionId, command = command, timeout = timeoutSec * 1000L)
            }

            // Also scrub markers from the aggregated one-shot output and
            // broker any URLs that only appeared there (defensive — handles
            // executors that don't fire lineCallback for every line).
            val (cleanedOutput, oneShotUrls) = MinisUrlMarker.extract(result.output)
            for (raw in oneShotUrls) MinisOpenUrlBroker.offer(raw)
            val output = if (cleanedOutput.isBlank()) "(no output)" else cleanedOutput
            val exitInfo = if (result.exitCode != 0) " (exit code ${result.exitCode})" else ""
            // Exit code 124 is the BusyBox/GNU timeout-utility convention for
            // a command that exceeded its budget. PersistentShell returns this
            // when its `withTimeoutOrNull(timeout)` wrapper fires.
            val timedOut = result.exitCode == 124

            // Redact env-var values that leaked into the captured output
            // before the model sees them. No-op when Privacy Mode is OFF.
            // Done after exitInfo is appended so the suffix can't accidentally
            // contain a secret that escaped masking. The user-visible streamed
            // content (toolBlocks above) is intentionally left unmasked.
            val finalOutput = "$output$exitInfo"
            val (redactedOut, redactHits) = com.openminis.app.data.EnvVarRedactor.redactIfEnabled(finalOutput)
            if (redactHits > 0) {
                android.util.Log.i("EnvVarRedact", "shell_execute: masked $redactHits env-var value(s) in tool result")
            }

            // [T-bash-on-demand] M5 self-heal: bash disappeared (user apk del'd)
            // → re-probe next time.
            if (result.exitCode == 127 && bashism.mustSwitchInterpreter) {
                OnDemandBash.markDisappeared()
            }
            // §4.2: append the bashism reminder when we fell back to sh and the
            // command failed OR any silent-class rule was hit (S-class exit-0
            // exception, default-on).
            val withReminder = bashReminder?.let { rem ->
                if (result.exitCode != 0 || bashism.hasSilent) "$redactedOut\n\n$rem" else redactedOut
            } ?: redactedOut

            ToolExecutionResult(
                output = withReminder,
                success = result.exitCode == 0,
                toolTitle = toolTitle,
                timedOut = timedOut,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    }

    private suspend fun executeBrowserUseTool(argsJson: String): ToolExecutionResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser_use input", false)

        return try {
            val result = browserTabPool.execute(input)
            val toolTitle = try {
                JSONObject(argsJson).optString("tool_title", "browser_use")
            } catch (_: Exception) { "browser_use" }

            var output = result.text
            var persistentImagePath: String? = result.imageFilePath
            var inferenceBytes: ByteArray? = null

            // Persist browser screenshots to /var/minis/browser/<session>/ so the
            // agent can reference them via minis:// in subsequent tool calls
            // (mirrors iOS AIChatViewModel case "browser_use").
            val base64 = result.base64Image
            var linuxImagePath: String? = null
            if (base64 != null) {
                val raw = try {
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                } catch (_: Exception) { null }
                if (raw != null) {
                    // Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000
                    // long edge across attachments / browser / read_image.
                    inferenceBytes = resizeJpegToMaxEdge(raw, 2000) ?: raw
                    val filename = "screenshot_${System.currentTimeMillis() / 1000}.jpg"
                    val persistPath = persistBrowserArtifact(filename, raw)
                    if (persistPath != null) {
                        persistentImagePath = persistPath
                        linuxImagePath = "/var/minis/browser/$filename"
                        linuxPathToMinisURL(linuxImagePath)?.let {
                            output = "$output\nminis_url: $it"
                        }
                    }
                }
            }

            // Persist fetched files (fetch action) and append minis_url
            val fetchData = result.fetchedFileData
            val fetchName = result.fetchedFileName
            if (fetchData != null && fetchName != null) {
                persistBrowserArtifact(fetchName, fetchData)
                linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {
                    output = "$output\nminis_url: $it"
                }
            }

            ToolExecutionResult(
                output = output,
                success = result.success,
                imageData = inferenceBytes,
                imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
                toolTitle = toolTitle,
                pageURL = result.pageURL,
                imageFilePath = persistentImagePath,
                imageLinuxPath = linuxImagePath,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    }

    /**
     * Write bytes to <filesDir>/minis-sessions/<sessionId>/browser/<filename>.
     * That directory is bind-mounted to `/var/minis/browser/` so the agent can
     * read it back via file_read / file_write / minis:// URLs.
     * Returns the host absolute path on success, null otherwise.
     */
    private fun persistBrowserArtifact(filename: String, data: ByteArray): String? {
        val sid = activeSessionId.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val dir = java.io.File(context.filesDir, "minis-sessions/$sid/browser").apply { mkdirs() }
            val file = java.io.File(dir, filename)
            file.writeBytes(data)
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "persistBrowserArtifact failed: ${e.message}")
            null
        }
    }

    /**
     * Convert a Linux path under /var/minis/ to a percent-encoded minis:// URL.
     * Mirrors iOS AIChatViewModel.linuxPathToMinisURL.
     */
    private fun linuxPathToMinisURL(path: String): String? {
        val prefix = "/var/minis/"
        if (!path.startsWith(prefix)) return null
        val rest = path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val namespace = rest.substring(0, slash)
        val filename = rest.substring(slash + 1)
        val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "minis://$namespace/$encoded"
    }

    /**
     * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
     * if already within bounds. Mirrors iOS AIChatViewModel.resizedImageData.
     */
    private fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxEdge) { bmp.recycle(); return null }
        val scale = maxEdge.toFloat() / longest
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        resized.recycle()
        return out.toByteArray()
    }

    private fun executeMemoryWriteTool(argsJson: String): ToolExecutionResult {
        val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
        if (!_memoryEnabled.value) {
            val msg = "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."
            return ToolExecutionResult(msg, false, toolTitle = "Memory (disabled)")
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        // Record for SessionMemorySheet
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        )
        return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
    }

    private fun executeMemoryGetTool(argsJson: String): ToolExecutionResult {
        val repo = memoryRepository ?: return ToolExecutionResult("Error: Memory not available", false)
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        )
        return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
    }

    // ─── UI Helpers ──────────────────────────────────────────────────────

    private fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        // T-streaming-side-channel: during a live turn, write high-frequency
        // fields into [_streamingById] instead of mutating the canonical
        // message list. This keeps the `messages` StateFlow reference stable
        // across the turn so ChatScreen's top-level reads
        // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
        // a full recompose of the 8980-line composable on every token.
        //
        // On stream end (isStreaming=false), drain the accumulated delta
        // back into the canonical message in a single `_messages` emit, then
        // clear the side-channel entry so post-turn reads (history rebuild,
        // persist, agent loop) see the canonical truth.
        if (isStreaming) {
            val toolBlocksImmutable = toolBlocks.toList()

            // [T-android-stream-flush-dualpath] Dual-path flush at the
            // message-accumulation layer (NOT per-fragment, which never
            // throttled). Decide whether to publish this delta now:
            //   • structural change (toolBlocks count / awaiting flag) →
            //     publish immediately — these drive tool-bubble UI and must
            //     never be coalesced away or the bubble state stalls.
            //   • else time-path: enough ms since last publish for this length.
            //   • else newline fast-path: a line break in the newly-streamed
            //     chunk + ≥50 new chars, gated to short docs (iOS parity).
            // When none fire, stash the latest as a trailing publish so the
            // final chunk before a pause still lands; a fresh delta cancels
            // and replaces it.
            val st = streamFlushStates.getOrPut(id) {
                StreamFlushState().also { it.lastFlushedLen = 0 }
            }
            val prev = _streamingById.value[id]
            // [T-android-stream-flush-review] Structural change also covers an
            // in-place tool-block STATUS flip (running → success), not just a
            // count change — otherwise a spinner→checkmark could lag up to one
            // throttle tier. Compare a cheap (kind,status) fingerprint.
            val toolStatusChanged = prev != null &&
                prev.toolBlocks.size == toolBlocksImmutable.size &&
                toolBlocksImmutable.indices.any { i ->
                    prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
                }
            val structuralChange = prev == null ||
                prev.toolBlocks.size != toolBlocksImmutable.size ||
                prev.isAwaitingModelResponse != isAwaitingModelResponse ||
                toolStatusChanged
            val now = System.currentTimeMillis()
            val elapsed = now - st.lastFlushMs
            val throttle = streamFlushThrottleMs(content.length)
            val newChunk = if (content.length > st.lastFlushedLen) {
                content.substring(st.lastFlushedLen.coerceAtMost(content.length))
            } else ""
            val unflushed = content.length - st.lastFlushedLen
            val newlineFlush = content.length < NEWLINE_FLUSH_MAX_LEN &&
                newChunk.contains('\n') &&
                unflushed >= NEWLINE_FLUSH_MIN_CHARS

            fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
                _streamingById.value = _streamingById.value + (
                    id to StreamingDelta(
                        content = text,
                        toolBlocks = blocks,
                        isAwaitingModelResponse = awaiting,
                    )
                )
                st.lastFlushMs = System.currentTimeMillis()
                st.lastFlushedLen = text.length
            }

            if (structuralChange || elapsed >= throttle || newlineFlush) {
                st.trailingJob?.cancel()
                st.trailingJob = null
                st.pendingContent = null
                publish(content, toolBlocksImmutable, isAwaitingModelResponse)
            } else {
                // Throttled: always record this delta as the freshest pending
                // value, so whenever the trailing job fires it publishes the
                // latest text — not whatever was captured when it was first
                // scheduled (review #2). Schedule the job only once.
                st.pendingContent = content
                st.pendingBlocks = toolBlocksImmutable
                st.pendingAwaiting = isAwaitingModelResponse
                if (st.trailingJob == null) {
                    val wait = (throttle - elapsed).coerceAtLeast(16L)
                    st.trailingJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(wait)
                        val pc = st.pendingContent
                        if (pc != null) {
                            publish(pc, st.pendingBlocks, st.pendingAwaiting)
                            st.pendingContent = null
                        }
                        st.trailingJob = null
                    }
                }
            }
            // [T-android-timeout-while-running] If a transient banner
            // (`message.error`) is still on the canonical assistant message
            // when a fresh streaming event arrives, the banner is stale —
            // the model is producing again, by construction the prior
            // transient timeout / retry / fallback has been resolved.
            // Clear it in the same mutation. setTransientInlineError /
            // setInlineError are the only paths that write `error`; the
            // terminal path (setInlineError) sets isStreaming=false on the
            // same message in the same emit, so it cannot reach this
            // branch and the clear is safe.
            //
            // 𝙓𝙄𝙉 TG36302 (0.10): user saw a red "timeout / retry" banner
            // glued to the bottom of the conversation while the agent
            // continued running (LM Studio tool loop on 30/30, "Minis is
            // thinking" indicator). Caused by (a) the fallback-switch branch in
            // runAgentLoop not calling clearInlineError(), and (b) the
            // streaming-side-channel writing every subsequent delta into
            // _streamingById without ever touching _messages where
            // `error` lives. (a) is fixed at the fallback site; (b) is
            // fixed here defensively so any future write-path that forgets
            // to clear can't strand a stale banner across the rest of
            // the turn.
            val canonical = _messages.value
            val canonicalIdx = canonical.indexOfLast { it.id == id }
            if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
                val updated = canonical.toMutableList()
                updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
                _messages.value = updated
            }
            return
        }
        // [T-android-stream-flush-dualpath] Stream end → cancel any pending
        // trailing flush and drop the throttle accumulator for this message;
        // the canonical drain below publishes the final, complete text.
        clearStreamFlushState(id)
        // Stream end → sync delta into canonical message + clear side-channel.
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) {
            // The message itself is gone (e.g. clearChat raced ahead) —
            // just clear any leftover stream delta and bail.
            if (_streamingById.value.containsKey(id)) {
                _streamingById.value = _streamingById.value - id
            }
            return
        }
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = content,
            isStreaming = false,
            toolBlocks = toolBlocks.toList(),
            isAwaitingModelResponse = isAwaitingModelResponse,
        )
        _messages.value = updated
        if (_streamingById.value.containsKey(id)) {
            _streamingById.value = _streamingById.value - id
        }
    }

    /**
     * Read a message's content + toolBlocks honoring any active streaming
     * delta. Use this from non-render code that needs the "current" view of
     * a message during a live turn (e.g. agent history builders, persistence
     * snapshots) without forcing the render layer to consult the delta map.
     */
    internal fun effectiveContent(id: String): String? {
        val delta = _streamingById.value[id]
        if (delta != null) return delta.content
        return _messages.value.firstOrNull { it.id == id }?.content
    }

    /**
     * Force-drain any outstanding streaming delta for [id] back into the
     * canonical message and clear the side-channel slot. Called from turn
     * exit paths (cancel / error / retry / resume / clearChat) so the
     * canonical message reflects all accumulated content even if the last
     * [updateAssistantMessage] call had isStreaming=true.
     */
    private fun flushStreamingDelta(id: String) {
        val delta = _streamingById.value[id] ?: return
        val current = _messages.value
        val idx = current.indexOfLast { it.id == id }
        if (idx >= 0) {
            val updated = current.toMutableList()
            updated[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            _messages.value = updated
        }
        // [T-android-stream-flush-review] Cancel the pending trailing flush
        // BEFORE clearing the side channel — otherwise its viewModelScope
        // coroutine (not cancelled by streamJob.cancel) fires later and
        // re-adds the orphan side-channel entry, reviving a stale "thinking"
        // row after the turn was stopped/drained.
        clearStreamFlushState(id)
        _streamingById.value = _streamingById.value - id
    }

    /** Drain ALL outstanding streaming deltas (called on global resets). */
    private fun flushAllStreamingDeltas() {
        clearAllStreamFlushStates()
        val pending = _streamingById.value
        if (pending.isEmpty()) return
        val current = _messages.value.toMutableList()
        var changed = false
        for ((id, delta) in pending) {
            val idx = current.indexOfLast { it.id == id }
            if (idx < 0) continue
            current[idx] = current[idx].copy(
                content = delta.content,
                isStreaming = false,
                toolBlocks = delta.toolBlocks,
                isAwaitingModelResponse = delta.isAwaitingModelResponse,
            )
            changed = true
        }
        if (changed) _messages.value = current
        _streamingById.value = emptyMap()
    }

    /**
     * Build the ordered AgentContentPart list for this turn by walking the slice of
     * `allToolBlocks` that belongs to the current turn (from `turnStartBlockIndex` to
     * the end). Text blocks become `Text`, tool_use blocks become `ToolUse` — the
     * original stream order is preserved by the list slice order. Thinking and info
     * blocks are skipped (they're persisted via `reasoningContent` or not at all).
     */
    private fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> {
        if (turnStartBlockIndex >= allToolBlocks.size) return emptyList()
        val out = mutableListOf<AgentContentPart>()
        for (i in turnStartBlockIndex until allToolBlocks.size) {
            val block = allToolBlocks[i]
            when (block.kind) {
                "text" -> if (block.content.isNotEmpty()) {
                    out.add(AgentContentPart.Text(block.content))
                }
                "tool_use" -> {
                    val name = block.toolName
                    if (name.isBlank()) continue
                    val inputStr = toolCallInputs[block.id] ?: "{}"
                    val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                    // [T-android-gemini3-thoughtsig / #179] Carry the block's
                    // signature into the persisted/replayed ToolUse.
                    out.add(AgentContentPart.ToolUse(block.id, name, inputJson, thoughtSignature = block.thoughtSignature))
                }
                // "thinking" / "info" → not persisted in parts
                else -> { /* skip */ }
            }
        }
        return out
    }

    /**
     * Persist a single agent turn: the ordered list of AgentContentParts produced
     * in this turn (text segments and tool_use blocks interleaved in the order they
     * were emitted). Mirrors iOS's per-turn `persistAgentMessage` — one DB row per
     * turn, no cross-turn accumulation, preserving `parts` array order.
     *
     * This is the right entry point for the agent loop; the legacy
     * `persistAssistantMessage(text, usage, toolBlocks, ...)` accumulated all history
     * on every call, which caused:
     *   - Duplicate tool_use rows across turns (crashed LazyColumn key uniqueness)
     *   - Orphan tool_result detection thrashing (sanitize injecting placeholders)
     *   - Lost chronological text ↔ tool_use ordering within a single turn
     */
    /**
     * Serialize a turn's [AgentContentPart] list into the on-disk parts_json
     * shape (text + toolUse blocks). Shared by [persistAssistantTurn] (the
     * authoritative per-turn row write) and the live session-list preview
     * update ([T-android-session-last-message-live-tool-call]) so both produce
     * an identical payload that [ChatRepository.extractTextPreview] understands.
     */
    private fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String = buildString {
        append("[")
        parts.forEachIndexed { index, part ->
            if (index > 0) append(",")
            when (part) {
                is AgentContentPart.Text -> {
                    append("""{"type":"text","value":${escapeJson(part.text)}}""")
                }
                is AgentContentPart.ToolUse -> {
                    // Skip tool_use with blank name — upstream bug guard.
                    val name = part.name
                    if (name.isBlank()) return@forEachIndexed
                    val inputStr = part.input.toString()
                    val meta = toolBlockMeta[part.id]
                    val desc = meta?.toolTitle ?: ""
                    val pageURL = meta?.browserURL ?: ""
                    val imgPath = meta?.imageFilePath ?: ""
                    // [T-android-gemini3-thoughtsig / #179] Persist the captured
                    // signature (null-literal when absent) so it survives a session
                    // reload and can be replayed on the historical functionCall.
                    val sigJson = part.thoughtSignature?.let { escapeJson(it) } ?: "null"
                    append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(part.id)},"name":${escapeJson(name)},"input":${escapeJson(inputStr)},"description":${escapeJson(desc)},"pageURL":${escapeJson(pageURL)},"imageFilePath":${escapeJson(imgPath)},"thoughtSignature":$sigJson}}""")
                }
                else -> { /* tool_result is persisted via persistToolResultMessage */ }
            }
        }
        append("]")
    }

    private suspend fun persistAssistantTurn(
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String? = null,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    ): String? {
        if (parts.isEmpty()) return null
        val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
        }
        val entity = chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            // [T-token-attribution-snapshot] From the live request context, not
            // the session row — see currentModelSnapshot().
            modelSnapshot = currentModelSnapshot(),
        )
        return entity.id
    }

    @Deprecated("Use persistAssistantTurn(parts, ...) for per-turn delta persistence")
    private suspend fun persistAssistantMessage(
        text: String,
        usage: LLMUsage?,
        toolBlocks: List<AssistantBlock>? = null,
        toolCallInputs: Map<String, String> = emptyMap()
        ,
        reasoningContent: String? = null,
    ) {
        if (text.isEmpty() && (toolBlocks == null || toolBlocks.isEmpty())) return

        val partsJson = buildString {
            append("[")
            var first = true
            if (text.isNotEmpty()) {
                append("""{"type":"text","value":${escapeJson(text)}}""")
                first = false
            }
            toolBlocks?.forEach { block ->
                // Only persist real tool-use blocks. text / thinking / info blocks are
                // either represented via the `text` parameter (accumulatedText) or
                // reconstructed from thinking metadata; persisting them as `toolUse`
                // produces empty-name records that Anthropic rejects with
                // "messages.N.content.M.tool_use.name: String should have at least 1 character".
                if (block.kind != "tool_use") return@forEach
                if (block.toolName.isBlank()) return@forEach  // extra safety
                if (!first) append(",")
                first = false
                val inputJson = toolCallInputs[block.id]?.let { escapeJson(it) } ?: "\"\""
                val pUrl = block.browserURL ?: ""
                val iPath = block.imageFilePath ?: ""
                append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(block.id)},"name":${escapeJson(block.toolName)},"input":$inputJson,"description":${escapeJson(block.toolTitle)},"pageURL":${escapeJson(pUrl)},"imageFilePath":${escapeJson(iPath)},"thoughtSignature":null}}""")
            }
            append("]")
        }
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
        }
        chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            modelSnapshot = currentModelSnapshot(),
        )
    }

    /** Persist tool results as a user-role message (mirrors iOS behavior). */
    private suspend fun persistToolResultMessage(parts: List<AgentContentPart>): String? {
        val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
        if (results.isEmpty()) return null
        val partsJson = buildString {
            append("[")
            results.forEachIndexed { index, result ->
                if (index > 0) append(",")
                val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
                append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError},"snapshot":{"type":"text","text":$snapshotText}}}""")
            }
            append("]")
        }
        val entity = chatRepository.appendMessage(realSessionId.ifEmpty { sessionId }, "user", partsJson)
        return entity.id
    }

    private fun buildSystemPrompt(): String? {
        // Cache-friendly layout: keep `base` byte-stable by stripping out anything
        // that varies per request, then append a "Runtime context" suffix at the
        // very end with all the dynamic bits (date, timezone, locale, configured
        // minis-model-use count). OpenAI / DeepSeek prompt caching is prefix-
        // based, so the longer the static head, the better the hit rate.
        // Pre-T122 the prompt embedded `Current time: yyyy-MM-dd HH:mm` mid-base,
        // which guaranteed cache misses across minute boundaries — even a quick
        // follow-up could land on a different minute and pay full ingestion.
        val today = java.time.LocalDate.now()
        val dateStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        val tzId = java.util.TimeZone.getDefault().id
        val lang = context.resources.configuration.locales[0].toLanguageTag()

        // Count of agent-loop-visible models for the `minis-model-use` CLI
        // (exposed as a shell command via the native_offload handler).
        val modelUseCount = try { providerRepository.resolvedAgentLoopEntries().size } catch (_: Exception) { 0 }

        // [T-soul-md] Layer 1 is rendered by SystemPromptBuilder, which
        // owns the "You are <name>, a capable AI assistant running on an
        // Android device ..." identity sentence (parametric on SOUL.md's
        // `name` field) and optionally appends a clearly-labeled
        // Personality section from SOUL.md's body. The original wording
        // is preserved inside SystemPromptBuilder.IDENTITY_TEMPLATE so we
        // don't regress model behavior that depended on it. When SOUL.md
        // has no personality body, identitySection() returns the identity
        // sentence with its original single trailing space — the full
        // assembled prompt then matches the pre-SOUL prompt byte-for-byte.
        val identitySection = com.openminis.app.agent.SystemPromptBuilder.identitySection(context)
        // [T-memory-toggle-gates-injection-and-tools-android] Mirror the iOS
        // gate: when memory is disabled for this session, replace the
        // "memory_write / memory_get" tool bullets and the "Memory system:"
        // guidance block with a single explicit DISABLED notice. The model
        // never sees the tools either (filtered in agentTools above), but
        // surfacing the state in the prompt lets it explain why memories
        // aren't reachable when the user asks. The fragment / tool dual
        // gate is symmetrical: enable both or disable both, never mismatch.
        val memoryOn = _memoryEnabled.value
        val toolListMemoryBullets = if (memoryOn) {
            """
- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.
- memory_get: Recall memories with keyword search. Check memory at the start of new topics to leverage past knowledge."""
        } else {
            // Empty — no memory_write / memory_get bullets when disabled.
            // The "Memory system:" section below also collapses, so the
            // model gets a coherent picture rather than half-mentioned
            // tools it can't actually call.
            ""
        }
        val memorySystemSection = if (memoryOn) {
            """

Memory system (currently ENABLED):
- memory_write writes to today's daily log (YYYY-MM-DD.md) — use it for session notes, key facts, project context, things learned, and action items.
- GLOBAL.md (/var/minis/memory/GLOBAL.md) stores persistent preferences, settings, and general-purpose conventions. To read it, use file_read (NOT memory_get). To update it, use file_read first then file_edit. If GLOBAL.md does not exist yet, use file_write to create it directly.
- IMPORTANT: Only write to GLOBAL.md when the user explicitly asks (e.g. 'remember this globally', 'save to global memory'). Before editing, deduplicate and clean up — avoid ambiguity, repetition, or daily-log-style entries. GLOBAL.md should contain only concise, reusable knowledge (preferences, settings, conventions), NOT session logs or transient context.
- Use memory_get to recall past knowledge before starting tasks — check if there are relevant memories that can help.
- Proactively save memories (via memory_write to daily log) when you discover user preferences or important patterns — don't wait to be asked.
- When the user says 'remember this' or similar, use memory_write to persist to the daily log. Only write to GLOBAL.md if the user specifically asks for global/persistent storage.
- What NOT to remember: passwords, API keys, tokens, secrets, or any sensitive credentials. Warn the user about the risk first; only proceed if they explicitly confirm.
- Keep memories concise, factual, and general-purpose — avoid noise that won't be useful later."""
        } else {
            """

Memory system (currently DISABLED):
- The user has turned OFF memory injection and memory tools for this session. GLOBAL.md and recent daily logs are NOT included in this prompt, and the memory_write / memory_get tools are NOT available — do not attempt to call them.
- If the user asks why earlier memories aren't visible, or asks you to save something, tell them memory is currently disabled and point them at the /memory slash command or [Settings → Memory](minis://settings/memory) to re-enable it.
- SOUL.md (personality / identity) is unaffected by this toggle; the persona section above still applies."""
        }
        val base = identitySection + """You should proactively use shell commands to accomplish the user's tasks — installing packages (apk add), writing and running scripts, managing files, networking, and any other operations a Linux terminal can perform.

Available tools:
- shell_execute: Run any shell command. Each invocation is an isolated process with stdout/stderr captured. Prefer this for most tasks — it is a real Linux environment with persistent filesystem. Common tools (python3, pip, curl, wget, git, ssh, etc.) can be installed via apk add; Python packages via pip install. Use `which <cmd>` to check if a tool is already installed before running apk add — many packages persist across sessions. When you need to wait before checking results (e.g. polling, waiting for a process), use the `delay` parameter instead of `sleep` in the command — delay blocks the agent flow without occupying the shell, so other concurrent tasks can use it during the wait. This avoids resource contention. Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. Without a scheduler or timed-callback tool, `delay` is your ONLY wait mechanism within a turn — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. If polling to completion is genuinely not worth blocking the turn, close honestly instead: state that the task keeps running in the background, that you will only learn its outcome when the user next messages (or they ask you to check), and — if something must fire on a schedule beyond this conversation — point them to the options under 'Scheduled tasks' later in this prompt (native alarm reminder or a system-level schedule; those notify the USER, they do not wake you).
- file_read: Read file contents (faster than cat).
- file_write: Create new files or overwrite existing files (faster than echo/tee).
- file_edit: Edit existing files with exact string replacement (old_string → new_string). Preferred over file_write for modifications — always file_read first.
- browser_use: Web browsing (navigate, screenshot, click, type, get_text, scroll, scroll_and_collect, get_readable, get_backbone, fetch, etc.). Starts with a desktop Chrome user agent. Use screenshot to see the page.
  当 browser_use 触达 Google 登录 / OAuth 页（accounts.google.com、signin.google.com、myaccount.google.com、oauth2.googleapis.com 等）或网页返回 "disallowed_useragent" / 403 包含 "browser is not secure" 字样时，**不要重试或尝试登录** — Google 永久禁止 in-app WebView 完成登录，重试只会浪费 turn。改为告诉用户："此页面需要在系统 Chrome 完成登录" 并给出可点击的 Markdown link [在 Chrome 中打开](https://accounts.google.com/...)。点该 link 时 app 会跳出 Custom Tab；用户在 Chrome 完成操作后，请他**把所需结果（邮件正文 / 文档摘要 / 表格数据）粘贴回 chat**，你再继续帮他处理。这是 Android 平台限制，不是 bug。${toolListMemoryBullets}

Shared directory /var/minis/ (bidirectional read/write between shell and app):
  /var/minis/attachments/ — Media files (images, audio, video). Display inline with ![desc](minis://attachments/filename).
  /var/minis/workspace/   — Working files (scripts, data, configs). Link with [name](minis://workspace/filename).
  /var/minis/offloads/    — Auto-saved large outputs. Read with file_read.
  /var/minis/browser/     — Browser screenshots and extracts.
  /var/minis/shared/      — Cross-session shared storage for artifacts and documents. Organize by project or topic (e.g. shared/myproject/, shared/datasets/). Do NOT store temporary files here.
  /var/minis/memory/GLOBAL.md    — Persistent global memory (read-only, user-maintained via Settings).
  /var/minis/memory/YYYY-MM-DD.md — Daily memory log.
  /var/minis/mounts/<name>/      — User-mounted external folders from Settings → Mount External Folders. Presence and names vary per user; check this directory first when the task references external/user files. Some mounts may be read-only — file_write / file_edit will reject writes with a clear error message.

The minis:// URL scheme:
  minis://attachments/file.png  →  /var/minis/attachments/file.png
  minis://workspace/data.csv    →  /var/minis/workspace/data.csv
  minis://shared/project/f.txt  →  /var/minis/shared/project/f.txt

IMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. The built-in browser fully supports minis:// — HTML pages and all sub-resources (JS, CSS, images, fonts, etc.) referenced via minis:// absolute URLs or relative paths resolve correctly within the current session. When building multi-file web projects, use file_write to create files in the same directory (e.g. /var/minis/workspace/myapp/), then reference sub-resources with relative paths in HTML (e.g. <link href="style.css">, <script src="app.js">, <img src="logo.png">). The browser resolves relative paths against the minis:// base URL automatically. Cross-directory references also work with absolute minis:// URLs (e.g. <img src="minis://attachments/photo.png"> from a workspace HTML page). Navigate to the entry HTML to preview, e.g. minis://workspace/myapp/index.html.
To display a minis:// URL in chat, write it as a Markdown link or image (e.g. [name](minis://...)) — the app handles it when the user taps it.
IMPORTANT: minis:// URLs MUST be percent-encoded. Non-ASCII characters (Chinese, emoji, spaces, etc.) in filenames will break Markdown rendering if not encoded. Use the minis_url from tool results directly — it is already encoded. If you construct a minis:// URL manually, percent-encode the filename (e.g. %E4%B8%AD%E6%96%87 for non-ASCII characters).
When you write files to /var/minis/, the tool result includes a minis_url you can embed directly in Markdown.
Inline media — use the ![desc](minis://...) image syntax for ALL of images, audio, AND video. The same ![]() syntax renders an inline audio player or video player, not just images:
  - Images: ![chart](minis://attachments/chart.png)   → inline image (.png/.jpg/.gif/.webp)
  - Audio:  ![song](minis://attachments/song.mp3)     → inline audio player (.mp3/.m4a/.wav)
  - Video:  ![clip](minis://attachments/clip.mp4)     → inline video player (.mp4/.mov/.m4v)
Do NOT use the [text](url) link form for audio/video when you want them to play inline — that only produces a tappable link. Use ![]() to embed an actual player.
For non-media files, use Markdown links: [filename](minis://workspace/filename).
Tappable link previews: text/code (.py/.json/.md/etc), images, audio, video, HTML, and PDF files open native previews when the user taps a [name](minis://...) link.
Use Markdown links for all non-media minis:// files — the user can tap to preview them directly in chat.

File creation guidelines:
- Use file_write to CREATE new files. Use file_edit to MODIFY existing files. The shell is BusyBox ash: heredoc syntax (cat << EOF, python3 << 'EOF') may mis-parse braces, quotes, or special characters and execute abnormally — avoid it whenever possible, and prefer file_write over echo/printf for writing file contents. When you hit escaping or parsing errors with long inline content, write the content to a file first (file_write), then pass or execute the file (e.g. `python3 /tmp/script.py`).
- file_write and file_edit are atomic, preserve formatting, and make it easy to fix errors or update content later.
- shell_execute is for RUNNING commands, not for writing files.
- shell_execute supports multi-line commands directly — quoting and special characters are handled automatically. However, commands MUST NOT exceed 1000 characters. If longer, write a script file with file_write first, then run it.
- ICMP is blocked by the PRoot sandbox — `ping` will hang indefinitely. Use `curl` or `wget` to test network connectivity instead.
- Also (BusyBox ash, NOT bash): `**` recursive glob (globstar) is NOT supported. Use `find <dir> -name '*.ext'` for recursive file search, and pipe to `xargs` for tools like `wc`. Brace expansion ({a,b,c}) and bash arrays (arr=(...), ${'$'}{arr[@]}) are also unsupported — use space-separated strings with a for loop or multiple arguments instead.
- Python packages: many PyPI packages (numpy, pandas, scipy, pillow, etc.) lack musllinux_aarch64 wheels and will fail to build from source. Use Alpine's native packages instead: `apk search py3-<name>` then `apk add py3-numpy py3-pandas py3-matplotlib py3-pillow py3-scipy py3-requests`. Only fall back to `pip install` for pure-Python packages not available via apk. For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in the sandbox.
- Background services: each shell_execute runs in an isolated process. When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: `python3 -m http.server 8765 > /dev/null 2>&1 &`. Without redirection the server dies silently after the command finishes.
- File search: when looking for user files, do NOT scan the whole filesystem. Search under /var/minis/ first (workspace/attachments/shared for the current session, mounts/* for user-provided external folders). Only widen the scope if the file is clearly not under /var/minis/.

Tool call style:
- Default: do not narrate routine, low-risk tool calls — just call the tool directly.
- Narrate only when it helps: multi-step work, complex problems, sensitive actions, or when the user explicitly asks.
- Keep narration brief and value-dense; avoid repeating obvious steps.
- When a tool exists for an action, use it directly instead of explaining what you plan to do or asking the user to confirm.
- Use reasonable defaults and contextual inference to fill in missing details (e.g. 'tonight' means today, 'remind me' implies creating a reminder immediately). Only ask for clarification when genuinely ambiguous.

Tone and style:
- Reply in the language that best matches the user's input. Only switch languages when the user explicitly asks.
- Be concise. Prefer action over explanation — when the user asks for something that can be done via shell, do it directly.

Android-only tools (android-* CLIs):
CLI tools at /usr/local/bin with the `android-` prefix give you access to Android framework capabilities and on-device control. Invoke them from shell_execute like any other binary — they are already on PATH. Each tool prints JSON (or a short human-readable line) and supports --help for full usage. Tools gated by Shizuku or AccessibilityService return permission_denied when not granted — handle that gracefully and point the user at [Settings → Permissions](minis://settings/permissions).
- android-alarm — schedule alarms/timers in the system Clock app (`schedule <HH:MM> --label <L> [--repeat ONCE|DAILY|WEEKDAYS]`, `timer <seconds> --label <L>`, `open`). Alarms/timers are saved into the user's Android Clock — list/cancel are not supported (no system query API); tell the user to manage them from the Clock app's Alarms/Timers tabs (or `android-alarm open` / minis://views/alarm).
- android-calendar — read/write the device calendar (`list --start YYYY-MM-DD [--end ...] [--max N]`; `create --title <T> --start <ISO> [--end <ISO>] [--description <D>] [--location <L>] [--all-day]`).
- android-clipboard — `get | set <text> [--label L] | clear`.
- android-contacts — `list [--max N] | search <query> [--max N] | get <id> | delete <id>`. Requires READ_CONTACTS (delete also needs WRITE_CONTACTS).
- android-device — `[all|info|battery|storage]` — model, OS version, battery, storage (JSON).
- android-location — `current` for device location with reverse-geocoded address; `geocode <lat> <lon>` for reverse, `forward --address "<addr>"` for forward geocoding.
- android-notification — `send --title <T> [--body <B>] | clear | list [--max N]`. `send` triggers the system permission prompt on Android 13+ if POST_NOTIFICATIONS isn't granted. `list` reads active status-bar notifications and requires Notification Access (one-time setup; the first `list` call opens that page automatically).
- android-open <url> — open a URL via the system handler (http/https, tel:, mailto:, geo:, market:, intent:, etc.). Use this to open something immediately. To offer a tappable link instead, write a standard Markdown link with the URL directly — the app handles system URL schemes natively.
- android-photos — `list [--max N] | stats | near <lat> <lon> [--radius KM] [--max N]` — query the device photo library via MediaStore.
- android-player — audio playback sessions (`play <session> <path>`, `pause/resume/seek/stop/status <session>`, `list`).
- android-speak — device TTS (`<text> [--rate F] [--pitch F] [--volume F]`; `--stop | --status`).
- android-speech — microphone transcription (`listen [--language BCP47] [--max N] [--timeout SEC]`; `status`). Requires RECORD_AUDIO.
- android-weather <latitude> <longitude> — Open-Meteo forecast (current + hourly + daily). No API key needed.
- android-shizuku-cli — invoke privileged Android system APIs (package management, settings, system commands) via Shizuku when granted. Curated subcommands return structured JSON; for anything not covered, fall back to `android-shizuku-cli exec <any shell command>` which runs the command via `sh -c` with Shizuku privilege (same surface as `adb shell`). Run with no args (or --help) for the subcommand list.
- android-a11y-cli — drive system UI (read screen, tap, type, swipe, scroll) via the Android AccessibilityService when enabled. Run with no args (or --help) for the subcommand list.
- minis-open <url-or-path>: Opens a resource inside Minis without leaving the chat. Accepts http/https URLs (→ built-in WebKit preview) and chat-resource file paths under /var/minis/** (→ built-in file preview, routed by extension: images to the image viewer, .md to markdown preview, .html to HTML preview, .pdf/office docs to QuickLook, audio/video to the media player, else share sheet). Examples: minis-open https://example.com, minis-open /var/minis/workspace/report.md, minis-open /var/minis/attachments/chart.png. Prefer this over android-open for anything that can be previewed in-app so the user doesn't lose conversation context. Use android-open for non-web schemes (tel:, mailto:, geo:, intent:, etc.) or when the user explicitly wants the system handler.
- minis-sessions-cli: Manage chat sessions. `list` recent or by date range, `search --keywords` cross-session, `messages --id` to read, `send` to create/continue a session, `retry` to re-run, `status` to check, `open` to navigate the app UI. Run --help for full options.
- minis-model-use: Invoke other LLM models pre-configured by the user. Use `minis-model-use list` to see them (includes each model's modality capabilities like image_output, audio_output, etc.), `minis-model-use search <query>` to filter by name/provider. `minis-model-use run --model <id_or_name>` sends an OpenAI-compatible messages request; pass input via --input <json_file> or stdin, output goes to stdout or --output <path>. The OpenAI shape is the PRIMARY input for every model and modality; standard params are auto-converted to the underlying provider, so do not hand-write provider-native bodies as the primary input. For provider-specific extras the standard schema doesn't model (web-search plugins, image-to-image fields, TTS/video or other custom endpoints), escape hatches exist for OpenAI-compatible providers (they error or are ignored on Anthropic/Gemini models): `extra_body` (object merged verbatim into the request body), a custom `endpoint` path, and a top-level `passthrough` envelope for fully verbatim requests with RAW (unparsed) responses. Results may carry `warnings` (fields that were ignored/downgraded and why) and `applied_extras` (which extras actually took effect) — read them to self-correct. Run --help for the full contract before using these. Models may support multimodal output (image generation, TTS/audio, video) — check the modalities field in list output. For image_output models, pass generation params in the input JSON: top-level `n`/`size`/`quality`/`prompt` (OpenAI /images/generations style) or `generation_config.{aspect_ratio,image_size,number_of_images,person_generation}` (Gemini). Run with --help for full usage.
- minis-config: Read or change Minis settings programmatically. Run `minis-config --help` for subcommands and `minis-config topic-help <topic>` for details on a specific area. For array-valued fields (e.g. `models`, `groups`, `envvars`, `defaults.agentLoopEntries`) the `get` subcommand accepts `--filter <keywords>` (whitespace-AND, case-insensitive substring match against each element's JSON) and `--page <N> --page-size <N>` (default 20, max 100) — use these instead of dumping the full list when you only need a subset, and check the response's `pagination` / `agent_hint` fields for the next-page command. Every write triggers an in-app confirmation sheet and is logged to a revertable audit (1000-entry rolling log). After a successful change the response includes a `user_message` field — relay it (or paraphrase) so the user knows how to review or revert via Settings → Logs → Config Changes. If the call returns `permission_denied`, the user has disabled minis-config in [Settings → Permissions](minis://settings/permissions); relay that message and don't retry. You CAN add new providers and write their `apiKey` (literal string OR a `${'$'}${'$'}ENV_VAR` reference to copy from an env var at write time), but `get` never echoes API keys / OAuth tokens / env var values back — those reads return `permission_denied` by design. OAuth tokens and env var values are not settable via this tool; for an env var, point the user at [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) so they enter the value themselves.
- minis-scheduled: Create and manage scheduled tasks — prompts that run automatically at a chosen time. `minis-scheduled create --time HH:MM --prompt "..." [--label L] [--repeat once|daily|weekdays|custom --days mon,tue,...] [--target new|follow-up|rerun --session <id> --message <id>] [--model <modelId>] [--start YYYY-MM-DD] [--end YYYY-MM-DD]` schedules it; `list` shows existing tasks (with nextTriggerMs and run history), `delete --id <taskId>`, `enable`/`disable --id <taskId>`, and `run --id <taskId>` fires one immediately. Target modes: `new` runs the prompt in a fresh chat; `follow-up` appends the prompt to an existing chat (--session); `rerun` re-runs an existing chat (--session) from a chosen user message (--message). Use this when the user asks to "remind me / do X every morning / run this later / schedule a task". Run --help for full usage.
Interactive terminal: minis://open_terminal opens a terminal for tasks that require interactive stdin (passwords, ssh, TUI apps like htop/vi). Write it as a Markdown link in your response — the app opens it when tapped. The optional init_command parameter pre-fills (NOT executes) a command; it MUST be fully percent-encoded (spaces → %20, & → %26, | → %7C, etc.). Only use this for genuinely interactive sessions — for everything else, use shell_execute. Examples: [Open Terminal](minis://open_terminal), [Login to SSH](minis://open_terminal?init_command=ssh%20user%40host).

Environment variables:
- Shell environment variables may contain sensitive API keys, tokens, or passwords. NEVER echo, print, cat, or otherwise output their values to stdout/stderr. Always reference them by variable name (e.g. ${'$'}API_KEY) inside scripts or commands — never inline the literal value.
- When a skill or task requires an environment variable that is not set, tell the user which variable is missing and provide a tappable deep link to create it: [Set ENV_NAME](minis://settings/environments?create_key=ENV_NAME&create_value=) — the user can tap it to open the Environment Variables page with the key pre-filled.
- Settings deep links: when you tell the user "go to Settings → X" or want to point them at a specific setting, prefer a Markdown link `[Label](minis://settings/<path>)` over plain prose. Available paths: providers (list), providers/<instanceId> (one provider), model-groups (incl. Agent Loop), model-groups/<groupId>, usage (token usage), skills, memory, storage, shared-folders (Shared Folders: /var/minis/{shared,skills,memory}), mount-external (Mount External Folders), logs, appearance, background, about, permissions, environments[?create_key=K&create_value=V[&create_note=N]], rootfs (also reachable as mirrors). Unknown paths fall back to Settings home, but prefer the exact path so users land where they want. These settings/action links are app deep links — render them as Markdown links in chat (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use).
- To check if a variable is set, use `[ -n "${'$'}VAR" ] && echo 'set' || echo 'not set'`. NEVER use echo ${'$'}VAR, printenv VAR, or any command that would output the actual value into the conversation context.${memorySystemSection}

Scheduled tasks: crontab / at / nohup loops will stop when the app is suspended, so in-app scheduled scripts may not run as expected. For recurring tasks that must fire while the app is backgrounded, use the native alarm tool (AlarmManager) or tell the user to set up a system-level schedule (Google Calendar event, Tasker automation, etc.). (Waiting or polling WITHIN the current turn is different — that is what shell_execute `delay` chains are for, per the shell_execute notes above.)"""

        // Match iOS order exactly: skills → global memory → recent daily memory.
        // See ios/Agent/Chat/AIChatViewModel.swift:4375-4387. Each fragment is
        // appended only when non-null; absent fragments leave no separator.
        // T-skillscan: rescan disk before reading the fragment so a skill
        // that an earlier turn dropped via shell `git clone` (which bypasses
        // the file_write hook below) becomes visible on the very next user
        // turn instead of "after kill app". Cheap: loadAll is a SQLite
        // SELECT + listFiles, no network.
        skillRepository?.reloadFromDisk()
        val skillFragment = skillRepository?.skillPromptFragment(activeSessionId)
        // [T-mcp-integration-android] Re-read servers.json (the CLI / file
        // browser may have changed it out-of-band) then build the Top-20
        // enabled-MCP disclosure, injected right after the skills fragment.
        mcpRepository?.reloadFromDisk()
        val mcpFragment = mcpRepository?.mcpPromptFragment(activeSessionId)
        // [T-memory-toggle-gates-injection-and-tools-android] Skip loading
        // GLOBAL.md + recent daily logs entirely when the user has turned
        // memory off for this session. Cheaper (no disk read) and — more
        // importantly — keeps the model from seeing stale persistent state
        // it can't tell the user how to manage. Skills and SOUL.md are
        // intentionally NOT gated by this toggle: skills are part of the
        // tool surface and SOUL.md is part of identity, both orthogonal
        // to the memory feature.
        val globalMemoryFragment = if (memoryOn) memoryRepository?.loadGlobalMemoryFragment() else null
        val dailyMemoryFragment = if (memoryOn) memoryRepository?.loadRecentDailyMemoryFragment() else null
        // [XSessionDiag] Hypothesis 3: ties the memory-injection sizes to a
        // SESSION id. MemoryRepository itself has no session context, so its own
        // `memory/daily-inject` line (which names the source files) cannot say who
        // received them — this line is the join key between the two. Emitted once
        // per system-prompt build, not per request iteration.
        AppLogger.info(
            "XSessionDiag",
            "[XSessionDiag] prompt/memory: session=${activeSessionId.take(8)} " +
                "memoryEnabled=$memoryOn " +
                "globalChars=${globalMemoryFragment?.length ?: 0} " +
                "dailyChars=${dailyMemoryFragment?.length ?: 0}",
        )

        return buildString {
            append(base)
            if (skillFragment != null) {
                append("\n\n")
                append(skillFragment)
            }
            if (mcpFragment != null) {
                append("\n\n")
                append(mcpFragment)
            }
            if (globalMemoryFragment != null) {
                append("\n\n")
                append(globalMemoryFragment)
            }
            if (dailyMemoryFragment != null) {
                append("\n\n")
                append(dailyMemoryFragment)
            }
            // Runtime context goes last so the prefix above stays byte-stable
            // across requests within the same day. Keep ordering deterministic
            // (date → tz → lang → model count) — any reorder defeats the cache.
            append("\n\nRuntime context:\n")
            append("- Current date: ").append(dateStr).append(" (").append(tzId).append(")\n")
            append("- Device language: ").append(lang).append("\n")
            append("- minis-model-use models available: ").append(modelUseCount)
        }
    }

    // ─── Legacy tool execution methods (kept for compatibility) ───────────

    fun executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        if (!_memoryEnabled.value) {
            return MemoryTools.ToolResult(
                "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
                false,
            )
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        )
        return result
    }

    fun executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
        val repo = memoryRepository ?: return MemoryTools.ToolResult("Error: Memory not available", false)
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        )
        return result
    }

    suspend fun executeBrowserUse(argsJson: String): BrowserToolResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return BrowserToolResult(text = "Error: Invalid browser_use input. Required: 'action' parameter.", success = false)

        return try {
            val result = browserTabPool.execute(input)
            BrowserToolResult(
                text = result.text,
                success = result.success,
                base64Image = result.base64Image,
                imageFilePath = result.imageFilePath,
                pageURL = result.pageURL,
            )
        } catch (e: Exception) {
            BrowserToolResult(text = "Error: ${e.message}", success = false)
        }
    }

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )

    // ─── Misc Helpers ────────────────────────────────────────────────────

    /**
     * T209: resize image bytes for the LLM inference payload only — the
     * full-resolution original is preserved on disk (mediaStore + uploads
     * dir) so chat history fullscreen view, agent shell `cat`, and
     * `read_image` all see the user's original picture, matching iOS.
     *
     * Returns null when the source already fits within [maxEdge] (caller
     * should fall back to [rawBytes]) or on any decode/compress failure.
     */
    private fun resizeImageBytes(
        rawBytes: ByteArray,
        mimeType: String,
        maxEdge: Int = 2000,
    ): ByteArray? {
        return try {
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            if (original.width <= maxEdge && original.height <= maxEdge) {
                original.recycle()
                return null
            }
            val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
            val w = (original.width * scale).toInt()
            val h = (original.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(original, w, h, true)
            val out = ByteArrayOutputStream()
            val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG
            else Bitmap.CompressFormat.JPEG
            scaled.compress(format, 85, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bundle of everything derived from a user-message's input attachments:
     * the resized in-memory image bytes for the LLM, file:// URIs of the
     * persisted copies (for stable rendering across app restarts), the
     * filenames in original attachment order (images first, then non-image
     * files — matches the rendering convention in UserAttachmentList), and
     * the mediaRef JSON parts that need to be embedded in parts_json so the
     * attachments survive a session reload (T128).
     */
    private data class PreparedAttachments(
        val imageParts: List<LLMMessage.ImagePart>,
        val imageUris: List<Uri>,
        val attachmentNames: List<String>,
        val mediaRefPartsJson: List<String>,
        // T132: iOS-parity additions so the model sees the attachment as
        // a real file in the agent's sandbox (read_image / shell_execute can
        // open these paths).
        //   imageUploadPaths: one /var/minis/attachments/uploads/<safe> per
        //     inlined image, in the same order as `imageParts`.
        //   attachedFilesXml:  null when no attachments, otherwise the
        //     <user-attached-files> XML block iOS appends to the user turn.
        val imageUploadPaths: List<String>,
        val attachedFilesXml: String?,
        // T150: file:// URIs of persisted non-image attachments, in the same
        // order as the non-image suffix of `attachmentNames`. Carried into
        // ChatMessage so the user-bubble file chip can route a tap directly
        // to FilePreviewScreen without re-resolving by filename.
        val nonImageUris: List<Uri>,
    )

    /**
     * Resize each image attachment, copy the bytes into MediaStore (private
     * filesDir/media/<date>/<sessionId>/<id>.<ext>), and return both the
     * in-memory bytes (for the LLM) and a stable file:// URI + mediaRef JSON
     * part (for persistence + reload). T150: non-image attachments take the
     * same persistence + uploadsHostDir path so they survive session reload
     * and remain visible to the agent's shell tools — but their content is
     * NOT inlined into the LLM payload (parity with iOS processAttachments,
     * AIChatViewModel.swift L1552-1645).
     */
    private fun prepareUserAttachments(
        attachments: List<InputAttachment>,
        sessionId: String,
    ): PreparedAttachments {
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        val imageUris = mutableListOf<Uri>()
        val imageNames = mutableListOf<String>()
        val nonImageNames = mutableListOf<String>()
        val nonImageUris = mutableListOf<Uri>()
        // T150: separate buffers so the persisted mediaRefPartsJson is
        // image-first, matching the on-screen UserAttachmentList ordering
        // and `attachmentNames = imageNames + nonImageNames`. On restore,
        // `loadSessionMessages` walks parts_json in array order — keeping
        // the persisted order image-first means restoredAttachmentNames
        // and restoredAttachmentUris also come out image-first/non-image-suffix.
        val imageMediaRefPartsJson = mutableListOf<String>()
        val nonImageMediaRefPartsJson = mutableListOf<String>()
        val imageUploadPaths = mutableListOf<String>()
        // T132: also write the resized bytes into the session's iSH-bound
        // attachments dir (filesDir/minis-sessions/<sid>/attachments/uploads/),
        // which is mounted at /var/minis/attachments/ inside iSH. This makes
        // the same image accessible to the agent via shell tools (read_image
        // / cat / file) and matches the iOS uploads-directory convention.
        val uploadsHostDir = java.io.File(
            context.filesDir,
            "minis-sessions/$sessionId/attachments/uploads",
        ).apply { mkdirs() }
        // Metadata captured per attachment for the <user-attached-files> XML.
        data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String)
        val metas = mutableListOf<UploadMeta>()
        val nowMs = System.currentTimeMillis()
        val isoFormatter = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val nowStr = isoFormatter.format(java.util.Date(nowMs))

        for (attachment in attachments) {
            if (attachment.isImage) {
                // T209: read the original image bytes once and reuse them
                // for storage + uploads dir; only the LLM inference payload
                // gets the resized copy. Pre-T209 the resized JPEG was used
                // for all three, so chat history fullscreen view and agent
                // shell tools (read_image / cat) saw a 1024px JPEG instead
                // of the user's original picture. Matches iOS canonical
                // (AIChatViewModel.swift L1595-1617).
                val rawBytes = try {
                    context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                    null
                } ?: continue
                val ref = try {
                    mediaStore.saveMedia(
                        data = rawBytes,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                    continue
                }
                // Resize only for the LLM payload — token-efficient and a
                // close-enough sketch of the picture for the model. Falls
                // back to raw bytes if the source is already small or the
                // decode/compress step fails.
                val inferenceBytes = resizeImageBytes(rawBytes, attachment.mimeType, maxEdge = 2000)
                    ?: rawBytes

                // Mirror ORIGINAL bytes into the iSH uploads dir under a
                // unique safe name so agent shell tools see the full-res
                // image. Don't fail the send if this write fails —
                // image_url in the request still carries (resized) bytes;
                // the model just won't be able to ask the agent to re-read
                // the same file from shell.
                //
                // Done BEFORE ImagePart construction so the linuxPath is
                // attached to the part — request-level image budgeting
                // uses it to emit a re-fetchable text placeholder when
                // the cumulative payload would exceed the per-request cap.
                val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
                val dest = java.io.File(uploadsHostDir, safeName)
                val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                    Log.w(TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                    false
                }
                val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
                if (linuxPath != null) {
                    imageUploadPaths.add(linuxPath)
                    metas.add(UploadMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr))
                }

                imageParts.add(LLMMessage.ImagePart(inferenceBytes, attachment.mimeType, linuxPath = linuxPath))
                val savedFile = java.io.File(mediaStore.mediaBaseDir, ref.relativePath)
                imageUris.add(Uri.fromFile(savedFile))
                imageNames.add(attachment.fileName)
                imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
                continue
            }

            // T150: non-image attachment — stream-copy to disk (no
            // resize), persist a mediaRef so the chip survives session
            // reload (T151), and put a copy in the iSH uploads dir so
            // the agent can `cat` it via shell tools. iOS parity: the
            // file content is NOT inlined into the LLM payload — it
            // only appears in <user-attached-files> XML metadata, the
            // model fetches content on demand.
            //
            // CRITICAL: we deliberately do NOT `readBytes()` the
            // attachment here. A 400MB APK shared in by the user would
            // OOM on a low-RAM device (heap growth limit ~500MB on
            // Pixel 4a); the file's not even going into the LLM
            // payload, so loading the full byte array is pointless.
            // Stream-copy to the uploads dest first, then hand that
            // file to MediaStore.saveMediaStreamed so a second
            // streaming pass produces the durable mediaRef.
            nonImageNames.add(attachment.fileName)
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            } catch (e: Exception) {
                Log.w(TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
                runCatching { dest.delete() }
                false
            }
            if (!uploadOk) continue

            val ref = try {
                dest.inputStream().use { input ->
                    mediaStore.saveMediaStreamed(
                        source = input,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
                null
            }
            if (ref != null) {
                nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
                nonImageUris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
            }

            val linuxPath = "/var/minis/attachments/uploads/$safeName"
            metas.add(UploadMeta(linuxPath = linuxPath, size = dest.length(), modifiedIso = nowStr))
        }

        // T-imgsize: byte-level budget enforcement. The resizeImageBytes pass
        // above caps *resolution* at 2000px but does nothing for the JPEG byte
        // size when the source is a 12-megapixel photo — Anthropic 413s once
        // cumulative inline image payload crosses ~30MB. ImageBudget walks
        // every image part, re-encodes oversize ones via the quality ladder,
        // and drops the tail when cumulative bytes would exceed 20MB. Result
        // is surfaced to the UI through _imageBudgetEvent so the Snackbar can
        // tell the user we touched their attachments.
        if (imageParts.isNotEmpty()) {
            val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
            // budgetResult.keptBytes.size <= imageParts.size; tail-drop the
            // parallel image-only lists symmetrically. Re-encoded bytes always
            // come out as JPEG so flip the mimeType on any part whose bytes
            // changed size (cheap proxy — never a false positive that hurts
            // semantics because the byte stream itself is the JPEG header).
            val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
                val orig = imageParts[idx]
                if (kept === orig.data) orig
                else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
            }
            val newSize = newImageParts.size
            imageParts.clear()
            imageParts.addAll(newImageParts)
            while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
            while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
            while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
            while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
            if (budgetResult.mutated) {
                AppLogger.info(
                    TAG,
                    "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
                )
                _imageBudgetEvent.tryEmit(budgetResult)
            }
        }

        // Build the <user-attached-files> XML block (iOS parity). One <file>
        // per attachment (image and non-image) that successfully landed in
        // the iSH uploads dir — gives the model a metadata-only inventory
        // it can resolve via shell tools when content is needed.
        val xml = if (metas.isEmpty()) null else buildString {
            append("<user-attached-files>\n")
            for (m in metas) {
                val urlPath = m.linuxPath.removePrefix("/var/minis/")
                append("  <file path=\"")
                append(m.linuxPath)
                append("\" url=\"minis://")
                append(urlPath)
                append("\" size=\"")
                append(m.size)
                append("\" modified=\"")
                append(m.modifiedIso)
                append("\" />\n")
            }
            append("</user-attached-files>")
        }

        // Order matches UserAttachmentList convention: images first, then files.
        return PreparedAttachments(
            imageParts = imageParts,
            imageUris = imageUris,
            attachmentNames = imageNames + nonImageNames,
            mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
            imageUploadPaths = imageUploadPaths,
            attachedFilesXml = xml,
            nonImageUris = nonImageUris,
        )
    }

    /**
     * Compute a unique-on-disk filename inside [dir] for [original]. Strips
     * path separators, falls back to "image.jpg" if the input is empty, and
     * appends `_N` before the extension when the target already exists.
     */
    private fun uniqueUploadFileName(dir: java.io.File, original: String): String {
        val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
        // Sanitize control / path-hostile chars without going overboard;
        // safe POSIX path chars are kept.
        val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!java.io.File(dir, sanitized).exists()) return sanitized
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "${base}_$n$ext"
            if (!java.io.File(dir, candidate).exists()) return candidate
            n++
        }
    }

    private fun buildMediaRefPartJson(
        ref: com.openminis.app.data.model.MediaRef,
        linuxPath: String? = null,
    ): String {
        val value = JSONObject()
            .put("id", ref.id)
            .put("relativePath", ref.relativePath)
            .put("mimeType", ref.mimeType)
        if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
        // Carry the iSH-visible uploads path through persistence so that
        // restored history can reconstruct AgentContentPart.ImageData with
        // its original linuxPath. Restored images that miss this field
        // (older rows written before this column existed) get linuxPath=null
        // and fall back to spillover at budget-elide time.
        if (linuxPath != null) value.put("linuxPath", linuxPath)
        return JSONObject().put("type", "mediaRef").put("value", value).toString()
    }

    /**
     * Build the parts_json array for a user message: a `text` part (omitted
     * when the user only sent attachments with no caption) followed by one
     * `mediaRef` part per persisted image. Mirrors the existing single-part
     * shape when there are no attachments.
     */
    /**
     * [T-android-paste-mediaref] What a message's `[Pasted#N]` markers turned
     * into once each was written to disk.
     *
     * @param partsJson the message's parts, in order, with each marker replaced
     *   by a `text/plain` mediaRef part and the surrounding prose kept as
     *   separate text parts.
     * @param modelText the same body with every marker expanded back to its full
     *   text — what the model must see on THIS turn. (Later turns rebuild it
     *   from disk via toLLMMessage.)
     * @param uiNames / [uiUris] the pasted blocks as attachment-style entries so
     *   the sent bubble shows a file card, exactly like a picked document.
     * @param consumedIds buffer entries actually referenced, for the caller to
     *   clear once the send is committed.
     */
    private data class PastedParts(
        val partsJson: List<String>,
        val modelText: String,
        val uiNames: List<String>,
        val uiUris: List<Uri>,
        val consumedIds: Set<Int>,
    )

    /**
     * [T-android-paste-mediaref] Write each `[Pasted#N]` in [text] to its own
     * `text/plain` media file and return the pieces the send path needs.
     *
     * This is the crux of the change. Previously the marker was substituted
     * inline and the whole block was persisted as one `text` part; now the block
     * becomes a mediaRef — the same mechanism images and documents already use —
     * so the stored message and its bubble stay small while the file on disk
     * holds the content.
     *
     * Returns null when [text] contains no live marker, letting every caller
     * keep its existing straight-line path untouched.
     */
    private fun buildPastedParts(text: String, sessionId: String): PastedParts? {
        val (chunks, consumed) = splitPastePlaceholders(text, _pastedTexts.value)
        if (consumed.isEmpty()) return null
        val byId = _pastedTexts.value.associateBy { it.id }

        val parts = mutableListOf<String>()
        val model = StringBuilder()
        val names = mutableListOf<String>()
        val uris = mutableListOf<Uri>()
        for (chunk in chunks) {
            when (chunk) {
                is PasteChunk.Text -> {
                    parts.add("""{"type":"text","value":${escapeJson(chunk.value)}}""")
                    model.append(chunk.value)
                }
                is PasteChunk.Pasted -> {
                    val entry = byId[chunk.id] ?: continue
                    val ref = try {
                        mediaStore.saveMedia(
                            data = entry.text.toByteArray(Charsets.UTF_8),
                            mimeType = PastedMedia.MIME,
                            sessionId = sessionId,
                            originalFileName = PastedMedia.fileNameFor(chunk.id),
                        )
                    } catch (e: Exception) {
                        // Disk full / unwritable: fall back to inlining this one
                        // block as text. The message is then shaped like the old
                        // behaviour — big, but complete. Losing the paste
                        // silently would be far worse than a heavy bubble.
                        AppLogger.warning(
                            TAG,
                            "[Paste] saveMedia failed for #${chunk.id}, inlining: ${e.message}",
                        )
                        parts.add("""{"type":"text","value":${escapeJson(entry.text)}}""")
                        model.append(entry.text)
                        continue
                    }
                    parts.add(buildMediaRefPartJson(ref))
                    model.append(entry.text)
                    names.add(ref.originalFileName ?: PastedMedia.fileNameFor(chunk.id))
                    uris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
                }
            }
        }
        AppLogger.info(
            TAG,
            "[Paste] ${consumed.size} placeholder(s) -> mediaRef: " +
                "${text.length} chars in bubble, ${model.length} chars to model",
        )
        return PastedParts(parts, model.toString(), names, uris, consumed)
    }

    private fun buildUserPartsJson(
        text: String,
        mediaRefPartsJson: List<String>,
        // [T-android-retry-attachment-loss] The <user-attached-files> XML
        // inventory (non-image file paths/sizes the model uses to `cat` the
        // file). iOS persists this same XML as a trailing text part so it
        // round-trips through retry / rerun / session-reload unchanged — the
        // model keeps seeing the /var/minis/attachments/uploads/... paths.
        // Android previously only added it to the in-memory agentHistory and
        // never persisted it, so a retry silently dropped the file inventory.
        // Persist it here as a text part (iOS parity); toLLMMessage restores
        // it via the plain "text" case with zero special-casing.
        attachedFilesXml: String? = null,
        /**
         * [T-android-paste-mediaref] Pre-split body parts from
         * [buildPastedParts], used INSTEAD of the single `text` part when the
         * message contained `[Pasted#N]` markers. Already an interleaved
         * text/mediaRef sequence, so it is spliced in at the position the plain
         * text part would have occupied — order is what keeps the pasted block
         * where the user put it, between the words around it.
         */
        bodyPartsJson: List<String>? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (bodyPartsJson != null) {
            parts.addAll(bodyPartsJson)
        } else if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
            parts.add("""{"type":"text","value":${escapeJson(text)}}""")
        }
        parts.addAll(mediaRefPartsJson)
        attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
        return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    /** LLM-based title + category generation, mirrors iOS generateSessionTitleIfNeeded(). */
    private var titleGenerationAttempts = 0
    private var titleGenerationInFlight = false
    private val TITLE_MAX_ATTEMPTS = 3

    // [T-android-auto-grouping-injection] Bounds on the group list folded into
    // the title-generation prompt. 30 groups × ~140 chars keeps the segment
    // well under a KB even in the worst case; the description cap matches
    // FolderEntity.DESC_MAX_CHARS, which is enforced at creation time but not
    // on rows written by older builds or by the AI-Suggest path.
    private val GROUP_CONTEXT_MAX = 30
    private val GROUP_NAME_MAX = 40
    private val GROUP_DESC_MAX = 100

    private fun generateSessionTitleIfNeeded() {
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
        // Prefer a dedicated sub-model (cheap, non-OAuth) — mirrors iOS resolveSubEntry.
        // Falls back to the primary provider if no sub-group is configured.
        // [T-title-gen-fallback-first-message-android] If no provider can be
        // resolved at all, the session would silently stay "New Chat". Log the
        // reason and fall back to the first user message as the title.
        val subProvider = resolveTitleProvider()
        if (subProvider == null) {
            AppLogger.info("TitleGen", "resolveTitleProvider=null — falling back to currentProvider")
        }
        val provider = subProvider ?: currentProvider
        if (provider == null) {
            AppLogger.warning("TitleGen", "no provider available (sub + current both null) — fallback-to-first-message path")
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
                        .map { f -> f to promptSafe(f.name, GROUP_NAME_MAX) }
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
                                ?.let { promptSafe(it, GROUP_DESC_MAX) }
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

                AppLogger.info(
                    "TitleGen",
                    "dispatch attempt=$titleGenerationAttempts provider=${provider.javaClass.simpleName} model=${provider.model.id}",
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
                    // Mirror iOS AIChatViewModel.swift:11244 — pass null so
                    // gpt-5.x family doesn't reject the request (only
                    // temperature=1 allowed there). buildRequestBody omits
                    // the field when null.
                    temperature = null,
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
                                promptSafe(it.name, GROUP_NAME_MAX)
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
                } else {
                    // [T-title-gen-fallback-first-message-android] The request
                    // succeeded but yielded no usable title — empty body or a
                    // response parseTitleResponse couldn't extract a title from
                    // (e.g. reasoning model that spent its whole budget thinking,
                    // or non-JSON output). Previously this was silent and left
                    // the session as "New Chat". Log the real cause and, on the
                    // final attempt, fall back to the first user message.
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=no-title attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                            "(empty / unparseable response) stopReason=${response.stopReason} " +
                            "textLen=${response.text.length}",
                    )
                    if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                        AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                        applyFallbackTitleFromFirstMessage("empty/unparseable title response")
                    }
                }
            } catch (e: Exception) {
                // [T-title-gen-fallback-first-message-android] Request error /
                // timeout / provider failure. Log the concrete cause (was
                // already logged, kept) and fall back to the first user message
                // on the final attempt.
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
    private suspend fun applyFallbackTitleFromFirstMessage(reason: String) {
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
     * Resolve the provider used for title generation. Mirrors iOS resolveSubEntry:
     * prefer an explicitly configured sub-model (cheap, non-OAuth) so title
     * generation doesn't hit the expensive primary model or fail under the
     * OAuth-Anthropic Claude-Code-only gate. Falls back to null if no sub is
     * configured — caller uses the primary provider then.
     */
    private fun resolveTitleProvider(): LLMProvider? {
        // [T-disabled-provider-via-group-android] Resolve the dedicated
        // title-generation sub-model (first enabled member of defaultSubGroupId).
        // [T-android-regenerate-title-submodel] Shares
        // ProviderRepository.resolveTitleSubEntry with the manual Regenerate
        // path so both prefer the same sub-model. Silently degrades (caller
        // falls back to the primary provider) when no sub-group is configured or
        // every member sits behind a disabled provider.
        val entry = providerRepository.resolveTitleSubEntry() ?: return null
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
                Log.w(TAG, "TitleGen OAuth refresh failed: ${e.message}")
            }
        }
        return ProviderFactory.create(instance, apiKey, entry.model, context)
    }

    /** Parse LLM response for title/category JSON. Multiple fallback strategies. */
    private fun parseTitleResponse(text: String): TitleGenResult {
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

    /**
     * [T-android-overlay-reply-status-34599] Pull the most recent
     * assistant text out of `_messages` and hand it to
     * [SessionActivityTracker.publishLastReply]. The tracker truncates
     * to a fixed-width excerpt and pairs it with [sessionId] so the
     * floating overlay can render a "tap to open this chat" capsule
     * after the stream completes. No-op when no assistant message has
     * content yet (e.g. fail during the very first turn).
     */
    private fun publishOverlayReplyExcerpt(sessionId: String) {
        val snapshot = _messages.value
        val text = snapshot.asReversed().firstOrNull { msg ->
            msg.role == "assistant" && msg.content.isNotBlank()
        }?.content
        SessionActivityTracker.publishLastReply(sessionId, text)
    }

    fun cancelStream() {
        AppLogger.info(TAG_STREAM, "cancelStream invoked _isStreaming=false (sid=$activeSessionId)")
        streamJob?.cancel()
        _isStreaming.value = false
        // T-streaming-side-channel: flush any in-flight delta back into the
        // canonical message so the rest of cancelStream's cleanup (publish
        // overlay excerpt, persist, retry-eligible state) sees the real
        // content rather than a stale pre-stream snapshot.
        flushAllStreamingDeltas()
        // T171: drop activity tracker immediately, don't wait for the
        // streamJob's finally block. When OkHttp is wedged in a blocking
        // execute() call.cancel() may unwind eventually but the finally
        // doesn't run until then — meanwhile RPC chat.session.status would
        // still report isRunning=true and the user thinks the stop button
        // did nothing.
        // [T-android-overlay-reply-status-34599] User-initiated cancel:
        // surface any reply we already streamed + tag outcome as
        // Cancelled so the overlay's glyph reflects the actual end
        // state (⊘) instead of carrying over the prior tool's outcome.
        publishOverlayReplyExcerpt(activeSessionId)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        // Stop whichever shell the agent loop is actually dispatching against.
        // Before `ensureSession()` that is the draft id; after, the real id.
        // Stopping the wrong one leaves a runaway yt-dlp/ffmpeg alive.
        ExecutionCoordinator.stopCurrentCommand(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            // Mid-turn rename: sweep any lingering draft shell too.
            ExecutionCoordinator.stopCurrentCommand(sessionId)
        }
        handleUserCancelledCleanup()

        // T189: iOS parity (AIChatViewModel.swift L2592-2610). If the user
        // enqueued prompts during the cancelled stream, auto-resume the drain
        // instead of leaving them stuck as dashed bubbles waiting for a manual
        // long-press retry.
        val pending = _promptQueue.value
        if (pending.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "cancel — ${pending.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

    /**
     * T189: spawn a fresh agent loop to drain whatever the user queued during
     * the cancelled stream. 200ms delay matches iOS resumeQueueAfterCancel
     * (Task.sleep(200_000_000)) — gives the cancelled streamJob's finally block
     * room to release the concurrency slot + write back state. Race-guards on
     * entry: empty queue (user withdrew) or already streaming (user manually
     * retried) → noop return.
     *
     * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
     * verbatim (incl. OAuth token refresh + Claude Code prefix), so a queued
     * prompt drain after cancel uses the same plumbing as a fresh send.
     */
    private fun resumeQueueAfterCancel() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            if (_promptQueue.value.isEmpty()) return@launch
            if (_isStreaming.value) return@launch
            // [T-android-compact-queued-drain] Defer while a compact is in
            // flight — draining would mutate agentHistory mid-marker-write.
            // Safe to just return: every SUCCESSFUL compact re-kicks this
            // function from its own tail, so a deferred drain is never lost
            // (and a failed compact leaves the queue pending by design).
            if (_isCompacting.value) {
                AppLogger.info(TAG, "resumeQueueAfterCancel: compact in flight — deferring to its completion kick")
                return@launch
            }

            val initialProvider = currentProvider
            if (initialProvider == null) {
                AppLogger.warning(TAG, "resumeQueueAfterCancel: no provider, dropping queue")
                _promptQueue.value = emptyList()
                _messages.value = _messages.value.filterNot { it.isQueued }
                return@launch
            }
            var provider: LLMProvider = initialProvider

            // Refresh OAuth token if needed (mirrors sendMessage L2477-2501).
            if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                try {
                    val activeEntryId = _activeEntryId.value
                    val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                    val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                    if (instance != null) {
                        val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                        val freshToken = manager?.validAccessToken()
                        if (freshToken != null) {
                            val storedKey = providerRepository.loadApiKey(instance.id)
                            if (freshToken != storedKey) {
                                providerRepository.saveApiKey(instance.id, freshToken)
                                provider = com.openminis.app.provider.ProviderFactory.create(
                                    instance, freshToken, currentModel ?: provider.model, context
                                )
                                currentProvider = provider
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OAuth token refresh failed (resumeQueueAfterCancel): ${e.message}")
                }
            }

            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

            // T145: claim the streaming flag synchronously before launching
            // the streamJob so a concurrent send/retry tap is rejected by the
            // entry guard. Mirrors sendMessage discipline.
            AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
            _isStreaming.value = true
            _canResume.value = false
            _error.value = null

            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob ENTER sid=$activeSessionId")
                try {
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "resumeQueueAfterCancel streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)

                    try {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts CALL")
                        drainQueuedPrompts(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drain CANCELLED")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "resumeQueueAfterCancel drain EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Queued drain error (resumeQueueAfterCancel)", e)
                        setInlineError(e.message ?: "Unknown error")
                    } finally {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY enter")
                        // [T-android-overlay-reply-status-34599] Surface
                        // the assistant's most recent reply text to the
                        // overlay BEFORE setInactive so the post-completion
                        // overlay state (no-running, has-outcome) carries a
                        // non-null excerpt. Reading _messages here is safe:
                        // we're in the finally block of the agent loop and
                        // the stream has already flushed its last delta.
                        publishOverlayReplyExcerpt(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob CANCELLED waiting for slot")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob EXIT")
            }
        }
    }

    /**
     * After the user stops a streaming turn, reconcile UI + agentHistory so
     * the conversation is valid on the next API call and resumable via
     * [resume]. Mirrors iOS AIChatViewModel.handleUserCancelledCleanup
     * (Case 1: tool cancel, Case 2: text cancel).
     *
     *  - Case 1: any in-flight tool block is flipped to [ToolBlockStatus.CANCELLED]
     *    and a synthetic tool_result with [CANCELLED_MARKER] is persisted so
     *    tool_use/tool_result stays paired.
     *  - Case 2: if there was partial assistant text streamed (and no tool
     *    cancel), commit the partial text + a truncation `<system-reminder>`
     *    to agentHistory so the model knows the prior turn was cut short.
     *
     * Always sets [_canResume] = true when there is something to resume from.
     */
    private fun handleUserCancelledCleanup() {
        val msgs = _messages.value.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastIdx < 0) return
        var last = msgs[lastIdx]

        // T73: clear "Minis is thinking…" the moment the user taps Stop.
        // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
        // 2785) so the typing indicator shows during the initial request
        // gap before the first stream chunk. The cancel paths below didn't
        // reset it, so after Stop the indicator stayed live forever even
        // though the streamJob was already torn down. Reset before either
        // case runs so both tool-cancel and text-cancel paths benefit.
        if (last.isAwaitingModelResponse) {
            last = last.copy(isAwaitingModelResponse = false)
            msgs[lastIdx] = last
            _messages.value = msgs
        }

        // Case 1: cancel during tool execution. Flip in-flight tool blocks to
        // CANCELLED and persist matching tool_result rows.
        val cancelledIds = mutableListOf<Pair<String, String>>() // (toolUseId, toolName)
        val updatedBlocks = last.toolBlocks.map { b ->
            val s = b.toolStatus
            if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
                if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
                b.copy(toolStatus = ToolBlockStatus.CANCELLED)
            } else b
        }
        val hadInflightTools = cancelledIds.isNotEmpty()
        if (hadInflightTools) {
            msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
            _messages.value = msgs
            val parts = cancelledIds.map { (id, name) ->
                AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = CANCELLED_MARKER,
                    isError = true,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                persistToolResultMessage(parts)
            }
            // [T-android-group-pause-badge-restamp] A LIVE interruption just
            // happened: this is a real entry into the paused state, so the
            // badge's 24h freshness stamp must be refreshed. Cancel any
            // unconsumed re-detection mark left by a prior load so it cannot
            // suppress the re-stamp here.
            markLiveInterruption()
            _canResume.value = true
            return
        }

        // Case 2: cancel during text streaming. If partial assistant text
        // exists and agentHistory does not already end with the assistant
        // turn we're on, commit the partial text + truncation marker so the
        // model sees an interrupted prior turn on the next call.
        val partialText = buildString {
            if (last.content.isNotEmpty()) append(last.content)
            for (b in last.toolBlocks) {
                if (b.kind == "text" && b.content.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(b.content)
                }
            }
        }
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

        // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
        // Stop fired while still in the pre-first-chunk thinking gap (no
        // partial text, no tool_use emitted, no committed history for this
        // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
        // not in the DB and would otherwise render as an empty "Minis" header
        // bubble with no body. Drop it so the UI snaps back to idle the
        // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
        // a candidate WITH real text or any emitted tool_use is kept (handled
        // by Case 1 / Case 2 below); a thinking-only placeholder is not.
        val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
        if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
            msgs.removeAt(lastIdx)
            _messages.value = msgs
            return
        }

        if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
            val parts = listOf<AgentContentPart>(
                AgentContentPart.Text(partialText),
                AgentContentPart.Text(
                    "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
                ),
            )
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = partialText,
                    contentParts = parts,
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val partsJson = buildAssistantPartsJson(parts)
                chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
            }
            // [T-android-group-pause-badge-restamp] A LIVE interruption just
            // happened: this is a real entry into the paused state, so the
            // badge's 24h freshness stamp must be refreshed. Cancel any
            // unconsumed re-detection mark left by a prior load so it cannot
            // suppress the re-stamp here.
            markLiveInterruption()
            _canResume.value = true
        } else if (historyEndsWithAssistant) {
            // Already committed (tool cancel path above handled or prior turn
            // wrote an assistant row). Still allow resume.
            // [T-android-group-pause-badge-restamp] A LIVE interruption just
            // happened: this is a real entry into the paused state, so the
            // badge's 24h freshness stamp must be refreshed. Cancel any
            // unconsumed re-detection mark left by a prior load so it cannot
            // suppress the re-stamp here.
            markLiveInterruption()
            _canResume.value = true
        }
    }

    /**
     * Build a JSON parts array matching the ChatRepository schema so a
     * committed interrupted-assistant turn round-trips across app restarts.
     * Only emits text parts — tool_use / tool_result paths are handled by
     * the existing persistence code in the agent loop.
     */
    private fun buildAssistantPartsJson(parts: List<AgentContentPart>): String {
        val sb = StringBuilder("[")
        var first = true
        for (p in parts) {
            if (p !is AgentContentPart.Text) continue
            if (!first) sb.append(',') else first = false
            sb.append("""{"type":"text","value":""")
            sb.append(escapeJson(p.text))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Resume an interrupted agent loop. Injects a `<system-reminder>` into
     * agentHistory so the model picks up where it left off, then re-enters
     * the agent loop in a fresh [streamJob]. Mirrors iOS
     * AIChatViewModel.resume().
     *
     * Safe to call only when [canResume] is true and [isStreaming] is false.
     * Clears [_canResume] on entry so repeated taps don't stack.
     */
    fun resume() {
        if (_isStreaming.value || !_canResume.value) return
        val provider = currentProvider ?: run {
            _error.value = "No provider configured"
            return
        }
        _canResume.value = false
        _error.value = null
        // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
        // setInlineError (which persisted an error sticker on the last assistant
        // row). Clear it now so a successful resume doesn't merge-resurrect the
        // turn-limit banner on the next reload.
        clearPersistedLastAssistantError()
        AppLogger.info(TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
        // [T-android-tool-autoscroll] Start-of-turn snap. The thinking
        // placeholder is the only visible delta until the model's first
        // token, and the auto-follow tuple won't advance until content
        // streams — ChatScreen would otherwise leave the placeholder
        // behind the input bar.
        _forceScrollToBottom.tryEmit(Unit)

        // If history ends with assistant (Case 2: text-cancel committed a
        // partial assistant turn), append a continue reminder as a user
        // message. If it ends with user tool_result (Case 1), it's already
        // a valid starting point for the next API call — no reminder needed.
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT
        if (historyEndsWithAssistant) {
            val reminder =
                "<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>"
            val parts = listOf<AgentContentPart>(AgentContentPart.Text(reminder))
            agentHistory.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = reminder,
                    contentParts = parts,
                )
            )
            viewModelScope.launch(Dispatchers.IO) {
                val partsJson = """[{"type":"text","value":${escapeJson(reminder)}}]"""
                chatRepository.appendMessage(activeSessionId, "user", partsJson)
            }
        }

        viewModelScope.launch {
            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt =
                if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth == true) {
                    val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                    if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                    else "$prefix\n\n${baseSystemPrompt ?: ""}"
                } else baseSystemPrompt

            AppLogger.info(TAG_STREAM, "resume _isStreaming=true (sid=$activeSessionId)")
            _isStreaming.value = true
            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
                try {
                    SessionConcurrencyManager.acquireSlot(activeSessionId)
                    AppLogger.debug(TAG_STREAM, "resume streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let {
                            providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy
                        } ?: com.openminis.app.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)
                    try {
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop CALL")
                        runAgentLoop(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop RETURN normal")
                        drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                        AppLogger.info(TAG_STREAM, "resume drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "resume runAgentLoop CANCELLED")
                        Log.d(TAG, "Agent loop cancelled (resume)")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Agent loop error (resume)", e)
                        setInlineError(e.message ?: "Unknown error")
                    } finally {
                        AppLogger.info(TAG_STREAM, "resume streamJob FINALLY enter")
                        // [T-android-overlay-reply-status-34599] Surface
                        // the assistant's most recent reply text to the
                        // overlay BEFORE setInactive so the post-completion
                        // overlay state (no-running, has-outcome) carries a
                        // non-null excerpt. Reading _messages here is safe:
                        // we're in the finally block of the agent loop and
                        // the stream has already flushed its last delta.
                        publishOverlayReplyExcerpt(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                        SessionConcurrencyManager.releaseSlot(activeSessionId)
                        AppLogger.info(TAG_STREAM, "resume streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                    Log.d(TAG, "Cancelled while waiting for concurrency slot (resume)")
                }
                // [T-android-stale-streamjob-clears-isstreaming] guard.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "resume _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "resume _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "resume streamJob EXIT")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Tear down whichever shell was actually serving this VM. Terminate
        // both ids when the rename happened, since a draft shell may still
        // linger if the agent ran a tool before `ensureSession()`.
        ExecutionCoordinator.sessionDidTerminate(activeSessionId)
        if (activeSessionId != sessionId) {
            ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    /**
     * T-android-new-chat-empty-residue: when the user leaves the chat screen,
     * drop sessions that were materialised in the DB (e.g. via a thinking /
     * memory toggle in `ensureSession()`) but never received a real message.
     * Without this hook, tapping "New chat" → toggling a session-scoped
     * setting → exiting leaves an empty row at the top of the session list.
     *
     * Called from ChatScreen's onDispose. Gates:
     *   - realSessionId must be non-empty (a row was actually inserted)
     *   - not currently streaming (background agent work would be lost)
     *   - persisted message count == 0 (authoritative DB check — `_messages`
     *     also contains ephemeral system-info bubbles that aren't persisted,
     *     so a state-only check would over-count).
     *
     * Safe to call multiple times; the row-existence + count gates make it
     * idempotent. After deletion we release the cached VM so a stale entry
     * doesn't linger in `ChatViewModelStore`.
     */
    fun cleanupIfEmptyOnExit() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = chatRepository.messageCount(sid)
                if (count > 0) return@launch
                AppLogger.info(
                    TAG,
                    "cleanupIfEmptyOnExit: deleting empty session $sid (no persisted messages)",
                )
                chatRepository.deleteSession(sid)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ChatViewModelStore.release(sid)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "cleanupIfEmptyOnExit failed for $sid: ${t.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun escapeJson(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * Convert a flat list of MessageEntity into ChatMessages, merging toolResult
     * data from user-role messages back into their corresponding AssistantBlocks.
     * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
     */
    /**
     * Matches a `<system-reminder>...</system-reminder>` block, including any
     * surrounding whitespace / newlines, so a part that is *only* a reminder
     * collapses to empty text instead of leaving a blank gap. DOTALL so `.`
     * spans newlines (reminders run multi-line in the cancel/resume paths).
     *
     * Only applied at the UI-render transform — agentHistory + DB rows keep
     * the raw text so the LLM continues to see the reminder on subsequent
     * turns (matches iOS, where system-reminder text is appended to
     * agentHistory/AgentMessage parts but never to the chat-list ChatMessage).
     */
    private val systemReminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

    private fun stripSystemReminders(text: String): String =
        if (!text.contains("<system-reminder>")) text
        else systemReminderRegex.replace(text, "")

    /**
     * [T-android-retry-attachment-loss] Remove the `<user-attached-files>` XML
     * inventory from a persisted text part for DISPLAY only. The XML is now
     * persisted (iOS parity) so the model keeps the file paths across retry /
     * reload, but it must never render in the user bubble — the file chips are
     * rebuilt from the mediaRef parts instead. Mirrors the index-based strip
     * already used by editMessage / the title-fallback path.
     */
    private fun stripAttachedFilesXml(text: String): String {
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx < 0) return text
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        return if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }

    private fun List<MessageEntity>.toChatMessages(): List<ChatMessage> {
        // First pass: extract all toolResult data keyed by toolUseId
        val toolResultMap = mutableMapOf<String, ToolResultData>()
        for (entity in this) {
            if (entity.role != "user") continue
            try {
                val array = org.json.JSONArray(entity.partsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") == "toolResult") {
                        val value = obj.getJSONObject("value")
                        val toolUseId = value.optString("toolUseId", "")
                        if (toolUseId.isNotEmpty()) {
                            toolResultMap[toolUseId] = ToolResultData(
                                output = value.optString("output", ""),
                                success = value.optBoolean("success", true),
                            )
                        }
                    }
                }
            } catch (_: Exception) { /* skip malformed */ }
        }

        // Second pass: convert messages, merging tool results into blocks
        // Filter out user messages that only contain toolResult parts (no visible text)
        return mapNotNull { entity ->
            var text = ""
            val blocks = mutableListOf<AssistantBlock>()
            // T128: media attachments persisted under user messages as `mediaRef`
            // parts. Restored to file:// URIs (stable across app restarts) and
            // their original filenames so UserAttachmentList renders the same
            // tiles after a session reload.
            val restoredImageUris = mutableListOf<Uri>()
            // [T-android-paste-mediaref] Names are collected PER COLUMN and
            // concatenated image-first at the end, instead of appended to one
            // list in part order.
            //
            // UserAttachmentList splits with `allFileNames.drop(imageUris.size)`,
            // so the names list must be images-then-files regardless of the
            // order the parts appear in. That used to be automatic: attachment
            // mediaRefs were always written images-first. A pasted block breaks
            // it — it lives in the BODY, so it can precede an image part, and a
            // single in-order list would then start with a file name and shift
            // every image caption onto the wrong tile.
            val restoredImageNames = mutableListOf<String>()
            val restoredFileNames = mutableListOf<String>()
            // T150: file:// URIs of restored non-image attachments, in the
            // same order as the non-image suffix of the joined name list.
            // Powers the user-bubble file chip → FilePreviewScreen tap after
            // a session reload.
            val restoredAttachmentUris = mutableListOf<Uri>()

            if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
                blocks.add(AssistantBlock(
                    id = "thinking_restored_${entity.id}",
                    kind = "thinking",
                    content = entity.reasoningContent,
                    toolTitle = "Thinking",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ))
            }

            try {
                val array = org.json.JSONArray(entity.partsJson)
                var textBlockCounter = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        "text" -> {
                            val raw = obj.optString("value", "")
                            // Strip <system-reminder>...</system-reminder> blocks
                            // here only — agentHistory in memory and the DB row
                            // both keep the raw text, so the LLM still sees the
                            // reminder on subsequent turns. UI just hides it.
                            // If a part was *only* a reminder, the cleaned
                            // string is empty and we skip it so we don't render
                            // a phantom blank text block.
                            // [T-android-retry-attachment-loss] Also strip the
                            // now-persisted <user-attached-files> XML so it
                            // doesn't render in the user bubble (file chips come
                            // from mediaRef parts). The DB row + agentHistory
                            // keep the raw XML so the model still sees paths.
                            val t = stripAttachedFilesXml(stripSystemReminders(raw)).let {
                                if (it != raw) it.trim() else it
                            }
                            if (t.isEmpty()) continue
                            text += t
                            // For assistant messages, also push the text as a block so
                            // the renderer can preserve the original text↔tool ordering.
                            // For user messages we keep using the `text` field only.
                            if (entity.role == "assistant") {
                                blocks.add(AssistantBlock(
                                    id = "text_restored_${entity.id}_${textBlockCounter++}",
                                    kind = "text",
                                    content = t,
                                ))
                            }
                        }
                        "toolUse" -> {
                            val value = obj.getJSONObject("value")
                            val toolId = value.optString("toolUseId", "")
                            if (toolId.startsWith("thinking_")) continue
                            val toolInput = value.optString("input", "")
                            // Merge tool result output (iOS: block.content = tr.output)
                            val result = toolResultMap[toolId]
                            val pageURL = value.optString("pageURL", "").ifEmpty { null }
                            val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                            blocks.add(AssistantBlock(
                                id = toolId,
                                kind = "tool_use",
                                toolName = value.optString("name", ""),
                                toolTitle = value.optString("description", ""),
                                toolArgs = toolInput,
                                content = result?.output?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                                toolStatus = when {
                                    result == null -> ToolBlockStatus.SUCCESS
                                    !result.success && (
                                        result.output.startsWith(CANCELLED_MARKER) ||
                                            result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                    ) -> ToolBlockStatus.CANCELLED
                                    result.success -> ToolBlockStatus.SUCCESS
                                    else -> ToolBlockStatus.FAILED
                                },
                                browserURL = pageURL,
                                imageFilePath = imgPath,
                                // [T-android-gemini3-thoughtsig / #179] Restore the
                                // persisted signature onto the rebuilt block.
                                thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                            ))
                        }
                        "mediaRef" -> {
                            if (entity.role != "user") continue
                            val value = obj.optJSONObject("value") ?: continue
                            val rel = value.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val file = java.io.File(mediaStore.mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val mime = value.optString("mimeType", "")
                            val name = value.optString("originalFileName", "").ifEmpty { file.name }
                            // T150: branch on mime so non-image mediaRefs land
                            // in the file-chip column instead of polluting
                            // imageUris (which feeds the image gallery).
                            //
                            // [T-android-paste-mediaref] A pasted block needs no
                            // case of its own: it is text/plain, so it takes the
                            // non-image branch and renders as the same file card
                            // as an attached document — which is exactly the
                            // requested behaviour. Note this ALSO relies on
                            // parts being written images-first; a pasted ref
                            // sits in the body (possibly before an image part),
                            // so the names/uris pairing here is positional per
                            // COLUMN, not per part index, and stays consistent
                            // because each column is appended in part order.
                            if (mime.startsWith("image/")) {
                                restoredImageUris.add(Uri.fromFile(file))
                                restoredImageNames.add(name)
                            } else {
                                restoredAttachmentUris.add(Uri.fromFile(file))
                                restoredFileNames.add(name)
                            }
                        }
                        // toolResult in user messages handled in first pass above
                    }
                }
            } catch (e: Exception) {
                // T-PARTS-FALLBACK: previously this catch dumped the entire
                // partsJson into `text` as a degraded fallback. That meant
                // any malformed (or unexpectedly large) row rendered its
                // raw JSON — including any inlined base64 — as a plain
                // user/assistant bubble, which then locked up Compose's
                // StaticLayout for tens of seconds (see HangDetector report
                // for session e84882d7 / 820 KB partsJson). Replace with a
                // short, fixed-size placeholder so the row still appears
                // (so the user can delete or scroll past it) but no longer
                // pulls megabytes through the layout pass.
                Log.w(
                    TAG,
                    "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                        "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
                )
                text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                    "${entity.partsJson.length} bytes)"
            }

            // Skip user messages with no visible content (toolResult-only internal messages,
            // or messages that were entirely a system-reminder). A user message that is
            // *only* an image attachment (no caption) still has visible content and must
            // not be skipped — restoredImageUris carries it.
            if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
            // Skip assistant messages that became empty after stripping system-reminders
            // and have no tool / thinking blocks to fall back on — would otherwise
            // render as a phantom blank assistant bubble.
            if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty()) return@mapNotNull null
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = text,
                imageUris = restoredImageUris,
                // Image names first, then file names — the invariant
                // UserAttachmentList's `drop(imageUris.size)` relies on.
                attachmentNames = restoredImageNames + restoredFileNames,
                attachmentUris = restoredAttachmentUris,
                toolBlocks = blocks,
                sourceDbIds = listOf(entity.id),
                // [T-error-persist-android] Restore the persisted terminal error
                // so the inline error banner + Retry button survive a reload.
                // Coalesce a blank value to null: the UI gate is `error?.let`, so
                // a non-null "" would render an empty banner. Defends against any
                // legacy/other-writer "" row.
                error = entity.errorInfo?.takeIf { it.isNotBlank() },
            )
        }.let { messages ->
            // Merge consecutive assistant messages into one:
            // agent loop persists each turn separately, but UI should show them as a single message.
            val merged = mutableListOf<ChatMessage>()
            for (msg in messages) {
                val prev = merged.lastOrNull()
                if (msg.role == "assistant" && prev?.role == "assistant") {
                    // Merge: combine tool blocks, append text, keep the last id.
                    // Deduplicate by block.id — the agent loop may persist the same tool
                    // use in multiple consecutive turns (as it carries tool state across),
                    // and duplicated ids would crash LazyColumn's key uniqueness check.
                    // Keep the LAST occurrence so the most recent status (e.g. SUCCESS with
                    // output) wins over an earlier STREAMING placeholder.
                    val seen = mutableSetOf<String>()
                    val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                        .asReversed()
                        .filter { seen.add(it.id) }
                        .asReversed()
                    val combinedText = when {
                        prev.content.isBlank() -> msg.content
                        msg.content.isBlank() -> prev.content
                        else -> prev.content + "\n\n" + msg.content
                    }
                    merged[merged.lastIndex] = prev.copy(
                        id = msg.id,
                        content = combinedText,
                        toolBlocks = combinedBlocks,
                        // T126-marker: keep every source dbId so Phase 2.5
                        // can resolve markers that point at any of the
                        // pre-merge rows (lastCompactedMessageId is often
                        // an assistant row that gets folded into a later
                        // assistant turn).
                        sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                        // [T-error-persist-android] The error sticker is written
                        // to the LAST assistant row of the turn, so the later row
                        // (`msg`) wins; fall back to `prev` if only it carried one.
                        error = msg.error ?: prev.error,
                    )
                } else {
                    merged.add(msg)
                }
            }
            merged
        }
    }

    private data class ToolResultData(val output: String, val success: Boolean)

    private fun MessageEntity.toLLMMessage(): LLMMessage {
        val r = if (role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
        val contentParts = mutableListOf<AgentContentPart>()
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        var textContent = ""

        try {
            val array = org.json.JSONArray(partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    "text" -> {
                        val value = obj.optString("value", "")
                        // [T-android-retry-attachment-loss] The persisted
                        // <user-attached-files> XML must reach the model via a
                        // contentPart (provider prefers contentParts), but it
                        // must NOT fold into `content`. On a FRESH send the
                        // `content` field is the clean caption (`trimmed`) and
                        // the XML lives only in contentParts; keep restored
                        // messages byte-identical so `.content` consumers
                        // (summary, title fallback, edit) see the same string
                        // as a fresh turn and don't get the XML twice.
                        if (value.contains("<user-attached-files>")) {
                            contentParts.add(AgentContentPart.Text(value))
                        } else {
                            textContent += value
                            contentParts.add(AgentContentPart.Text(value))
                        }
                    }
                    "toolUse" -> {
                        val v = obj.getJSONObject("value")
                        val inputStr = v.optString("input", "{}")
                        val inputJson = try {
                            JSONObject(inputStr)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        contentParts.add(AgentContentPart.ToolUse(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            input = inputJson,
                            // [T-android-gemini3-thoughtsig / #179] Restore the
                            // persisted Gemini 3.x signature so a reloaded session
                            // replays it (else the next gemini-3 turn 400s).
                            thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                        ))
                    }
                    "toolResult" -> {
                        val v = obj.getJSONObject("value")
                        contentParts.add(AgentContentPart.ToolResult(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            content = v.optString("output", ""),
                            isError = !v.optBoolean("success", true),
                        ))
                    }
                    "mediaRef" -> {
                        // T128: load persisted user-message images so the model
                        // sees them on subsequent turns after a session reload.
                        // T150: skip non-image mediaRefs here — their bytes
                        // shouldn't be re-inlined into the LLM payload (parity
                        // with the on-send path, which only inlines images).
                        // The original turn's <user-attached-files> XML stayed
                        // in the persisted text part, and the file is still
                        // on disk under attachments/uploads, so the agent can
                        // re-fetch via shell tools.
                        val v = obj.optJSONObject("value") ?: continue
                        val rel = v.optString("relativePath", "")
                        if (rel.isEmpty()) continue
                        val mime = v.optString("mimeType", "image/jpeg")
                        // [T-android-paste-mediaref] A pasted block is stored as
                        // a mediaRef but is CONTENT, not an attachment: read it
                        // back off disk and inline it here, restoring exactly
                        // the text the original send put in the prompt.
                        //
                        // This branch is what makes every history-replay path
                        // correct at once — session reload, retry, rerun,
                        // edit-resend and compaction all rebuild through this
                        // one converter, so none of them needs its own handling.
                        //
                        // An unreadable file degrades to skipping the part
                        // rather than aborting the message: losing one pasted
                        // block is recoverable, failing to build the request is
                        // not.
                        if (PastedMedia.isPastedRef(mime, v.optString("originalFileName", null))) {
                            val pf = java.io.File(mediaStore.mediaBaseDir, rel)
                            val body = try {
                                if (pf.exists()) pf.readText(Charsets.UTF_8) else null
                            } catch (e: Exception) {
                                AppLogger.warning(TAG, "[Paste] restore failed for $rel: ${e.message}")
                                null
                            }
                            // [T-android-paste-missing-file] An unreadable
                            // pasted file degrades to an explicit marker, never
                            // to silence.
                            //
                            // The file can genuinely disappear — the user clears
                            // app storage, a sync pass prunes it as an orphan,
                            // the disk fills mid-write. Dropping the part on the
                            // floor would leave the model reading a sentence
                            // with a hole in the middle and no way to know
                            // content was ever there, so it would answer
                            // confidently about text it never saw. Saying so
                            // lets it ask, and leaves a searchable trace when a
                            // user reports a strange reply.
                            val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER
                            if (body == null) {
                                AppLogger.warning(
                                    TAG,
                                    "[Paste] missing pasted file for $rel — substituting placeholder",
                                )
                            }
                            textContent += resolved
                            contentParts.add(AgentContentPart.Text(resolved))
                            continue
                        }
                        if (!mime.startsWith("image/")) continue
                        val file = java.io.File(mediaStore.mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                        val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                        // [T-android-vision-group / GH#182] Seed the read_image
                        // hint on restored images too, so a non-vision main model
                        // with a Vision Group configured gets steered to read_image
                        // on subsequent turns after a session reload (not the bare
                        // "can't see it" literal).
                        val restoredPlaceholder = visionPlaceholderFor(restoredPath)
                        imageParts.add(LLMMessage.ImagePart(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                        contentParts.add(AgentContentPart.ImageData(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                    }
                }
            }
        } catch (_: Exception) {
            textContent = partsJson
            contentParts.add(AgentContentPart.Text(partsJson))
        }

        return LLMMessage(
            role = r,
            content = textContent,
            imageParts = imageParts,
            contentParts = contentParts,
            dbMessageId = id,
            reasoningContent = reasoningContent,
        )
    }

    /**
     * Extract a string value for `key` from *partial* (possibly truncated) JSON
     * without needing a complete, parseable object. Mirrors iOS
     * `extractPartialStringValue(_:from:)` in AIChatViewModel.swift.
     *
     * Returns content up to the first unescaped `"`, or the remaining buffer
     * if the closing quote has not streamed yet.
     */
    private fun extractPartialStringValue(key: String, json: String): String? {
        val patterns = listOf("\"$key\": \"", "\"$key\":\"")
        for (p in patterns) {
            val at = json.indexOf(p)
            if (at < 0) continue
            val after = json.substring(at + p.length)
            return unescapePartialJsonString(findUnescapedEnd(after))
        }
        return null
    }

    /** Return substring up to the first unescaped `"`, or the whole string if none. */
    private fun findUnescapedEnd(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                // Skip escaped character (could be `\"`, `\\`, `\n`, etc.)
                i += 2
                continue
            }
            if (c == '"') return s.substring(0, i)
            i++
        }
        return s
    }

    /** Unescape common JSON string escapes. */
    private fun unescapePartialJsonString(s: String): String =
        s.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

    /**
     * Humanize a snake_case tool name into a Title-Case label for pill headers
     * while the model's own `tool_title` arg has not yet streamed in.
     * e.g. `file_write` → "Write File", `shell_execute` → "Execute Shell".
     */
    private fun friendlyToolTitle(toolName: String): String = when (toolName) {
        "shell_execute" -> "Execute Shell"
        "file_read" -> "Read File"
        "file_write" -> "Write File"
        "file_edit" -> "Edit File"
        "browser_use" -> "Browse Web"
        "read_image" -> "Read Image"
        "memory_write" -> "Write Memory"
        "memory_get" -> "Read Memory"
        "web_search" -> "Search Web"
        else -> toolName
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
    }

    /**
     * Parse the JSON tool-arguments string into a plain Map for the loop
     * detector. Malformed JSON degrades gracefully to an empty map — the
     * detector still hashes the tool name, so identical bad calls are still
     * detected as a loop.
     */
    private fun parseToolParams(argsJson: String): Map<String, Any?> {
        if (argsJson.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(argsJson)
            val out = HashMap<String, Any?>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.get(k)
                out[k] = if (v == JSONObject.NULL) null else v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
