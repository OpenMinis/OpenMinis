package com.openminis.app.ui.chat

/**
 * [T-token-scan-memo] Performance roadmap §4-P1: token estimation re-scans
 * every character of every historical message on each call — send-time gates,
 * in-loop policy checks, and progressive-trim level decisions all walk the
 * full history — and the ToolUse branch calls `input.toString()` per part, a
 * linear copy of multi-KB JSON arguments (the real dominant cost on long
 * tool-heavy sessions).
 *
 * Both inputs are immutable for their lifetime: messages are data classes and
 * a streamed delta appends a NEW String instance; tool args settle as a NEW
 * JSONObject when the call completes. So per-reference memoization is
 * structurally safe — the same reference always scans to the same value, and
 * a "changed" history always produces new keys. No version counter to drift,
 * no list-mutability trap (`agentHistory` is a mutableListOf mutated in
 * place, so an identity-based whole-estimate cache would serve stale totals).
 *
 * `WeakHashMap` keys on equality (String's is content equality — two distinct
 * instances with the same text hit the same value, which is exactly the
 * arithmetic identity), and entries are collected when the referenced objects
 * are dropped from history, so long sessions cannot leak.
 */
internal object TokenScanMemo {

    private val toolUseJson =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())

    private val textScans =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<String, Long>())

    /** Memoizes `input.toString()` per object — multi-KB JSON args copied once. */
    internal fun toolUseInputJson(input: Any): String =
        toolUseJson[input] ?: input.toString().also { toolUseJson[input] = it }

    /** Memoizes the ASCII/CJK token scan per text reference. */
    internal fun mixedTokens(text: String): Long {
        textScans[text]?.let { return it }
        var ascii = 0
        var other = 0
        for (c in text) if (c.code < 128) ascii++ else other++
        val total = ascii / 4L + other * 3L / 5L
        textScans[text] = total
        return total
    }
}
