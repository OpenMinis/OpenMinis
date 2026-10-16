package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition

/**
 * 拾忆-style `spawn_agent` kinds.
 *
 * explore / plan are read-only (tool whitelist). worker may write and must
 * declare [write_paths] when two or more workers run in the same wave.
 * general-purpose is the unrestricted fallback (still no nested spawn).
 */
object SubAgentKind {
    const val WORKER = "worker"
    const val EXPLORE = "explore"
    const val PLAN = "plan"
    const val GENERAL = "general-purpose"

    const val RUN_SUBAGENT = "run_subagent"
    const val SPAWN_AGENT = "spawn_agent"

    const val SIMPLE_TURNS = 10
    // [T-subagent-medium-band] Medium was 20, which starved the most common
    // real-world shape: a focused fix/debug task that is not "implement the
    // login flow" long but needs read → edit → verify cycles, not 10 turns of
    // pure recon. 30 keeps simple recon cheap while a default-sized worker
    // can actually finish a bounded fix.
    const val MEDIUM_TURNS = 30
    const val COMPLEX_TURNS = 40
    const val COMPLEX_MAX_TURNS = 60
    // No longer a hard global ceiling — the coordinator assigns the budget and
    // SubAgentRunner only enforces its own ABSOLUTE_MAX_TURNS runaway guard.
    const val MAX_TURNS = SubAgentRunner.ABSOLUTE_MAX_TURNS

    private val READ_ONLY_ALLOW = setOf(
        "file_read",
        "web_search",
        "search_sessions",
        "read_session",
        "memory_get",
        "read_image",
        "browser_use",
        // grep_source is read-only source recon — exactly what explore/plan are
        // for, so it must be allowed here or those kinds lose their best lookup.
        GrepSourceTool.NAME,
        CodeGraphTool.NAME,
        GrepTool.NAME,
        GlobTool.NAME,
        ListDirTool.NAME,
        "shell_execute",
        "shell_exec",
        "env_exec",
        WebFetchTool.NAME,
        ExecuteCodeTool.NAME,
    )

    private val ALWAYS_DENY = setOf(
        RUN_SUBAGENT,
        SPAWN_AGENT,
        "ask_user_question",
        "AskUserQuestion",
        CronJobTool.NAME,
        DispatchAgentsTool.NAME,
        WolfpackTool.NAME,
    )

    fun isSpawnTool(name: String): Boolean {
        val n = name.trim()
        return n.equals(SPAWN_AGENT, ignoreCase = true) ||
            n.equals(RUN_SUBAGENT, ignoreCase = true) ||
            n.equals(DispatchAgentsTool.NAME, ignoreCase = true) ||
            n.equals(WolfpackTool.NAME, ignoreCase = true)
    }

    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        EXPLORE, "read-only", "readonly", "research", "recon" -> EXPLORE
        PLAN, "planner", "design" -> PLAN
        GENERAL, "general", "general_purpose", "generalpurpose", "gp" -> GENERAL
        else -> WORKER
    }

    fun canWrite(kind: String): Boolean {
        val k = normalize(kind)
        return k == WORKER || k == GENERAL
    }

    fun isReadOnly(kind: String): Boolean {
        val k = normalize(kind)
        return k == EXPLORE || k == PLAN
    }

    fun requiresWritePaths(kind: String, parallelWriters: Int): Boolean {
        return normalize(kind) == WORKER && parallelWriters > 1
    }

    private val MUTATING_BINS = setOf(
        "rm", "mv", "cp", "tee", "chmod", "chown", "chgrp", "mkdir", "rmdir",
        "touch", "truncate", "dd", "install", "apt", "apt-get", "dpkg", "pip",
        "npm", "ln", "unlink", "shred",
    )
    private val SHELL_WRAPPERS = setOf(
        "sudo", "command", "busybox", "env", "sh", "bash", "dash", "ash", "su",
    )

    /**
     * explore/plan may inspect the guest, not change it.
     *
     * Numbered redirects (`2>file`, `1>>out`) are writes. A digit before `>`
     * is not enough to skip the operator — that used to let stderr redirects
     * through. Quotes, here-docs, and fd dups (`2>&1`) are not writes.
     * `sh -c` / `$(...)` payloads are scanned too, or the wrapper hides both.
     */
    // [T-subagent-browser-readonly-actions] browser_use is in
    // READ_ONLY_ALLOW (best web-recon surface) but these ACTIONS mutate remote
    // pages: click/type submit forms and keystrokes, execute_js runs arbitrary
    // page JS, set_cookies rewrites session state.
    private val READONLY_BROWSER_DENY = setOf(
        "click", "type", "hover", "execute_js", "set_cookies",
    )

    /** Denial for a read-only kind driving [browser_use]; null when allowed.
     *  Takes the extracted action string so it stays pure and testable. */
    fun readOnlyBrowserDenial(action: String): String? {
        val a = action.trim().lowercase()
        if (a in READONLY_BROWSER_DENY) {
            return ("Error: explore/plan browser is read-only. navigate, " +
                "screenshot, get_text and scroll are fine; '$a' acts on the " +
                "page and is not.")
        }
        return null
    }

    fun readOnlyShellDenial(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        if (shellMutates(trimmed, 0)) {
            return "Error: explore/plan shell is read-only. date, uname, cat, ls, and df are fine; writes and redirects are not."
        }
        return null
    }

    private fun shellMutates(raw: String, depth: Int): Boolean {
        if (raw.isBlank()) return false
        if (depth > 6) return true
        if (hasFileRedirect(raw, depth)) return true
        if (simpleCommandsMutate(raw)) return true
        for (payload in wrapperPayloads(raw)) {
            if (shellMutates(payload, depth + 1)) return true
        }
        return false
    }

    private fun simpleCommandsMutate(raw: String): Boolean {
        for (seg in splitSimpleCommands(raw)) {
            if (simpleCommandMutates(seg)) return true
        }
        return false
    }

    private fun simpleCommandMutates(unit: String): Boolean {
        val argv = com.openminis.app.security.tokenizeCommand(unit)
        var i = 0
        while (i < argv.size && isEnvAssignment(argv[i])) i++
        while (i < argv.size) {
            val bin = argv[i].substringAfterLast('/')
            if (bin !in SHELL_WRAPPERS) break
            i++
            if (bin == "sudo" || bin == "env" || bin == "command" || bin == "busybox") {
                while (i < argv.size && (argv[i].startsWith("-") || (bin == "env" && isEnvAssignment(argv[i])))) i++
            }
        }
        if (i >= argv.size) return false
        val bin = argv[i].substringAfterLast('/')
        if (bin in MUTATING_BINS) return true
        val rest = argv.drop(i + 1)
        if (bin == "sed" && rest.any(::isSedInPlace)) return true
        if (bin == "perl" && rest.any(::isPerlInPlace)) return true
        return false
    }

    private fun wrapperPayloads(raw: String): List<String> {
        val out = ArrayList<String>()
        for (seg in splitSimpleCommands(raw)) {
            val payload = wrapperPayload(seg) ?: continue
            out.add(payload)
        }
        return out
    }

    private fun wrapperPayload(unit: String): String? {
        val argv = com.openminis.app.security.tokenizeCommand(unit)
        if (argv.isEmpty()) return null
        var i = 0
        while (i < argv.size && isEnvAssignment(argv[i])) i++
        while (i < argv.size) {
            val bin = argv[i].substringAfterLast('/')
            if (bin == "sudo" || bin == "env" || bin == "command" || bin == "busybox") {
                i++
                while (i < argv.size && (argv[i].startsWith("-") || (bin == "env" && isEnvAssignment(argv[i])))) i++
                continue
            }
            if (bin !in setOf("sh", "bash", "dash", "ash", "su")) return null
            val cIdx = cFlagIndex(argv, i + 1)
            if (cIdx < 0 || cIdx + 1 >= argv.size) return null
            return argv[cIdx + 1]
        }
        return null
    }

    /** `-c` or a clustered flag such as `-lc` / `-ic`. The next argv is the script. */
    private fun cFlagIndex(argv: List<String>, from: Int): Int {
        for (i in from until argv.size) {
            val arg = argv[i]
            if (arg == "-c") return i
            if (arg.startsWith("-") && !arg.startsWith("--") && 'c' in arg) return i
        }
        return -1
    }

    private fun isEnvAssignment(tok: String): Boolean {
        val eq = tok.indexOf('=')
        if (eq <= 0) return false
        return tok.substring(0, eq).all { it.isLetterOrDigit() || it == '_' }
    }

    private fun isSedInPlace(arg: String): Boolean {
        if (arg == "--in-place" || arg.startsWith("--in-place=")) return true
        if (arg == "-i" || arg.startsWith("-i.")) return true
        return arg.startsWith("-i") && arg.length > 2 && !arg.startsWith("-in")
    }

    private fun isPerlInPlace(arg: String): Boolean {
        if (arg == "-i" || (arg.startsWith("-i") && !arg.startsWith("-I"))) return true
        return arg.length > 2 && arg[0] == '-' && arg[1] != '-' && arg.indexOf('i') > 0
    }

    private fun hasFileRedirect(raw: String, depth: Int): Boolean {
        var i = 0
        var quote = '\u0000'
        while (i < raw.length) {
            val c = raw[i]
            if (quote == '\'') {
                if (c == '\'') quote = '\u0000'
                i++
                continue
            }
            if (quote == '"') {
                if (c == '\\' && i + 1 < raw.length) {
                    i += 2
                    continue
                }
                if (c == '$' && i + 1 < raw.length && raw[i + 1] == '(') {
                    val jumped = scanSubstitution(raw, i, depth) ?: return true
                    i = jumped
                    continue
                }
                if (c == '`') {
                    val jumped = scanBacktick(raw, i, depth) ?: return true
                    i = jumped
                    continue
                }
                if (c == '"') quote = '\u0000'
                i++
                continue
            }
            if (c == '\\' && i + 1 < raw.length) {
                i += 2
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                i++
                continue
            }
            if (c == '#' && (i == 0 || raw[i - 1].isWhitespace())) {
                val nl = raw.indexOf('\n', i)
                i = if (nl < 0) raw.length else nl + 1
                continue
            }
            if (c == '$' && i + 1 < raw.length && raw[i + 1] == '(') {
                val jumped = scanSubstitution(raw, i, depth) ?: return true
                i = jumped
                continue
            }
            if (c == '`') {
                val jumped = scanBacktick(raw, i, depth) ?: return true
                i = jumped
                continue
            }
            if (c == '<' && i + 1 < raw.length && raw[i + 1] == '<' && (i + 2 >= raw.length || raw[i + 2] != '<')) {
                var j = i + 2
                val stripTabs = j < raw.length && raw[j] == '-'
                if (stripTabs) j++
                val delim = readRedirectWord(raw, j)
                i = skipHereDoc(raw, delim.next, delim.word, stripTabs)
                continue
            }
            if (c == '>' || c == '<' || (c == '&' && i + 1 < raw.length && raw[i + 1] == '>')) {
                val op = parseRedirect(raw, i)
                if (op == null) {
                    i++
                    continue
                }
                if (op.write && !op.harmless) return true
                if (op.nested != null && shellMutates(op.nested, depth + 1)) return true
                i = op.next
                continue
            }
            i++
        }
        return false
    }

    private fun scanSubstitution(raw: String, dollarAt: Int, depth: Int): Int? {
        if (dollarAt + 2 < raw.length && raw[dollarAt + 2] == '(') {
            val end = matchingClose(raw, dollarAt + 1, '(', ')')
            return if (end < 0) null else end + 1
        }
        val end = matchingClose(raw, dollarAt + 1, '(', ')')
        if (end < 0) return null
        val body = raw.substring(dollarAt + 2, end)
        if (shellMutates(body, depth + 1)) return null
        return end + 1
    }

    private fun scanBacktick(raw: String, at: Int, depth: Int): Int? {
        val end = raw.indexOf('`', at + 1)
        if (end < 0) return null
        if (shellMutates(raw.substring(at + 1, end), depth + 1)) return null
        return end + 1
    }

    private class RedirectHit(
        val write: Boolean,
        val harmless: Boolean,
        val next: Int,
        val nested: String? = null,
    )

    private fun parseRedirect(raw: String, at: Int): RedirectHit? {
        if (raw[at] == '&') {
            if (at + 1 >= raw.length || raw[at + 1] != '>') return null
            val target = readRedirectWord(raw, at + 2)
            return RedirectHit(write = true, harmless = isHarmlessTarget(target.word), next = target.next)
        }
        if (raw[at] == '<') {
            if (at + 1 < raw.length && raw[at + 1] == '>') {
                val target = readRedirectWord(raw, at + 2)
                return RedirectHit(write = true, harmless = isHarmlessTarget(target.word), next = target.next)
            }
            if (at + 1 < raw.length && raw[at + 1] == '(') {
                val end = matchingClose(raw, at + 1, '(', ')')
                if (end < 0) return RedirectHit(write = true, harmless = false, next = raw.length)
                return RedirectHit(
                    write = false,
                    harmless = true,
                    next = end + 1,
                    nested = raw.substring(at + 2, end),
                )
            }
            val target = readRedirectWord(raw, at + 1)
            return RedirectHit(write = false, harmless = true, next = target.next)
        }
        var j = at + 1
        var dup = false
        if (j < raw.length && raw[j] == '>') {
            j++
        } else if (j < raw.length && raw[j] == '&') {
            j++
            dup = true
        } else if (j < raw.length && raw[j] == '|') {
            j++
        } else if (j < raw.length && raw[j] == '(') {
            val end = matchingClose(raw, j, '(', ')')
            if (end < 0) return RedirectHit(write = true, harmless = false, next = raw.length)
            return RedirectHit(write = true, harmless = false, next = end + 1)
        }
        val target = readRedirectWord(raw, j)
        val harmless = if (dup) isFdDup(target.word) || isHarmlessTarget(target.word) else isHarmlessTarget(target.word)
        return RedirectHit(write = true, harmless = harmless, next = target.next)
    }

    private class RedirectWord(val word: String, val next: Int)

    private fun readRedirectWord(raw: String, start: Int): RedirectWord {
        var i = start
        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length) return RedirectWord("", i)
        if (raw[i] == '&') {
            var k = i + 1
            while (k < raw.length && raw[k].isDigit()) k++
            if (k > i + 1) return RedirectWord(raw.substring(i, k), k)
        }
        val q = raw[i]
        if (q == '\'' || q == '"') {
            val end = raw.indexOf(q, i + 1)
            if (end < 0) return RedirectWord(raw.substring(i + 1), raw.length)
            return RedirectWord(raw.substring(i + 1, end), end + 1)
        }
        val begin = i
        while (i < raw.length && !raw[i].isWhitespace() &&
            raw[i] != ';' && raw[i] != '|' && raw[i] != '&' &&
            raw[i] != '<' && raw[i] != '>' && raw[i] != '\n' && raw[i] != '\r'
        ) {
            i++
        }
        return RedirectWord(raw.substring(begin, i), i)
    }

    private fun isFdDup(word: String): Boolean {
        val body = word.trim().removePrefix("&")
        return body.isNotEmpty() && body.all { it.isDigit() }
    }

    private fun isHarmlessTarget(word: String): Boolean {
        val w = word.trim().trim('"').trim('\'')
        if (w.isEmpty()) return false
        if (w.startsWith("&") && isFdDup(w)) return true
        return w == "/dev/null" || w == "/dev/stdout" || w == "/dev/stderr" ||
            w == "/dev/tty" || w == "/dev/fd" || w.startsWith("/dev/fd/")
    }

    private fun skipHereDoc(raw: String, from: Int, delim: String, stripTabs: Boolean): Int {
        val marker = delim.trim().trim('"', '\'')
        if (marker.isEmpty()) return from
        val nl = raw.indexOf('\n', from)
        if (nl < 0) return raw.length
        var i = nl + 1
        while (i < raw.length) {
            val lineEnd = raw.indexOf('\n', i).let { if (it < 0) raw.length else it }
            var line = raw.substring(i, lineEnd).trimEnd('\r')
            if (stripTabs) line = line.trimStart('\t')
            if (line == marker) return if (lineEnd < raw.length) lineEnd + 1 else raw.length
            i = if (lineEnd < raw.length) lineEnd + 1 else raw.length
        }
        return raw.length
    }

    private fun matchingClose(raw: String, openAt: Int, open: Char, close: Char): Int {
        var depth = 0
        var quote = '\u0000'
        var i = openAt
        while (i < raw.length) {
            val c = raw[i]
            if (quote != '\u0000') {
                if (c == '\\' && quote == '"' && i + 1 < raw.length) {
                    i += 2
                    continue
                }
                if (c == quote) quote = '\u0000'
                i++
                continue
            }
            if (c == '\\' && i + 1 < raw.length) {
                i += 2
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                i++
                continue
            }
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** Split on `;`, `&&`, `||`, `|`, `&`, and newlines, but not `>&` / `&>` / `>|`. */
    private fun splitSimpleCommands(raw: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = '\u0000'
        var i = 0
        fun flush() {
            val s = cur.toString().trim()
            if (s.isNotEmpty()) out.add(s)
            cur.clear()
        }
        while (i < raw.length) {
            val c = raw[i]
            if (quote != '\u0000') {
                cur.append(c)
                if (c == '\\' && quote == '"' && i + 1 < raw.length) {
                    cur.append(raw[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = '\u0000'
                i++
                continue
            }
            if (c == '\\' && i + 1 < raw.length) {
                cur.append(c).append(raw[i + 1])
                i += 2
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                cur.append(c)
                i++
                continue
            }
            if (c == '\n' || c == '\r' || c == ';') {
                flush()
                i++
                continue
            }
            if (c == '&') {
                val keep = (i + 1 < raw.length && raw[i + 1] == '>') || (i > 0 && raw[i - 1] == '>')
                if (keep) {
                    cur.append(c)
                    i++
                    continue
                }
                flush()
                if (i + 1 < raw.length && raw[i + 1] == '&') i++
                i++
                continue
            }
            if (c == '|') {
                if (i > 0 && raw[i - 1] == '>') {
                    cur.append(c)
                    i++
                    continue
                }
                flush()
                if (i + 1 < raw.length && raw[i + 1] == '|') i++
                i++
                continue
            }
            cur.append(c)
            i++
        }
        flush()
        return out
    }

    fun blocks(kind: String, toolName: String): Boolean {
        if (toolName in ALWAYS_DENY || isSpawnTool(toolName)) return true
        val k = normalize(kind)
        if (k == EXPLORE || k == PLAN) return toolName !in READ_ONLY_ALLOW
        return false
    }

    /**
     * Stable de-duplication by tool name, keeping the first definition seen.
     * A provider may hand us duplicate rows (same name, different schema) and
     * passing them twice breaks downstream tool binding, so collapse them here.
     */
    fun dedupeByName(tools: List<AgentToolDefinition>): List<AgentToolDefinition> {
        val seen = LinkedHashSet<String>(tools.size)
        val out = ArrayList<AgentToolDefinition>(tools.size)
        for (t in tools) {
            if (seen.add(t.name)) out.add(t)
        }
        return out
    }

    fun filterTools(
        kind: String,
        tools: List<AgentToolDefinition>,
        role: String? = null,
        roleContext: android.content.Context? = null,
    ): List<AgentToolDefinition> {
        val base = dedupeByName(tools.filter { !blocks(kind, it.name) })
        val allowed = roleContext?.let { CollabRoles.toolsFor(it, role) }
            ?: CollabRoles.toolsFor(role)
            ?: return base
        return dedupeByName(base.filter { it.name in allowed })
    }

    /**
     * Simple recon ≈ 10 turns; medium ≈ 30; complex implement/refactor ≈ 40–60.
     * Callers still clamp to the user-facing settings cap.
     */
    fun inferTurns(kind: String, prompt: String): Int {
        val k = normalize(kind)
        val len = prompt.length
        val simple = containsSimpleHint(prompt)
        val complex = containsComplexHint(prompt)
        return when (k) {
            EXPLORE -> if (len > 1500 || complex) MEDIUM_TURNS else SIMPLE_TURNS
            PLAN -> if (len > 2000 || complex) COMPLEX_TURNS else MEDIUM_TURNS
            else -> when {
                len > 3500 || (complex && len > 1800) -> COMPLEX_MAX_TURNS
                len > 1200 || complex -> COMPLEX_TURNS
                len < 400 && simple -> SIMPLE_TURNS
                else -> MEDIUM_TURNS
            }
        }
    }

    /**
     * The coordinator's assigned budget wins outright — no global settings
     * clamp. Only SubAgentRunner.ABSOLUTE_MAX_TURNS bounds a runaway. When the
     * coordinator omits max_turns, auto-size from the prompt (unbounded by the
     * old 60-turn cap, so a complex task can actually get the turns it needs).
     */
    fun clampTurns(
        kind: String,
        requested: Int?,
        cap: Int = MAX_TURNS,
        prompt: String = "",
    ): Int {
        val limit = cap.coerceIn(1, MAX_TURNS)
        val target = if (requested == null || requested <= 0) {
            inferTurns(kind, prompt)
        } else {
            requested
        }
        return target.coerceIn(1, limit)
    }

    private fun containsSimpleHint(prompt: String): Boolean {
        val p = prompt.lowercase()
        val keys = listOf(
            "look", "find", "list", "where", "summarize",
            "搜索", "查找", "看看", "摘要", "定位",
        )
        return keys.any { p.contains(it) }
    }

    private fun containsComplexHint(prompt: String): Boolean {
        val p = prompt.lowercase()
        // [T-subagent-complex-hints] The verb list decides whether a short
        // prompt gets the complex tier. It used to miss the everyday repair
        // verbs (fix/debug/investigate/…), so "修复登录 bug" sized as plain
        // medium and ran dry at 20 turns. False positives are cheap — turns
        // are a cap, not a target — so err toward the higher tier.
        val keys = listOf(
            "implement", "refactor", "migrate", "rewrite", "fix all",
            "fix", "repair", "debug", "investigate", "troubleshoot",
            "analyze", "optimise", "optimize", "integrate", "compile",
            "build", "port", "review", "audit", "test",
            "实现", "重构", "迁移", "重写", "全量",
            "修复", "修好", "排查", "调试", "诊断", "分析", "优化",
            "集成", "编译", "构建", "移植", "审查", "测试",
        )
        return keys.any { p.contains(it) }
    }
}
