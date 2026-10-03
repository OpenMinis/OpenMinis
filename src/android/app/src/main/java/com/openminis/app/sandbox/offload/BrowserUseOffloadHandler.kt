package com.openminis.app.sandbox.offload

import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * minis-browser-use — expose the agent's browser_use tool as a CLI inside the
 * PRoot sandbox.
 *
 * Output contract mirrors iOS `BrowserUseOffload.m` / `NativeOffloadUtils.m`:
 * every invocation emits a JSON envelope
 *   `{ ok, tool, action, data | error, timestamp }`
 * (pretty-printed by default; minified with `--compact`; data-only with `-q`).
 * Screenshots are persisted under `/var/minis/browser/` and referenced via
 * `image_path` + `minis_url` — raw base64 is only included when the caller
 * explicitly passes `--with-base64`, matching iOS.
 *
 * [T-android-browser-cli-own-pool] Runs on the CALLING chat's tab pool (the one
 * its `browser_use` tool drives), resolved by [BrowserCliPools] from the
 * shell's `MINIS_CHAT_SESSION_ID`; mirrors iOS BrowserUseOffloadBridge. It used
 * to run every chat's calls on one process-wide pool, so concurrent chats
 * navigated each other's page. Screenshots and downloads go to the caller's
 * own `/var/minis/browser/`.
 *
 * Hard 90-second timeout via `withTimeout` so a hanging navigation can't lock
 * the shell indefinitely. Exits 0 for success, 1 for runtime errors, 2 for
 * bad arguments. Bare invocation prints help and exits 0 (matches iOS).
 */
class BrowserUseOffloadHandler(private val app: MinisApp) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // Declared-boolean flags: without this set, the shared OffloadArgs
        // parser would greedily consume the next positional token (typically
        // the action name) as a value, e.g. `--compact navigate` would store
        // `compact=navigate` and drop `navigate` from positional args.
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = BOOLEAN_FLAGS,
        )
        val compact = args.hasFlag("compact")
        val quiet = args.hasFlag("q", "quiet")
        val withBase64 = args.hasFlag("with-base64", "with_base64")

        // Bare invocation / --help: exit 0 with help (matches iOS). iOS writes
        // help to stderr; stdout vs stderr is not separable across this native
        // offload boundary, so we return help in `output` with exit 0.
        if (request.argv.size <= 1 || args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }

        // [T-tools-granular-switches] The CLI is a second door to the same
        // capability: a shell_execute call can reach minis-browser-use even
        // when browser_use was removed from the tool schema. Gate it here too,
        // or turning the tool off would only hide it. Mirrors iOS
        // BrowserUseOffloadBridge's AgentToolSwitch.browser guard. Checked
        // AFTER --help so documentation stays readable while it is off.
        if (!com.openminis.app.tools.AgentToolSwitch.BROWSER.isEnabled(app)) {
            return emitError(
                action = "execute", code = ERR_DISABLED,
                message = "The browser tool is turned off in Settings > Tools.",
                compact = compact, quiet = quiet, exit = 2,
            )
        }

        // Parse args into the browser_use input shape.
        val inputJson = try {
            buildInputJson(args)
        } catch (e: IllegalArgumentException) {
            return emitError(action = "execute", code = ERR_INVALID_ARGS,
                message = e.message ?: "invalid arguments",
                compact = compact, quiet = quiet, exit = 2)
        }

        val input = BrowserActionInput.parse(inputJson.toString())
            ?: return emitError(action = "execute", code = ERR_INVALID_ARGS,
                message = "Invalid browser_use input. Required: 'action' (one of ${BrowserAction.allValues.joinToString(", ")})",
                compact = compact, quiet = quiet, exit = 2)

        val actionName = input.action.value

        // [T-android-browser-cli-own-pool] The caller's pool, not a shared one.
        val pool = when (val r = BrowserCliPools.resolve(app, request.sessionId)) {
            is BrowserCliPools.Resolution.Found -> {
                Log.i(TAG, "action=$actionName sid=${request.sessionId?.take(8)} pool=${r.via}")
                r.pool
            }
            is BrowserCliPools.Resolution.SessionGone -> return emitError(
                action = actionName, code = ERR_INTERNAL,
                message = "session ${r.sessionId} no longer exists; cannot run browser_use.",
                compact = compact, quiet = quiet, exit = 1,
            )
        }
        // The session whose /var/minis is mounted for this shell (the parent,
        // for a sub agent), so files land where the caller can read them.
        val filesSid = request.sessionId?.let {
            com.openminis.app.sandbox.ExecutionCoordinator.mountedSessionIdFor(it)
        }

        // [T-bg-overlay phase 2 fix] Surface browser sub-action progress
        // to SessionActivityTracker.currentToolStatus so the background
        // overlay capsule + FGS notification show what the agent is
        // actually doing — "browser_use: navigate https://example.com"
        // instead of the static "Running: browser_use".
        com.openminis.app.service.SessionActivityTracker.updateToolStatus(
            statusForBrowserAction(actionName, inputJson),
        )

        // Execute under a 90s hard timeout. A hanging navigation in a tab
        // pool shouldn't be able to block the entire shell forever.
        val result: BrowserActionResult = try {
            runBlocking {
                withTimeout(EXECUTE_TIMEOUT_MS) {
                    // [T-browser-readaction-follow-tab-and-yolo-android] The CLI
                    // (`minis-browser-use`) is a serial human/script driver:
                    // navigate → execute_js / get_text / … all expect the page
                    // just navigated to, not a fanned-out grace tab. Drive in
                    // YOLO single-tab mode so every tab-less action sticks to the
                    // selected tab. Explicit --tab-id still routes normally.
                    // Mirrors iOS BrowserUseOffloadBridge passing singleTab=true.
                    // No owner, as on iOS: the chat drives its own pool with
                    // full access.
                    pool.execute(input, singleTab = true)
                }
            }
        } catch (_: TimeoutCancellationException) {
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = "browser action timed out after ${EXECUTE_TIMEOUT_MS / 1000}s",
                compact = compact, quiet = quiet, exit = 1)
        } catch (e: Throwable) {
            Log.w(TAG, "execute failed: ${e.message}", e)
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = e.message ?: "browser action failed",
                compact = compact, quiet = quiet, exit = 1)
        }

        if (!result.success) {
            return emitError(action = actionName, code = ERR_INTERNAL,
                message = result.text.ifEmpty { "browser action failed" },
                compact = compact, quiet = quiet, exit = 1)
        }

        val data = encodeData(result, withBase64 = withBase64, filesSid = filesSid)
        val envelope = envelope(actionName, data)
        return NativeOffloadResult(0, emit(envelope, data, compact = compact, quiet = quiet))
    }

    // ── Input parsing ────────────────────────────────────────────────────

    private fun buildInputJson(args: OffloadArgs): JSONObject {
        // Explicit --json wins; action/flags are ignored in that case.
        args.get("json")?.let { raw ->
            return try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("--json is not valid JSON: ${e.message}")
            }
        }

        val action = args.positional.firstOrNull()
            ?: throw IllegalArgumentException("missing <action>. Use --help for usage.")

        val obj = JSONObject().put("action", action)
        args.get("url")?.let { obj.put("url", it) }
        args.get("selector")?.let { obj.put("selector", it) }
        args.get("text")?.let { obj.put("text", it) }
        args.getInt("coordinate-x", "coordinate_x", "x")?.let { obj.put("coordinate_x", it) }
        args.getInt("coordinate-y", "coordinate_y", "y")?.let { obj.put("coordinate_y", it) }
        args.get("direction")?.let { obj.put("direction", it) }
        args.getInt("amount")?.let { obj.put("amount", it) }
        args.get("script")?.let { obj.put("script", it) }
        args.get("user-agent", "user_agent", "ua")?.let { obj.put("user_agent", it) }
        args.getInt("max-depth", "max_depth")?.let { obj.put("max_depth", it) }
        args.getInt("tab-id", "tab_id")?.let { obj.put("tab_id", it) }
        // set_viewport parameters
        args.getInt("width")?.let { obj.put("viewport_width", it) }
        args.getInt("height")?.let { obj.put("viewport_height", it) }
        if (args.hasFlag("reset")) obj.put("reset", true)
        // get_cookies / scroll_and_collect parameters
        args.get("item-selector", "item_selector")?.let { obj.put("item_selector", it) }
        args.getInt("scroll-count", "scroll_count")?.let { obj.put("scroll_count", it) }
        args.getInt("timeout")?.let { obj.put("timeout", it) }
        if (args.hasFlag("fuzzy")) obj.put("fuzzy", true)
        args.get("keywords")?.let { raw ->
            // Comma- or whitespace-separated list.
            val tokens = raw.split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isNotEmpty()) obj.put("keywords", JSONArray(tokens))
        }
        // set_cookies: a JSON array of cookie objects. Either inline via
        // --cookies '<json>' or, to dodge busybox-ash shell mangling of the
        // JSON's quotes / braces / colons, from a file via --cookies-file <path>
        // (mirrors `minis-config set --file`). Parse failures throw so the
        // handler surfaces an explicit invalid_args error instead of silently
        // dropping the array (which used to reach set_cookies as empty).
        val cookiesFile = args.get("cookies-file", "cookies_file")
        val cookiesRaw: String? = if (cookiesFile != null) {
            val host = PRootKernel.resolveHostPath(cookiesFile)
                ?: throw IllegalArgumentException("--cookies-file: cannot resolve path '$cookiesFile'")
            runCatching { host.readText() }.getOrNull()
                ?: throw IllegalArgumentException("--cookies-file: could not read '$cookiesFile'")
        } else {
            args.get("cookies")
        }
        cookiesRaw?.let { raw ->
            // JSON array first; otherwise accept a Netscape cookies.txt export.
            val arr = runCatching { JSONArray(raw) }.getOrNull()
                ?: parseNetscapeCookies(raw)
                ?: throw IllegalArgumentException(
                    "--cookies (or --cookies-file content) must be a JSON ARRAY of cookie " +
                        "objects, or a Netscape cookies.txt file. JSON object fields: name + " +
                        "value (required); optional domain (defaults to current page host), " +
                        "path (defaults to \"/\"), secure (bool), http_only (bool, alias " +
                        "httpOnly), expires (unix seconds, alias expirationDate), sameSite. " +
                        "e.g. '[{\"name\":\"sid\",\"value\":\"abc\",\"domain\":\".x.com\",\"secure\":true}]'. " +
                        "If the shell is mangling the quotes, write the JSON array to a file " +
                        "and pass --cookies-file <path>.",
                )
            obj.put("cookies", arr)
        }
        return obj
    }

    /**
     * Parse a Netscape-format cookie file (curl/wget/browser export) into the
     * JSON array `set_cookies` consumes. Returns null if it doesn't look like
     * Netscape, so the caller falls through to the JSON error. Format: 7
     * TAB-separated fields per line — domain, includeSubdomains, path, secure,
     * expires, name, value. `#` lines are comments, except curl's `#HttpOnly_`
     * domain prefix which marks an HttpOnly cookie.
     */
    private fun parseNetscapeCookies(text: String): JSONArray? {
        val out = JSONArray()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            var httpOnly = false
            var work = t
            when {
                work.startsWith("#HttpOnly_") -> { httpOnly = true; work = work.removePrefix("#HttpOnly_") }
                work.startsWith("#") -> continue
            }
            val f = work.split("\t")
            if (f.size < 7) continue
            val name = f[5]
            if (name.isEmpty()) continue
            val c = JSONObject().apply {
                put("name", name)
                put("value", f[6])
                put("domain", f[0])
                put("path", f[2])
                if (f[3].equals("TRUE", ignoreCase = true)) put("secure", true)
                if (httpOnly) put("http_only", true)
                f[4].toLongOrNull()?.takeIf { it > 0 }?.let { put("expires", it) }
            }
            out.put(c)
        }
        return if (out.length() > 0) out else null
    }

    // ── Data encoding (iOS BrowserUseOffloadBridge.encode counterpart) ────

    private fun encodeData(r: BrowserActionResult, withBase64: Boolean, filesSid: String?): JSONObject {
        val out = JSONObject()
        out.put("text", r.text)
        out.put("success", r.success)
        r.pageURL?.takeIf { it.isNotEmpty() }?.let { out.put("page_url", it) }

        // Persist screenshot bytes under /var/minis/browser/ so shells can
        // reference the JPEG via image_path + minis_url instead of piping
        // base64 through stdout.
        // [T-android-browser-cli-own-pool] The caller's own browser dir
        // (minis-sessions/<sid>/browser is what /var/minis/browser mounts for
        // that chat). The process-wide mount table only knows the last shell
        // that set it up, so another chat's folder could get the file.
        val browserHostDir: File? = (
            filesSid?.let { com.openminis.app.sandbox.SessionMounts.sessionDir(app.filesDir, it, "browser") }
                ?: PRootKernel.resolveHostPath(VAR_MINIS_BROWSER)
            )?.also {
            try { it.mkdirs() } catch (_: Throwable) { /* non-fatal — write will fail below */ }
        }

        var persistedImagePath: String? = null
        val base64 = r.base64Image
        if (!base64.isNullOrEmpty() && browserHostDir != null) {
            val bytes = try { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                catch (_: Exception) { null }
            if (bytes != null) {
                val filename = "screenshot_${System.currentTimeMillis()}.jpg"
                val dest = File(browserHostDir, filename)
                if (runCatching { dest.writeBytes(bytes) }.isSuccess) {
                    val linuxPath = "$VAR_MINIS_BROWSER/$filename"
                    persistedImagePath = linuxPath
                    out.put("image_path", linuxPath)
                    out.put("minis_url", "minis://browser/$filename")
                } else {
                    Log.w(TAG, "Failed to persist screenshot to ${dest.absolutePath}")
                }
            }
        } else if (!base64.isNullOrEmpty()) {
            Log.w(TAG, "No /var/minis/browser mount — falling back to base64-only output")
        }

        // Fall back to the in-memory host path only when we couldn't persist.
        if (persistedImagePath == null) {
            r.imageFilePath?.takeIf { it.isNotEmpty() }?.let { out.put("image_path", it) }
        }

        if (withBase64 && !base64.isNullOrEmpty()) {
            out.put("image_base64", base64)
        }

        // Fetched file bytes (fetch action)
        val fname = r.fetchedFileName
        if (!fname.isNullOrEmpty()) {
            out.put("fetched_file", fname)
            val data = r.fetchedFileData
            if (data != null) {
                out.put("fetched_bytes", data.size)
                if (browserHostDir != null) {
                    val dest = File(browserHostDir, fname)
                    if (runCatching { dest.writeBytes(data) }.isSuccess) {
                        out.put("fetched_path", "$VAR_MINIS_BROWSER/$fname")
                        out.put("fetched_minis_url", "minis://browser/$fname")
                    } else {
                        Log.w(TAG, "Failed to persist fetched file to ${dest.absolutePath}")
                    }
                }
            }
        }

        return out
    }

    // ── JSON envelope helpers (mirror NativeOffloadUtils.m) ──────────────

    private fun envelope(action: String, data: JSONObject): JSONObject = JSONObject()
        .put("ok", true)
        .put("tool", TOOL_NAME)
        .put("action", action)
        .put("data", data)
        .put("timestamp", isoTimestamp())

    private fun errorEnvelope(action: String, code: String, message: String): JSONObject {
        val err = JSONObject().put("code", code).put("message", message)
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("error", err)
            .put("timestamp", isoTimestamp())
    }

    private fun emitError(
        action: String,
        code: String,
        message: String,
        compact: Boolean,
        quiet: Boolean,
        exit: Int,
    ): NativeOffloadResult {
        val env = errorEnvelope(action, code, message)
        // In quiet mode, error emits just the `error` sub-object.
        val payload = if (quiet) env.optJSONObject("error") ?: env else env
        return NativeOffloadResult(exit, serialize(payload, compact) + "\n")
    }

    private fun emit(envelope: JSONObject, data: JSONObject, compact: Boolean, quiet: Boolean): String {
        val payload: Any = if (quiet) data else envelope
        return serialize(payload, compact) + "\n"
    }

    private fun serialize(payload: Any, compact: Boolean): String {
        if (payload !is JSONObject && payload !is JSONArray) return payload.toString()
        return if (compact) payload.toString() else (payload as? JSONObject)?.toString(2)
            ?: (payload as JSONArray).toString(2)
    }

    private fun isoTimestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }

    /**
     * [T-bg-overlay phase 2 fix] Human-readable browser sub-action status
     * surfaced to the background overlay + FGS notification. Falls back to
     * a bare action name when no rich detail is available — keeps the
     * overlay text useful for any future BrowserAction values too.
     */
    private fun statusForBrowserAction(action: String, input: org.json.JSONObject): String {
        val url = input.optString("url", "").takeIf { it.isNotBlank() }
        val sel = input.optString("selector", "").takeIf { it.isNotBlank() }
        val txt = input.optString("text", "").takeIf { it.isNotBlank() }
        val dir = input.optString("direction", "").takeIf { it.isNotBlank() }
        return when (action) {
            "navigate"           -> "browser: navigate ${url ?: ""}".trim()
            "screenshot"         -> "browser: screenshot"
            "click"              -> "browser: click ${sel ?: ""}".trim()
            "type"               -> "browser: type"
            "scroll"             -> "browser: scroll${dir?.let { " $it" } ?: ""}"
            "scroll_and_collect" -> "browser: scroll & collect"
            "get_text"           -> "browser: read text"
            "get_readable"       -> "browser: extract readable"
            "get_backbone"       -> "browser: extract structure"
            "fetch"              -> "browser: fetch ${url ?: ""}".trim()
            "execute_js"         -> "browser: run JS"
            "set_viewport"       -> "browser: set viewport"
            "get_cookies"        -> "browser: read cookies"
            "set_cookies"        -> "browser: write cookies"
            "go_back", "back"    -> "browser: go back"
            "reload"             -> "browser: reload"
            "find"               -> "browser: find ${txt ?: sel ?: ""}".trim()
            else                 -> "browser: $action"
        }
    }

    companion object {
        private const val TAG = "BrowserUseOffload"
        private const val TOOL_NAME = "minis-browser-use"
        private const val VAR_MINIS_BROWSER = "/var/minis/browser"
        private const val EXECUTE_TIMEOUT_MS = 90_000L

        /**
         * Long-option flags this handler treats as booleans (no value expected).
         * Passed to [OffloadArgs] so the shared parser doesn't greedily consume
         * the next token — e.g. `--compact navigate` would otherwise record
         * `compact=navigate`.
         */
        private val BOOLEAN_FLAGS = setOf(
            "compact", "quiet", "with-base64", "with_base64",
            "reset", "fuzzy", "help",
        )

        // Error codes — match iOS NOFF_ERR_* constants.
        private const val ERR_INVALID_ARGS = "invalid_args"
        /** [T-tools-granular-switches] The user turned browser_use off. */
        private const val ERR_DISABLED = "tool_disabled"
        private const val ERR_INTERNAL = "internal_error"

        private val HELP = """
minis-browser-use - Drive the in-app WebView from the shell

USAGE:
  minis-browser-use <action> [options]
  minis-browser-use --json '<json>'
  minis-browser-use --help

ACTIONS:
  navigate        --url <url>
  screenshot
  click           --selector <css> | --coordinate-x <n> --coordinate-y <n>
  type            --selector <css> --text <text>
  get_text        --selector <css>
  scroll          [--selector <css>] [--direction up|down] [--amount <px>]
  get_page_info
  execute_js      --script <js>
  find_elements   --selector <css>
  hover           --selector <css>
  get_readable
  set_user_agent  --user-agent mobile_chrome|desktop_chrome|custom
  set_viewport    --width <n> --height <n> | --reset
  get_backbone    [--max-depth <n>]
  fetch           --url <url>
  new_tab         [--url <url>]
  close_tab       [--tab-id <n>]
  list_tabs
  get_cookies     --keywords <list> [--fuzzy]
  set_cookies     --cookies <json-array> | --cookies-file <path>
                  Content (for both: the file holds the SAME JSON array):
                    [{"name":"n","value":"v",            // required
                      "domain":".x.com","path":"/",       // optional
                      "secure":true,"http_only":true,     // optional
                      "expires":1893456000}]              // optional unix secs
                  domain defaults to the current page host, path to "/".
                  Aliases accepted: httpOnly, expirationDate, sameSite.
                  --cookies-file also accepts a Netscape cookies.txt
                  (curl/wget/browser export, TAB-separated).
                  Prefer --cookies-file: the JSON's quotes/braces survive
                  the shell intact — write the array to a temp file first.
  scroll_and_collect --scroll-count <n> --item-selector <css> [--keywords <list>]
  wait_for_dom_stable [--timeout <ms>]

COMMON OPTIONS:
  --tab-id <n>     Route the action to a specific tab (default: active tab)
  --json '<s>'     Pass the full input object as JSON (matches browser_use schema)
  --with-base64    Also include image_base64 in the screenshot output. Off by
                   default — screenshots are persisted to /var/minis/browser/
                   and referenced via image_path + minis_url.
  --compact        Minimize JSON output
  -q, --quiet      Output only the data field
  -h, --help       Show this help message

OUTPUT:
  JSON envelope with a `data` object containing:
    text              Human-readable result the agent would see
    success           true / false
    page_url          URL after the action (when applicable)
    image_path        Linux path of the persisted JPEG under /var/minis/browser/
    minis_url         minis://browser/<filename> — stable reference for
                      read_image / downstream tools
    image_base64      Base64 JPEG (only when --with-base64 is set)
    fetched_file      Filename of the downloaded resource (fetch action)
    fetched_path      Linux path of the persisted download under /var/minis/browser/
    fetched_minis_url minis://browser/<filename> for the download

EXAMPLES:
  minis-browser-use navigate --url https://example.com
  minis-browser-use screenshot
  minis-browser-use click --selector '.btn-primary'
  minis-browser-use type --selector 'input[name=q]' --text 'hello'
  minis-browser-use execute_js --script 'return document.title'
  minis-browser-use --json '{"action":"navigate","url":"https://x.com"}'
""".trimIndent() + "\n"
    }
}
