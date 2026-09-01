package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-skill-stale-name] The self-heal decision in
 * [SkillRepository.loadAll] — when a stored skill row should be refreshed from
 * the SKILL.md on disk, and which fields may be overwritten.
 *
 * The rule is narrow on purpose: healing must only ever replace a
 * blank/placeholder value with a real one, never the reverse. A mid-edit or
 * malformed SKILL.md would otherwise wipe metadata the user set deliberately.
 *
 * The repository itself needs a live SQLiteDatabase, so the decision is
 * mirrored here rather than driven through it — the same convention
 * AtomicCellSelectionTest uses for SelectionController's boundary maths. The
 * mirror is kept in sync by hand; it is the branch table, not the storage.
 */
class SkillStaleMetadataTest {

    /** Outcome of one heal decision: what the row would hold afterwards. */
    private data class Healed(
        val name: String,
        val description: String,
        val version: String,
        val wrote: Boolean,
    )

    /**
     * Mirrors the block in loadAll(). [parsedName]/[parsedDescription] are what
     * SKILL.md yields; a null [parsedName] models parseSkillMd returning null,
     * which it does whenever the file has no usable frontmatter name.
     */
    private fun heal(
        storedName: String,
        storedDescription: String,
        storedVersion: String = "1.0.0",
        parsedName: String? = "Real Name",
        parsedDescription: String = "Real description",
        parsedVersion: String = "2.0.0",
        skillMdExists: Boolean = true,
    ): Healed {
        var name = storedName
        var description = storedDescription
        var version = storedVersion

        val nameStale = name.isBlank()
        val descStale = description == ">" || description == "|" || description.isBlank()
        var wrote = false
        if ((descStale || nameStale) && skillMdExists && parsedName != null) {
            var changed = false
            if (descStale && parsedDescription.isNotBlank()) {
                description = parsedDescription
                changed = true
            }
            if (nameStale && parsedName.isNotBlank()) {
                name = parsedName
                changed = true
            }
            if (version.isBlank()) {
                version = parsedVersion
                changed = true
            }
            wrote = changed
        }
        return Healed(name, description, version, wrote)
    }

    // ── The bug this change fixes ────────────────────────────────────────

    @Test
    fun `a blank name is healed even when the description is perfectly fine`() {
        // The regression: healing used to be gated on the description alone, so
        // this row — good description, empty name — never entered the block and
        // showed as a nameless entry in the skills list forever.
        val r = heal(storedName = "", storedDescription = "A good description")
        assertEquals("Real Name", r.name)
        assertEquals("A good description", r.description)
        assertTrue("must persist the healed name", r.wrote)
    }

    @Test
    fun `a blank name and a blank description are both healed in one write`() {
        val r = heal(storedName = "", storedDescription = "")
        assertEquals("Real Name", r.name)
        assertEquals("Real description", r.description)
        assertTrue(r.wrote)
    }

    // ── No regression: the description cases that already worked ─────────

    @Test
    fun `a block-scalar leftover description is still healed`() {
        for (leftover in listOf(">", "|")) {
            val r = heal(storedName = "Kept Name", storedDescription = leftover)
            assertEquals("Real description", r.description)
            assertEquals("name must not be touched", "Kept Name", r.name)
            assertTrue(r.wrote)
        }
    }

    @Test
    fun `a healthy row is left completely alone and writes nothing`() {
        // The common case. Both stale tests are false, so the block never runs
        // — this is what keeps the change zero-impact for everyone else.
        val r = heal(storedName = "妙想", storedDescription = "Imagination skill")
        assertEquals("妙想", r.name)
        assertEquals("Imagination skill", r.description)
        assertFalse("no DB write for a healthy row", r.wrote)
    }

    // ── Healing may never destroy good data ──────────────────────────────

    @Test
    fun `a deliberately set name is never overwritten by the file`() {
        // A user renamed the skill in-app; SKILL.md may lag behind. The stored
        // name is non-blank, so nameStale is false and the file cannot win.
        val r = heal(
            storedName = "My Renamed Skill",
            storedDescription = "",
            parsedName = "Old File Name",
        )
        assertEquals("My Renamed Skill", r.name)
        assertEquals("only the description heals", "Real description", r.description)
    }

    @Test
    fun `an unparseable SKILL_md heals nothing rather than writing blanks`() {
        // parseSkillMd returns null (no frontmatter fence, or no name key).
        // Writing that back would wipe whatever the row still had.
        val r = heal(storedName = "", storedDescription = "", parsedName = null)
        assertEquals("", r.name)
        assertEquals("", r.description)
        assertFalse(r.wrote)
    }

    @Test
    fun `a missing SKILL_md heals nothing`() {
        val r = heal(storedName = "", storedDescription = "", skillMdExists = false)
        assertEquals("", r.name)
        assertFalse(r.wrote)
    }

    @Test
    fun `a blank parsed description does not overwrite a stale one`() {
        // Mid-write file: frontmatter has a name but no description yet.
        val r = heal(
            storedName = "",
            storedDescription = "",
            parsedDescription = "",
        )
        assertEquals("the name still heals", "Real Name", r.name)
        assertEquals("", r.description)
        assertTrue(r.wrote)
    }

    // ── Version tags along, but never alone ──────────────────────────────

    @Test
    fun `a blank version is filled while healing`() {
        val r = heal(storedName = "", storedDescription = "ok", storedVersion = "")
        assertEquals("2.0.0", r.version)
        assertTrue(r.wrote)
    }

    @Test
    fun `a blank version alone does NOT trigger a heal`() {
        // Version is not part of the stale test — a healthy name+description
        // row with an odd version must not cause a write on every single load.
        val r = heal(storedName = "Name", storedDescription = "Desc", storedVersion = "")
        assertEquals("", r.version)
        assertFalse(r.wrote)
    }

    // ── Whitespace counts as blank ───────────────────────────────────────

    @Test
    fun `a whitespace-only name counts as stale`() {
        // isBlank(), not isEmpty(): a name of "   " renders as nothing and is
        // just as broken as "".
        val r = heal(storedName = "   ", storedDescription = "ok")
        assertEquals("Real Name", r.name)
        assertTrue(r.wrote)
    }
}
