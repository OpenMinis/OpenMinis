package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val onCode: (code: String, state: String?) -> Unit,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** The port actually bound (may differ from [port] if fallback was used). */
    var boundPort: Int = port
        private set

    /**
     * Invoked by [stop] when the server is shut down by an external
     * caller (e.g. the user dismisses Chrome Custom Tab and the auth
     * manager wants to abort the in-flight wait without waiting for the
     * 5-minute timeout). The callback is _only_ fired when stop() is
     * called by something other than the success path —
     * [onCode] callers set this to null before calling stop() so they
     * don't fire a cancel after a successful redirect.
     *
     * [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
     */
    @Volatile var onExternalCancel: (() -> Unit)? = null

    fun start() {
        running = true
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    try {
                        serverSocket = ServerSocket(p)
                        boundPort = p
                        bound = true
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(TAG, "Port $p in use, trying next...")
                    }
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: continue
                        Log.d(TAG, "Request: ${OAuthLogRedaction.url(requestLine)}") // [T-android-oauth-log-redact]

                        // CORS preflight for providers (e.g. xAI) that
                        // OPTIONS /callback from their authorization page
                        // before redirecting the browser. Without this we
                        // return a 404 to the preflight and the browser
                        // never follows the redirect — OAuth stalls.
                        // Read the rest of the headers to find Origin and
                        // only echo back permissive CORS for known xAI hosts.
                        if (requestLine.startsWith("OPTIONS")) {
                            var origin: String? = null
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isEmpty()) break
                                val lower = h.lowercase()
                                if (lower.startsWith("origin:")) {
                                    origin = h.substringAfter(":").trim()
                                }
                            }
                            val trustedHosts = listOf("auth.x.ai", "accounts.x.ai")
                            val allowOrigin = if (origin != null && trustedHosts.any { origin!!.contains(it) }) {
                                origin
                            } else {
                                "null"
                            }
                            val pre = "HTTP/1.1 204 No Content\r\n" +
                                "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: *\r\n" +
                                "Access-Control-Max-Age: 600\r\n" +
                                "Connection: close\r\n\r\n"
                            socket.getOutputStream().write(pre.toByteArray())
                            socket.close()
                            continue
                        }

                        // Parse GET /callback?code=xxx&state=yyy HTTP/1.1
                        val parts = requestLine.split(" ")
                        if (parts.size >= 2) {
                            val uri = URI("http://localhost${ parts[1] }")
                            val params = uri.query?.split("&")?.associate {
                                val kv = it.split("=", limit = 2)
                                kv[0] to (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else "")
                            } ?: emptyMap()

                            val code = params["code"]
                            val state = params["state"]

                            // Send response
                            // [T-android-oauth-foreground-exchange] Not "complete": the app still has
                            // to exchange the code, and it can only do that once the user is
                            // back in Minis (background network is blocked). Say so, so the
                            // user returns rather than waiting on a tab that already "succeeded".
                            // ASCII only: Content-Length below is computed from String.length.
                            val html = "<html><body><h1>Authorization received</h1><p>Return to Minis to finish signing in.</p><script>window.close()</script></body></html>"
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\nConnection: close\r\n\r\n$html"
                            socket.getOutputStream().write(response.toByteArray())
                            socket.close()

                            if (code != null) {
                                onCode(code, state)
                                stop()
                                return@Thread
                            }
                        }
                        socket.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling connection", e)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    fun stop() {
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires. Consume the callback (set to
        // null before invoking) so re-entrant stop() calls don't
        // double-fire (T-xai-oauth-stop-resume).
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}
