package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-brand-accent-color] Brand accent used for the usage-page share bars.
 *
 * Two problems this solves, both invisible in a screenshot of a single theme:
 *
 *  1. **Black brands vanish on dark.** Kimi, OpenAI, Grok, Qwen and Hailuo all
 *     ship a pure-black mark. Reusing that literal as a 3dp bar fill makes the
 *     bar disappear against a dark surface — the number is there, the visual is
 *     not. Dark mode therefore uses per-brand LIGHT variants chosen to stay
 *     recognisably on-brand (Kimi keeps its cool blue-grey family, Qwen borrows
 *     its own purple, Hailuo its teal).
 *  2. **Brand must NOT follow the wallpaper.** In dynamic-colour mode the scheme
 *     recolours surfaces and accents; a brand bar that followed it would paint
 *     every vendor the same hue and destroy the only cue telling rows apart.
 *     Brand accents are deliberately identical across the static and dynamic
 *     paths, keyed only on light/dark.
 *
 * Unknown slugs have no brand colour — callers get a deterministic hash hue so
 * two unrecognised rows still differ from each other.
 */
internal object BrandAccent {
    /** Light-theme brand accents: the vendors' own primary hues. */
    private val LIGHT: Map<String, Color> = mapOf(
        "zhipu" to Color(0xFF3859FF),
        "deepseek" to Color(0xFF4D6BFE),
        "claude" to Color(0xFFD97757),
        "gemini" to Color(0xFF3186FF),
        "wenxin" to Color(0xFF012F8D),
        "minimax" to Color(0xFFE2167E),
        "hailuo" to Color(0xFF1FA2B8),
        "kimi" to Color(0xFF16161A),
        "openai" to Color(0xFF16161A),
        "grok" to Color(0xFF16161A),
        "qwen" to Color(0xFF16161A),
        "moonshot" to Color(0xFF16161A),
        "meta" to Color(0xFF0668E1),
        "mistral" to Color(0xFFFF7000),
        "cohere" to Color(0xFF39594D),
        "perplexity" to Color(0xFF20808D),
        "openrouter" to Color(0xFF00A3C4),
        "groq" to Color(0xFFF55036),
        "baichuan" to Color(0xFF1A6DFF),
        "hunyuan" to Color(0xFF0052D9),
    )

    /**
     * Dark-theme overrides. Only NEAR-BLACK brands strictly need one — the
     * coloured brands keep their hue and simply read fine on a dark surface;
     * a few are nudged brighter so a 3dp bar does not look muddy. Kept as a
     * separate map so the light table stays the vendors' literal colours.
     */
    private val DARK_OVERRIDES: Map<String, Color> = mapOf(
        "kimi" to Color(0xFF8FA8C8),
        "openai" to Color(0xFFC8C8D2),
        "grok" to Color(0xFFA8AEC0),
        "qwen" to Color(0xFF9B8CF0),
        "hailuo" to Color(0xFF7FC8D8),
        "moonshot" to Color(0xFF8FA8C8),
        "cohere" to Color(0xFF6FA893),
        "mistral" to Color(0xFFFF9A4D),
        "wenxin" to Color(0xFF5B7FD4),
        "hunyuan" to Color(0xFF4D8AE8),
        "baichuan" to Color(0xFF5B8CFF),
        "meta" to Color(0xFF4C8DF0),
        "groq" to Color(0xFFFF7A66),
        "minimax" to Color(0xFFFF5AA5),
        "zhipu" to Color(0xFF6D8BFF),
        "deepseek" to Color(0xFF7A93FF),
        "claude" to Color(0xFFE89070),
        "gemini" to Color(0xFF6FA8FF),
    )

    /** Palette for brands with no known accent (deterministic per name). */
    private val LIGHT_HASH = listOf(
        Color(0xFF5B8DEF),
        Color(0xFF3FA36A),
        Color(0xFFD4654A),
        Color(0xFF8A6AD6),
        Color(0xFFC48A2A),
        Color(0xFF3A9CA8),
    )

    private val DARK_HASH = listOf(
        Color(0xFF8AB4F8),
        Color(0xFF7BC98F),
        Color(0xFFE89478),
        Color(0xFFB79CE8),
        Color(0xFFE4B65C),
        Color(0xFF6FC4D0),
    )

    /** Brand accent for a lobe slug, or null when the slug has no entry. */
    fun of(slug: String?, dark: Boolean): Color? {
        if (slug.isNullOrBlank()) return null
        if (dark) DARK_OVERRIDES[slug]?.let { return it }
        return LIGHT[slug]
    }

    /**
     * Deterministic accent for a row with no resolvable brand. The SAME name
     * always yields the same hue, so a model never changes colour between
     * recompositions, theme toggles or app launches (the offline-hash rule the
     * avatar fallback already follows).
     */
    fun fallback(name: String, dark: Boolean): Color {
        val palette = if (dark) DARK_HASH else LIGHT_HASH
        return palette[name.hashCode().ushr(1) % palette.size]
    }

    /**
     * The composed entry point: brand accent when the model resolves to a known
     * logo, otherwise the deterministic hash hue.
     */
    fun forModel(modelId: String?, displayName: String?, dark: Boolean): Color {
        val slug = ModelBrandResolver.resolveExact(modelId, displayName)
        return of(slug, dark) ?: fallback(modelId ?: displayName ?: "?", dark)
    }
}

/**
 * Theme-aware brand accent for a model row.
 *
 * [ChatColors.isDark] follows the app's RESOLVED theme — the in-app override
 * (Settings → Appearance) included — and is the single source of truth the
 * rest of the UI already reads. A system-level dark check would miss a user
 * who forces dark in-app while the system stays light, painting light brand
 * accents on a dark surface.
 */
@Composable
internal fun brandAccentFor(modelId: String?, displayName: String?): Color {
    val dark = ChatColors.isDark
    return remember(modelId, displayName, dark) {
        BrandAccent.forModel(modelId, displayName, dark)
    }
}
