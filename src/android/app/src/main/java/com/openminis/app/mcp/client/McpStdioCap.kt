package com.openminis.app.mcp.client

/**
 * [T-mcp-stdio-cap] Upstream 1.15-beta.2: "Live STDIO MCP servers are
 * capped, and stopping one ends its whole process tree."
 *
 * Pure decision core for the cap half — which cached live STDIO servers to
 * evict when opening one more would exceed the cap. Kept free of Android
 * types so it is JVM-testable; [McpNativeBridge] feeds it the live set and
 * applies the plan.
 *
 * Policy:
 *  - Only LIVE stdio servers count (a cached entry whose process died does
 *    not occupy a slot).
 *  - Eviction order: least-recently-used IDLE server first. An idle server
 *    is one with no in-flight JSON-RPC request — evicting a busy one would
 *    abort a tool call the agent is waiting on.
 *  - When not enough idle servers exist to get under the cap, opening the
 *    new server is REFUSED with a distinct reason: silently killing a busy
 *    server mid-call is worse than a tool error the model can react to.
 */
internal object McpStdioCap {

    /** Upstream-parity cap on simultaneously live STDIO server processes. */
    const val DEFAULT_MAX_LIVE = 8

    /** One live STDIO server as seen by the cache. */
    data class Entry(
        val serverId: String,
        /** Monotonic-ish timestamp of the last cache hit (System.currentTimeMillis). */
        val lastUsedMs: Long,
        /** True while a JSON-RPC request is in flight on this server. */
        val busy: Boolean,
    )

    /** Why [plan] refused instead of evicting. */
    enum class Refusal { ALL_BUSY }

    sealed interface Decision {
        /** Evict these server ids (already sorted) before spawning the new one. */
        data class Evict(val serverIds: List<String>) : Decision

        /** Cap cannot be met without killing busy servers — refuse the new spawn. */
        data class Refused(val reason: Refusal) : Decision
    }

    /**
     * @param live cached STDIO servers that are alive right now, EXCLUDING
     *        the server about to be (re)built — a rebuild of the same id
     *        replaces its own slot and must not count twice.
     */
    fun decide(live: List<Entry>, cap: Int = DEFAULT_MAX_LIVE): Decision {
        if (cap <= 0) return Decision.Refused(Refusal.ALL_BUSY)
        if (live.size < cap) return Decision.Evict(emptyList())
        val idle = live.filter { !it.busy }.sortedBy { it.lastUsedMs }
        val need = live.size + 1 - cap
        if (idle.size < need) return Decision.Refused(Refusal.ALL_BUSY)
        return Decision.Evict(idle.take(need).map { it.serverId })
    }
}
