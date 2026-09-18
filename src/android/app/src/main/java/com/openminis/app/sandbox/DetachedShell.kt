package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Background PRoot jobs for `shell_execute(detach=true)`.
 *
 * These do **not** go through [PersistentShell]: that shell is a pipe with a
 * completion marker, so `nohup python … &` either blocks the marker, dumps
 * later stdout into the next command, or looks dead because CPython fully
 * buffers stdout when it is not a TTY.
 */
object DetachedShell {

    private const val TAG = "DetachedShell"
    const val MAX_PER_SESSION = 8
    const val LOG_READ_BYTES = 32_000
    const val LINUX_LOG_DIR = "/var/minis/offloads/tasks"

    data class Snapshot(
        val id: String,
        val sessionId: String,
        val command: String,
        val linuxLogPath: String,
        val hostLog: File,
        val startedAtMs: Long,
        val pid: Int?,
        val exitCode: Int?,
    ) {
        val running: Boolean get() = exitCode == null
    }

    data class SpawnResult(
        val ok: Boolean,
        val snapshot: Snapshot?,
        val message: String,
    )

    data class LogSlice(
        val text: String,
        val offset: Long,
        val nextOffset: Long,
        val totalBytes: Long,
        val truncated: Boolean,
    )

    private class Task(
        val id: String,
        val sessionId: String,
        val command: String,
        val linuxLogPath: String,
        val hostLog: File,
        val startedAtMs: Long,
        @Volatile var process: Process?,
        @Volatile var pid: Int?,
        @Volatile var exitCode: Int?,
    ) {
        fun snapshot(): Snapshot = Snapshot(
            id = id,
            sessionId = sessionId,
            command = command,
            linuxLogPath = linuxLogPath,
            hostLog = hostLog,
            startedAtMs = startedAtMs,
            pid = pid,
            exitCode = exitCode,
        )
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    fun wrapDetachedCommand(command: String): String =
        "export PYTHONUNBUFFERED=1 PYTHONIOENCODING=utf-8 PYTHONDONTWRITEBYTECODE=1\n$command"

    fun jsonFlag(obj: JSONObject, key: String): Boolean {
        if (!obj.has(key) || obj.isNull(key)) return false
        return when (val v = obj.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true) || v == "1" || v.equals("yes", ignoreCase = true)
            else -> false
        }
    }

    fun spawn(
        context: Context,
        sessionId: String,
        command: String,
        mounts: Map<String, String>,
        extraEnv: Map<String, String> = emptyMap(),
    ): SpawnResult {
        val running = tasks.values.count { it.sessionId == sessionId && it.exitCode == null }
        if (running >= MAX_PER_SESSION) {
            return SpawnResult(
                ok = false,
                snapshot = null,
                message = "Too many detached tasks ($running). Kill one with task_output kill=true, or wait for exit.",
            )
        }
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val hostDir = File(
            context.filesDir,
            "minis-sessions/$sessionId/offloads/tasks",
        )
        if (!hostDir.mkdirs() && !hostDir.isDirectory) {
            return SpawnResult(false, null, "Could not create $hostDir")
        }
        val hostLog = File(hostDir, "$id.log")
        val linuxLog = "$LINUX_LOG_DIR/$id.log"
        val wrapped = wrapDetachedCommand(command)
        val pb = PRootKernel.newGuestProcessBuilder(
            context = context,
            sessionId = sessionId,
            mounts = mounts,
            guestCommand = wrapped,
        )
        pb.redirectErrorStream(true)
        if (extraEnv.isNotEmpty()) {
            val env = pb.environment()
            for ((key, value) in extraEnv) {
                if (key.isNotEmpty() && !key.contains('=')) env[key] = value
            }
        }
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(hostLog))
        val devNull = File("/dev/null")
        if (devNull.exists()) {
            pb.redirectInput(ProcessBuilder.Redirect.from(devNull))
        }
        val process = try {
            pb.start()
        } catch (t: Throwable) {
            AppLogger.error(TAG, "detach spawn failed: ${t.message}")
            return SpawnResult(false, null, "Failed to start detached process: ${t.message}")
        }
        val task = Task(
            id = id,
            sessionId = sessionId,
            command = command,
            linuxLogPath = linuxLog,
            hostLog = hostLog,
            startedAtMs = System.currentTimeMillis(),
            process = process,
            pid = unixPid(process),
            exitCode = null,
        )
        tasks[id] = task
        thread(name = "minis-detach-$id", isDaemon = true) {
            val code = try {
                process.waitFor()
            } catch (_: Exception) {
                -1
            }
            task.exitCode = code
            task.process = null
            AppLogger.info(TAG, "detached $id exited $code")
        }
        val snap = task.snapshot()
        return SpawnResult(true, snap, formatSpawn(snap))
    }

    fun snapshot(taskId: String): Snapshot? = tasks[taskId]?.snapshot()

    fun list(sessionId: String): List<Snapshot> =
        tasks.values.filter { it.sessionId == sessionId }.map { it.snapshot() }
            .sortedBy { it.startedAtMs }

    fun kill(taskId: String): Boolean {
        val task = tasks[taskId] ?: return false
        val proc = task.process
        if (proc != null) {
            proc.destroyForcibly()
            return true
        }
        return task.exitCode != null
    }

    fun killSession(sessionId: String) {
        for (task in tasks.values) {
            if (task.sessionId == sessionId) {
                task.process?.destroyForcibly()
            }
        }
    }

    fun readLog(taskId: String, offset: Long, maxBytes: Int = LOG_READ_BYTES): LogSlice? {
        val task = tasks[taskId] ?: return null
        return readLogFile(task.hostLog, offset, maxBytes)
    }

    fun readLogFile(file: File, offset: Long, maxBytes: Int = LOG_READ_BYTES): LogSlice {
        if (!file.exists()) {
            return LogSlice("", offset.coerceAtLeast(0L), offset.coerceAtLeast(0L), 0L, false)
        }
        val total = file.length()
        val start = offset.coerceAtLeast(0L).coerceAtMost(total)
        val want = maxBytes.coerceAtLeast(0)
        if (want == 0 || start >= total) {
            return LogSlice("", start, start, total, false)
        }
        FileInputStream(file).use { ins ->
            ins.skip(start)
            val buf = ByteArray(want)
            val n = ins.read(buf)
            if (n <= 0) return LogSlice("", start, start, total, false)
            val text = String(buf, 0, n, Charsets.UTF_8)
            val next = start + n
            return LogSlice(text, start, next, total, truncated = next < total)
        }
    }

    suspend fun waitForMore(
        taskId: String,
        offset: Long,
        timeoutSec: Int,
    ): LogSlice? {
        val deadline = System.currentTimeMillis() + timeoutSec.coerceAtLeast(0) * 1000L
        var slice = readLog(taskId, offset) ?: return null
        while (System.currentTimeMillis() < deadline) {
            val snap = snapshot(taskId) ?: break
            if (!snap.running) break
            if (slice.nextOffset > offset && slice.text.isNotEmpty()) break
            delay(200)
            slice = readLog(taskId, offset) ?: break
        }
        return slice
    }

    fun formatSpawn(snap: Snapshot): String = buildString {
        appendLine("detached=true")
        appendLine("task_id=${snap.id}")
        appendLine("pid=${snap.pid ?: "unknown"}")
        appendLine("status=running")
        appendLine("log=${snap.linuxLogPath}")
        appendLine("command=${snap.command}")
        appendLine()
        appendLine("Running in the background. Call task_output with this task_id to read stdout.")
        appendLine("Do not wrap in nohup/& — detach already backgrounds it.")
        appendLine("Stop/cancel does not kill this task; use task_output kill=true.")
    }

    fun formatOutput(snap: Snapshot, slice: LogSlice): String = buildString {
        appendLine("task_id=${snap.id}")
        appendLine("status=${if (snap.running) "running" else "exited (${snap.exitCode})"}")
        appendLine("pid=${snap.pid ?: "unknown"}")
        appendLine("log=${snap.linuxLogPath}")
        appendLine("bytes=${slice.offset}-${slice.nextOffset} / ${slice.totalBytes}")
        if (slice.truncated) appendLine("truncated=true (pass offset=${slice.nextOffset} for the rest)")
        appendLine("command=${snap.command}")
        appendLine("---")
        append(slice.text)
        if (slice.text.isNotEmpty() && !slice.text.endsWith("\n")) appendLine()
        appendLine("---")
        if (snap.running) {
            appendLine("Still running. Call task_output again with offset=${slice.nextOffset} (optional timeout seconds waits for more).")
        }
    }

    fun formatList(snaps: List<Snapshot>): String {
        if (snaps.isEmpty()) return "No detached tasks for this session."
        return buildString {
            appendLine("task_id  status           pid      command")
            for (s in snaps) {
                val status = if (s.running) "running" else "exit ${s.exitCode}"
                appendLine("${s.id.padEnd(8)} ${status.padEnd(16)} ${(s.pid?.toString() ?: "-").padEnd(8)} ${s.command}")
            }
        }
    }

    fun unixPid(process: Process): Int? {
        try {
            val m = process.javaClass.getMethod("pid")
            val v = m.invoke(process)
            if (v is Number) return v.toInt()
        } catch (_: Throwable) {
            // API 30 Process has no pid(); fall through to the field.
        }
        return reflectPid(process)
    }

    internal fun reflectPid(process: Process): Int? {
        var cls: Class<*>? = process.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField("pid")
                f.isAccessible = true
                return f.getInt(process)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return null
    }
}
