package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpJsonRpc.McpError
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-mcp-native] STDIO transport: an MCP server process running inside the
 * PRoot guest, spoken to over line-delimited JSON-RPC on its stdin/stdout.
 * Mirrors the in-guest daemon's `MCPStdioServer` (daemon.py): direct exec of
 * `command + args` with the config `env` overlaid, `initialize` bounded by
 * the per-server startup timeout, stderr drained into a ring buffer for
 * diagnostics, one in-flight request at a time.
 *
 * The process is spawned through a caller-supplied proot command prefix
 * ([SpawnSpec]) so this class stays JVM-testable with a plain
 * `ProcessBuilder` — no Android types below the spec.
 */
class McpStdioClient(
    override val serverName: String,
    private val spawn: SpawnSpec,
    private val startupTimeoutMs: Long = 60_000L,
    private val callTimeoutMs: Long = 300_000L,
    private val hooks: ProcessHooks? = null,
) : McpClient {

    /**
     * [T-mcp-stdio-cap] Host-side lifecycle hooks for the spawned proot
     * process. Kept optional and Process-typed so the class stays
     * JVM-testable; the Android wiring ([McpNativeBridge]) registers the
     * process with [com.openminis.app.sandbox.SandboxWorkload] so the whole
     * guest tree is owned: background cgroup confinement, the global
     * process cap, and a tree-wide kill when this server stops — upstream
     * beta.2 "stopping one ends its whole process tree". Without the hook,
     * destroyForcibly() SIGKILLs only proot and the traced node/python
     * server survives as an orphan.
     */
    interface ProcessHooks {
        /** Called right after a successful spawn, before initialize. */
        fun onSpawned(process: Process)

        /**
         * Called exactly once per spawned process when it stops.
         * [graceful] = it exited after SIGTERM (proot --kill-on-exit already
         * tore the tracees down); false = it had to be SIGKILLed, the host
         * side must kill the surviving tree itself.
         */
        fun onTerminated(process: Process, graceful: Boolean)
    }

    /**
     * Everything Android-specific needed to launch the guest process:
     * the full argv prefix ending right before the server command
     * (proot + rootfs + bind mounts), plus the environment for the host-side
     * ProcessBuilder (PROOT_LOADER etc.) and the guest-visible env for the
     * server itself.
     */
    data class SpawnSpec(
        /** e.g. [proot, -0, --kill-on-exit, -r, rootfs, -b, ..., /bin/sh, -c, 'exec "$0" "$@"', cmd, args…] */
        val argv: List<String>,
        /** Host ProcessBuilder environment (PROOT_* vars). */
        val processEnv: Map<String, String>,
        /** Guest environment for the server process (PATH, HOME, config env…). */
        val guestEnv: Map<String, String>,
    )

    @Volatile private var process: Process? = null
    @Volatile private var initialized = false
    private var writer: java.io.BufferedWriter? = null
    private var stdout: BufferedReader? = null

    // [T-mcp-stdio-reply-loss] SynchronousQueue.offer() only succeeds when a
    // taker is ALREADY parked. The reader thread can reach that line before the
    // caller has finished re-serialising and writing the request, so on a fast
    // local stdio server (typical for `initialize` against a node server) the
    // reply was dropped SILENTLY and the call then hung until the 300s timeout
    // even though the server had answered. A 1-slot LinkedBlockingQueue always
    // accepts the offer, and the caller still removes its own entry.
    private val pending = ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<JSONObject>>()
    private val stderrTail = StringBuilder()
    private val lock = Any()

    private fun alive(): Boolean = process?.isAlive == true

    /** [T-mcp-stdio-cap] Occupies a live-server slot (process alive). */
    val isLive: Boolean get() = alive()

    /** [T-mcp-stdio-cap] A JSON-RPC request is in flight — not evictable. */
    val isBusy: Boolean get() = pending.isNotEmpty()

    private fun stderrSnapshot(): String = synchronized(stderrTail) { stderrTail.toString() }

    private fun appendStderr(chunk: String) {
        synchronized(stderrTail) {
            stderrTail.append(chunk)
            // Keep only the last ~2KB — enough for "why did it die" context.
            val over = stderrTail.length - 2048
            if (over > 0) stderrTail.delete(0, over)
        }
    }

    private fun startProcess() {
        stopProcess()
        val pb = ProcessBuilder(spawn.argv)
        pb.environment().clear()
        pb.environment().putAll(spawn.processEnv)
        pb.environment().putAll(spawn.guestEnv)
        pb.redirectErrorStream(false)
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            throw McpError(McpError.CONNECTION_ERROR, "failed to spawn '${spawn.argv.lastOrNull()}': ${e.message}")
        }
        process = proc
        hooks?.onSpawned(proc)
        writer = proc.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))

        // Reader thread: dispatch replies by id; ignore notifications.
        val reader = Thread({
            try {
                while (true) {
                    val line = stdout?.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val id = obj.optInt("id", Int.MIN_VALUE)
                    if (id != Int.MIN_VALUE && (obj.has("result") || obj.has("error"))) {
                        // [T-mcp-stdio-reply-loss] Offer into the caller's bounded
                        // queue WITHOUT removing the map entry: the reply may land
                        // before the caller has reached `poll`, and removal here is
                        // what made the delivery race with the caller's own cleanup.
                        // The caller's `finally { pending.remove(id) }` owns the
                        // lifetime. A second reply for the same id (protocol
                        // violation) is dropped by the 1-slot capacity.
                        pending[id]?.offer(obj)
                    }
                }
            } catch (_: Exception) {
            }
        }, "mcp-stdio-$serverName-reader").apply { isDaemon = true; start() }

        // Stderr drainer: bounded ring for diagnostics (daemon.py parity).
        Thread({
            try {
                val err = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))
                while (true) {
                    val l = err.readLine() ?: break
                    appendStderr(l + "\n")
                }
            } catch (_: Exception) {
            }
        }, "mcp-stdio-$serverName-stderr").apply { isDaemon = true; start() }
    }

    private fun rpc(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        val proc = process ?: throw McpError(McpError.CONNECTION_ERROR, "stdio server not running")
        if (!proc.isAlive) {
            val err = stderrSnapshot().trim()
            throw McpError(
                McpError.CONNECTION_ERROR,
                "stdio server exited before/while serving '$method'" +
                    if (err.isNotEmpty()) " — stderr tail: $err" else "",
            )
        }
        val id = McpJsonRpc.nextId()
        val queue = java.util.concurrent.LinkedBlockingQueue<JSONObject>(1)
        pending[id] = queue
        try {
            val payload = McpJsonRpc.request(id, method, params)
            synchronized(lock) {
                val w = writer ?: throw McpError(McpError.CONNECTION_ERROR, "stdin closed")
                w.write(payload.toString())
                w.newLine()
                w.flush()
            }
            val reply = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
                // [T-mcp-stdio-reply-loss] Late-delivery re-check: a reply can land
                // in the window between the poll timing out and the entry being
                // removed. Take it rather than failing a call the server answered.
                ?: pending.remove(id)?.poll()
                ?: throw McpError(McpError.TIMEOUT, "no reply to '$method' within ${timeoutMs / 1000}s")
            return McpJsonRpc.unwrapResult(reply)
        } finally {
            pending.remove(id)
        }
    }

    private fun notify(method: String, params: JSONObject? = null) {
        val w = writer ?: return
        runCatching {
            synchronized(lock) {
                w.write(McpJsonRpc.notification(method, params).toString())
                w.newLine()
                w.flush()
            }
        }
    }

    override fun ensureInitialized() {
        if (initialized && alive()) return
        synchronized(lock) {
            if (initialized && alive()) return
            if (!alive()) startProcess()
            val result = rpc("initialize", McpJsonRpc.initializeParams(), startupTimeoutMs)
            // Server may negotiate a different version; we accept whatever it
            // answers (daemon.py parity) and keep the constant header value.
            val proto = result.optString("protocolVersion", McpJsonRpc.PROTOCOL_VERSION)
            notify("notifications/initialized")
            initialized = true
            com.openminis.app.logging.AppLogger.info(
                "McpStdioClient",
                "[$serverName] initialized (protocol=$proto)",
            )
        }
    }

    override fun listTools(): JSONArray {
        ensureInitialized()
        return rpc("tools/list", JSONObject(), callTimeoutMs).optJSONArray("tools") ?: JSONArray()
    }

    override fun callTool(tool: String, arguments: JSONObject): JSONObject {
        ensureInitialized()
        return rpc(
            "tools/call",
            JSONObject().apply { put("name", tool); put("arguments", arguments) },
            callTimeoutMs,
        )
    }

    private fun stopProcess() {
        initialized = false
        writer = null
        stdout = null
        val p = process
        process = null
        if (p != null && p.isAlive) {
            runCatching { p.destroy() }
            val exited = runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited) {
                runCatching { p.destroyForcibly() }
                // [T-mcp-stdio-cap] SIGKILLed proot cannot run its
                // --kill-on-exit teardown — the host hook must kill the
                // surviving tracee tree (SandboxWorkload.release kill=true).
                hooks?.onTerminated(p, graceful = false)
            } else {
                hooks?.onTerminated(p, graceful = true)
            }
        } else if (p != null) {
            hooks?.onTerminated(p, graceful = true)
        }
    }

    override fun close() {
        synchronized(lock) { stopProcess() }
    }
}
