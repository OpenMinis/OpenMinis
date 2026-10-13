package com.openminis.app.ui

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.openminis.app.sandbox.PRootKernel
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
 */
class MinisImageFetcher(
    private val uri: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // Strip minis:// prefix, drop any ?query, and percent-decode so
        // Chinese/emoji/space filenames resolve to the actual on-disk file.
        // [T-minis-decode-unify] Hand-rolled %XX candidates (shared with
        // ChatLinkResolver), NOT URLDecoder: form-urlencoded semantics turn
        // a literal '+' in a filename into a space, and a single pass cannot
        // recover a double-encoded URL. Link taps, inline media and this
        // fetcher must agree, or the image breaks while the tap on the same
        // URL works.
        //
        // This fetcher stays on the GLOBAL resolver on purpose: callers that
        // know the owning session resolve to a File model before Coil sees a
        // URL (StreamingMarkdownRenderBlock / MarkdownText), so a raw URL
        // reaching here means no session context existed — the global map is
        // the only remaining answer, and a miss renders the documented
        // broken-image placeholder rather than a wrong-session file.
        val stripped = uri.removePrefix("minis://").substringBefore('?')
        val candidates = com.openminis.app.ui.chat.ChatLinkResolver.minisPathCandidates(stripped)
        var hostFile: File? = null
        for (candidate in candidates) {
            val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
            val f = PRootKernel.resolveHostPath(linuxPath)
            if (f != null && f.exists()) {
                hostFile = f
                break
            }
        }
        if (hostFile == null) {
            hostFile = candidates.first().let { candidate ->
                val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
                PRootKernel.resolveHostPath(linuxPath)
            } ?: throw IllegalArgumentException("Cannot resolve path: /var/minis/$stripped")
        }

        if (!hostFile.exists()) {
            throw java.io.FileNotFoundException("File not found: ${hostFile.absolutePath}")
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
            return composeMtimeKey(data.toString())
        }
    }

    class StringMtimeKeyer : Keyer<String> {
        override fun key(data: String, options: Options): String? {
            if (!data.startsWith("minis://")) return null
            return composeMtimeKey(data)
        }
    }

    companion object {
        private fun composeMtimeKey(uri: String): String {
            // Resolve once to fetch mtime. Cheap (single stat on host fs);
            // Coil only calls Keyer when computing/looking up cache keys,
            // not on every recomposition.
            // [T-minis-decode-unify] same candidate decode as fetch(): a
            // '+' filename or a double-encoded URL must key off the SAME
            // file the fetcher reads, or the cache bust never fires.
            val stripped = uri.removePrefix("minis://").substringBefore('?')
            val candidates = com.openminis.app.ui.chat.ChatLinkResolver.minisPathCandidates(stripped)
            var mtime = 0L
            for (candidate in candidates) {
                val linuxPath = if (candidate.startsWith("/")) candidate else "/var/minis/$candidate"
                val t = try {
                    PRootKernel.resolveHostPath(linuxPath)?.takeIf { it.exists() }?.lastModified() ?: 0L
                } catch (_: Throwable) {
                    0L
                }
                if (t > 0L) {
                    mtime = t
                    break
                }
            }
            return "$uri?mt=$mtime"
        }
    }
}
