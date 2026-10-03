package com.openminis.app.backup

import java.text.BreakIterator
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * [T-android-backup-package-name-parity] The one place a `.minisbak` filename
 * is built — a port of iOS `BackupExporter.packageFileName` and its helpers,
 * which must produce the SAME name for the same inputs.
 *
 * Shape: `<device>-<yyyyMMdd>-<sortable-id>[-encrypted].minisbak`, e.g.
 * `Alexs-Pixel-20260817-m08r03g0nz0.minisbak`.
 *
 * Android used to write `backup-<yyyyMMdd-HHmm>-<6 hex of the id>.minisbak`,
 * the shape iOS dropped in [T-backup-package-name-device]. That name carries
 * no device, so packages from several devices in one NAS folder were
 * indistinguishable; an encrypted package looked the same as a plain one until
 * someone tried to open it; and the random id slice could not order two
 * backups taken in the same minute. Nothing parses the name (importers read
 * the manifest), so old packages stay restorable.
 */
object BackupPackageName {

    /**
     * Build the filename. [atMillis] is the run's snapshot instant, not "now",
     * so the date and the id agree and a retried run keeps its name. [zone]
     * only exists for tests; the date is local, as on iOS.
     */
    fun packageFileName(
        backupId: String,
        atMillis: Long,
        deviceName: String,
        encrypted: Boolean = false,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val device = filenameDeviceToken(deviceName)
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(atMillis)
        val suffix = if (encrypted) "-encrypted" else ""
        return "$device-$stamp-${sortableId(backupId, atMillis)}$suffix.${BackupFormat.FILE_EXTENSION}"
    }

    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    /**
     * 8 characters of Crockford-style Base32 over the millisecond timestamp,
     * then 3 from an FNV-1a hash of [backupId]. The alphabet is monotonic in
     * ASCII, so name order is time order — which is what allows the clock time
     * to be left out of the name. 40 bits of milliseconds last until year
     * 36812; the id part separates two runs in the same millisecond.
     */
    fun sortableId(backupId: String, atMillis: Long): String {
        val ms = atMillis.coerceAtLeast(0L) and 0xFF_FFFF_FFFFL
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325, FNV-1a offset basis
        for (b in backupId.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L // wraps like Swift's &*
        }
        return encode(ms, 8) + encode(h, 3)
    }

    private fun encode(value: Long, width: Int): String {
        val out = CharArray(width)
        var v = value
        for (i in width - 1 downTo 0) {
            out[i] = ALPHABET[(v and 31).toInt()]
            v = v ushr 5
        }
        return String(out)
    }

    /**
     * A filename-safe ASCII token from a user-controlled device name: ASCII
     * letters and digits kept, apostrophes elided (`Alex's` → `Alexs`), any
     * other run collapsed to one `-`, capped at 24 characters. The full name
     * still goes into the manifest's `device_name`.
     *
     * Walks grapheme clusters, not chars, because that is what a Swift
     * `Character` is: a decomposed `é` (e + U+0301) is one non-ASCII
     * character on iOS, and iterating chars would keep its `e` and give a
     * different token for the same name.
     */
    fun filenameDeviceToken(raw: String, fallback: String = "device"): String {
        val out = StringBuilder()
        var lastWasSeparator = false
        val it = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(raw) }
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = raw.substring(start, end)
            val c = g[0]
            when {
                g.length == 1 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') -> {
                    out.append(c)
                    lastWasSeparator = false
                }
                g == "'" || g == "’" -> Unit
                out.isNotEmpty() && !lastWasSeparator -> {
                    out.append('-')
                    lastWasSeparator = true
                }
            }
            start = end
            end = it.next()
        }
        var token = out.toString().trimEnd('-')
        if (token.length > 24) token = token.take(24).trimEnd('-')
        return token.ifEmpty { fallback }
    }
}
