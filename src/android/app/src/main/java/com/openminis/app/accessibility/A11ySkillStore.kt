package com.openminis.app.accessibility

import com.openminis.app.tools.AtomicFileWrite
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent store for recorded a11y skills: one user instruction mapped to
 * the action sequence recorded via [A11yScriptRecorder]. JSON on disk through
 * [AtomicFileWrite] (atomic + verified write, no schema drift).
 *
 * - LRU-capped at [MAX_SKILLS]; the least-recently-used skill is evicted.
 * - A skill whose replay failed [STALE_AFTER_FAILURES] times in a row is
 *   marked stale: it never matches again but stays listed and deletable.
 *
 * Pure JVM (no android.* imports beyond Context in the factory) so the whole
 * CRUD/LRU/stale surface is unit-testable.
 */
class A11ySkillStore(private val dir: File) {

    data class Step(
        val kind: String,
        val packageName: String?,
        val text: String?,
        val viewId: String?,
        val contentDescription: String?,
        val xy: String?,
        val atMs: Long,
        val resolvedXy: String? = null,
    )

    data class Skill(
        val id: String,
        val name: String,
        val instruction: String,
        val packageName: String?,
        val steps: List<Step>,
        val createdAt: Long,
        val lastUsedAt: Long,
        val failStreak: Int = 0,
        val stale: Boolean = false,
    )

    companion object {
        const val MAX_SKILLS = 50
        const val STALE_AFTER_FAILURES = 3
        const val FILE_NAME = "a11y_skills.json"

        // [T-skill-store-singleton] Process-wide singleton keyed by the
        // single canonical dir (filesDir/a11y-skills). Every method on the
        // store is @Synchronized, but that lock only guards ONE instance —
        // a fresh instance per call would give two callers two independent
        // caches and two read-modify-write cycles over the same JSON file
        // (last-writer-wins, lost updates). Caching the instance makes the
        // per-instance lock actually mean something, and lets the chat tool
        // and any future settings-page wiring share one view of the data.
        @Volatile
        private var cached: A11ySkillStore? = null

        fun forContext(context: android.content.Context): A11ySkillStore {
            val dir = File(context.filesDir, "a11y-skills")
            return forDir(dir)
        }

        /** Test-visible singleton accessor: same dir → same instance. */
        @Synchronized
        internal fun forDir(dir: File): A11ySkillStore =
            cached?.takeIf { it.dir == dir } ?: A11ySkillStore(dir).also { cached = it }

        /** Drop the cached singleton (tests only). */
        internal fun resetSingletonForTest() {
            synchronized(this) { cached = null }
        }

        /** Adapter from the live recorder buffer to persisted steps. */
        fun fromRecorderSteps(steps: List<A11yScriptRecorder.Step>): List<Step> =
            steps.map {
                Step(
                    kind = it.kind.name,
                    packageName = it.packageName,
                    text = it.text,
                    viewId = it.viewId,
                    contentDescription = it.contentDescription,
                    xy = it.xy,
                    atMs = it.atMs,
                )
            }
    }

    private val file: File get() = File(dir, FILE_NAME)
    private var cache: MutableList<Skill>? = null

    @Synchronized
    fun all(): List<Skill> {
        if (cache == null) cache = load().toMutableList()
        return cache!!.toList()
    }

    @Synchronized
    fun byName(name: String): Skill? =
        all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    @Synchronized
    fun byId(id: String): Skill? = all().firstOrNull { it.id == id }

    /** Insert or replace by name (same name keeps id + createdAt, refreshes steps). */
    @Synchronized
    fun save(name: String, instruction: String, steps: List<Step>, packageName: String?): Skill? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val now = System.currentTimeMillis()
        val existing = byName(n)
        val skill = Skill(
            id = existing?.id ?: ("a11y-" + java.lang.Long.toHexString(now) + "-" + (0..9999).random()),
            name = n,
            instruction = instruction.trim(),
            packageName = packageName,
            steps = steps,
            createdAt = existing?.createdAt ?: now,
            lastUsedAt = now,
        )
        val list = cache ?: load().toMutableList().also { cache = it }
        list.removeAll { it.id == skill.id }
        list.add(skill)
        evictLru(list)
        return if (persist()) skill else null
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val list = cache ?: load().toMutableList().also { cache = it }
        val removed = list.removeAll { it.id == id }
        return removed && persist()
    }

    /** Successful replay: bump LRU clock, optionally clear the failure streak. */
    @Synchronized
    fun touch(id: String, resetFailures: Boolean = true): Boolean {
        val s = byId(id) ?: return false
        return replace(
            s.copy(
                lastUsedAt = System.currentTimeMillis(),
                failStreak = if (resetFailures) 0 else s.failStreak,
            )
        )
    }

    /** Failed replay: increment streak; auto-stale at [STALE_AFTER_FAILURES]. */
    @Synchronized
    fun recordFailure(id: String): Boolean {
        val s = byId(id) ?: return false
        val streak = s.failStreak + 1
        return replace(s.copy(failStreak = streak, stale = s.stale || streak >= STALE_AFTER_FAILURES))
    }

    /** 坐标自愈: write back freshly resolved tap coordinates for one step. */
    @Synchronized
    fun updateResolvedXy(id: String, stepIndex: Int, x: Int, y: Int): Boolean {
        val s = byId(id) ?: return false
        if (stepIndex !in s.steps.indices) return false
        val steps = s.steps.toMutableList()
        steps[stepIndex] = steps[stepIndex].copy(resolvedXy = "$x,$y")
        return replace(s.copy(steps = steps))
    }

    private fun replace(updated: Skill): Boolean {
        val list = cache ?: load().toMutableList().also { cache = it }
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx < 0) return false
        list[idx] = updated
        return persist()
    }

    private fun evictLru(list: MutableList<Skill>) {
        while (list.size > MAX_SKILLS) {
            val victim = list.minByOrNull { it.lastUsedAt } ?: break
            list.remove(victim)
        }
    }

    private fun persist(): Boolean {
        if (!dir.exists()) dir.mkdirs()
        val arr = JSONArray()
        cache.orEmpty().forEach { arr.put(it.toJson()) }
        val root = JSONObject().put("version", 1).put("skills", arr)
        return AtomicFileWrite.write(file, root.toString()) != null
    }

    private fun load(): List<Skill> {
        val text = AtomicFileWrite.read(file) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(text).optJSONArray("skills") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val steps = mutableListOf<Step>()
                val sarr = o.optJSONArray("steps") ?: JSONArray()
                for (j in 0 until sarr.length()) {
                    val so = sarr.optJSONObject(j) ?: continue
                    steps.add(
                        Step(
                            kind = so.optString("kind"),
                            packageName = so.optString("packageName").takeIf { it.isNotEmpty() },
                            text = so.optString("text").takeIf { it.isNotEmpty() },
                            viewId = so.optString("viewId").takeIf { it.isNotEmpty() },
                            contentDescription = so.optString("contentDescription").takeIf { it.isNotEmpty() },
                            xy = so.optString("xy").takeIf { it.isNotEmpty() },
                            atMs = so.optLong("atMs"),
                            resolvedXy = so.optString("resolvedXy").takeIf { it.isNotEmpty() },
                        )
                    )
                }
                Skill(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    instruction = o.optString("instruction"),
                    packageName = o.optString("packageName").takeIf { it.isNotEmpty() },
                    steps = steps,
                    createdAt = o.optLong("createdAt"),
                    lastUsedAt = o.optLong("lastUsedAt"),
                    failStreak = o.optInt("failStreak"),
                    stale = o.optBoolean("stale"),
                )
            }
        }.getOrElse { emptyList() }
    }
}

/** JSON round-trip kept as extensions so the data classes stay pure. */
internal fun A11ySkillStore.Skill.toJson(): JSONObject {
    val steps = JSONArray()
    this.steps.forEach {
        steps.put(
            JSONObject()
                .put("kind", it.kind)
                .put("packageName", it.packageName ?: "")
                .put("text", it.text ?: "")
                .put("viewId", it.viewId ?: "")
                .put("contentDescription", it.contentDescription ?: "")
                .put("xy", it.xy ?: "")
                .put("atMs", it.atMs)
                .put("resolvedXy", it.resolvedXy ?: "")
        )
    }
    return JSONObject()
        .put("id", id)
        .put("name", name)
        .put("instruction", instruction)
        .put("packageName", packageName ?: "")
        .put("steps", steps)
        .put("createdAt", createdAt)
        .put("lastUsedAt", lastUsedAt)
        .put("failStreak", failStreak)
        .put("stale", stale)
}
