package com.openminis.app.backup

import java.io.File
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-package-name-parity] Android must name a package exactly
 * as iOS does. Every expected value below was produced by running the iOS
 * implementation (the logic in MinisTests/Standalone/BackupPackageNameTests.swift,
 * itself pinned to BackupExporter.swift) on the same input, with the date
 * formatter in UTC — so a mismatch here is a cross-platform divergence, not a
 * matter of taste.
 */
class BackupPackageNameTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val uuid = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"

    @Test
    fun `full filename matches iOS for the same inputs`() {
        assertEquals(
            "Alexs-Pixel-20260817-m08r03g0nz0.minisbak",
            BackupPackageName.packageFileName(uuid, 1_787_000_000_000L, "Alex's Pixel", zone = utc),
        )
        assertEquals(
            "Alexs-Pixel-20260817-m08r03g0nz0-encrypted.minisbak",
            BackupPackageName.packageFileName(uuid, 1_787_000_000_000L, "Alex's Pixel", encrypted = true, zone = utc),
        )
    }

    @Test
    fun `sortable id matches iOS, including the hash and the 40-bit edge`() {
        assertEquals("m08r03g0nz0", BackupPackageName.sortableId(uuid, 1_787_000_000_000L))
        assertEquals("000000005r7", BackupPackageName.sortableId("x", 0L))
        assertEquals("000000015r7", BackupPackageName.sortableId("x", 1L))
        assertEquals("zzzzzzzzep0", BackupPackageName.sortableId("id", 0xFF_FFFF_FFFFL))
        assertEquals("k5xb0mvv8s5", BackupPackageName.sortableId("", 1_758_700_000_123L))
    }

    @Test
    fun `device token matches iOS for awkward names`() {
        val cases = listOf(
            "Alex's iPhone" to "Alexs-iPhone",
            "Alex’s iPhone" to "Alexs-iPhone",
            "张三的 Pixel" to "Pixel",
            "小米手机" to "device",
            "  Pixel 6 Pro  " to "Pixel-6-Pro",
            "a/b:c" to "a-b-c",
            "Samsung Galaxy S24 Ultra Enterprise Edition" to "Samsung-Galaxy-S24-Ultra",
            // A decomposed é is ONE Swift Character, so its `e` is dropped too.
            "école" to "cole",
            "Café Phone" to "Caf-Phone",
            "🙂 Phone" to "Phone",
            "" to "device",
            "Pixel--6__x" to "Pixel-6-x",
            "ABCDEFGHIJKLMNOPQRSTUVW-XYZ" to "ABCDEFGHIJKLMNOPQRSTUVW",
        )
        for ((raw, expected) in cases) {
            assertEquals("token for '$raw'", expected, BackupPackageName.filenameDeviceToken(raw))
        }
    }

    @Test
    fun `names of one device sort in time order`() {
        val base = 1_787_000_000_000L
        var prev = BackupPackageName.packageFileName("a", base, "Pixel 6", zone = utc)
        for (i in 1..2000) {
            val cur = BackupPackageName.packageFileName("id$i", base + i * 37_913L, "Pixel 6", zone = utc)
            assertTrue("$prev < $cur", prev < cur)
            prev = cur
        }
        // Crossing every Base32 digit boundary is where a naive encoder breaks.
        for (shift in 5..39) {
            val edge = 1L shl shift
            assertTrue(BackupPackageName.sortableId("id", edge - 2) < BackupPackageName.sortableId("id", edge + 2))
        }
    }

    @Test
    fun `two runs in the same millisecond get different names`() {
        val a = BackupPackageName.packageFileName("run-a", 1_787_000_000_000L, "Pixel 6", zone = utc)
        val b = BackupPackageName.packageFileName("run-b", 1_787_000_000_000L, "Pixel 6", zone = utc)
        assertFalse(a == b)
    }

    @Test
    fun `the exporter names packages through BackupPackageName, not the old backup- shape`() {
        val src = File("src/main/java/com/openminis/app/backup/BackupExporter.kt").readText()
        assertTrue(src.contains("BackupPackageName.packageFileName(backupId, snapshotAtMillis, deviceName, encrypted)"))
        assertTrue(src.contains("encrypted = encryption != null"))
        assertTrue("manifest uses the same name", src.contains("deviceName = deviceName,"))
        assertFalse(src.contains("\"backup-\$stamp"))
    }
}
