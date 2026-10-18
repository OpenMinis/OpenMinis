package com.openminis.app.tools

/**
 * [T-brand-cjk-aliases] Chinese brand aliases for ModelBrandResolver's keyword
 * table. Group-chat speakers are frequently named in Chinese ("智谱清言",
 * "通义千问"); without these the scan misses and the avatar degrades to an
 * initial letter. Merged from the retired GroupChat.vendorKey alias table —
 * one keyword table, one truth (PR70 lesson).
 *
 * Lives in `tools/` (not `ui/`) on purpose: these are MATCH DATA consumed by
 * the brand scan, not user-facing copy — the CJK hardcoded-string ratchet
 * guards `ui/` against untranslated literals, and brand keywords are neither
 * translatable nor display text.
 */
internal object BrandKeywordSources {
    val CJK_ALIASES: List<Pair<String, String>> = listOf(
        "深度求索" to "deepseek",
        "通义" to "qwen",
        "千问" to "qwen",
        "月之暗面" to "kimi",
        "豆包" to "doubao",
        "混元" to "hunyuan",
        "文心" to "wenxin",
        "百川" to "baichuan",
        "海螺" to "hailuo",
        "智谱" to "zhipu",
    )
}
