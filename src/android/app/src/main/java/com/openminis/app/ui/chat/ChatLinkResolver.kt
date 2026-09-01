package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.sandbox.FileItem
import java.io.File

/**
 * Decides what should happen when a link inside chat markdown is tapped.
 *
 * Routing order:
 *  1. Recognized minis:// deep-link action  → DeepLink (delegated to MainActivity via Intent.ACTION_VIEW)
 *  2. minis://<sandbox path>, file://, or absolute /var/minis|/root path → SandboxFile
 *  3. Non-http(s) external schemes (intent://, mailto:, tel:, geo:, …)   → ExternalApp
 *  4. Anything else (http(s), about, file)                                → Web
 */
sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()
}

object ChatLinkResolver {

    fun resolve(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ChatLinkAction.Web(rawUrl)

        val uri = runCatching { trimmed.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()

        // 1. minis:// deep links — only branch out when the URL maps to a known action,
        //    otherwise fall through to sandbox-path handling.
        if (scheme == "minis") {
            val action = DeepLinkHandler.parse(uri)
            if (action !is DeepLinkAction.Unknown) {
                return ChatLinkAction.DeepLink(action)
            }
        }

        // 2. Sandbox file resolution — prefer a session-scoped resolver when
        //    the caller knows which chat this link belongs to. The global
        //    `PRootKernel.bindMounts` is last-writer-wins, so on a device
        //    with multiple sessions the resolver otherwise points at
        //    whichever session booted its shell most recently.
        val hostFile = resolveSandboxFile(trimmed, scheme, sessionId, context)
        android.util.Log.w("ChatLinkDiag",
            "resolve url=${trimmed.take(200)} sid=$sessionId hostFile=${hostFile?.absolutePath} exists=${hostFile?.exists()}")
        if (hostFile != null && hostFile.exists() && !hostFile.isDirectory) {
            FileItem.from(hostFile)?.let { return ChatLinkAction.SandboxFile(it) }
        }

        // T136: intent://, mailto:, tel:, geo:, market: etc. need a system
        // dispatch — the in-app preview WebView's `loadUrl(...)` doesn't
        // trip `shouldOverrideUrlLoading` for the initial URL, so without
        // this hop those schemes hit the WebView and surface as
        // ERR_UNKNOWN_URL_SCHEME.
        if (com.openminis.app.ui.browser.BrowserExternalSchemeHandler.shouldHandleExternally(trimmed)) {
            return ChatLinkAction.ExternalApp(trimmed)
        }

        return ChatLinkAction.Web(trimmed)
    }

    /**
     * Map a chat link to a host File when it points into the sandbox, else null.
     * Accepts:
     *   minis://attachments/foo.png        → /var/minis/attachments/foo.png
     *   minis:///var/minis/workspace/x.csv → /var/minis/workspace/x.csv (absolute)
     *   file:///path/to/file               → /path/to/file
     *   /var/minis/workspace/x.csv         → resolved via bind mount
     *   /root/whatever                     → resolved relative to rootfs
     */
    private fun resolveSandboxFile(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): File? {
        fun lookup(linuxPath: String): File? =
            if (sessionId != null && context != null) {
                PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                PRootKernel.resolveHostPath(linuxPath)
            }
        return when (scheme) {
            "minis" -> {
                // Keep '#' — attachment filenames legitimately contain it.
                // `minis://` URLs don't use fragments, so stripping at '#'
                // would truncate filenames like `foo #China.mp4`.
                val stripped = raw.removePrefix("minis://").substringBefore('?')
                // [T-android-minis-url-double-encoding] Try each decode
                // candidate and take the first that exists on disk. See
                // [minisPathCandidates] for why one decode pass isn't enough.
                minisPathCandidates(stripped)
                    .asSequence()
                    .map { candidate ->
                        val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
                        lookup(linuxPath)
                    }
                    .firstOrNull { it != null && it.exists() }
                    // Nothing existed — hand back the primary candidate so the
                    // caller's own exists() check reports against the path the
                    // user actually meant, and diagnostics stay readable.
                    ?: lookup(
                        minisPathCandidates(stripped).first().let {
                            if (it.startsWith("/")) it else "/var/minis/$it"
                        },
                    )
            }
            "file" -> {
                val path = raw.removePrefix("file://").substringBefore('?')
                if (path.isEmpty()) null else File(java.net.URLDecoder.decode(path, "UTF-8"))
            }
            null -> {
                if (raw.startsWith("/")) lookup(raw) else null
            }
            else -> null
        }
    }

    /**
     * [T-android-minis-url-double-encoding] Decode candidates for the path part
     * of a `minis://` URL, in priority order. Ported from iOS
     * `MinisURLPathDecoding` (T-fix-double-encoding).
     *
     * A correctly-formed minis URL percent-encodes each segment exactly once,
     * and one decode pass recovers the real UTF-8 name. But links reach us
     * double-encoded when the agent — or an intermediate Markdown
     * autolink/sanitize step — re-encodes the literal `%` of an
     * already-encoded URL, turning `%E5` into `%25E5`. One decode pass then
     * yields a literal `%E5…`, which matches no file on disk.
     *
     * The user-visible symptom is a tap that does NOTHING, which is why this
     * is worth the tolerance: [resolve] falls through to `ChatLinkAction.Web`,
     * and a web preview of a `minis://` URL renders nothing at all. There is
     * no error, no toast, no navigation — the link just looks dead.
     *
     * Note this is NOT specific to CJK. Any non-ASCII segment percent-encodes
     * to `%XX` bytes and is equally affected; CJK paths merely make it far
     * more likely, since every character encodes (an ASCII path often survives
     * because it has nothing to encode in the first place).
     *
     * Disk existence is the disambiguator, so the extra candidate is only ever
     * reached when the correct single decode found nothing — a filename that
     * legitimately contains a `%` still resolves via the first candidate and
     * never sees the second decode.
     *
     * Percent-decoding is done by hand rather than with
     * [java.net.URLDecoder], which implements
     * `application/x-www-form-urlencoded` — where `+` means SPACE. A path
     * segment like `a+b/file.pdf` is a real directory name on disk, and
     * URLDecoder silently turns it into `a b/file.pdf`, resolving to nothing
     * and producing the same dead-link symptom. (`java.net.URI` is no help
     * either: its multi-arg constructor ENCODES its input, so `getPath()`
     * hands the string straight back undecoded.)
     */
    internal fun minisPathCandidates(strippedPath: String): List<String> {
        // Decode %XX only, never mapping '+' to space (see KDoc). Done by hand
        // rather than with URLDecoder (form semantics: '+' → space) or
        // java.net.URI (its multi-arg constructor ENCODES its input, so
        // getPath() hands the string straight back). Invalid escapes are
        // emitted verbatim so a stray '%' degrades instead of throwing.
        fun decodeOnce(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        out.write(byte)
                        i += 3
                        continue
                    }
                }
                // Non-escape (or malformed escape): keep the character's own
                // UTF-8 bytes so already-decoded CJK passes through intact.
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        val once = decodeOnce(strippedPath)
        val candidates = mutableListOf(once)
        val twice = decodeOnce(once)
        if (twice != once) candidates.add(twice)
        return candidates
    }

    /** Fire a system intent so MainActivity's BROWSABLE filter picks the deep link up. */
    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
