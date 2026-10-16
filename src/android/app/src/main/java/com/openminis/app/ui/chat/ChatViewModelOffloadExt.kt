package com.openminis.app.ui.chat

import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.provider.NonFiniteJson

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
internal fun ChatViewModel.offloadContextIfNeeded(
    contextWindow: Int,
    lastContextTokens: Int,
    force: Boolean = false,
) {
    val sid = activeSessionId
    val policy = ContextPolicy.forContextWindow(contextWindow, effectiveCompactPercent())

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

    AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Triggered ━━━")
    AppLogger.info(ChatViewModel.TAG, "  Window: $contextWindow tokens")
    AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
    if (force) {
        AppLogger.info(ChatViewModel.TAG, "  Mode: FORCE — offloading all eligible candidates")
    } else {
        AppLogger.info(ChatViewModel.TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
        AppLogger.info(ChatViewModel.TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
    }
    AppLogger.info(ChatViewModel.TAG, "  Agent history: ${agentHistory.size} messages")

    val protectedCount = minOf(4, agentHistory.size)
    val candidateUpper = agentHistory.size - protectedCount
    AppLogger.info(ChatViewModel.TAG, "  Scanning messages 0..<$candidateUpper (last $protectedCount protected)")

    val candidates = mutableListOf<ChatViewModel.OffloadCandidate>()
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
                    candidates.add(ChatViewModel.OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                }
                is AgentContentPart.ToolUse -> {
                    if (part.name != "file_write" && part.name != "file_edit") continue
                    val content = part.input.optString("content", "")
                    if (content.length <= 500) continue
                    val tokens = countPartTokens(part)
                    val bytes = content.toByteArray(Charsets.UTF_8).size
                    candidates.add(ChatViewModel.OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                }
                is AgentContentPart.ImageData -> {
                    if (part.data.size <= 1024) {
                        skippedTooSmall++
                        continue
                    }
                    val tokens = countPartTokens(part)
                    // Synthesize a tool id since bare images don't carry one.
                    val synthId = "img${msgIdx}_$partIdx"
                    candidates.add(ChatViewModel.OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                }
                is AgentContentPart.Text -> Unit
            }
        }
    }

    candidates.sortByDescending { it.tokens }
    val totalCandidateTokens = candidates.sumOf { it.tokens }
    AppLogger.info(ChatViewModel.TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens tokens total)")
    AppLogger.info(ChatViewModel.TAG, "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small")

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
                val newInput = NonFiniteJson.parse(part.input.toString())
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
            ChatViewModel.TAG,
            "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~${candidate.tokens} tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
        )
    }

    if (offloadedCount > 0) {
        val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Complete ━━━")
        AppLogger.info(ChatViewModel.TAG, "  Parts offloaded: $offloadedCount")
        AppLogger.info(ChatViewModel.TAG, "  Tokens freed: ~$freedTokens")
        AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
        AppLogger.info(ChatViewModel.TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
        AppLogger.info(ChatViewModel.TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
}
