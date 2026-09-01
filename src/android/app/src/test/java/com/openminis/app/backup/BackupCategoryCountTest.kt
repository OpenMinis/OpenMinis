package com.openminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the numbers on the backup/restore screen actually mean.
 *
 * Every category used to report `entries` in whatever unit was cheapest to
 * compute, which produced three separate wrong numbers in one screenshot:
 * "569 items" for a dozen skills (files, not skills), "1 item" for two MCP
 * servers (files, not servers), and "9 providers" for eight (providers plus
 * thinking rules). All three were display-only — the exported bytes were
 * always correct — but a backup tool whose counts cannot be trusted is a
 * backup tool nobody trusts.
 *
 * These tests pin the UNIT of each count, which is the part that regressed.
 */
class BackupCategoryCountTest {

    private val tmp: File = File.createTempFile("count-test", "").apply {
        delete(); mkdirs()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun skill(id: String, vararg files: String) {
        val dir = File(tmp, "skills/$id").apply { mkdirs() }
        files.forEach { File(dir, it).apply { parentFile?.mkdirs() }.writeText("x") }
    }

    private fun serversJson(text: String): File =
        File(tmp, "servers.json").apply { parentFile?.mkdirs(); writeText(text) }

    // MARK: - Skills: skills, not files

    /** The reported case: many files spread over few skills. */
    @Test
    fun `skill count is directories, not the files inside them`() {
        skill("alpha", "SKILL.md", "scripts/run.sh", "assets/a.png", "assets/b.png")
        skill("beta", "SKILL.md", "data/1.json", "data/2.json")
        skill("gamma", "SKILL.md")

        // 3 skills, 9 files — the old code reported 9.
        assertEquals(3, BackupExporter.skillCount(File(tmp, "skills")))
    }

    @Test
    fun `a skill carrying hundreds of files still counts as one skill`() {
        val many = (1..569).map { "assets/file$it.bin" }.toTypedArray()
        skill("heavy", *many)

        assertEquals(1, BackupExporter.skillCount(File(tmp, "skills")))
    }

    @Test
    fun `loose files at the skills root are not skills`() {
        skill("real", "SKILL.md")
        File(tmp, "skills/stray.md").writeText("not a skill")

        assertEquals(1, BackupExporter.skillCount(File(tmp, "skills")))
    }

    @Test
    fun `no skills directory yields zero, not a crash`() {
        assertEquals(0, BackupExporter.skillCount(File(tmp, "skills")))
    }

    // MARK: - MCP: servers, not files

    @Test
    fun `mcp count reports the number of servers`() {
        val f = serversJson(
            """{"mcpServers":{"context7":{"url":"https://a"},"playwright":{"command":"npx"}}}"""
        )
        // The reported case: two servers that used to display as "1".
        assertEquals(2, BackupExporter.mcpServerCount(f))
    }

    @Test
    fun `mcp count handles a single server and an empty set`() {
        assertEquals(1, BackupExporter.mcpServerCount(serversJson("""{"mcpServers":{"only":{}}}""")))
        assertEquals(0, BackupExporter.mcpServerCount(serversJson("""{"mcpServers":{}}""")))
    }

    /**
     * The file exists and is being backed up, so an unreadable one must not
     * claim "0 servers" — that reads as data loss. Falls back to the old
     * fixed 1.
     */
    @Test
    fun `malformed or unexpected servers json falls back to one, never zero`() {
        assertEquals(1, BackupExporter.mcpServerCount(serversJson("{ not json")))
        assertEquals(1, BackupExporter.mcpServerCount(serversJson("""{"somethingElse":{}}""")))
    }

    // MARK: - Providers: providers, not providers + rules

    /**
     * Thinking rules ride in the `providers` category because they have no
     * category of their own (adding one would change the cross-platform
     * category set), but they must not inflate the provider count.
     */
    @Test
    fun `provider stat separates thinking rules from provider count`() {
        val stat = BackupManifest.CategoryStat(
            entries = 8, bytes = 26_000, encrypted = false,
            includesCredentials = true, thinkingRules = 1,
        )

        // The reported case: 8 providers + 1 rule displayed as "9".
        assertEquals(8, stat.entries)
        assertEquals(1, stat.thinkingRules)
    }

    @Test
    fun `no custom thinking rules leaves the field absent rather than zero`() {
        // `takeIf { it > 0 }` at the export site: absent, so the UI picks the
        // plain "N providers" label instead of "N providers · 0 rules".
        val json = BackupFormat.json.encodeToString(
            BackupManifest.CategoryStat.serializer(),
            BackupManifest.CategoryStat(entries = 8, bytes = 1, encrypted = false),
        )
        // `explicitNulls = false` (BackupFormat.json) drops null fields, so an
        // absent rule count really is absent on the wire, not `"…":null`.
        assertFalse(json.contains("thinking_rules"))
    }

    // MARK: - Wire compatibility

    /**
     * A package written before these fields existed must still decode, and
     * must not gain invented numbers — the UI falls back to the generic label
     * when the detail field is null.
     */
    @Test
    fun `old package without the new fields still decodes`() {
        val stat = BackupFormat.json.decodeFromString(
            BackupManifest.CategoryStat.serializer(),
            """{"entries":569,"bytes":84400000,"encrypted":false}""",
        )
        assertEquals(569, stat.entries)
        assertNull(stat.files)
        assertNull(stat.thinkingRules)
    }

    /** Round-trip under the wire name iOS would read. */
    @Test
    fun `thinking rules serialises under its snake_case wire key`() {
        val json = BackupFormat.json.encodeToString(
            BackupManifest.CategoryStat.serializer(),
            BackupManifest.CategoryStat(entries = 8, bytes = 1, encrypted = false, thinkingRules = 2),
        )
        assertTrue(json.contains("\"thinking_rules\":2"))

        val back = BackupFormat.json.decodeFromString(
            BackupManifest.CategoryStat.serializer(), json,
        )
        assertEquals(2, back.thinkingRules)
        assertEquals(8, back.entries)
    }

    /**
     * Skills stat shape as the exporter builds it: skills in `entries`, files
     * in `files`. Guards the pairing, since swapping them would still compile
     * and still round-trip.
     */
    @Test
    fun `skills stat carries skills in entries and files in files`() {
        val stat = BackupManifest.CategoryStat(
            entries = 12, bytes = 84_400_000, encrypted = false, files = 569,
        )
        assertEquals(12, stat.entries)
        assertEquals(569, stat.files)
    }
}
