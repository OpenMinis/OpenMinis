package com.openminis.app.security

import com.openminis.app.sandbox.GuestWorkloadPolicy
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default SecurityGate.
 *
 * Adapted from XINCODE-Public SecurityGateImpl (GPL-3.0-or-later)
 * https://github.com/kusesad-1122/XINCODE-Public
 *
 * Audit is an in-memory sha256 hash chain (no Room). Tests can tamper entries.
 */
class SecurityGateImpl : SecurityGate {

    @Volatile
    private var _permissionMode = PermissionMode.ASK

    override fun setPermissionMode(mode: PermissionMode) { _permissionMode = mode }
    override fun getPermissionMode(): PermissionMode = _permissionMode

    @Volatile
    private var permissionRules: List<PermissionRule> = emptyList()

    override fun setPermissionRules(rules: List<PermissionRule>) {
        permissionRules = rules
    }

    @Volatile
    private var authorityProfile: PermissionProfile? = null

    override fun setAuthorityProfile(profile: PermissionProfile?) {
        authorityProfile = profile
    }

    /**
     * Caller chat session for the decision in flight.
     *
     * ThreadLocal rather than a field: the offload IPC worker and the chat loop
     * can classify concurrently, and a stale value would deny the caller's own
     * tree (or worse, allow someone else's). [withCallerSession] is the only
     * writer, so the value cannot outlive the call it belongs to.
     */
    private val callerSession = ThreadLocal<String?>()

    /**
     * Whether the guest `/sdcard` bind mount exists right now.
     *
     * Injected by the app because the gate has no Context, and
     * `PRootKernel.shellSharedStorageBinds` is the single source of truth for
     * that plan. Left null the /sdcard rule is skipped rather than guessed.
     */
    @Volatile
    var sdcardMounted: (() -> Boolean)? = null

    /** Run [block] with [sessionId] visible to the path policies inside it. */
    fun <T> withCallerSession(sessionId: String?, block: () -> T): T {
        val previous = callerSession.get()
        callerSession.set(sessionId)
        try {
            return block()
        } finally {
            callerSession.set(previous)
        }
    }

    private val auditTrail = CopyOnWriteArrayList<AuditEntry>()

    /** [T-audit-trail-bounded] Newest-wins cap on the in-memory trail. */
    private val AUDIT_TRAIL_MAX = 500

    companion object {
        val READ_ONLY_TOOLS = setOf(
            "file_read", "list_dir", "grep", "grep_source", "glob",
            "web_search", "web_fetch", "search_sessions", "read_session",
            "memory_get", "recall_memory", "read_image", "describe_image",
            "code_graph", "ocr_image", "get_screen_time",
        )
        val WRITE_TOOLS = setOf(
            "file_write", "file_edit", "multi_edit", "su_exec",
        )
        val SHELL_TOOLS = setOf("shell_exec", "shell_execute", "su_exec", "env_exec")
        // [T-ssh-backend] ssh_exec action split. `get` sits in neither read
        // set: it writes a LOCAL file, so READ_ONLY/PLAN block it, while its
        // reversibility stays REVERSIBLE (workspace files are checkpointable).
        val SSH_MUTATING_ACTIONS = setOf("exec", "put", "add_host", "remove_host", "forget_host_key")
        val SSH_READ_ACTIONS = setOf("list_hosts", "ls", "test")
        val COORDINATOR_TOOLS = setOf(
            "spawn_agent", "run_subagent", "dispatch_agents", "wolfpack_run",
            "agent_plan", "goal", "update_goal", "cronjob", "ask_user_question", "invoke_skill",
            "skill_manage", "memory_write", "memory_get", "save_memory",
            "recall_memory", "ask_reasoning",
        )
        val SAFE_COMMANDS = setOf(
            "ls", "cat", "pwd", "whoami", "id", "echo", "grep", "rg", "egrep", "fgrep",
            "head", "tail", "find", "stat", "file", "wc", "date", "uname", "df", "du",
            "ps", "env", "printenv", "which", "type", "basename", "dirname", "realpath",
            "readlink", "sort", "uniq", "cut", "tr", "diff", "cmp", "md5sum",
            "sha1sum", "sha256sum", "getprop", "true", "test",
        )
        val SAFE_GIT_SUB = setOf(
            "status", "log", "diff", "show", "branch", "rev-parse",
            "remote", "ls-files", "blame",
        )
    }

    override fun classify(toolName: String, toolArgs: String): GateCommand {
        val name = ToolAliases.canonical(toolName)
        return when (name) {
            "su_exec" -> classifyShellCommand(name, toolArgs).copy(capability = Capability.SYSTEM)
            "shell_exec", "shell_execute", "env_exec" -> classifyShellCommand(
                if (name == "shell_execute") "shell_exec" else name,
                toolArgs,
            ).let { if (name == "env_exec") it.copy(capability = Capability.TERMINAL) else it }
            "file_read" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "只读文件操作，可逆")
            "file_write" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "文件写入可回滚")
            "file_edit", "multi_edit" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "文件局部编辑可回滚")
            // Not in WRITE_TOOLS on purpose: capture only READS, and restore
            // rewrites the same bytes a file_write/file_edit already put
            // behind an approval prompt — gating the undo would mean asking
            // the user to approve the rollback of a change they just approved.
            "file_checkpoint" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "文件检查点快照与回滚")
            "list_dir", "grep", "grep_source", "glob" ->
                GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "只读文件/目录操作，可逆")
            "web_search", "web_fetch" -> GateCommand(name, toolArgs, Capability.NET, Reversibility.REVERSIBLE, "只读网络")
            // [T-mcp-native] In-process MCP invocation. This traffic used to ride
            // shell_execute (minis-mcp-cli), which was not in SAFE_COMMANDS and so
            // asked for confirmation in ASK mode; classifying as NET keeps the
            // same approval semantics (ASK → confirm, YOYO → auto). READ_ONLY /
            // PLAN block it below, matching the shell route's behaviour.
            "mcp" -> GateCommand(name, toolArgs, Capability.NET, Reversibility.REVERSIBLE, "MCP 服务器调用")
            // [T-ssh-backend] Remote SSH. Mutating actions touch a machine we
            // cannot roll back → IRREVERSIBLE; probes/listings stay
            // REVERSIBLE. Device risk rules (classifyRisk) deliberately do NOT
            // apply: they encode Android host/guest paths, and the remote is
            // the user's own server — ASK-mode confirmation on
            // IRREVERSIBLE+NET carries the weight here.
            "ssh_exec" -> classifySshExec(name, toolArgs)
            "browser_use" -> classifyBrowserUse(toolArgs)
            "ocr_image" -> GateCommand(name, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "只读图片文字")
            "get_screen_time" -> GateCommand(name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "只读屏幕使用时间")
            "execute_code", "code_exec" ->
                GateCommand(name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "沙箱代码执行")
            "spawn_agent", "run_subagent", "dispatch_agents", "wolfpack_run" ->
                GateCommand(name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "派发子代理")
            "agent_plan", "cronjob", "invoke_skill", "skill_manage", "ask_reasoning",
            "memory_write", "memory_get", "ask_user_question",
            -> GateCommand(name, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "会话协调工具")
            else -> GateCommand(name, toolArgs, Capability.UNKNOWN, Reversibility.IRREVERSIBLE, "未知工具类型，默认不可逆")
        }
    }

    override fun classifyRisk(command: String): RiskLevel {
        var worst = RiskLevel.NORMAL
        val units = riskUnits(command).ifEmpty { listOf(command) }
        for (unit in units) {
            when (classifySegment(unit)) {
                RiskLevel.FATAL_BANNED -> return RiskLevel.FATAL_BANNED
                RiskLevel.DANGEROUS -> worst = RiskLevel.DANGEROUS
                else -> {}
            }
        }
        if (worst == RiskLevel.NORMAL && containsShellExpansionSyntax(command)) {
            return RiskLevel.DANGEROUS
        }
        return worst
    }

    private fun classifySegment(raw: String): RiskLevel {
        val unwrapped = raw.replace(Regex("\\$\\{IFS\\}"), " ")
        val argv = tokenizeCommand(unwrapped)
        val joined = argv.joinToString(" ")
        val systemPaths = listOf(
            "/system", "/system_ext", "/vendor", "/product", "/odm", "/boot", "/recovery",
        ) + APP_DATA_ROOTS
        // [T-rm-root-false-positive] Both halves are argv-level now. The old
        // `joined.contains(p)` arm made `/data` match `/database` and `/product`
        // match `/production`, so an unrelated absolute path plus any `rm `
        // token anywhere on the line (including inside an `echo "…"` argument,
        // via the `raw.contains` arm) was classified FATAL. Assignment forms
        // (`of=/system/img`) are still caught — the value is split off the key.
        if (systemPaths.any { p ->
                argv.any { tok ->
                    val v = tok.substringAfter('=', tok)
                    v == p || v.startsWith("$p/")
                }
            } &&
            argv.any { tok ->
                val base = tok.substringAfterLast('/')
                base == "rm" || base == "dd" || base == "wipe" || base == "format" || base == "shred"
            }
        ) {
            return RiskLevel.FATAL_BANNED
        }
        if (listOf("parted", "fdisk", "mkfs", "/dev/block/", "fastboot", "flash_image").any {
                argv.contains(it) || joined.contains(it)
            }
        ) {
            return RiskLevel.FATAL_BANNED
        }
        val hasRm = argv.firstOrNull() == "rm" || argv.contains("rm")
        val hasRf = argv.any { it == "-rf" || it == "-fr" || (it.startsWith("-") && it.contains("r") && it.contains("f")) }
        if (hasRm && hasRf && argv.any { it == "/" || it == "/*" }) {
            return RiskLevel.FATAL_BANNED
        }
        if (hasRm && hasRf && argv.any { it == "/data" || it == "/data/" } &&
            argv.none { it.startsWith("/data/data/") || it.startsWith("/data/local/") }
        ) {
            return RiskLevel.FATAL_BANNED
        }
        if (hasRm && hasRf && argv.any { it.startsWith("/data/data/") }) {
            return RiskLevel.DANGEROUS
        }
        if (joined.contains("build.prop") || argv.any { it.contains("build.prop") }) {
            return RiskLevel.DANGEROUS
        }
        if (argv.firstOrNull() == "iptables" && argv.contains("-F")) {
            return RiskLevel.DANGEROUS
        }
        if (argv.contains("pm") && argv.contains("uninstall") && joined.contains("--user 0")) {
            return RiskLevel.DANGEROUS
        }
        if (joined.contains("/etc/hosts")) {
            return RiskLevel.DANGEROUS
        }
        if (joined.contains("setenforce 0") || (argv.contains("setenforce") && argv.contains("0"))) {
            return RiskLevel.DANGEROUS
        }
        if (containsShellExpansionSyntax(raw)) {
            return RiskLevel.DANGEROUS
        }
        return RiskLevel.NORMAL
    }

    override fun decide(cmd: GateCommand, mode: PermissionMode): Decision {
        // [Fix-P0] Reordered to match user expectation: ALLOW_ALL should bypass
        // everything except rules + fatal-confirm. Old order put authority fence
        // + fatal-block *before* ALLOW_ALL, breaking user's "allow all" intent.

        // [1] Permission rules: explicit allow/deny bypass everything else.
        val rule = evaluateRules(cmd)
        if (rule == "deny") return Decision.Denied("规则拒绝: ${cmd.toolName}")
        if (rule == "allow") return Decision.Allow("规则放行: ${cmd.toolName}")

        // [2] Always pass: read-only tools (unless mode==DENY_ALL).
        if ((cmd.toolName in READ_ONLY_TOOLS || (cmd.toolName == "browser_use" && isBrowserReadOnlyAction(cmd))) &&
            mode != PermissionMode.DENY_ALL
        ) {
            return Decision.Allow("只读工具自动放行")
        }

        // [3] Always pass: coordinator tools (unless mode blocks them).
        if (cmd.toolName in COORDINATOR_TOOLS && mode != PermissionMode.DENY_ALL &&
            mode != PermissionMode.READ_ONLY && mode != PermissionMode.PLAN
        ) {
            return Decision.Allow("协调工具自动放行")
        }

        // Host `su` shares the app UID, so mode 700 does not hide other chats
        // or databases/minis.db. Guest paths are a separate matter: /data is the
        // sandbox's own tree, and /sdcard only exists once shared storage has
        // been bound. Both need the caller, which this method cannot see, so
        // [withCallerSession] supplies it — a null caller keeps the old
        // deny-anything-private behaviour instead of opening up.
        //
        // [T-supath-follows-mode] Reading the app's own private tree through the
        // USER's host `su` is intended behaviour, not a hole: this is an open,
        // geek-facing project and the person who granted Magisk/KernelSU and put
        // the session in YOYO has already made that call. Refusing it here
        // contradicted the mode they picked — the exact contradiction 2.0.10 had
        // for bare `su` (see the requiresFreshConfirm comment below).
        //
        // The su-granted half of the precondition needs no app-side signal: with
        // no su binary, or with elevation denied, the command cannot succeed
        // whatever this gate decides. Gating on the mode alone therefore yields
        // "YOYO AND host su ⇒ unrestricted" without a false-negative-prone
        // HostSuManager probe.
        //
        // Blast radius stays narrow: sub-agent fences run in READ_ONLY / PLAN /
        // DENY_ALL (PermissionMode KDoc), so a spawned or prompt-injected
        // sub-agent still gets the full refusal. Only a session the user opened
        // by hand is relaxed.
        if (cmd.toolName in SHELL_TOOLS) {
            val command = extractCommand(cmd)
            if (mode != PermissionMode.ALLOW_ALL) {
                SuPathPolicy.denial(command, callerSession.get())
                    ?.let { return Decision.Denied(it, hard = true) }
            }
            // Not mode-gated: this refusal is about CORRECTNESS, not permission.
            // Guest /data is not the phone's /data, and `rm` of a missing path
            // exits 0 — letting it through would report success for a no-op.
            // "Mounted" when nothing injected a provider: refusing commands that
            // name a mount we were never told about would break the shell for a
            // state this object cannot observe.
            GuestMountPolicy.rejection(command, sdcardMounted?.invoke() ?: true)
                ?.let { return Decision.Denied(it, hard = true) }
        }

        // [4] Mode-level block: DENY_ALL, READ_ONLY, PLAN.
        if (mode == PermissionMode.DENY_ALL) {
            return Decision.Denied("当前为拒绝全部模式")
        }
        if (mode == PermissionMode.READ_ONLY || mode == PermissionMode.PLAN) {
            if (cmd.toolName in WRITE_TOOLS || cmd.toolName in SHELL_TOOLS ||
                cmd.toolName == "mcp" ||
                (cmd.toolName == "ssh_exec" && !isSshReadOnlyAction(cmd)) ||
                (cmd.toolName == "browser_use" && !isBrowserReadOnlyAction(cmd))
            ) {
                return Decision.Denied("只读/计划模式禁止写与执行")
            }
        }

        // [5] Destructive / fatal risk. YOYO still asks. Approval mode also asks.
        val command = extractCommand(cmd)
        val risk = commandRisk(cmd, command)
        if (risk == RiskLevel.FATAL_BANNED) {
            // [2.0.7-baseline-fix] In ASK mode there is no auto-allow, so a
            // fatal command can only ever be stopped outright. Offering a
            // confirm dialog means "deny" is a tap away from "run the most
            // destructive command a user can type" — ASK is the stricter
            // mode and must stay strictly stricter than YOYO. YOYO keeps the
            // confirm: its contract is "full-auto except fatal-confirm", and
            // a session that opened the gate has already said yes.
            if (mode == PermissionMode.ASK) {
                return Decision.Denied(
                    describeFatalViolation(command),
                )
            }
            return Decision.NeedConfirm(
                describeFatalViolation(command),
                "⚠️ 极端高危操作（格式化 / 清空 / 批量删除）\n\n${preview(cmd)}",
                mustPrompt = true,
            )
        }

        // Irreversible host damage. A block-device write is refused outright in
        // EVERY mode — the user has no surface to undo a rewritten partition
        // table. Root deletion is a separate arm just below and does follow the
        // mode, because unlike a bricked device a wiped sandbox rootfs can be
        // rebuilt, and YOYO already promises "full-auto except fatal-confirm".
        //
        // Host `su` is a permission question, so it follows the mode. YOYO
        // means "run it": 2.0.10 asked on every `su` even under YOYO, which
        // contradicted the mode the user picked. A bare `su` still needs a
        // confirm in ASK, because "su with no command" is an interactive
        // root shell.
        //
        // An unscoped `find` is neither: it is slow, not irreversible, and
        // the wall clock, output rate and resident window are the brakes for
        // that. Refusing it made a read-only command unavailable.
        if (cmd.toolName in SHELL_TOOLS) {
            // [T-yoyo-root-deletion] Block-device writes stay refused in every
            // mode: a rewritten partition table bricks the device and there is
            // no surface to undo it. Root deletion now follows the mode.
            //
            // YOYO's contract is "full-auto except fatal-confirm", which [5]
            // above already honours for FATAL_BANNED. hostRefusal ignored the
            // mode entirely, so the two arms contradicted each other: `rm -rf /`
            // was a confirmable FATAL via classifySegment but a flat exit 126
            // via hostRefusal, depending on which one ran first. Under YOYO it
            // becomes a mustPrompt confirmation that the session's allow-all
            // cannot swallow; ASK keeps the hard deny, staying strictly
            // stricter than YOYO.
            GuestWorkloadPolicy.blockDeviceRefusal(command)?.let {
                return Decision.Denied(it, hard = true)
            }
            GuestWorkloadPolicy.rootDeletionRefusal(command)?.let {
                if (mode == PermissionMode.ALLOW_ALL) {
                    return Decision.NeedConfirm(
                        it,
                        "⚠️ 不可逆操作：删除文件系统根目录\n\nYOYO 模式下仍需你确认一次，确认后会真正执行。\n\n${preview(cmd)}",
                        mustPrompt = true,
                    )
                }
                return Decision.Denied(it, hard = true)
            }
            if (GuestWorkloadPolicy.requiresFreshConfirm(command) &&
                mode != PermissionMode.ALLOW_ALL
            ) {
                return Decision.NeedConfirm(
                    "宿主提权不能沿用本会话的工具放行",
                    preview(cmd),
                    mustPrompt = true,
                )
            }
        }

        // Shell prefix rule layer (Codex execpolicy semantics, rewritten):
        // must come AFTER every hard refusal above so a rule cannot mask one,
        // and BEFORE any confirmable path below so `forbidden` is not
        // laundered through an approval dialog and `prompt` is not silently
        // executed under YOYO.
        if (cmd.toolName in SHELL_TOOLS) {
            val command = extractCommand(cmd)
            PrefixRulePolicy.evaluate(permissionRules, cmd.toolName, command)?.let { (verdict, pattern) ->
                return when (verdict) {
                    PrefixRulePolicy.Verdict.FORBIDDEN -> Decision.Denied(
                        "规则禁止 (forbidden): $pattern",
                        hard = true,
                    )
                    PrefixRulePolicy.Verdict.PROMPT -> Decision.NeedConfirm(
                        "规则要求确认 (prompt): $pattern",
                        preview(cmd),
                        mustPrompt = true,
                    )
                }
            }
        }

        // [6] YOYO: everything except fatal-confirm is auto-run.
        if (mode == PermissionMode.ALLOW_ALL) {
            return Decision.Allow("YOYO：自动执行")
        }

        // [7] Authority fence: only enforced in ASK mode (workspace jail).
        val authority = authorityProfile
        if (authority != null) {
            val path = extractPath(cmd)
            if (path != null) {
                val write = cmd.toolName in WRITE_TOOLS || cmd.toolName in SHELL_TOOLS
                if (!authority.allows(path, write)) {
                    return Decision.Denied("权威围栏拒绝路径: $path")
                }
            }
            if (cmd.capability == Capability.NET && authority.net == NetPolicy.RESTRICTED &&
                cmd.toolName in setOf("web_fetch", "web_search", "download_file", "ssh_exec")
            ) {
                return Decision.Denied("权威围栏禁止出网")
            }
        }

        // [8] Approval: low-risk auto-run. Everything else confirms.
        if (isLowRisk(cmd, command, risk)) {
            return Decision.Allow("审批：低风险自动放行")
        }
        return Decision.NeedConfirm(cmd.why, preview(cmd))
    }

    private fun commandRisk(cmd: GateCommand, command: String): RiskLevel {
        if (cmd.toolName in SHELL_TOOLS || cmd.toolName == "shell_exec") {
            return classifyRisk(command)
        }
        return RiskLevel.NORMAL
    }

    private fun isLowRisk(cmd: GateCommand, command: String, risk: RiskLevel): Boolean {
        if (risk != RiskLevel.NORMAL) return false
        if (cmd.toolName in READ_ONLY_TOOLS && !(cmd.toolName == "browser_use" && cmd.reversibility == Reversibility.IRREVERSIBLE)) return true
        if (cmd.toolName in COORDINATOR_TOOLS) return true
        if (cmd.toolName in SHELL_TOOLS) return isSafeReadOnlyCommand(command)
        return false
    }

    override fun preview(cmd: GateCommand): String {
        val command = extractCommand(cmd)
        val sb = StringBuilder()
        sb.append("工具: ${cmd.toolName}\n")
        sb.append("能力: ${cmd.capability.label}\n")
        sb.append("可逆: ${cmd.reversibility.label}\n")
        sb.append("说明: ${cmd.why}\n")
        if (command.isNotBlank()) sb.append("目标: $command\n")
        if (cmd.toolName == "env_exec") {
            sb.append("注意: env_exec 跑在 Ubuntu 客户机里，bind 了 /sdcard /storage /data。\n")
        }
        return sb.toString()
    }

    override fun audit(cmd: GateCommand, decision: Decision, result: String?) {
        val ds = when (decision) {
            is Decision.Allow -> "Allow: ${decision.reason}"
            is Decision.NeedConfirm -> "NeedConfirm: ${decision.reason}"
            is Decision.Denied -> "Denied: ${decision.reason}"
        }
        synchronized(auditTrail) {
            // [T-audit-trail-bounded] Long sessions append one entry per
            // decision; without a bound the hash-chained list grew for the
            // life of the process. Trim to the newest window — the chain
            // restarts from the surviving head, which is fine: the trail is
            // a review aid, not a tamper-proof ledger.
            val prev = auditTrail.lastOrNull()?.hash ?: ""
            val ts = System.currentTimeMillis()
            val hash = computeAuditHash(prev, ts, cmd.toolName, cmd.toolArgs, ds, result)
            auditTrail.add(
                AuditEntry(
                    timestamp = ts,
                    toolName = cmd.toolName,
                    toolArgs = cmd.toolArgs,
                    capability = cmd.capability,
                    reversibility = cmd.reversibility,
                    decision = ds,
                    result = result,
                    prevHash = prev,
                    hash = hash,
                ),
            )
            while (auditTrail.size > AUDIT_TRAIL_MAX) auditTrail.removeAt(0)
        }
    }

    override fun getAuditTrail(): List<AuditEntry> = synchronized(auditTrail) { auditTrail.toList() }

    override fun verifyAuditChain(): AuditChainVerification {
        synchronized(auditTrail) {
            // [T-audit-trail-bounded] Long sessions append one entry per
            // decision; without a bound the hash-chained list grew for the
            // life of the process. Trim to the newest window — the chain
            // restarts from the surviving head, which is fine: the trail is
            // a review aid, not a tamper-proof ledger.
            var prev = ""
            for ((i, e) in auditTrail.withIndex()) {
                if (e.prevHash != prev) return AuditChainVerification(ok = false, brokenAt = i)
                val expected = computeAuditHash(e.prevHash, e.timestamp, e.toolName, e.toolArgs, e.decision, e.result)
                if (expected != e.hash) return AuditChainVerification(ok = false, brokenAt = i)
                prev = e.hash
            }
            return AuditChainVerification(ok = true)
        }
    }

    /** Test helper: mutate payload without recomputing hash. */
    fun tamperDecision(index: Int, newDecision: String) {
        synchronized(auditTrail) {
            // [T-audit-trail-bounded] Long sessions append one entry per
            // decision; without a bound the hash-chained list grew for the
            // life of the process. Trim to the newest window — the chain
            // restarts from the surviving head, which is fine: the trail is
            // a review aid, not a tamper-proof ledger.
            val e = auditTrail[index]
            auditTrail[index] = e.copy(decision = newDecision)
        }
    }

    private fun computeAuditHash(
        prevHash: String,
        timestamp: Long,
        toolName: String,
        toolArgs: String,
        decision: String,
        result: String?,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = buildString {
            append(prevHash); append('|')
            append(timestamp); append('|')
            append(toolName); append('|')
            append(toolArgs); append('|')
            append(decision); append('|')
            append(result ?: "")
        }
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * [T-android-rule-laundering] An `allow` rule used to be decided against the
     * JOINED command with substring semantics, so one rule like `cat` or a blank
     * pattern authorised `cat x; rm -rf /data/data/…` — and step [1] returns
     * before SuPathPolicy, GuestMountPolicy, the block-device and fatal arms,
     * which contradicts the ordering contract [PrefixRulePolicy] documents for
     * its own layer. `allow` is therefore matched per risk unit and never over a
     * fatal command. `deny` keeps its old broad matching: over-deny is not a
     * security hole, and tightening it would silently loosen user intent.
     */
    private fun evaluateRules(cmd: GateCommand): String? {
        if (permissionRules.isEmpty()) return null
        val target = extractCommand(cmd)
        val isShell = cmd.toolName in SHELL_TOOLS
        // Segments, not risk units: an allow rule is written in the user's own
        // words (`sudo *`, `timeout *`), so it must keep seeing the wrapper it
        // typed. Units are still what the fatal check runs over, because that
        // question is about the payload, not the spelling.
        val segments = if (isShell) shellSegments(target).ifEmpty { listOf(target) } else listOf(target)
        val hasFatalUnit = isShell &&
            riskUnits(target).any { classifySegment(it) == RiskLevel.FATAL_BANNED }
        var allowHit = false
        for (r in permissionRules) {
            if (!toolFilterMatches(r.toolFilter, cmd.toolName)) continue
            val action = r.action.lowercase()
            if (action == "allow") {
                if (hasFatalUnit) continue
                if (segments.all { matchesAllowPattern(r.pattern, it) }) allowHit = true
                continue
            }
            if (patternMatches(r.pattern, target)) {
                if (action == "deny") return "deny"
            }
        }
        return if (allowHit) "allow" else null
    }

    /**
     * Whole-unit prefix match for an allow pattern: `git` covers `git status` but
     * not `grep category`, `cat*` covers `cat notes.md` but not a later segment
     * of the same command line. A blank pattern authorises nothing — it used to
     * authorise everything.
     */
    private fun matchesAllowPattern(pattern: String, unit: String): Boolean =
        RulePattern.allowMatches(pattern, unit)

    private fun toolFilterMatches(filter: String, toolName: String): Boolean = when {
        filter == "*" || filter.isBlank() -> true
        filter.endsWith("*") -> toolName.startsWith(filter.dropLast(1))
        else -> filter == toolName || ToolAliases.canonical(toolName) == filter
    }

    private fun patternMatches(pattern: String, target: String): Boolean =
        RulePattern.denyMatches(pattern, target)

    private fun extractCommand(cmd: GateCommand): String = when (cmd.toolName) {
        "shell_exec", "shell_execute", "su_exec", "env_exec" -> {
            try { JSONObject(cmd.toolArgs).optString("command", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
        }
        "file_read", "file_write", "file_edit", "multi_edit", "list_dir", "glob", "grep", "grep_source" -> {
            try { JSONObject(cmd.toolArgs).optString("path", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
        }
        "ssh_exec" -> {
            try {
                val a = JSONObject(cmd.toolArgs)
                buildString {
                    append(a.optString("action", ""))
                    val h = a.optString("host", "")
                    if (h.isNotBlank()) append(' ').append(h)
                    val c = a.optString("command", "")
                    val rp = a.optString("remote_path", "")
                    when {
                        c.isNotBlank() -> append(": ").append(c)
                        rp.isNotBlank() -> append(": ").append(rp)
                    }
                }
            } catch (_: Exception) { cmd.toolArgs }
        }
        // args carry a `paths` ARRAY, not `path`. Left on the default branch
        // it would fall through as the raw JSON blob and be read as a command
        // string, so prefix/path rules would match against `{...}` instead of
        // the files actually being snapshotted.
        "file_checkpoint" -> {
            try {
                val a = JSONObject(cmd.toolArgs)
                val arr = a.optJSONArray("paths")
                val joined = if (arr != null && arr.length() > 0) {
                    (0 until arr.length()).joinToString(" ") { arr.optString(it) }
                } else {
                    a.optString("path", "")
                }
                joined.ifBlank { "file_checkpoint ${a.optString("op", "")}" }
            } catch (_: Exception) { "file_checkpoint" }
        }
        else -> cmd.toolArgs
    }

    private fun extractPath(cmd: GateCommand): String? {
        val p = try { JSONObject(cmd.toolArgs).optString("path", "") } catch (_: Exception) { "" }
        return p.takeIf { it.isNotBlank() }
    }

    private fun describeFatalViolation(command: String): String {
        val fatal = riskUnits(command).firstOrNull { classifySegment(it) == RiskLevel.FATAL_BANNED } ?: command
        val norm = fatal.replace(Regex("\\s+"), " ")
        return when {
            APP_DATA_ROOTS.any { norm.contains(it) } -> "禁止写入应用私有数据 ($command)"
            listOf("/system", "/system_ext", "/vendor", "/product", "/odm", "/boot", "/recovery").any { norm.contains(it) } ->
                "禁止写入系统分区 ($command)"
            listOf("parted", "fdisk", "mkfs", "/dev/block/", "fastboot", "flash_image").any { norm.contains(it) } ->
                "禁止操作分区表/块设备"
            // [T-rm-root-false-positive] argv-level, matching classifySegment.
            // `norm.contains("rm -rf /")` described `rm -rf /sdcard/x` as
            // "deleting the root directory", and `contains("rm -rf /data")`
            // fired on `/database` — mislabelling the violation the user was
            // actually being asked about. rmDeletesPath only reports a wholesale
            // target, so the old `/data/data/` and `/data/local/` carve-outs are
            // subsumed: deleting a path *under* /data never matches.
            rmTargetsRoot(fatal) -> "禁止删除根目录"
            rmDeletesPath(fatal, "/data") -> "禁止删除 /data 整体"
            else -> "致命违规操作"
        }
    }

    private fun classifySshExec(name: String, toolArgs: String): GateCommand {
        val action = sshAction(toolArgs)
        return when {
            action in SSH_MUTATING_ACTIONS ->
                GateCommand(name, toolArgs, Capability.NET, Reversibility.IRREVERSIBLE, "SSH 远程执行/变更")
            action == "get" ->
                GateCommand(name, toolArgs, Capability.NET, Reversibility.REVERSIBLE, "SSH 下载远端文件到工作区")
            else -> GateCommand(name, toolArgs, Capability.NET, Reversibility.REVERSIBLE, "SSH 远程只读")
        }
    }

    /**
     * list_hosts / ls / test have no local or remote side effects (test
     * authenticates but changes nothing). Missing/unknown action parses as
     * "" → NOT read-only: conservative, the tool errors on it anyway.
     */
    private fun isSshReadOnlyAction(cmd: GateCommand): Boolean =
        sshAction(cmd.toolArgs) in SSH_READ_ACTIONS

    private fun sshAction(toolArgs: String): String =
        try { JSONObject(toolArgs).optString("action", "").lowercase() } catch (_: Exception) { "" }

    private fun isBrowserReadOnlyAction(cmd: GateCommand): Boolean {
        val action = try { JSONObject(cmd.toolArgs).optString("action", "").lowercase() } catch (_: Exception) { "" }
        return action in setOf(
            "screenshot", "get_text", "scroll", "get_page_info", "find_elements", "hover",
            "get_readable", "get_backbone", "list_tabs", "wait_for_dom_stable",
        )
    }

    private fun classifyBrowserUse(toolArgs: String): GateCommand {
        val action = try { JSONObject(toolArgs).optString("action", "").lowercase() } catch (_: Exception) { "" }
        val readOnly = action in setOf(
            "screenshot", "get_text", "scroll", "get_page_info", "find_elements", "hover",
            "get_readable", "get_backbone", "list_tabs", "wait_for_dom_stable",
        )
        val capability = when (action) {
            "fetch" -> Capability.NET
            "get_cookies", "set_cookies", "execute_js", "navigate", "click", "type" -> Capability.NET
            else -> Capability.SYSTEM
        }
        return GateCommand(
            "browser_use", toolArgs, capability,
            if (readOnly) Reversibility.REVERSIBLE else Reversibility.IRREVERSIBLE,
            if (readOnly) "浏览器只读 action: $action" else "浏览器副作用 action: ${action.ifEmpty { "unknown" }}",
        )
    }

    private fun classifyShellCommand(toolName: String, toolArgs: String): GateCommand {
        val command = try { JSONObject(toolArgs).optString("command", "") } catch (_: Exception) { "" }
        val risk = classifyRisk(command)
        val rev = when (risk) {
            RiskLevel.FATAL_BANNED, RiskLevel.DANGEROUS -> Reversibility.IRREVERSIBLE
            RiskLevel.NORMAL -> Reversibility.REVERSIBLE
        }
        return GateCommand(toolName, toolArgs, inferCapability(command), rev, "风险等级: ${risk.label}, 命令: '$command'")
    }

    private fun inferCapability(command: String): Capability = when {
        command.contains("kill") || command.contains("renice") -> Capability.PROCESS
        command.contains("iptables") || command.contains("ifconfig") -> Capability.NET
        command.contains("mount") || command.contains("insmod") -> Capability.KERNEL
        command.contains("pm ") || command.contains("am ") -> Capability.APP
        command.contains("settings") || command.contains("setprop") -> Capability.SYSTEM
        command.contains("gradle") || command.contains("sdkmanager") || command.contains("apt-get") || command.contains("apt ") -> Capability.BUILD
        command.contains("chroot") || command.contains("env -i") -> Capability.TERMINAL
        else -> Capability.FS
    }

    private fun isSafeReadOnlyCommand(command: String): Boolean {
        if (command.isBlank()) return false
        if (containsShellExpansionSyntax(command)) return false
        val argv = tokenizeCommand(command)
        if (argv.isEmpty()) return false
        val bin = argv[0].substringAfterLast('/')
        if (bin == "git") {
            val sub = argv.getOrNull(1) ?: return false
            return sub in SAFE_GIT_SUB
        }
        return bin in SAFE_COMMANDS
    }
}
