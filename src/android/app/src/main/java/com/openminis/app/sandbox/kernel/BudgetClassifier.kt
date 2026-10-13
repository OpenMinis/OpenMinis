package com.openminis.app.sandbox.kernel

import com.openminis.app.sandbox.GuestWorkloadPolicy
import com.openminis.app.sandbox.SandboxResourceGate
import com.openminis.app.sandbox.ShellTimeoutPolicy

/**
 * The live classifier. [ShellTimeoutPolicy] owns the numbers; this object is
 * the only thing [com.openminis.app.sandbox.ExecutionCoordinator] and
 * [com.openminis.app.sandbox.ShellExecutor] arm. A requested timeout is not an
 * input — the old `maxOf(requested, minimumMs)` path is how a 10-minute default
 * outlived the table.
 *
 * SETUP is only the named setup scripts. A background `&` is SERVICE, not
 * SETUP, so a long install window cannot be claimed by an unrelated task.
 * `find` is NORMAL: a short special case does not stop a tree walk, the
 * output rate and the host deadline do.
 */
object BudgetClassifier {

    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    fun classify(
        command: String,
        resourceClass: SandboxResourceGate.ResourceClass = SandboxResourceGate.ResourceClass.AUTO,
    ): ProcessBudget {
        val text = command.trim()
        if (text.isEmpty()) return interactive()
        // Split consumes `&`, so a background job must be seen on the raw
        // command. Otherwise `sleep 100 &` becomes NORMAL and inherits a
        // CPU limit a daemon must not have.
        val background = isBackground(text)
        // [T-quote-aware-split] Blank quoted spans BEFORE splitting: a `|`
        // inside quotes (`grep -n "a\|b" file`) used to cut a phantom
        // segment whose garbage token classified as NORMAL, inflating the
        // wall clock for a quick utility. Only each segment's leading token
        // is inspected, so losing the quoted text costs nothing.
        val stripped = text.replace(Regex(""""[^"]*"|'[^']*'"""), " ")
        val segments = stripped.split(Regex("""&&|\|\||[;&|]|\$\(|`"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val detected = if (segments.isEmpty()) interactive()
            else segments.map { classifySegment(it) }.maxBy { rank(it.workClass) }
        val raised = if (background && rank(detected.workClass) < rank(WorkClass.SERVICE)) service() else detected
        if (resourceClass == SandboxResourceGate.ResourceClass.HEAVY &&
            rank(raised.workClass) < rank(WorkClass.BATCH)
        ) {
            return batch()
        }
        return raised
    }

    private fun isBackground(command: String): Boolean {
        val stripped = command
            .replace(Regex(""""[^"]*"|'[^']*'"""), " ")
            // `2>&1` and `&>file` are redirections, not background jobs.
            .replace(Regex("""\d*>&\d*|\d*<&\d*|&>>|&>"""), " ")
        if (Regex("""(?:^|[\s;&|`(\n])(?:nohup|setsid)(?:\s|$)""").containsMatchIn(stripped)) return true
        return Regex("""(?<![&])&(?![&])""").containsMatchIn(stripped)
    }

    /**
     * Armed wall clock. [requestedMs] may SHORTEN the class wall but never
     * extend it: the class ceiling stays the authority (the old
     * `maxOf(requested, minimumMs)` path is how a 10-minute default
     * outlived the table), while a caller that knows it wants 60s gets 60s
     * instead of riding the full 20-minute BATCH wall — the field report
     * was a `grep` hanging 1200s with a 60s request logged as "ignored".
     * [T-timeout-honored]
     */
    fun armMs(command: String, requestedMs: Long = 0L): Long =
        armMs(classify(command), command, requestedMs)

    /**
     * [T-timeout-honored] The effective wall for an ALREADY-classified
     * budget: the class ceiling capped by the caller's request, but never
     * below the setup floor — a `minis-dev-setup-full` killed by a short
     * request leaves a broken rootfs, which is exactly what
     * `setupFloorSurvivesAShortRequest` pins. The live execution paths
     * ([com.openminis.app.sandbox.ExecutionCoordinator],
     * [com.openminis.app.sandbox.PersistentShell],
     * [com.openminis.app.sandbox.ShellExecutor]) call this with the budget
     * they classified (including `resource_class`), so HEAVY escalation
     * and the floor compose in one place.
     */
    fun armMs(budget: ProcessBudget, command: String, requestedMs: Long): Long {
        val floor = com.openminis.app.sandbox.ShellTimeoutPolicy.minimumMs(command)
        if (requestedMs <= 0L) return maxOf(floor, budget.wallMs)
        return maxOf(floor, minOf(requestedMs, budget.wallMs))
    }

    private fun classifySegment(segment: String): ProcessBudget {
        if (GuestWorkloadPolicy.isSetup(segment)) return setup()
        if (isService(segment)) return service()
        if (GuestWorkloadPolicy.isBuild(segment) || GuestWorkloadPolicy.isInstall(segment)) return batch()
        if (isInteractive(segment)) return interactive()
        return normal()
    }

    private fun isService(segment: String): Boolean {
        val c = segment.trim()
        if (c.endsWith("&")) return true
        val token = c.substringBefore(' ').substringAfterLast('/')
        return token == "nohup" || token == "setsid"
    }

    private fun isInteractive(segment: String): Boolean {
        val token = segment.trim().substringBefore(' ').substringAfterLast('/')
        return token in INTERACTIVE_TOKENS
    }

    private fun rank(cls: WorkClass): Int = when (cls) {
        WorkClass.INTERACTIVE -> 0
        WorkClass.NORMAL -> 1
        WorkClass.BATCH -> 2
        WorkClass.SERVICE -> 3
        WorkClass.SETUP -> 4
    }

    fun interactive(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.INTERACTIVE,
        wallMs = ShellTimeoutPolicy.INTERACTIVE_WALL_MS,
        cpuSeconds = 30,
        fileSizeBytes = 256L * MIB,
        nproc = 64,
        outputCapBytes = 8L * MIB,
        outputRateBytesPerSec = 256L * 1024L,
    )

    fun normal(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.NORMAL,
        wallMs = ShellTimeoutPolicy.NORMAL_WALL_MS,
        cpuSeconds = 300,
        fileSizeBytes = GIB,
        nproc = 128,
        outputCapBytes = 32L * MIB,
        outputRateBytesPerSec = 2L * MIB,
    )

    fun batch(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.BATCH,
        wallMs = ShellTimeoutPolicy.BATCH_WALL_MS,
        cpuSeconds = 600,
        fileSizeBytes = 4L * GIB,
        // [T-nproc-alignment] 1024, not 4096: RLIMIT_NPROC counts per UID and
        // includes the app's own processes — a `make -j4096` at 4096 could
        // starve the host app itself (fork EAGAIN in the very watcher meant
        // to clean up). 1024 leaves Gradle/aapt2's 300-500 processes ample
        // headroom while keeping real margin below the UID ceiling, and sits
        // above GuestWorkloadPolicy.PROCESS_LIMIT (the earlier watchdog
        // tripwire) so the kill path still fires before the rlimit does.
        nproc = 1024,
        outputCapBytes = 64L * MIB,
        outputRateBytesPerSec = 8L * MIB,
    )

    /**
     * Ordinary long-running services. Output rate is 512 KiB/s
     * ([ShellTimeoutPolicy.SERVICE_RATE_BPS] — 61a2d77 raised it from the
     * original 64 KiB/s for build workloads; this KDoc trailed the constant
     * until it was resynced). A service that can write whole megabytes per
     * second is still the incident's resource type.
     */
    fun service(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.SERVICE,
        wallMs = ShellTimeoutPolicy.SERVICE_WALL_MS,
        cpuSeconds = 0,
        fileSizeBytes = 4L * GIB,
        nproc = 1024,
        outputCapBytes = 16L * MIB,
        outputRateBytesPerSec = ShellTimeoutPolicy.SERVICE_RATE_BPS,
    )

    /**
     * Named setup scripts only. Same wall as a service, but a separate class
     * so nothing else inherits the window. FSIZE is 8 GiB because an SDK zip
     * is one file; the output rate is the shared 512 KiB/s budget so the
     * install cannot flood the UI pipe.
     */
    fun setup(): ProcessBudget = ProcessBudget(
        workClass = WorkClass.SETUP,
        wallMs = ShellTimeoutPolicy.SETUP_WALL_MS,
        cpuSeconds = 0,
        fileSizeBytes = ShellTimeoutPolicy.SETUP_FSIZE_BYTES,
        nproc = 1024,
        outputCapBytes = 16L * MIB,
        outputRateBytesPerSec = ShellTimeoutPolicy.SETUP_RATE_BPS,
    )

    private val INTERACTIVE_TOKENS = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "rg", "egrep", "fgrep",
        "echo", "printf", "pwd", "which", "whoami", "id", "true", "false",
        "basename", "dirname", "stat", "df",
        // [T-cd-readonly] Pure navigation builtins: `cd repo && grep …` must
        // classify exactly like `grep …` alone — INTERACTIVE, fail fast.
        "cd", "pushd", "popd",
    )
}
