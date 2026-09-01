package com.openminis.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * [T-android-soul-custom-icon] The Soul identity icon: an emoji, or a
 * transparent image stored inline as a data URI.
 *
 * Port of the iOS `SoulIconImage` / emoji-normalization pair
 * (`dfa7a17b5`, `68b9ceaed`). The rules here are contract, not preference —
 * the value syncs between platforms, so both sides must agree on what is
 * storable.
 */
object SoulIcon {

    /** The stored prefix. PNG specifically: it is the format we re-encode to. */
    const val DATA_URI_PREFIX = "data:image/png;base64,"

    /** Longest edge of the stored bitmap, in pixels. ~1-4 KB as a data URI. */
    const val STORED_PIXELS = 96

    /**
     * Hard cap on the stored string. A 96px PNG lands far below this; the cap
     * exists so a hand-edited or synced-in SOUL.md cannot put an unbounded
     * blob on the single frontmatter line (there is no other size guard on
     * frontmatter, unlike the body which has its own limit).
     */
    const val MAX_DATA_URI_CHARS = 64 * 1024

    fun isDataUri(value: String): Boolean = value.startsWith(DATA_URI_PREFIX)

    /**
     * Corner radius as a fraction of the icon's edge, matching iOS.
     *
     * Rounded, deliberately NOT a circle: at 18dp in the chat header a circle
     * eats the corners of a small avatar. 22% is the platform app-icon
     * proportion.
     */
    const val CORNER_RADIUS_FRACTION = 0.22f

    /** Why a picked image was refused. */
    enum class Rejection { UNREADABLE, TOO_LARGE }

    sealed class EncodeResult {
        data class Success(val dataUri: String) : EncodeResult()
        data class Failure(val reason: Rejection) : EncodeResult()
    }

    /**
     * Normalize a picked bitmap into the stored form: centre-cropped to 1:1,
     * downscaled to [STORED_PIXELS], PNG, base64 data URI.
     *
     * **Opaque images are accepted.** An earlier version refused anything
     * without an alpha channel, reasoning that an unframed opaque rectangle
     * reads as a broken tile. That reasoning was about PRESENTATION, so the
     * fix belongs in the renderer — every image is now clipped to a rounded
     * rectangle ([CORNER_RADIUS_FRACTION]) wherever it is drawn. With that in
     * place the refusal only turned away most of the images a user might pick.
     * Follows iOS `fe2f3ae8b`, which reversed the same rule for the same
     * reason; the two platforms must agree, since the value syncs.
     *
     * Alpha is still PRESERVED (PNG, never flattened) — it is simply no
     * longer required.
     */
    fun encode(source: Bitmap): EncodeResult {
        if (source.width <= 0 || source.height <= 0) {
            return EncodeResult.Failure(Rejection.UNREADABLE)
        }

        val square = squareCropped(source)
        // Never upscale: a 48px source stays 48px rather than being blown up
        // to 96 and looking soft.
        val side = minOf(STORED_PIXELS, square.width, square.height)
        val scaled = if (square.width == side && square.height == side) {
            square
        } else {
            Bitmap.createScaledBitmap(square, side, side, true)
        }

        val png = ByteArrayOutputStream().use { out ->
            // PNG is lossless and ignores the quality argument, so alpha and
            // exact pixels survive the round trip.
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return EncodeResult.Failure(Rejection.UNREADABLE)
            }
            out.toByteArray()
        }

        // NO_WRAP is mandatory: the default inserts newlines, and the
        // frontmatter parser is strictly line-oriented — a wrapped payload
        // would be truncated at the first break.
        val encoded = DATA_URI_PREFIX + Base64.encodeToString(png, Base64.NO_WRAP)
        if (encoded.length > MAX_DATA_URI_CHARS) {
            return EncodeResult.Failure(Rejection.TOO_LARGE)
        }
        return EncodeResult.Success(encoded)
    }

    /** Decode a stored data URI. Returns null for an emoji value or garbage. */
    fun decode(value: String): Bitmap? {
        if (!isDataUri(value)) return null
        return runCatching {
            val bytes = Base64.decode(value.removePrefix(DATA_URI_PREFIX), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * [T-android-soul-icon-config-images] Turn a `minis-config` value into
     * bitmap bytes.
     *
     * Mirrors iOS `fe2f3ae8b`: an address is only an IMPORT SOURCE. Whatever
     * it resolves to goes through the same [encode] the Settings picker uses,
     * and only the RESULT is stored — so the source file can be deleted
     * afterwards and the icon still survives attachment cleanup and syncing.
     *
     * Deliberately NOT supported on Android: `http(s)://`. iOS resolves those
     * in an async phase before its confirmation sheet; Android's
     * `ConfigField.write` is synchronous, and doing a network fetch inside it
     * would block the caller and hand a model-supplied URL a request from
     * inside the app — an SSRF surface that needs the same host-blocklist
     * treatment iOS built. Rather than ship a weaker version of that, remote
     * URLs are refused with a message pointing at the local forms. Everything
     * that does not need the network is supported.
     */
    sealed class Source {
        data class Bytes(val data: ByteArray) : Source() {
            // ByteArray needs structural equals for the data class to behave.
            override fun equals(other: Any?): Boolean =
                this === other || (other is Bytes && data.contentEquals(other.data))
            override fun hashCode(): Int = data.contentHashCode()
        }
        data class LinuxPath(val path: String) : Source()
        data class Unsupported(val reason: String) : Source()
    }

    /** Classify a config value that is not an emoji and not empty. */
    fun classifySource(raw: String): Source {
        val v = raw.trim()
        return when {
            v.startsWith("http://", true) || v.startsWith("https://", true) ->
                Source.Unsupported(
                    "remote URLs aren't supported on Android — download the file first, " +
                        "then pass a path like /var/minis/attachments/icon.png",
                )
            v.startsWith("minis://") -> {
                // minis://attachments/x.png -> /var/minis/attachments/x.png
                val rest = v.removePrefix("minis://").trimStart('/')
                if (rest.isEmpty()) Source.Unsupported("empty minis:// path")
                else Source.LinuxPath("/var/minis/$rest")
            }
            v.startsWith("data:") -> {
                val comma = v.indexOf(',')
                val meta = if (comma > 0) v.substring(0, comma) else ""
                if (comma < 0 || !meta.contains(";base64")) {
                    Source.Unsupported("only base64 data URIs are supported")
                } else {
                    decodeBase64(v.substring(comma + 1))
                        ?.let { Source.Bytes(it) }
                        ?: Source.Unsupported("the data URI's base64 could not be decoded")
                }
            }
            v.startsWith("/") -> Source.LinuxPath(v)
            // Bare base64 — auto-detected, matching iOS. Checked last so it
            // cannot shadow any of the addressed forms.
            looksLikeBareBase64(v) ->
                decodeBase64(v)?.let { Source.Bytes(it) }
                    ?: Source.Unsupported("that base64 could not be decoded")
            else -> Source.Unsupported(
                "not an emoji, a data URI, base64, a minis:// resource or a /var/minis path",
            )
        }
    }

    private fun decodeBase64(s: String): ByteArray? = runCatching {
        Base64.decode(s.trim(), Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun looksLikeBareBase64(v: String): Boolean =
        v.length >= 32 && v.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

    /**
     * The linux directories a config-supplied path may read from.
     *
     * Containment resolves symlinks on BOTH sides before comparing (same
     * construction as the backup extractor), so a symlink inside an allowed
     * directory cannot point out of it. Without the canonicalisation a
     * model-supplied `/var/minis/attachments/../../../databases/x` would walk
     * straight out of the sandbox.
     */
    val ALLOWED_LINUX_ROOTS = listOf(
        "/var/minis/attachments",
        "/var/minis/workspace",
        "/var/minis/offloads",
        "/var/minis/shared",
        "/var/minis/memory",
        "/var/minis/skills",
    )

    /** True when [candidate] really sits inside [root] after both are resolved. */
    fun isContained(candidate: java.io.File, root: java.io.File): Boolean {
        val c = runCatching { candidate.canonicalFile }.getOrElse { return false }
        val r = runCatching { root.canonicalFile }.getOrElse { return false }
        // The separator matters: it stops "/a/bc" matching root "/a/b".
        return c == r || c.path.startsWith(r.path + java.io.File.separator)
    }

    /** Centre-crop to 1:1, keeping the shorter edge. */
    private fun squareCropped(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w == h) return bitmap
        val side = minOf(w, h)
        return Bitmap.createBitmap(bitmap, (w - side) / 2, (h - side) / 2, side, side)
    }

    // ── Emoji ────────────────────────────────────────────────────────────

    /**
     * Suggestions offered in the picker.
     *
     * **All Unicode 6.0 (2010).** Non-negotiable: this value syncs to iOS, and
     * a 2021-or-later emoji renders as a blank box on a device with an older
     * font, which reads as data loss rather than a style choice. No skin tones
     * and no ZWJ sequences, for the same reason.
     */
    val SUGGESTED_EMOJI: List<String> = listOf(
        "✨", "🤖", "🐱", "🦊", "🐧", "🦉", "🌟", "⚡",
        "🧠", "💡", "🔮", "🚀", "🌊", "🍀", "🎯", "🐳",
    )

    /**
     * Keep at most one emoji, preferring whatever the user just added.
     *
     * Called PER KEYSTROKE, not on submit: typing a second emoji replaces the
     * first (we take the LAST one present), and non-emoji input is silently
     * dropped rather than surfaced as an error.
     *
     * Operates on grapheme clusters, so a flag (🇯🇵), keycap (1️⃣), ZWJ
     * sequence (👩‍💻) or skin-toned emoji (👍🏽) each survive as ONE glyph
     * instead of being sliced apart.
     */
    fun normalizeEmojiInput(input: String): String =
        graphemeClusters(input).lastOrNull { isEmojiGlyph(it) } ?: ""

    /**
     * True for a grapheme cluster that is genuinely an emoji.
     *
     * The trap this exists for: a naive "has the Emoji property" test accepts
     * bare ASCII digits, `#` and `*`, because Unicode gives those the Emoji
     * property — they are the bases of keycap sequences like 1️⃣. So a lone
     * "1" would pass and be stored as the identity icon.
     *
     * The rule, matching iOS: a MULTI-scalar cluster is accepted when any
     * scalar is emoji-ish (that keeps 1️⃣, 🇯🇵, 👩‍💻, 👍🏽); a LONE scalar
     * must have default emoji presentation (which excludes plain digits,
     * letters, CJK and punctuation).
     */
    fun isEmojiGlyph(cluster: String): Boolean {
        if (cluster.isEmpty()) return false
        val codePoints = cluster.codePoints().toArray()
        if (codePoints.isEmpty()) return false

        if (codePoints.size > 1) {
            // A variation selector-16 or keycap makes an otherwise-text base
            // render as emoji; a ZWJ or regional-indicator pair is emoji by
            // construction.
            if (codePoints.any { it == 0xFE0F || it == 0x20E3 || it == 0x200D }) return true
            if (codePoints.all { it in 0x1F1E6..0x1F1FF }) return true
            return codePoints.any { hasEmojiPresentation(it) }
        }
        return hasEmojiPresentation(codePoints[0])
    }

    /**
     * Approximates Unicode's Emoji_Presentation property: code points that
     * render as emoji by default, with no variation selector.
     *
     * Kotlin/Java exposes no Emoji_Presentation query (and
     * `Character.isEmoji` is API 35+, far above our minSdk), so this is an
     * explicit range list. It deliberately EXCLUDES the keycap bases
     * (`0-9`, `#`, `*`) and other text-default symbols, which is exactly the
     * case that a property-based test gets wrong.
     */
    private fun hasEmojiPresentation(cp: Int): Boolean = when (cp) {
        0x231A, 0x231B,                                  // watch, hourglass
        0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2648, 0x2649, 0x264A, 0x264B, 0x264C, 0x264D,  // zodiac
        0x264E, 0x264F, 0x2650, 0x2651, 0x2652, 0x2653,
        0x267F, 0x2693, 0x26A1, 0x26AA, 0x26AB,          // ⚡ is here
        0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26D4,
        0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD,
        0x2705, 0x270A, 0x270B,
        0x2728,                                          // ✨ the default
        0x274C, 0x274E,
        0x2753, 0x2754, 0x2755, 0x2757,
        0x2795, 0x2796, 0x2797,
        0x27B0, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B55,
        -> true
        else -> when (cp) {
            in 0x2B05..0x2B07 -> false                   // text-default arrows
            in 0x1F004..0x1F0CF -> true                  // mahjong, playing card
            in 0x1F18E..0x1F19A -> true
            in 0x1F1E6..0x1F1FF -> false                 // lone regional indicator
            in 0x1F200..0x1F251 -> true
            in 0x1F300..0x1F320 -> true
            in 0x1F32D..0x1F335 -> true
            in 0x1F337..0x1F37C -> true
            in 0x1F37E..0x1F393 -> true
            in 0x1F3A0..0x1F3CA -> true
            in 0x1F3CF..0x1F3D3 -> true
            in 0x1F3E0..0x1F3F0 -> true
            in 0x1F3F4..0x1F3F4 -> true
            in 0x1F3F8..0x1F43E -> true
            in 0x1F440..0x1F4FC -> true
            in 0x1F4FF..0x1F53D -> true
            in 0x1F54B..0x1F54E -> true
            in 0x1F550..0x1F567 -> true
            in 0x1F57A..0x1F57A -> true
            in 0x1F595..0x1F596 -> true
            in 0x1F5A4..0x1F5A4 -> true
            in 0x1F5FB..0x1F64F -> true
            in 0x1F680..0x1F6C5 -> true
            in 0x1F6CC..0x1F6CC -> true
            in 0x1F6D0..0x1F6D2 -> true
            in 0x1F6EB..0x1F6EC -> true
            in 0x1F6F4..0x1F6FC -> true
            in 0x1F7E0..0x1F7EB -> true
            in 0x1F90C..0x1F93A -> true
            in 0x1F93C..0x1F945 -> true
            in 0x1F947..0x1F978 -> true
            in 0x1F97A..0x1F9CB -> true
            in 0x1F9CD..0x1F9FF -> true
            in 0x1FA70..0x1FAFF -> true
            else -> false
        }
    }

    /**
     * Split into extended grapheme clusters, for the emoji subset we care
     * about: ZWJ sequences, regional-indicator flag pairs, variation
     * selectors, keycaps and skin-tone modifiers.
     *
     * Hand-rolled rather than using `BreakIterator.getCharacterInstance()`.
     * That was the first implementation and it is **wrong for exactly the
     * cases this feature depends on** — measured on JDK 17, it reports
     * 🇯🇵 as 2 clusters, 👩‍💻 as 3 and 👍🏽 as 2, because the JDK's
     * BreakIterator implements legacy boundaries, not UAX #29 *extended*
     * grapheme clusters. Android's ICU gets those right, so a
     * BreakIterator-based version would pass on device and fail on the JVM —
     * i.e. the unit tests would be the only thing telling the truth, and only
     * by disagreeing with production. One implementation that behaves
     * identically in both places is worth more than the shared-library
     * shortcut.
     */
    fun graphemeClusters(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val cps = input.codePoints().toArray()
        var i = 0
        while (i < cps.size) {
            val start = i
            i++
            // A flag is exactly two regional indicators.
            if (isRegionalIndicator(cps[start]) &&
                i < cps.size && isRegionalIndicator(cps[i])
            ) {
                i++
            } else {
                // Absorb trailing modifiers, then any ZWJ-joined segment
                // (each of which may itself carry modifiers).
                i = absorbModifiers(cps, i)
                while (i < cps.size && cps[i] == ZWJ && i + 1 < cps.size) {
                    i += 2                     // the ZWJ and the joined base
                    i = absorbModifiers(cps, i)
                }
            }
            out.add(String(cps, start, i - start))
        }
        return out
    }

    private const val ZWJ = 0x200D

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Variation selectors, keycap, skin tones, and combining marks. */
    private fun absorbModifiers(cps: IntArray, from: Int): Int {
        var i = from
        while (i < cps.size) {
            val cp = cps[i]
            val isModifier = cp == 0xFE0E || cp == 0xFE0F ||   // variation selectors
                cp == 0x20E3 ||                                // combining keycap
                cp in 0x1F3FB..0x1F3FF ||                      // skin tones
                cp in 0x0300..0x036F ||                        // combining diacritics
                cp in 0xE0020..0xE007F                         // tag chars (subdivision flags)
            if (!isModifier) break
            i++
        }
        return i
    }
}
