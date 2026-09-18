package com.openminis.app.data

import java.util.Base64

/**
 * [GH#352] Pull large inline base64 / `data:image…;base64,` blobs out of
 * prompt text before they are sent as tokens.
 *
 * A 1,030,449-byte PNG as an image block is ~267 tokens. The same bytes as
 * text/base64 were measured at **959,936 tokens** and a single such tool
 * result permanently 400'd the session: retry re-sends the history.
 *
 * Pure JVM — no Android types — so unit tests can exercise the detector
 * without a device. I/O (offload to `/var/minis/offloads`) is the caller's
 * job; this object only rewrites the string and returns decoded images.
 */
object InlineMediaScrubber {

    /** `data:` URIs are unambiguous; strip even modest ones. */
    const val MIN_DATA_URI_CHARS = 512

    /**
     * Raw base64 runs below this are left alone (short signatures, hashes,
     * tiny icons). 8 KiB of base64 is already ~6k real tokens.
     */
    const val MIN_RAW_BLOB_CHARS = 8_192

    data class Hit(
        val start: Int,
        val endExclusive: Int,
        val payload: String,
        val mimeHint: String?,
    )

    data class ExtractedImage(
        val bytes: ByteArray,
        val mimeType: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExtractedImage) return false
            return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    data class Result(
        val text: String,
        val images: List<ExtractedImage>,
        val strippedChars: Int,
    ) {
        val changed: Boolean get() = strippedChars > 0
    }

    fun containsLargeInlineBlob(text: String): Boolean = findHits(text).isNotEmpty()

    /**
     * Short preview for the on-screen tool card. Strips inline blobs first so
     * Compose never has to layout a 1.3M-char line (that hang is how GH#352
     * was originally noticed in partsJson dumps).
     */
    fun preview(text: String, uiCap: Int = 20_000): String {
        val body = scrub(text).text
        if (body.length <= uiCap) return body
        return body.take(uiCap) + "\n…[truncated for display]"
    }

    fun scrub(text: String): Result {
        val hits = findHits(text)
        if (hits.isEmpty()) return Result(text, emptyList(), 0)
        val images = ArrayList<ExtractedImage>(hits.size)
        val sb = StringBuilder(text.length.coerceAtMost(8_192))
        var last = 0
        var stripped = 0
        for (hit in hits) {
            sb.append(text, last, hit.start)
            val decoded = decodeBase64(hit.payload)
            val sniffed = decoded?.let { sniffImageMime(it) }
            val mime = sniffed ?: hit.mimeHint?.takeIf { it.startsWith("image/") }
            val placeholder = when {
                decoded != null && mime != null -> {
                    images += ExtractedImage(decoded, mime)
                    "[inline image extracted | $mime | ${decoded.size} bytes]"
                }
                decoded != null ->
                    "[inline binary extracted | ${decoded.size} bytes]"
                else ->
                    "[inline base64 omitted | ${hit.endExclusive - hit.start} chars]"
            }
            sb.append(placeholder)
            stripped += hit.endExclusive - hit.start
            last = hit.endExclusive
        }
        sb.append(text, last, text.length)
        return Result(sb.toString(), images, stripped)
    }

    fun findHits(text: String): List<Hit> {
        if (text.length < MIN_DATA_URI_CHARS) return emptyList()
        val hits = mutableListOf<Hit>()
        var i = 0
        while (i < text.length) {
            val dataAt = text.indexOf("data:", i)
            if (dataAt < 0) break
            val parsed = parseDataUri(text, dataAt)
            if (parsed != null) {
                hits += parsed
                i = parsed.endExclusive
            } else {
                i = dataAt + 5
            }
        }
        i = 0
        while (i < text.length) {
            val magicAt = indexOfMagicPrefix(text, i)
            if (magicAt < 0) break
            if (covered(hits, magicAt)) {
                i = magicAt + 1
                continue
            }
            val end = consumeBase64(text, magicAt)
            val len = end - magicAt
            if (len >= MIN_RAW_BLOB_CHARS) {
                hits += Hit(magicAt, end, text.substring(magicAt, end), mimeHint = null)
                i = end
            } else {
                i = magicAt + 1
            }
        }
        hits.sortBy { it.start }
        return hits
    }

    fun sniffImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) return "image/png"
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) return "image/jpeg"
        if (bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte()
        ) return "image/gif"
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return "image/webp"
        return null
    }

    private fun parseDataUri(text: String, at: Int): Hit? {
        // data:[mime][;base64],payload
        if (!text.startsWith("data:", at)) return null
        val comma = text.indexOf(',', at + 5)
        if (comma < 0 || comma - at > 128) return null
        val header = text.substring(at + 5, comma)
        val base64Mark = header.indexOf(";base64")
        if (base64Mark < 0) return null
        val mime = header.substring(0, base64Mark).ifEmpty { null }
        val payloadStart = comma + 1
        val payloadEnd = consumeBase64(text, payloadStart)
        if (payloadEnd - at < MIN_DATA_URI_CHARS) return null
        return Hit(
            start = at,
            endExclusive = payloadEnd,
            payload = text.substring(payloadStart, payloadEnd),
            mimeHint = mime,
        )
    }

    private fun indexOfMagicPrefix(text: String, from: Int): Int {
        val png = text.indexOf("iVBORw0KGgo", from)
        val jpg = text.indexOf("/9j/", from)
        val gif = text.indexOf("R0lGOD", from)
        val webp = text.indexOf("UklGR", from)
        var best = -1
        if (png >= 0) best = png
        if (jpg >= 0 && (best < 0 || jpg < best)) best = jpg
        if (gif >= 0 && (best < 0 || gif < best)) best = gif
        if (webp >= 0 && (best < 0 || webp < best)) best = webp
        return best
    }

    /**
     * Consume a base64 run. Newlines are allowed (MIME wrapping). Spaces are
     * not: letters after a space are English, and treating them as payload
     * would swallow " reply OK" after a blob.
     */
    private fun consumeBase64(text: String, from: Int): Int {
        var i = from
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (isBase64Char(c) || c == '\n' || c == '\r') {
                i++
            } else {
                break
            }
        }
        return i
    }

    private fun isBase64Char(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='

    private fun covered(hits: List<Hit>, index: Int): Boolean {
        for (h in hits) {
            if (index >= h.start && index < h.endExclusive) return true
        }
        return false
    }

    private fun decodeBase64(payload: String): ByteArray? {
        val compact = payload.filter { !it.isWhitespace() }
        if (compact.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getMimeDecoder().decode(payload)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
