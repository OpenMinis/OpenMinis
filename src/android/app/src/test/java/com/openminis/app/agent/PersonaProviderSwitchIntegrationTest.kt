package com.openminis.app.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [T-persona-provider-switch] Field report: mid-session the user switched the
 * chat from provider A's model to provider B's model (two different provider
 * instances, each with its own per-provider persona selected in Settings →
 * Soul), and the injected personality stayed on provider A's prompt.
 *
 * The injection chain is: buildSystemPrompt → _activeEntryId → entry's
 * providerInstanceId → SystemPromptBuilder.identitySection →
 * PersonaPromptLibrary.resolve → providerSelections[providerInstanceId].
 * Every hop was read as correct in isolation; this test pins the END-TO-END
 * behaviour with a real (Robolectric) filesystem, including the single-slot
 * identity cache in SystemPromptBuilder — the one shared mutable state on
 * the path that a pure-logic test can never catch drifting.
 *
 * If this test passes, the injection core is provider-faithful and the field
 * report points at the VM layer (_activeEntryId timing) or at a session
 * override the user forgot — NOT at this library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PersonaProviderSwitchIntegrationTest {

    private val markerX = "PERSONA-X-MARKER"
    private val markerY = "PERSONA-Y-MARKER"

    /** Seed a persona library: builtin + px (mapped to provA) + py (provB). */
    private fun seed(context: Context) {
        val root = PersonaPromptLibrary.root(context)
        PersonaPromptLibrary.filesDir(context).mkdirs()
        // mkdirs on the leaf leaves the parent chain created; be explicit.
        root.mkdirs()
        PersonaPromptLibrary.indexFile(context).writeText(
            """
            {
              "prompts": [
                {"id": "soul", "fileName": "SOUL.md", "storedName": "soul.md", "builtin": true},
                {"id": "px", "fileName": "persona-x.md", "storedName": "px.md", "builtin": false},
                {"id": "py", "fileName": "persona-y.md", "storedName": "py.md", "builtin": false}
              ],
              "selectedId": "soul",
              "providerSelections": {"provA": "px", "provB": "py"}
            }
            """.trimIndent(),
        )
        File(PersonaPromptLibrary.filesDir(context), "px.md").writeText(markerX)
        File(PersonaPromptLibrary.filesDir(context), "py.md").writeText(markerY)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `switching provider mid-session injects the new provider persona`() {
        val context = context()
        seed(context)
        val session = "s-switch-1"

        // Turn 1..n on provider A.
        val asA = SystemPromptBuilder.identitySection(context, "provA", session)
        assertTrue("provider A must inject its own persona", asA.contains(markerX))

        // User switches the chat to provider B's model; next turn builds the
        // prompt with B's instance id.
        val asB = SystemPromptBuilder.identitySection(context, "provB", session)
        assertTrue(
            "after switching to provider B the injected persona must be B's",
            asB.contains(markerY),
        )
        assertFalse("A's persona must not leak into B's turn", asB.contains(markerX))
    }

    @Test
    fun `switching back and forth survives the single-slot cache churn`() {
        val context = context()
        seed(context)
        val session = "s-switch-2"

        val a1 = SystemPromptBuilder.identitySection(context, "provA", session)
        val b1 = SystemPromptBuilder.identitySection(context, "provB", session)
        val a2 = SystemPromptBuilder.identitySection(context, "provA", session)
        val b2 = SystemPromptBuilder.identitySection(context, "provB", session)

        assertTrue(a1.contains(markerX))
        assertTrue(b1.contains(markerY))
        assertTrue("revisit of A must not serve B's cached value", a2.contains(markerX))
        assertFalse(a2.contains(markerY))
        assertTrue("revisit of B must not serve A's cached value", b2.contains(markerY))
        assertFalse(b2.contains(markerX))
    }

    @Test
    fun `mid-session provider selection edit invalidates the cached identity`() {
        val context = context()
        seed(context)
        val session = "s-switch-3"

        val before = SystemPromptBuilder.identitySection(context, "provA", session)
        assertTrue(before.contains(markerX))

        // User re-maps provider A to persona Y while the chat is alive.
        PersonaPromptLibrary.setProviderPrompt(context, "provA", "py")

        val after = SystemPromptBuilder.identitySection(context, "provA", session)
        assertTrue(
            "a provider selection edit must be visible on the very next turn",
            after.contains(markerY),
        )
        assertFalse(after.contains(markerX))
    }

    @Test
    fun `a session override wins over both providers until cleared`() {
        val context = context()
        seed(context)
        val session = "s-switch-4"
        PersonaPromptLibrary.writeSessionOverride(context, session, "SESSION-ONLY-MARKER")

        val asA = SystemPromptBuilder.identitySection(context, "provA", session)
        val asB = SystemPromptBuilder.identitySection(context, "provB", session)
        assertTrue("session override must win over provider A", asA.contains("SESSION-ONLY-MARKER"))
        assertTrue("session override must win over provider B", asB.contains("SESSION-ONLY-MARKER"))
        assertFalse(asA.contains(markerX))
        assertFalse(asB.contains(markerY))

        // Clearing the override restores provider-faithful injection — the
        // "stuck on the old persona" report is only explained by an override
        // file that still exists, so prove the unstick path works too.
        PersonaPromptLibrary.writeSessionOverride(context, session, "")
        val restored = SystemPromptBuilder.identitySection(context, "provB", session)
        assertTrue(restored.contains(markerY))
        assertFalse(restored.contains("SESSION-ONLY-MARKER"))
    }
}
