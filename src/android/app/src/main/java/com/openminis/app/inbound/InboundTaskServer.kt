package com.openminis.app.inbound

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [inbound-task-api] HTTP listener for POST /v1/tasks.
 *
 * Bind is the IPv4 wildcard [BIND_HOST] (`0.0.0.0`), not loopback. Tailscale
 * and a LAN client deliver packets to the phone's Tailscale or Wi-Fi address.
 * Those packets never arrive on 127.0.0.1, so a loopback-only socket would
 * be unreachable from the assistant this API is for. `0.0.0.0` also accepts
 * loopback, which is how `adb forward` reaches it — and loopback is not
 * exempt from the bearer token. A Tailscale-IP-only bind would go dark
 * whenever that address changes or Tailscale is off, and would block the
 * USB-forward path.
 *
 * The debug server is a separate socket (port 5321, debug builds, loopback
 * exempt). This class does not start it and does not share its token.
 *
 * Nothing written to [log] includes the token, the Authorization header, or
 * the prompt.
 */
class InboundTaskServer(
    private val port: Int = InboundTaskProtocol.PORT,
    private val bindHost: String = BIND_HOST,
    private val token: () -> String,
    private val starter: InboundTaskStarter,
    private val log: (String) -> Unit = { message ->
        runCatching { AppLogger.info(TAG, message) }
    },
    private val readTimeoutMs: Int = 15_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val bound = CountDownLatch(1)

    @Volatile
    private var stopped = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(bindHost, port), 50)
                serverSocket = ss
                log("listening port=${ss.localPort} bind=$bindHost")
                bound.countDown()
                while (!stopped) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!stopped) log("accept ended (${e.javaClass.simpleName})")
                        break
                    }
                    scope.launch { handleConnection(client) }
                }
            } catch (e: Exception) {
                log("bind failed (${e.javaClass.simpleName})")
                try { ss?.close() } catch (_: Exception) {}
                serverSocket = null
                bound.countDown()
            }
        }
    }

    fun awaitBound(timeoutMs: Long = 3_000): Boolean {
        if (!bound.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        return isRunning()
    }

    fun isRunning(): Boolean {
        val ss = serverSocket ?: return false
        return ss.isBound && !ss.isClosed
    }

    fun boundPort(): Int = serverSocket?.localPort ?: error("listener is not bound")

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        bound.countDown()
        scope.cancel()
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = readTimeoutMs
                val reader = ByteHttpReader(client.getInputStream())
                val requestLine = reader.readLine(InboundTaskProtocol.MAX_HEADER_LINE) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    send(client, 400, "Bad Request", InboundTaskProtocol.errorJson("bad_request"))
                    return
                }
                val headers = readHeaders(reader)
                if (headers == null) {
                    send(client, 400, "Bad Request", InboundTaskProtocol.errorJson("bad_request"))
                    return
                }
                val expected = try {
                    token()
                } catch (t: Throwable) {
                    log("credential unreadable (${t.javaClass.simpleName})")
                    ""
                }
                val provided = InboundTaskProtocol.parseBearer(headers.authorization)
                if (!InboundTaskProtocol.tokensMatch(provided, expected)) {
                    log("unauthorized")
                    send(client, 401, "Unauthorized", InboundTaskProtocol.errorJson("unauthorized"), authenticate = true)
                    return
                }
                when (val route = InboundTaskProtocol.route(parts[0], parts[1])) {
                    InboundTaskProtocol.Route.Health -> {
                        log("health")
                        send(client, 200, "OK", InboundTaskProtocol.healthJson(client.localPort))
                    }
                    InboundTaskProtocol.Route.CreateTask -> handleCreate(client, reader, headers)
                    is InboundTaskProtocol.Route.TaskStatus -> handleStatus(client, route.id)
                    InboundTaskProtocol.Route.MethodNotAllowed -> {
                        log("method")
                        send(client, 405, "Method Not Allowed", InboundTaskProtocol.errorJson("method_not_allowed"))
                    }
                    InboundTaskProtocol.Route.NotFound -> {
                        log("not_found")
                        send(client, 404, "Not Found", InboundTaskProtocol.errorJson("not_found"))
                    }
                }
            } catch (_: LineTooLong) {
                send(client, 400, "Bad Request", InboundTaskProtocol.errorJson("bad_request"))
            } catch (t: Throwable) {
                log("connection closed (${t.javaClass.simpleName})")
            }
        }
    }

    private fun handleCreate(client: Socket, reader: ByteHttpReader, headers: Headers) {
        if (!headers.contentLengthPresent) {
            send(client, 411, "Length Required", InboundTaskProtocol.errorJson("length_required"))
            return
        }
        val length = headers.contentLength
        if (length == null || length < 0) {
            send(client, 400, "Bad Request", InboundTaskProtocol.errorJson("bad_request"))
            return
        }
        if (length > InboundTaskProtocol.MAX_BODY_BYTES) {
            log("body too large")
            send(client, 413, "Payload Too Large", InboundTaskProtocol.errorJson("body_too_large"))
            return
        }
        if (length == 0) {
            send(client, 400, "Bad Request", InboundTaskProtocol.errorJson("prompt_required"))
            return
        }
        if (!InboundTaskProtocol.acceptsJsonBody(headers.contentType)) {
            send(client, 415, "Unsupported Media Type", InboundTaskProtocol.errorJson("unsupported_media_type"))
            return
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = reader.read(body, offset, length - offset)
            if (n < 0) {
                send(client, 400, "Bad Request", InboundTaskProtocol.errorJson("bad_request"))
                return
            }
            offset += n
        }
        val text = String(body, Charsets.UTF_8)
        when (val parsed = InboundTaskProtocol.parseCreateBody(text)) {
            is InboundTaskProtocol.CreateBody.Reject -> {
                log("rejected ${parsed.code}")
                val reason = if (parsed.http == 400) "Bad Request" else "Payload Too Large"
                send(client, parsed.http, reason, InboundTaskProtocol.errorJson(parsed.code))
            }
            is InboundTaskProtocol.CreateBody.Ok -> dispatch(client, parsed)
        }
    }

    private fun dispatch(client: Socket, parsed: InboundTaskProtocol.CreateBody.Ok) {
        val outcome = try {
            starter.start(parsed.prompt, parsed.session, parsed.label)
        } catch (t: Throwable) {
            log("start failed (${t.javaClass.simpleName})")
            StartOutcome.Rejected(503, "not_started")
        }
        when (outcome) {
            is StartOutcome.Started -> {
                log("started id=${outcome.id} target=${outcome.target}")
                send(
                    client,
                    202,
                    "Accepted",
                    InboundTaskProtocol.createdJson(outcome.id, outcome.sessionId, outcome.target),
                )
            }
            is StartOutcome.Rejected -> {
                log("rejected ${outcome.code}")
                send(client, outcome.httpStatus, reasonFor(outcome.httpStatus), InboundTaskProtocol.errorJson(outcome.code))
            }
        }
    }

    private fun handleStatus(client: Socket, id: String) {
        val view = try {
            starter.status(id)
        } catch (t: Throwable) {
            log("status failed (${t.javaClass.simpleName})")
            null
        }
        if (view == null) {
            log("status missing")
            send(client, 404, "Not Found", InboundTaskProtocol.errorJson("not_found"))
            return
        }
        log("status ${view.status}")
        send(client, 200, "OK", InboundTaskProtocol.statusJson(view))
    }

    private fun readHeaders(reader: ByteHttpReader): Headers? {
        var contentLengthPresent = false
        var contentLength: Int? = null
        var authorization: String? = null
        var contentType: String? = null
        var count = 0
        var total = 0
        while (true) {
            val line = reader.readLine(InboundTaskProtocol.MAX_HEADER_LINE) ?: return null
            if (line.isEmpty()) break
            count++
            total += line.length
            if (count > InboundTaskProtocol.MAX_HEADERS || total > 16 * 1024) return null
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "content-length" -> if (!contentLengthPresent) {
                    contentLengthPresent = true
                    contentLength = value.toIntOrNull()
                }
                "authorization" -> if (authorization == null) authorization = value
                "content-type" -> if (contentType == null) contentType = value
            }
        }
        return Headers(contentLengthPresent, contentLength, authorization, contentType)
    }

    private fun send(
        client: Socket,
        status: Int,
        reason: String,
        body: String,
        authenticate: Boolean = false,
    ) {
        try {
            val payload = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n")
                append("Cache-Control: no-store\r\n")
                if (authenticate) append("WWW-Authenticate: Bearer realm=\"minis-inbound\"\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            val out = client.getOutputStream()
            out.write(head)
            out.write(payload)
            out.flush()
        } catch (t: Throwable) {
            log("response not sent (${t.javaClass.simpleName})")
        }
    }

    private data class Headers(
        val contentLengthPresent: Boolean,
        val contentLength: Int?,
        val authorization: String?,
        val contentType: String?,
    )

    private class ByteHttpReader(private val input: InputStream) {
        fun readLine(maxChars: Int): String? {
            val buf = ByteArrayOutputStream()
            var saw = false
            while (true) {
                val b = input.read()
                if (b < 0) return if (saw) buf.toString(Charsets.UTF_8.name()) else null
                saw = true
                if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name())
                if (b != '\r'.code) {
                    if (buf.size() >= maxChars) throw LineTooLong()
                    buf.write(b)
                }
            }
        }

        fun read(dst: ByteArray, off: Int, len: Int): Int = input.read(dst, off, len)
    }

    private class LineTooLong : Exception()

    companion object {
        private const val TAG = "InboundTask"

        /**
         * IPv4 wildcard. See the class comment for why this is not 127.0.0.1.
         * Clients should use the phone's Tailscale IPv4 (100.x) or LAN IPv4.
         */
        const val BIND_HOST = "0.0.0.0"

        private fun reasonFor(status: Int): String = when (status) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}
