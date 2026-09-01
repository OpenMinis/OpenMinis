package com.openminis.app.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Debug-only JSON-RPC 2.0 server on port 8321.
 * Listens on all interfaces (0.0.0.0) so it's reachable from the local network for debugging.
 * Mirrors the iOS DebugServer for parity with the debug-server CLI skill.
 *
 * IMPORTANT: Only start this in debug builds. Never start in release.
 */
class DebugServer(
    private val context: Context,
    private val port: Int = 5321,
) {
    companion object {
        private const val TAG = "DebugServer"

        /**
         * [T-android-debugserver-auth] Remote-connection auth decision, kept
         * pure for unit testing. Loopback connections (adb forward — the
         * developer's own machine over USB) stay token-free so the local
         * tooling keeps working unchanged; any NON-loopback (LAN) connection
         * must present the device token. Rationale: Android debug builds are
         * never distributed (release channel ships assembleRelease without
         * this server), but the dev workflow leaves the device reachable on
         * the LAN for remote-worker e2e — an unauthenticated 0.0.0.0 RPC
         * surface there can read logs/files and burn API quota. The iOS
         * protocol-v1 encrypted envelope (358e3ded) is deliberately NOT
         * ported: with no distribution surface the token gate is the
         * proportionate hardening (evaluated in the A8 batch report).
         */
        fun isAuthorized(isLoopback: Boolean, providedToken: String?, expectedToken: String): Boolean {
            if (isLoopback) return true
            if (expectedToken.isEmpty()) return false
            val provided = providedToken ?: return false
            if (provided.length != expectedToken.length) return false
            // Constant-time compare — don't leak the token via timing.
            var diff = 0
            for (i in expectedToken.indices) {
                diff = diff or (provided[i].code xor expectedToken[i].code)
            }
            return diff == 0
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val rpcHandler = DebugRPCHandler(context)

    /**
     * [T-android-debugserver-auth] Per-install token required from
     * non-loopback clients. Generated once, persisted in filesDir so the
     * developer can read it with:
     *   adb shell run-as com.openminis.app cat files/debug_server_token
     * Also logged at startup (logcat is adb-only — not readable remotely).
     */
    private val authToken: String by lazy {
        val f = java.io.File(context.filesDir, "debug_server_token")
        if (f.exists()) {
            f.readText().trim().ifEmpty { generateToken(f) }
        } else {
            generateToken(f)
        }
    }

    private fun generateToken(f: java.io.File): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        runCatching { f.writeText(token) }
        return token
    }

    @Volatile
    private var stopped = false

    fun start() {
        if (serverSocket != null) return
        stopped = false

        acceptJob = scope.launch {
            try {
                // Listen on all interfaces (0.0.0.0) for network access
                val ss = ServerSocket(port, 10)
                serverSocket = ss
                Log.i(TAG, "Debug server listening on port $port (all interfaces)")
                Log.i(TAG, "Remote (non-loopback) clients must send X-Minis-Token: $authToken")

                while (!stopped) {
                    try {
                        val client = ss.accept()
                        launch { handleConnection(client) }
                    } catch (e: Exception) {
                        if (!stopped) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        Log.i(TAG, "Server stopped")
    }

    private fun handleConnection(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                // Byte-level, so the header parser cannot pull body bytes into a
                // char decoder's buffer and so the body can be read as the exact
                // Content-Length byte count (see the body read below).
                val reader = HttpRequestReader(s.getInputStream())
                val writer = PrintWriter(s.getOutputStream(), true)

                // Read HTTP request line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Parse error"))
                    return
                }
                val method = parts[0]
                // [T-android-debugserver-skill] Path (query stripped) so the
                // unauthenticated GET skill routes can be dispatched.
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"

                // Handle CORS preflight
                if (method == "OPTIONS") {
                    sendCorsPreflightResponse(writer)
                    return
                }

                // Read headers
                var contentLength = 0
                var providedToken: String? = null
                var accept = ""
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                    val lower = headerLine.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (lower.startsWith("accept:")) {
                        accept = lower.substringAfter(":").trim()
                    }
                    // [T-android-debugserver-auth] Token via X-Minis-Token or
                    // Authorization: Bearer — either spelling accepted.
                    if (lower.startsWith("x-minis-token:")) {
                        providedToken = headerLine.substringAfter(":").trim()
                    }
                    if (lower.startsWith("authorization:")) {
                        val v = headerLine.substringAfter(":").trim()
                        if (v.lowercase().startsWith("bearer ")) {
                            providedToken = v.substring(7).trim()
                        }
                    }
                }

                // [T-android-debugserver-auth] Gate BEFORE any RPC dispatch:
                // loopback (adb forward) is exempt; LAN clients need the token.
                val isLoopback = s.inetAddress?.isLoopbackAddress == true
                if (!isAuthorized(isLoopback, providedToken, authToken)) {
                    Log.w(TAG, "401 unauthorized ${if (isLoopback) "loopback" else s.inetAddress?.hostAddress ?: "?"} (missing/wrong token)")
                    sendResponse(writer, 401, rpcHandler.errorJSON(-32000, "Unauthorized — send X-Minis-Token (see `adb shell run-as com.openminis.app cat files/debug_server_token`)"))
                    return
                }

                // [T-android-debugserver-skill] Self-serve bootstrap routes,
                // mirroring iOS: a client that only has curl can pull the manual
                // and a working reference client straight off the device.
                //
                // Deliberately placed AFTER the auth gate above: unlike iOS
                // (whose /skill is unauthenticated because every RPC is still
                // sealed by the v1 envelope), Android's RPCs are plaintext, so
                // the token remains the only barrier for LAN clients and must not
                // be bypassed. Over `adb forward` (loopback) it's token-free,
                // which is the path the tooling actually uses.
                if (method == "GET") {
                    val wantsHuman = accept.contains("text/html") ||
                        accept.contains("text/markdown") ||
                        accept.contains("text/plain")
                    when (path) {
                        "/schema" -> {
                            sendResponse(writer, 200, schemaJSON())
                            return
                        }
                        "/", "/skill", "/skill/" -> {
                            // A bare `GET /` from a tool (no human Accept) keeps
                            // returning the machine schema, same contract as iOS.
                            if (path == "/" && !wantsHuman) {
                                sendResponse(writer, 200, schemaJSON())
                            } else {
                                sendSkill(writer, wantsHuman)
                            }
                            return
                        }
                        "/skill/examples/python", "/skill/examples/minis_rpc_android.py" -> {
                            sendSkillAsset(writer, "examples/minis_rpc_android.py", "text/x-python; charset=utf-8")
                            return
                        }
                        "/skill/examples/curl", "/skill/examples/curl.md" -> {
                            sendSkillAsset(writer, "examples/curl.md", "text/markdown; charset=utf-8")
                            return
                        }
                    }
                }

                if (method != "POST") {
                    sendResponse(writer, 405, rpcHandler.errorJSON(-32600, "Only POST accepted"))
                    return
                }

                if (contentLength <= 0) {
                    sendResponse(writer, 400, rpcHandler.errorJSON(-32700, "Empty body"))
                    return
                }

                // Read body.
                //
                // Content-Length counts BYTES, so the loop must too. Reading it
                // as CharArray off the decoding reader counted CHARACTERS, and
                // any multi-byte UTF-8 in the body made the two disagree: a
                // 6-byte "你好" decodes to 2 chars, so the loop kept waiting for
                // 4 more that were never coming and the request stalled until
                // one side timed out. Callers saw non-ASCII requests hang while
                // the identical \u-escaped (all-ASCII) body answered in ~25ms.
                val body = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val n = reader.read(body, totalRead, contentLength - totalRead)
                    if (n < 0) break
                    totalRead += n
                }
                val jsonBody = String(body, 0, totalRead, Charsets.UTF_8)

                val responseJSON = runBlocking {
                    rpcHandler.handle(jsonBody)
                }

                sendResponse(writer, 200, responseJSON)
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }
        }
    }

    /// [T-android-debugserver-skill] Capability descriptor. Mirrors the iOS
    /// shape so a shared client can detect the platform: `auth` reports the
    /// Android scheme ("token-lan" — plaintext payloads, token only for
    /// non-loopback) rather than iOS's "v1" envelope, and there is no pair_path.
    private fun schemaJSON(): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return """{"app":"MinisApp","platform":"android","version":"$version",""" +
            """"rpc":"jsonrpc-2.0","auth":"token-lan","transport":"plaintext",""" +
            """"rpc_path":"/","skill_path":"/skill"}"""
    }

    /// GET / and /skill. Human clients get raw markdown; tools get JSON with the
    /// manual plus every reference client inlined, so one request bootstraps.
    private fun sendSkill(writer: PrintWriter, wantsHuman: Boolean) {
        val skill = readSkillAsset("SKILL.md")
        if (skill == null) {
            sendResponse(writer, 503, """{"error":"skill assets not bundled in this build"}""")
            return
        }
        if (wantsHuman) {
            sendResponse(writer, 200, skill, "text/markdown; charset=utf-8")
            return
        }
        val py = readSkillAsset("examples/minis_rpc_android.py") ?: ""
        val curl = readSkillAsset("examples/curl.md") ?: ""
        val payload = org.json.JSONObject().apply {
            put("skill", skill)
            put("clients", org.json.JSONObject().apply {
                put("python", py)
                put("curl", curl)
            })
            put("client_paths", org.json.JSONObject().apply {
                put("python", "/skill/examples/python")
                put("curl", "/skill/examples/curl")
            })
        }
        sendResponse(writer, 200, payload.toString())
    }

    private fun sendSkillAsset(writer: PrintWriter, assetPath: String, contentType: String) {
        val body = readSkillAsset(assetPath)
        if (body == null) {
            sendResponse(writer, 503, """{"error":"asset not bundled: $assetPath"}""")
            return
        }
        sendResponse(writer, 200, body, contentType)
    }

    /// Reads from the DEBUG-only asset source set staged by
    /// scripts/gen_debug_skill_android.sh. Absent in release (where this whole
    /// server never starts) and absent if the generator didn't run.
    private fun readSkillAsset(relPath: String): String? = try {
        context.assets.open("debug-skill/$relPath").bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    private fun sendResponse(
        writer: PrintWriter,
        statusCode: Int,
        body: String,
        contentType: String = "application/json",
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    /**
     * Minimal byte-level HTTP request reader.
     *
     * Exists because the header block and the body need DIFFERENT treatment on
     * the same socket: headers are ASCII lines, the body is a Content-Length
     * count of BYTES. A BufferedReader cannot serve both — it decodes to
     * chars (so the body loop counted the wrong unit for any multi-byte UTF-8)
     * and it read ahead, so body bytes could already sit in its buffer and be
     * lost to a byte-level read. This reads one byte at a time for header lines
     * and hands the still-undrained stream to the body read.
     */
    private class HttpRequestReader(private val input: java.io.InputStream) {
        private val buf = java.io.ByteArrayOutputStream(128)

        /** Reads one CRLF/LF-terminated line as ASCII; null at end of stream. */
        fun readLine(): String? {
            buf.reset()
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b < 0) return if (sawAny) buf.toString("UTF-8") else null
                sawAny = true
                if (b == '\n'.code) return buf.toString("UTF-8")
                if (b != '\r'.code) buf.write(b)
            }
        }

        /** Reads exactly [len] BYTES into [dst], returning how many arrived. */
        fun read(dst: ByteArray, off: Int, len: Int): Int = input.read(dst, off, len)
    }

    private fun sendCorsPreflightResponse(writer: PrintWriter) {
        writer.print("HTTP/1.1 204 No Content\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, X-Minis-Token, Authorization\r\n")
        writer.print("Access-Control-Max-Age: 86400\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }
}
