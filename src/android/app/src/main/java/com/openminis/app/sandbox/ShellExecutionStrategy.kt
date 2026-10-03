package com.openminis.app.sandbox

/**
 * [T-android-shell-fresh-process] Which mechanism runs a `shell_execute`.
 *
 * ## Why this switch exists
 *
 * Command completion has always been detected by an IN-BAND marker: the host
 * appends `echo "__MINIS_DONE_<uuid>_EXIT_$?__"` to every command and the
 * reader scans stdout for it. That was not a shortcut — with a REUSED shell,
 * `java.lang.Process` offers no other option. `waitFor()` waits for the shell
 * itself, stdout is an undelimited byte stream, and `$?` lives only inside the
 * guest. Telling the host "this command ended, with this code" had to travel
 * through the one channel that existed.
 *
 * In-band signalling then failed the way in-band signalling always does, three
 * times in five months:
 *
 *  - a marker split across two `read()` calls was never matched, so the
 *    command hung until its timeout;
 *  - the fix for that (a hold-back buffer) swallowed every output shorter than
 *    the window — measured on a Pixel 6, EVERY command returned empty;
 *  - a timed-out command kept writing into the shared stdout, so its bytes
 *    were attributed to whichever command ran next (Issue #358).
 *
 * Each patch introduced the next edge case, because the defect is structural:
 * control and data share a channel that the command itself can write to.
 *
 * ## Why a fresh process is the way out, and why it is affordable
 *
 * With one process per command, `Process.waitFor()` returns the exit code
 * directly. There is no marker to split, no buffer to swallow bytes, no shared
 * stream to pollute, and nothing for a command to forge. The whole class of
 * bug stops being expressible.
 *
 * The original objection was cold-start cost. Measured on a Pixel 6, inside
 * this project's own PRoot Alpine guest, 20 runs each:
 *
 *     sh -c "echo hi"          4.2 ms
 *     sh -c "true"             5.7 ms
 *     setsid sh -c "echo hi"   8.5 ms
 *     FIFO control round-trip  24.3 ms   (the rejected out-of-band option)
 *
 * Against a per-tool-call host overhead of 65-90 ms, 4.2 ms is under 6%. A
 * `python3` interpreter start alone costs 68 ms. The premise that warm-shell
 * reuse pays for its complexity does not survive measurement.
 *
 * Two alternatives were tested on device and rejected:
 *
 *  - **FD 3 passthrough** — PRoot's ptrace layer does not carry fd 3 across
 *    `execve`. Both `pass_fds` and an explicit `dup2` fail with
 *    `/bin/sh: dup2(3,1): Bad file descriptor`.
 *  - **FIFO control pipe** — works in isolation, but a background process
 *    inheriting the write end holds the reader open forever. Reproduced twice:
 *    the read blocked and never returned. That is Issue #358's failure mode
 *    moved to a new channel, and 5.8x slower besides.
 *
 * ## Rollout
 *
 * The migration is staged so each step is verifiable on its own, and
 * [WARM_SHELL] stays available as a fallback for a release cycle. Only when
 * fresh-process has run as the default without incident do the marker,
 * carry-over buffer and single-slot callback come out.
 */
internal enum class ShellExecutionStrategy {
    /**
     * The long-lived pooled shell with in-band `__MINIS_DONE__` markers.
     * Retained as the rollback path; carries every defect described above.
     */
    WARM_SHELL,

    /**
     * One `/bin/sh -c` per command, completion read from the process exit
     * status. Matches iOS, which has always worked this way
     * (`ISHExecutionCoordinator`).
     */
    FRESH_PROCESS,

    ;

    companion object {
        /**
         * Step 5: [FRESH_PROCESS] is now what users get.
         *
         * Steps 1-4 built the path, matched its sandbox configuration to the
         * warm shell flag for flag, and made a timeout kill the whole guest
         * process tree. Verified on a Pixel 6: a timed-out command returns 124
         * on schedule, the next command in the same session runs in 0.12s
         * (Issue #358's wedge cannot occur — there is no shared stdin to queue
         * behind), the tree is fully reaped, and deliberately detached work
         * survives.
         *
         * [WARM_SHELL] stays reachable for a release cycle as the rollback,
         * and the marker/carry-over machinery it needs stays with it. Deleting
         * that in the same change that flips the default would leave no way
         * back if the new path misbehaves on a device we cannot test.
         */
        val DEFAULT: ShellExecutionStrategy = FRESH_PROCESS

        /**
         * Whether a strategy needs the in-band completion marker.
         *
         * The marker exists only because a reused shell cannot report a
         * command boundary any other way. A fresh process gets the exit code
         * from `waitFor()`, so appending a marker would be pure risk — one
         * more thing to split, swallow or forge.
         */
        fun needsInBandMarker(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL

        /**
         * Whether a timeout has to recycle the shell afterwards.
         *
         * WARM_SHELL must: the runaway keeps writing into a stdout that later
         * commands read, so even a successful kill leaves bytes that would be
         * misattributed. A fresh process owns its pipes exclusively — when it
         * dies, they die, and the next command starts from nothing.
         */
        fun recyclesShellOnTimeout(strategy: ShellExecutionStrategy): Boolean =
            strategy == WARM_SHELL
    }
}
