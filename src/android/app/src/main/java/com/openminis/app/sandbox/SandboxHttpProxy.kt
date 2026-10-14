package com.openminis.app.sandbox

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Loopback HTTP/CONNECT proxy for sandbox traffic logging and one-click cut.
 * Does not use VpnService. Host LLM OkHttp does not honor http_proxy env.
 */
object SandboxHttpProxy {

    const val PORT = 17890
    private const val TAG = "SandboxHttpProxy"

    // [T-proxy-tunnel-leak] Idle read timeout for BOTH ends of a tunnel. The
    // client socket already had a 20s soTimeout but the remote socket did not,
    // so on an idle HTTPS tunnel one pump thread hit the client timeout and
    // exited while the other blocked forever on a silent remote — the latch
    // never reached zero and leaked one handler thread + two pump threads + two
    // sockets per idle connection. Symmetric soTimeout + a bounded await fixes it.
    private const val IDLE_TIMEOUT_MS = 20_000
    private val running = AtomicBoolean(false)
    private val denyAll = AtomicBoolean(false)
    private val recent = ArrayDeque<JSONObject>()
    private val logHits = AtomicInteger(0)
    private val logLock = Any()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var logFile: File? = null

    fun isRunning(): Boolean = running.get()

    fun envBlock(): Map<String, String>? {
        if (!running.get()) return null
        val uri = "http://127.0.0.1:$PORT"
        return mapOf(
            "http_proxy" to uri,
            "https_proxy" to uri,
            "HTTP_PROXY" to uri,
            "HTTPS_PROXY" to uri,
            "no_proxy" to "localhost,127.0.0.1,::1",
            "NO_PROXY" to "localhost,127.0.0.1,::1",
        )
    }

    fun recentJson(limit: Int = 20): JSONArray {
        val arr = JSONArray()
        synchronized(recent) {
            recent.toList().takeLast(limit).forEach { arr.put(it) }
        }
        return arr
    }

    @Synchronized
    fun start(rootfsDir: File?, deny: Boolean) {
        denyAll.set(deny)
        logFile = rootfsDir?.let { File(File(it, "run").also { d -> d.mkdirs() }, "minis-netlog.jsonl") }
        if (running.get()) return
        val ss = try {
            ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            Log.w(TAG, "proxy bind failed: ${t.message}")
            return
        }
        server = ss
        running.set(true)
        thread(name = "minis-http-proxy", isDaemon = true) {
            try {
                Log.i(TAG, "listening 127.0.0.1:$PORT deny=$deny")
                while (running.get()) {
                    val client = try {
                        ss.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    thread(name = "minis-proxy-conn", isDaemon = true) { handle(client) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "proxy failed: ${t.message}")
            } finally {
                if (server === ss) {
                    running.set(false)
                    runCatching { ss.close() }
                    server = null
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        denyAll.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            val input = client.getInputStream()
            val header = readHeaders(input) ?: return
            val first = header.lineSequence().firstOrNull().orEmpty()
            val parts = first.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            val hostPort = when {
                method.equals("CONNECT", true) -> target
                else -> {
                    val hostLine = header.lineSequence().firstOrNull { it.startsWith("Host:", true) }
                    hostLine?.substringAfter(':')?.trim()?.ifBlank { target } ?: target
                }
            }
            logHit(method, hostPort, denyAll.get())
            if (denyAll.get()) {
                client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            if (method.equals("CONNECT", true)) {
                val hp = hostPort.split(':')
                val host = hp[0]
                val port = hp.getOrNull(1)?.toIntOrNull() ?: 443
                Socket(host, port).use { remote ->
                    // [T-proxy-tunnel-leak] Match the client's idle timeout so
                    // BOTH pump threads self-terminate on an idle tunnel and the
                    // latch reaches zero, instead of one blocking forever on a
                    // silent remote while the other already exited.
                    remote.soTimeout = IDLE_TIMEOUT_MS
                    client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    tunnel(client, remote)
                }
            } else {
                val hp = hostPort.split(':')
                val host = hp[0].trim()
                val port = hp.getOrNull(1)?.toIntOrNull() ?: 80
                Socket(host, port).use { remote ->
                    remote.soTimeout = IDLE_TIMEOUT_MS
                    remote.getOutputStream().write(header.toByteArray())
                    tunnel(client, remote)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "conn: ${t.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readHeaders(input: java.io.InputStream): String? {
        val buf = StringBuilder()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code && buf.endsWith("\r\n\r\n")) return buf.toString()
            prev = b
            if (buf.length > 16_384) return buf.toString()
        }
    }

    private fun tunnel(a: Socket, b: Socket) {
        val done = CountDownLatch(2)
        thread(isDaemon = true) {
            try {
                a.getInputStream().copyTo(b.getOutputStream())
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        thread(isDaemon = true) {
            try {
                b.getInputStream().copyTo(a.getOutputStream())
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        // [T-proxy-tunnel-leak] The pumps now have an idle soTimeout on BOTH
        // sockets, so a stalled tunnel trips them and the latch falls to zero.
        // This await is still bounded as a backstop: on expiry close BOTH
        // sockets so any still-blocked copyTo throws and its thread unwinds,
        // instead of an untimed await() leaking a handler thread + two pump
        // threads + two sockets per idle connection forever.
        val finished = done.await((IDLE_TIMEOUT_MS + 5_000L).toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { a.close() }
            runCatching { b.close() }
            done.await(2_000L, TimeUnit.MILLISECONDS)
        }
    }

    private fun logHit(method: String, host: String, denied: Boolean) {
        val obj = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("method", method)
            .put("host", host)
            .put("denied", denied)
        synchronized(recent) {
            if (recent.size >= 200) recent.removeFirst()
            recent.addLast(obj)
        }
        val f = logFile ?: return
        try {
            synchronized(logLock) {
                if (logHits.incrementAndGet() % 256 == 0 && f.length() > 5L * 1024 * 1024) {
                    val bak = File(f.parentFile, "minis-netlog.jsonl.1")
                    bak.delete()
                    if (!f.renameTo(bak)) {
                        f.writeText("")
                    }
                }
                f.appendText(obj.toString() + "\n")
            }
        } catch (_: Throwable) {
        }
    }
}
