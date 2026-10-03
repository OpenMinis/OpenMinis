package com.openminis.app.data.repository

import java.io.File

/**
 * [T-android-skill-scan-parity] Pure disk-side helpers for [SkillRepository].
 *
 * Kept free of SQLite so the scan cost and the prune rule can be tested on a
 * plain JVM with real temp directories.
 */
internal object SkillDiskSignature {

    /** Minimum custom skills before the breaker applies — tiny libraries prune freely. */
    private const val BREAKER_MIN_COUNT = 3

    /** Share of custom skills missing at once that reads as "storage not mounted", not "user deleted". */
    private const val BREAKER_RATIO = 0.40

    /**
     * Ids of skill directories under [skillsDir] that contain a SKILL.md.
     * One listFiles() plus one stat per directory — O(n).
     */
    fun skillIdsOnDisk(skillsDir: File): Set<String> {
        val dirs = skillsDir.listFiles() ?: return emptySet()
        val ids = HashSet<String>(dirs.size)
        for (dir in dirs) {
            if (dir.isDirectory && File(dir, "SKILL.md").isFile) ids.add(dir.name)
        }
        return ids
    }

    /**
     * Cheap fingerprint of everything a reload would pick up: which skill
     * directories exist, and each SKILL.md's mtime and size. Stands in for the
     * iOS fakefs change notifier, which Android's proot has no equivalent of.
     *
     * Covers the shell cases that matter: `git clone` / `mkdir` + write (new
     * directory), `rm -rf` (directory gone), and `cat >` / `sed -i` / editor
     * saves on an existing SKILL.md (mtime or size change). Files other than
     * SKILL.md are ignored — nothing loaded into memory reads them.
     *
     * Returns 0 for a missing directory, which differs from any populated one.
     */
    fun compute(skillsDir: File): Long {
        val dirs = skillsDir.listFiles() ?: return 0L
        dirs.sortBy { it.name }
        var h = 1125899906842597L
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val md = File(dir, "SKILL.md")
            h = 31 * h + dir.name.hashCode()
            h = 31 * h + md.lastModified()
            h = 31 * h + md.length()
        }
        return h
    }

    /**
     * [T-android-skill-circuit-breaker] True when so many custom skills are
     * missing at once that the storage is more likely unmounted or mid-restore
     * than deliberately emptied, so no orphan row may be pruned this load.
     */
    fun breakerTripped(totalCustom: Int, missingCustom: Int): Boolean =
        totalCustom >= BREAKER_MIN_COUNT &&
            missingCustom >= BREAKER_MIN_COUNT &&
            missingCustom.toDouble() / totalCustom >= BREAKER_RATIO
}
