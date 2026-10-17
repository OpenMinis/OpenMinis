package com.openminis.app.security

/**
 * SEC-3: linear glob matching for security-gate rules.
 *
 * The old implementation compiled a fresh `Regex` per rule per tool call and
 * expanded `*` to `.*`. A rule with a handful of stars over a long command is
 * a classic backtracking bomb, and compiling on every call was pure overhead.
 * These matchers are O(pattern × text) worst case with no regex engine, no
 * allocation per call, and hard input caps.
 */
internal object RulePattern {

    private const val MAX_PATTERN_CHARS = 512
    private const val MAX_TARGET_CHARS = 8_192

    /**
     * Whole-unit prefix match for an allow pattern: `git` covers `git status`
     * but not `grep category`; `cat*` covers `cat notes.md`.
     */
    fun allowMatches(pattern: String, unit: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (p == "*") return true
        if (p.length > MAX_PATTERN_CHARS) return false
        val target = unit.trim()
        if (target.isEmpty() || target.length > MAX_TARGET_CHARS) return false
        return globPrefix(p, target)
    }

    /**
     * Deny/legacy rule match: full-string glob plus the historical
     * `target.contains(pattern.trim('*'))` fallback.
     *
     * A target over the cap fails closed: a deny rule must never be bypassed
     * by padding the command past the matcher's budget.
     */
    fun denyMatches(pattern: String, target: String): Boolean {
        if (pattern.isBlank()) return true
        if (target.length > MAX_TARGET_CHARS) return true
        val p = pattern.trim()
        if (p.length > MAX_PATTERN_CHARS) return target.contains(p)
        return globFull(p, target) || target.contains(p.trim('*'))
    }

    /** Pattern must match [text] from 0; after it, [text] must end or start whitespace. */
    private fun globPrefix(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var starMatch = 0
        while (true) {
            if (p == pattern.length) {
                if (t == text.length || text[t].isWhitespace()) return true
                // A trailing `*` may still need to consume the unit tail.
                if (star >= 0 && starMatch < text.length) {
                    starMatch++
                    p = star + 1
                    t = starMatch
                    continue
                }
                return false
            }
            val pc = pattern[p]
            if (pc == '*') {
                star = p
                p++
                starMatch = t
                continue
            }
            if (t < text.length && (pc == '?' || pc == text[t])) {
                p++
                t++
                continue
            }
            if (star >= 0 && starMatch < text.length) {
                starMatch++
                p = star + 1
                t = starMatch
                continue
            }
            return false
        }
    }

    /** Whole-string glob match (`*` any run, `?` one char). */
    private fun globFull(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var starMatch = 0
        while (t < text.length) {
            if (p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t])) {
                p++
                t++
                continue
            }
            if (p < pattern.length && pattern[p] == '*') {
                star = p
                p++
                starMatch = t
                continue
            }
            if (star >= 0 && starMatch < text.length) {
                starMatch++
                p = star + 1
                t = starMatch
                continue
            }
            return false
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }
}
