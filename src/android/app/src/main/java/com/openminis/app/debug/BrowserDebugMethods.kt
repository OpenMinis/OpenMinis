package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.BrowserUseManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browser-debug RPC handlers (`debug.browser.*`).
 *
 * Mirrors the iOS `debug.browser.*` surface (debug-server API spec, §browser). All
 * methods operate on the application-scoped [MinisApp.sharedBrowserTabPool]
 * (the same pool the in-shell `minis-browser-use` agent drives) so external
 * automation sees the tabs the user has actually opened.
 *
 * Tab-id conventions mirror iOS: each method accepts an optional `tabId`. When
 * omitted the currently selected tab is used; an unknown id raises `-32602`
 * with `Tab not found: N`.
 */
internal object BrowserDebugMethods {

    private fun pool(context: Context): BrowserTabPool {
        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")
        return app.sharedBrowserTabPool
    }

    private fun resolveTab(pool: BrowserTabPool, params: JSONObject): BrowserTabPool.Tab {
        val tabs = pool.tabs.value
        if (tabs.isEmpty()) throw RPCException(-32000, "No tabs open")

        if (!params.has("tabId") || params.isNull("tabId")) {
            // Match iOS behavior — fall back to selectedTabId, then first tab.
            val sel = pool.selectedTabId.value
            return tabs.firstOrNull { it.id == sel } ?: tabs.first()
        }
        val id = params.optInt("tabId", -1)
        return tabs.firstOrNull { it.id == id }
            ?: throw RPCException(-32602, "Tab not found: $id")
    }

    fun listTabs(context: Context): JSONObject {
        val pool = pool(context)
        val sel = pool.selectedTabId.value
        val arr = JSONArray()
        for (tab in pool.tabs.value) {
            arr.put(JSONObject().apply {
                put("id", tab.id)
                put("url", tab.manager.currentURL.value)
                put("title", tab.manager.pageTitle.value)
                put("selected", tab.id == sel)
                put("inUse", tab.inUse)
            })
        }
        return JSONObject().put("tabs", arr)
    }

    suspend fun pageInfo(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val mgr = tab.manager
        val res = mgr.execute(BrowserActionInput(action = BrowserAction.GET_PAGE_INFO))
        // GET_PAGE_INFO returns metadata text; we surface the raw fields directly
        // for callers that want structured data without parsing the text blob.
        val viewport = readViewport(mgr)
        return JSONObject().apply {
            put("tabId", tab.id)
            put("url", mgr.currentURL.value)
            put("title", mgr.pageTitle.value)
            put("isLoading", mgr.isLoading.value)
            put("canGoBack", mgr.canGoBack.value)
            put("canGoForward", mgr.canGoForward.value)
            put("viewport", viewport)
            // Include the raw GET_PAGE_INFO text (newline-separated lines that
            // include scroll position + viewport) for clients that want the
            // same string the agent loop sees.
            put("text", res.text)
        }
    }

    private suspend fun readViewport(mgr: BrowserUseManager): JSONObject {
        // Mirror navigationMetadata's probe — same shape iOS exposes under
        // `debug.browser.pageInfo.viewport`.
        val js = "JSON.stringify({" +
            "sx:window.scrollX||0,sy:window.scrollY||0," +
            "pw:document.documentElement.scrollWidth||0," +
            "ph:document.documentElement.scrollHeight||0," +
            "vw:window.innerWidth||0,vh:window.innerHeight||0," +
            "rs:document.readyState||''" +
            "})"
        val res = mgr.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = js))
        val out = JSONObject().apply {
            put("scrollX", 0)
            put("scrollY", 0)
            put("pageWidth", 0)
            put("pageHeight", 0)
            put("viewportWidth", 0)
            put("viewportHeight", 0)
            put("readyState", "")
        }
        // The result text holds the JSON literal for EXECUTE_JS — strip wrapping
        // quotes if any and parse.
        val raw = res.text.trim().trim('"').replace("\\\"", "\"")
        try {
            val parsed = JSONObject(raw)
            out.put("scrollX", parsed.optInt("sx"))
            out.put("scrollY", parsed.optInt("sy"))
            out.put("pageWidth", parsed.optInt("pw"))
            out.put("pageHeight", parsed.optInt("ph"))
            out.put("viewportWidth", parsed.optInt("vw"))
            out.put("viewportHeight", parsed.optInt("vh"))
            out.put("readyState", parsed.optString("rs"))
        } catch (_: Exception) {
            // Fall through with zeros — the page may be at about:blank.
        }
        return out
    }

    suspend fun executeJS(context: Context, params: JSONObject): JSONObject {
        val script = params.optString("script", "").ifEmpty {
            throw RPCException(-32602, "Missing 'script' param")
        }
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.EXECUTE_JS, script = script))
        if (!res.success) throw RPCException(-32000, res.text)
        return JSONObject().put("result", res.text)
    }

    suspend fun getReadable(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.GET_READABLE))
        if (!res.success) throw RPCException(-32000, res.text)
        // BrowserUseManager.getReadable returns formatted text; preserve the
        // same envelope iOS exposes (text + length + tabId).
        return JSONObject().apply {
            put("tabId", tab.id)
            put("text", res.text)
            put("length", res.text.length)
        }
    }

    suspend fun getText(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val selector = params.optString("selector", "").ifEmpty { null }
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.GET_TEXT, selector = selector))
        if (!res.success) throw RPCException(-32000, res.text)
        return JSONObject().apply {
            put("tabId", tab.id)
            put("text", res.text)
            put("length", res.text.length)
        }
    }

    suspend fun screenshot(context: Context, params: JSONObject): JSONObject {
        val pool = pool(context)
        val tab = resolveTab(pool, params)
        val res = tab.manager.execute(BrowserActionInput(action = BrowserAction.SCREENSHOT))
        if (!res.success) throw RPCException(-32000, res.text)
        val b64 = res.base64Image
            ?: throw RPCException(-32000, "Screenshot returned no image data")
        // Decode size for parity with iOS (which reports `size` in bytes).
        val size = try {
            android.util.Base64.decode(b64, android.util.Base64.NO_WRAP).size
        } catch (_: Exception) { 0 }
        return JSONObject().apply {
            put("base64", b64)
            put("size", size)
            put("encoding", "jpeg")
            put("tabId", tab.id)
        }
    }
}
