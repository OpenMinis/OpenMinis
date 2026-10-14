package com.openminis.app.sandbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interactive PTY shell session backed by the Termux terminal emulator engine
 * (com.termux.terminal). Replaces the hand-rolled [PtyBridge] + TerminalEmulator
 * stack with industry-standard ANSI/CSI/OSC parsing, TUI compatibility, and
 * mature keyboard / text-selection handling.
 *
 * Output is delivered as raw bytes through [outputBytes]; consumers that still
 * feed a legacy TerminalEmulator can continue to use this same Flow. The new
 * Termux-backed TerminalScreen (Layer 4) reads directly from the Termux
 * TerminalView, which is driven by the [termuxSession] below.
 */
class TerminalSession(private val context: Context) {

    companion object {
        private const val TAG = "TerminalSession"
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

        /** Broadcast the HTTP-proxy env block to every live interactive shell. */
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

    /** Raw PTY output bytes — delta stream for legacy consumers not using Termux TerminalView. */
    private val _outputBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val outputBytes: SharedFlow<ByteArray> = _outputBytes.asSharedFlow()

    /** Counter bumped on every clearOutput() — lets UI wipe emulator state. */
    private val _clearVersion = MutableStateFlow(0)
    val clearVersion: StateFlow<Int> = _clearVersion.asStateFlow()

    // --- Termux engine internals ---

    /** Attached by [start]; used by [TerminalScreen] to wire its TerminalView. */
    internal var termuxSession: com.termux.terminal.TerminalSession? = null
        private set

    /** The TerminalView currently rendering this session (if any). */
    @Volatile
    private var attachedView: TerminalView? = null

    /** Marshals output-redraw callbacks from the emulator thread to the UI thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Track last transcript length so we can emit delta bytes on text changes. */
    @Volatile
    private var lastTranscriptLength: Int = 0

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var cols: Int = DEFAULT_COLS
    @Volatile private var rows: Int = DEFAULT_ROWS

    val isRunning: Boolean get() = _state.value == State.RUNNING

    // ──────────────────────────────────────────────
    //  Start / Stop
    // ──────────────────────────────────────────────

    /**
     * Start the PTY-backed shell via Termux engine.
     * Call [setWindowSize] first if you know the target geometry.
     *
     * Termux [com.termux.terminal.TerminalSession] creates the PTY internally
     * from the supplied executable path and arguments — we pass PRoot as the
     * executable with the same args the legacy [buildInteractiveCommand] built.
     */
    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        if (_state.value == State.RUNNING) return
        _state.value = State.BOOTING
        cols = initialCols
        rows = initialRows

        scope.launch {
            try {
                // [T-android-startup-splash-hang] The first terminal open used
                // to boot the PRoot kernel ON THE UI THREAD: refreshDns /
                // configureUbuntuGuest file writes, registerGlobalBindMounts()
                // .mkdirs(), installHandlerStubs() writes, NativeOffloadServer
                // .start()'s abstract-socket bind + Thread.sleep backoff (up to
                // ~1.55s) and its listFiles() sweep, and applyMountedFolders
                // Snapshot()'s ContentResolver queries + File.canExecute()
                // checks — all on Dispatchers.Main, the same ANR class the
                // SessionWorkspace warmup fix removed. Run the blocking boot body
                // (and this session's bind snapshot, itself mkdirs + resolve) on
                // Dispatchers.IO; only the Termux session construction below
                // needs Main (the scope default), so the PTY is still wired up
                // on the UI thread. Boot ordering and isBooted visibility are
                // unchanged — boot() is awaited in place and sets isBooted at its
                // own end, before this withContext returns.
                val launch = withContext(Dispatchers.IO) {
                    PRootKernel.boot(context)
                    val rootfsManager = RootfsManager.getInstance(context)
                    val proot = rootfsManager.prootBinary.absolutePath
                    val filesDir = context.filesDir.absolutePath
                    // Refresh this chat's binds before reading the process-wide
                    // map, or the terminal inherits whichever session booted last.
                    sessionId?.let { ExecutionCoordinator.sessionBindMounts(it) }
                    // Build PRoot arguments (mirrors legacy buildInteractiveCommand).
                    // Pass the proot binary path so it lands in argv[0] — critical
                    // for PRoot's option parsing (see buildTermuxArgs docs).
                    val args = buildTermuxArgs(sessionId, rootfsManager, proot)
                    // [T-session-env-not-global] Per-session env (API tokens from
                    // the Environments store) is merged into THIS terminal's envp
                    // by buildTermuxEnv; it is deliberately NOT pushed into
                    // PRootKernel.customEnvironment — that is the process-global
                    // map every later proot spawn reads, so putAll there leaked
                    // one session's secrets into every other session and the
                    // interactive terminal, and they were never cleared.
                    val env = buildTermuxEnv(rootfsManager)
                    TermuxLaunch(proot, filesDir, args, env)
                }

                val client = TermuxSessionClient()
                val session = com.termux.terminal.TerminalSession(
                    launch.proot,
                    launch.filesDir,
                    launch.args.toTypedArray(),
                    launch.env.toTypedArray(),
                    2_000, // scrollback rows — match RikkaHub default
                    client,
                )
                session.updateSize(cols, rows)

                termuxSession = session
                // [hud-truthful-sampler] Register the PRoot tracer PID so the
                // live CPU/MEM HUD attributes the terminal's workload too.
                                lastTranscriptLength = 0
                _state.value = State.RUNNING
                liveSessions.add(WeakReference(this@TerminalSession))
                Log.i(TAG, "Termux PTY started: cols=$cols rows=$rows sessionId=$sessionId")

                // If a session is active, cd into its workspace so the user lands
                // in a familiar directory (mirrors legacy behaviour).
                kotlinx.coroutines.delay(300)
                termuxShellPid(session)?.let {
                    SandboxWorkload.trackPid(it, "terminal:${sessionId ?: "anon"}")
                }
                val initCmd = (GuestLimits.interactivePrelude() + "cd /var/minis && clear\r").toByteArray()
                session.write(initCmd, 0, initCmd.size)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Termux PTY session", t)
                _outputBytes.emit("Error: ${t.message}\r\n".toByteArray())
                _state.value = State.STOPPED
            }
        }
    }

    fun stop() {
        scope.cancel()
        val s = termuxSession
        termuxSession = null
        attachedView = null
        if (s != null) {
            // [hud-truthful-sampler] Drop the tracer from the HUD registry.
                        killTermuxProcessTree(s)
        }
        if (_state.value != State.STOPPED) _state.value = State.STOPPED
        liveSessions.removeAll { it.get() === this || it.get() == null }
        Log.i(TAG, "TerminalSession stopped")
    }

    /** The PRoot pid backing the given Termux session, via reflection. */
    private fun termuxShellPid(s: com.termux.terminal.TerminalSession): Int? = try {
        val f = s.javaClass.getDeclaredField("mShellPid")
        f.isAccessible = true
        f.getInt(s).takeIf { it > 0 }
    } catch (_: Throwable) { null }

    /**
     * Enumerate the full descendant subtree of [pid] by walking the
     * task children entries under /proc. Returns children-first
     * (leaf) order.
     */
    private fun collectDescendants(pid: Int): List<Int> {
        val seen = mutableSetOf<Int>()
        val out = mutableListOf<Int>()
        fun walk(p: Int) {
            if (!seen.add(p)) return
            try {
                val taskDir = File("/proc/$p/task")
                val children = taskDir.listFiles()?.flatMap { tidDir ->
                    runCatching {
                        File(tidDir, "children").readText().trim()
                            .split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }
                    }.getOrElse { emptyList() }
                } ?: emptyList()
                for (c in children) { out.add(c); walk(c) }
            } catch (_: Throwable) { }
        }
        walk(pid)
        return out
    }

    /**
     * Kill the entire PRoot tree owned by this Termux session, not just the
     * tracer itself. [com.termux.terminal.TerminalSession.finishIfRunning]
     * only SIGKILLs mShellPid (the PRoot tracer); its children (bash, and
     * anything bash spawned) survive as orphans whose native memory still
     * counts against this app's process group. Rapid open/close cycles then
     * accumulate orphaned trees and exhaust the Scudo allocator
     * (SIGABRT "Can't populate more pages" — the OOM crashes the user is
     * seeing). Kill leaves first, then the root, and fall back to Termux's
     * own finish for the root's cleanup path.
     *
     * Three layered strategies (each is best-effort):
     *  1. /proc children walk — kills descendants individually (leaves
     *     first, so nobody gets reparented into a zombie gap).
     *  2. Explicit SIGKILL (Process.sendSignal) on the tracer root itself —
     *     redundant belt-and-braces in case the walk missed a reparented
     *     process.
     *  3. finishIfRunning() — Termux's own teardown (closes ByteQueues,
     *     JNI fds; wait-thread cleanupResources runs cleanly).
     */
    private fun killTermuxProcessTree(s: com.termux.terminal.TerminalSession) {
        val pid = termuxShellPid(s)
        if (pid != null) {
            SandboxWorkload.releasePid(pid, kill = true, reason = "terminal-stop")
            // (1) Descendant walk via /proc — best-effort.
            try {
                val tree = listOf(pid) + collectDescendants(pid)
                for (p in tree.asReversed()) {
                    runCatching { android.os.Process.killProcess(p) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "killTermuxProcessTree walk failed: ${t.message}")
            }
            // (2) Also signal-kill the tracer directly (Process.killProcess does the
            // same under the hood, but keep the explicit sendSignal as a
            // redundant belt-and-braces — e.g. if the walk in (1) missed a
            // reparented process, this still nails the root).
            runCatching {
                android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
            }
        }
        // (3) Termux's own teardown — closes the ByteQueues/JNI fds and
        // triggers the wait-thread cleanupResources for a clean close.
        runCatching { s.finishIfRunning() }
    }

    /**
     * Hand the Android TerminalView to this session so output callbacks
     * ([TermuxSessionClient.onTextChanged]) can redraw it. Called from
     * [TerminalScreen] both on first composition and whenever the view is
     * re-attached. If the PTY already booted, attach and size it immediately.
     */
    fun attachView(view: TerminalView) {
        attachedView = view
        val session = termuxSession
        if (session != null && view.mTermSession != session) {
            view.attachSession(session)
            view.updateSize()
        }
    }

    // ──────────────────────────────────────────────
    //  Input
    // ──────────────────────────────────────────────

    /** Send raw bytes to the PTY (keystrokes, control codes, etc.). */
    fun sendRawBytes(bytes: ByteArray) {
        val s = termuxSession ?: return
        if (bytes.isEmpty()) return
        s.write(bytes, 0, bytes.size)
    }

    /**
     * Send a UTF-8 string to the PTY.
     *
     * Unicode (CJK, emoji) is UTF-8 encoded verbatim. Line endings (`\r\n` and
     * bare `\n`) are collapsed to `\r` so the TTY's ICRNL termios flag maps
     * them to newline as usual.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val normalized = normalizeLineEndings(text)
        sendRawBytes(normalized.toByteArray(Charsets.UTF_8))
    }

    /** Legacy — same as [sendText] + CR. */
    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) {
        sendRawBytes((normalizeLineEndings(text) + "\r").toByteArray(Charsets.UTF_8))
    }

    /** Send SIGINT (Ctrl+C, 0x03). */
    fun sendInterrupt() {
        sendRawBytes(byteArrayOf(0x03))
    }

    // ──────────────────────────────────────────────
    //  Window size
    // ──────────────────────────────────────────────

    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        termuxSession?.updateSize(newCols, newRows)
    }

    // ──────────────────────────────────────────────
    //  Output control
    // ──────────────────────────────────────────────

    /** Signal UI to wipe the emulator. Sends Ctrl+L (form feed 0x0C) — the
     *  shell's native clear-screen shortcut — so the TTY redraws the prompt. */
    fun clearOutput() {
        _clearVersion.value = _clearVersion.value + 1
        // 0x0C = Ctrl+L. The old ESC c (RIS) got split by readline into
        // meta-prefix + literal "c", printing a stray "c" on the input line.
        sendRawBytes(byteArrayOf(0x0C))
    }

    // ──────────────────────────────────────────────
    //  Env broadcasts
    // ──────────────────────────────────────────────

    private fun applyTimezone(tz: String) {
        val line = "export TZ='${tz.replace("'", "'\\''")}'\r"
        sendRawBytes(line.toByteArray(Charsets.UTF_8))
    }

    private fun applyEnvMap(env: Map<String, String>) {
        if (env.isEmpty()) return
        val sb = StringBuilder()
        for ((k, v) in env) {
            val escaped = v.replace("'", "'\\''")
            sb.append("export ").append(k).append("='").append(escaped).append("'\r")
        }
        sendRawBytes(sb.toString().toByteArray(Charsets.UTF_8))
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun normalizeLineEndings(text: String): String {
        return internalNormalizeLineEndings(text)
    }

    /**
     * Build the PRoot argument list for Termux TerminalSession.
     *
     * IMPORTANT: argv[0] must be the program name (the proot binary itself).
     * Termux's JNI does `execvp(cmd, argv)` where `argv[0]` is argv[0] of the
     * exec'd program — PRoot's `parse_config` starts parsing at `argv[1]`.
     * If we omitted the proot path here (i.e. started the list directly with
     * "-0"), then "-0" would land in argv[0] and never be parsed, the fake-id
     * root would silently fail, and bash's PS1 `\u` would resolve to
     * "I have no name!" because the app uid (11576) isn't in /etc/passwd.
     * This mirrors upstream TermuxSession where `arguments[0] = processName`.
     */
    private fun buildTermuxArgs(
        sessionId: String?,
        rootfsManager: RootfsManager,
        proot: String,
    ): List<String> {
        val args = mutableListOf<String>()

        // argv[0] → program name. Do NOT reorder this before proot.
        args.add(proot)
        args.add("-0")
        args.add("--link2symlink")
        args.add("--kill-on-exit")
        args.add("-r"); args.add(rootfsManager.rootfsDir.absolutePath)
        args.add("-b"); args.add("/dev")
        args.add("-b"); args.add("/proc")
        args.add("-b"); args.add("/sys")
        args.add("-w"); args.add("/root")

        // Bind mounts — global + per-session overlay.
        // Keep OpenMinis-Linux bind mounts (PRootKernel.bindMounts already
        // includes /var/minis workspace). Do not overlay RikkaMinis minis-sessions/.
        for ((linuxPath, hostPath) in PRootKernel.bindMounts) {
            args.add("-b"); args.add("$hostPath:$linuxPath")
        }

        // Native offload socket.
        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            args.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        // Login + interactive shell.
        // Prefer bash (readline, Ctrl+C-at-prompt semantics), but fall back
        // to dash when bash or its dynamic deps are missing/corrupt.
        // A broken bash must never take the whole terminal down — dash's
        // plain prompt is a minor UX regression vs a terminal that can't
        // start. Note: Ubuntu resolves /bin/bash -> /usr/bin/bash via the
        // bin -> usr/bin symlink, so bin/bash still stats correctly.
        val bashFile = File(rootfsManager.rootfsDir, "bin/bash")
        val shell = if (bashFile.exists() && bashFile.canExecute()) {
            "/bin/bash"
        } else {
            Log.w(TAG, "/bin/bash unavailable — falling back to /bin/sh")
            "/bin/sh"
        }
        args.add(shell)
        args.add("-l")
        args.add("-i")
        return args
    }

    /** Build the environment array for Termux TerminalSession. */
    private fun buildTermuxEnv(rootfsManager: RootfsManager): List<String> {
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
        envMap["TZ"] = PRootKernel.guestTz()
        for ((k, v) in PRootKernel.customEnvironment) envMap[k] = v
        ExecutionCoordinator.envVarRepository?.allAsDict()?.forEach { (k, v) -> envMap[k] = v }
        return envMap.map { (k, v) -> "$k=$v" }
    }

    // ──────────────────────────────────────────────
    //  TermuxSessionClient — callbacks → outputBytes
    // ──────────────────────────────────────────────

    /**
     * The IO-prepared inputs for constructing a [com.termux.terminal.TerminalSession].
     * Computed on [kotlinx.coroutines.Dispatchers.IO] in [start]; consumed on Main.
     */
    private data class TermuxLaunch(
        val proot: String,
        val filesDir: String,
        val args: List<String>,
        val env: List<String>,
    )

    /**
     * Bridges Termux terminal events into the legacy [outputBytes] flow so
     * existing consumers (logging, the legacy TerminalEmulator path) keep
     * receiving output deltas.
     *
     * In the new TerminalScreen (Layer 4) the Termux TerminalView reads
     * directly from the [termuxSession]; this client exists only for
     * backward-compatible side channels.
     */
    private inner class TermuxSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {
            // CRITICAL: this callback is what drives the TerminalView to
            // redraw when the PTY emits bytes. Termux 0.118.0's own
            // TermuxTerminalSessionClient.onTextChanged() calls
            // terminalView.onScreenUpdated(). Without forwarding here the view
            // stays frozen (black, no cursor, no output) — exactly the
            // "can't operate the terminal" symptom.
            val view = attachedView
            if (view != null) {
                // onTextChanged fires on the emulator thread; redraw on UI.
                mainHandler.post {
                    if (attachedView == null || attachedView !== view) return@post
                    view.onScreenUpdated()
                    // Keep the PTY size in sync with the real view dimensions
                    // once the view is laid out (it starts at 80×24 defaults).
                    val emu = view.mEmulator
                    if (emu != null) {
                        val c = emu.mColumns
                        val r = emu.mRows
                        if (c != cols || r != rows) {
                            cols = c; rows = r
                            termuxSession?.updateSize(c, r)
                        }
                    }
                }
            }
        }
        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
        override fun onSessionFinished(
            changedSession: com.termux.terminal.TerminalSession,
        ) {
            Log.i(TAG, "Termux session finished (exit=${changedSession.exitStatus})")
            _state.value = State.STOPPED
        }
        override fun onBell(session: com.termux.terminal.TerminalSession) {}
        override fun onColorsChanged(changedSession: com.termux.terminal.TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun onPasteTextFromClipboard(
            session: com.termux.terminal.TerminalSession,
        ): Unit {} // returns Unit in Termux 0.118.0

        override fun onCopyTextToClipboard(
            session: com.termux.terminal.TerminalSession,
            text: String,
        ) {}

        override fun getTerminalCursorStyle(): Int? = null

        // ── JitPack 0.118.0 log callbacks (no-op — we use our own logging) ──
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: java.lang.Exception) {}
        override fun logStackTrace(tag: String, e: java.lang.Exception) {}
    }
}

/**
 * Normalize line endings for PTY output: converts `\n` to `\r`, collapses
 * `\r\n` to `\r`, and passes bare `\r` through unchanged. The TTY's ICRNL
 * termios flag will map the resulting `\r` to `\n` as usual.
 *
 * Extracted as a top-level function so it can be JVM-tested without loading
 * Android classes ([TerminalSession] itself imports `android.content.Context`).
 */
internal fun internalNormalizeLineEndings(text: String): String {
    if ('\n' !in text && '\r' !in text) return text
    val sb = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        when (val c = text[i]) {
            '\r' -> {
                sb.append('\r')
                if (i + 1 < n && text[i + 1] == '\n') i++
            }
            '\n' -> sb.append('\r')
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}
