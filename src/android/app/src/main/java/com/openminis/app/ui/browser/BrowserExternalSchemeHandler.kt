package com.openminis.app.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * Centralized router for non-http(s) URL schemes that show up inside our
 * various WebViews. Without this, schemes like `intent://`, `market://`,
 * `tel:`, `mailto:`, `sms:`, `geo:` would be handed to the page-load
 * pipeline and the user would see `net::ERR_UNKNOWN_URL_SCHEME` (T134).
 *
 * Behavior:
 *
 *   * `intent://...` — parsed via `Intent.parseUri(_, URI_INTENT_SCHEME)`
 *     so `action`, `category`, and extras encoded after `#Intent;` get
 *     restored. We strip `FLAG_ACTIVITY_NEW_DOCUMENT` and the package
 *     selector (set when the spec restricts to one app) before fanning
 *     out via `startActivity`. If no app handles the intent, the spec's
 *     `browser_fallback_url` extra is honoured; absent that, a Toast.
 *   * Other "open in app" schemes — fired as a plain `ACTION_VIEW`
 *     against the URI. ActivityNotFoundException → Toast.
 *   * `http`, `https`, `about`, `file` — return `false` so the WebView
 *     loads them normally.
 *
 * Returns `true` when the URL has been (or should have been) routed
 * away from the WebView; the caller should also return `true` from
 * `shouldOverrideUrlLoading` in that case.
 */
object BrowserExternalSchemeHandler {
    private const val TAG = "BrowserExternalScheme"

    private val INTERNAL_SCHEMES = setOf("http", "https", "about", "file")

    /**
     * `intent://` schemes that require Intent.parseUri (NOT plain
     * ACTION_VIEW) to round-trip correctly.
     */
    private val INTENT_SCHEME = "intent"
    private val ANDROID_APP_SCHEME = "android-app"

    /**
     * "Open in another app" schemes that are fine to dispatch as a bare
     * `ACTION_VIEW` (the system will resolve to dialer / mail / maps /
     * Play Store etc.). `intent`/`android-app` are NOT in this list —
     * they need `parseUri`.
     *
     * [T-android-user-initiated-scheme-dispatch] This list now only gates the
     * [Origin.AGENT_BACKGROUND] path. A user-initiated tap dispatches ANY
     * scheme, so the list never has to grow to keep up with new apps.
     */
    private val EXTERNAL_VIEW_SCHEMES = setOf(
        "tel", "mailto", "sms", "smsto", "mms", "mmsto",
        "geo", "market", "whatsapp", "tg", "weixin",
    )

    /**
     * [T-android-user-initiated-scheme-dispatch] Who asked for this navigation.
     *
     * T318 blocked unrecognized app schemes because the agent driving a
     * headless browser could silently hurl the user into Taobao/Weibo/etc.
     * mid-task. That reasoning holds ONLY for navigation the user did not ask
     * for. The same handler is also wired to deliberate taps in chat, and
     * there the block is simply wrong: the user pointed at a map link and got
     * a Toast.
     *
     * The distinction is the ORIGIN of the navigation, not the scheme — an
     * allowlist can never keep pace with new apps, which is the actual defect.
     */
    enum class Origin {
        /**
         * The user tapped something they can see: a chat-message link, or a
         * link inside a visible in-app browser / preview WebView. Their intent
         * is explicit, so every scheme dispatches normally.
         */
        USER_INITIATED,

        /**
         * The agent is driving a background/headless WebView
         * ([com.openminis.app.browser.BrowserUseManager]) and the PAGE chose
         * to navigate. The user is not watching and did not ask, so unknown
         * app schemes stay blocked — this is T318's original case.
         */
        AGENT_BACKGROUND,
    }

    /**
     * [T-android-user-initiated-scheme-dispatch] What [handle] will do with a
     * scheme. Split out from [handle] so the routing decision is pure and can
     * be unit-tested on the JVM — [handle] itself needs a `Context` and fires
     * Toasts / `startActivity`, none of which exist in a plain JVM test.
     */
    internal enum class Route {
        /** `http`/`https`/`about`/`file` — let the WebView load it. */
        LOAD_IN_WEBVIEW,

        /** `intent:` / `android-app:` — needs `Intent.parseUri`. */
        PARSE_URI,

        /** Dispatch as a plain `ACTION_VIEW`. */
        VIEW_INTENT,

        /** Swallow it and tell the user (agent-background only). */
        BLOCK,
    }

    /**
     * [T-android-user-initiated-scheme-dispatch] Decide how [scheme] should be
     * routed given who asked for it.
     *
     * The only asymmetry between the two origins is the final fallback: a
     * scheme nobody recognizes dispatches for [Origin.USER_INITIATED] (they
     * tapped it on purpose) and is blocked for [Origin.AGENT_BACKGROUND] (the
     * page decided, and the user is not even looking at it).
     */
    internal fun route(scheme: String?, origin: Origin): Route {
        val s = scheme?.lowercase() ?: return Route.LOAD_IN_WEBVIEW
        return when {
            s in INTERNAL_SCHEMES -> Route.LOAD_IN_WEBVIEW
            s == INTENT_SCHEME || s == ANDROID_APP_SCHEME -> Route.PARSE_URI
            s in EXTERNAL_VIEW_SCHEMES -> Route.VIEW_INTENT
            origin == Origin.USER_INITIATED -> Route.VIEW_INTENT
            else -> Route.BLOCK
        }
    }

    /**
     * Whether [url] is an external scheme that this handler would route
     * away from any in-app WebView. Used by callers (e.g. the chat-link
     * resolver) that need to decide *before* a `loadUrl` whether the
     * URL should bypass the in-app browser entirely — `loadUrl` doesn't
     * trigger `shouldOverrideUrlLoading` for the initial URL, so an
     * `intent://...` would otherwise reach the WebView and produce
     * `ERR_UNKNOWN_URL_SCHEME` (T136).
     */
    fun shouldHandleExternally(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme !in INTERNAL_SCHEMES
    }

    /**
     * Decide whether [uri] should be intercepted and, if so, route it to
     * an external Activity. Returns `true` when the WebView should NOT
     * load the URL itself.
     */
    fun handle(
        context: Context,
        url: String?,
        origin: Origin = Origin.USER_INITIATED,
    ): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        return handle(context, uri, origin)
    }

    fun handle(
        context: Context,
        uri: Uri?,
        origin: Origin = Origin.USER_INITIATED,
    ): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in INTERNAL_SCHEMES) return false

        return when (route(scheme, origin)) {
            Route.PARSE_URI -> handleIntentScheme(context, uri.toString())
            Route.VIEW_INTENT -> handleViewIntent(context, uri)
            Route.LOAD_IN_WEBVIEW -> false
            Route.BLOCK -> {
                // T318: the agent is driving a background WebView and the PAGE
                // decided to navigate (`toutiao://`, `snssdk141://`, `weibo://`
                // and friends). Firing ACTION_VIEW here would throw the user
                // out of Minis into another app mid-task, for something they
                // never asked for, and would destroy the agent's browsing
                // context. Swallow it.
                AppLogger.info(TAG, "blocked unknown scheme: $scheme (uri=$uri, origin=$origin)")
                Toast.makeText(
                    context,
                    context.getString(R.string.external_link_blocked, scheme),
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        }
    }

    private fun handleIntentScheme(context: Context, url: String): Boolean {
        val intent = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrElse {
            AppLogger.warning(TAG, "parseUri failed for $url: ${it.message}")
            return true
        }

        // Drop selector + browser-only flags so the system resolves the
        // intent against any matching Activity, not just the package the
        // page suggested (which is often unavailable).
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = intent.getStringExtra("browser_fallback_url")

        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            AppLogger.info(TAG, "No app handles $url; fallback=${fallback != null}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "startActivity failed for $url: ${e.message}")
        }

        // No handler — try fallback URL if the page provided one.
        if (!fallback.isNullOrBlank()) {
            try {
                val fb = Intent(Intent.ACTION_VIEW, fallback.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fb)
                return true
            } catch (e: Exception) {
                AppLogger.warning(TAG, "fallback $fallback failed: ${e.message}")
            }
        }

        Toast.makeText(
            context,
            context.getString(R.string.external_link_no_app),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun handleViewIntent(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.external_link_no_app),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            AppLogger.warning(TAG, "ACTION_VIEW failed for $uri: ${e.message}")
        }
        return true
    }
}
