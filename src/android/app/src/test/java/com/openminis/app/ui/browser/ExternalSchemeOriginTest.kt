package com.openminis.app.ui.browser

import com.openminis.app.ui.browser.BrowserExternalSchemeHandler.Origin
import com.openminis.app.ui.browser.BrowserExternalSchemeHandler.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-user-initiated-scheme-dispatch] A user tap dispatches any scheme;
 * an agent-driven background navigation still only dispatches known ones.
 *
 * The bug this pins: T318 blocked every scheme outside an 11-entry allowlist
 * to stop the agent's headless browser from throwing the user into another app
 * mid-task. Correct for that case — but the same handler is wired to
 * deliberate taps in chat, so tapping an Amap / Meituan / Taobao link showed a
 * "blocked" Toast and went nowhere. Measured on a Pixel 4a, 7 of 10 common
 * Chinese app schemes were unreachable, including Meituan on a device with 4
 * installed handlers for it.
 *
 * The fix keys on the ORIGIN of the navigation rather than the scheme,
 * because an allowlist can never keep pace with new apps — that lag IS the
 * defect. These tests assert the two modes stay genuinely different.
 */
class ExternalSchemeOriginTest {

    private fun user(scheme: String?) =
        BrowserExternalSchemeHandler.route(scheme, Origin.USER_INITIATED)

    private fun agent(scheme: String?) =
        BrowserExternalSchemeHandler.route(scheme, Origin.AGENT_BACKGROUND)

    /** The schemes from the field report, by app. */
    private val thirdPartyApps = listOf(
        "androidamap",  // Amap — the reported case
        "alipays",      // Alipay
        "taobao",       // Taobao
        "imeituan",     // Meituan
        "ctrip",        // Ctrip
        "qqmusic",      // QQ Music
        "orpheus",      // NetEase Cloud Music
        "dianping",     // Dianping
        "snssdk141",    // Toutiao — one of T318's original offenders
        "weibo",        // Weibo — likewise
    )

    // ─── User taps: everything dispatches ────────────────────────────────

    @Test
    fun `user tap dispatches every third-party app scheme`() {
        for (s in thirdPartyApps) {
            assertEquals(
                "user tap on $s:// must dispatch, not block",
                Route.VIEW_INTENT,
                user(s),
            )
        }
    }

    @Test
    fun `user tap dispatches a scheme nobody has ever heard of`() {
        // The whole point: the allowlist must not have to know about it.
        assertEquals(Route.VIEW_INTENT, user("someapplaunchedtomorrow"))
    }

    // ─── Agent background: T318 behaviour preserved ──────────────────────

    @Test
    fun `agent background still blocks unknown app schemes`() {
        for (s in thirdPartyApps) {
            assertEquals(
                "agent-driven navigation to $s:// must stay blocked (T318)",
                Route.BLOCK,
                agent(s),
            )
        }
    }

    @Test
    fun `agent background blocks the original T318 offenders`() {
        // Named explicitly in T318's commit message.
        for (s in listOf("toutiao", "snssdk141", "weibo")) {
            assertEquals(Route.BLOCK, agent(s))
        }
    }

    // ─── The two modes genuinely differ ──────────────────────────────────

    @Test
    fun `origin is the only thing that changes for an unknown scheme`() {
        // If these ever agree, the fix has collapsed into "allow everything"
        // or reverted to "block everything".
        assertEquals(Route.VIEW_INTENT, user("androidamap"))
        assertEquals(Route.BLOCK, agent("androidamap"))
    }

    // ─── Shared behaviour: identical regardless of origin ────────────────

    @Test
    fun `allowlisted schemes dispatch in both modes`() {
        // tel/mailto/etc. were never the problem and must not regress.
        for (s in listOf("tel", "mailto", "sms", "geo", "market", "weixin")) {
            assertEquals("$s user", Route.VIEW_INTENT, user(s))
            assertEquals("$s agent", Route.VIEW_INTENT, agent(s))
        }
    }

    @Test
    fun `internal schemes load in the webview in both modes`() {
        for (s in listOf("http", "https", "about", "file")) {
            assertEquals("$s user", Route.LOAD_IN_WEBVIEW, user(s))
            assertEquals("$s agent", Route.LOAD_IN_WEBVIEW, agent(s))
        }
    }

    @Test
    fun `intent and android-app always take the parseUri path`() {
        // These carry an encoded fallback URL and must never be dispatched as
        // a bare ACTION_VIEW, in either mode.
        for (s in listOf("intent", "android-app")) {
            assertEquals("$s user", Route.PARSE_URI, user(s))
            assertEquals("$s agent", Route.PARSE_URI, agent(s))
        }
    }

    // ─── Robustness ──────────────────────────────────────────────────────

    @Test
    fun `scheme matching is case-insensitive`() {
        assertEquals(Route.LOAD_IN_WEBVIEW, user("HTTPS"))
        assertEquals(Route.PARSE_URI, user("Intent"))
        assertEquals(Route.VIEW_INTENT, agent("TEL"))
        // Case must not be a way to smuggle a blocked scheme past the agent gate.
        assertEquals(Route.BLOCK, agent("AndroidAmap"))
    }

    @Test
    fun `a null scheme is treated as loadable, never dispatched`() {
        assertEquals(Route.LOAD_IN_WEBVIEW, user(null))
        assertEquals(Route.LOAD_IN_WEBVIEW, agent(null))
    }
}
