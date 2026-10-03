package com.openminis.app.data.repository

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-android-skill-scan-parity] Sending a message must not touch the skill
 * directory, and the scan that does run must be linear.
 *
 * Field report (realme RMX5010, 1.14): every send froze the UI for 1.7–2.7 s.
 * buildSystemPrompt() called reloadFromDisk() on the main thread — twice per
 * send — and with 283 of 490 skills missing, the orphan circuit breaker
 * re-queried every row and stat()ed every SKILL.md once per missing skill.
 */
class SkillScanParityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun skill(root: File, id: String, body: String = "---\nname: $id\n---\n"): File =
        File(root, id).apply { mkdirs() }.let { dir -> File(dir, "SKILL.md").apply { writeText(body) } }

    // -- Disk listing --

    @Test fun `only directories holding a SKILL md count as skills on disk`() {
        val root = tmp.newFolder("skills")
        skill(root, "a")
        skill(root, "b")
        File(root, "empty-dir").mkdirs()
        File(root, "stray.txt").writeText("x")
        File(root, "c").mkdirs(); File(root, "c/README.md").writeText("no skill md")

        assertEquals(setOf("a", "b"), SkillDiskSignature.skillIdsOnDisk(root))
    }

    @Test fun `a missing skills directory lists nothing and signs as zero`() {
        val gone = File(tmp.root, "never-created")
        assertEquals(emptySet<String>(), SkillDiskSignature.skillIdsOnDisk(gone))
        assertEquals(0L, SkillDiskSignature.compute(gone))
    }

    // -- Signature: what a shell command can do to /var/minis/skills --

    @Test fun `signature is stable when nothing changed`() {
        val root = tmp.newFolder("skills")
        skill(root, "a"); skill(root, "b")
        assertEquals(SkillDiskSignature.compute(root), SkillDiskSignature.compute(root))
    }

    @Test fun `git clone of a new skill changes the signature`() {
        val root = tmp.newFolder("skills")
        skill(root, "a")
        val before = SkillDiskSignature.compute(root)
        skill(root, "cloned")
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `rm -rf of a skill changes the signature`() {
        val root = tmp.newFolder("skills")
        skill(root, "a"); skill(root, "b")
        val before = SkillDiskSignature.compute(root)
        File(root, "b").deleteRecursively()
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `editing SKILL md in place changes the signature`() {
        val root = tmp.newFolder("skills")
        val md = skill(root, "a", "---\nname: a\ndescription: old\n---\n")
        md.setLastModified(1_000_000L)
        val before = SkillDiskSignature.compute(root)
        md.writeText("---\nname: a\ndescription: a much longer new description\n---\n")
        md.setLastModified(2_000_000L)
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `a same-size rewrite is caught by mtime alone`() {
        val root = tmp.newFolder("skills")
        val md = skill(root, "a", "---\nname: a\ndescription: 1111\n---\n")
        md.setLastModified(1_000_000L)
        val before = SkillDiskSignature.compute(root)
        md.writeText("---\nname: a\ndescription: 2222\n---\n")
        md.setLastModified(2_000_000L)
        assertNotEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `files other than SKILL md do not trigger a reload`() {
        val root = tmp.newFolder("skills")
        skill(root, "a")
        val before = SkillDiskSignature.compute(root)
        File(root, "a/scripts").mkdirs()
        File(root, "a/scripts/run.py").writeText("print(1)")
        assertEquals(before, SkillDiskSignature.compute(root))
    }

    @Test fun `signature does not depend on listing order`() {
        val r1 = tmp.newFolder("r1"); val r2 = tmp.newFolder("r2")
        for (id in listOf("x", "a", "m")) skill(r1, id).setLastModified(5_000_000L)
        for (id in listOf("m", "x", "a")) skill(r2, id).setLastModified(5_000_000L)
        assertEquals(SkillDiskSignature.compute(r1), SkillDiskSignature.compute(r2))
    }

    // -- Circuit breaker rule (decided once per load) --

    @Test fun `the reporter's library trips the breaker`() {
        assertTrue(SkillDiskSignature.breakerTripped(totalCustom = 490, missingCustom = 283))
    }

    @Test fun `a few deliberate deletions still prune`() {
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 490, missingCustom = 5))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 10, missingCustom = 3))
    }

    @Test fun `tiny libraries always prune`() {
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 2, missingCustom = 2))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 5, missingCustom = 2))
    }

    @Test fun `the forty percent boundary trips`() {
        assertTrue(SkillDiskSignature.breakerTripped(totalCustom = 10, missingCustom = 4))
        assertFalse(SkillDiskSignature.breakerTripped(totalCustom = 100, missingCustom = 39))
    }

    // -- Source guards: the send path and the scan shape --

    private val chatVm by lazy { ProductionSources.read("ui/chat/ChatViewModel.kt") }
    private val repo by lazy { ProductionSources.read("data/repository/SkillRepository.kt") }

    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        var depth = 0
        var i = src.indexOf('{', start)
        val open = i
        while (i < src.length) {
            when (src[i]) { '{' -> depth++; '}' -> if (--depth == 0) return src.substring(open, i + 1) }
            i++
        }
        error("unbalanced $signature")
    }

    private fun code(body: String): String =
        body.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test fun `building the system prompt never touches the skill directory`() {
        val body = code(functionBody(chatVm, "private fun buildSystemPrompt(): String?"))
        assertFalse("per-send rescan is back", body.contains("skillRepository?.reloadFromDisk()"))
        assertFalse(body.contains("skillRepository?.requestReload"))
        assertTrue(body.contains("skillPromptFragment(activeSessionId)"))
    }

    @Test fun `shell and file_write queue a background rescan`() {
        assertTrue(chatVm.contains("skillRepository?.requestReload(\"shell_execute\")"))
        assertTrue(chatVm.contains("skillRepository?.requestReload(\"file_write\", force = true)"))
    }

    @Test fun `restore and returning to the app queue a rescan`() {
        assertTrue(ProductionSources.read("backup/BackupImporter.kt").contains("requestReload(\"backup_restore\", force = true)"))
        assertTrue(ProductionSources.read("MinisApp.kt").contains("skillRepository.requestReload(\"foreground\")"))
    }

    @Test fun `reloadFromDisk no longer scans on the caller's thread`() {
        val body = code(functionBody(repo, "fun reloadFromDisk()"))
        assertFalse(body.contains("loadAll("))
        assertTrue(body.contains("requestReload("))
    }

    @Test fun `the breaker is decided once, outside the per-row loop`() {
        val body = code(functionBody(repo, "private fun loadAll(requireSeq: Boolean = false): Boolean"))
        val rowLoop = body.indexOf("SELECT * FROM skills ORDER BY installed_at DESC")
        assertTrue(rowLoop > 0)
        val insideLoop = body.substring(rowLoop)
        assertFalse("per-row requery is back", insideLoop.contains("rawQuery("))
        assertFalse("per-row breaker log is back", insideLoop.contains("Circuit breaker tripped"))
        assertFalse("per-row stat is back", insideLoop.contains("SKILL.md\").exists()"))
        assertEquals(1, Regex("Circuit breaker tripped").findAll(body).count())
    }

    @Test fun `every in-memory edit goes through mutateSkills`() {
        // The one direct write left is loadAll's guarded commit.
        assertEquals(1, Regex("""_skills\.value = """).findAll(repo).count() -
            Regex("""_skills\.value = transform""").findAll(repo).count())
    }
}
