package com.openminis.app.ui

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.SessionMounts
import okio.buffer
import okio.source
import java.io.File

/**
 * Coil Fetcher that resolves `minis://` URIs to local files.
 *
 * Usage: Register with ImageLoader.Builder().components {
 *     add(MinisImageFetcher.Factory())
 * }
 *
 * minis://attachments/foo.jpg → /var/minis/attachments/foo.jpg → host path
 *
 * [T-android-image-session-direct] The owning chat session comes from the
 * `?session=<id>` query, or from the request parameter [SESSION_PARAM] set by
 * the renderer of the message (the row knows its session for certain; the
 * link text may be years old and carry nothing). With a session, a
 * per-session path is read straight from
 * `minis-sessions/<id>/<subdir>/...` — no PRoot state involved. This used to
 * go through the process-global mount table, which answered with whichever
 * session last built a shell, so an image could fail to load (or load the
 * wrong session's file) just because another chat ran a command.
 */
class MinisImageFetcher(
    private val uri: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val sessionId = sessionOf(uri, options.parameters.value<String>(SESSION_PARAM))
        val hostFile = resolve(options.context, uri, sessionId)
        // Only after every fallback has had its turn: the not-found error is
        // what makes the renderer show its broken-image placeholder.
        if (hostFile == null || !hostFile.isFile) {
            throw java.io.FileNotFoundException("File not found: $uri (session=${sessionId ?: "-"})")
        }

        return SourceResult(
            source = coil.decode.ImageSource(
                source = hostFile.source().buffer(),
                context = options.context,
            ),
            mimeType = guessMimeType(hostFile),
            dataSource = DataSource.DISK,
        )
    }

    private fun guessMimeType(file: File): String? = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> null
    }

    /**
     * String factory — matches when Coil still sees the raw model string before
     * its default mappers run. Rare in practice since Coil's StringMapper
     * converts model strings to Uri; kept as a safety net.
     */
    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.startsWith("minis://")) return null
            return MinisImageFetcher(data, options)
        }
    }

    /**
     * Uri factory — the path the Markdown renderer hits. Coil's default
     * StringMapper converts an `AsyncImage(model = "minis://…")` String into
     * an android.net.Uri before fetcher resolution, so the String factory
     * above is never consulted for markdown images. Match on scheme here.
     */
    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "minis") return null
            return MinisImageFetcher(data.toString(), options)
        }
    }

    /**
     * T-image-cache-mtime-35133: Bust Coil's memory + disk cache when a
     * `minis://` file is overwritten in-place (e.g. Grok regenerating an
     * image to the same `attachments/cat.jpg`). Without this, Coil keys
     * off the URI alone and keeps serving the previous bitmap; only the
     * ToolDetailSheet — which reads the File directly — saw the new bytes.
     *
     * The key composes `<minis-uri>?mt=<lastModified>`. The fetcher above
     * already strips `?query` before resolving, so adding the suffix here
     * does not interfere with on-disk lookup. Returning `null` falls back
     * to Coil's default keying, which is correct for non-minis data.
     *
     * Memory cache key is set by [Keyer]; disk cache key derives from the
     * same returned string in Coil 2.
     */
    class MtimeKeyer : Keyer<Uri> {
        override fun key(data: Uri, options: Options): String? {
            if (data.scheme != "minis") return null
            return composeMtimeKey(options.context, data.toString(), options.parameters.value(SESSION_PARAM))
        }
    }

    class StringMtimeKeyer : Keyer<String> {
        override fun key(data: String, options: Options): String? {
            if (!data.startsWith("minis://")) return null
            return composeMtimeKey(options.context, data, options.parameters.value(SESSION_PARAM))
        }
    }

    companion object {
        private const val TAG = "MinisImageFetcher"

        /** ImageRequest parameter carrying the owning chat session id. */
        const val SESSION_PARAM = "minis.sessionId"

        private fun composeMtimeKey(context: Context, uri: String, paramSession: String?): String {
            // Resolve once to fetch mtime. Cheap (a stat or two on the host
            // fs); Coil only calls Keyer when computing/looking up cache keys,
            // not on every recomposition. The session is part of the key: the
            // same `minis://attachments/a.png` names a different file in each
            // chat, and must not share a cache entry across them.
            val sessionId = sessionOf(uri, paramSession)
            val mtime = try {
                resolve(context, uri, sessionId)?.lastModified() ?: 0L
            } catch (_: Throwable) {
                0L
            }
            return if (sessionId != null && paramSession != null && sessionOf(uri, null) == null) {
                "$uri?mt=$mtime&s=$sessionId"
            } else {
                "$uri?mt=$mtime"
            }
        }

        /** `?session=` (or `?sessionId=`) on the URI wins over the request parameter. */
        internal fun sessionOf(uri: String, paramSession: String?): String? {
            val query = uri.substringAfter('?', "")
            if (query.isNotEmpty()) {
                for (pair in query.split('&')) {
                    val k = pair.substringBefore('=')
                    if (k == "session" || k == "sessionId") {
                        val v = runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                            .getOrNull()
                        if (!v.isNullOrBlank() && isSafeSessionId(v)) return v
                    }
                }
            }
            return paramSession?.takeIf { it.isNotBlank() && isSafeSessionId(it) }
        }

        /** A session id is one path segment; anything else could walk out of minis-sessions. */
        private fun isSafeSessionId(id: String): Boolean =
            id != "." && id != ".." && '/' !in id && '\\' !in id

        /**
         * Host file for [uri], or null. Order:
         *  1. with a session: that session's own directory, read directly;
         *  2. global dirs (shared / memory / skills / mcp-servers) directly,
         *     external mounts through the (global-only) mount table;
         *  3. no session, or the session's file is missing (a link copied
         *     between chats, a restored backup): the legacy resolver, then a
         *     filename search across sessions — only reached on a miss, so it
         *     never costs a normal load anything.
         */
        internal fun resolve(context: Context, uri: String, sessionId: String?): java.io.File? =
            resolve(context.filesDir, uri, sessionId)

        internal fun resolve(filesDir: java.io.File, uri: String, sessionId: String?): java.io.File? {
            // Strip minis:// and any ?query, then percent-decode so
            // Chinese/emoji/space filenames resolve to the actual file.
            val stripped = uri.removePrefix("minis://").substringBefore('?')
            val decoded = try {
                java.net.URLDecoder.decode(stripped, "UTF-8")
            } catch (_: Throwable) {
                stripped
            }
            if (decoded.split('/').any { it == ".." }) return null
            val linuxPath = "/var/minis/$decoded"
            val sub = decoded.substringBefore('/')
            val rest = decoded.substringAfter('/', "")

            if (PRootKernel.isPerSessionPath(linuxPath)) {
                if (sessionId != null) {
                    val direct = SessionMounts.sessionDir(filesDir, sessionId, sub)
                        .let { if (rest.isEmpty()) it else java.io.File(it, rest) }
                    if (direct.isFile) return direct
                }
                PRootKernel.resolveHostPath(linuxPath)?.takeIf { it.isFile }?.let { return it }
                return searchSessions(filesDir, sub, rest, exclude = sessionId)
            }
            if (sub in SessionMounts.GLOBAL_SUBDIRS) {
                val f = java.io.File(filesDir, "minis-global/$decoded")
                if (f.isFile) return f
            }
            return PRootKernel.resolveHostPath(linuxPath)
        }

        /** Newest `minis-sessions/<any>/<sub>/<rest>`; last resort for orphan links. */
        private fun searchSessions(
            filesDir: java.io.File,
            sub: String,
            rest: String,
            exclude: String?,
        ): java.io.File? {
            if (rest.isEmpty()) return null
            val hit = java.io.File(filesDir, "minis-sessions").listFiles().orEmpty()
                .asSequence()
                .filter { it.name != exclude }
                .map { java.io.File(it, "$sub/$rest") }
                .filter { it.isFile }
                .maxByOrNull { it.lastModified() }
            if (hit != null) Log.i(TAG, "orphan link $sub/$rest found by search in ${hit.parentFile?.parentFile?.name}")
            return hit
        }
    }
}
