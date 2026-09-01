package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [T-android-inapp-theme-popups #187] The KaTeX bitmap cache must be keyed by
 * theme.
 *
 * `KaTeXRendererCache` stores a rendered BITMAP whose glyphs are baked
 * light-on-dark or dark-on-light. The key was `(latex, displayMode)` only, and
 * nothing evicts the cache when the user flips the in-app theme —
 * `KaTeXRendererCache.evictAll()` is wired to memory pressure
 * (MinisApp.onTrimMemory), not to `theme_mode`. So the composable re-read the
 * colour, computed the same key, and got the PREVIOUS theme's bitmap back: a
 * formula kept its old ink until the entry happened to be evicted.
 *
 * Fixing the colour source without fixing the key would have looked correct in
 * a fresh process and wrong for every user who flips the theme in a live one,
 * which is the case the bug report describes.
 */
class KaTeXCacheKeyThemeTest {

    @Test
    fun `light and dark keys differ for the same formula`() {
        val light = KaTeXRendererCache.cacheKey("E = mc^2", displayMode = true, isDark = false)
        val dark = KaTeXRendererCache.cacheKey("E = mc^2", displayMode = true, isDark = true)
        assertNotEquals(
            "a theme flip must MISS the cache — same key means the previous " +
                "theme's baked-in bitmap is served (light=$light dark=$dark)",
            light,
            dark,
        )
    }

    @Test
    fun `key still separates display from inline`() {
        assertNotEquals(
            "display and inline render differently and must not share an entry",
            KaTeXRendererCache.cacheKey("x^2", displayMode = true, isDark = true),
            KaTeXRendererCache.cacheKey("x^2", displayMode = false, isDark = true),
        )
    }

    @Test
    fun `key is stable for identical inputs`() {
        assertEquals(
            "same inputs must hit the same entry, or the cache never pays off",
            KaTeXRendererCache.cacheKey("\\sum_{i=1}^{n} i", displayMode = true, isDark = false),
            KaTeXRendererCache.cacheKey("\\sum_{i=1}^{n} i", displayMode = true, isDark = false),
        )
    }

    @Test
    fun `theme marker cannot collide with formula text`() {
        // The marker is a prefix, so a formula that literally starts with the
        // marker text must not be able to impersonate the other theme's key.
        assertNotEquals(
            KaTeXRendererCache.cacheKey("k:x", displayMode = true, isDark = false),
            KaTeXRendererCache.cacheKey("x", displayMode = true, isDark = true),
        )
    }
}
