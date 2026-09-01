package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages skill metadata (SQLite) and SKILL.md files on disk.
 * Mirrors iOS SkillStore architecture:
 *   - Metadata in `skills.db` (name, description, version, source, enabled)
 *   - SKILL.md in `minis-global/skills/<id>/SKILL.md`
 *   - Session overrides in `session_skill_overrides` table
 *   - Prompt fragment generation for system prompt injection
 */
class SkillRepository(private val context: Context) {

    companion object {
        private const val TAG = "SkillRepository"
        private const val DB_NAME = "skills.db"
        private const val DB_VERSION = 3
        private const val MAX_SKILLS_IN_PROMPT = 20
        private const val MAX_SKILL_DESC_LENGTH = 200
        private const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000
        private const val RECENT_SLOTS = 10
        private const val NORMALIZE_THRESHOLD = 1000.0

        /** [T-android-skill-export] How long an exported zip stays on disk
         *  before the next export sweeps it. 24h matches iOS — long enough that
         *  any Save-to-Files / AirDrop / upload consumer has finished. */
        private const val EXPORT_TTL_MS = 24L * 3600 * 1000
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Repository-owned scope for fire-and-forget background work that must
     * outlive the calling Composable / ViewModel — notably the sibling-file
     * download that runs after a SKILL.md import. SupervisorJob so a single
     * download failure doesn't poison the next one.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- Data Types --

    enum class ImportSource(val value: String) {
        URL("url"),
        FILE("file"),
        BUNDLED("bundled"),
        SESSION("session");

        companion object {
            fun from(value: String): ImportSource =
                entries.find { it.value == value } ?: FILE
        }
    }

    data class Skill(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val version: String = "1.0.0",
        val importSource: ImportSource = ImportSource.FILE,
        val isEnabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        val body: String = "",
        /** Original GitHub URL when importSource is URL (null otherwise). */
        val sourceURL: String? = null,
        /** Cumulative read count of this skill's SKILL.md; normalized to 0–100 after exceeding 1000. */
        val useCount: Double = 0.0,
    )

    enum class UsageFrequency { NEVER, LOW, REGULAR, HIGH }

    // -- State --

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val db: SQLiteDatabase by lazy {
        SkillDbHelper(context).writableDatabase
    }

    private val skillsDir: File
        get() = File(context.filesDir, "minis-global/skills")

    init {
        // [T-android-safemode-lateinit-crash-147] Never let a bad skill take
        // the whole app down. This constructor runs inline in
        // MinisApp.onCreate, BEFORE subsystemsInitialized is set, so anything
        // escaping here aborts onCreate with the repositories half-assigned.
        // The Application object is then permanently broken (onCreate never
        // re-runs), every subsequent launch crashes on the first Compose frame
        // with "lateinit property chatRepository has not been initialized",
        // and each crash writes a log that re-trips the crash-burst detector —
        // the self-sustaining loop reported in GH#147, where the user could
        // only recover by reinstalling.
        //
        // loadAll() reads rows written by third-party skill imports and uses
        // getColumnIndexOrThrow plus SKILL.md parsing, so a malformed skill
        // from an external hub is exactly the kind of input that can throw.
        // Degrading to "some skills missing from the list" is always better
        // than an app that cannot start.
        runCatching { loadAll() }.onFailure {
            Log.e(TAG, "loadAll failed — continuing with ${_skills.value.size} skill(s): ${it.message}", it)
        }
        runCatching { installBundledSkills() }.onFailure {
            Log.e(TAG, "installBundledSkills failed — continuing: ${it.message}", it)
        }
    }

    // -- CRUD --

    fun add(name: String, description: String, body: String, version: String = "1.0.0", source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val id = slugify(name)
        if (id.isBlank()) return null
        if (_skills.value.any { it.id == id }) return null

        val skill = Skill(
            id = id,
            name = name,
            description = description,
            version = version,
            importSource = source,
            body = body,
            sourceURL = sourceURL,
        )

        insertDb(skill)
        writeSkillMd(skill)
        _skills.value = _skills.value + skill
        Log.i(TAG, "Added skill: ${skill.id}")
        return skill
    }

    fun update(id: String, name: String? = null, description: String? = null, body: String? = null): Boolean {
        val current = _skills.value.find { it.id == id } ?: return false
        val updated = current.copy(
            name = name ?: current.name,
            description = description ?: current.description,
            body = body ?: current.body,
            updatedAt = System.currentTimeMillis(),
        )

        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(updated.name, updated.description, updated.version, updated.updatedAt, id)
        )
        writeSkillMd(updated)
        _skills.value = _skills.value.map { if (it.id == id) updated else it }
        return true
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
        db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
        val dir = File(skillsDir, id)
        dir.deleteRecursively()
        _skills.value = _skills.value.filter { it.id != id }
        Log.i(TAG, "Deleted skill: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        db.execSQL("UPDATE skills SET is_enabled=? WHERE id=?", arrayOf<Any>(if (enabled) 1 else 0, id))
        _skills.value = _skills.value.map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
    }

    // -- Session Overrides --

    fun isEnabledForSession(skillId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
            arrayOf(sessionId, skillId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _skills.value.find { it.id == skillId }?.isEnabled ?: false
    }

    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        db.execSQL(
            "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
            arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0)
        )
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    /**
     * [T-android-session-skill-override-init-timing] Re-point every
     * `session_skill_overrides` row that was written against the draft
     * session id ([fromDraft], e.g. `__new__<uuid>`) onto the persisted
     * session id ([toReal]) once `ensureSession()` creates the real DB row.
     * Without this hop, a pre-first-message skill toggle stays bound to the
     * draft key and becomes invisible the next time the chat is opened
     * under its real id — exactly the symptom XIN reported. Paired with
     * [MCPRepository.renameSessionOverrides].
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE session_skill_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
    }

    // -- Prompt Fragment --

    /**
     * Build the system-prompt fragment that makes skills discoverable.
     * Discloses up to [MAX_SKILLS_IN_PROMPT] skills with 3-tier priority
     * (bundled > 7-day recent > most-used), matching iOS SkillStore.
     * Returns null when the session has no enabled skills.
     */
    fun skillPromptFragment(sessionId: String): String? {
        val enabled = _skills.value.filter { isEnabledForSession(it.id, sessionId) }
        if (enabled.isEmpty()) return null

        val total = enabled.size
        val selected: List<Skill>
        val hasMore: Boolean

        if (total <= MAX_SKILLS_IN_PROMPT) {
            selected = enabled.sortedByDescending { it.updatedAt }
            hasMore = false
        } else {
            val picked = linkedMapOf<String, Skill>() // preserves insertion order + id dedupe
            // Priority 1: bundled
            enabled.filter { it.importSource == ImportSource.BUNDLED }
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 2: recently updated (within 7 days), up to RECENT_SLOTS more
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            val recentLimit = minOf(RECENT_SLOTS, MAX_SKILLS_IN_PROMPT - picked.size).coerceAtLeast(0)
            enabled.asSequence()
                .filter { it.updatedAt > cutoff && it.id !in picked }
                .sortedByDescending { it.updatedAt }
                .take(recentLimit)
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 3: fill remaining slots by useCount (desc)
            if (picked.size < MAX_SKILLS_IN_PROMPT) {
                val remaining = MAX_SKILLS_IN_PROMPT - picked.size
                enabled.asSequence()
                    .filter { it.id !in picked }
                    .sortedByDescending { it.useCount }
                    .take(remaining)
                    .forEach { picked.putIfAbsent(it.id, it) }
            }
            selected = picked.values.toList()
            hasMore = total > selected.size
        }

        val xml = buildString {
            append("<available_skills>\n")
            for (skill in selected) {
                var desc = skill.description
                if (desc.length > MAX_SKILL_DESC_LENGTH) {
                    desc = desc.substring(0, MAX_SKILL_DESC_LENGTH) + "…"
                }
                append("  <skill>\n")
                append("    <name>").append(escapeXml(skill.name)).append("</name>\n")
                append("    <description>").append(escapeXml(desc)).append("</description>\n")
                append("    <path>/var/minis/skills/").append(skill.id).append("/SKILL.md</path>\n")
                append("  </skill>\n")
            }
            append("</available_skills>")
        }

        return buildString {
            append("Skills:\n")
            append("Reusable instruction sets stored at /var/minis/skills/<name>/SKILL.md. Read the SKILL.md file to load full instructions before using a skill.\n\n")
            append(xml)
            if (hasMore) {
                val selectedIds = selected.mapTo(HashSet(selected.size)) { it.id }
                val omitted = enabled.filter { it.id !in selectedIds }
                val maxUndisclosed = (100 - selected.size).coerceAtLeast(0)
                val names = omitted.take(maxUndisclosed).joinToString(", ") { it.name }
                append("\n\n")
                append(omitted.size).append(" more skills not shown above: ").append(names)
                append(". List /var/minis/skills/ or grep to search all.")
            }
        }
    }

    /**
     * Record that a skill's SKILL.md was read. Matches iOS `SkillStore.recordSkillUse`:
     * bumps `useCount` by 1 and normalizes all counts to 0–100 when any exceeds 1000,
     * so long-lived installs don't drift into multi-thousand-read territory.
     */
    fun recordSkillUse(skillId: String) {
        val current = _skills.value.find { it.id == skillId } ?: return
        val bumped = current.copy(useCount = current.useCount + 1.0)
        db.execSQL(
            "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
            arrayOf<Any>(skillId)
        )
        var next = _skills.value.map { if (it.id == skillId) bumped else it }
        if (bumped.useCount > NORMALIZE_THRESHOLD) {
            next = normalizeUseCounts(next)
        }
        _skills.value = next
    }

    private fun normalizeUseCounts(list: List<Skill>): List<Skill> {
        val maxCount = list.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return list
        val scaled = list.map { it.copy(useCount = it.useCount / maxCount * 100.0) }
        db.beginTransaction()
        try {
            for (s in scaled) {
                db.execSQL(
                    "UPDATE skills SET use_count=? WHERE id=?",
                    arrayOf<Any>(s.useCount, s.id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return scaled
    }

    /** Coarse-grained usage band for UI surfacing (matches iOS UsageFrequency). */
    fun usageFrequency(skillId: String): UsageFrequency {
        val skill = _skills.value.find { it.id == skillId } ?: return UsageFrequency.NEVER
        if (skill.useCount == 0.0) return UsageFrequency.NEVER
        val maxCount = _skills.value.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return UsageFrequency.NEVER
        val normalized = skill.useCount / maxCount * 100.0
        return when {
            normalized < 20.0 -> UsageFrequency.LOW
            normalized < 60.0 -> UsageFrequency.REGULAR
            else -> UsageFrequency.HIGH
        }
    }

    /**
     * Extract skill id from `/var/minis/skills/<id>/SKILL.md`. Returns null unless
     * the path is exactly a SKILL.md read of a known skill — sub-resource reads
     * under `scripts/` etc. don't count toward usage.
     */
    fun skillIdFromPath(path: String): String? {
        val prefix = "/var/minis/skills/"
        if (!path.startsWith(prefix)) return null
        if (!path.endsWith("/SKILL.md")) return null
        val rest = path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val candidate = rest.substring(0, slash)
        return if (_skills.value.any { it.id == candidate }) candidate else null
    }

    // -- Import from SKILL.md Content --

    /**
     * Parse SKILL.md content (YAML frontmatter + markdown body) and import.
     * Returns the created Skill or null on failure.
     */
    fun importFromContent(content: String, source: ImportSource = ImportSource.FILE, sourceURL: String? = null): Skill? {
        val parsed = parseSkillMd(content) ?: return null
        val id = slugify(parsed.name)
        if (id.isNotBlank() && _skills.value.any { it.id == id }) {
            // Replace existing: update in place so URL updates refresh contents.
            val current = _skills.value.first { it.id == id }
            val replaced = current.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                importSource = source,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
                sourceURL = sourceURL ?: current.sourceURL,
            )
            db.execSQL(
                "UPDATE skills SET name=?, description=?, version=?, import_source=?, source_url=?, updated_at=? WHERE id=?",
                arrayOf<Any?>(
                    replaced.name, replaced.description, replaced.version,
                    replaced.importSource.value, replaced.sourceURL,
                    replaced.updatedAt, id
                )
            )
            writeSkillMd(replaced)
            _skills.value = _skills.value.map { if (it.id == id) replaced else it }
            return replaced
        }
        return add(
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            version = parsed.version,
            source = source,
            sourceURL = sourceURL,
        )
    }

    /**
     * Returns the SKILL.md file path for a given skill (for display in UI).
     */
    fun skillMdPath(id: String): String = "/var/minis/skills/$id/SKILL.md"

    // -- Import from Zip Archive --

    /**
     * Import a skill from a .zip archive (mirrors iOS `SkillStore.importFromArchive`).
     * The archive must contain a `SKILL.md` at the root or one directory deep;
     * bundled sibling files (scripts/, references/, assets/, etc.) are extracted
     * alongside it into `skillsDir/<id>/`.
     */
    fun importFromArchive(input: InputStream): Skill? {
        val entries = try { readZipEntries(input) } catch (e: Exception) {
            Log.w(TAG, "Failed to read zip archive: ${e.message}")
            return null
        }
        if (entries.isEmpty()) return null

        // Accept SKILL.md at root or one level deep (matches iOS behavior).
        val skillMdEntry = entries.firstOrNull { entry ->
            val name = entry.name
            name == "SKILL.md" || (name.endsWith("/SKILL.md") && name.count { it == '/' } == 1)
        } ?: return null

        val skillContent = try { String(skillMdEntry.data, Charsets.UTF_8) } catch (_: Exception) { return null }
        if (skillContent.isBlank()) return null

        val skill = importFromContent(skillContent, ImportSource.FILE) ?: return null

        val prefix = if (skillMdEntry.name == "SKILL.md") "" else skillMdEntry.name.dropLast("SKILL.md".length)
        val skillDir = File(skillsDir, skill.id)
        skillDir.mkdirs()

        for (entry in entries) {
            if (entry.isDirectory) continue
            var relativePath = entry.name
            if (prefix.isNotEmpty() && relativePath.startsWith(prefix)) {
                relativePath = relativePath.drop(prefix.length)
            }
            if (relativePath == "SKILL.md" || relativePath.startsWith(".") || relativePath.isEmpty()) continue
            // Guard against zip-slip: reject path traversal.
            if (relativePath.contains("..")) continue

            val destFile = File(skillDir, relativePath)
            destFile.parentFile?.mkdirs()
            try { destFile.writeBytes(entry.data) } catch (e: Exception) {
                Log.w(TAG, "Failed to write $relativePath: ${e.message}")
            }
        }
        return skill
    }

    private data class ZipEntryData(val name: String, val isDirectory: Boolean, val data: ByteArray)

    private fun readZipEntries(input: InputStream): List<ZipEntryData> {
        val out = mutableListOf<ZipEntryData>()
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                val isDir = entry.isDirectory
                val data = if (isDir) ByteArray(0) else {
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zis.read(chunk)
                        if (n <= 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                out.add(ZipEntryData(name, isDir, data))
                zis.closeEntry()
            }
        }
        return out
    }

    // -- Import from GitHub URL --

    data class GitHubInfo(
        val user: String,
        val repo: String,
        val branch: String,
        /** Directory path (without SKILL.md leaf) */
        val dirPath: String,
    )

    /**
     * Download SKILL.md from a GitHub URL and recursively download sibling files
     * from the same directory. Mirrors iOS SkillStore.importFromGitHub.
     */
    suspend fun importFromGitHub(urlString: String): Skill? = withContext(Dispatchers.IO) {
        val rawURL = githubToRawURL(urlString) ?: return@withContext null
        val content = httpGetString(rawURL) ?: return@withContext null
        val skill = importFromContent(content, ImportSource.URL, sourceURL = urlString) ?: return@withContext null

        val ghInfo = parseGitHubURL(urlString) ?: return@withContext skill
        val skillDir = File(skillsDir, skill.id)
        // Detach the sibling-file download from the caller's coroutine scope.
        // The previous design ran the recursion inline, so any navigation
        // away from the import screen — or a ViewModel finishing its initial
        // composition — would cancel the recursion mid-flight and leave the
        // skill with only SKILL.md (T152 root cause).
        backgroundScope.launch {
            val outcome = downloadSiblingFiles(ghInfo, skillDir, skill)
            val bumped = skill.copy(updatedAt = System.currentTimeMillis())
            db.execSQL(
                "UPDATE skills SET updated_at=? WHERE id=?",
                arrayOf<Any>(bumped.updatedAt, skill.id)
            )
            _skills.value = _skills.value.map { if (it.id == skill.id) bumped else it }
            if (!outcome.isComplete) {
                Log.w(TAG, "importFromGitHub partial for ${skill.id}: ${outcome.reason ?: "${outcome.filesFailed} file(s) failed"}")
            }
        }
        skill
    }

    /** Outcome of [updateFromURL]: either an updated skill or a human-readable reason. */
    sealed class UpdateResult {
        data class Success(val skill: Skill) : UpdateResult()
        /**
         * SKILL.md fetched + persisted, but the sibling-file recursion
         * (scripts/, references/, …) ran into trouble. The skill is usable
         * but its bundled resources may be incomplete. T152 fix: this state
         * used to be silently squashed into a plain Success.
         */
        data class PartialSuccess(val skill: Skill, val reason: String) : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    /**
     * Aggregate outcome of a sibling-file download walk — surfaced through
     * [UpdateResult.PartialSuccess] when SKILL.md imported but sibling files
     * (scripts/, references/, …) could not all be retrieved. `reason` is
     * non-null only when [filesFailed] > 0 or the recursion bailed before
     * listing the directory at all.
     */
    data class SiblingDownloadOutcome(
        val filesWritten: Int,
        val filesFailed: Int,
        val reason: String?,
    ) {
        val isComplete: Boolean get() = filesFailed == 0 && reason == null
    }

    /**
     * Re-fetch the SKILL.md for an existing URL-sourced skill and update its
     * record in place — does NOT create a new skill. Also re-downloads sibling
     * files from the same GitHub directory so bundled scripts/references stay
     * in sync with upstream. Returns a typed result with an explicit reason on
     * failure so the UI can surface *why* the update failed.
     */
    suspend fun updateFromURL(skillId: String): UpdateResult = withContext(Dispatchers.IO) {
        val existing = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill not found")
        if (existing.importSource != ImportSource.URL) {
            return@withContext UpdateResult.Failure("Skill was not imported from a URL")
        }
        val urlString = existing.sourceURL
        if (urlString.isNullOrBlank()) {
            // Older imports (pre-fix) never persisted the source URL. Users need
            // to re-import the skill from Minis Skills so the URL gets saved.
            return@withContext UpdateResult.Failure(
                "No source URL on file. Re-import this skill from Minis Skills to enable updates."
            )
        }

        val rawURL = githubToRawURL(urlString)
            ?: return@withContext UpdateResult.Failure("Could not build a raw download URL from $urlString")
        val content = try { httpGetString(rawURL) } catch (e: Exception) {
            return@withContext UpdateResult.Failure("Network error: ${e.message ?: "unknown"}")
        }
        if (content == null) {
            return@withContext UpdateResult.Failure("Download failed (HTTP error) for $rawURL")
        }
        val parsed = parseSkillMd(content)
            ?: return@withContext UpdateResult.Failure("Downloaded SKILL.md is not valid (missing YAML frontmatter or 'name:' field)")

        val ok = update(
            id = skillId,
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
        )
        if (!ok) return@withContext UpdateResult.Failure("Failed to write updated skill")

        // Re-download sibling files (scripts/, references/, etc.). Unlike
        // importFromGitHub, the user invoked this synchronously and is
        // looking at a spinner — so we await the outcome here and surface
        // any failure as PartialSuccess so the UI can show *what* went
        // wrong (e.g. "GitHub API 403 (likely rate limited)") instead of
        // claiming success while scripts/ stays empty.
        val ghInfo = parseGitHubURL(urlString)
        var siblingOutcome: SiblingDownloadOutcome? = null
        if (ghInfo != null) {
            val skillDir = File(skillsDir, skillId)
            val current = _skills.value.first { it.id == skillId }
            siblingOutcome = downloadSiblingFiles(ghInfo, skillDir, current)
        }

        val fresh = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill disappeared during update")
        if (siblingOutcome != null && !siblingOutcome.isComplete) {
            val reason = siblingOutcome.reason
                ?: "${siblingOutcome.filesFailed} sibling file(s) failed to download"
            return@withContext UpdateResult.PartialSuccess(fresh, reason)
        }
        UpdateResult.Success(fresh)
    }

    // -- Export to Zip Archive --

    /**
     * [T-android-skill-export] Zip the whole `skillsDir/<id>/` directory for
     * sharing. Android port of iOS `SkillDetailView.shareSkill`; the round-trip
     * partner of [importFromArchive], which already accepts exactly this shape
     * (SKILL.md at the archive root plus any sibling files).
     *
     * Three safeguards carried over from iOS, each of which fixed a real bug
     * there — do not simplify them away:
     *
     *  1. **Unique per-export directory** (`share/skill-export-<uuid>/`).
     *     Consecutive shares of the same skill must not overwrite or delete a
     *     zip that an earlier share's consumer (Save to Files / Drive / a chat
     *     app) may still be reading.
     *  2. **Non-empty verification** before returning. iOS shipped an "exported
     *     an empty file" bug precisely because a failed copy was swallowed;
     *     here any failure throws or returns null rather than vending a zip
     *     nobody can import.
     *  3. **Stale sweep** of exports older than [EXPORT_TTL_MS], run before each
     *     new export instead of deleting on share-sheet dismissal — by then any
     *     pending consumer of an older export has long finished.
     *
     * Written under `cacheDir/share/` because that is a root already declared
     * in `file_provider_paths.xml`; a file outside a declared root makes
     * `FileProvider.getUriForFile` throw and the user just sees a share failure.
     *
     * @return the zip file, or null when the skill has no directory / no files.
     */
    fun exportSkillToZip(skillId: String): File? {
        val skill = _skills.value.find { it.id == skillId }
        val dir = File(skillsDir, skillId)
        if (!dir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: no directory for $skillId")
            return null
        }
        val relPaths = listSkillFiles(skillId)
        if (relPaths.isEmpty()) {
            Log.w(TAG, "exportSkillToZip: $skillId has no files to export")
            return null
        }

        sweepStaleExports()

        val exportDir = File(File(context.cacheDir, "share"), "skill-export-${UUID.randomUUID()}")
        if (!exportDir.mkdirs() && !exportDir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: could not create $exportDir")
            return null
        }
        // Filesystem-safe, single-component zip name (mirrors iOS's sanitizing).
        val safeName = (skill?.name ?: skillId)
            .replace('/', '-')
            .replace(':', '-')
            .replace('\\', '-')
            .trim()
            .ifEmpty { "skill" }
        val zipFile = File(exportDir, "$safeName.zip")

        return try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                for (rel in relPaths) {
                    val src = File(dir, rel)
                    if (!src.isFile) continue
                    // Store entries with forward slashes — the portable
                    // separator every unzip tool (and importFromArchive) expects.
                    zos.putNextEntry(ZipEntry(rel.replace(File.separatorChar, '/')))
                    FileInputStream(src).buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            // Safeguard 2: never vend an empty/missing archive.
            if (!zipFile.isFile || zipFile.length() <= 0L) {
                Log.w(TAG, "exportSkillToZip: produced an empty zip for $skillId")
                zipFile.delete()
                return null
            }
            Log.i(TAG, "exportSkillToZip: $skillId -> ${zipFile.name} (${zipFile.length()} bytes, ${relPaths.size} files)")
            zipFile
        } catch (e: Exception) {
            Log.w(TAG, "exportSkillToZip failed for $skillId: ${e.message}")
            runCatching { zipFile.delete() }
            null
        }
    }

    /**
     * Safeguard 3: delete `skill-export-*` directories older than
     * [EXPORT_TTL_MS]. Called before each new export rather than when the share
     * sheet closes, so a consumer still reading a previous export is never
     * pulled out from under.
     */
    private fun sweepStaleExports() {
        val root = File(context.cacheDir, "share")
        val now = System.currentTimeMillis()
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            if (!entry.isDirectory || !entry.name.startsWith("skill-export-")) continue
            if (now - entry.lastModified() < EXPORT_TTL_MS) continue
            runCatching { entry.deleteRecursively() }
                .onFailure { Log.w(TAG, "sweepStaleExports: could not delete ${entry.name}") }
        }
    }

    /**
     * List every file inside `skillsDir/<id>/` (recursive). Returns relative
     * paths from the skill root, with SKILL.md first and the rest sorted by
     * name. Used by the Skill detail UI to enumerate bundled scripts.
     */
    fun listSkillFiles(skillId: String): List<String> {
        val dir = File(skillsDir, skillId)
        if (!dir.isDirectory) return emptyList()
        val rootLen = dir.absolutePath.length + 1
        val files = dir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.absolutePath.substring(rootLen) }
            .toList()
        return files.sortedWith(compareBy(
            { if (it.equals("SKILL.md", ignoreCase = true)) 0 else 1 },
            { it },
        ))
    }

    /** Absolute host path for a file inside a skill's directory. */
    fun skillFileHostPath(skillId: String, relativePath: String): String =
        File(File(skillsDir, skillId), relativePath).absolutePath

    /**
     * Re-read SKILL.md from disk and push any frontmatter changes back into the
     * DB + in-memory state. Call after the agent (or user) edits SKILL.md on
     * disk so the store reflects the new name/description/version/body.
     * Returns the refreshed skill or null if the file is gone or malformed.
     */
    fun rescanFromDisk(skillId: String): Skill? {
        val current = _skills.value.find { it.id == skillId } ?: return null
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (!file.exists()) {
            // [T-android-skill-orphan-db-row] A rescan that finds nothing on
            // disk used to only paint "SKILL.md missing or invalid" and leave
            // the record in place, so the one action explicitly meant to
            // reconcile with disk detected the orphan and still refused to act
            // on it. Drop it here too — same BUNDLED exemption as loadAll(),
            // since a bundled skill's file is restored by installBundledSkills.
            if (current.importSource != ImportSource.BUNDLED) {
                delete(skillId)
                Log.i(TAG, "Rescan found no SKILL.md — removed orphan skill: $skillId")
            }
            return null
        }
        val parsed = parseSkillMd(file.readText()) ?: return null
        val refreshed = current.copy(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            body = parsed.body,
            updatedAt = System.currentTimeMillis(),
        )
        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.description, refreshed.version, refreshed.updatedAt, skillId)
        )
        _skills.value = _skills.value.map { if (it.id == skillId) refreshed else it }
        return refreshed
    }

    /**
     * Rename a skill in place — updates the `name:` line in frontmatter and DB
     * but keeps the slugged id (and therefore the path) stable so references
     * from existing sessions don't break.
     */
    fun renameSkill(skillId: String, newName: String): Boolean {
        val current = _skills.value.find { it.id == skillId } ?: return false
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (file.exists()) {
            val original = file.readText()
            val updated = original.replaceFirst(Regex("(?m)^name:\\s*.*$"), "name: $trimmed")
            if (updated != original) file.writeText(updated)
        }
        val refreshed = current.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        db.execSQL(
            "UPDATE skills SET name=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.updatedAt, skillId)
        )
        _skills.value = _skills.value.map { if (it.id == skillId) refreshed else it }
        return true
    }

    /** Read an arbitrary file inside a skill's directory (e.g. scripts/foo.py). */
    fun readSkillFile(skillId: String, relativePath: String): String? {
        if (relativePath.contains("..")) return null
        val file = File(File(skillsDir, skillId), relativePath)
        if (!file.isFile) return null
        return try { file.readText() } catch (_: Exception) { null }
    }

    /** Write an arbitrary file inside a skill's directory. Creates parents. */
    fun writeSkillFile(skillId: String, relativePath: String, content: String): Boolean {
        if (relativePath.contains("..")) return false
        val file = File(File(skillsDir, skillId), relativePath)
        file.parentFile?.mkdirs()
        return try {
            file.writeText(content)
            // SKILL.md edits must flow back to DB metadata.
            if (relativePath.equals("SKILL.md", ignoreCase = true)) rescanFromDisk(skillId)
            true
        } catch (_: Exception) { false }
    }

    private suspend fun downloadSiblingFiles(ghInfo: GitHubInfo, destDir: File, skill: Skill): SiblingDownloadOutcome {
        Log.i(TAG, "[siblings] start skill=${skill.id} dir=${ghInfo.dirPath} repo=${ghInfo.user}/${ghInfo.repo}@${ghInfo.branch}")
        val agg = AggregateOutcome()
        downloadGitHubDirectory(
            user = ghInfo.user,
            repo = ghInfo.repo,
            branch = ghInfo.branch,
            remotePath = ghInfo.dirPath,
            localDir = destDir,
            relativeTo = "",
            skill = skill,
            depth = 0,
            outcome = agg,
        )
        val finalReason = agg.firstReason
        Log.i(TAG, "[siblings] done skill=${skill.id} written=${agg.filesWritten} failed=${agg.filesFailed} reason=${finalReason ?: "(none)"}")
        return SiblingDownloadOutcome(agg.filesWritten, agg.filesFailed, finalReason)
    }

    /**
     * Mutable accumulator threaded through the recursion. Tracks a
     * machine-friendly count plus the FIRST human-readable reason so the UI
     * can show one specific cause (e.g. "GitHub API 403 (rate limited)")
     * rather than a generic "some files failed".
     */
    private class AggregateOutcome {
        var filesWritten = 0
        var filesFailed = 0
        var firstReason: String? = null
        fun recordFailure(reason: String) {
            filesFailed += 1
            if (firstReason == null) firstReason = reason
        }
        fun recordReason(reason: String) {
            if (firstReason == null) firstReason = reason
        }
    }

    private suspend fun downloadGitHubDirectory(
        user: String,
        repo: String,
        branch: String,
        remotePath: String,
        localDir: File,
        relativeTo: String,
        skill: Skill,
        depth: Int,
        outcome: AggregateOutcome,
    ) {
        if (depth > 5) {
            val msg = "Max recursion depth (5) at $remotePath — stopping"
            Log.w(TAG, "[siblings] $msg")
            outcome.recordReason(msg)
            return
        }
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8").replace("+", "%20").replace("%2F", "/")
        val apiURL = "https://api.github.com/repos/$user/$repo/contents/$encodedPath?ref=$branch"

        // 1-shot retry on transient failure (HTTP 403 rate-limited, network
        // exception). The retry waits 1.5s — enough to clear short-lived
        // OkHttp connection-pool issues, not enough to fix a real rate-limit
        // window (which is per-hour) but cheap enough that it's worth trying.
        val body = fetchContentsWithRetry(apiURL, outcome) ?: return

        val items = try {
            JSONArray(body)
        } catch (e: Exception) {
            // Some endpoints return a JSON object on error (e.g. {"message":...})
            // even with HTTP 200. Capture the first-line snippet for triage.
            val snippet = body.lineSequence().firstOrNull()?.take(160) ?: "(empty)"
            val msg = "GitHub contents API returned non-array JSON for $remotePath: $snippet"
            Log.w(TAG, "[siblings] $msg (${e.javaClass.simpleName}: ${e.message})")
            outcome.recordReason(msg)
            return
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val type = item.optString("type")
            val relPath = if (relativeTo.isEmpty()) name else "$relativeTo/$name"

            if (type == "dir") {
                val subRemote = if (remotePath.isEmpty()) name else "$remotePath/$name"
                downloadGitHubDirectory(
                    user = user, repo = repo, branch = branch,
                    remotePath = subRemote, localDir = localDir, relativeTo = relPath,
                    skill = skill, depth = depth + 1, outcome = outcome,
                )
                continue
            }

            if (type != "file") continue
            // Skip SKILL.md at root (already imported)
            if (relativeTo.isEmpty() && name.equals("SKILL.md", ignoreCase = true)) continue
            val downloadURL = item.optString("download_url")
            if (downloadURL.isEmpty()) {
                Log.w(TAG, "[siblings] missing download_url for $relPath — skipping")
                outcome.recordFailure("Missing download_url for $relPath")
                continue
            }

            val fileData = fetchBytesWithRetry(downloadURL)
            if (fileData == null) {
                val msg = "Failed to download $relPath from $downloadURL"
                Log.w(TAG, "[siblings] $msg")
                outcome.recordFailure(msg)
                continue
            }
            val destFile = File(localDir, relPath)
            destFile.parentFile?.mkdirs()
            val existing = if (destFile.exists()) destFile.readBytes() else null
            if (existing == null || !existing.contentEquals(fileData)) {
                destFile.writeBytes(fileData)
                Log.i(TAG, "[siblings] wrote $relPath (${fileData.size}B)")
            } else {
                Log.i(TAG, "[siblings] kept $relPath (unchanged, ${fileData.size}B)")
            }
            outcome.filesWritten += 1
        }
    }

    /**
     * Fetch a GitHub Contents-API URL, retrying once on transient failure
     * (HTTP 403/429/5xx, IOException). Returns the response body string on
     * success, or null after both attempts fail — in which case the failure
     * reason is recorded into [outcome] so the UI can surface it.
     */
    private suspend fun fetchContentsWithRetry(apiURL: String, outcome: AggregateOutcome): String? {
        var lastReason: String? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(apiURL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) return body
                        lastReason = "GitHub contents API returned empty body"
                    } else {
                        val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                        // 403 from api.github.com is almost always rate-limit;
                        // call it out specifically so users know waiting helps.
                        val hint = if (resp.code == 403) " (likely anonymous rate limit — wait an hour or sign in)" else ""
                        lastReason = "GitHub contents API HTTP ${resp.code}$hint for $apiURL"
                        if (!isTransient) {
                            Log.w(TAG, "[siblings] non-retryable ${lastReason}")
                            outcome.recordReason(lastReason!!)
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                lastReason = "GitHub contents API ${e.javaClass.simpleName}: ${e.message ?: "unknown"} for $apiURL"
            }
            if (attempt == 0) {
                Log.w(TAG, "[siblings] retrying after transient failure: $lastReason")
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "[siblings] both attempts failed: $lastReason")
        outcome.recordReason(lastReason ?: "GitHub contents API unavailable")
        return null
    }

    /** Same retry policy as [fetchContentsWithRetry], for raw file blobs. */
    private suspend fun fetchBytesWithRetry(url: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return resp.body?.bytes()
                    val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                    if (!isTransient) {
                        Log.w(TAG, "[siblings] HTTP ${resp.code} non-retryable for $url")
                        return null
                    }
                    Log.w(TAG, "[siblings] HTTP ${resp.code} on $url (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[siblings] ${e.javaClass.simpleName}: ${e.message} for $url (attempt ${attempt + 1})")
            }
            if (attempt == 0) {
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Parse a GitHub URL into components. Returns null for non-GitHub URLs.
     * Supports:
     *   - github.com/user/repo/blob|tree/branch/path/SKILL.md
     *   - raw.githubusercontent.com/user/repo/branch/path/SKILL.md
     */
    private fun parseGitHubURL(urlString: String): GitHubInfo? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            // /user/repo/branch/path/to/SKILL.md
            if (parts.size < 3) return null
            val user = parts[0]; val repo = parts[1]; val branch = parts[2]
            val dirParts = parts.drop(3).toMutableList()
            if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
                dirParts.removeAt(dirParts.size - 1)
            }
            return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
        }

        if (host != "github.com") return null
        // /user/repo/blob|tree/branch/path/...
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val dirParts = parts.drop(4).toMutableList()
        if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
            dirParts.removeAt(dirParts.size - 1)
        }
        return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
    }

    /**
     * Convert a GitHub URL to a raw.githubusercontent.com SKILL.md URL.
     * Returns null if the URL cannot be parsed.
     */
    private fun githubToRawURL(urlString: String): String? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            return if (parts.lastOrNull()?.equals("SKILL.md", ignoreCase = true) == true) {
                normalized
            } else {
                val base = normalized.trimEnd('/')
                "$base/SKILL.md"
            }
        }

        if (host != "github.com") return null
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val pathParts = parts.drop(4).toMutableList()
        var path = pathParts.joinToString("/")
        if (!path.endsWith("SKILL.md", ignoreCase = true)) {
            path = if (path.isEmpty()) "SKILL.md" else "$path/SKILL.md"
        }
        return "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
    }

    private fun normalizeURL(urlString: String): String {
        val trimmed = urlString.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun httpGetString(url: String): String? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetString failed $url: ${e.message}")
        null
    }

    private fun httpGetBytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetBytes failed $url: ${e.message}")
        null
    }


    // -- Bundled Skills --

    private fun installBundledSkills() {
        val bundledId = "skill-creator"
        val bundledVersion = "2.0.0"
        val existing = _skills.value.find { it.id == bundledId }

        // Skip if local version is same or newer
        if (existing != null && existing.version >= bundledVersion) return

        val parsed = parseSkillMd(SKILL_CREATOR_CONTENT) ?: return
        if (existing != null) {
            // Upgrade existing
            val updated = existing.copy(
                description = parsed.description,
                version = bundledVersion,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
            )
            db.execSQL(
                "UPDATE skills SET description=?, version=?, updated_at=? WHERE id=?",
                arrayOf<Any>(updated.description, bundledVersion, updated.updatedAt, bundledId)
            )
            writeSkillMd(updated)
            _skills.value = _skills.value.map { if (it.id == bundledId) updated else it }
            Log.i(TAG, "Upgraded bundled skill: $bundledId → v$bundledVersion")
        } else {
            // Fresh install
            add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                source = ImportSource.BUNDLED,
            )
            Log.i(TAG, "Installed bundled skill: $bundledId (v$bundledVersion)")
        }
    }

    // -- Reload --

    /**
     * Re-scan `skillsDir` and the SQLite registry, re-publishing the
     * resulting list via the [skills] StateFlow. Mirrors iOS
     * `SkillStore.reload()` (Agent/Session/SkillStore.swift). Use this after
     * an out-of-band install (agent shell `git clone`, agent file_write of a
     * SKILL.md, on Skills screen entry) so newly dropped directories are
     * promoted from disk into the registry without requiring an app restart.
     *
     * Compat: does not delete or reset enabled state — DB rows are the
     * source of truth for `is_enabled`, and disk-only directories are
     * auto-discovered as ImportSource.SESSION (matching the existing init
     * path), not overwriting any preserved DB toggle.
     */
    fun reloadFromDisk() {
        loadAll()
    }

    // -- Internal --

    private fun loadAll() {
        // Load from DB
        val dbSkills = mutableListOf<Skill>()
        // [T-android-safemode-lateinit-crash-147] `use` so the cursor is closed
        // even when a row throws — the old code only reached close() on the
        // success path and leaked on any failure.
        db.rawQuery("SELECT * FROM skills ORDER BY installed_at DESC", null).use { cursor ->
        while (cursor.moveToNext()) {
            // [T-android-safemode-lateinit-crash-147] Per-row isolation: one
            // malformed skill (a third-party import with a missing column or
            // an unparseable SKILL.md) must not discard every OTHER skill, and
            // must not escape into MinisApp.onCreate. Skip the bad row, keep
            // the rest.
            try {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            val importSource = ImportSource.from(cursor.getString(cursor.getColumnIndexOrThrow("import_source")))
            // [T-android-skill-orphan-db-row] Prune rows whose skill directory
            // is gone. The agent deletes skills with `rm -rf` from the shell,
            // which never reaches delete() — so the DB row survived and this
            // merge, which only ever ADDED disk-only skills (the auto-discover
            // pass below), read the orphan straight back into _skills on every
            // load. The entry stayed in the settings list through screen
            // re-entry AND cold start, was still navigable, and — worse than
            // cosmetic — skillPromptFragment() kept advertising it to the model
            // with a dead /var/minis/skills/<id>/SKILL.md path. Mirrors iOS
            // SkillStore.loadSkills(), which does dbDeleteSkill(id:)+continue
            // when SKILL.md is missing.
            //
            // BUNDLED is exempt on purpose: init{} runs loadAll() BEFORE
            // installBundledSkills(), so on the first load after a fresh
            // install the skill-creator row legitimately exists with nothing on
            // disk yet. Pruning it here would discard its use_count moments
            // before the installer re-materializes the file.
            //
            // Only one location to check, unlike iOS's Library+rootfs pair:
            // PRootKernel.registerGlobalBindMounts binds /var/minis/skills
            // straight to this same filesDir/minis-global/skills.
            if (importSource != ImportSource.BUNDLED &&
                !File(skillsDir, "$id/SKILL.md").exists()
            ) {
                db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
                db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
                Log.i(TAG, "Pruned orphan skill row (no SKILL.md on disk): $id")
                continue
            }
            val body = readSkillMdBody(id)
            val sourceUrlIdx = cursor.getColumnIndex("source_url")
            val useCountIdx = cursor.getColumnIndex("use_count")
            var description = cursor.getString(cursor.getColumnIndexOrThrow("description"))
            var name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            var version = cursor.getString(cursor.getColumnIndexOrThrow("version"))
            // Self-heal rows persisted by an earlier parser that treated YAML
            // block scalars (`|` and `>`) as literal one-character description
            // values. Re-parse the on-disk SKILL.md whenever the stored
            // description is stale and update the row in place so the skills
            // list shows the real frontmatter description.
            //
            // [T-android-skill-empty-description-never-refreshes] GH#215
            // (iOS 7d0cc49a7): an EMPTY description is stale too, not just the
            // literal "|" / ">" leftovers the original check knew about.
            //
            // How a skill gets stuck: the agent writes SKILL.md from the shell,
            // and the skill can be registered while that file is still being
            // written and has no `description:` line yet. "" is persisted, and
            // because the stale test only matched "|" / ">", every later load
            // trusted that "" forever. Since the DB row is keyed by directory
            // name it survives delete-and-recreate, so touching the file,
            // rewriting the frontmatter and even `rm -rf` + recreate all fail
            // to heal it — only renaming the skill works, because that mints a
            // new row id.
            // [T-android-skill-stale-name] The NAME is stale-checked too, and
            // independently of the description.
            //
            // Before, healing was gated entirely on the description: a row
            // whose description was fine but whose name was empty never
            // entered this block at all, and even when it did, the name was
            // only filled in `if (name.isBlank())` — inside a branch that
            // additionally required a non-blank re-parsed description. A skill
            // could therefore sit with a good description and a blank name
            // forever, showing as an empty row in the skills list while the
            // real name sat in SKILL.md one directory away.
            //
            // Note on scope: the iOS report (e4af3ad81) described names stuck
            // at the literal "Untitled Skill". That constant is iOS-only —
            // grep finds no such string in this codebase, and Android's
            // parseSkillMd `return null`s on a missing name rather than
            // substituting a placeholder, so a row can only ever be stuck
            // BLANK here, never at that literal. The check below is written
            // against blankness for that reason; adding the iOS literal would
            // be matching a value this platform cannot produce.
            val nameStale = name.isBlank()
            val descStale = description == ">" || description == "|" || description.isBlank()
            if (descStale || nameStale) {
                val skillMd = File(skillsDir, "$id/SKILL.md")
                if (skillMd.exists()) {
                    val reparsed = parseSkillMd(runCatching { skillMd.readText() }.getOrNull() ?: "")
                    // Only ever REPLACE an empty/placeholder value with a real
                    // one, never the reverse. A mid-edit or malformed SKILL.md
                    // parses to null or yields an empty description, and
                    // writing that back would wipe good metadata — the exact
                    // hazard that kept this check narrow in the first place.
                    // Combined with the guards above (the stored value must be
                    // blank or a block-scalar leftover to get here), a name or
                    // description the user deliberately set can never be
                    // overwritten.
                    if (reparsed != null) {
                        var changed = false
                        if (descStale && reparsed.description.isNotBlank()) {
                            description = reparsed.description
                            changed = true
                        }
                        // parseSkillMd returns null unless it found a non-blank
                        // name, so reparsed.name is always real here — but the
                        // isNotBlank() guard is kept so this stays correct if
                        // that parser contract ever loosens.
                        if (nameStale && reparsed.name.isNotBlank()) {
                            name = reparsed.name
                            changed = true
                        }
                        if (version.isBlank()) {
                            version = reparsed.version
                            changed = true
                        }
                        if (changed) {
                            db.execSQL(
                                "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
                                arrayOf<Any>(name, description, version, System.currentTimeMillis(), id),
                            )
                            Log.i(TAG, "Self-healed skill name/description for $id")
                        }
                    }
                }
            }
            dbSkills.add(Skill(
                id = id,
                name = name,
                description = description,
                version = version,
                importSource = importSource,
                isEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) == 1,
                installedAt = cursor.getLong(cursor.getColumnIndexOrThrow("installed_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                body = body,
                sourceURL = if (sourceUrlIdx >= 0 && !cursor.isNull(sourceUrlIdx)) cursor.getString(sourceUrlIdx) else null,
                useCount = if (useCountIdx >= 0) cursor.getDouble(useCountIdx) else 0.0,
            ))
            } catch (t: Throwable) {
                Log.e(TAG, "skipping unreadable skill row: ${t.message}", t)
            }
        }
        }

        // Auto-discover skills on disk without DB entries
        val onDisk = skillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in onDisk) {
            val skillMd = File(dir, "SKILL.md")
            if (skillMd.exists() && dbSkills.none { it.id == dir.name }) {
                val parsed = parseSkillMd(skillMd.readText())
                if (parsed != null) {
                    val skill = Skill(
                        id = dir.name,
                        name = parsed.name,
                        description = parsed.description,
                        importSource = ImportSource.SESSION,
                        body = parsed.body,
                    )
                    insertDb(skill)
                    dbSkills.add(skill)
                    Log.i(TAG, "Auto-discovered skill: ${dir.name}")
                }
            }
        }

        _skills.value = dbSkills
    }

    private fun insertDb(skill: Skill) {
        db.execSQL(
            """INSERT OR REPLACE INTO skills (id, name, description, version, import_source, source_url, is_enabled, installed_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                skill.id, skill.name, skill.description, skill.version,
                skill.importSource.value, skill.sourceURL,
                if (skill.isEnabled) 1 else 0,
                skill.installedAt, skill.updatedAt,
            )
        )
    }

    private fun writeSkillMd(skill: Skill) {
        val dir = File(skillsDir, skill.id)
        dir.mkdirs()
        val content = buildString {
            appendLine("---")
            appendLine("name: ${skill.name}")
            appendLine("description: ${skill.description}")
            appendLine("version: ${skill.version}")
            appendLine("---")
            append(skill.body)
        }
        File(dir, "SKILL.md").writeText(content)
    }

    private fun readSkillMdBody(id: String): String {
        val file = File(skillsDir, "$id/SKILL.md")
        if (!file.exists()) return ""
        val parsed = parseSkillMd(file.readText())
        return parsed?.body ?: ""
    }

    data class ParsedSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val body: String,
    )

    /**
     * Parse a SKILL.md file into [ParsedSkill]. Mirrors iOS SkillStore.parse(skillMD:).
     *
     * Recognized YAML frontmatter forms (delimited by `---` … `---`):
     *
     *   description: short text
     *   description: |
     *     line one
     *     line two           ← preserved with newlines
     *   description: >
     *     line one
     *     line two           ← folded into a single space-joined line
     *
     * The block-scalar variants (`|` and `>`) matter in practice — many
     * authored SKILL.md files (e.g. hyperframes, bilibili-hub) use
     * `description: >` to either fold a multi-line description or as a
     * placeholder with an empty body. The previous parser stored the
     * literal string `">"` for those skills, which is what surfaced in the
     * skills list as a confusing single-character description.
     *
     * Returns null only if the document doesn't start with `---` or if
     * the closing `---` is missing — caller treats null as "not a SKILL.md".
     * If frontmatter parses but `name` is blank we also return null so
     * import paths can fall back (e.g. derive name from the GitHub URL).
     */
    /**
     * Public re-exposure for the "Update from File" UI. Same parser, same
     * `null = invalid SKILL.md` contract. Lives on the instance for symmetry
     * with the rest of the API surface, even though the parse itself is
     * stateless.
     */
    fun parseSkillMdPublic(content: String): ParsedSkill? = parseSkillMd(content)

    private fun parseSkillMd(content: String): ParsedSkill? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return null

        // Find the closing `---` on a line by itself (matches iOS — and avoids
        // false matches against horizontal rules later in the document).
        val lines = trimmed.lines()
        var frontmatterEndLine = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                frontmatterEndLine = i
                break
            }
        }
        if (frontmatterEndLine < 0) return null

        var name = ""
        var description = ""
        var version = "1.0.0"

        var i = 1
        while (i < frontmatterEndLine) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) { i++; continue }
            val key = line.substring(0, colonIdx).trim().lowercase()
            val rawValue = line.substring(colonIdx + 1).trim()

            // YAML block scalar: `|` keeps newlines, `>` folds them into spaces.
            // Body lines continue while they are indented (or empty); a
            // dedented line ends the block.
            //
            // T151: also accept the YAML chomping indicators `>-`, `>+`, `|-`,
            // `|+` (and any explicit indentation digit suffix). Real-world
            // skill frontmatter — qbt-hub, for one — opens its description
            // with `description: >-` so trailing newlines are stripped, but
            // the previous strict `==` match treated that as a plain string
            // literal, leaving the renderer to display the raw `>-` marker.
            val isBlockScalar = rawValue.startsWith("|") || rawValue.startsWith(">")
            val resolved: String
            if (isBlockScalar && i + 1 < frontmatterEndLine) {
                val fold = rawValue.startsWith(">")
                val blockLines = mutableListOf<String>()
                var j = i + 1
                while (j < frontmatterEndLine) {
                    val next = lines[j]
                    if (next.isEmpty() || next[0].isWhitespace()) {
                        blockLines.add(next.trim())
                    } else break
                    j++
                }
                resolved = if (fold) {
                    blockLines.joinToString(" ").trim()
                } else {
                    blockLines.joinToString("\n").trim('\n')
                }
                i = j
            } else {
                resolved = rawValue
                i++
            }

            when (key) {
                "name" -> name = resolved
                "description" -> description = resolved
                "version" -> version = resolved
            }
        }

        if (name.isBlank()) return null

        val bodyStartLine = frontmatterEndLine + 1
        val body = if (bodyStartLine < lines.size) {
            lines.subList(bodyStartLine, lines.size).joinToString("\n").trim('\n')
        } else ""

        return ParsedSkill(name, description, version, body)
    }

    private fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    // -- Database Helper --

    private class SkillDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE skills (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    version TEXT NOT NULL DEFAULT '1.0.0',
                    import_source TEXT NOT NULL DEFAULT 'file',
                    source_url TEXT,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    installed_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    use_count REAL NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("""
                CREATE TABLE session_skill_overrides (
                    session_id TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, skill_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN source_url TEXT")
                } catch (_: Exception) { /* column may already exist */ }
            }
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN use_count REAL NOT NULL DEFAULT 0")
                } catch (_: Exception) { /* column may already exist */ }
            }
        }
    }
}

// Bundled skill-creator content (matches iOS SkillStore.skillCreatorContent)
private val SKILL_CREATOR_CONTENT = """
---
name: skill-creator
version: 2.0.0
description: Guide for creating effective skills. This skill should be used when users want to create a new skill (or update an existing skill) that extends Claude's capabilities with specialized knowledge, workflows, or tool integrations.
---

# Skill Creator

This skill provides guidance for creating effective skills.

## About Skills

Skills are modular, self-contained packages that extend Claude's capabilities by providing
specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific
domains or tasks—they transform Claude from a general-purpose agent into a specialized agent
equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key

The context window is a public good. Skills share the context window with everything else Claude needs: system prompt, conversation history, other Skills' metadata, and the actual user request.

**Default assumption: Claude is already very smart.** Only add context Claude doesn't already have. Challenge each piece of information: "Does Claude really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility and variability:

- **High freedom (text-based instructions)**: Use when multiple approaches are valid.
- **Medium freedom (pseudocode or scripts with parameters)**: Use when a preferred pattern exists.
- **Low freedom (specific scripts, few parameters)**: Use when operations are fragile, consistency is critical, or a specific sequence must be followed.

### Anatomy of a Skill

Every skill consists of a required SKILL.md file and optional bundled resources:

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name + description required)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/       - Executable code
    ├── references/    - Documentation loaded as needed
    └── assets/        - Files used in output (templates, icons, etc.)
```

#### SKILL.md Frontmatter

- `name` (required): The skill name
- `description` (required): What the skill does and when to trigger it. Be comprehensive—this is the primary triggering mechanism.

#### SKILL.md Body

Instructions and guidance, loaded after the skill triggers. Keep under 500 lines; split into reference files when approaching this limit.

### Progressive Disclosure

Skills use three loading levels:
1. **Metadata** - Always in context (~100 words)
2. **SKILL.md body** - When skill triggers (<5k words)
3. **Bundled resources** - As needed (unlimited)

## Skill Creation Process

1. **Understand** the skill with concrete examples from the user
2. **Plan** reusable contents (scripts, references, assets)
3. **Create** the SKILL.md with proper frontmatter and instructions
4. **Test** by using the skill on real tasks
5. **Iterate** based on actual usage

### Writing the SKILL.md

- Use imperative/infinitive form
- `description` field should include all "when to use" triggers (body is loaded after triggering)
- Only add context Claude doesn't already have
- Prefer concise examples over verbose explanations
- Keep essential workflow in SKILL.md; move detailed reference material to separate files

### What NOT to Include

Do not create extraneous files: README.md, INSTALLATION_GUIDE.md, CHANGELOG.md, etc. The skill should only contain what an AI agent needs to do the job.
""".trimIndent()
