package com.openminis.app.sandbox

/**
 * [T-android-shell-timeout-pgid] How a foreground command is isolated so a
 * timeout can kill it — and only it.
 *
 * Issue #358: one command that outran its timeout wedged its whole session.
 * Every later `shell_execute` in that session timed out too, `echo alive`
 * included, while other sessions and the interactive Terminal stayed fine.
 *
 * Cause: the timeout path did exactly nothing to the runaway.
 *
 *     // Timeout — cancel pending, but don't kill the shell
 *     pendingCallback = null
 *
 * The command kept running inside the session's long-lived, REUSED shell,
 * still writing to stdout. `/bin/sh` reads stdin serially, so the next
 * command merely queued behind it and could not start until the runaway
 * finished — which is why even `echo alive` burned its full timeout.
 *
 * ## Why a process group, and not a string search for `&`
 *
 * The obvious-looking fix — "if the command contains `&`, treat it as a
 * background job and leave it alone" — is wrong in a way that is hard to see
 * and easy to ship. `&` is ordinary text in `curl 'a?x=1&y=2'`, in
 * `grep 'a & b'`, in `awk '{print $1 & $2}'`, and inside any heredoc. A
 * matcher cannot tell those from a real job-control `&` without parsing the
 * whole shell grammar, quoting and all. Getting it wrong either kills a
 * daemon the user deliberately started, or spares a runaway and leaves #358
 * unfixed.
 *
 * The kernel already tracks exactly this, so ask it instead. Each foreground
 * command runs under `setsid`, which puts it in a brand-new session and
 * process group whose pgid equals its pid. Verified in this project's own
 * Alpine guest (`/proc/<pid>/stat` field 5):
 *
 *     shell           pid=23997  pgid=946     <- the persistent shell
 *     setsid child    pid=24027  pgid=24027   <- its own group
 *     plain child     pid=24029  pgid=946     <- inherits the shell's
 *
 * Timeout then sends `kill -9 -<pgid>`, which reaches that command and its
 * descendants and nothing else.
 *
 * ## Protecting real background work
 *
 * A `&` job started inside the wrapper still inherits the wrapper's group —
 * measured directly: `bg_pgid=24041` against `fg_pgid=24041` — so a plain
 * `sleep 100 &` WOULD be swept up. That is the correct call: an unadorned `&`
 * job belongs to the command that spawned it, and the user asked for that
 * command to stop.
 *
 * Work that is meant to outlive the command says so, by detaching into its
 * own session with `setsid` or `nohup`. Measured on device, killing the
 * foreground group:
 *
 *     plain `… &` daemon   : frozen at 2 lines  (swept, as intended)
 *     `setsid …` daemon    : 6 -> 9 lines       (survived)
 *
 * So the rule the user gets is the standard POSIX one, enforced by the
 * kernel rather than by a regex over their command text.
 */
internal object ForegroundCommandGroup {

    /** Guest path to `setsid`, present in the shipped Alpine rootfs. */
    const val SETSID = "/usr/bin/setsid"

    /**
     * Wrap [command] so it runs in its own process group and reports the
     * group id before it starts.
     *
     * The wrapper prints `__MINIS_PGID_<marker>_<pgid>__` first, so the host
     * learns which group to kill without having to hunt for the pid. It is
     * emitted by the subshell itself — reading `/proc/self/stat` field 5 —
     * because busybox `ps` has no `-o pgid=`.
     *
     * `exec` replaces the subshell with the user's command where possible, so
     * there is no extra shell sitting between the group leader and the work.
     * The command still runs under a shell (not exec'd directly) because it
     * may be a pipeline, a list, or contain redirections.
     *
     * Falls back to running the command unwrapped when [setsidAvailable] is
     * false: an unkillable-but-working command beats a broken one.
     */
    fun wrap(command: String, marker: String, setsidAvailable: Boolean = true): String {
        if (!setsidAvailable) {
            return "$command\necho \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n"
        }
        // Single-quote the payload for the inner sh -c, escaping any quote in
        // it the POSIX way ('\'' closes, escapes, reopens). The command text
        // is never interpreted by the outer shell, so `&`, quotes and URLs
        // pass through untouched — the whole point of not parsing it.
        val quoted = command.replace("'", "'\\''")
        return buildString {
            append(SETSID)
            append(" sh -c 'echo \"__MINIS_PGID_")
            append(marker)
            append("_$(awk \"{print \\$5}\" /proc/self/stat)__\"; ")
            append(quoted)
            append("'\n")
            append("echo \"__MINIS_DONE_${marker}_EXIT_\$?__\"\n")
        }
    }

    /**
     * [T-android-shell-fresh-process] Step 4: the same process-group
     * isolation, for a path that has no marker protocol.
     *
     * The fresh-process path already learns completion from `waitFor()`, so it
     * needs none of [wrap]'s marker machinery — only the property that makes a
     * timeout killable: the command must lead its own process group.
     *
     * Why it is needed there at all. `Process.destroyForcibly()` SIGKILLs the
     * proot process it spawned, but not the guest descendants proot is
     * tracing. Measured on a Pixel 6: proot died on schedule at 5s
     * (`timeout after 5s, process killed` in logcat) while a `libproot.so`
     * child survived holding the pipe's write end, so the host's blocking
     * read never saw EOF and the call outlived its deadline. Killing the
     * GROUP reaches those descendants, the last writer closes, and the read
     * ends naturally.
     *
     * `exec` matters here: without it the wrapping `sh` would remain as an
     * extra process between the group leader and the work, and would be the
     * one to die while the real command kept the pipe open. With it the
     * command IS the group leader.
     *
     * [T-android-fresh-exit-status-file] The group leader is now a small
     * runner ([FRESH_RUNNER]) rather than the command itself, because process
     * exit stopped being a usable completion signal: a command that leaves a
     * detached daemon behind (every cold `minis-mcp-cli` call) keeps proot
     * alive — proot must keep tracing the daemon for its paths to work — and
     * proot's own stdout is the host pipe, so the read never reaches EOF. The
     * runner records its pid (= the group id, since setsid made it the leader)
     * in `<statusPath>.pid`, runs the command in a child shell, then writes
     * the command's exit status to `<statusPath>` and exits with it. The
     * status file is the completion signal; the pid file lets a timeout or
     * Stop kill exactly this command's group and nothing it deliberately
     * detached.
     *
     * The command and the path stay separate argv entries — `$1` and `$2` of
     * the runner — so nothing in either is ever re-parsed by a shell.
     */
    fun wrapForFreshProcess(command: String, statusPath: String): List<String> =
        listOf(SETSID, "/bin/sh", "-c", FRESH_RUNNER, "sh", command, statusPath)

    /**
     * [T-android-fresh-exit-status-file] See [wrapForFreshProcess]. `$1` is the
     * command, `$2` the status path. The inner `/bin/sh -c "$1"` means an
     * `exit N` in the command ends only the command, and the status is still
     * written; a command killed by a signal reports 128+N, as a shell would.
     */
    const val FRESH_RUNNER =
        "printf '%s' \"\$\$\" > \"\$2.pid\"; /bin/sh -c \"\$1\"; rc=\$?; printf '%s' \"\$rc\" > \"\$2\"; exit \$rc"

    /** Marker the wrapper prints so the host can learn the group id. */
    fun pgidMarkerPrefix(marker: String): String = "__MINIS_PGID_${marker}_"

    /**
     * Parse the pgid out of a chunk containing [pgidMarkerPrefix], or null.
     *
     * Returns null for a malformed or absent marker rather than guessing: a
     * wrong pgid would kill an unrelated group, so "don't know" must mean
     * "don't kill".
     */
    fun parsePgid(text: String, marker: String): Int? {
        val prefix = pgidMarkerPrefix(marker)
        val start = text.indexOf(prefix)
        if (start < 0) return null
        val from = start + prefix.length
        val end = text.indexOf("__", from)
        if (end < 0) return null
        val pgid = text.substring(from, end).trim().toIntOrNull() ?: return null
        // pgid 0 / 1 would mean "this whole process group" or init — never
        // send a kill there.
        return if (pgid > 1) pgid else null
    }

    /**
     * The guest-side kill for a timed-out foreground group.
     *
     * SIGTERM first so a well-behaved tree can flush and exit, then SIGKILL
     * for anything that ignored it — the escalation iOS already uses
     * (`ISHShellExecutor.killProcessGroup`). Negative pid targets the group.
     *
     * Runs detached via [SETSID] itself: the shell that must execute this is
     * the very shell whose foreground is wedged, so the kill has to come from
     * something the kill will not also destroy.
     */
    fun killGroupCommand(pgid: Int): String =
        "$SETSID sh -c 'kill -TERM -$pgid 2>/dev/null; sleep 1; kill -KILL -$pgid 2>/dev/null' >/dev/null 2>&1 &\n"

    /**
     * Whether a shell that just had a command timed out can keep being used.
     *
     * Always false. Even after the group is killed, the shell's stdout may
     * still hold bytes the runaway wrote before dying, and those would be
     * attributed to whichever command reads next. Recycling is the only way
     * to guarantee a clean channel; `ensureStarted()` replays the environment
     * snapshot, so nothing the user configured is lost.
     */
    fun shellIsReusableAfterTimeout(): Boolean = false
}
