package com.openminis.app.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import com.openminis.app.tools.ToolConcurrency
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Host-side endpoint of the proot `native_offload` extension.
 *
 * The guest issues `execve("<handler-name>", argv, envp)`; the proot
 * extension sends argv/env/cwd over an abstract unix socket to this
 * server. The server dispatches to the registered [NativeOffloadHandler],
 * writes the handler's combined output into a tmpfile inside the guest's
 * /tmp, and replies with `(exit_code, guest_tmpfile_path)`. The extension
 * then rewrites the execve into `/bin/cat <tmpfile>` so the guest sees
 * the handler output as plain stdout.
 *
 * Mirrors iOS `native_offload_add_handler` + `native_offload_exec`.
 */
data class NativeOffloadRequest(
    val pid: Int,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    /**
     * T340: chat session id forwarded by the agent shell via the
     * `MINIS_CHAT_SESSION_ID` env var. Lets [OffloadPermissionManager]
     * scope ASK_ONCE grants/denials per-chat-session instead of using
     * a single process-wide bucket. Null when the offload originates
     * outside a chat (e.g. interactive terminal) — handlers fall back
     * to `OFFLOAD_GLOBAL_SESSION_ID` in that case.
     */
    val sessionId: String? = null,
)

data class NativeOffloadResult(
    val exitCode: Int,
    val output: String,
)

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult

    /**
     * CFG-1: handlers with a suspending confirmation gate override this so the
     * watchdog can cancel the wait. Default preserves every existing handler.
     */
    suspend fun handleAsync(request: NativeOffloadRequest): NativeOffloadResult = handle(request)
}

object NativeOffloadServer {
    private const val TAG = "NativeOffloadServer"
    // Distinct from official OpenMinis ("native-offload") so both apps can
    // bind an abstract LocalServerSocket on the same device.
    private const val SOCKET_NAME = "native-offload-linux"
    private const val MAGIC_REQ = 0x46464F4E  // 'N' 'O' 'F' 'F' little-endian
    private const val MAGIC_RSP = 0x52464F4E  // 'N' 'O' 'F' 'R'
    private const val VERSION = 1

    /** [T-android-offload-tmp-leak] Filename prefix of a handler reply file. */
    private const val REPLY_PREFIX = ".native-offload-"

    /**
     * How long a reply file may live before the sweep may remove it.
     *
     * The real gap between writing the file and the rewritten `/bin/cat` reading
     * it is sub-millisecond, so even 10 seconds is enormously conservative — it
     * exists only so a stopped/slow tracee can never lose its output.
     *
     * It used to be 10 MINUTES, which defeated the sweep for the very case it
     * was written for: re-checked minutes after the calls ran, build 8 still
     * held 34 files (3 → 34 within a single session), because the guest tmp
     * directory accumulates faster than a 10-minute age gate can retire it.
     */
    private const val REPLY_TTL_MS = 10_000L

    /** Run the opportunistic sweep every N replies, not on every single one. */
    private const val SWEEP_EVERY_N_REPLIES = 8L

    /**
     * [T-android-offload-watchdog] Hard ceiling on how long a single
     * handler may run before the server gives up on it and replies on its
     * behalf.
     *
     * WHY THIS EXISTS. Handlers are synchronous and several of them bridge
     * to the UI with `runBlocking` while waiting for the user to answer a
     * permission prompt (system dialog 120s, then an in-app settings gate
     * another 120s). When the app has no foreground UI the prompt is never
     * answered, so the handler sat in `runBlocking` for ~4 minutes while the
     * guest sat in `read()` on the socket. The caller's `timeout 25` cannot
     * reach into this process: it kills the guest tracee, the app's handler
     * thread keeps waiting, and every retry leaks another pair of threads —
     * the observed "N calls hang forever, process count 6 → 20" failure.
     *
     * With this watchdog a stuck handler can no longer wedge a caller: the
     * server always replies, and it replies with a structured body + a
     * distinct exit code so the agent can tell "the tool is broken" apart
     * from "a human still has to answer a prompt".
     *
     * 20s is chosen to be comfortably above the slowest legitimate handler
     * (a contacts/provider query or a media scan) and well below the
     * shortest sane guest-side `timeout` so callers get a real answer.
     */
    private const val HANDLER_TIMEOUT_MS = 20_000L

    const val socketName: String = SOCKET_NAME

    private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()
    private val counter = AtomicLong(0)
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var rootfsTmpDir: File? = null

    val registeredHandlers: Set<String> get() = handlers.keys.toSet()

    fun register(name: String, handler: NativeOffloadHandler) {
        require(name.isNotEmpty())
        handlers[name] = handler
        Log.d(TAG, "register '$name' (total=${handlers.size})")
    }

    @Synchronized
    fun start(rootfsDir: File) {
        rootfsTmpDir = File(rootfsDir, "tmp")
        if (serverSocket != null) return

        // T287-followup: bind with bounded retry. Linux abstract sockets are
        // freed by the kernel only after the owning process is fully reaped —
        // when the previous app process dies (debug crash button, OOM kill,
        // ANR-kill) and Android's ActivityManager respawns us within ~100ms,
        // the kernel may still hold our prior namespace entry and bindLocal
        // returns EADDRINUSE. Crashing onCreate here puts the app in a
        // restart loop forever (re-spawn → EADDRINUSE → ACRA caught → die →
        // re-spawn …). Retry up to ~2s with exponential backoff; in the
        // overwhelming majority of cases the socket frees within the first
        // 100-300ms window.
        val s = bindWithRetry()
            ?: throw java.io.IOException(
                "failed to bind abstract socket '$SOCKET_NAME' after retries — " +
                "previous process holding the namespace?",
            )
        serverSocket = s
        acceptThread = thread(name = "native-offload-accept", isDaemon = true) {
            runAcceptLoop(s)
        }
        Log.i(TAG, "listening on abstract socket '$SOCKET_NAME' " +
            "handlers=${handlers.keys.sorted()} tmpDir=${rootfsTmpDir?.absolutePath}")

        // [T-android-offload-tmp-leak] Sweep reply files orphaned by earlier
        // app processes. See sweepStaleReplies for why this is safe here and
        // why the mechanism leaks in the first place.
        sweepStaleReplies(all = true)
    }

    /**
     * [T-android-offload-tmp-leak] Delete `.native-offload-*` reply files.
     *
     * WHY THESE LEAK. Each offload call writes the handler's combined output to
     * `<rootfs>/tmp/.native-offload-<pid>-<seq>` and returns the GUEST path;
     * proot's native_offload extension then rewrites the tracee's execve into
     * `/bin/cat <tmpfile>`. So the host cannot delete the file at reply time —
     * `cat` has not run yet, and deleting it would turn every offload call into
     * "No such file or directory". Nothing else ever removed them either
     * (`delete`/`cleanup` were zero occurrences in this file), so one file
     * accumulated per offload call, forever: measured on a dev device, 35 files
     * spanning 12 days and surviving many app restarts, growing +1 per call.
     * On a heavy user's device this reached thousands of files / GBs, showing
     * up as an inflated "Shell container" figure on the storage screen.
     *
     * WHY DELETING IS SAFE HERE.
     *  - [all] = true is used at server start. Any file present then belongs to
     *    a PREVIOUS app process: its tracee died with that process, so no `cat`
     *    can still be pending on it.
     *  - [all] = false keeps files younger than [REPLY_TTL_MS] and is used
     *    opportunistically while serving. The TTL is what makes it safe: it only
     *    ever removes files far older than the microseconds between writing the
     *    reply and the rewritten `cat` reading it. A pathologically stopped
     *    tracee (SIGSTOP between execve-enter and the read) is the sole way to
     *    exceed it, and that already means the caller is not reading its output.
     *
     * Failures are logged and swallowed: a leaked temp file must never break an
     * offload call.
     */
    private fun sweepStaleReplies(all: Boolean) {
        val dir = rootfsTmpDir ?: return
        try {
            val cutoff = System.currentTimeMillis() - REPLY_TTL_MS
            var removed = 0
            var bytes = 0L
            dir.listFiles { f -> f.isFile && f.name.startsWith(REPLY_PREFIX) }?.forEach { f ->
                if (!all && f.lastModified() > cutoff) return@forEach
                val len = f.length()
                if (f.delete()) {
                    removed++
                    bytes += len
                }
            }
            if (removed > 0) {
                Log.i(TAG, "swept $removed stale reply file(s), freed ${bytes / 1024}KB (all=$all)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sweepStaleReplies failed: ${e.message}")
        }
    }

    private fun bindWithRetry(): LocalServerSocket? {
        // Backoff schedule: 0, 50, 100, 200, 400, 800 ms — total ~1.55s.
        val delays = longArrayOf(0L, 50L, 100L, 200L, 400L, 800L)
        for ((attempt, delay) in delays.withIndex()) {
            if (delay > 0) Thread.sleep(delay)
            try {
                return LocalServerSocket(SOCKET_NAME)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "bind attempt ${attempt + 1}/${delays.size} failed: ${e.message}")
            }
        }
        return null
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    private fun runAcceptLoop(s: LocalServerSocket) {
        while (true) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                Log.i(TAG, "accept loop terminated: ${e.message}")
                return
            }
            Log.d(TAG, "accepted client from proot extension")
            thread(name = "native-offload-worker", isDaemon = true) {
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    Log.w(TAG, "worker error: ${e.message}", e)
                } finally {
                    try { client.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        // SEC-1: the abstract socket has no filesystem permissions and is
        // reachable by any process that can connect to the namespace. The
        // proot guest runs as this app's UID, so peer credentials are the
        // boundary: reject every other UID before reading the request.
        val peerUid = try {
            client.peerCredentials.uid
        } catch (t: Throwable) {
            Log.w(TAG, "peer credentials unavailable — refusing client: ${t.message}")
            return
        }
        if (peerUid != Process.myUid()) {
            Log.w(TAG, "rejected native_offload client uid=$peerUid (expected ${Process.myUid()})")
            return
        }
        val input = DataInputStream(client.inputStream)
        val output = DataOutputStream(client.outputStream)

        val magic = input.readLEInt()
        if (magic != MAGIC_REQ) {
            Log.w(TAG, "bad magic: 0x${magic.toUInt().toString(16)}")
            return
        }
        val version = input.readLEInt()
        if (version != VERSION) {
            Log.w(TAG, "unsupported version $version")
            return
        }

        val pid = input.readLEInt()
        val argc = input.readLEInt()
        if (argc < 0 || argc > 256) throw IllegalStateException("bad argc=$argc")
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(input.readLEString()) }

        val envc = input.readLEInt()
        if (envc < 0 || envc > 4096) throw IllegalStateException("bad envc=$envc")
        val env = LinkedHashMap<String, String>(envc)
        repeat(envc) {
            val s = input.readLEString()
            val eq = s.indexOf('=')
            if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
        }
        val cwd = input.readLEString()

        val name = argv.firstOrNull().orEmpty().substringAfterLast('/')
        // S-14: argv may carry credentials (`curl -H Authorization: …`).
        // Name + count are enough to diagnose routing; never the full vector.
        Log.d(TAG, "recv pid=$pid name='$name' argc=$argc cwdHash=${cwd.hashCode()} envc=$envc")

        val t0 = System.nanoTime()
        val handler = handlers[name]
        val result = if (handler == null) {
            Log.w(TAG, "no handler registered for '$name' (known=${handlers.keys})")
            NativeOffloadResult(exitCode = 127, output = "native_offload: no handler for '$name'\n")
        } else {
            runHandlerWithWatchdog(
                name = name,
                handler = handler,
                request = NativeOffloadRequest(
                    pid = pid,
                    argv = argv,
                    env = env,
                    cwd = cwd,
                    sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        val tmpDir = rootfsTmpDir ?: throw IllegalStateException("server not started")
        tmpDir.mkdirs()
        val seq = counter.incrementAndGet()
        val tmpHost = File(tmpDir, "$REPLY_PREFIX$pid-$seq")
        // [T-android-offload-silent-exit] A non-zero exit with no output leaves
        // the caller with literally nothing to show — observed once as
        // `offloaded 'android-contacts' -> exit=77` with 0 bytes on both
        // streams, which is indistinguishable from a crash and cost the agent a
        // whole diagnostic detour. Never let a failure be mute: synthesize a
        // body naming the tool and the code whenever a handler returns none.
        // Named `body`, not `output`: `output` is the DataOutputStream below,
        // and shadowing it here made every reply write resolve against a String.
        val body = if (result.exitCode == 0 || result.output.isNotBlank()) {
            result.output
        } else {
            Log.w(TAG, "handler '$name' returned exit=${result.exitCode} with no output — synthesizing body")
            org.json.JSONObject()
                .put("error", "silent_failure")
                .put("tool", name)
                .put("exit_code", result.exitCode)
                .put("message", "The host handler failed without producing any output.")
                .toString() + "\n"
        }
        // proot rewrites the intercepted execve into `cat` of this file, so the
        // guest's $? is cat's 0 unless the shell wrapper sees the real code.
        // The wrapper strips this first line before the caller sees the body.
        tmpHost.writeText("__MINIS_OFFLOAD_RC=${result.exitCode}__\n$body")
        val tmpGuest = "/tmp/${tmpHost.name}"

        // [T-android-offload-tmp-leak] Bound growth WITHIN a long-running
        // process. The start-time sweep only catches files from previous
        // processes, but a single session can issue thousands of offload calls
        // (agent loops shelling out repeatedly), so without this the leak simply
        // moves from "across restarts" to "within one run". Age-gated, so the
        // file just written — and any other still awaiting its `cat` — is never
        // touched. Sampled rather than run per reply to keep the hot path cheap.
        if (seq % SWEEP_EVERY_N_REPLIES == 0L) sweepStaleReplies(all = false)

        Log.d(TAG, "reply name='$name' exit=${result.exitCode} outBytes=${body.length} " +
            "tmpGuest=$tmpGuest elapsed=${elapsedMs}ms")

        output.writeLEInt(MAGIC_RSP)
        output.writeLEInt(result.exitCode)
        output.writeLEString(tmpGuest)
        output.flush()
    }

    /**
     * Deadline must outlast the handler's own budget, or the guest gets a
     * false timeout and the real result is dropped.
     *
     * Fast tools stay at 20s. Browser actions run up to 90s, accessibility
     * prompts up to 60s, speech listens up to 120s, a fresh location fix up
     * to 30s, and video polls up to about 16 minutes. Permission prompts are
     * already capped inside the handler.
     */
    internal fun handlerDeadlineMs(name: String, argv: List<String>): Long {
        fun flagSec(flag: String, defaultSec: Int, capSec: Int): Int {
            val idx = argv.indexOf("--$flag")
            val raw = if (idx >= 0) argv.getOrNull(idx + 1)?.toIntOrNull() else null
            return (raw ?: defaultSec).coerceIn(1, capSec)
        }
        // The a11y watch sub-commands (event/notify/dialog watch) take their
        // wait in MILLISECONDS (--duration / --timeout), clamped to 120s inside
        // AccessibilityOffloadHandler — so the deadline reads ms, not flagSec.
        fun flagMs(flag: String, defaultMs: Long, capMs: Long): Long {
            val idx = argv.indexOf("--$flag")
            val raw = if (idx >= 0) argv.getOrNull(idx + 1)?.toLongOrNull() else null
            return (raw ?: defaultMs).coerceIn(1_000L, capMs)
        }
        return when (name) {
            "minis-browser-use" -> 100_000L
            // [T-a11y-deadline-duration] `android-a11y-cli event watch
            // --duration 90000` blocks up to `duration` AFTER a grant prompt of
            // up to AccessibilityRecoveryManager.PROMPT_TIMEOUT_MS. A fixed 75s
            // watchdog fired handler_timeout/exit 124 while the handler kept
            // running to 150s+, leaking an abandoned thread per call — the exact
            // "false timeout, real result dropped" the KDoc above forbids.
            // Derive the deadline from the request the way android-speech does,
            // and outlast prompt + watch + reply overhead.
            "android-a11y-cli" -> {
                val watchMs = if ("--duration" in argv) flagMs("duration", 30_000L, 120_000L)
                    else flagMs("timeout", 30_000L, 120_000L)
                com.openminis.app.accessibility.AccessibilityRecoveryManager.PROMPT_TIMEOUT_MS +
                    watchMs + 15_000L
            }
            "android-speech" -> {
                val listen = if ("--duration" in argv) flagSec("duration", 30, 120) else flagSec("timeout", 30, 120)
                15_000L + listen * 1_000L + 10_000L
            }
            "android-location" -> 15_000L + flagSec("timeout", 8, 30) * 1_000L + 8_000L
            "minis-model-use" -> 18L * 60L * 1000L
            else -> HANDLER_TIMEOUT_MS
        }
    }

    /**
     * [T-android-tool-pool-cap] Admit at most
     * [ToolConcurrency.MAX_CONCURRENT_OFFLOAD_HANDLERS] concurrent handlers.
     *
     * This server starts a fresh daemon thread per connection AND another per
     * handler deadline, with no ceiling: a guest script that fans out `minis-*`
     * calls (`for f in …; do minis-photos … & done`), or an agent turn that
     * emits several parallel `shell_execute` calls, put arbitrarily many
     * long-lived threads on the phone — each able to sit for up to 18 minutes
     * (`minis-model-use`). That is how a "stuck" turn becomes a device-wide
     * stall instead of one slow tool.
     *
     * The slot is released when the CALLER stops waiting, not when the handler
     * thread dies: a handler that ignores cancellation has already had its
     * connection answered by [handlerTimeoutResult], and holding a slot forever
     * for a wedged recognizer would permanently shrink the pool to three. The
     * admission cap therefore bounds concurrent handler EXECUTION, which is the
     * resource that matters; an abandoned thread only touches its result queue.
     *
     * Over the ceiling the guest gets a structured `host_busy` reply instead of
     * another thread, so its CLI exits cleanly and the model can retry.
     */
    private fun runHandlerWithWatchdog(
        name: String,
        handler: NativeOffloadHandler,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (!ToolConcurrency.tryAcquireOffloadSlot()) {
            return offloadBusyResult(name, request)
        }
        try {
            return runHandlerWithWatchdogLocked(name, handler, request)
        } finally {
            ToolConcurrency.releaseOffloadSlot()
        }
    }

    /**
     * [T-android-offload-watchdog] Run [handler] with a hard deadline.
     *
     * The handler runs on its own daemon thread and hands its result back
     * through a one-slot queue; if nothing arrives within
     * [HANDLER_TIMEOUT_MS] the server replies with a structured
     * `handler_timeout` body instead of leaving the client blocked in
     * `read()` forever. The abandoned thread keeps running (Java cannot
     * safely kill it) but it no longer owns the connection: it only ever
     * touches the queue, so its late result is dropped harmlessly.
     *
     * [runHandlerWithWatchdog] gates entry to this method.
     */
    private fun runHandlerWithWatchdogLocked(
        name: String,
        handler: NativeOffloadHandler,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        val timeoutMs = handlerDeadlineMs(name, request.argv)
        val slot = ArrayBlockingQueue<NativeOffloadResult>(1)
        thread(name = "native-offload-handler", isDaemon = true) {
            val result = try {
                // The coroutine deadline is what lets a suspending confirmation
                // gate observe cancellation instead of sitting in runBlocking.
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(timeoutMs) {
                        handler.handleAsync(request)
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                handlerTimeoutResult(name, timeoutMs, request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                handlerTimeoutResult(name, timeoutMs, request)
            } catch (e: Exception) {
                Log.w(TAG, "handler '$name' threw: ${e.message}", e)
                NativeOffloadResult(exitCode = 1, output = "native_offload: ${e.message}\n")
            } catch (t: Throwable) {
                // A handler that dies on an Error (assertion, OOM-adjacent state)
                // must not take the connection with it.
                Log.w(TAG, "handler '$name' threw ${t.javaClass.simpleName}: ${t.message}", t)
                NativeOffloadResult(exitCode = 1, output = "native_offload: ${t.javaClass.simpleName}\n")
            }
            slot.offer(result)
        }
        // Grace on top of the coroutine deadline: a handler that ignores
        // cancellation (blocking IO) still must not hold the connection.
        return slot.poll(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
            ?: handlerTimeoutResult(name, timeoutMs, request)
    }

    /** Structured reply for a request that could not get a handler slot. */
    private fun offloadBusyResult(name: String, request: NativeOffloadRequest): NativeOffloadResult {
        val max = ToolConcurrency.MAX_CONCURRENT_OFFLOAD_HANDLERS
        Log.w(TAG, "handler '$name' refused: $max handlers already in flight (session=${request.sessionId})")
        return NativeOffloadResult(
            exitCode = 1,
            output = buildString {
                append("{\n")
                append("  \"error\": \"host_busy\",\n")
                append("  \"tool\": \"").append(name.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n")
                append("  \"max_concurrent\": ").append(max).append(",\n")
                append("  \"message\": \"The device is already running ").append(max)
                append(" long host handlers; this call waited ")
                append(ToolConcurrency.OFFLOAD_QUEUE_WAIT_MS / 1000)
                append("s for a free slot and gave up. Retry in a moment, or run fewer host tools at once.\"\n")
                append("}\n")
            },
        )
    }

    private fun handlerTimeoutResult(
        name: String,
        timeoutMs: Long,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        Log.w(
            TAG,
            "handler '$name' exceeded ${timeoutMs}ms — replying on its behalf " +
                "(argc=${request.argv.size}, session=${request.sessionId})",
        )
        return NativeOffloadResult(
            exitCode = 124,
            output = buildString {
                append("{\n")
                append("  \"error\": \"handler_timeout\",\n")
                append("  \"tool\": \"").append(name.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n")
                append("  \"timeout_ms\": ").append(timeoutMs).append(",\n")
                append("  \"message\": \"")
                append("The host handler did not finish within ${timeoutMs / 1000}s. ")
                append("This is a host-side hang, not a guest problem. ")
                append("If the tool needs a permission prompt, open the app, grant it, and retry.\"\n")
                append("}\n")
            },
        )
    }

    // ---- little-endian helpers ----

    private fun DataInputStream.readLEInt(): Int {
        val buf = ByteArray(4)
        readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun DataInputStream.readLEString(): String {
        val len = readLEInt()
        if (len < 0 || len > 1 shl 20) throw IllegalStateException("bad string len $len")
        if (len == 0) return ""
        val buf = ByteArray(len)
        readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeLEInt(v: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(buf)
    }

    private fun DataOutputStream.writeLEString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLEInt(bytes.size)
        if (bytes.isNotEmpty()) write(bytes)
    }
}
