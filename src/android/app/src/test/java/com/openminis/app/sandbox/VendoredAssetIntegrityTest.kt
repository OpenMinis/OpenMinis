package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Integrity guard for the ZIPs vendored under `src/main/assets`.
 *
 * **STATUS: DORMANT.** Since #25 (built on `18dff0c`) no `.zip` is vendored at
 * all: the SDK host tools (`android-sdk-tools-aarch64.zip`,
 * `android-cmdline-tools.zip`) left the APK and are downloaded on demand inside
 * the guest by `minis-android-sdk-setup`. Every test here therefore reports as
 * SKIPPED rather than passed — they state out loud why they did not run instead
 * of silently returning from an empty loop, which is how a guard goes rotten
 * without anyone noticing. [assetsCarryNoRetiredVendoredZip] is the one test
 * that always runs, and [DORMANT_REASON] is the message it prints.
 *
 * The class is kept on purpose: the four guards below are the only automated
 * check for a future re-vendor, and the damage mode they were written for is
 * genuinely nasty. Historical behaviour this encodes:
 *
 * These archives were fetched verbatim from third-party releases by
 * `scripts/prepare_android_sandbox.sh` and committed to the tree. A damaged
 * blob therefore ships to every device, and it fails in a way that is very
 * hard to attribute:
 *
 *  - `assets/android-sdk-tools-aarch64.zip` 35.0.2 as published has 512 KiB
 *    inserted part-way through the member data. The EOCD's central-directory
 *    offset and the per-entry local-header offsets were never fixed up.
 *  - Random-access readers (Info-ZIP `unzip`, [ZipFile], Python `zipfile`)
 *    cannot locate the directory at all and report the misleading
 *    "overlapped components (possible zip bomb)".
 *  - The streaming reader [ZipInputStream] — which is what
 *    `RootfsManager.extractSdkZip` uses on device — walks the first members
 *    fine and then throws mid-inflate. `installBundledAndroidSdkTools` catches
 *    that, **deletes the partially-extracted build-tools and platform-tools
 *    directories**, retries, fails again, and logs one line. The device ends up
 *    with no bundled aarch64 aapt2 at all, and the symptom surfaces much later
 *    as "AAPT2 Daemon startup failed" when AGP falls back to the x86_64 aapt2
 *    it downloads from Maven.
 *
 * So the check that actually matters is not "does it open" but "do the two
 * reader families agree". [streamingAndRandomAccessSeeTheSameMembers] encodes
 * exactly that disagreement.
 *
 * Note that anything sitting in `src/main/assets` on disk is packaged by AGP
 * even when it is not tracked by git — which is why a stale leftover of a
 * retired zip is a real shipping hazard, not a cosmetic one.
 *
 * Run with: `:app:testReleaseUnitTest --tests com.openminis.app.sandbox.VendoredAssetIntegrityTest`
 */
class VendoredAssetIntegrityTest {

    /**
     * The two archives this guard was written for, un-vendored in #25. Kept as
     * data so [assetsCarryNoRetiredVendoredZip] can name them.
     */
    private val retiredVendoredZips = listOf(
        "android-sdk-tools-aarch64.zip",
        "android-cmdline-tools.zip",
    )

    private val dormantReason: String
        get() = "no .zip is vendored in src/main/assets any more (SDK host tools " +
            "download on demand via minis-android-sdk-setup since #25) — re-vendor " +
            "a zip and this guard becomes live again"

    private fun assetsDir(): File =
        find("src/android/app/src/main/assets")
            ?: find("app/src/main/assets")
            ?: find("src/main/assets")
            ?: error("assets dir not found from ${System.getProperty("user.dir")}")

    private fun find(relative: String): File? {
        var dir = File(System.getProperty("user.dir") ?: return null)
        repeat(8) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun vendoredZips(): List<File> =
        assetsDir().listFiles { f -> f.isFile && f.name.endsWith(".zip") }?.sortedBy { it.name }
            ?: emptyList()

    /**
     * A named archive that no longer ships is a SKIP, not a silent pass. The
     * message names the removal so the reason survives in the test report.
     */
    private fun requireVendoredZip(name: String): File {
        val zip = File(assetsDir(), name)
        assumeTrue("SKIPPED: $name is absent — $dormantReason", zip.isFile)
        return zip
    }

    /** Every vendored archive must survive a full CRC-checked random-access read. */
    @Test
    fun everyVendoredZipInflatesUnderRandomAccess() {
        val zips = vendoredZips()
        // An empty set is the expected steady state since #25; skip loudly so the
        // dormancy shows up in the report instead of hiding behind a green check.
        assumeTrue("SKIPPED: assets/ holds no .zip — $dormantReason", zips.isNotEmpty())
        for (zip in zips) {
            ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                assertTrue("${zip.name}: central directory is empty", entries.isNotEmpty())
                for (e in entries) {
                    // Reading to EOF forces the inflater and the CRC check.
                    val n = zf.getInputStream(e).use { it.readBytes().size.toLong() }
                    assertEquals(
                        "${zip.name}!${e.name}: uncompressed size mismatch",
                        e.size, n
                    )
                }
            }
        }
    }

    /**
     * The on-device extractor streams. If streaming sees fewer members than the
     * central directory promises, extraction stops early and the tools after the
     * damage never land — silently, because the caller deletes the partial
     * result and only logs.
     */
    @Test
    fun streamingAndRandomAccessSeeTheSameMembers() {
        val zips = vendoredZips()
        assumeTrue("SKIPPED: assets/ holds no .zip — $dormantReason", zips.isNotEmpty())
        for (zip in zips) {
            val viaRandomAccess = ZipFile(zip).use { zf ->
                zf.entries().toList().map { it.name }.sorted()
            }
            val viaStream = mutableListOf<String>()
            zip.inputStream().use { raw ->
                ZipInputStream(raw).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        // Mirror extractSdkZip: actually consume the payload so an
                        // inflate failure throws here instead of being skipped.
                        zis.readBytes()
                        viaStream += entry.name
                    }
                }
            }
            assertEquals(
                "${zip.name}: ZipInputStream reached fewer members than the central " +
                    "directory lists — on-device extraction would stop early. " +
                    "Repair with scripts/repair_vendor_zip.py.",
                viaRandomAccess,
                viaStream.sorted()
            )
        }
    }

    /**
     * The SDK-tools archive must carry an *aarch64* aapt2. Google's official
     * build-tools are x86_64 only; these are the AOSP static aarch64 builds the
     * sandbox relies on. A valid archive with the wrong architecture inside is
     * just as broken as a corrupt one, and just as silent.
     */
    @Test
    fun bundledSdkToolsAreAarch64Elf() {
        val zip = requireVendoredZip("android-sdk-tools-aarch64.zip")
        val required = listOf(
            "build-tools/aapt2",
            "build-tools/zipalign",
            "platform-tools/adb",
        )
        ZipFile(zip).use { zf ->
            for (name in required) {
                val entry = zf.getEntry(name)
                assertNotNull("${zip.name}: missing required member $name", entry)
                val head = zf.getInputStream(entry).use { it.readNBytes(20) }
                assertEquals(
                    "$name: not an ELF (got ${head.take(4).joinToString { "%02x".format(it) }})",
                    listOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
                    head.take(4)
                )
                assertEquals("$name: not a 64-bit ELF", 2, head[4].toInt())
                // e_machine lives at offset 18, little-endian. 0xB7 = EM_AARCH64.
                val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
                assertEquals("$name: ELF machine is $machine, expected 0xB7 (EM_AARCH64)", 0xB7, machine)
            }
        }
    }

    /**
     * The five members that sat *after* the 512 KiB insertion. They are the ones
     * a streaming reader lost, so they are the ones worth naming explicitly —
     * if a future re-vendor drops them the message should say which.
     */
    @Test
    fun previouslyLostPlatformToolsArePresent() {
        val zip = requireVendoredZip("android-sdk-tools-aarch64.zip")
        val expected = listOf(
            "platform-tools/make_f2fs",
            "platform-tools/make_f2fs_casefold",
            "platform-tools/mke2fs",
            "platform-tools/sload_f2fs",
            "platform-tools/sqlite3",
        )
        ZipFile(zip).use { zf ->
            val names = zf.entries().toList().map { it.name }
            for (name in expected) {
                assertTrue(
                    "${zip.name}: $name is missing — it was one of the members lost " +
                        "to the offset shift; re-run scripts/repair_vendor_zip.py",
                    name in names
                )
            }
        }
    }

    /**
     * The dormancy itself, pinned as an assertion instead of an early return —
     * the only test in this class that always runs.
     *
     * #25 un-vendored these two archives and nothing is supposed to put them
     * back. AGP packages whatever sits in `src/main/assets` even when git does
     * not track it, so a stale leftover of either name still ships to every
     * device — and `android-sdk-tools-aarch64.zip` is exactly the one that
     * shipped damaged.
     *
     * A deliberate re-vendor under these names SHOULD fail here: that is the
     * signal that the four integrity guards above went live again, so the
     * steady state this test pins has to be re-checked (and this assertion
     * retired) in the same change.
     */
    @Test
    fun assetsCarryNoRetiredVendoredZip() {
        val present = vendoredZips()
            .map { it.name }
            .filter { it in retiredVendoredZips }
            .sorted()
        assertTrue(
            "src/main/assets still carries retired vendored archives: $present — they " +
                "were un-vendored in #25 (the SDK host tools now download on demand via " +
                "minis-android-sdk-setup) and AGP would package them into the APK anyway. " +
                "Delete them; or, if this is an intentional re-vendor, the four integrity " +
                "guards in this class are live again — run them and retire this assertion " +
                "in the same change.",
            present.isEmpty()
        )
    }
}
