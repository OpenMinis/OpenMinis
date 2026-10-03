package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-envvar-empty-value] A restore must leave env vars with
 * usable VALUES, not just the right keys.
 *
 * Reported after a real restore: every variable was present in Settings with
 * its name, note and timestamp intact, yet shell commands that needed them
 * failed. Measured on a Pixel 6 by reading lengths only (the redactor masks
 * content but preserves length): 11 of 12 sampled variables held a zero-length
 * value. It read as a successful restore right up until something tried to use
 * a credential.
 *
 * Cause: `importEnvironmentVariables` gated on
 * `repo.isDuplicateKey(meta.key)` — "does this KEY exist?" — and `continue`d
 * when it did, so a key that was already present never had its value written,
 * even when the stored value was empty. The second path had the same shape:
 * a key absent from `secrets.json` was added with `?: ""`, creating an entry
 * that looked restored and counted as `imported` while being unusable.
 *
 * iOS gates on the VALUE instead (`BackupSecretsImporter.swift:94-101`:
 * iterate `secrets.envVars`, write when `loadValueSync(forKey:) == nil`), so
 * it repairs exactly this state. These tests pin the Android decision table to
 * that behaviour.
 *
 * The importer needs a Context and EncryptedSharedPreferences, so the decision
 * is modelled here as a pure function and additionally pinned against the real
 * source text — the same split used by the other restore tests in this module.
 */
class RestoreEnvVarValueTest {

    /** What the importer should do for one metadata entry. */
    private enum class Outcome { IMPORTED, SKIPPED }

    /**
     * Mirror of the importer's decision, kept deliberately small so the table
     * below is readable. `existingValue` is null when the key is absent
     * entirely, "" when the key exists with no usable value.
     */
    private fun decide(existingValue: String?, restored: String?): Outcome = when {
        // Key present with a real value — never clobber a live credential.
        !existingValue.isNullOrEmpty() -> Outcome.SKIPPED
        // Nothing usable in the package.
        restored.isNullOrEmpty() -> Outcome.SKIPPED
        // Key present but blank, or absent: write the restored value.
        else -> Outcome.IMPORTED
    }

    @Test
    fun `an existing key with an empty value is repaired`() {
        // THE REPORTED BUG. The old code skipped this outright, which is how a
        // device ended up with 35 correctly-named variables and no values.
        assertEquals(Outcome.IMPORTED, decide(existingValue = "", restored = "ghp_realtoken"))
    }

    @Test
    fun `a key that is absent entirely is added with its value`() {
        assertEquals(Outcome.IMPORTED, decide(existingValue = null, restored = "ghp_realtoken"))
    }

    @Test
    fun `a live value is never replaced by the package's copy`() {
        // A restore must not silently downgrade a credential the user rotated
        // after the backup was taken.
        assertEquals(Outcome.SKIPPED, decide(existingValue = "current-secret", restored = "older-secret"))
    }

    @Test
    fun `metadata with no value in the package does not create a blank entry`() {
        // Writing "" here is what made the failure invisible: the entry looked
        // restored, counted as imported, and was unusable.
        assertEquals(Outcome.SKIPPED, decide(existingValue = null, restored = null))
        assertEquals(Outcome.SKIPPED, decide(existingValue = null, restored = ""))
        assertEquals(Outcome.SKIPPED, decide(existingValue = "", restored = ""))
    }

    // ── The real source must implement that table ────────────────────────

    private val importerSrc: String by lazy {
        val f = File("src/main/java/com/openminis/app/backup/BackupImporter.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** `importEnvironmentVariables` body with comment lines stripped. */
    private val envImportCode: String by lazy {
        importerSrc
            .substringAfter("private fun importEnvironmentVariables(")
            .substringBefore("\n    /** Read + decode `secrets.json`")
            .lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    @Test
    fun `the importer does not skip purely because the key exists`() {
        assertFalse(
            "gating on isDuplicateKey skips keys whose value is empty — the reported bug",
            envImportCode.contains("if (repo.isDuplicateKey(meta.key))"),
        )
        assertTrue(
            "it must look at the stored value instead",
            envImportCode.contains("repo.getValue("),
        )
    }

    @Test
    fun `the importer never writes an empty value as if it were restored`() {
        assertFalse(
            "`?: \"\"` turns a missing secret into a blank entry that counts as imported",
            envImportCode.contains("""valuesByName[meta.key] ?: """"),
        )
        assertTrue(
            "a missing/blank restored value must be skipped",
            envImportCode.contains("restored.isNullOrEmpty()"),
        )
    }

    @Test
    fun `an existing entry is updated in place rather than re-added`() {
        // add() refuses a duplicate key, so repairing an existing entry has to
        // go through update() or the value silently never lands.
        assertTrue(envImportCode.contains("repo.update("))
    }
}
