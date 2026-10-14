package com.openminis.app.tools

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Restricts [FileWriteTool] / [FileEditTool] to prefixes assigned via
 * `spawn_agent.write_paths` (拾忆-style isolation).
 *
 * [T-write-guard-fail-closed] `write_paths` is MODEL-SUPPLIED, so three states
 * must not be collapsed into two:
 *  - ABSENT / blank → no allow-list at all: a solo writer is unrestricted
 *    (legitimate, and what every pre-isolation session expects).
 *  - PRESENT with at least one usable absolute prefix → that allow-list.
 *  - PRESENT but every entry invalid (relative `src`, traversal `../out`,
 *    garbage) → the writer DECLARED an isolation boundary we cannot honour.
 *    That used to fall into the "absent" bucket because the filtered list was
 *    empty, so [denyReason] returned null and the worker got NO path
 *    restriction — the exact opposite of the multi-worker isolation contract.
 *    It now pins a poison prefix ([INVALID]) that denies every write.
 *
 * Implemented as a [ThreadContextElement] so the allow-list survives
 * `withContext` hops and so two parallel sub-agents on the same dispatcher
 * thread cannot overwrite each other's prefixes. [swap]/[restore] remain for
 * tests and any caller that is not inside a coroutine.
 */
object WritePathGuard {

    private val allowed = ThreadLocal<List<String>?>()

    /** Poison marker: prefixes were supplied but none survived validation. */
    internal const val INVALID = "\u0000invalid"

    class Scope(
        prefixes: List<String>,
    ) : ThreadContextElement<List<String>?> {
        private val normalized = sanitize(prefixes)

        companion object Key : CoroutineContext.Key<Scope>

        override val key: CoroutineContext.Key<Scope> get() = Key

        override fun updateThreadContext(context: CoroutineContext): List<String>? {
            val old = allowed.get()
            if (normalized == null) allowed.remove() else allowed.set(normalized)
            return old
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: List<String>?) {
            if (oldState.isNullOrEmpty()) allowed.remove() else allowed.set(oldState)
        }
    }

    /**
     * Filter to absolute-or-`none` prefixes, keeping the three states apart:
     * null = "nothing was supplied" (unrestricted), [INVALID] = "supplied, but
     * every entry was invalid" (deny everything), otherwise the allow-list.
     */
    private fun sanitize(prefixes: List<String>?): List<String>? {
        if (prefixes.isNullOrEmpty()) return null
        val filtered = prefixes
            .map(::normalize)
            .filter { it.startsWith("/") || it.equals(NO_WRITE, true) }
        return if (filtered.isEmpty()) listOf(INVALID) else filtered
    }

    suspend fun <T> withPaths(prefixes: List<String>, block: suspend () -> T): T =
        withContext(Scope(prefixes)) { block() }

    fun current(): List<String> = allowed.get().orEmpty()

    fun swap(prefixes: List<String>?): List<String>? {
        val previous = allowed.get()
        allowed.set(sanitize(prefixes))
        return previous
    }

    fun restore(previous: List<String>?) {
        if (previous.isNullOrEmpty()) allowed.remove() else allowed.set(previous)
    }

    fun denyReason(linuxPath: String): String? {
        val prefixes = allowed.get() ?: return null
        if (prefixes.any { it.equals(NO_WRITE, true) }) {
            return "Error: this worker declared write_paths=none and cannot file_write or file_edit."
        }
        // [T-write-guard-fail-closed] Present-but-invalid must DENY, not pass.
        if (prefixes.any { it == INVALID }) {
            return "Error: this worker's write_paths contained no usable absolute " +
                "prefix, so file_write / file_edit are refused until it supplies " +
                "absolute guest paths (e.g. /var/minis/workspace/src) or write_paths=none."
        }
        if (prefixes.isEmpty()) return null
        val n = normalize(linuxPath)
        if (n.isEmpty()) return "Error: path is empty and write_paths is in effect."
        val ok = prefixes.any { n == it || n.startsWith("$it/") }
        if (ok) return null
        return "Error: path $linuxPath is outside assigned write_paths (${prefixes.joinToString()})."
    }

    /**
     * Export the allow-list into the guest shell so scripts can honour it.
     * Does not `cd` — compilers and `ls /root` must keep working. File tools
     * remain the hard gate.
     */
    fun wrapShellCommand(command: String): String {
        val prefixes = current()
        if (prefixes.isEmpty() || prefixes.any { it == INVALID }) return command
        val escaped = prefixes.joinToString(":") { it.replace("'", "'\\''") }
        return "export MINIS_WRITE_PATHS='$escaped'\n$command"
    }

    const val NO_WRITE = "none"

    fun isNoWrite(paths: List<String>): Boolean =
        paths.size == 1 && paths[0].equals(NO_WRITE, ignoreCase = true)

    /**
     * Parse a model-supplied `write_paths` value into the list a [Scope] should
     * install, keeping ALL THREE states [T-write-guard-fail-closed]:
     *  - absent/blank → empty list (unrestricted),
     *  - `none` → [NO_WRITE],
     *  - ≥1 usable absolute prefix → those prefixes,
     *  - present but nothing usable → [INVALID] (deny everything).
     *
     * The last case used to return an EMPTY list here, i.e. the exact value the
     * "absent" case returns, so the whole three-state distinction the guard makes
     * was destroyed at the parse step: `parseWritePathsArg` → `withPaths` saw an
     * empty list, `sanitize` mapped it to null (= unrestricted) and the worker ran
     * with NO write restriction — the fail-open this tag exists to close, just one
     * function further from the deny check than the original fix touched.
     */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        if (isNoWrite(parts)) return listOf(NO_WRITE)
        val usable = parts.map { normalize(it) }.filter { it.startsWith("/") }.distinct()
        // Keep the "declared but unusable" state distinguishable from "absent".
        return usable.ifEmpty { listOf(INVALID) }
    }

    /** True when the model supplied write_paths that contain nothing usable. */
    fun hasInvalidOnly(raw: String?): Boolean = parse(raw).any { it == INVALID }

    /** True when [paths] is exactly the poison state (present, nothing usable). */
    fun isPoisoned(paths: List<String>): Boolean =
        paths.size == 1 && paths[0] == INVALID

    /**
     * Prefixes that are safe to SHOW (prompts, env exports, tool errors). The
     * [INVALID] marker is a state flag, not a path, and a raw NUL inside a system
     * prompt or `MINIS_WRITE_PATHS` would be nonsense to the model and to a shell.
     */
    fun usablePrefixes(paths: List<String>): List<String> = paths.filter { it != INVALID }

    fun normalize(path: String): String {
        var p = path.trim().replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }
}
