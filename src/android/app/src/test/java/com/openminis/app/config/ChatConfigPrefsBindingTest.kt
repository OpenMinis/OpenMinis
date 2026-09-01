package com.openminis.app.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-config-prefs-mismatch] Every `chat.*` builtin that mirrors a
 * Settings-UI toggle must bind to the SAME SharedPreferences store and the
 * SAME key the UI reads.
 *
 * The bug this pins: `minis-config set chat.returnKey '"send"'` reported
 * `ok: true` with `old: "newline", new: "send"` — and nothing changed. The
 * field wrote `minis_settings/return_key_behavior` while
 * `AppearanceScreen.returnKeySendsMessage` reads
 * `appearance_prefs/returnKeyBehavior`. Two independent values: the Settings
 * screen still showed Newline and Enter still inserted a newline. Confirmed on
 * a Mate Pad, where both files ended up holding a separate copy of the flag.
 *
 * This is a SOURCE-level check rather than a behavioural one on purpose. The
 * Prefs*Field classes need a real android SharedPreferences, so a JVM test
 * cannot exercise them — but the defect is entirely visible in the
 * registration site, and the thing that was missing was any check at all.
 * Binding by shared `KEY_*` constant (as `chat.toolPreview` already did) is
 * what makes the two sides impossible to drift apart.
 */
class ChatConfigPrefsBindingTest {

    private fun source(relative: String): String {
        // Tests run with the module dir as CWD.
        val f = File("src/main/java/com/openminis/app/$relative")
        assertTrue("source not found: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private val builtins by lazy { source("config/ConfigBuiltins.kt") }

    /** The `r.register(...)` block for a given config path. */
    private fun registrationFor(path: String): String {
        val marker = "path = \"$path\""
        val at = builtins.indexOf(marker)
        assertTrue("no builtin registers $path", at >= 0)
        // The registration ends at the closing of its field constructor.
        val end = builtins.indexOf("\n        )", at)
        return builtins.substring(at, if (end > at) end else at + 600)
    }

    // ─── The reported regression ─────────────────────────────────────────

    @Test
    fun `chat_returnKey binds to the appearance prefs the UI reads`() {
        val reg = registrationFor("chat.returnKey")
        assertTrue(
            "chat.returnKey must use appearancePrefs — writing minis_settings " +
                "makes `minis-config set` a silent no-op:\n$reg",
            reg.contains("prefs = appearancePrefs"),
        )
        assertTrue(
            "chat.returnKey must bind the shared KEY_RETURN_KEY_BEHAVIOR " +
                "constant rather than a hand-typed key:\n$reg",
            reg.contains("KEY_RETURN_KEY_BEHAVIOR"),
        )
    }

    @Test
    fun `chat_keepScreenAwake binds to the appearance prefs the UI reads`() {
        val reg = registrationFor("chat.keepScreenAwake")
        assertTrue("must use appearancePrefs:\n$reg", reg.contains("prefs = appearancePrefs"))
        assertTrue("must use KEY_KEEP_SCREEN_AWAKE:\n$reg", reg.contains("KEY_KEEP_SCREEN_AWAKE"))
    }

    @Test
    fun `chat_toolPreview stays correct`() {
        // This one was already right and is the pattern the others now follow;
        // it must not regress the other way.
        val reg = registrationFor("chat.toolPreview")
        assertTrue(reg.contains("prefs = appearancePrefs"))
        assertTrue(reg.contains("KEY_TOOL_PREVIEW"))
    }

    // ─── The general rule ────────────────────────────────────────────────

    @Test
    fun `no builtin writes a UI-owned key into the wrong prefs store`() {
        // The real defect is not a hand-typed key — it is a key that resolves
        // to a DIFFERENT store than the UI reads. `appearance.theme` types
        // "theme_mode" literally but binds a prefs handle already opened on
        // appearance_prefs, so it works; chat.returnKey typed its key AND
        // pointed at minis_settings, so it silently diverged.
        //
        // Rule: if a registration names a key AppearanceScreen also defines,
        // its `prefs =` must resolve to the appearance store.
        val appearance = source("ui/settings/AppearanceScreen.kt")
        val uiKeyValues = Regex("""const val KEY_\w+ = "([^"]+)"""")
            .findAll(appearance).map { it.groupValues[1] }.toSet()

        // `prefs` is re-declared per registerX() function, each opening a
        // DIFFERENT store, so the handle NAME alone says nothing. Walk the file
        // in order and track which store the nearest preceding declaration
        // opened — that is the one a `prefs = prefs` reference resolves to.
        val events = Regex(
            """val (\w+) = context\.getSharedPreferences\(\s*(?:"([^"]+)"|([\w.]+))""" +
                """|prefs = (\w+),\s*\n\s*key = (?:"([^"]+)"|[\w.]+\.(KEY_\w+))""",
        ).findAll(builtins)

        val storeOf = mutableMapOf<String, String>()
        val offenders = mutableListOf<String>()
        for (m in events) {
            val declName = m.groupValues[1]
            if (declName.isNotEmpty()) {
                // Literal store name, or a constant reference (PREF_APPEARANCE).
                val literal = m.groupValues[2]
                val viaConst = m.groupValues[3]
                storeOf[declName] =
                    if (literal.isNotEmpty()) literal
                    else if (viaConst.endsWith("PREF_APPEARANCE")) "appearance_prefs"
                    else viaConst
                continue
            }
            val handle = m.groupValues[4]
            val literalKey = m.groupValues[5]
            // Only a hand-typed key can silently diverge; a shared KEY_*
            // constant is exactly the fix, so it is never an offender.
            if (literalKey.isEmpty()) continue
            if (literalKey in uiKeyValues && storeOf[handle] != "appearance_prefs") {
                offenders.add("key=$literalKey via prefs=$handle -> ${storeOf[handle]}")
            }
        }
        assertEquals(
            "these builtins write a UI-owned key into a store the UI never " +
                "reads, so `minis-config set` silently no-ops: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the appearance prefs store is resolved from the shared constant`() {
        // Hard-coding "appearance_prefs" here would drift the same way the key
        // did. It must come from PREF_APPEARANCE.
        assertTrue(
            "appearancePrefs must be built from PREF_APPEARANCE",
            builtins.contains("com.openminis.app.ui.settings.PREF_APPEARANCE"),
        )
    }
}
