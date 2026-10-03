package com.openminis.app.auth

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Invisible activity that captures OAuth redirect URLs (http://localhost:54545/callback).
 *
 * When the OAuth provider (e.g. Anthropic) redirects to localhost, Android would normally
 * show a "Choose activity" dialog. This activity's intent filter in AndroidManifest catches
 * the redirect URL instead, extracts the code/state parameters, and forwards them to the
 * OAuthCallbackServer via a local HTTP request.
 *
 * The activity finishes immediately (Theme.NoDisplay) — the user never sees it.
 */
class OAuthRedirectActivity : Activity() {

    companion object {
        private const val TAG = "OAuthRedirect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        // [T-android-oauth-log-redact] The redirect carries code + state.
        Log.i(TAG, "OAuth redirect received: ${OAuthLogRedaction.url(uri?.toString())}")

        if (uri != null) {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")

            if (code != null) {
                // Forward the callback to our local server by making a local HTTP request.
                // The OAuthCallbackServer is already listening on the port.
                Thread {
                    try {
                        val port = uri.port.takeIf { it > 0 } ?: 54545
                        val localUrl = "http://127.0.0.1:$port${uri.path}?${uri.query}"
                        Log.d(TAG, "Forwarding to local server: ${OAuthLogRedaction.url(localUrl)}")
                        val conn = java.net.URL(localUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.requestMethod = "GET"
                        val responseCode = conn.responseCode
                        Log.i(TAG, "Local server response: $responseCode")
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to forward to local server", e)
                    }
                }.start()
            } else {
                Log.w(TAG, "No 'code' parameter in redirect URI")
            }
        }

        finish()
    }
}
