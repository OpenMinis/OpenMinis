package com.openminis.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * [T-android-storage-symlink-inflation] Pins that sizing counts real bytes
 * once, the way `du` does — not `du -L`.
 *
 * The reported bug: the storage screen showed "Shell 容器 43.89 GB" for an
 * install Android's own settings measured at 9.60 GB. `walkTopDown()` follows
 * directory symlinks and `File.length()`/`isFile()` resolve file symlinks, so
 * every link re-counted its target — and a link to a directory re-counted a
 * whole subtree, multiplying rather than adding.
 */
class DirectorySizeSymlinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) {
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    @Test
    fun `a symlink to a file is not counted as a second copy`() {
        val root = tmp.newFolder("root")
        write(File(root, "real/big.bin"), 1_000)
        Files.createSymbolicLink(
            File(root, "link.bin").toPath(),
            File(root, "real/big.bin").toPath(),
        )
        assertEquals(1_000L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a symlink to a directory is not descended into`() {
        // The expensive case: the Alpine rootfs and every venv/node_modules
        // layout has these, and each one re-counted an entire subtree.
        val root = tmp.newFolder("root")
        write(File(root, "usr/lib/a.so"), 4_000)
        write(File(root, "usr/lib/b.so"), 6_000)
        Files.createSymbolicLink(
            File(root, "lib").toPath(),
            File(root, "usr/lib").toPath(),
        )
        assertEquals(10_000L, SessionStorage.directorySize(root))
    }

    @Test
    fun `many links to one target inflate nothing`() {
        // 304 busybox applet links ship in the stock rootfs; the multiplier is
        // what turns single-digit GB into tens of GB.
        val root = tmp.newFolder("root")
        write(File(root, "bin/busybox"), 900_000)
        repeat(300) { i ->
            Files.createSymbolicLink(
                File(root, "bin/applet$i").toPath(),
                File(root, "bin/busybox").toPath(),
            )
        }
        assertEquals(900_000L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a symlink pointing outside the tree cannot import foreign bytes`() {
        // Deleting this directory would not free those bytes, so counting them
        // would make the "bytes freed" figure a lie too.
        val outside = tmp.newFolder("outside")
        write(File(outside, "huge.bin"), 50_000)
        val root = tmp.newFolder("root")
        write(File(root, "own.bin"), 7)
        Files.createSymbolicLink(
            File(root, "escape").toPath(),
            outside.toPath(),
        )
        assertEquals(7L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a symlink cycle terminates instead of hanging`() {
        // `a/loop -> a` would recurse forever under the old walk.
        val root = tmp.newFolder("root")
        write(File(root, "a/file.bin"), 42)
        Files.createSymbolicLink(
            File(root, "a/loop").toPath(),
            File(root, "a").toPath(),
        )
        assertEquals(42L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a broken symlink is skipped rather than throwing`() {
        val root = tmp.newFolder("root")
        write(File(root, "real.bin"), 11)
        Files.createSymbolicLink(
            File(root, "dangling").toPath(),
            File(root, "does-not-exist").toPath(),
        )
        assertEquals(11L, SessionStorage.directorySize(root))
    }

    @Test
    fun `ordinary nested files are still summed`() {
        val root = tmp.newFolder("root")
        write(File(root, "a.bin"), 100)
        write(File(root, "x/b.bin"), 200)
        write(File(root, "x/y/c.bin"), 300)
        assertEquals(600L, SessionStorage.directorySize(root))
    }

    @Test
    fun `a missing directory is zero`() {
        assertEquals(0L, SessionStorage.directorySize(File(tmp.root, "nope")))
    }

    @Test
    fun `walkTopDown follows directory symlinks`() {
        // Not testing our code — pinning the platform behaviour the bug rested
        // on, so the reasoning above stays checkable rather than remembered.
        // If a future Kotlin/JDK stops following directory links here, the
        // pruning in directorySize becomes redundant and this test says so.
        val root = tmp.newFolder("root")
        write(File(root, "usr/lib/a.so"), 4_000)
        Files.createSymbolicLink(
            File(root, "lib").toPath(),
            File(root, "usr/lib").toPath(),
        )

        var naive = 0L
        root.walkTopDown().forEach { if (it.isFile) naive += it.length() }

        assertEquals("the naive walk sees the payload twice", 8_000L, naive)
        assertTrue(
            "and that is strictly more than the truth",
            naive > SessionStorage.directorySize(root),
        )
    }

    // ── [T-android-storage-symlink-inflation] no fourth copy ──────────────
    //
    // 754e65eac fixed two sizing walks and a THIRD survived in RootfsManager,
    // which is why the "Rootfs 管理" screen still read 40.25 GB while the
    // Storage screen next to it was correct. A fourth then turned up in
    // DeviceOffloadHandler. Each was hand-rolled, each read plausibly, and each
    // disagreed with the others by an order of magnitude.
    //
    // So the guard is structural: one implementation, and a test that fails if
    // someone writes a second. Cheaper than discovering the next one from a
    // screenshot.

    private fun mainSources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no file rolls its own recursive directory-size walk`() {
        // The shape that keeps recurring: summing `length()` while recursing on
        // `isDirectory`/`walkTopDown`, both of which resolve symlinks.
        // Counting bytes ACTUALLY COPIED OR WRITTEN is a different question from
        // "how much does this directory occupy": there, resolving a symlink is
        // correct, because the copy really does duplicate the target's bytes.
        // The backup writers are that case and are deliberately exempt — the
        // exemption is by path and is listed here so it stays a decision rather
        // than a hole the heuristic quietly grew.
        val bytesWrittenNotBytesOnDisk = setOf(
            "backup/BackupZip.kt",        // pre-flight check against the ZIP format's 4GB limit
            "backup/BackupExporter.kt",   // progress counter over files it just copied
            "backup/BackupImporter.kt",   // progress counter over files it just wrote
        )

        val offenders = mutableListOf<String>()
        for (f in mainSources()) {
            if (f.path.endsWith("SessionStorage.kt")) continue // the one real implementation
            val norm = f.path.replace(File.separatorChar, '/')
            if (bytesWrittenNotBytesOnDisk.any { norm.endsWith(it) }) continue
            val text = f.readText()
            // Any accumulation of `length()` counts — `total += f.length()`,
            // `sumOf { it.length() }`, and the ternary form
            // `total += if (isDirectory) recurse(f) else f.length()`, which the
            // first version of this regex missed and which is exactly the shape
            // RootfsManager had.
            val sumsLength = Regex("""\+=[^\n]*\.length\(\)|sumOf\s*\{[^}]*\.length\(\)""")
                .containsMatchIn(text)
            if (!sumsLength) continue
            // Only a RECURSIVE/tree walk is the bug — a flat listFiles() over a
            // directory with no symlinks (AppLogger's log dir) is fine, and so
            // is counting bytes actually copied (the backup writers), where
            // following a link is the correct behaviour.
            val walksTree = Regex("""walkTopDown\(\)""").containsMatchIn(text) ||
                Regex("""isDirectory\s*\)\s*\{?\s*
?\s*\w*[Dd]irSize|calculateDirSize\(""")
                    .containsMatchIn(text)
            if (walksTree) offenders.add(f.path)
        }
        assertTrue(
            "these files sum file lengths over a tree walk of their own instead of " +
                "calling SessionStorage.directorySize — every hand-rolled copy so far has " +
                "followed symlinks and over-reported: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the known former offenders now delegate to the shared implementation`() {
        // Named explicitly so deleting the delegation is loud, even if the
        // heuristic above ever stops matching.
        for (rel in listOf(
            "src/main/java/com/openminis/app/sandbox/RootfsManager.kt",
            "src/main/java/com/openminis/app/sandbox/offload/DeviceOffloadHandler.kt",
            "src/main/java/com/openminis/app/debug/DebugRPCHandler.kt",
        )) {
            val f = File(rel)
            assertTrue("$rel not found (cwd=${File(".").absolutePath})", f.isFile)
            assertTrue(
                "$rel no longer routes its size walk through SessionStorage.directorySize",
                f.readText().contains("SessionStorage.directorySize"),
            )
        }
    }
}
