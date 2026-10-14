package com.openminis.app.git

import com.openminis.app.git.GitCli.GitFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-parser tests for [GitCli] — no Robolectric, no sandbox. The shell
 * layer is a thin wrapper; everything that can go wrong structurally lives
 * in the parsers, so they get the coverage.
 */
class GitCliParseTest {

    // ---------------------------------------------------------------- probe

    @Test
    fun `probe parses repo root branch and identity`() {
        val out = "true\n/var/minis/workspace/myrepo\nmain\ncfg\tAlice\talice@example.com\n"
        val info = GitCli.parseProbe(out, exitCode = 0)
        assertTrue(info.isRepo)
        assertEquals("/var/minis/workspace/myrepo", info.root)
        assertEquals("main", info.branch)
        assertEquals("Alice", info.userName)
        assertEquals("alice@example.com", info.userEmail)
    }

    @Test
    fun `probe tolerates missing identity and detached HEAD`() {
        val out = "true\n/repo\n\ncfg\t\t\n"
        val info = GitCli.parseProbe(out, exitCode = 0)
        assertTrue(info.isRepo)
        assertEquals("HEAD", info.branch) // blank branch → detached-HEAD label
        assertNull(info.userName)
        assertNull(info.userEmail)
    }

    @Test
    fun `probe reports non-repo with git's own error`() {
        val info = GitCli.parseProbe("", exitCode = 128)
        assertFalse(info.isRepo)

        val noisy = GitCli.parseProbe("fatal: not a git repository\n", exitCode = 128)
        assertFalse(noisy.isRepo)
        assertTrue(noisy.error!!.contains("not a git repository"))
    }

    // --------------------------------------------------------------- status

    @Test
    fun `status parses upstream tracking line with ahead and behind`() {
        val out = "## main...origin/main [ahead 2, behind 1]\n M src/a.kt\n"
        val s = GitCli.parseStatus(out)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        assertEquals("origin/main", s.upstream)
        assertEquals(1, s.entries.size)
        assertFalse(s.entries[0].staged)
        assertEquals('M', s.entries[0].code)
    }

    @Test
    fun `status handles initial commit branch line`() {
        val out = "## No commits yet on main\n?? README.md\n"
        val s = GitCli.parseStatus(out)
        assertEquals(0, s.ahead)
        assertNull(s.upstream)
        assertEquals(1, s.entries.size)
        assertTrue(s.entries[0].untracked)
        assertEquals("README.md", s.entries[0].path)
    }

    @Test
    fun `status splits mixed MM entry into staged and unstaged rows`() {
        val s = GitCli.parseStatus("MM both.txt\n")
        assertEquals(2, s.entries.size)
        val staged = s.entries.single { it.staged }
        val unstaged = s.entries.single { !it.staged }
        assertEquals('M', staged.code)
        assertEquals('M', unstaged.code)
        assertEquals(staged.path, unstaged.path)
        assertEquals(1, s.stagedCount)
    }

    @Test
    fun `status parses rename keeping orig and new path`() {
        val s = GitCli.parseStatus("R  old name.txt -> new name.txt\n")
        assertEquals(1, s.entries.size)
        val e = s.entries[0]
        assertTrue(e.staged)
        assertEquals('R', e.code)
        assertEquals("old name.txt", e.origPath)
        assertEquals("new name.txt", e.path)
    }

    @Test
    fun `status parses added deleted and untracked entries`() {
        val out = """
            A  added.kt
            D  deleted.kt
            ?? scratch/
        """.trimIndent()
        val s = GitCli.parseStatus(out)
        assertEquals(3, s.entries.size)
        assertTrue(s.entries.any { it.path == "added.kt" && it.staged && it.code == 'A' })
        assertTrue(s.entries.any { it.path == "deleted.kt" && it.staged && it.code == 'D' })
        assertTrue(s.entries.any { it.path == "scratch/" && it.untracked })
    }

    @Test
    fun `status on clean tree is clean`() {
        val s = GitCli.parseStatus("## main...origin/main\n")
        assertTrue(s.clean)
        assertEquals(0, s.stagedCount)
    }

    @Test
    fun `status ignores malformed short lines`() {
        val s = GitCli.parseStatus("## main\nxy\n?? ok.txt\n")
        assertEquals(1, s.entries.size)
    }

    @Test
    fun `status parses unicode paths unescaped`() {
        // -c core.quotepath=false keeps UTF-8 verbatim.
        val s = GitCli.parseStatus("?? 中文文档/笔记.md\n")
        assertEquals("中文文档/笔记.md", s.entries.single().path)
    }

    // ------------------------------------------------------------------ log

    @Test
    fun `log parses all five fields`() {
        val out = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f1700000000\u001ffix: subject here"
        val commits = GitCli.parseLog(out)
        assertEquals(1, commits.size)
        val c = commits[0]
        assertEquals("a1b2c3d4e5f6", c.hash)
        assertEquals("a1b2c3d", c.shortHash)
        assertEquals("Alice", c.author)
        assertEquals(1700000000L, c.epochSeconds)
        assertEquals("fix: subject here", c.subject)
    }

    @Test
    fun `log parses multiple commits and unicode subjects`() {
        val out = listOf(
            "h1\u001fs1\u001f张三\u001f1700000001\u001ffeat: 中文主题",
            "h2\u001fs2\u001fBob\u001f1700000002\u001fchore: bump",
        ).joinToString("\n")
        val commits = GitCli.parseLog(out)
        assertEquals(2, commits.size)
        assertEquals("feat: 中文主题", commits[0].subject)
        assertEquals("Bob", commits[1].author)
    }

    @Test
    fun `log drops malformed lines and blank padding`() {
        val out = "\nh1\u001fs1\u001fA\u001fnotanumber\u001fsubject\nbrokenline\n"
        val commits = GitCli.parseLog(out)
        // notanumber → epoch falls back to 0 but the record survives;
        // brokenline has no field separators → dropped.
        assertEquals(1, commits.size)
        assertEquals(0L, commits[0].epochSeconds)
        assertEquals("subject", commits[0].subject)
    }

    @Test
    fun `log on empty output yields empty list`() {
        assertEquals(0, GitCli.parseLog("").size)
    }

    // ------------------------------------------------------------- shell-ish

    @Test
    fun `shellQuote wraps and escapes single quotes`() {
        assertEquals("'plain.txt'", GitCli.shellQuote("plain.txt"))
        assertEquals("'with space.txt'", GitCli.shellQuote("with space.txt"))
        assertEquals("'it'\\''s.txt'", GitCli.shellQuote("it's.txt"))
        assertEquals("'中文.md'", GitCli.shellQuote("中文.md"))
    }

    @Test
    fun `commit command carries base64 payload and uses -F`() {
        val msg = "feat: add git panel\n\n- multi-line body with 'quotes' and \$dollar"
        val b64 = java.util.Base64.getEncoder().encodeToString(msg.toByteArray(Charsets.UTF_8))
        val cmd = GitCli.buildCommitCommand(b64)
        assertTrue(cmd.contains("git commit -F /tmp/.minis-git-msg"))
        // [T-git-commit-temp-collision] Unique per invocation via the shell pid,
        // so two concurrent session commits cannot clobber one message file.
        assertTrue("per-pid temp path expected: $cmd", cmd.contains("/tmp/.minis-git-msg.\$\$"))
        assertTrue(cmd.contains(b64))
        // The b64 alphabet is quote-free, so the payload survives single quotes.
        assertFalse(b64.contains("'"))
        // Round-trip: what the guest decodes is exactly the message.
        val embedded = cmd.substringAfter("printf '%s' '").substringBefore("' |")
        assertEquals(
            msg,
            String(java.util.Base64.getDecoder().decode(embedded), Charsets.UTF_8),
        )
    }

    // ------------------------------------------------ commit-message prompt

    @Test
    fun `generator sanitize strips code fence and label`() {
        assertEquals(
            "fix: thing",
            GitCommitMessageGenerator.sanitize("```\nfix: thing\n```"),
        )
        assertEquals(
            "fix: thing",
            GitCommitMessageGenerator.sanitize("Commit message: fix: thing"),
        )
        assertNull(GitCommitMessageGenerator.sanitize("   "))
        assertNull(GitCommitMessageGenerator.sanitize(null))
    }

    @Test
    fun `generator sanitize caps runaway output at 20 lines`() {
        val raw = (1..40).joinToString("\n") { "line$it" }
        val out = GitCommitMessageGenerator.sanitize(raw)!!
        assertEquals(20, out.lines().size)
        assertEquals("line1", out.lines().first())
        assertEquals("line20", out.lines().last())
    }

    @Test
    fun `generator prompt caps huge diffs`() {
        val huge = "x".repeat(60_000)
        val (system, user) = GitCommitMessageGenerator.buildPrompt(huge)
        assertTrue(system.contains("commit message"))
        assertTrue(user.length < huge.length)
        assertTrue(user.contains("truncated"))
    }

    @Test
    fun `generator prompt keeps small diffs verbatim`() {
        val small = "diff --git a/f b/f\n+hello\n"
        val (_, user) = GitCommitMessageGenerator.buildPrompt(small)
        assertTrue(user.contains(small))
    }

    // ----------------------------------------------------------- entry model

    @Test
    fun `status entry equality supports list diffing in compose`() {
        val e = GitFileEntry("a.txt", "a.txt", 'M', staged = true)
        assertNotNull(e.copy())
        assertEquals(e, e.copy())
        assertFalse(e.equals(e.copy(staged = false)))
    }

    // ------------------------------------------------- nested repo discovery

    @Test
    fun `discover parses immediate-child repo names in order`() {
        val out = "./OpenMinis-Linux/.git\n./notes/.git\n"
        assertEquals(listOf("OpenMinis-Linux", "notes"), GitCli.parseDiscover(out))
    }

    @Test
    fun `discover ignores blank lines cwd itself and stray text`() {
        val out = "\n./a/.git\n./.git\nnot-a-path\n./b with space/.git\n"
        assertEquals(listOf("a", "b with space"), GitCli.parseDiscover(out))
    }

    @Test
    fun `discover on empty output yields no candidates`() {
        assertTrue(GitCli.parseDiscover("").isEmpty())
        assertTrue(GitCli.parseDiscover("cfg\t\t\n").isEmpty())
    }

    @Test
    fun `git prefix is bare without root and quoted with one`() {
        assertEquals("git", GitCli.gitPrefix(null))
        assertEquals("git", GitCli.gitPrefix(""))
        assertEquals("git -C '/var/w/r'", GitCli.gitPrefix("/var/w/r"))
        assertEquals("git -C '/a b/'", GitCli.gitPrefix("/a b/"))
    }

    @Test
    fun `probe and status commands carry the resolved root`() {
        assertFalse(GitCli.probeCmd().contains("-C"))
        assertTrue(GitCli.probeCmd("r").contains("git -C 'r' rev-parse --is-inside-work-tree"))
        // cfg lookup must resolve against the same repo, not the shell cwd
        assertTrue(GitCli.probeCmd("r").contains("\$(git -C 'r' config user.name"))
        assertEquals(
            "git -C 'r' -c core.quotepath=false status --porcelain -b",
            GitCli.statusCmd("r"),
        )
        assertTrue(GitCli.logCmd(5, "r").startsWith("git -C 'r' -c core.quotepath=false log -n 5"))
        assertTrue(GitCli.buildCommitCommand("QQ==", "r").contains("git -C 'r' commit -F"))
        // default stays cwd-bound so existing callers and tests are unaffected
        assertEquals(GitCli.statusCmd(), GitCli.statusCmd(null))
    }

    @Test
    fun `probe error text drops shell watchdog noise`() {
        val out = "cfg\t\t\n" +
            "/bin/bash: line 39: 14394 Killed\n" +
            "( sleep 600; kill -TERM -$$ 2> /dev/null; sleep 3; kill -KILL -$$ 2> /dev/null )\n"
        val info = GitCli.parseProbe(out, exitCode = 128)
        assertFalse(info.isRepo)
        // everything was noise → fall back to the exit code, not raw stderr
        assertEquals("exit=128", info.error)

        val mixed = GitCli.parseProbe("fatal: not a git repository\n/bin/bash: line 1: 9 Killed\n", 128)
        assertEquals("fatal: not a git repository", mixed.error)
    }
}
