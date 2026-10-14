package com.openminis.app.speech

/**
 * [T-android-voice-text-sanitizer] Cleans a text unit before it is handed to
 * TTS. Port of iOS `Providers/Voice/VoiceTextSanitizer.swift`.
 *
 * Without this, read-aloud pronounces raw Markdown — "star star important star
 * star", every table pipe, and whole URLs character by character — and stumbles
 * over emoji and box-drawing glyphs. It removes the syntax that shouldn't be
 * spoken while keeping the visible text and the punctuation that drives
 * prosody.
 *
 * Ordering matters and mirrors iOS: fenced code → inline code → links/images →
 * emphasis → line-leading block markers → leftover markers.
 *
 * DIVERGENCE FROM iOS (deliberate): iOS hardcodes the Chinese link phrases
 * "<host> 的链接" / "链接". Here the phrase is supplied by the caller via
 * [LinkPhrases] so it can come from `R.string` and follow the device locale —
 * the project requires localized user-facing strings, and TTS output is
 * user-facing. Defaults are English so the class stays usable (and unit
 * testable) without a Context.
 */
object VoiceTextSanitizer {

    /**
     * Localized wording for spoken link substitutions.
     *
     * @param linkToHost formats a known host; must contain a single `%s`.
     * @param bareLink used when no host can be extracted.
     */
    data class LinkPhrases(
        val linkToHost: String = "link to %s",
        val bareLink: String = "link",
    )

    private val DEFAULT_PHRASES = LinkPhrases()

    // [T-android-voice-sanitizer-regex-hoist] All patterns are compile-once
    // constants at object level. ReadAloudPlayer.enqueue runs sanitize() for
    // EVERY streamed sentence during a reply, and ~16 Regex literals were
    // being recompiled per sentence on that hot path. `Regex.replace` is
    // documented safe for concurrent use on a shared instance, and this object
    // is stateless, so object-level vals carry no extra risk.
    private val MULTI_SPACE_RE = Regex("[ \\t]{2,}")
    private val LINE_PAD_RE = Regex(" *\\n *")
    private val FENCED_CODE_RE = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE_RE = Regex("`([^`]+)`")
    private val MARKDOWN_LINK_RE = Regex("!?\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)")
    private val BOLD_STAR_RE = Regex("\\*\\*([^*]+)\\*\\*")
    private val BOLD_UNDER_RE = Regex("__([^_]+)__")
    private val ITALIC_STAR_RE = Regex("\\*([^*]+)\\*")
    private val UNDERSCORE_EMPH_RE = Regex("(?<!\\w)_([^_]+)_(?!\\w)")
    private val STRIKE_RE = Regex("~~([^~]+)~~")
    private val HEADING_RE = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val BLOCKQUOTE_RE = Regex("(?m)^\\s{0,3}>\\s?")
    private val BULLET_RE = Regex("(?m)^\\s{0,3}[-*+]\\s+")
    private val ORDERED_RE = Regex("(?m)^\\s{0,3}\\d+[.)]\\s+")
    private val TABLE_SEP_ROW_RE = Regex("(?m)^\\s*\\|?[-:| ]+\\|?\\s*$")
    private val TABLE_PIPE_RE = Regex("\\|")
    private val HRULE_RE = Regex("(?m)^\\s*([-*_])\\1{2,}\\s*$")
    private val LEFTOVER_UNDERSCORE_RE = Regex("(?<!\\w)_+|_+(?!\\w)")
    private val LEFTOVER_MARKER_RE = Regex("[*~`]+")
    private val BARE_URL_RE = Regex("https?://[^\\s)\\]]+")

    /**
     * Strip Markdown syntax and non-speakable glyphs, then tidy whitespace.
     * Returns the cleaned, speakable string — which may legitimately be empty
     * (e.g. a message that was nothing but a fenced code block), and callers
     * must treat empty as "nothing to speak" rather than speaking the original.
     */
    fun sanitize(text: String, phrases: LinkPhrases = DEFAULT_PHRASES): String {
        var s = stripMarkdown(text, phrases)
        // Any remaining bare URL in plain text → spoken phrase, so the reader
        // never voices a long, unstoppable URL.
        s = rewriteBareUrls(s, phrases)
        // Replace non-speakable glyphs with a space rather than deleting them,
        // so "word🔥word" doesn't fuse into one token.
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            sb.append(if (isSpeakable(cp)) String(Character.toChars(cp)) else " ")
            i += Character.charCount(cp)
        }
        // Collapse the whitespace the removals left behind.
        return sb.toString()
            .replace(MULTI_SPACE_RE, " ")
            .replace(LINE_PAD_RE, "\n")
            .trim()
    }

    /**
     * Remove the Markdown syntax that shouldn't be pronounced, keeping the
     * visible text.
     */
    private fun stripMarkdown(text: String, phrases: LinkPhrases): String {
        var s = text
        // Fenced code blocks are dropped entirely — reading code aloud is noise.
        s = s.replace(FENCED_CODE_RE, " ")
        // Inline `code` → keep the inner text.
        s = s.replace(INLINE_CODE_RE, "$1")
        // Links/images before emphasis, so a description containing * is safe.
        s = rewriteMarkdownLinks(s, phrases)
        // Emphasis: **x** __x__ *x* _x_ ~~x~~ → x
        s = s.replace(BOLD_STAR_RE, "$1")
        s = s.replace(BOLD_UNDER_RE, "$1")
        s = s.replace(ITALIC_STAR_RE, "$1")
        // Intra-word underscores (snake_case) must survive, hence the guards.
        s = s.replace(UNDERSCORE_EMPH_RE, "$1")
        s = s.replace(STRIKE_RE, "$1")
        // Line-leading block markers.
        s = s.replace(HEADING_RE, "")        // # headings
        s = s.replace(BLOCKQUOTE_RE, "")             // > blockquote
        s = s.replace(BULLET_RE, "")         // - bullet
        s = s.replace(ORDERED_RE, "")      // 1. ordered
        s = s.replace(TABLE_SEP_ROW_RE, " ") // table separator
        s = s.replace(TABLE_PIPE_RE, " ")                           // table pipes
        // Horizontal rules --- *** ___
        s = s.replace(HRULE_RE, " ")
        // Stray leftover emphasis markers.
        //
        // DIVERGENCE FROM iOS (bug fix): iOS strips `[*_~`]{1,}` unconditionally,
        // which also eats the underscores in identifiers — "read_image_file"
        // became "readimagefile", defeating the (?<!\w)_..._(?!\w) guard above.
        // Underscores are only stripped when NOT sitting between word
        // characters, so snake_case survives; the other markers are never
        // meaningful mid-identifier and stay unconditional.
        s = s.replace(LEFTOVER_UNDERSCORE_RE, "")
        s = s.replace(LEFTOVER_MARKER_RE, "")
        return s
    }

    /**
     * Rewrite `[desc](url)` / `![alt](url)`: a non-empty description is kept
     * verbatim; an empty one becomes the spoken link phrase.
     */
    private fun rewriteMarkdownLinks(text: String, phrases: LinkPhrases): String {
        return MARKDOWN_LINK_RE.replace(text) { m ->
            val desc = m.groupValues[1].trim()
            if (desc.isEmpty()) linkPhrase(m.groupValues[2], phrases) else desc
        }
    }

    /** Replace bare http(s) URLs in plain text with the spoken link phrase. */
    private fun rewriteBareUrls(text: String, phrases: LinkPhrases): String =
        BARE_URL_RE.replace(text) { m -> linkPhrase(m.value, phrases) }

    private fun linkPhrase(url: String, phrases: LinkPhrases): String {
        val host = hostOf(url)
        return if (host.isEmpty()) phrases.bareLink else phrases.linkToHost.format(host)
    }

    /** Host without a leading "www.", or "" when nothing usable can be parsed. */
    internal fun hostOf(url: String): String {
        var s = url
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        // Strip userinfo and port so "user@host:8080" reads as "host".
        s.indexOf('@').let { if (it >= 0) s = s.substring(it + 1) }
        s.indexOf(':').let { if (it >= 0) s = s.substring(0, it) }
        if (s.startsWith("www.")) s = s.substring(4)
        return s
    }

    /**
     * True when the code point should be spoken. Drops emoji, dingbats, box
     * drawing and other symbol ranges that a TTS engine either skips or
     * verbalizes awkwardly. Ranges mirror the iOS implementation.
     */
    private fun isSpeakable(cp: Int): Boolean {
        // Keep all ASCII: letters, digits, punctuation, whitespace.
        if (cp < 0x80) return true
        return when (cp) {
            in 0x200B..0x200F,        // zero-width / directional marks
            in 0xFE00..0xFE0F,        // variation selectors
            in 0x2190..0x21FF,        // arrows
            in 0x2300..0x23FF,        // misc technical
            in 0x2460..0x24FF,        // enclosed alphanumerics
            in 0x2500..0x257F,        // box drawing
            in 0x2580..0x259F,        // block elements
            in 0x25A0..0x25FF,        // geometric shapes
            in 0x2600..0x26FF,        // misc symbols
            in 0x2700..0x27BF,        // dingbats
            in 0x2B00..0x2BFF,        // misc symbols & arrows
            in 0x1F000..0x1FAFF,      // emoji / pictographs
            in 0xE0000..0xE007F,      // tags
            -> false
            0x20E3 -> false           // combining keycap
            else -> true
        }
    }
}
