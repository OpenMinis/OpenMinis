package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interactive PTY shell session — mirrors iOS ISHKernel + TerminalSession.
 *
 * Uses [PtyBridge.forkExec] to spawn `proot /bin/sh -l -i` attached to a real
 * PTY (via bionic forkpty). The shell sees `isatty(0)==true`, emits its PS1
 * prompt, honours termios echo/canonical/ISIG modes, and responds to signals,
 * arrow keys, Tab completion, and interactive programs (vi, top, gh auth login)
 * identically to iSH on iOS.
 *
 * Output is delivered as raw bytes through [outputBytes]; a TerminalEmulator
 * should consume these and parse ANSI sequences.
 */
class TerminalSession(private val context: Context) {

    companion object {
        private const val TAG = "TerminalSession"
        private const val READ_BUFFER_SIZE = 8192
        /** Max bytes per PTY write; larger pastes are chunked so nothing is dropped. */
        private const val CHUNK_SIZE = 2048
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24

        /**
         * Weak-reference registry of live sessions so the Application can push
         * environment updates (e.g. ACTION_TIMEZONE_CHANGED) into already-running
         * interactive shells. Stale weak refs are cleaned on each iteration.
         */
        private val liveSessions = CopyOnWriteArrayList<WeakReference<TerminalSession>>()

        /** Broadcast `export TZ=<value>` to every live interactive shell. */
        fun broadcastTimezone(tz: String) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyTimezone(tz)
            }
            liveSessions.removeAll(dead)
        }

        /**
         * Broadcast the HTTP-proxy env block to every live interactive shell.
         * Sends `export KEY=value` for each pair — empty values clear a
         * previously-set proxy in one round trip.
         */
        fun broadcastProxy(env: Map<String, String>) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyEnvMap(env)
            }
            liveSessions.removeAll(dead)
        }
    }

    enum class State { IDLE, BOOTING, RUNNING, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Raw PTY output bytes (not sanitized — feed directly into TerminalEmulator). */
    private val _outputBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val outputBytes: SharedFlow<ByteArray> = _outputBytes.asSharedFlow()

    /** Counter bumped on every reset() / clearOutput() — lets UI wipe emulator state. */
    private val _clearVersion = MutableStateFlow(0)
    val clearVersion: StateFlow<Int> = _clearVersion.asStateFlow()

    private var masterFd: Int = -1
    private var childPid: Int = 0
    private var readerJob: Job? = null

    /**
     * [T-android-seccomp-selfheal / GH#186] Set once the interactive shell has
     * been restarted with PROOT_NO_SECCOMP=1; also the one-shot guard that
     * stops a crash-restart loop if the retry dies the same way.
     */
    @Volatile
    private var useNoSeccomp = false

    /**
     * Whether this PTY ever emitted output. A terminal that printed something
     * has begun a real session, so a later crash is the guest program's, not
     * the loader's — and restarting would wipe visible scrollback.
     */
    @Volatile
    private var sawOutput = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cols: Int = DEFAULT_COLS
    @Volatile private var rows: Int = DEFAULT_ROWS

    val isRunning: Boolean get() = _state.value == State.RUNNING

    /**
     * Start the PTY-backed shell. Call [setWindowSize] first if you know the
     * target geometry — otherwise boots at 80×24 and expects a resize callback.
     */
    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        if (_state.value == State.RUNNING) return
        _state.value = State.BOOTING
        cols = initialCols
        rows = initialRows
        // Fresh per PTY incarnation, so a retry is judged on its own output
        // rather than inheriting the dead attempt's.
        sawOutput = false

        scope.launch {
            try {
                PRootKernel.boot(context)

                if (sessionId != null) {
                    ExecutionCoordinator.envVarRepository?.allAsDict()?.let { envVars ->
                        PRootKernel.customEnvironment.putAll(envVars)
                    }
                }

                val cmdList = buildInteractiveCommand(sessionId)
                val cmd = cmdList.first()
                val argv = cmdList.toTypedArray()

                // Environment in KEY=VALUE form
                val envMap = LinkedHashMap<String, String>()
                envMap["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
                if (PRootKernel.nativeLibDir.isNotEmpty())
                    envMap["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
                if (PRootKernel.prootLoaderPath.isNotEmpty())
                    envMap["PROOT_LOADER"] = PRootKernel.prootLoaderPath
                if (PRootKernel.prootLoader32Path.isNotEmpty())
                    envMap["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
                envMap["TERM"] = "xterm-256color"
                envMap["LANG"] = "C.UTF-8"
                envMap["LC_ALL"] = "C.UTF-8"
                // Timezone: PRootKernel.boot() already seeded customEnvironment["TZ"],
                // but set a fresh value here too in case the kernel was booted earlier
                // and the system timezone changed since.
                envMap["TZ"] = PRootKernel.posixTz()
                for ((k, v) in PRootKernel.customEnvironment) envMap[k] = v
                ExecutionCoordinator.envVarRepository?.allAsDict()?.forEach { (k, v) -> envMap[k] = v }

                // [T-android-seccomp-selfheal / GH#186] Applied last so nothing
                // above can clobber it once this device is known to need it.
                if (useNoSeccomp) {
                    envMap[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
                }

                val envp = envMap.map { (k, v) -> "$k=$v" }.toTypedArray()
                val cwd = File(cmd).parentFile?.absolutePath

                val outPid = IntArray(1)
                val fd = PtyBridge.forkExec(cmd, argv, envp, cwd, cols, rows, outPid)
                if (fd < 0) {
                    Log.e(TAG, "forkExec failed: errno=${-fd}")
                    _outputBytes.emit("Failed to start PTY: errno=${-fd}\r\n".toByteArray())
                    _state.value = State.STOPPED
                    return@launch
                }
                masterFd = fd
                childPid = outPid[0]
                _state.value = State.RUNNING
                liveSessions.add(WeakReference(this@TerminalSession))
                Log.i(TAG, "PTY started: fd=$fd pid=$childPid cols=$cols rows=$rows")

                if (sessionId != null) {
                    kotlinx.coroutines.delay(300)
                    writeInput("cd /var/minis && clear\n".toByteArray())
                }
                // [T-android-session-private-mounts] Say which files this
                // terminal sees. Written to the screen, not typed into the
                // shell, so it never lands in history. Delayed past the
                // `clear` above so it is not wiped.
                scope.launch {
                    kotlinx.coroutines.delay(if (sessionId != null) 600 else 300)
                    _outputBytes.emit(banner(sessionId).toByteArray())
                }

                readerJob = scope.launch { readLoop() }

                // Wait for child exit in a sibling coroutine.
                val spawnedAt = System.currentTimeMillis()
                scope.launch {
                    val status = PtyBridge.waitFor(childPid)
                    Log.i(TAG, "Child exited: status=$status")

                    // [T-android-seccomp-selfheal / GH#186] PtyBridge reports a
                    // signal death as -(128+signal) (pty_bridge.c:234), so
                    // negate before consulting the policy. If the interactive
                    // shell died on an early fatal signal, restart it once with
                    // PROOT_NO_SECCOMP=1 rather than leaving the user staring
                    // at a dead terminal they cannot fix from the UI.
                    val aliveMs = System.currentTimeMillis() - spawnedAt
                    if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                            exitCode = if (status < 0) -status else null,
                            durationMs = aliveMs,
                            producedOutput = sawOutput,
                            alreadyRetried = useNoSeccomp,
                        )
                    ) {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            SeccompFallbackPolicy.retryLogLine(-status, aliveMs, "interactive terminal"),
                        )
                        useNoSeccomp = true
                        masterFd = -1
                        childPid = 0
                        readerJob?.cancel()
                        readerJob = null
                        start(sessionId, cols, rows)
                        return@launch
                    }

                    _outputBytes.emit("\r\n[Process exited: $status]\r\n".toByteArray())
                    _state.value = State.STOPPED
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start PTY session", t)
                _outputBytes.emit("Error: ${t.message}\r\n".toByteArray())
                _state.value = State.STOPPED
            }
        }
    }

    private suspend fun readLoop() {
        val fd = masterFd
        val buf = ByteArray(READ_BUFFER_SIZE)
        while (_state.value == State.RUNNING) {
            val n = PtyBridge.readBytes(fd, buf, 0, buf.size)
            if (n <= 0) {
                // EOF or error → child exited / fd closed
                if (n < 0) Log.d(TAG, "readBytes returned errno=${-n}")
                break
            }
            sawOutput = true
            _outputBytes.emit(buf.copyOf(n))
        }
    }

    /** Send raw bytes to PTY master. Suitable for keystrokes, control codes, etc. */
    fun sendRawBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || masterFd < 0) return
        scope.launch { writeInput(bytes) }
    }

    /**
     * Send a UTF-8 string to the PTY, mirroring iOS TerminalInputView.insertText
     * semantics while covering the cases that iOS handles implicitly through UIKit:
     *
     *  - Unicode (CJK, emoji, combining chars): UTF-8 encoded verbatim.
     *  - Line endings: `\r\n` and bare `\n` both collapse to `\r`. A TTY expects
     *    CR for Enter; the kernel's ICRNL termios flag translates CR → NL on the
     *    shell's read side, so the shell sees a newline as usual.
     *  - Large pastes: chunked into ≤2048-byte writes and yielded between chunks
     *    so the PTY's input buffer has time to drain and bytes aren't dropped.
     *
     * Ctrl sequences, arrow keys, and function keys come through TerminalInputView
     * (hardware keys / accessory bar) as already-translated byte arrays via
     * [sendRawBytes]. They deliberately do NOT go through [sendText] — that
     * mirrors the iOS split where insertText handles printable text and
     * pressesBegan handles function/arrow keys.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val normalized = normalizeLineEndings(text)
        sendRawBytes(normalized.toByteArray(Charsets.UTF_8))
    }

    /** Collapse CRLF / LF to CR so a real TTY treats them as Enter. */
    private fun normalizeLineEndings(text: String): String {
        // Fast path: no newlines to rewrite.
        if ('\n' !in text && '\r' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when (c) {
                '\r' -> {
                    sb.append('\r')
                    // Swallow a following \n so \r\n becomes a single \r.
                    if (i + 1 < n && text[i + 1] == '\n') i++
                }
                '\n' -> sb.append('\r')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Legacy API — same as [sendText] but appends a newline. Kept for the deprecated line-input UI. */
    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) {
        // Use CR, not LF — a real TTY treats CR as Enter (termios ICRNL handles the rest).
        sendRawBytes((normalizeLineEndings(text) + "\r").toByteArray(Charsets.UTF_8))
    }

    /** Send SIGINT (Ctrl+C handled by kernel thanks to termios ISIG). */
    fun sendInterrupt() {
        // With a real PTY, writing 0x03 to the master triggers SIGINT via
        // termios VINTR — which is the correct, portable path.
        sendRawBytes(byteArrayOf(0x03))
    }

    /** Resize PTY window — emits SIGWINCH so the shell redraws its prompt. */
    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        if (masterFd >= 0) {
            val rc = PtyBridge.setWindowSize(masterFd, newCols, newRows)
            if (rc < 0) Log.w(TAG, "setWindowSize failed: errno=${-rc}")
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        val fd = masterFd
        val pid = childPid
        masterFd = -1
        childPid = 0
        if (fd >= 0) PtyBridge.closeFd(fd)
        if (pid > 0) PtyBridge.sendSignal(pid, 15) // SIGTERM
        if (_state.value != State.STOPPED) _state.value = State.STOPPED
        liveSessions.removeAll { it.get() === this || it.get() == null }
    }

    /**
     * Push a new TZ value into the running interactive shell via stdin.
     * Uses CR as the line terminator (termios ICRNL maps it to NL for the shell).
     */
    private fun applyTimezone(tz: String) {
        if (masterFd < 0) return
        val line = "export TZ='${tz.replace("'", "'\\''")}'\r"
        sendRawBytes(line.toByteArray(Charsets.UTF_8))
    }

    /**
     * Push a block of env vars into the running interactive shell via stdin.
     * Each key is `export`ed on its own line with POSIX single-quote escaping.
     * Empty values are exported verbatim — this lets a proxy-unset transition
     * clear previously-set proxy vars in one round trip.
     */
    private fun applyEnvMap(env: Map<String, String>) {
        if (masterFd < 0 || env.isEmpty()) return
        val sb = StringBuilder()
        for ((k, v) in env) {
            val escaped = v.replace("'", "'\\''")
            sb.append("export ").append(k).append("='").append(escaped).append("'\r")
        }
        sendRawBytes(sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** Signal UI to wipe the emulator. No PTY bytes are written. */
    fun clearOutput() {
        _clearVersion.value = _clearVersion.value + 1
    }

    private suspend fun writeInput(bytes: ByteArray) {
        val fd = masterFd
        if (fd < 0) return
        // PTY master writes may return a short count at PIPE_BUF (~4096) boundaries
        // or under backpressure. Loop until all bytes are written; cap per-call size
        // to CHUNK so large pastes don't monopolize the PTY or block the caller.
        var off = 0
        while (off < bytes.size) {
            val take = minOf(CHUNK_SIZE, bytes.size - off)
            val rc = PtyBridge.writeBytes(fd, bytes, off, take)
            if (rc < 0) {
                Log.w(TAG, "writeBytes failed: errno=${-rc}")
                return
            }
            if (rc == 0) {
                // Shouldn't happen on a healthy fd; bail rather than spin.
                Log.w(TAG, "writeBytes returned 0 at off=$off size=${bytes.size}")
                return
            }
            off += rc
            // Yield after each chunk so the PTY reader side has time to drain.
            if (off < bytes.size) kotlinx.coroutines.yield()
        }
    }

    /**
     * One-line notice of what `/var/minis` means in this terminal. A chat's
     * terminal shares that chat's workspace with its agent; the global one
     * belongs to no chat, and saying so up front prevents "I saved it in the
     * terminal and the agent can't see it".
     */
    private fun banner(sessionId: String?): String =
        if (sessionId != null) {
            "\u001b[2m[Minis] /var/minis/{workspace,attachments,offloads,browser} are this chat's " +
                "(session ${sessionId.take(8)}) — the agent in that chat sees the same files.\u001b[0m\r\n"
        } else {
            "\u001b[2m[Minis] Global terminal — not tied to any chat. /var/minis/workspace here is NOT " +
                "any chat's workspace; open the terminal from a chat's menu to work in its files. " +
                "Shared: /var/minis/{shared,memory,skills}.\u001b[0m\r\n"
        }

    private fun buildInteractiveCommand(sessionId: String?): List<String> {
        check(PRootKernel.isBooted) { "PRootKernel must be booted" }
        val rootfsManager = RootfsManager.getInstance(context)
        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        cmd.add("-r"); cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // [T-android-session-private-mounts] The same mount set the agent's
        // shell for [sessionId] gets (SessionMounts is the single builder), so
        // a file saved here is the file the agent sees. This used to replay
        // the global PRootKernel.bindMounts, whose per-session entries were
        // whichever chat last built a shell — a random chat's workspace.
        // No session (global entry): global dirs and external mounts only.
        cmd.addAll(SessionMounts.toProotArgs(SessionMounts.forContext(context, sessionId).mounts))

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        // [T-android-fake-netlink-tty-parity] Same rtnetlink emulation the
        // agent's shell gets — see PRootKernel.buildProotCommand, which is the
        // authoritative copy of this rationale.
        //
        // c9318f42f added `--fake-netlink` to that builder only, so the two
        // ways into the sandbox disagreed: a tool run by the agent saw an
        // (empty) interface list, while the SAME tool typed into this terminal
        // hit the real AF_NETLINK refusal from Android's untrusted_app SELinux
        // domain. A sandbox that behaves differently depending on which screen
        // you launched from is a debugging trap regardless of which behaviour
        // is the better one, so the flag is mirrored here.
        //
        // The ⚠️ on the original still stands, and now covers this call site
        // too: an empty interface list is a deliberate lie that unblocks
        // callers which only need enumeration to succeed, at the cost of
        // moving the failure later for callers that then bind a local address.
        // Whatever exposure decision that flag gets, both builders take it
        // together.
        cmd.add("--fake-netlink")

        // Login + interactive so /etc/profile is sourced (readline, history, color aliases).
        cmd.add("/bin/sh")
        cmd.add("-l")
        cmd.add("-i")
        return cmd
    }
}
