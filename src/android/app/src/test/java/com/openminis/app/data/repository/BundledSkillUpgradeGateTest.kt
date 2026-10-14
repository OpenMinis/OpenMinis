package com.openminis.app.data.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-bundled-skill-version] The bundled-skill upgrade gate compared version
 * STRINGS (`existing.version >= bundledVersion`). Lexicographic order is not
 * numeric order: `"1.9.0" >= "1.10.0"` and `"2.0.9" >= "10.0.0"` are both TRUE,
 * so a device already holding the older copy skipped the SKILL.md the APK ships
 * forever — the upgrade path silently died at every two-digit boundary.
 *
 * The gate now delegates to the repo's numeric comparator
 * ([com.openminis.app.data.UpdateVersionLogic.compareVersions]), which is what the
 * in-app update flow already uses. Kept as a JVM test against
 * [BundledSkillUpgradeGate] (top-level, no Context) rather than a Robolectric
 * SkillRepository test.
 */
class BundledSkillUpgradeGateTest {

    @Test
    fun lexicographicTrapNowUpgrades() {
        // The exact case the report names: these are the strings that used to sort
        // "newer" and therefore suppress the upgrade.
        assertTrue(BundledSkillUpgradeGate.needsUpgrade("1.9.0", "1.10.0"))
        assertTrue(BundledSkillUpgradeGate.needsUpgrade("2.0.9", "10.0.0"))
        assertTrue(BundledSkillUpgradeGate.needsUpgrade("1.2.9", "1.10.0"))
        // And they were literally true as strings, which is the bug:
        assertTrue("1.9.0" >= "1.10.0")
        assertTrue("2.0.9" >= "10.0.0")
    }

    @Test
    fun ordinaryBumpsStillUpgrade() {
        assertTrue(BundledSkillUpgradeGate.needsUpgrade("1.2.0", "1.2.1"))
        assertTrue(BundledSkillUpgradeGate.needsUpgrade("1.0.0", "2.0.0"))
        assertTrue(BundledSkillUpgradeGate.needsUpgrade("", "1.0.0"))
    }

    @Test
    fun equalOrNewerInstalledDoesNoWork() {
        // The old rule's one correct behaviour is preserved: equal means no-op.
        assertFalse(BundledSkillUpgradeGate.needsUpgrade("1.10.0", "1.10.0"))
        assertFalse(BundledSkillUpgradeGate.needsUpgrade("1.10.1", "1.10.0"))
        assertFalse(BundledSkillUpgradeGate.needsUpgrade("2.0.0", "1.10.0"))
    }

    /**
     * b3a2136 rewrote the `android-sdk-mirrors` SKILL.md text but left the version
     * at 1.2.0, so nobody reinstalled it even with a correct comparator — the gate
     * and the shipped content have to move together. Pinned at the source level
     * (the content constant is private): the `installOrUpgradeBundled` argument and
     * the front-matter `version:` of the bundled text must agree, and both must be
     * past the 1.2.0 that shipped pre-b3a2136.
     */
    @Test
    fun androidSdkMirrorsContentBumpIsConsistent() {
        val source = File("src/main/java/com/openminis/app/data/repository/SkillRepository.kt")
        assertTrue("source not found: ${source.absolutePath}", source.isFile)
        val text = source.readText()

        val call = Regex(
            """installOrUpgradeBundled\(\s*"android-sdk-mirrors",\s*"([^"]+)"""",
        ).find(text)
        assertTrue("android-sdk-mirrors is no longer installed from a literal version", call != null)
        val bundledVersion = call!!.groupValues[1]

        val frontMatter = Regex(
            """name: android-sdk-mirrors\s*\nversion: ([0-9][^\s]*)""",
        ).find(text)
        assertTrue("bundled android-sdk-mirrors content lost its version front matter", frontMatter != null)
        val contentVersion = frontMatter!!.groupValues[1]

        assertTrue(
            "must be past 1.2.0 (the pre-b3a2136 version), got $bundledVersion",
            BundledSkillUpgradeGate.needsUpgrade("1.2.0", bundledVersion),
        )
        assertTrue(
            "the gate version ($bundledVersion) and the shipped SKILL.md version " +
                "($contentVersion) must agree, or a device that took the upgrade " +
                "still looks stale on the next launch",
            !BundledSkillUpgradeGate.needsUpgrade(contentVersion, bundledVersion) &&
                !BundledSkillUpgradeGate.needsUpgrade(bundledVersion, contentVersion),
        )
    }
}
