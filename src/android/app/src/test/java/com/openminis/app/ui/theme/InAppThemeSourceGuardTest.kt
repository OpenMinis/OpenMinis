package com.openminis.app.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-inapp-theme-popups #187] Source guard: nothing under `ui/` may ask
 * Android whether the SYSTEM is in dark mode.
 *
 * Minis resolves its own theme from the `theme_mode` preference (0=System,
 * 1=Light, 2=Dark) in MainActivity and hands the answer to `MinisTheme`, which
 * publishes it as `ChatPalette.isDark` (read via [ChatColors.isDark]).
 * `isSystemInDarkTheme()` tracks only the OS setting, so the two DISAGREE
 * exactly when the user has overridden the theme in-app — system light + Minis
 * dark being the reported case. A component reading the system API then paints
 * light chrome inside a dark app (GH OpenMinis#187: pop-up menus followed the
 * system theme; the web-preview sheets and KaTeX formulas did the same).
 *
 * This is enforced mechanically because it has now happened twice. The April
 * fix (8154d9b05, "T126-fix") introduced `ChatPalette.isDark` and converted the
 * two call sites it was chasing, but left twelve others across six files; those
 * are what #187 reported four months later. A grep-able rule is what stops a
 * third round — a new component picking `isSystemInDarkTheme()` because it is
 * the obvious API now fails here instead of shipping.
 *
 * Two files are allowed to call it, and only they:
 *   • MainActivity — the "follow the system" branch of the theme_mode `when`.
 *     This is the one place the system value is the correct answer.
 *   • Theme.kt — the `darkTheme` default parameter, for previews/tests that
 *     invoke MinisTheme without an explicit value.
 *
 * If a new file legitimately needs the system value, add it here WITH the
 * reason; the point is that the exception becomes a deliberate, reviewed act.
 */
class InAppThemeSourceGuardTest {

    private val allowed = setOf(
        "MainActivity.kt",
        "Theme.kt",
    )

    @Test
    fun `ui code reads the in-app theme, not the system theme`() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue(
            "source root not found — this test locates sources relative to the " +
                "module dir (cwd=${File(".").absolutePath})",
            root.isDirectory,
        )

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowed }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    // Skip comments: the correct pattern is documented by NAMING
                    // the wrong one (see ChatToolDetailUI's T126-fix note), and
                    // those references must not trip the guard.
                    val code = line.substringBefore("//").substringBefore("/*")
                    if (code.contains("isSystemInDarkTheme")) {
                        offenders += "${f.path}:${i + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            buildString {
                append("isSystemInDarkTheme() tracks the OS setting, not the user's ")
                append("in-app theme (Settings → Appearance). Use ChatColors.isDark ")
                append("so the component follows the theme the app actually resolved.\n")
                append("See GH OpenMinis#187 and commit 8154d9b05.\n\n")
                offenders.forEach { append("  ").append(it).append('\n') }
            },
            offenders.isEmpty(),
        )
    }
}
