package com.openminis.app.sandbox

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-session locks so parallel sub-agents / chats cannot deadlock the
 * shared PRoot guest (one gradle daemon, one SDK tree, one apt dpkg lock).
 */
object SandboxResourceGate {
    private val executionGate = AdaptiveExecutionGate()

    enum class ResourceClass { AUTO, HEAVY }

    fun isHeavy(command: String): Boolean {
        // Resource cleanup must remain available while a daemon holds memory.
        val trimmed = command.trim()
        if (Regex("""^(?:kill|pkill|killall)(?:\s|$)""").containsMatchIn(trimmed) ||
            Regex("""^(?:\S*/)?gradle(?:w)?\s+--stop\s*$""").matches(trimmed)) return false
        // Unknown binaries are heavy. A keyword denylist misses the next bomb.
        if (GuestWorkloadPolicy.isObviouslyReadOnly(command)) return false
        return true
    }

    /**
     * Shared with [RootfsManager] so boot-time `minis-mirror` / dpkg-world
     * restore cannot race an agent `apt-get` / `minis-dev-setup` on the guest
     * dpkg lock files.
     */
    val aptMutex = Mutex()
    private val named = ConcurrentHashMap<String, Mutex>()

    /** How long a new apt command waits for [aptMutex]. The install itself is not capped. */
    const val APT_WAIT_MS = 5 * 60 * 1000L

    fun isApkBuild(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("gradlew") ||
            c.contains("gradle ") ||
            Regex("""(^|\s|\./)gradle(\s|$)""").containsMatchIn(c) ||
            c.contains("assembledebug") ||
            c.contains("assemblerelease") ||
            c.contains("bundleRelease".lowercase()) ||
            c.contains("aapt2") ||
            c.contains("bundletool")
    }

    fun isPackageManager(command: String): Boolean {
        val c = command.lowercase()
        // [T-setup-family] The minis-setup-* scripts shell out to apt/dpkg
        // themselves and take the in-guest `minis_acquire_apt_lock`; until now
        // the installer ran OUTSIDE this host apt mutex, so a concurrent
        // agent `apt-get` and a Dev Suite setup raced on the dpkg lock files.
        // Treat the family as package-manager work so it also serialises on
        // the host mutex.
        if (GuestWorkloadPolicy.isSetupScript(c)) return true
        return c.contains("apt-get") || c.contains("apt ") ||
            c.contains("dpkg") || c.contains("sdkmanager") ||
            c.contains("minis-dev-setup") || c.contains("minis-build-env") || c.contains("minis-android-sdk-setup") ||
            c.contains("minis-mirror")
    }

    // [T-apt-ro-exempt] Provably read-only apt/dpkg QUERY forms. The apt mutex
    // exists to protect the dpkg status database from concurrent WRITERS —
    // serialising `apt list --installed` / `apt-cache policy` / `dpkg -l`
    // behind a running install bought no correctness and cost every lookup a
    // queue wait. The whitelist is deliberately narrow: compound commands,
    // redirects/substitutions, and value-taking flags (-o APT::…= would
    // redirect the database paths) fall back to the lock. `apt-get download`
    // and `apt-cache gencaches` write (cwd files / cache rebuild) and stay
    // locked on purpose.
    private val READ_ONLY_APT_FORMS = listOf(
        Regex("""^(?:\S*/)?apt(?:-get)?\s+(?:--?[a-z][\w-]*\s+)*(?:list|show|search|policy|depends|rdepends|pkgnames|indextargets)\b.*"""),
        Regex("""^(?:\S*/)?apt-cache\s+(?:show|search|policy|depends|rdepends|pkgnames|stats|showpkg|showsrc|dump|dumpavail|unmet|check)\b.*"""),
        Regex("""^(?:\S*/)?dpkg\s+(?:-l|--list|-s|--status|-L|--listfiles|-S|--search|-C|--audit|-p|--print-architecture|--print-foreign-architectures)\b.*"""),
        Regex("""^(?:\S*/)?apt-get\s+(?:-s|--simulate|--dry-run|--just-print)\s+\S+.*"""),
    )

    /** True when [command] is an apt/dpkg query that cannot mutate package state. */
    fun isReadOnlyPackageManager(command: String): Boolean {
        val c = command.trim().lowercase()
        if (c.isEmpty()) return false
        if (c.contains(';') || c.contains('|') || c.contains('&') || c.contains('`') ||
            c.contains('\n') || c.contains(">(") || c.contains("$(") || c.contains('>') ||
            c.contains('<')
        ) return false
        return READ_ONLY_APT_FORMS.any { it.containsMatchIn(c) }
    }

    /**
     * Wrap a command with resource locks to serialize conflicting operations.
     *
     * Builds, package managers and other heavy tools share a global budget.
     * Package state also retains its existing dpkg lock. Lightweight commands
     * remain concurrent; opaque scripts can explicitly request HEAVY.
     *
     * The apt wait is capped at [aptWaitMs]. The command that already holds
     * the lock is not cancelled: `minis-dev-setup-full` is documented at
     * 10–20 minutes, and a timeout around [Mutex.withLock] both aborted that
     * install and dropped the host lock while the guest apt was still running.
     */
    suspend fun <T> withCommandLock(
        command: String,
        aptWaitMs: Long = com.openminis.app.data.ToolLimitPrefs.queueTimeoutSec() * 1000L,
        resourceClass: ResourceClass = ResourceClass.AUTO,
        pressure: suspend () -> String? = { null },
        onWaiting: (String) -> Unit = {},
        limits: () -> Pair<Int, Int> = {
            val total = com.openminis.app.data.ToolLimitPrefs.commandConcurrency()
            total to minOf(total, com.openminis.app.data.ToolLimitPrefs.heavyConcurrency())
        },
        block: suspend () -> T,
    ): T {
        val heavy = resourceClass == ResourceClass.HEAVY || isHeavy(command)
        // Light commands (including cleanup) do not consume heavy permits.
        // Their per-lane serialization remains in ExecutionCoordinator.
        if (!heavy) return block()
        val queuedAt = System.nanoTime()
        suspend fun admitted(): T = executionGate.run(
            heavy, if (aptWaitMs <= 0) 0 else
                (aptWaitMs - (System.nanoTime() - queuedAt) / 1_000_000).also {
                    check(it > 0) { "Execution queue timed out; command was not started" }
                },
            limits = { limits().let { AdaptiveExecutionGate.Limits(it.first, it.second) } },
            pressure = { if (heavy) pressure() else null },
            onWaiting = onWaiting,
            block = block,
        )
        return if (isPackageManager(command) && !isReadOnlyPackageManager(command)) {
            withAptLock(aptWaitMs, onWaiting) { admitted() }
        } else admitted()
    }

    private suspend fun <T> withAptLock(waitMs: Long, onWaiting: (String) -> Unit, block: suspend () -> T): T {
        val queuedAt = System.currentTimeMillis()
        if (aptMutex.isLocked) {
            // [T-aptqueue-copy] The old copy ("package manager is busy;
            // command not started") read as a REFUSAL — users reported
            // "老是提示 WAITING_RESOURCE" thinking commands were being
            // dropped, while the command is in fact queued and starts the
            // moment the holder finishes. Say that instead, and keep a
            // per-minute heartbeat so a long queue (boot mirror+seed can
            // hold the lock for many minutes) does not read as a hang.
            onWaiting("WAITING_RESOURCE: 包管理器忙，命令已排队，锁释放后自动开始")
        }
        if (!awaitAptLock(waitMs, queuedAt, onWaiting)) {
            throw RuntimeException(
                "Package manager queue timed out; command was not started.",
            )
        }
        try {
            return block()
        } finally {
            aptMutex.unlock()
        }
    }

    /** tryLock so a timeout cannot leave the mutex held or cancel the holder. */
    private suspend fun awaitAptLock(waitMs: Long, queuedAt: Long, onWaiting: (String) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + waitMs.coerceAtLeast(0L)
        // One beat per waited MINUTE: a per-second ticker would flood the
        // transcript, but total silence for 10+ minutes reads as a hang.
        var lastBeatMinute = 0L
        while (!aptMutex.tryLock()) {
            if (waitMs > 0 && System.currentTimeMillis() >= deadline) return false
            val minute = (System.currentTimeMillis() - queuedAt) / 60_000L
            if (minute > 0 && minute != lastBeatMinute) {
                lastBeatMinute = minute
                onWaiting("WAITING_RESOURCE: 包管理器仍被占用，已排队 $minute 分钟，继续等待…")
            }
            delay(50)
        }
        return true
    }

    suspend fun <T> withNamedLock(name: String, block: suspend () -> T): T {
        // computeIfAbsent (atomic), not getOrPut (get→compute→put, non-atomic):
        // the same guarantee ExecutionCoordinator relies on for its mutex map.
        val mutex = named.computeIfAbsent(name) { Mutex() }
        return mutex.withLock { block() }
    }
}
