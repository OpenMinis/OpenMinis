package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-devsuite-uncheck-converge] The assembly dialog's checkbox is the TARGET
 * state: checking installs, unchecking removes. These pure checks pin the
 * two halves of that contract —
 *  * [DevSuiteCatalog.checkboxEditable]: the toggle is only offered when the
 *    assembly can deliver both directions (required locked on; installed
 *    apt-only rows locked because shared rootfs packages are not removable
 *    by an uncheck);
 *  * [DevSuiteInstaller.convergeUninstallTargets]: an assembly removes the
 *    installed components the user unchecked instead of silently skipping
 *    them — the old behavior pre-checked them again on the next dialog
 *    open ("已安装插件保持勾选无法取消勾选").
 */
class DevSuiteConvergeTest {

    private fun bundle(vararg components: DevComponent) =
        DevBundle(
            id = "test",
            // Never resolves resources; real keys keep the stub constructing
            // the same shape as production call sites (see DevSuiteOrderTest).
            nameRes = com.openminis.app.R.string.dev_suite_online_header,
            summaryRes = com.openminis.app.R.string.dev_suite_online_footer,
            components = components.toList(),
        )

    private fun comp(
        id: String,
        required: Boolean = false,
        purgePaths: List<String> = emptyList(),
        purgeLinks: List<String> = emptyList(),
    ) = DevComponent(
        id = id, name = id, description = "",
        isRequired = required,
        checkCommand = "true",
        purgePaths = purgePaths,
        purgeLinks = purgeLinks,
    )

    // ---- checkboxEditable: when is the toggle honest? ----

    @Test
    fun `required rows are locked regardless of state`() {
        // The old expression `!isRequired || checked` was always true because
        // required rows are always checked — the lock never engaged and taps
        // bounced back. Required must be false in every state combination.
        assertFalse(DevSuiteCatalog.checkboxEditable(isRequired = true, installed = false, purgeable = false))
        assertFalse(DevSuiteCatalog.checkboxEditable(isRequired = true, installed = false, purgeable = true))
        assertFalse(DevSuiteCatalog.checkboxEditable(isRequired = true, installed = true, purgeable = false))
        assertFalse(DevSuiteCatalog.checkboxEditable(isRequired = true, installed = true, purgeable = true))
    }

    @Test
    fun `not installed rows are always toggleable`() {
        assertTrue(DevSuiteCatalog.checkboxEditable(isRequired = false, installed = false, purgeable = false))
        assertTrue(DevSuiteCatalog.checkboxEditable(isRequired = false, installed = false, purgeable = true))
    }

    @Test
    fun `installed purgeable rows are toggleable - uncheck means remove`() {
        // flutter / jadx / rust shape: purgePaths declared, so the assembly
        // can deliver the unchecked direction via uninstall().
        assertTrue(DevSuiteCatalog.checkboxEditable(isRequired = false, installed = true, purgeable = true))
    }

    @Test
    fun `installed apt-only rows are locked - uncheck cannot remove shared packages`() {
        // rg / fd / fzf / bat / qemu shape: no purge targets, apt packages
        // shared with the whole rootfs. Unchecking would be a visual no-op
        // (status flips, probe re-passes, next open pre-checks again), so the
        // toggle is not offered. Mirrors the 移除 button's visibility rule.
        assertFalse(DevSuiteCatalog.checkboxEditable(isRequired = false, installed = true, purgeable = false))
    }

    // ---- convergeUninstallTargets: what does an assembly remove? ----

    @Test
    fun `unchecked optional components are removal targets`() {
        val b = bundle(
            comp("core", required = true),
            comp("flutter", purgePaths = listOf("/opt/flutter")),
            comp("jadx", purgeLinks = listOf("jadx")),
        )
        val targets = DevSuiteInstaller.convergeUninstallTargets(b, setOf("core", "jadx"))
        assertEquals(listOf("flutter"), targets.map { it.id })
    }

    @Test
    fun `required components are never removal targets`() {
        val b = bundle(comp("core", required = true), comp("extra"))
        // Even a caller that somehow omits the required id must not cause the
        // assembly to uninstall the bundle's own foundation.
        val targets = DevSuiteInstaller.convergeUninstallTargets(b, emptySet())
        assertEquals(listOf("extra"), targets.map { it.id })
    }

    @Test
    fun `selected components are not removal targets`() {
        val b = bundle(comp("core", required = true), comp("a"), comp("b"))
        val targets = DevSuiteInstaller.convergeUninstallTargets(b, setOf("core", "a", "b"))
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `empty selection still converges - uncheck-only assembly removes`() {
        // All optional unchecked, required always in the selection: the
        // assembly's remaining job is removal. (Bundles always carry at
        // least one required component, so the dialog's confirm button
        // never sees an empty selection — but the contract holds anyway.)
        val b = bundle(comp("core", required = true), comp("a"), comp("b"))
        val targets = DevSuiteInstaller.convergeUninstallTargets(b, setOf("core"))
        assertEquals(setOf("a", "b"), targets.map { it.id }.toSet())
    }
}
