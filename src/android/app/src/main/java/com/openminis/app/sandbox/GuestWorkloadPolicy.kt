package com.openminis.app.sandbox

/**
 * Handheld ceilings for guest work. This APK only runs on a phone or tablet:
 * a Python tree walk at foreground priority wedged a 12GB device in eight
 * minutes (OML-IA-2026-0927). Scheduling isolation is the primary brake;
 * these classifiers decide the wall-clock, CPU, disk and approval brakes.
 *
 * Pure. No Android types, so the decisions are unit-tested without a device.
 */
internal object GuestWorkloadPolicy {

    const val CPU_SECONDS_DEFAULT = 120
    const val CPU_SECONDS_BUILD = 300
    const val CPU_SECONDS_INSTALL = 300
    const val CPU_SECONDS_SETUP = 900

    /**
     * `ulimit -u` and the host-side cap. A guest that raises its own soft
     * limit, or forks before the prefix runs, still dies when the host sees
     * more than this many owned pids.
     */
    const val PROCESS_LIMIT = 256

    const val NOFILE_LIMIT = 4096

    /**
     * `ulimit -f` is in 1024-byte blocks. 2GB stops one `dd` from filling the
     * volume without rejecting an SDK zip or an apt package. A directory of
     * small files is caught by [diskRefusal], not by this per-file cap.
     */
    const val FILE_SIZE_KB = 2 * 1024 * 1024

    const val DISK_MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
    const val DISK_HARD_USED = 0.92

    const val HOST_SU_MAX_TIMEOUT_MS = 120_000L

    /**
     * Hard CPU budget. Command names do not opt out: a process that calls
     * itself a server can still spin. An idle server burns almost no CPU, so
     * the cap does not kill it. Only the documented setup scripts get more.
     */
    fun cpuSeconds(command: String): Int =
        com.openminis.app.sandbox.kernel.BudgetClassifier.classify(command).cpuSeconds

    fun isSetup(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("minis-dev-setup-full") ||
            c.contains("minis-build-env") ||
            c.contains("minis-android-sdk-setup") ||
            c.contains("minis-self-build")
    }

    fun isBuild(command: String): Boolean {
        val c = command.lowercase()
        if (Regex("""(?:^|\s|\./)gradle(?:w)?\s+--stop\s*$""").containsMatchIn(c)) return false
        return c.contains("gradle") || c.contains("assembledebug") ||
            c.contains("assemblerelease") || c.contains("aapt2")
    }

    fun isInstall(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("apt-get") || c.contains("apt ") || c.contains("dpkg") ||
            c.contains("sdkmanager") || c.contains("minis-build-env") || c.contains("minis-dev-setup") ||
            c.contains("pip install") || c.contains("pip3 install")
    }

    /**
     * True for a `find` rooted at /, /data, /home, /root, /var, /sys or /proc.
     *
     * Not a refusal any more. 2.0.10 refused these outright in every mode,
     * which made a read-only command unavailable; slowness is handled by the
     * wall clock, the output rate and the resident window. Kept because it is
     * the honest description of the walk, and a caller that wants to warn
     * about it should ask here rather than re-parsing argv.
     */
    fun isBroadFind(command: String): Boolean =
        findPathArgs(command).any(::isBroadRoot)

    /**
     * Refuse before spawn when the disk is already stressed. This is not a
     * denylist of known tools: anything that is not an obvious read is
     * refused, including a binary we have never seen. Reads stay available
     * so the agent can still see why.
     *
     * [T-disk-percent-arm] The stress test used to have a second arm,
     * `used >= 0.82`. A percentage says nothing about the room left: on a
     * 477 GB volume, 82% used still leaves 86 GB free, and the gate refused
     * every non-whitelisted command — including `du`, the one command that
     * finds what to clean, and `rm` / `apt-get clean`, the commands the
     * refusal message told the user to run. Stress is measured in ABSOLUTE
     * free bytes only ([DISK_MIN_FREE_BYTES] — the honest signal that the
     * volume is the constraint), and commands that FREE space
     * ([isDiskRelief]) are admitted alongside reads so the advised cleanup
     * is never blocked by the advice itself.
     */
    fun diskRefusal(availableBytes: Long, totalBytes: Long, command: String): String? {
        if (totalBytes <= 0L || availableBytes < 0L) return null
        if (isObviouslyReadOnly(command) || isDiskRelief(command)) return null
        if (availableBytes >= DISK_MIN_FREE_BYTES) return null
        val freeMiB = availableBytes / (1024 * 1024)
        return "命令未启动（exit 126）：磁盘空闲不足 2GB（剩余 ${freeMiB} MiB）。" +
            "此时只放行只读命令和释放空间的命令（如 rm、apt-get clean）。" +
            "先清理空间再重试。"
    }

    /**
     * [T-disk-percent-arm] Commands whose effect is to FREE space. The old
     * gate refused these under the same stress that made the refusal message
     * say "先清理空间再重试" — the cleanup path was blocked by the very gate
     * demanding it. Deletion SAFETY is not decided here: root deletion and
     * block-device writes keep their own refusals and run BEFORE this gate
     * (see [hostRefusal] in the executor), and the confirmation gates still
     * apply. This only admits commands that reduce usage, so a stressed
     * volume can be recovered from inside the app.
     */
    fun isDiskRelief(command: String): Boolean {
        if (hasWriteSyntax(command)) return false
        val segments = splitCommandSegments(command)
        if (segments.isEmpty()) return false
        return segments.all { segment ->
            val token = commandToken(segment) ?: return@all false
            token in READ_ONLY_TOKENS || isReliefVerb(token, segment)
        }
    }

    private fun isReliefVerb(token: String, segment: String): Boolean {
        val body = segment.lowercase()
        return when (token) {
            "rm", "unlink" -> true
            // `truncate -s 4G` GROWS a file; only the shrink-to-zero forms
            // are relief.
            "truncate" -> Regex("""(?:-s|--size)\s*=?\s*0\b""").containsMatchIn(body)
            "apt", "apt-get" -> Regex("""\b(clean|autoclean|autoremove)\b""").containsMatchIn(body)
            "pip", "pip3" -> body.contains("cache purge")
            "npm", "yarn" -> body.contains("cache clean")
            else -> false
        }
    }

    /**
     * A live guest plus one long stall is already the failure. A single short
     * stutter is not: markdown can hitch for a few seconds without a guest
     * being the cause. Two counted hangs, or one stall of 8s or more, kills.
     */
    fun shouldKillLiveWork(hangCount: Int, durationMs: Long): Boolean =
        hangCount >= 2 || durationMs >= 8_000L

    fun exceedsProcessCap(ownedPids: Int): Boolean = ownedPids > PROCESS_LIMIT

    /**
     * [T-memory-poison-guard] Sandbox-side quota gate for the memory bind.
     *
     * The daily-entry quota in MemoryRepository only guards the memory_write
     * TOOL path — but `/var/minis/memory` is bind-mounted read-write, so the
     * same runaway loop can reach the identical file through the shell. This
     * check refuses direct shell WRITE syntax against the memory path once
     * the day's quota is spent, closing the cheap bypasses (echo >>, tee,
     * rm/truncate of the log, sed -i).
     *
     * Honest limits: reads stay available (the agent must still see why), and
     * writes from inside an interpreter (`python -c ...`) are not statically
     * recognizable — the tool path plus these direct-syntax refusals is the
     * practical boundary, not a sandbox invariant.
     *
     * Pure: the caller resolves today's entry count.
     */
    fun memoryQuotaRefusal(command: String, dailyEntryCount: Int, maxDailyEntries: Int): String? {
        if (dailyEntryCount < maxDailyEntries) return null
        if (!command.contains("/var/minis/memory")) return null
        if (!hasMemoryWriteSyntax(command)) return null
        return "命令未启动（exit 126）：今日记忆日志已达到 $maxDailyEntries 条上限。" +
            "memory_write 与对 /var/minis/memory 的直接写入都已暂停；" +
            "旧的按日归档，明日自动开始新日志。"
    }

    private fun hasMemoryWriteSyntax(command: String): Boolean {
        val stripped = command
            .replace(Regex(""""[^"]*"|'[^']*'"""), " ")
            // [T-devnull-redirect] same as hasWriteSyntax: a discarded
            // stream is not a write to the memory bind. Reads must stay
            // available at quota — including `cat … 2>/dev/null`.
            .replace(Regex("""\s*\d?>>?\s*/dev/null\b"""), " ")
        if (Regex("""(^|[^>])>>?(?![>&])""").containsMatchIn(stripped)) return true
        val writeWords = setOf("tee", "rm", "mv", "truncate", "dd", "sed", "perl", "shred", "unlink")
        return stripped.split(Regex("""\s+""")).any { raw ->
            raw.substringAfterLast('/').lowercase() in writeWords
        }
    }

    fun isObviouslyReadOnly(command: String): Boolean {
        if (command.isBlank()) return true
        if (hasWriteSyntax(command)) return false
        val segments = splitCommandSegments(command)
        if (segments.isEmpty()) return true
        return segments.all { segment ->
            val token = commandToken(segment) ?: return@all false
            when (token) {
                "git" -> isReadOnlyGit(segment)
                else -> token in READ_ONLY_TOKENS
            }
        }
    }

    /**
     * [T-quote-aware-split] Split a compound command into segments WITHOUT
     * letting quoted spans create phantom segments. `grep -n "a\|b" file`
     * contains a `|` inside quotes: the old raw split cut it into `grep -n "a\`
     * and `b" file`, whose garbage leading token failed the read-only
     * whitelist — so a plainly read-only pipeline classified as heavy work,
     * escalated to BATCH and armed a 20-minute wall clock (the field report:
     * every `cd repo && grep …` hung the full 1200s). Quoted spans are
     * blanked first, exactly like [hasWriteSyntax] already does; only the
     * leading token of each segment is inspected, so losing the quoted text
     * costs nothing.
     */
    internal fun splitCommandSegments(command: String): List<String> {
        val stripped = command.replace(Regex(""""[^"]*"|'[^']*'"""), " ")
        return stripped.split(Regex("""&&|\|\||[;|]""")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The ceiling may reduce log sampling. It must not drop the counter:
     * a gap that is only "too long to be a hang" is exactly the freeze the
     * remediation loop has to see.
     */
    @Suppress("UNUSED_PARAMETER")
    fun countsHang(gapMs: Long, ceilingMs: Long, workloadLive: Boolean): Boolean = true

    fun shouldSampleHang(gapMs: Long, ceilingMs: Long, workloadLive: Boolean): Boolean =
        workloadLive || gapMs <= ceilingMs

    /**
     * Host `su` and unscoped walks must not inherit "allow this tool".
     *
     * Only `su` belongs here. A broad `find` is slow, not irreversible, so
     * it is not a permission question — it is handed to the resource brakes
     * (wall clock, output rate, the 8 MiB resident window) and refused only
     * when the disk is already stressed, via [diskRefusal].
     */
    fun requiresFreshConfirm(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("android-su") || c.contains("su -c") ||
            Regex("""(?:^|[;&|`(\n])\s*(?:\S*/)?su(?:\s|$)""").containsMatchIn(c)
    }

    /**
     * Block-device writes. Refused in EVERY permission mode: a rewritten
     * partition table or a `dd` onto a `/dev/block` device bricks it, and no
     * confirmation dialog can undo that.
     *
     * [T-rm-root-false-positive] `mkfs` is matched at the PROGRAM position, not
     * as a substring and not as any token: `contains("mkfs")` fired on commands
     * that merely mentioned the word, and scanning every argv slot would refuse
     * `man mkfs` for having it as an argument. [riskUnits] peels wrappers first,
     * so `sudo mkfs.ext4 …` is still caught.
     *
     * [T-blockdev-redirect-space] The redirect arm was `contains(">/dev/block")`
     * / `contains(">/dev/mmc")`, which only matched a *glued* redirect.
     * `cat img > /dev/block/mmcblk0` — the same write with the conventional
     * space — walked straight through. It is a regex now, and also covers `>>`,
     * an fd prefix (`2>`) and the other device families.
     *
     * The `of=` arm stays a substring check: its spelling does not vary, and
     * what it matches is a literal device path, not an ordinary word.
     */
    fun blockDeviceRefusal(command: String): String? {
        val c = command.lowercase().replace(Regex("\\s+"), " ")
        val formatsDevice = com.openminis.app.security.riskUnits(command).any { unit ->
            val head = com.openminis.app.security.tokenizeCommand(unit)
                .firstOrNull()
                ?.substringAfterLast('/')
            head == "mkfs" || (head != null && head.startsWith("mkfs."))
        }
        val writesDeviceNode = Regex("""\d?>>?\s*/dev/(?:block|mmc|sd[a-z]|nvme|disk|mapper)""")
            .containsMatchIn(c)
        return if (formatsDevice || c.contains("of=/dev/") || writesDeviceNode) {
            "命令未启动（exit 126）：禁止在宿主上操作块设备。"
        } else {
            null
        }
    }

    /**
     * `rm` wiping the filesystem root, judged on argv — see
     * [com.openminis.app.security.rmTargetsRoot].
     *
     * [T-rm-root-false-positive] The old `contains("rm -rf /")` /
     * `contains("rm -fr /")` pair matched EVERY absolute path, because they all
     * begin with `/`. Routine cleanups such as `rm -rf /sdcard/Download/tmp`,
     * `rm -rf /tmp/x; echo done` and `rm -rf /var/log/app && ls` were therefore
     * hard-denied with exit 126 ("禁止删除根目录") in every mode, YOYO included.
     * The only workaround was to disguise the command — `cd` into the directory
     * and use relative paths — which trained both the user and the agent to
     * route around the gate instead of trusting it. The sibling regex
     * (`\brm\s+-[a-z]*f[a-z]*\s+/\s*$`) was already correct but required the
     * target to end the command line, so the substring arm did all the damage.
     */
    fun rootDeletionRefusal(command: String): String? =
        if (com.openminis.app.security.rmTargetsRoot(command)) {
            "命令未启动（exit 126）：禁止删除根目录。"
        } else {
            null
        }

    /**
     * Irreversible host damage that no confirmation makes acceptable, because
     * the user has no surface to undo it.
     *
     * An unscoped walk is deliberately NOT here: slowness has its own brakes,
     * and refusing it outright made a read-only command unavailable.
     *
     * [T-yoyo-root-deletion] [allowRootDeletion] drops ONLY the root-deletion
     * arm, and only the guest executor passes it, only under YOYO. YOYO's
     * contract is "full-auto except fatal-confirm", and SecurityGateImpl now
     * turns this same command into a mustPrompt confirmation — so refusing it
     * again at the executor made the approval a dead end: the user tapped
     * through and the command still died with exit 126. Two hard limits keep
     * this from being a blanket bypass:
     *
     *  - block-device writes ([blockDeviceRefusal]) are refused in every mode;
     *    a brashed device cannot be confirmed back to life.
     *  - host `su` never passes true. There the root being deleted is the
     *    phone's, not the sandbox's, and the blast radius is unrecoverable.
     *
     * Under YOYO in the guest, `rm -rf /` destroys a rebuildable rootfs; that
     * is the one case the user has explicitly pre-authorised by picking the
     * mode and then tapping the confirmation.
     */
    fun hostRefusal(command: String, allowRootDeletion: Boolean = false): String? =
        blockDeviceRefusal(command)
            ?: if (allowRootDeletion) null else rootDeletionRefusal(command)

    // [T-devnull-redirect] `du` is the one tool that finds what to clean
    // when the disk IS the constraint — it was missing while `df` was
    // present. The rest are pure reporters with no write mode, added for
    // the same reason: they diagnose a stressed system without growing it.
    // [T-cd-readonly] `cd`/`pushd`/`popd` are pure navigation builtins: they
    // write nothing to disk, yet their absence made every
    // `cd repo && grep …` fail the read-only check → isHeavy → forced BATCH
    // → the 1200s wall clock the field report hit on every repo command.
    private val READ_ONLY_TOKENS = setOf(
        "ls", "cat", "head", "tail", "pwd", "echo", "printf", "df", "du", "stat", "file",
        "wc", "true", "false", "whoami", "id", "uname", "date", "env", "printenv",
        "which", "type", "basename", "dirname", "readlink", "realpath", "test", "[",
        "sha256sum", "sha1sum", "md5sum", "cmp", "diff", "cut", "tr", "sort", "uniq",
        "nl", "od", "hexdump", "fold", "grep", "egrep", "fgrep", "tree",
        "free", "lsblk", "findmnt", "vmstat", "iostat", "uptime", "ps", "nproc",
        "arch", "lscpu", "lsof", "groups", "hostname", "tty",
        "cd", "pushd", "popd",
    )

    fun clampHostTimeout(requestedMs: Long): Long =
        requestedMs.coerceIn(1L, HOST_SU_MAX_TIMEOUT_MS)

    fun findPathArgs(command: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("""(?:^|[;&|`(\n])\s*(?:\S*/)?find\b""")
        var from = 0
        while (true) {
            val m = re.find(command, from) ?: break
            val rest = command.substring(m.range.last + 1).trim()
            for (raw in rest.split(Regex("\\s+"))) {
                if (raw.isEmpty()) continue
                if (raw == "|" || raw == "||" || raw == "&&" || raw == ";") break
                if (raw.startsWith("-")) break
                out += raw.trim('"', '\'', '`')
            }
            from = m.range.last + 1
        }
        return out
    }

    private fun hasWriteSyntax(command: String): Boolean {
        // [T-devnull-redirect] `2>/dev/null` — and `>/dev/null`, `>>/dev/null`,
        // `n>>/dev/null`, spaced or glued — DISCARDS a stream; it writes
        // nothing. The old check treated any `>` as a write, so the most
        // common noise-suppression idiom turned a read-only pipeline into a
        // "write" and the disk gate refused it. The field report was
        // `du -xh --max-depth=2 / 2>/dev/null | sort -rh | head -30`.
        val stripped = command
            .replace(Regex(""""[^"]*"|'[^']*'"""), " ")
            .replace(Regex("""\s*\d?>>?\s*/dev/null\b"""), " ")
        if (Regex("""\btee\b""").containsMatchIn(stripped)) return true
        return Regex("""(^|[^>])>>?(?![>&])""").containsMatchIn(stripped)
    }

    private fun commandToken(segment: String): String? {
        for (raw in segment.trim().split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            if (!raw.startsWith("-") && raw.contains("=")) continue
            return raw.substringAfterLast('/').lowercase()
        }
        return null
    }

    private fun isReadOnlyGit(segment: String): Boolean {
        val verb = segment.trim().split(Regex("\\s+")).dropWhile { it.contains("=") }
            .drop(1).firstOrNull()?.lowercase() ?: return false
        return verb in setOf("status", "log", "diff", "show", "rev-parse", "blame", "ls-files", "describe")
    }

    private fun isBroadRoot(path: String): Boolean {
        val p = path.trimEnd('/').ifEmpty { "/" }
        return p == "/" || p == "/data" || p.startsWith("/data/") ||
            p == "/home" || p == "/root" || p == "/var" || p == "/sys" || p == "/proc"
    }
}
