package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.agent.Level
import com.openminis.app.data.ToolLimitPrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.streamStallWatchdog
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject

/**
 * [T-truncated-tool-call-reject] finish_reason=length / max_tokens 意味着
 * 输出预算耗尽，流尾的工具调用参数极可能被截断成半截 JSON。这类调用必须
 * 拒执行并回写结构化错误，而不是拿半截参数去写文件/跑 shell。
 * 镜像 Eta AGENT_RUNTIME.md 的截断即拒执行。
 */
private val TRUNCATED_FINISH_REASONS = setOf("length", "max_tokens")

internal suspend fun ChatViewModel.runAgentLoop(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate> = emptyList(),
    fallbackStrategy: com.openminis.app.data.model.FallbackStrategy = com.openminis.app.data.model.FallbackStrategy.default,
    goalExecutionRun: Boolean = false,
    /** [T-android-instant-thinking] send 路径已预插占位气泡，复用其 id。 */
    overrideAssistantId: String? = null,
) {
    // Room is the transcript authority. Rebuild immediately before the first
    // request so UI paging, cold-open tail limits, and the resident hot-cache
    // cannot hide older user instructions from the model. The current user row
    // is already persisted by sendMessage; this also restores raw BodyStore
    // bodies instead of the display projection.
    withContext(Dispatchers.IO) {
        rebuildAgentHistoryFromDatabase(activeSessionId)
    }
    // [T-android-resume-banner-race] Snapshot the resume epoch at run start:
    // every `_canResume = true` this loop's tail may emit is only allowed to
    // land while this snapshot is still current. A newer send bumps the
    // epoch, so a tail that outlives its replacement drops the raise instead
    // of re-showing the resume banner over the fresh turn.
    val loopResumeEpoch = snapshotResumeEpoch()
    // [T-android-model-switch-next-request] Snapshot the model-selection
    // epoch too: this run's fallback writeback (persisting the fallback
    // model onto the VM) may only land while this snapshot is still
    // current. A user switching models mid-run bumps the epoch, so the
    // writeback drops and the user's choice takes effect on the NEXT
    // request instead of being clobbered by the loop's tail.
    val loopModelEpoch = snapshotModelSelectionEpoch()
    AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
    // [T-generation-run] 每轮生成落账：崩溃后可定位半截流归属哪个会话/模型。
    // 成功时 finish(DONE)；失败路径不落 FINISH，留 RUNNING 由
    // GenerationRunStore.abandoned() 在下次启动扫描时标记。
    val generationRunId = com.openminis.app.agent.GenerationRunStore.start(
        context,
        activeSessionId,
        provider.model.displayName,
    )
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
            ChatViewModel.TAG_STREAM,
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
    var assistantId = overrideAssistantId ?: "assistant_${System.currentTimeMillis()}"
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
    val activeRun = com.openminis.app.service.ActiveRunContext.current()
    activeRun?.associateSession(activeSessionId)
    activeRun?.attachAssistantMessage(assistantId)

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
    val goalManager = com.openminis.app.goal.GoalManager(chatRepository)
    val goalSessionId = activeSessionId
    var goalContinuations = 0
    // [T-subagent-background] Bounded nudges for uncollected detached waves.
    var subAgentCollectNudges = 0
    val goalRunRequested = goalExecutionRun
    // [T-perf-ui-batch] Non-text mutations batch through a 200ms window on Dispatchers.Default.
    val uiBatcher = UiUpdateBatcher(viewModelScope, ::updateAssistantMessage)
    uiBatcher.start()
    // [T-android-stream-drop-autocontinue] Per-run budget of silent
    // auto-continuations after a relay-side stream cut (see the
    // turnFinishReason == null branch below).
    var streamDropContinuations = 0


    // Add placeholder assistant message (once). Mark as awaiting so the
    // "Minis is thinking" indicator shows during the initial request gap
    // before the first stream chunk arrives. Mirrors iOS isAwaitingModelResponse.
    // T300: snapshot the user's current thinking level at message
    // creation so the renderer can hide Deep Thinking blocks for
    // turns the user explicitly asked not to surface, even when a
    // forced-reasoning model still streams reasoning_content.
    // turnThinkingLevel 保留在块外：in-loop compact 的 freshAssistantId
    // 分支同样引用它。
    val turnThinkingLevel = _thinkingLevel.value
    // [T-android-instant-thinking] send 路径已把占位气泡插入 _messages，
    // 此处跳过，避免同一回复出现两条气泡。
    if (overrideAssistantId == null) {
        withContext(Dispatchers.Main) {
            // The model is producing again: the previous turn's
            // retry/interrupt/error sticker is now stale. Clear it BEFORE the
            // new placeholder is appended — clearInlineError() targets the
            // last assistant row, and once the placeholder joins, that row is
            // the new (error-free) message, so the old sticker would survive
            // until the first stream delta's defensive clear. The user-facing
            // rule is "the hint bubble disappears the moment the model starts
            // running", not "once the first token lands".
            clearInlineError()
            _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
                id = assistantId, role = "assistant", content = "", isStreaming = true,
                isAwaitingModelResponse = true,
                thinkingLevel = turnThinkingLevel,
            ))
        }
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
    // [T-compact-on-context-overflow] How many times THIS runAgentLoop has
    // compacted in response to the provider's own context verdict
    // (LLMError.ContextLengthExceeded) rather than the token estimate.
    // Capped at 1: if the provider still refuses after a compaction, the
    // history isn't the problem (or the model's real window is smaller
    // than configured) and fallback/terminal-error handling takes over.
    var contextOverflowCompactions = 0
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
    // [T-main-session-turn-cap] The runaway guard is user-tunable
    // (Settings → Tool Limits); 200 is only the shipped default. Read once
    // per run so a mid-run settings change can't shift the range end under
    // an in-flight iteration.
    val maxTurns = ToolLimitPrefs.mainSessionMaxTurns()
    for (turn in 0 until maxTurns) {
        currentCoroutineContext().ensureActive()
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
            ChatViewModel.InLoopContextAction.PROCEED -> {}
            ChatViewModel.InLoopContextAction.COMPACTED -> {
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
                val freshAssistantId = "assistant_${System.currentTimeMillis()}_${turn}"
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
                            ChatViewModel.TAG,
                            "[Compact] in-loop: dropped empty sealed bubble $sealedId",
                        )
                    }
                    _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
                        id = freshAssistantId,
                        role = "assistant",
                        content = "",
                        isStreaming = true,
                        isAwaitingModelResponse = true,
                        thinkingLevel = turnThinkingLevel,
                    ))
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
                    ChatViewModel.TAG,
                    "[Compact] in-loop: sealed $sealedId, continuing in $freshAssistantId below the divider",
                )
                continue
            }
            ChatViewModel.InLoopContextAction.STOP -> {
                // The loop cannot present a modal mid-flight, so stop
                // safely: user-visible notice + resumable, without the
                // turn-limit error overwrite.
                AppLogger.warning(
                    ChatViewModel.TAG,
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
                    text = context.getString(R.string.vm_context_full_unreduced),
                    iconKind = "compact",
                )
                // [T-android-group-pause-badge-restamp] A LIVE interruption just
                // happened: this is a real entry into the paused state, so the
                // badge's 24h freshness stamp must be refreshed. Cancel any
                // unconsumed re-detection mark left by a prior load so it cannot
                // suppress the re-stamp here.
                markLiveInterruption()
                raiseCanResume(loopResumeEpoch)
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
        val turnStartedAtMs = System.currentTimeMillis()
        val turnStartBlockIndex = allToolBlocks.size
        // T307: per-delta StringBuilder for the running turn text + the
        // currently-open trailing text block, so GROWTH is amortised — the
        // snapshot itself is still an O(message length) String per publish
        // (see `messageTextSnapshot` below for the exact cadence and what
        // would be needed to get rid of it). `currentTextBlockSb` mirrors the
        // trailing text block's growing content; reset to a fresh builder
        // whenever a new text block opens (which happens after a tool_use /
        // thinking break interrupts the text run).
        val turnTextSb = StringBuilder()
        // [T-android-stream-snapshot-helper] The single source for the bubble
        // text published mid-turn: the cross-turn prefix + THIS turn's streamed
        // text. Every publish site below routes through it so the sites cannot
        // drift apart, and it skips the O(prefix) copy while the turn has no
        // text yet — during a long thinking phase `turnTextSb` is empty, so
        // `accumulatedText` ALREADY is the snapshot and concatenating it with
        // "" only allocated a fresh full-size String per reasoning delta.
        //
        // Cadence, stated honestly: the `_messages` publishes are gated by the
        // T256 length-aware throttle, but the active-run overlay update is NOT
        // (it must stay current for the stop path), so on a long reply that one
        // still costs a full copy per text delta. Making it O(1) would need a
        // persistent cross-turn prefix builder — which every rollback /
        // fallback / inject site (turnTextSb.setLength(0) ×2, accumulatedText =
        // "") would have to reseed in lock-step, i.e. a second accumulator to
        // keep in sync through the most reset-prone path in this range — or
        // publishing the turn text on its own and joining it in the UI, which
        // lives in ChatModels/ChatScreen. Neither is worth the regression risk
        // for one String copy, so this helper is the whole optimisation.
        fun messageTextSnapshot(): String =
            if (turnTextSb.isEmpty()) accumulatedText else accumulatedText + turnTextSb.toString()
        // [T-stall-echo-strip] Armed only when the previous attempt stalled
        // mid-stream (see the retry-rollback path); the retried attempt's
        // text deltas route through it before reaching any consumer.
        var stallEchoStripper: com.openminis.app.provider.StallEchoStripper? = null
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

        // [T-android-duplicate-toolcall-replay] Idempotence for re-emitted
        // ToolCallComplete events. A gateway/SSE replay can deliver the same
        // completed call (same raw id, same name, same args) twice within one
        // stream attempt; the [T-dedupe-toolcallid] renamer turns the second
        // sighting into a fresh id ("<id>-2") so the receiver contract stays
        // happy, but the execution loop would dispatch it as a brand-new
        // call — tool runs twice, transcript gets two results (observed live
        // 2026-10-04 as a duplicated verdict inside one assistant bubble).
        // The guard records raw-id sightings as completes arrive;
        // replayed completions are flagged here and refused at dispatch time
        // with a synthetic result so tool_use/tool_result pairing stays
        // balanced without a second execution. Reset alongside toolCalls on
        // the retry-rollback path (a retried attempt re-streams into an
        // empty dispatch list; nothing executed in the dead attempt).
        val toolReplayGuard = com.openminis.app.provider.ToolReplayGuard()
        val replayRenamedIds = mutableSetOf<String>()

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
                AppLogger.warning(ChatViewModel.TAG_STREAM, "[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'")
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
        var retryAttempt = 0  // per-provider; reset when falling back to the next member
        // [T-stall-resume] Mid-stream stalls (stream was alive, then went quiet
        // past the idle budget) carry the already-streamed text into the retry
        // so the model continues instead of regenerating from scratch. Set in
        // the retry catch when the TransientError carries stalledAfterFirstEvent,
        // consumed by the retried requestHistory, cleared once a stream attempt
        // succeeds (a normal `continue` never reaches the rollback that sets it).
        var stallResumeContext: String? = null
        while (!collectDone) {
            try {
                // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                // Cache flag onto the active provider here — the single
                // choke point every turn passes through, regardless of how
                // currentProvider was (re)assigned by the fallback loop.
                // Non-Anthropic providers ignore it (cast fails silently).
                (currentProvider as? com.openminis.app.provider.anthropic.AnthropicProvider)
                    ?.enhancedCache = _enhancedCacheEnabled.value
                try {
                // Route through effectiveAgentHistory() so a populated
                // [_compactSummary] is prepended as a `<context-summary>`
                // user message. Falls through to the raw agentHistory when
                // no compact has happened, so the common path stays zero-copy.
                val requestGoalState = if (goalRunRequested) {
                    withContext(Dispatchers.IO) { goalManager.get(goalSessionId) }
                } else null
                val goalCanContinue = requestGoalState?.let {
                    com.openminis.app.goal.GoalStateMachine.canContinue(it, usefulActivity = true)
                } == true
                if (goalRunRequested && !goalCanContinue) {
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
                    }
                    loopExitedNormally = true
                    break
                }
                val requestGoalPrompt = requestGoalState?.let(goalManager::continuationPrompt)
                val requestHistory = com.openminis.app.goal.withGoalContext(
                    effectiveAgentHistory(),
                    requestGoalPrompt,
                    appendNewUser = goalExecutionRun && turn == 0,
                ).let { history ->
                    // [T-stall-resume] A previous attempt stalled mid-stream:
                    // append the bounded continuation note so this attempt picks
                    // up where it stopped instead of regenerating the same text.
                    val resumeNote = stallResumeContext
                    if (resumeNote == null) history else history + LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = resumeNote,
                        contentParts = listOf(
                            com.openminis.app.data.model.AgentContentPart.Text(resumeNote),
                        ),
                    )
                }
                // [T-first-event-watchdog] A hung relay below the provider
                // never throws — the stream below will cancel itself and surface
                // a TransientError, letting the existing retry chain take over.
                val firstEventTimeoutMs = if ((if (currentModelSupportsReasoning) _thinkingLevel.value else ThinkingLevel.OFF).isEnabled) 90_000L else 45_000L
                // [T-stream-stall-watchdog] Phase 2: once the stream is alive,
                // silence longer than twice the first-event budget means
                // progress stopped, not that it is slow to start. Derived from
                // the existing constant so there is no second number to tune
                // per vendor, and it replaces a 600s transport read timeout as
                // the only bound on a mid-stream stall.
                val streamIdleTimeoutMs = firstEventTimeoutMs * 2
                currentProvider.streamMessage(
                    applyRequestImageBudget(requestHistory),
                    systemPrompt, dynamicMaxTokens(currentProvider, lastContextTokens),
                    temperature = samplingTemperature(_activeEntryId.value),
                    tools = agentTools,
                    thinkingLevel = if (currentModelSupportsReasoning) _thinkingLevel.value else ThinkingLevel.OFF,
                    systemStablePrefixLen = systemPromptStablePrefixLen,
                ).streamStallWatchdog(firstEventTimeoutMs, streamIdleTimeoutMs).collect { chunk ->
            // [T-stream-trace-live] 运行时轨迹录制（日志页开关控制，默认关）。
            com.openminis.app.provider.StreamTraceRecorder.record(chunk)
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
                            // [P-process-card] Record when thinking STARTED so
                            // the block can carry a duration when it settles
                            // (process cards sum block durations; without this
                            // thinking contributed 0 to the card's 时长).
                            startTimeMs = System.currentTimeMillis(),
                        ))
                    } else {
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(content = turnThinking.toString())
                    }
                    uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
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
                        // [P-process-card] Settle the thinking duration here:
                        // first text after reasoning is the end of the thinking
                        // phase, so now - startTimeMs is what the block ran for.
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(
                            toolStatus = ToolBlockStatus.SUCCESS,
                            durationMs = settleThinkingDurationMs(allToolBlocks[thinkIdx]),
                        )
                    }
                    // [T-stall-echo-strip] Route the delta through the echo
                    // suppressor when the previous attempt stalled mid-stream:
                    // the retried request carried a verbatim tail of the
                    // already-shown text as a continuation seed, and echo-prone
                    // models restate it before continuing. Empty output means
                    // the delta is still aligned inside the seed (probe phase)
                    // — hold every UI-side consumer until it resolves.
                    val strippedDelta = stallEchoStripper?.feed(chunk.text) ?: chunk.text
                    if (strippedDelta.isEmpty() && stallEchoStripper != null) {
                        return@collect
                    }
                    // T307: append-only on the StringBuilder; the snapshot the
                    // overlay reads is taken by `messageTextSnapshot()`.
                    turnTextSb.append(strippedDelta)
                    activeRun?.updateAssistantText(messageTextSnapshot())
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
                                ChatViewModel.TAG_STREAM,
                                "[T-android-tool-splits-reply-fix] post-tool_calls content delta merged into pre-tool text block (model=${currentProvider.model.id})",
                            )
                        }
                        currentTextBlockSb!!.append(strippedDelta)
                        currentTextBlockSb!!
                    } else if (!monolithic && lastIdx >= 0 && allToolBlocks[lastIdx].kind == "text" && currentTextBlockSb != null) {
                        currentTextBlockSb!!.append(strippedDelta)
                        currentTextBlockSb!!
                    } else {
                        // New text run — either first text after a tool_use/thinking
                        // break, or first text in this turn. Open a fresh block AND
                        // a fresh accumulator. The new block's content carries the
                        // first delta verbatim; subsequent deltas append to the SB.
                        val freshSb = StringBuilder(strippedDelta)
                        currentTextBlockSb = freshSb
                        val block = AssistantBlock(
                            id = "text_${turn}_${allToolBlocks.size}",
                            kind = "text",
                            content = strippedDelta,
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
                    pendingChunkSb.append(strippedDelta)
                    val len = turnTextSb.length
                    val unflushed = len - lastFlushedLen
                    val throttle = textDeltaThrottleMs(len)
                    val newlineFlush = len < 5_000 && chunk.text.contains('\n') && unflushed >= 50
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastUiUpdateMs >= throttle || newlineFlush) {
                        lastUiUpdateMs = nowMs
                        lastFlushedLen = len
                        pendingChunkSb.setLength(0)
                        // Materialise SB → String for the active block's
                        // content (so Compose sees an immutable snapshot); the
                        // message body itself comes from
                        // `messageTextSnapshot()`. Both are O(n) calls but
                        // happen at throttled cadence, not per delta.
                        // (activeSb === currentTextBlockSb by construction.)
                        materializeActiveTextBlock()
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
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
                        // [P-process-card] Same settle as the text path: a tool
                        // call after reasoning also ends the thinking phase.
                        allToolBlocks[thinkIdx] = allToolBlocks[thinkIdx].copy(
                            toolStatus = ToolBlockStatus.SUCCESS,
                            durationMs = settleThinkingDurationMs(allToolBlocks[thinkIdx]),
                        )
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
                        // active text block + the message snapshot.
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
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
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
                        uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
                    }
                }
                is LLMStreamChunk.ToolInputDelta -> {
                    // [T-dedupe-toolcallid] Translate to the currently-in-flight
                    // renamed id so the per-tool ring + block lookup match
                    // the block that ToolUseStart created.
                    val toolInputId = dedupeToolInputId(chunk.id)
                    val deltaLen = chunk.accumulated.length
                    if (com.openminis.app.text.BoundedText.shouldLogLengthStride(deltaLen)) {
                        android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolInputDelta id=$toolInputId len=$deltaLen")
                    }
                    // Maintain a per-tool ring of the most recent `accumulated`
                    // snapshots so the preflight validator below can dump them
                    // when an empty/invalid call is detected. Cheap (single
                    // append + bounded trim) and lives outside any throttle so
                    // every delta lands here.
                    val ring = toolInputChunkRings.getOrPut(toolInputId) { mutableListOf() }
                    ring.add(chunk.accumulated)
                    if (ring.size > ChatViewModel.TOOL_INPUT_CHUNK_RING_MAX) {
                        // Drop from the front so we keep the most recent N.
                        ring.subList(0, ring.size - ChatViewModel.TOOL_INPUT_CHUNK_RING_MAX).clear()
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
                        // [T-perf-async-db-init] extractPartialStringValue is CPU-bound
                        // string scanning (scans from scratch each delta). Run it on
                        // Dispatchers.Default to prevent blocking the IO dispatcher.
                        val partialTitle = StreamChunkJsonHelper.extractPartialStringValue("tool_title", chunk.accumulated)
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
                                updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                            }
                        }
                    }
                }
                is LLMStreamChunk.ToolCallComplete -> {
                    // [T-android-duplicate-toolcall-replay] Record the RAW id
                    // sighting BEFORE the renamer runs. An identical re-emission
                    // (same raw id + name + serialized args) is flagged so the
                    // dispatch loop refuses it; a same-id call with different
                    // name/args is the documented gateway parallel case and
                    // still dispatches under its renamed id.
                    // [T-perf-async-db-init] args.toString() can be multi-KB
                    // linear copy — run on Dispatchers.Default.
                    val argsString = StreamChunkJsonHelper.serializeArgs(chunk.args)
                    val isReplayedComplete = toolReplayGuard.registerAndCheckReplay(
                        chunk.id, chunk.name, argsString,
                    )
                    // [T-dedupe-toolcallid] Rewrite duplicate id so the
                    // persisted tool_calls list, the block lookup, and
                    // the downstream tool-result join all key on the
                    // same value (matches the rename applied at start).
                    val toolCompleteId = dedupeToolCompleteId(chunk.id)
                    if (isReplayedComplete) {
                        replayRenamedIds += toolCompleteId
                        AppLogger.warning(ChatViewModel.TAG_STREAM, "[ToolReplay] duplicate ToolCallComplete raw=${chunk.id} renamed=$toolCompleteId name=${chunk.name} — will refuse at dispatch, no re-execution")
                    }
                    android.util.Log.d("ToolChain[VM]", "[turn=$turn] ToolCallComplete id=$toolCompleteId name=${chunk.name} args=${argsString.take(300)}")
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
                        uiBatcher.enqueue(assistantId, messageTextSnapshot(), true, allToolBlocks.toList())
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
                        // [T-token-usage-calibration] 用真实 usage 校准估算器。
                        val modelKey = currentModel?.id ?: ""
                        if (modelKey.isNotEmpty()) {
                            com.openminis.app.data.TokenUsageCalibration.observe(
                                modelKey,
                                estimated = estimateAgentHistoryTokens(),
                                actual = lastContextTokens,
                            )
                        }
                        // [T-cache-hit-rate] Per-turn cache hit rate for TokenUsageSheet.
                        val inputTotal = chunk.usage.inputTokens + (chunk.usage.cacheReadInputTokens ?: 0) + 
                            (chunk.usage.cacheCreationInputTokens ?: 0)
                        if (inputTotal > 0) {
                            _lastCacheHitRate.value = 
                                ((chunk.usage.cacheReadInputTokens ?: 0).toDouble() / inputTotal)
                                    .coerceIn(0.0, 1.0)
                        }
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
                // [T-stall-echo-strip] Stream finished: resolve any seed-
                // aligned bytes the stripper still holds (and its final echo
                // determination) before the finalize snapshot below —
                // otherwise a short resumed reply would never surface.
                stallEchoStripper?.let { stripper ->
                    val tail = stripper.flush()
                    if (tail.isNotEmpty()) {
                        turnTextSb.append(tail)
                        val blockSb = currentTextBlockSb
                        if (blockSb == null) {
                            // No text block open (the whole attempt was held
                            // in the probe buffer) — open one for the tail.
                            val freshSb = StringBuilder(tail)
                            currentTextBlockSb = freshSb
                            allToolBlocks.add(
                                AssistantBlock(
                                    id = "text_${turn}_${allToolBlocks.size}",
                                    kind = "text",
                                    content = tail,
                                ),
                            )
                        } else {
                            blockSb.append(tail)
                        }
                        pendingChunkSb.append(tail)
                    }
                    stallEchoStripper = null
                }
                // T94 fix 2: flush any text that landed in the throttle
                // window after the last UI tick. The retry-rollback /
                // turn-finalize paths below assume _messages reflects all
                // accumulated text-deltas, so we must not leave the last
                // 0-50ms worth on the floor.
                if (pendingChunkSb.isNotEmpty()) {
                    pendingChunkSb.setLength(0)
                    // T307: also flush the active text block's pending
                    // tail and publish the message snapshot.
                    materializeActiveTextBlock()
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
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
                com.openminis.app.agent.GenerationRunStore.finish(context, generationRunId, ok = true)
                // [T-stall-resume] The stream attempt succeeded — no resume
                // context should leak into a later turn.
                stallResumeContext = null
                // Stream completed without error — clear any lingering retry UI state.
                if (_autoRetryAttempt.value != 0 || _autoRetryCountdown.value != 0) {
                    _autoRetryAttempt.value = 0
                    _autoRetryCountdown.value = 0
                }
                } finally {
                    // [T-first-event-watchdog] The operator cancels itself; nothing to clean here.
                }
            } catch (e: Exception) {
                if (e is CancellationException && e.cause == null) throw e  // real job cancellation
                val actual = unwrapFlowException(e)
                val isRateLimit = actual is com.openminis.app.data.model.LLMError.RateLimited
                val is5xx = actual is com.openminis.app.data.model.LLMError.ProviderError &&
                    ChatViewModel.HTTP_5XX_STATUS_RE.containsMatchIn(actual.detail)
                val isPermanentCapacity = actual is com.openminis.app.data.model.LLMError.ProviderError &&
                    (actual.detail.contains("[429]") ||
                        com.openminis.app.provider.HttpRetryAfter.isPermanentCapacityBody(actual.detail))
                // Auto-retry on transient network/5xx/transient errors on the SAME provider
                // Same-provider retry for network / 5xx / 429 (honour Retry-After),
                // then fall back to the next group member.
                // [T-llm-error-timeout-phase] Timeout now participates in
                // same-provider retry ONLY for CONNECT-phase (the request
                // never landed — pure local/connectivity issue; falling back
                // to another model cannot help). READ/TTFB timeouts skip
                // straight to fallback (isFallbackable already includes
                // Timeout), so the main loop no longer diverges from the
                // sub-agent loop (which keys off isRetryable). NOTE: with the
                // breaker removed (5b9aa6f), a dead-but-not-429 key is now
                // retried through the full backoff budget before fallback —
                // the accepted cost of removing the breaker, not a
                // classification defect.
                val isTimeoutConnect = actual is com.openminis.app.data.model.LLMError.Timeout &&
                    actual.phase == com.openminis.app.data.model.LLMError.Timeout.TimeoutPhase.CONNECT
                val isTransient = (actual is com.openminis.app.data.model.LLMError.NetworkError ||
                    actual is com.openminis.app.data.model.LLMError.TransientError ||
                    actual is com.openminis.app.data.model.LLMError.RateLimited ||
                    is5xx || isTimeoutConnect) && !isPermanentCapacity
                // [T-compact-on-context-overflow] Upstream 1.14 compaction
                // robustness: compaction is triggered by the provider's
                // context verdict — never by network errors. The taxonomy
                // already separates them (ContextLengthExceeded is only
                // classified from real 400/413 context signals, NetworkError
                // / Timeout / TransientError never reach this branch), so
                // wire the actionable remedy: compact once, then retry the
                // SAME provider with the smaller history. Only when that
                // fails do we fall through to group fallback (a bigger-
                // window member may still rescue) and the terminal error.
                if (actual is com.openminis.app.data.model.LLMError.ContextLengthExceeded &&
                    contextOverflowCompactions < 1
                ) {
                    contextOverflowCompactions += 1
                    Log.w(
                        ChatViewModel.TAG,
                        "📦 Context overflow on ${currentProvider.model.displayName} — " +
                            "compacting once and retrying (attempt $contextOverflowCompactions)",
                    )
                    withContext(Dispatchers.Main) {
                        setTransientInlineError(
                            context.getString(
                                com.openminis.app.R.string.vm_context_filling_compacting,
                                contextTokensForPolicy(),
                                effectiveContextWindowTokens() ?: 0,
                            ),
                        )
                    }
                    val compacted = awaitCompaction()
                    withContext(Dispatchers.Main) { clearInlineError() }
                    if (compacted) {
                        // [T-android-inloop-compact-divider-order / GH#235]
                        // Same seal+swap contract as the in-loop COMPACTED
                        // branch: compactAll() tail-appends the "N messages
                        // compacted" divider AFTER the still-streaming
                        // bubble, so continuing to write into that bubble
                        // renders fresh output ABOVE the divider. Seal it,
                        // drop it when empty, and continue in a FRESH
                        // placeholder below the divider.
                        val sealedId = assistantId
                        val freshAssistantId = "assistant_${System.currentTimeMillis()}_${turn}"
                        withContext(Dispatchers.Main) {
                            updateAssistantMessage(
                                sealedId,
                                messageTextSnapshot(),
                                false,
                                allToolBlocks,
                                isAwaitingModelResponse = false,
                            )
                            val sealed = _messages.value.firstOrNull { it.id == sealedId }
                            if (sealed != null &&
                                sealed.content.isEmpty() &&
                                sealed.toolBlocks.isEmpty()
                            ) {
                                _messages.value = _messages.value.filterNot { it.id == sealedId }
                                AppLogger.info(
                                    ChatViewModel.TAG,
                                    "[Compact] overflow: dropped empty sealed bubble $sealedId",
                                )
                            }
                            _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
                                id = freshAssistantId,
                                role = "assistant",
                                content = "",
                                isStreaming = true,
                                isAwaitingModelResponse = true,
                                thinkingLevel = turnThinkingLevel,
                            ))
                        }
                        clearStreamFlushState(sealedId)
                        if (_streamingById.value.containsKey(sealedId)) {
                            _streamingById.value = _streamingById.value - sealedId
                        }
                        assistantId = freshAssistantId
                        accumulatedText = ""
                        allToolBlocks.clear()
                        allToolInputs.clear()
                        toolInputChunkRings.clear()
                        // Per-attempt state reset — same list as the
                        // transient-retry path above.
                        turnTextSb.setLength(0)
                        currentTextBlockSb = null
                        turnTextBlockIdx = -1
                        turnThinking.clear()
                        toolCalls.clear()
                        toolCallSignatures.clear()
                        toolReplayGuard.reset()
                        replayRenamedIds.clear()
                        pendingChunkSb.setLength(0)
                        lastUiUpdateMs = 0L
                        lastFlushedLen = 0
                        stallResumeContext = null
                        continue  // retry on same provider with compacted history
                    }
                    // Compaction failed — fall through to fallback / throw.
                }
                val maxRetries = effectiveMaxRetries()
                // 429 with another group member: switch endpoints instead of
                // hammering the same key through 1/2/4/8/16s (inside a typical
                // 60s relay window). Same-provider retries remain for network
                // / 5xx, and for 429 when this is the last candidate — at most once.
                val skipSameProviderRetry = (isRateLimit && remainingFallbacks.isNotEmpty()) || isPermanentCapacity
                val sameProviderBudget = if (isRateLimit) minOf(maxRetries, 1) else maxRetries
                if (isTransient && !skipSameProviderRetry && sameProviderBudget > 0 && retryAttempt < sameProviderBudget) {
                    val retryAfter = (actual as? com.openminis.app.data.model.LLMError.RateLimited)?.retryAfterSeconds
                    val delaySec = com.openminis.app.provider.HttpRetryAfter.delaySeconds(
                        retryAttempt, retryAfter, ChatViewModel.AUTO_RETRY_DELAYS_SEC,
                    )
                    retryAttempt += 1
                    val errDesc = actual.message ?: actual.javaClass.simpleName
                    Log.w(ChatViewModel.TAG, "🔁 Transient error on ${currentProvider.model.displayName}, retry $retryAttempt/$maxRetries in ${delaySec}s: $errDesc")
                    withContext(Dispatchers.Main) {
                        _autoRetryAttempt.value = retryAttempt
                        // Show the error inline on the streaming assistant message during countdown.
                        // Keeps isStreaming=true so the UI doesn't tear down the streaming state.
                        setTransientInlineError("$errDesc — retrying ($retryAttempt/$maxRetries)…")
                    }
                    try {
                        for (remaining in delaySec downTo 1) {
                            _autoRetryCountdown.value = remaining
                            kotlinx.coroutines.delay(1000)
                        }
                        val jitter = com.openminis.app.provider.HttpRetryAfter.jitterMs()
                        if (jitter > 0L) kotlinx.coroutines.delay(jitter)
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
                            updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
                        }
                    }
                    // T307: SB-based per-turn accumulators reset.
                    // [T-stall-resume] Capture the partial text BEFORE the reset:
                    // a mid-stream stall (stalledAfterFirstEvent) carries the
                    // already-produced text into the retried request so the model
                    // continues instead of regenerating from scratch.
                    if ((actual as? com.openminis.app.data.model.LLMError.TransientError)?.stalledAfterFirstEvent == true) {
                        val partial = turnTextSb.toString()
                        stallResumeContext = com.openminis.app.provider.StallResume.note(partial)
                        // [T-stall-echo-strip] Arm the echo suppressor for the
                        // retried attempt. The seed must be the exact bytes the
                        // note embeds — StallResume.tail is the single source.
                        stallEchoStripper = com.openminis.app.provider.StallEchoStripper(
                            com.openminis.app.provider.StallResume.tail(partial),
                        )
                    }
                    turnTextSb.setLength(0)
                    currentTextBlockSb = null
                    // [T-android-tool-splits-reply-fix] The tracked turn
                    // text block was just rolled back with the rest of
                    // this turn's partial blocks.
                    turnTextBlockIdx = -1
                    turnThinking.clear()
                    toolCalls.clear()
                    toolCallSignatures.clear()  // [T-android-gemini3-thoughtsig / #179]
                    // [T-android-duplicate-toolcall-replay] Reset replay
                    // bookkeeping with the dispatch list: the retried attempt
                    // re-streams into an empty toolCalls, so raw-id sightings
                    // from the dead attempt must not refuse the fresh ones.
                    toolReplayGuard.reset()
                    replayRenamedIds.clear()
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
                val llmErr = actual as? com.openminis.app.data.model.LLMError
                val shouldFallback = fallbackStrategy == com.openminis.app.data.model.FallbackStrategy.always ||
                    llmErr?.isFallbackable == true ||
                    isRateLimit || is5xx || isPermanentCapacity
                val curGate = currentProvider.callGateKey
                val nextCandidate = if (shouldFallback && _selectedGroupId.value != null) {
                    var c: ChatViewModel.FallbackCandidate? = remainingFallbacks.removeFirstOrNull()
                    while (c != null && com.openminis.app.provider.ProviderKeyGate.sameBucket(c.provider.callGateKey, curGate)) {
                        c = remainingFallbacks.removeFirstOrNull()
                    }
                    c
                } else null
                val next = nextCandidate?.provider
                if (next != null && nextCandidate != null) {
                    val reason = when {
                        isRateLimit -> actual.message ?: "Rate limited"
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
                    Log.i(ChatViewModel.TAG, "🔀 $reason on ${currentProvider.model.displayName}, switching to ${next.model.displayName} (realModelChange=$isRealModelChange)")
                    currentProvider = next
                    retryAttempt = 0
                    // [T-android-model-switch-next-request] Gate the VM
                    // writeback on the model-selection epoch: the run itself
                    // keeps executing on `next` (snapshot semantics — the
                    // fallback was resolved inside THIS run), but if the user
                    // picked a different model mid-run, their selection stays
                    // on the VM and the next request uses it. The old code
                    // wrote unconditionally, silently clobbering the picker.
                    if (ModelSwitchGate.mayWriteBack(loopModelEpoch, snapshotModelSelectionEpoch())) {
                        persistFallbackModelSelection(next, nextCandidate.entryId, isRealModelChange)
                    } else {
                        Log.i(
                            ChatViewModel.TAG,
                            "[ModelSwitch] user changed model mid-run (epoch $loopModelEpoch -> ${snapshotModelSelectionEpoch()}); " +
                                "skipping fallback writeback, user's selection takes effect next request",
                        )
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
                        updateAssistantMessage(assistantId, messageTextSnapshot(), true, allToolBlocks)
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
                    // [T-android-duplicate-toolcall-replay] Reset replay
                    // bookkeeping with the dispatch list: the retried attempt
                    // re-streams into an empty toolCalls, so raw-id sightings
                    // from the dead attempt must not refuse the fresh ones.
                    toolReplayGuard.reset()
                    replayRenamedIds.clear()
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
        activeRun?.updateAssistantText(accumulatedText)

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

        appendBoundedHistory(LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = turnText,
            contentParts = assistantParts,
            reasoningContent = turnReasoningContent,
        ))
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
        // T321: empty-turn diagnostic — fires when GPT-5.5 (or any other
        // provider) returns a turn with no visible text AND no tool calls.
        // Log only; UI behavior unchanged. Pair with OpenAIProvider SSE
        // logs to triage server-empty vs parser-drop vs swallowed-exception.
        if (turnText.isEmpty() && toolCalls.isEmpty()) {
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "empty turn detected: turn=$turn finishReason=$turnFinishReason " +
                    "reasoningLen=${turnThinking.length} reasoningBlobLen=${turnReasoningBlob?.length ?: -1} " +
                    "model=${provider.model.id} provider=${provider.name}"
            )
        }

        var goalAccountingSucceeded = true
        if (toolCalls.isEmpty()) {
            val turnUsageTokens = (lastUsage?.inputTokens ?: 0).toLong() + (lastUsage?.outputTokens ?: 0).toLong()
            goalAccountingSucceeded = runCatching {
                goalManager.recordUsage(
                    goalSessionId,
                    turnUsageTokens,
                    (System.currentTimeMillis() - turnStartedAtMs).coerceAtLeast(0),
                )
            }.onFailure { AppLogger.warning(ChatViewModel.TAG_STREAM, "Goal usage accounting failed: ${it.message}") }.isSuccess
        }

        if (toolCalls.isEmpty() && turnFinishReason != null && turnFinishReason in setOf("stop", "end_turn") &&
            turnText.isNotBlank() && goalAccountingSucceeded && goalRunRequested &&
            goalContinuations < ChatViewModel.MAX_GOAL_CONTINUATIONS &&
            activeRun?.isStopped != true &&
            com.openminis.app.service.SessionActivityTracker.isActive(goalSessionId)
        ) {
            val goal = runCatching { goalManager.get(goalSessionId) }.getOrNull()
            if (goal != null && goalManager.mayContinue(goalSessionId, usefulActivity = true)) {
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, allToolInputs)
                val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                val persistedId = persistAssistantTurnForRun(
                    checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
                )
                if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
                val steering = goalManager.continuationPrompt(goal)
                appendBoundedHistory(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = steering,
                        contentParts = listOf(AgentContentPart.Text(steering)),
                    ),
                )
                goalContinuations++
                AppLogger.info(ChatViewModel.TAG_STREAM, "Goal auto-continuation $goalContinuations/${ChatViewModel.MAX_GOAL_CONTINUATIONS} (session=$goalSessionId)")
                continue
            }
        }

        // [T-subagent-background] The model is about to end the turn while a
        // detached wave it started still owes a result: lanes in flight, or a
        // finished wave whose reports nobody collected. Ending here is exactly
        // how background work gets abandoned — the user reads a confident final
        // answer while the lanes keep burning tokens and their output dies in
        // the registry. Nudge (bounded by MAX_SUBAGENT_COLLECT_NUDGES) so the
        // model either collects, or tells the user plainly that work is still
        // running, which dispatch id it is, and how to pick it up later.
        if (toolCalls.isEmpty() &&
            subAgentCollectNudges < ChatViewModel.MAX_SUBAGENT_COLLECT_NUDGES &&
            activeRun?.isStopped != true
        ) {
            val batchRegistry = com.openminis.app.tools.SubAgentBatchRegistry
            val pending = runCatching {
                batchRegistry.unfinished(goalSessionId, context)
            }.getOrElse { e ->
                AppLogger.warning(ChatViewModel.TAG_STREAM, "sub-agent registry read failed: ${e.message}")
                emptyList()
            }
            if (pending.isNotEmpty()) {
                val nudge = buildString {
                    append("<system-reminder>\n")
                    append(batchRegistry.renderPending(pending))
                    append('\n')
                    pending.forEach { b ->
                        append("- dispatch ").append(b.id.take(8)).append(": ")
                        append(
                            if (b.complete) {
                                "finished, reports NOT collected yet"
                            } else {
                                "${b.running}/${b.lanes.size} lane(s) still running"
                            },
                        )
                        append(" (").append(b.done).append(" done, ").append(b.failed).append(" failed)\n")
                    }
                    append(
                        "\nDo not end the turn as if nothing had been dispatched. Call check_agent now: " +
                            "op=await with timeout_sec to wait for running lanes, op=collect to read the finished " +
                            "reports, then fold those results into your answer. If a lane genuinely cannot finish " +
                            "in time, say so explicitly — name the dispatch id, state that it is still running, and " +
                            "tell the user it can be collected later with check_agent.",
                    )
                    append("\n</system-reminder>")
                }
                // Same shape as the Goal continuation above: persist this turn's
                // assistant output first, otherwise history would carry two
                // consecutive user messages and providers reject the sequence.
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, allToolInputs)
                val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
                persistAssistantTurnForRun(
                    checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
                )
                if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
                appendBoundedHistory(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = nudge,
                        contentParts = listOf(AgentContentPart.Text(nudge)),
                    ),
                )
                subAgentCollectNudges++
                AppLogger.info(
                    ChatViewModel.TAG_STREAM,
                    "sub-agent collect nudge $subAgentCollectNudges/${ChatViewModel.MAX_SUBAGENT_COLLECT_NUDGES} " +
                        "(session=$goalSessionId, dispatches=${pending.size})",
                )
                continue
            }
        }

        // If no tool calls and no Goal continuation, we're done.
        if (toolCalls.isEmpty()) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop turn=$turn no tool calls → break (finishReason=$turnFinishReason)")
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
            }
            val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
            val blockMeta = allToolBlocks.filter { it.kind == "tool_use" }.associateBy { it.id }
            val persistedId = persistAssistantTurnForRun(
                checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
            )
            if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
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
                // [T-android-stream-drop-autocontinue] Relay-side silent cut:
                // the SSE stream ended with no finish_reason and no [DONE]
                // while content was already on screen. Buffet relays do this
                // routinely (nginx proxy_read_timeout during long thinking
                // silences, upstream caps after big tool-call turns) — the
                // "一瞬间出现4个 Execute Shell 然后中断" pattern is exactly a
                // cut right after the tool-call flush.
                //
                // The partial assistant turn is ALREADY appended to
                // agentHistory (turn boundary above) and ALREADY persisted
                // (persistAssistantTurnForRun just ran), so continuing is
                // safe and lossless: append a continue-reminder and re-enter
                // the loop. The next turn streams into the SAME assistant
                // bubble (accumulatedText/allToolBlocks carry over), so the
                // user sees one continuous reply instead of a banner + a
                // manual retry that regenerates everything. Mirrors the Goal
                // auto-continuation pattern. Budget-limited per run; the
                // banner below only fires once the budget is exhausted.
                if (activeRun?.isStopped != true &&
                    streamDropContinuations < ChatViewModel.MAX_STREAM_DROP_CONTINUATIONS
                ) {
                    streamDropContinuations++
                    AppLogger.warning(
                        ChatViewModel.TAG_STREAM,
                        "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                            "auto-continuation $streamDropContinuations/" +
                            "${ChatViewModel.MAX_STREAM_DROP_CONTINUATIONS} " +
                            "(turn=$turn, model=${provider.model.id}, provider=${provider.name})",
                    )
                    val dropReminder = "<system-reminder>The connection dropped mid-reply: your previous assistant message was cut off in transit and ends abruptly in the transcript above. Continue the task from EXACTLY where that message stopped. Do not repeat or restate any text already present there. If a sentence was cut mid-way, complete it; if you were about to call a tool, issue that tool call now; if the work was already finished, give the final answer.</system-reminder>"
                    appendBoundedHistory(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = dropReminder,
                            contentParts = listOf(AgentContentPart.Text(dropReminder)),
                        ),
                    )
                    continue
                }
                AppLogger.warning(
                    ChatViewModel.TAG_STREAM,
                    "stream closed without a finish reason after ${accumulatedText.length} chars — " +
                        "surfacing as an interrupted reply (turn=$turn)",
                )
                if (activeRun?.isStopped != true) {
                    withContext(Dispatchers.Main) {
                        setInlineError(
                            context.getString(R.string.chat_error_stream_dropped_partial),
                        )
                    }
                }
                // [T-android-group-pause-badge-restamp] A LIVE interruption just
                // happened: this is a real entry into the paused state, so the
                // badge's 24h freshness stamp must be refreshed. Cancel any
                // unconsumed re-detection mark left by a prior load so it cannot
                // suppress the re-stamp here.
                markLiveInterruption()
                raiseCanResume(loopResumeEpoch)
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
                    AppLogger.warning(ChatViewModel.TAG_STREAM, "empty turn after tool result — injecting <system-reminder> and retrying one round (turn=$turn)")
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
                if (activeRun?.isStopped != true) {
                    withContext(Dispatchers.Main) { setInlineError(hint) }
                }
            }
            // Auto-title after first exchange
            if (turn == 0) generateSessionTitleIfNeeded()
            loopExitedNormally = true
            break
        }
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")

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

        // [T-truncated-tool-call-reject] finish_reason=length/max_tokens 且
        // 带工具调用 → 参数极可能被截断。不执行半截参数（写文件是半截内容、
        // shell 是半个命令），改写成结构化失败结果让模型下一轮自愈。
        // 镜像 Eta AGENT_RUNTIME.md 的截断即拒执行。
        val truncatedToolTurn = toolCalls.isNotEmpty() &&
            turnFinishReason != null && turnFinishReason in TRUNCATED_FINISH_REASONS

        // Execute all tool calls
        val resultParts = mutableListOf<AgentContentPart>()
        val parallelSubResults = mutableMapOf<String, ToolExecutionResult>()
        var didFanOutSubAgents = false
        for ((id, name, args) in toolCalls) {
            currentCoroutineContext().ensureActive()
            // [T-android-overlay-tool-title] Pull tool_title uniformly
            // from args for ALL tools — without this browser_use's
            // tool_title never reached the overlay (only shell_execute
            // had a per-tool status override that surfaced it). Reading
            // it here also means new tools added later automatically
            // get title-in-overlay behavior without per-call plumbing.
            val dispatchToolTitle = try {
                args.optString("tool_title", "").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            activeRun?.associateSession(activeSessionId)
            activeRun?.setCurrentTool(id, name, args.toString())
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
            // [T-android-duplicate-toolcall-replay] Refuse a replayed
            // ToolCallComplete (same raw id + name + serialized args as a call
            // already dispatched this stream attempt). Executing it again would
            // run the tool twice and put two results into the transcript; the
            // refusal synthesizes an error result under the renamed id so
            // tool_use/tool_result pairing stays balanced. Not recorded in the
            // loop detector — a transport replay is not model behavior.
            if (id in replayRenamedIds) {
                val duplicateMsg = "Error: duplicate tool call ignored. This exact call " +
                    "(same id, name, and arguments) was already dispatched earlier in this " +
                    "turn and its result is already available above. The duplicate was not " +
                    "executed. Continue with the recorded result."
                AppLogger.warning(ChatViewModel.TAG_STREAM, "[ToolReplay] refused duplicate dispatch id=$id name=$name args=${argsStr.take(200)}")
                val dupIdx = allToolBlocks.indexOfFirst { it.id == id }
                if (dupIdx >= 0) {
                    val elapsedDup = System.currentTimeMillis() - allToolBlocks[dupIdx].startTimeMs
                    allToolBlocks[dupIdx] = allToolBlocks[dupIdx].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = "Duplicate call ignored (already executed)",
                        durationMs = elapsedDup,
                    )
                }
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = duplicateMsg,
                    isError = true,
                ))
                toolInputChunkRings.remove(id)
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                }
                continue
            }
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

            currentCoroutineContext().ensureActive()
            // [T-truncated-tool-call-reject] 流因长度限制被切断（finish_reason=
            // length/max_tokens），此工具调用的参数极可能是半截 JSON。拒执行，
            // 让模型看到失败结果后下一轮重新完整调用。镜像 Eta AGENT_RUNTIME.md。
            if (truncatedToolTurn) {
                val truncatedMsg = "Error: This tool call was rejected because the " +
                    "model output was truncated (finish_reason=$turnFinishReason). " +
                    "The arguments are likely incomplete. Re-read any relevant " +
                    "context and re-issue the call with complete arguments."
                val blockIdxTr = allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdxTr >= 0) {
                    val elapsedTr = System.currentTimeMillis() - allToolBlocks[blockIdxTr].startTimeMs
                    allToolBlocks[blockIdxTr] = allToolBlocks[blockIdxTr].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = truncatedMsg,
                        durationMs = elapsedTr,
                    )
                }
                toolLoopDetector.record(name, paramsMap,
                    result = null, errorMessage = truncatedMsg, toolCallId = id)
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = truncatedMsg,
                    isError = true,
                ))
                continue
            }

            currentCoroutineContext().ensureActive()
            android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool START name=$name args=${argsStr.take(200)}")
            if (SubAgentKind.isSpawnTool(name) && !didFanOutSubAgents) {
                didFanOutSubAgents = true
                val cap = MultiAgentSettings.clampConcurrent(multiAgentSettings.maxConcurrent.value)
                val sem = Semaphore(cap)
                val peers = toolCalls.filter { SubAgentKind.isSpawnTool(it.second) }
                val writerCount = peers.sumOf { (_, _, peerArgs) ->
                    parseSubAgentBatch(peerArgs.toString(), com.openminis.app.data.ToolLimitPrefs.subagentMaxTurns())
                        .count { spawn -> SubAgentKind.canWrite(spawn.kind) }
                }
                coroutineScope {
                    peers.mapIndexed { peerIdx, (peerId, _, peerArgs) ->
                        async {
                            val peerResult = executeRunSubAgent(
                                peerArgs.toString(),
                                peerId,
                                allToolBlocks,
                                assistantId,
                                accumulatedText,
                                limiter = sem,
                                parallelWriters = writerCount,
                                waveIndex = peerIdx,
                                waveSize = peers.size,
                            )
                            synchronized(parallelSubResults) { parallelSubResults[peerId] = peerResult }
                        }
                    }.awaitAll()
                }
            }
            val result = if (SubAgentKind.isSpawnTool(name)) {
                parallelSubResults[id] ?: executeTool(name, argsStr, id, allToolBlocks, assistantId, accumulatedText)
            } else {
                executeTool(name, argsStr, id, allToolBlocks, assistantId, accumulatedText)
            }
            currentCoroutineContext().ensureActive()
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
                val mergedContent = if (existingContent.length > resultContent.length) existingContent else resultContent
                val childPrefix = "$id#sub-"
                val childCount = allToolBlocks.count { it.id.startsWith(childPrefix) }
                val finalContent = if (childCount > 0) {
                    "已分发 $childCount 个子代理，点开各自卡片查看当前运行。"
                } else {
                    mergedContent
                }
                // [T-truncated-args-visibility #119] A call built from
                // truncated args must not render as a clean success — that
                // silence is the reported bug. Show it with the same weight
                // as the blocked path. Mirrors iOS ConcurrentTools.
                val finalStatus = when {
                    activeRun?.isStopped == true -> ToolBlockStatus.CANCELLED
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
            currentCoroutineContext().ensureActive()
            val outputForLLMWithNote = if (truncationRepairTag != null) {
                outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
                    "truncated in transit and auto-closed by the client (repair strategy: " +
                    "$truncationRepairTag) before execution. The arguments actually used may be " +
                    "incomplete — verify the result and re-issue the call with complete " +
                    "arguments if anything is missing.</system-reminder>"
            } else {
                outputForLLM
            }
            val outputSpilled = com.openminis.app.tools.ToolOutputSpill.maybeSpill(
                context = context,
                sessionId = activeSessionId,
                toolName = name,
                toolId = id,
                output = outputForLLMWithNote,
            )

            resultParts.add(AgentContentPart.ToolResult(
                id = id,
                name = name,
                content = outputSpilled,
                isError = !result.success,
                imageData = result.imageData,
                imageMimeType = result.imageMimeType,
                imageLinuxPath = result.imageLinuxPath,
            ))
        }

        currentCoroutineContext().ensureActive()
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
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
        val assistantDbId = persistAssistantTurnForRun(
            checkNotNull(activeRun) { "Agent loop has no persistence owner" }, turnParts, lastUsage, turnReasoningContent, blockMeta, accumulatedText,
        )
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }
        if (assistantDbId != null) {
            val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
            if (lastIdx >= 0) {
                agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
            }
        }

        // Persist tool results as user-role message (mirrors iOS)
        val toolResultDbId = persistToolResultMessageForRun(
            checkNotNull(activeRun) { "Agent loop has no persistence owner" },
            resultParts,
            uiMessageId = assistantId,
        )
        if (activeRun?.isStopped == true) { loopExitedNormally = true; break }

        // Add tool results to history
        appendBoundedHistory(LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = resultParts,
            dbMessageId = toolResultDbId,
        ))
        val turnUsageTokens = (lastUsage?.inputTokens ?: 0).toLong() + (lastUsage?.outputTokens ?: 0).toLong()
        val goalUsageResult = runCatching {
            goalManager.recordUsage(
                goalSessionId,
                turnUsageTokens,
                (System.currentTimeMillis() - turnStartedAtMs).coerceAtLeast(0),
            )
        }.onFailure { AppLogger.warning(ChatViewModel.TAG_STREAM, "Goal usage accounting failed: ${it.message}") }
        val goalAfterTurn = goalUsageResult.getOrNull()
        if (goalUsageResult.isFailure || goalAfterTurn?.status in setOf(
                com.openminis.app.data.db.GoalStatus.COMPLETE,
                com.openminis.app.data.db.GoalStatus.BLOCKED,
                com.openminis.app.data.db.GoalStatus.PAUSED,
                com.openminis.app.data.db.GoalStatus.BUDGET_LIMITED,
            )
        ) {
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
            }
            loopExitedNormally = true
            break
        }

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
                ChatViewModel.TAG_STREAM,
                "📨[QueueInterrupt] turn=$turn ${_promptQueue.value.size} queued prompt(s) — interrupting after current tool call to start a standalone turn",
            )
            val handled = try {
                injectQueuedPromptsAsNewTurn(
                    finishedAssistantId = assistantId,
                    finishedAccumulatedText = accumulatedText,
                    finishedAllToolBlocks = allToolBlocks,
                )
            } catch (e: Exception) {
                Log.e(ChatViewModel.TAG, "injectQueuedPromptsAsNewTurn failed", e)
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
    //   (b) `for (turn in 0 until maxTurns)` exhausted → flag stays false,
    //       which means the model kept asking for tool calls past the ceiling.
    //
    // (b) is the only case that needs the inline-error/Resume hand-holding;
    // (a) must NOT be touched or every normal completion gets a fake "hit
    // 200 turns" sticker (the bug user hit at v1.4.0-dev tip).
    // [P-perf-async-db-init] Flush pending UI batches before the final update.
    uiBatcher.flush()
    uiBatcher.stop()
    if (!loopExitedNormally) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "runAgentLoop EXIT — hit turn cap=$maxTurns, finalizing as resumable",
        )
        withContext(Dispatchers.Main) {
            finalizeAtTurnLimit(assistantId, accumulatedText, allToolBlocks, loopResumeEpoch)
        }
    } else {
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
    }
    // [T-context-ring] Refresh the live token ring after the turn settles.
    refreshContextUsage()

    // [T-android-tts-scene-announcements] Loop converged — announce to the
    // user if they opted in (scene pref, default off). The sink is owned by
    // the UI layer; every failure mode (no engine bound, engine not yet
    // initialized, pref off) is swallowed inside the coordinator.
    runCatching {
        val sink = announcementSink ?: return@runCatching
        com.openminis.app.speech.VoiceAnnouncementCoordinator(sink)
            .onScene(context, com.openminis.app.speech.AnnouncementScene.IDLE_AGENT_DONE)
    }
}

/**
 * [T-android-model-switch-next-request] Persist a group-fallback model
 * switch onto the VM so the next sendMessage() starts from it. Extracted
 * from the fallback branch of runAgentLoop so the loop can gate the
 * call on the model-selection epoch — when the user picks a model
 * mid-run, this writeback is skipped and their selection wins (see
 * [ModelSwitchGate]).
 */
internal fun ChatViewModel.persistFallbackModelSelection(
    next: LLMProvider,
    entryId: String?,
    isRealModelChange: Boolean,
) {
    // Also update class-level provider so the next sendMessage() starts from here
    this.currentProvider = next
    // Update top bar model info + active entry. (For a same-model
    // endpoint recovery these are no-ops on the visible model name, but
    // still keep activeEntryId / provider name in sync with the instance
    // we actually used.) [T-android-model-switch-next-request] Reads the
    // non-null parameter, not the VM's nullable property — the original
    // loop read its local `var currentProvider`, and the bare name inside
    // this extension resolves to `this.currentProvider` (LLMProvider?).
    _modelName.value = next.model.displayName
    // Update activeEntryId so model picker reflects the switch.
    // [T-android-fallback-entry-identity] Look the entry up by its OWN id,
    // carried on the candidate. The previous
    // `find { it.model.id == currentProvider.model.id }` was ambiguous:
    // two instances can expose the same model id, so it returned whichever
    // entry sits earlier in modelEntries. Observed in the field — falling
    // back onto `deepseek-v4-flash` served by "DeekSeak" showed the
    // provider as "Bailian OpenAI", because Bailian also has a
    // `deepseek-v4-flash` entry and happened to be found first. That also
    // poisoned activeEntryId and the persisted binding, so re-entering the
    // session resumed on the WRONG instance.
    val newEntry = providerRepository.config.value.modelEntries.find { it.id == entryId }
    if (newEntry != null) {
        _activeEntryId.value = newEntry.id
        currentModel = newEntry.model
        val newInstance = providerRepository.instance(newEntry.providerInstanceId)
        if (newInstance != null) {
            _providerName.value = newInstance.label.ifEmpty { newEntry.model.provider }
        }
    }
    // Flash ONLY on a genuine model switch — never on a transparent
    // same-model endpoint retry.
    if (isRealModelChange) _fallbackTrigger.value++
    // [T-android-cross-window-compact] REVERTED by design: auto-compacting
    // on a model switch silently dropped context the user could still see
    // (reads as data loss). The new model's own provider error, the
    // in-loop threshold guard, and dynamicMaxTokens() handle the smaller
    // window without surprise truncation.
    // Persist the fallback model so re-entering the session starts from here
    val groupId = _selectedGroupId.value
    if (groupId != null && newEntry != null) {
        persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${newEntry.id}"}""")
    }
}
