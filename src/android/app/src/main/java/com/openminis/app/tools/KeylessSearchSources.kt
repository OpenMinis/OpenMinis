package com.openminis.app.tools

/**
 * [T-android-websearch-keyless-cn] Key-free domestic fallback sources for
 * [WebSearchTool]: Sogou → Bing RSS → Baidu. They run only after every
 * configured backend AND the DDG engine returned nothing — the exact
 * situation a mainland-China user with no API keys hits daily.
 *
 * All parsers are PURE (String → List<Result>) so they are JVM-testable
 * without network; the HTTP round-trip stays in [WebSearchTool.fetchUrl].
 * Markup fixtures in KeylessSearchSourcesTest were cut from real pages
 * fetched 2026-10 (sogou `vr-title` blocks, bing `format=rss` items,
 * baidu security-wall page).
 *
 * Robustness contract: a source whose markup changed or who served a
 * consent/security wall simply parses to an empty list and the chain
 * moves on. No source may throw.
 */
internal object KeylessSearchSources {

    /** Desktop UA: m.baidu/m.sogou markup differs and is more ad-heavy. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class Source(
        val id: String,
        val label: String,
        val urlFor: (String) -> String,
        val parse: (String, Int) -> List<WebSearchTool.Result>,
    )

    /** Chain order is the product decision: 搜狗 → Bing RSS → 百度. */
    val chain: List<Source> = listOf(
        Source(
            id = "sogou",
            label = "搜狗",
            urlFor = { q -> "https://www.sogou.com/web?query=${enc(q)}" },
            parse = { html, max -> parseSogou(html, max) },
        ),
        Source(
            id = "bing-rss",
            label = "Bing RSS",
            urlFor = { q -> "https://www.bing.com/search?q=${enc(q)}&format=rss" },
            parse = { xml, max -> parseBingRss(xml, max) },
        ),
        Source(
            id = "baidu",
            label = "百度",
            urlFor = { q -> "https://www.baidu.com/s?wd=${enc(q)}" },
            parse = { html, max -> parseBaidu(html, max) },
        ),
    )

    fun enc(query: String): String =
        java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8.name())

    /**
     * Headers for every keyless fetch. Desktop UA matters: m.sogou and
     * m.baidu serve different (more ad-heavy) markup to mobile clients,
     * and the parsers are pinned to the desktop shapes.
     */
    fun desktopHeaders(query: String): Map<String, String> = linkedMapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        // Referer suppresses some bot walls (sogou especially).
        "Referer" to "https://www.bing.com/",
    )

    /** Resolve a possibly-relative href against the source's own origin. */
    fun absoluteUrl(href: String, base: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:${href}"
        href.startsWith("/") -> base + href
        else -> href
    }

    // ------------------------------------------------------------------
    // Sogou
    // ------------------------------------------------------------------

    /**
     * Real shape (2026-10):
     * `<h3 class="vr-title"><a target="_blank" href="/link?url=…" …><em>K</em> title</a></h3>`
     * followed by a snippet div whose class contains `space-txt`,
     * `text-layout` or `star-wiki`. The `/link?url=` redirect is kept —
     * it is clickable and resolving it would cost one round-trip per hit.
     */
    fun parseSogou(html: String, max: Int): List<WebSearchTool.Result> {
        val out = ArrayList<WebSearchTool.Result>(max)
        val seen = HashSet<String>()
        for (chunk in splitH3(html)) {
            val a = ANCHOR_RE.find(chunk) ?: continue
            var url = unescape(a.groupValues[1]).trim()
            if (url.startsWith("/")) url = "https://www.sogou.com$url"
            if (!url.startsWith("http")) continue
            val title = stripTags(a.groupValues[2])
            if (title.isEmpty()) continue
            // Ad blocks: sogou marks sponsored results with sogou.com/sogou?
            // or an explicit ad class inside the same <h3> parent.
            if (url.contains("sogou.com/sogou?")) continue
            if (!seen.add(url)) continue
            val snippet = SNIPPET_RE.find(chunk)?.groupValues?.get(1)?.let { stripTags(it).take(200) }.orEmpty()
            out += WebSearchTool.Result(title, url, snippet)
            if (out.size >= max) break
        }
        return out
    }

    // ------------------------------------------------------------------
    // Bing RSS
    // ------------------------------------------------------------------

    /**
     * `https://www.bing.com/search?q=…&format=rss` → RSS 2.0 with one
     * `<item>` per hit; title/link/description may be plain text or CDATA.
     * The channel header also carries `<title>`/`<link>` (and an `<image>`
     * block repeating them), so only `<item>` sections are parsed.
     */
    fun parseBingRss(xml: String, max: Int): List<WebSearchTool.Result> {
        val out = ArrayList<WebSearchTool.Result>(max)
        val seen = HashSet<String>()
        for (item in ITEM_RE.findAll(xml)) {
            val body = item.groupValues[1]
            val title = tag(body, "title") ?: continue
            val link = tag(body, "link") ?: continue
            if (!link.startsWith("http")) continue
            if (!seen.add(link)) continue
            val description = tag(body, "description").orEmpty().take(200)
            out += WebSearchTool.Result(title, link, description)
            if (out.size >= max) break
        }
        return out
    }

    /** First `<name>…</name>` inside [block], CDATA unwrapped, tags stripped. */
    private fun tag(block: String, name: String): String? {
        val m = tagRegex(name).find(block) ?: return null
        val raw = m.groupValues[1].trim()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
        val text = stripTags(raw)
        return text.ifEmpty { null }
    }

    /**
     * [T-hoisted-regex] `<name>…</name>` is built per call, but the name set is
     * tiny (title/link/description) and fixed by the RSS shape, so the compiled
     * patterns are cached instead of rebuilt for every item of every response.
     */
    private val tagRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun tagRegex(name: String): Regex = tagRegexCache.getOrPut(name) {
        Regex("""<$name>([\s\S]*?)</$name>""", RegexOption.IGNORE_CASE)
    }

    // ------------------------------------------------------------------
    // Baidu
    // ------------------------------------------------------------------

    /**
     * Desktop result shape: `<h3 class="t|c-title|c-gap-top-small…"><a
     * href="http://www.baidu.com/link?url=…" …>title<em>kw</em></a></h3>`,
     * snippet in a `c-abstract` / `content-right_…` span. Datacenter and
     * mobile IPs very often get the `百度安全验证` interstitial instead —
     * detected explicitly so the chain moves on instead of parsing garbage.
     */
    fun parseBaidu(html: String, max: Int): List<WebSearchTool.Result> {
        if (html.contains("百度安全验证") || html.contains("wappass.baidu.com")) return emptyList()
        val out = ArrayList<WebSearchTool.Result>(max)
        val seen = HashSet<String>()
        for (chunk in splitH3(html)) {
            val a = ANCHOR_RE.find(chunk) ?: continue
            val url = unescape(a.groupValues[1]).trim()
            if (!url.startsWith("http")) continue
            // Baidu's own chrome (news tabs, passport, tieba promos) leaks
            // into <h3> occasionally; organic hits redirect via /link?url=.
            if (!url.contains("baidu.com/link?url=") && url.contains("baidu.com")) continue
            val title = stripTags(a.groupValues[2])
            if (title.isEmpty()) continue
            if (!seen.add(url)) continue
            val snippet = BAIDU_SNIPPET_RE.find(chunk)?.groupValues?.get(1)
                ?.let { stripTags(it).take(200) }.orEmpty()
            out += WebSearchTool.Result(title, url, snippet)
            if (out.size >= max) break
        }
        return out
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Split on `<h3` keeping the body of each heading block. */
    fun splitH3(html: String): List<String> = html.split("<h3").drop(1)

    private val SNIPPET_RE = Regex(
        """class="[^"]*(?:space-txt|text-layout|star-wiki)[^"]*"[^>]*>([\s\S]*?)</(?:div|p|span)>""",
        RegexOption.IGNORE_CASE,
    )

    private val BAIDU_SNIPPET_RE = Regex(
        """class="[^"]*(?:c-abstract|content-right)[^"]*"[^>]*>([\s\S]*?)</(?:span|div|p)>""",
        RegexOption.IGNORE_CASE,
    )

    // [T-hoisted-regex] Constant patterns were compiled per call — once per
    // <h3> chunk of every search page. Compiled once, reused.
    private val ANCHOR_RE = Regex("""<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val ITEM_RE = Regex("""<item>([\s\S]*?)</item>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG_RE = Regex("<[^>]*>")
    private val WS_RE = Regex("\\s+")
    private val DECIMAL_ENTITY_RE = Regex("&#(\\d+);")
    private val HEX_ENTITY_RE = Regex("&#x([0-9a-fA-F]+);")

    fun stripTags(raw: String): String =
        unescape(HTML_TAG_RE.replace(raw, " ")).let { WS_RE.replace(it, " ").trim() }

    /** Common named entities + decimal/hex numeric ones. */
    fun unescape(raw: String): String {
        if (!raw.contains('&')) return raw
        var s = raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&hellip;", "…")
            .replace("&middot;", "·")
        s = DECIMAL_ENTITY_RE.replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp ->
                runCatching { String(Character.toChars(cp)) }.getOrNull()
            } ?: m.value
        }
        s = HEX_ENTITY_RE.replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp ->
                runCatching { String(Character.toChars(cp)) }.getOrNull()
            } ?: m.value
        }
        // &amp; last so &amp;lt; decodes to literal "&lt;" text, not "<".
        return s.replace("&amp;", "&")
    }
}
