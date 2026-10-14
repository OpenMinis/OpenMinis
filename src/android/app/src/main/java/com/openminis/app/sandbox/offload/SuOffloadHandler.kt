package com.openminis.app.sandbox.offload

import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.BoundedOutputBuffer
import com.openminis.app.sandbox.GuestWorkloadPolicy
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.SandboxWorkload
import com.openminis.app.service.ActiveRunContext
import com.openminis.app.service.ActiveRunRegistry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host Magisk / KernelSU `su` passthrough, with Shizuku as fallback.
 *
 * Guest `su` / `android-su` is intercepted by native_offload and executed
 * as the **host** superuser binary (`su -c …`) first. If that binary is
 * missing or Magisk/KernelSU refuses elevation, the same command is retried
 * via Shizuku (`sh -c`) when the binder is ready. Shizuku's own agent
 * permission is not required — `su_cli` is the gate; Shizuku is only the
 * elevation transport. `status` / help / version skip the permission gate.
 */
class SuOffloadHandler : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val parsed = SuCommand.parse(request.argv)
        return when (parsed.kind) {
            SuCommand.Kind.HELP -> NativeOffloadResult(0, HELP)
            SuCommand.Kind.VERSION -> NativeOffloadResult(0, "android-su 1.1 (host Magisk/KernelSU, Shizuku fallback)\n")
            SuCommand.Kind.STATUS -> status()
            SuCommand.Kind.INVALID -> NativeOffloadResult(2, "android-su: missing command. Try `su -c id` or `android-su --help`.\n")
            SuCommand.Kind.EXEC -> {
                if (!OffloadGate.allow("su_cli", "android-su", request)) {
                    val body = JSONObject()
                        .put("error", "permission_denied")
                        .put(
                            "message",
                            "Agent is not allowed to use android-su. Open Settings → Permissions → Host su to change.",
                        )
                        .toString()
                    return NativeOffloadResult(126, body + "\n")
                }
                exec(parsed.command!!, parsed.timeoutMs, request)
            }
        }
    }

    private fun status(): NativeOffloadResult {
        val path = SuCommand.findSuBinary()
        val shizukuReady = ShizukuManager.isReady()
        val shizukuState = ShizukuManager.snapshot.value.state.name
        val preferred = when {
            path != null -> "host-su"
            shizukuReady -> "shizuku"
            else -> "none"
        }
        val data = JSONObject()
            .put("found", path != null)
            .put("path", path ?: JSONObject.NULL)
            .put("backend", preferred)
            .put("preferred", "host-su")
            .put("fallback", "shizuku")
            .put(
                "host_su",
                JSONObject()
                    .put("found", path != null)
                    .put("path", path ?: JSONObject.NULL),
            )
            .put(
                "shizuku",
                JSONObject()
                    .put("ready", shizukuReady)
                    .put("state", shizukuState),
            )
        if (preferred == "none") {
            data.put(
                "message",
                "No host su binary and Shizuku is not ready. Install Magisk/KernelSU, or authorize Shizuku.",
            )
            return NativeOffloadResult(1, data.toString(2) + "\n")
        }
        return NativeOffloadResult(0, data.toString(2) + "\n")
    }

    private fun exec(command: String, timeoutMs: Long, request: NativeOffloadRequest): NativeOffloadResult {
        // Host `su` runs on the phone itself, not in the rebuildable sandbox, so
        // a root deletion here means the device. This arm deliberately ignores
        // YOYO: allowRootDeletion stays false, unlike the guest executor.
        GuestWorkloadPolicy.hostRefusal(command)?.let {
            return NativeOffloadResult(126, it + "\n")
        }
        val armed = GuestWorkloadPolicy.clampHostTimeout(timeoutMs)
        val confined = SuCommand.confineHost(command)
        val su = SuCommand.findSuBinary()
        if (su != null) {
            try {
                val (code, output) = SuCommand.runHost(listOf(su, "-c", confined), armed, request.sessionId)
                if (!SuCommand.looksLikeElevationFailure(code, output)) {
                    return NativeOffloadResult(code, output)
                }
                AppLogger.info(TAG, "host su refused elevation (code=$code); trying Shizuku")
                return execViaShizuku(confined, armed, "host su refused elevation")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "su -c failed: ${t.message}")
                return execViaShizuku(confined, armed, t.message ?: "host su failed")
            }
        }
        return execViaShizuku(confined, armed, "host su binary not found")
    }

    private fun execViaShizuku(
        command: String,
        timeoutMs: Long,
        hostReason: String,
    ): NativeOffloadResult {
        if (!ShizukuManager.isReady()) {
            val state = ShizukuManager.snapshot.value.state.name
            return NativeOffloadResult(
                1,
                "android-su: $hostReason. Shizuku fallback unavailable (state=$state). " +
                    "Install Magisk/KernelSU, or authorize Shizuku in Settings → Permissions.\n",
            )
        }
        val r = ShizukuManager.runProcess(arrayOf("sh", "-c", command), timeoutMs = timeoutMs)
        val body = r.combined.let { if (it.endsWith("\n") || it.isEmpty()) it else it + "\n" }
        return NativeOffloadResult(r.exitCode, body)
    }

    companion object {
        private const val TAG = "SuOffload"
        private const val HELP = """android-su — privileged host command (Magisk/KernelSU, then Shizuku)

This is NOT the guest fake-root inside PRoot. Prefers the device's real
`su` binary (`su -c <command>`). If that binary is missing or Magisk
denies elevation, retries the same command via Shizuku when it is ready.

Usage:
  su -c <command>                 privileged host command
  android-su -c <command>         same
  android-su exec <command>       same (joins remaining argv)
  android-su status               host su + Shizuku readiness (no Magisk prompt)
  android-su --help

Options:
  --timeout <seconds>             kill the host process after N seconds (default 60)

Examples:
  su -c id
  su -c 'ls /data'
  android-su exec getprop ro.build.version.release
"""
    }
}

/** Pure command parser + host su locator, unit-tested without a device. */
internal object SuCommand {
    enum class Kind { HELP, VERSION, STATUS, EXEC, INVALID }

    data class Parsed(
        val kind: Kind,
        val command: String? = null,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    )

    const val DEFAULT_TIMEOUT_MS = 60_000L

    /**
     * [T-su-exec-quoting] `exec` re-joins its remaining argv tokens into one
     * command string that is then handed to `su -c`. The guest shell already
     * stripped the user's quotes, so a token like `/data/x a.txt` arrives as ONE
     * argv slot; a bare `joinToString(" ")` re-split it on the space into two
     * wrong paths on a PRIVILEGED host command. Quote each token when it needs
     * it — POSIX single-quote escaping — so the host re-tokenises exactly the
     * way the guest did. Safe words (no metacharacters) stay unquoted for
     * readability and to match the documented `exec getprop <key>` shape.
     */
    private val SHELL_SAFE_TOKEN = Regex("""[A-Za-z0-9_@%+=:,./-]+""")

    internal fun quoteArg(token: String): String =
        if (token.isNotEmpty() && SHELL_SAFE_TOKEN.matches(token)) token
        else "'" + token.replace("'", "'\\''") + "'"

    val SU_CANDIDATES: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/debug_ramdisk/ksu",
        "/debug_ramdisk/magisk",
        "/system/bin/ksu",
        "/system/xbin/ksu",
        "/system/bin/magisk",
        "/data/adb/ksu/bin/ksu",
        "/data/adb/ksud",
        "/data/adb/magisk/magisk",
    )

    fun parse(argv: List<String>): Parsed {
        val args = argv.drop(1)
        if (args.isEmpty()) return Parsed(Kind.HELP)
        var timeoutMs = DEFAULT_TIMEOUT_MS
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-h", "--help", "help" -> return Parsed(Kind.HELP, timeoutMs = timeoutMs)
                "--version", "version" -> return Parsed(Kind.VERSION, timeoutMs = timeoutMs)
                "status", "ping" -> return Parsed(Kind.STATUS, timeoutMs = timeoutMs)
                "--timeout" -> {
                    val secs = args.getOrNull(i + 1)?.toLongOrNull()
                    if (secs != null && secs > 0) {
                        timeoutMs = GuestWorkloadPolicy.clampHostTimeout(secs * 1000L)
                    }
                    i += 2
                    continue
                }
                "-c", "--command" -> {
                    val cmd = args.getOrNull(i + 1)
                    return if (cmd.isNullOrBlank()) Parsed(Kind.INVALID, timeoutMs = timeoutMs)
                    else Parsed(Kind.EXEC, command = cmd, timeoutMs = timeoutMs)
                }
                "exec" -> {
                    val cmd = args.drop(i + 1).joinToString(" ") { quoteArg(it) }.trim()
                    return if (cmd.isEmpty()) Parsed(Kind.INVALID, timeoutMs = timeoutMs)
                    else Parsed(Kind.EXEC, command = cmd, timeoutMs = timeoutMs)
                }
                else -> {
                    if (a.startsWith("-")) {
                        i++
                        continue
                    }
                    val cmd = args.drop(i).joinToString(" ") { quoteArg(it) }.trim()
                    return if (cmd.isEmpty()) Parsed(Kind.INVALID, timeoutMs = timeoutMs)
                    else Parsed(Kind.EXEC, command = cmd, timeoutMs = timeoutMs)
                }
            }
        }
        return Parsed(Kind.INVALID, timeoutMs = timeoutMs)
    }

    fun findSuBinary(): String? {
        for (path in SU_CANDIDATES) {
            val f = File(path)
            if (f.exists() && f.canExecute()) return path
        }
        for (which in listOf("su", "ksu", "ksud")) {
            try {
                val proc = ProcessBuilder("/system/bin/sh", "-c", "command -v $which").start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    continue
                }
                out.takeIf { it.isNotEmpty() && File(it).canExecute() }?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * Same-shell prefix. `nice cmd || cmd` is not used: a pipeline must not
     * run twice when `nice` is missing. Host cgroup is the brake that holds.
     */
    fun confineHost(command: String): String {
        val classified = com.openminis.app.sandbox.kernel.BudgetClassifier.classify(command)
        val wall = minOf(classified.wallMs, GuestWorkloadPolicy.HOST_SU_MAX_TIMEOUT_MS)
        return com.openminis.app.sandbox.kernel.GuardianScript.oneshot(
            command,
            classified.copy(wallMs = wall),
        )
    }

    fun runHost(argv: List<String>, timeoutMs: Long, sessionId: String? = null): Pair<Int, String> {
        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        SandboxWorkload.track(proc, "host-su")
        ActiveRunRegistry.registerProcess(sessionId, proc)
        val activeRun = ActiveRunContext.current()
        activeRun?.registerProcess(proc)
        SandboxWorkload.armDeadline(proc, timeoutMs)
        val buf = BoundedOutputBuffer(headChars = 32 * 1024, tailChars = 32 * 1024)
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = reader.read(chunk)
                        if (n < 0) break
                        buf.append(chunk, 0, n)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            SandboxWorkload.release(proc, kill = true, reason = "host-su-timeout")
            ActiveRunRegistry.unregisterProcess(sessionId, proc)
            activeRun?.unregisterProcess(proc)
            reader.join(1_000)
            return 124 to "android-su: timed out after ${timeoutMs}ms; process group killed\n${buf}"
        }
        reader.join(1_000)
        SandboxWorkload.release(proc, kill = false, reason = "host-su-done")
        ActiveRunRegistry.unregisterProcess(sessionId, proc)
        activeRun?.unregisterProcess(proc)
        return proc.exitValue() to buf.toString()
    }

    /**
     * True when the host `su` wrapper itself refused elevation — not when the
     * inner command ran as root and then failed. Timeouts are not elevation
     * failures (the command was already running).
     */
    fun looksLikeElevationFailure(exitCode: Int, output: String): Boolean {
        if (exitCode == 0 || exitCode == 124) return false
        val t = output.trim()
        // Wrapper denials are short. A real command that ran as root can also
        // print "Permission denied"; don't retry those via Shizuku (would
        // execute the payload twice).
        if (t.length > 200) return false
        if (t.lineSequence().count { it.isNotBlank() } > 2) return false
        if (exitCode == 255) return true
        if (exitCode == 1 && t.isEmpty()) return true
        val low = t.lowercase()
        if (low == "permission denied" || low == "access denied") return true
        val deny = listOf(
            "not allowed to su",
            "can't get permission",
            "cannot get permission",
            "authentication failed",
            "no su program",
            "not rooted",
        )
        if (deny.any { low.contains(it) }) return true
        return low.startsWith("su:")
    }
}
