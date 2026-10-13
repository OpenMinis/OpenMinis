package com.openminis.app.sandbox

/**
 * Choose a shell-execution timeout based on the **command prefix**, matching
 * the tiered strategy iOS uses in `ISHExecutionCoordinator`. Agent calls tend
 * to cluster around a few command families with wildly different expected
 * durations:
 *
 *   - quick shell utilities (`ls`, `cat`, `grep`, `sed`, `awk`) finish in
 *     under a second and should fail fast if they don't
 *   - package installs (`apt-get install`, `pip install`, `npm install`) legitimately
 *     take 2–5 minutes and should not hit a tight timeout
 *   - build / compile commands (`make`, `cargo build`, `go build`) can run 10+
 *     minutes on non-trivial projects
 *   - long-running services (daemons, watch mode) are either cancelled by the
 *     user or preempted by the coordinator's wait-queue
 *
 * The numbers live here. [com.openminis.app.sandbox.kernel.BudgetClassifier]
 * is the only reader the live path uses. [effectiveMs] ignores the requested
 * timeout: `maxOf(requested, minimumMs)` is how a 10-minute default outlived
 * the table. [minimumMs] remains the named-setup floor and is not a general
 * class other tasks can enter.
 */
object ShellTimeoutPolicy {
    /** Default (baseline) timeout matching the existing call-site default. */
    const val DEFAULT_TIMEOUT_MS = 600_000L      // 10 min

    /** Quick tier — shell builtins, pure text utilities, metadata queries. */
    const val QUICK_TIMEOUT_MS = 60_000L         // 1 min

    /** Interactive / networked queries (ping, dig, curl one-shots). */
    const val NETWORK_TIMEOUT_MS = 180_000L      // 3 min

    /** Package installs — apk / pip / npm / gem / cargo install. */
    const val INSTALL_TIMEOUT_MS = 600_000L      // 10 min

    /** Build / compile — make, cargo build, go build, gradle, ninja. */
    const val BUILD_TIMEOUT_MS = 1_200_000L      // 20 min

    /** Long-running services / daemons — always allowed the full budget. */
    const val LONG_RUNNING_TIMEOUT_MS = 1_800_000L // 30 min

    /** Table read by BudgetClassifier. Do not arm these from a caller timeout. */
    const val INTERACTIVE_WALL_MS = 60_000L
    const val NORMAL_WALL_MS = 600_000L
    const val BATCH_WALL_MS = 1_200_000L
    const val SERVICE_WALL_MS = LONG_RUNNING_TIMEOUT_MS
    const val SETUP_WALL_MS = LONG_RUNNING_TIMEOUT_MS
    const val SERVICE_RATE_BPS = 512L * 1024L
    const val SETUP_RATE_BPS = 512L * 1024L
    const val SETUP_FSIZE_BYTES = 8L * 1024L * 1024L * 1024L

    /**
     * Floor for commands that outlive the default shell cap. Zero means
     * "do not raise". Never used to shorten a caller-supplied timeout.
     */
    fun minimumMs(command: String): Long {
        val lower = command.lowercase()
        return when {
            "minis-dev-setup-full" in lower || "minis-build-env" in lower -> LONG_RUNNING_TIMEOUT_MS
            "minis-android-sdk-setup" in lower -> LONG_RUNNING_TIMEOUT_MS
            "minis-self-build" in lower -> LONG_RUNNING_TIMEOUT_MS
            else -> 0L
        }
    }

    /**
     * Armed wall clock. [requestedMs] may SHORTEN the class wall, never
     * extend it ([T-timeout-honored]): the class ceiling stays the authority
     * — the old `maxOf(requested, minimumMs)` path is how a 10-minute
     * default outlived the table — while a caller that asks for 60s gets
     * 60s instead of riding the full 20-minute BATCH wall. The live callers
     * ([com.openminis.app.sandbox.ExecutionCoordinator.execute],
     * [com.openminis.app.sandbox.ShellExecutor.execute],
     * [com.openminis.app.sandbox.PersistentShell.executeCommand]) apply the
     * same min() themselves.
     */
    fun effectiveMs(command: String, requestedMs: Long): Long =
        com.openminis.app.sandbox.kernel.BudgetClassifier.armMs(command, requestedMs)

    /** Same wall [effectiveMs] returns. Not a second table. */
    fun ceilingMs(command: String): Long =
        com.openminis.app.sandbox.kernel.BudgetClassifier.classify(command).wallMs

    /** Same value [ceilingMs] returns. Dead as a caller; do not revive it. */
    fun forCommand(command: String): Long = ceilingMs(command)
}
