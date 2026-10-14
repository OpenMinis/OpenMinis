package com.openminis.app.evolution

import android.content.Context
import android.os.BatteryManager
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.text.BoundedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class EvolutionEngine(
    private val context: Context,
    memoryRepository: com.openminis.app.data.repository.MemoryRepository,
    evolutionDir: File,
    private val chatRepository: ChatRepository,
    providerRepository: com.openminis.app.data.repository.ProviderRepository,
    private val skillRepository: SkillRepository,
) {
    val prefs = EvolutionPrefs(context)
    val store = EvolutionStore(evolutionDir)
    val learned = memoryRepository.learnedPrefs
    private val llm = EvolutionLlm(context, providerRepository, prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun onSessionFinished(sessionId: String, isError: Boolean) {
        if (!prefs.isEnabled) return
        scope.launch {
            mutex.withLock {
                runCatching { processSession(sessionId) }
                    .onFailure { AppLogger.warning(TAG, "session $sessionId: ${it.message}") }
            }
        }
    }

    fun maybeHarvestIdle() {
        if (!prefs.isEnabled) return
        if (!prefs.harvestCooldownElapsed()) return
        if (!isChargingOrUnknown()) return
        scope.launch {
            mutex.withLock {
                runCatching {
                    store.maintainBeliefs()
                    harvestIdle()
                    maybeWeeklyReflect()
                }.onFailure { AppLogger.warning(TAG, "idle harvest: ${it.message}") }
            }
        }
    }

    fun recordToolFailure(sessionId: String, toolName: String, argsJson: String, error: String) {
        if (!prefs.isEnabled) return
        val skillId = skillIdFromTool(toolName, argsJson) ?: return
        store.recordToolFail(sessionId, skillId, error)
        val fails = store.recentToolFails(sessionId, skillId, System.currentTimeMillis() - DAY_MS)
        if (fails.size < EvolutionPrefs.SKILL_FAIL_THRESHOLD) return
        scope.launch {
            mutex.withLock {
                runCatching { maybeSkillPatch(sessionId, skillId, fails) }
                    .onFailure { AppLogger.warning(TAG, "skill patch: ${it.message}") }
            }
        }
    }

    suspend fun accept(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val p = store.getProposal(id) ?: return@withLock false
            if (p.status != EvolutionProposal.Status.PENDING &&
                p.status != EvolutionProposal.Status.DEFERRED
            ) return@withLock false
            when (p.type) {
                EvolutionProposal.Type.LEARNED_RULE -> {
                    val snap = learned.snapshotRegion()
                    if (!learned.addBullet(p.draftText, p.scene, core = true)) return@withLock false
                    p.beliefId?.let { store.promoteBelief(it, BeliefMaintenance.CORE) }
                    store.setStatus(id, EvolutionProposal.Status.ACCEPTED, snap)
                    true
                }
                EvolutionProposal.Type.RETRACT -> {
                    val snap = learned.snapshotRegion()
                    learned.removeBullet(p.draftText)
                    p.beliefId?.let { store.promoteBelief(it, BeliefMaintenance.DECAYED) }
                    store.setStatus(id, EvolutionProposal.Status.ACCEPTED, snap)
                    true
                }
                EvolutionProposal.Type.SKILL_PATCH -> {
                    val skillId = p.skillId ?: return@withLock false
                    val skill = skillRepository.skills.value.find { it.id == skillId }
                        ?: return@withLock false
                    if (skill.importSource == SkillRepository.ImportSource.BUNDLED) {
                        val snap = learned.snapshotRegion()
                        val rule = bundledPatchRule(skill.name, p.draftText) ?: return@withLock false
                        if (!learned.addBullet(rule)) return@withLock false
                        store.setStatus(id, EvolutionProposal.Status.ACCEPTED, snap)
                        true
                    } else {
                        val snap = skill.body
                        if (!skillRepository.appendLearnedSection(skillId, p.draftText)) {
                            return@withLock false
                        }
                        store.setStatus(id, EvolutionProposal.Status.ACCEPTED, snap)
                        true
                    }
                }
            }
        }
    }

    suspend fun reject(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.setStatus(id, EvolutionProposal.Status.REJECTED)
        }
    }

    suspend fun defer(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.setStatus(id, EvolutionProposal.Status.DEFERRED)
        }
    }

    suspend fun rollback(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val p = store.getProposal(id) ?: return@withLock false
            if (p.status != EvolutionProposal.Status.ACCEPTED) return@withLock false
            when (p.type) {
                EvolutionProposal.Type.LEARNED_RULE,
                EvolutionProposal.Type.RETRACT -> {
                    val snap = p.rollbackText
                    if (snap != null) learned.restoreRegion(snap)
                    else if (p.type == EvolutionProposal.Type.RETRACT) {
                        learned.addBullet(p.draftText, p.scene, core = false)
                    } else {
                        learned.removeBullet(p.draftText)
                    }
                }
                EvolutionProposal.Type.SKILL_PATCH -> {
                    val skillId = p.skillId
                    val snap = p.rollbackText
                    val skill = skillId?.let { id -> skillRepository.skills.value.find { it.id == id } }
                    // [T-evolution-bundled-rollback] Accepting a SKILL_PATCH for a
                    // BUNDLED skill does NOT touch SKILL.md — it writes a
                    // "When using skill X: …" bullet into LEARNED.md and stores the
                    // LEARNED.md region as `rollbackText` (see accept()). The
                    // rollback path here only looked at `skillId != null`, which is
                    // also true for those proposals, so it called
                    // `skillRepository.update(skillId, body = snap)` and overwrote
                    // the SHIPPED SKILL.md with the learned-region text. Roll back
                    // a bundled accept the way it was applied: drop its bullet.
                    if (skill != null && skill.importSource == SkillRepository.ImportSource.BUNDLED) {
                        val removed = learned.removeBullet(
                            bundledPatchRule(skill.name, p.draftText) ?: "",
                        )
                        if (!removed && snap != null) learned.restoreRegion(snap)
                    } else if (skillId != null && snap != null) {
                        skillRepository.update(skillId, body = snap)
                    } else {
                        learned.removeBullet(p.draftText)
                    }
                }
            }
            store.setStatus(id, EvolutionProposal.Status.ROLLED_BACK)
            true
        }
    }

    /**
     * [T-evolution-bundled-rollback] The LEARNED.md bullet that stands in for a
     * patch a BUNDLED skill cannot receive (its SKILL.md ships with the APK and is
     * re-installed on upgrade). accept() writes exactly this text and rollback()
     * removes it again, so both go through here — two hand-written copies of the
     * sentence would drift and the rollback would silently stop matching.
     */
    private fun bundledPatchRule(skillName: String, draftText: String): String? =
        LearnedPrefsStore.normalize("When using skill $skillName: $draftText")

    private suspend fun processSession(sessionId: String) {
        val messages = chatRepository.loadSessionTail(sessionId, limit = 400).messages
        if (messages.isEmpty()) return
        beActive(sessionId, messages)
        harvestSession(sessionId, messages, force = false)
    }

    private suspend fun beActive(sessionId: String, messages: List<MessageEntity>) {
        val lastUser = messages.lastOrNull { it.role.equals("user", true) } ?: return
        val text = ChatRepository.extractTextPreview(lastUser.partsJson).orEmpty()
        val hint = CorrectionDetector.extractHint(text) ?: return
        val scene = sceneFor(sessionId, text)
        val fp = CorrectionDetector.fingerprint(hint)
        store.insertObservation("correction", sessionId, null, text, fp)
        val belief = store.bumpBelief(fp, "correction", hint, scene)
        if (store.hasOpenProposal(fp)) return
        val lastAssistant = messages.lastOrNull { it.role.equals("assistant", true) }
        val assistantText = lastAssistant?.let {
            ChatRepository.extractTextPreview(it.partsJson).orEmpty()
        }.orEmpty()
        val polished = refineRule(
            user = text,
            assistant = assistantText,
            hint = hint,
        ) ?: hint
        store.insertProposal(
            EvolutionProposal(
                id = UUID.randomUUID().toString(),
                type = EvolutionProposal.Type.LEARNED_RULE,
                status = EvolutionProposal.Status.PENDING,
                beliefId = belief.id,
                title = "Correction",
                draftText = polished,
                evidence = text.take(EvolutionPrefs.MAX_EVIDENCE_CHARS),
                skillId = null,
                rollbackText = null,
                createdAt = System.currentTimeMillis(),
                decidedAt = null,
                scene = scene,
            ),
        )
        AppLogger.info(TAG, "Be-ACTIVE proposal for session ${sessionId.take(8)}")
    }

    private suspend fun harvestIdle() {
        prefs.markHarvested()
        val sessions = chatRepository.listSessions().take(12)
        var n = 0
        for (session in sessions) {
            if (n >= 3) break
            val messages = chatRepository.loadSessionTail(session.id, limit = 400).messages
            if (messages.isEmpty()) continue
            val lastId = messages.last().id
            if (store.watermark(session.id) == lastId) continue
            harvestSession(session.id, messages, force = true)
            n++
        }
    }

    private suspend fun harvestSession(
        sessionId: String,
        messages: List<MessageEntity>,
        force: Boolean,
    ) {
        val lastId = messages.lastOrNull()?.id ?: return
        if (!force && store.watermark(sessionId) == lastId) return
        val compact = runCatching {
            chatRepository.dao.listCompactMarkers(sessionId).lastOrNull()?.summary
        }.getOrNull()
        val userTurns = messages.filter { it.role.equals("user", true) }
        val hints = userTurns.mapNotNull { msg ->
            val raw = ChatRepository.extractTextPreview(msg.partsJson).orEmpty()
            if (com.openminis.app.data.repository.MemoryRepository.looksLikeTaskDiary(raw)) null
            else CorrectionDetector.extractHint(raw)
        }
        val sessionScene = sceneFor(
            sessionId,
            userTurns.lastOrNull()?.let { ChatRepository.extractTextPreview(it.partsJson) }.orEmpty(),
        )
        for (hint in hints) {
            val fp = CorrectionDetector.fingerprint(hint)
            store.insertObservation("harvest", sessionId, null, hint, fp)
            val belief = store.bumpBelief(fp, "harvest", hint, sessionScene)
            if (belief.hitCount < EvolutionPrefs.MIN_BELIEF_HITS) continue
            if (store.hasOpenProposal(fp)) continue
            val polished = if (compact.isNullOrBlank()) hint else {
                refineRule(user = hint, assistant = compact.take(800), hint = hint) ?: hint
            }
            store.insertProposal(
                EvolutionProposal(
                    id = UUID.randomUUID().toString(),
                    type = EvolutionProposal.Type.LEARNED_RULE,
                    status = EvolutionProposal.Status.PENDING,
                    beliefId = belief.id,
                    title = "Harvested preference",
                    draftText = polished,
                    evidence = hint,
                    skillId = null,
                    rollbackText = null,
                    createdAt = System.currentTimeMillis(),
                    decidedAt = null,
                    scene = sessionScene,
                ),
            )
        }
        store.setWatermark(sessionId, lastId)
    }

    private suspend fun maybeSkillPatch(sessionId: String, skillId: String, fails: List<String>) {
        val fp = "skill:$skillId"
        if (store.hasOpenProposal(fp)) return
        val skill = skillRepository.skills.value.find { it.id == skillId } ?: return
        val excerpt = BoundedText.markdownParseInput(skill.body).take(4_000)
        val errors = fails.take(3).joinToString("\n") { "- ${it.take(240)}" }
        val patch = refineSkillPatch(skill.id, excerpt, errors)
            ?: return
        store.insertProposal(
            EvolutionProposal(
                id = UUID.randomUUID().toString(),
                type = EvolutionProposal.Type.SKILL_PATCH,
                status = EvolutionProposal.Status.PENDING,
                beliefId = null,
                title = "Skill: ${skill.name}",
                draftText = patch,
                evidence = errors.take(EvolutionPrefs.MAX_EVIDENCE_CHARS),
                skillId = skill.id,
                rollbackText = null,
                createdAt = System.currentTimeMillis(),
                decidedAt = null,
            ),
        )
        AppLogger.info(TAG, "skill_patch proposal for $skillId session=${sessionId.take(8)}")
    }

    private suspend fun refineRule(user: String, assistant: String, hint: String): String? {
        val raw = llm.complete(
            system = RULE_SYSTEM,
            user = "User correction:\n${user.take(800)}\n\nLast assistant excerpt:\n${assistant.take(800)}\n\nHeuristic:\n$hint",
        ) ?: return null
        return EvolutionJson.parseRules(raw).firstOrNull()
    }

    private suspend fun refineSkillPatch(skillId: String, excerpt: String, errors: String): String? {
        val raw = llm.complete(
            system = SKILL_SYSTEM,
            user = "skill_id=$skillId\n\nSKILL.md excerpt:\n$excerpt\n\nRecent failures:\n$errors",
            maxTokens = 500,
        ) ?: return null
        return EvolutionJson.parseSkillPatch(raw)
    }

    private fun skillIdFromTool(toolName: String, argsJson: String): String? {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        val path = obj.optString("path", "")
        skillRepository.skillIdFromPath(path)?.let { return it }
        val command = obj.optString("command", "")
        val hay = "$path $command $toolName"
        val m = SKILL_PATH_RE.find(hay) ?: return null
        val id = m.groupValues[1]
        return if (skillRepository.skills.value.any { it.id == id }) id else null
    }

    private suspend fun maybeWeeklyReflect() {
        if (!prefs.reflectCooldownElapsed()) return
        prefs.markReflected()
        val rules = learned.readParsed()
        if (rules.isEmpty()) return
        val sessions = chatRepository.listSessions().take(8)
        val userTurns = mutableListOf<String>()
        var transcriptChars = 0
        sessionLoop@ for (session in sessions) {
            val messages = chatRepository.loadSessionTail(session.id, limit = 400).messages
            for (msg in messages) {
                if (!msg.role.equals("user", true)) continue
                val raw = ChatRepository.extractTextPreview(msg.partsJson) ?: continue
                val window = BoundedText.icuWindow(raw, 400).toString()
                if (window.isBlank()) continue
                if (transcriptChars + window.length > EvolutionPrefs.MAX_TRANSCRIPT_CHARS) break@sessionLoop
                userTurns.add(window)
                transcriptChars += window.length
            }
        }
        val beliefs = store.listBeliefs()
        val lastHitByBody = mutableMapOf<String, Long>()
        for (b in beliefs) {
            val body = LearnedPrefsStore.parseItem(b.summary).body.lowercase()
            lastHitByBody[body] = maxOf(lastHitByBody[body] ?: 0L, b.lastHitAt)
        }
        for (p in store.proposals.value) {
            if (p.status != EvolutionProposal.Status.ACCEPTED) continue
            if (p.type != EvolutionProposal.Type.LEARNED_RULE) continue
            val body = LearnedPrefsStore.parseItem(p.draftText).body.lowercase()
            val fromBelief = p.beliefId?.let { id -> beliefs.find { it.id == id }?.lastHitAt }
            val hit = fromBelief ?: p.createdAt
            lastHitByBody[body] = maxOf(lastHitByBody[body] ?: 0L, hit)
        }
        val now = System.currentTimeMillis()
        var decisions = WeeklyReflection.decide(
            rules = rules,
            userTurns = userTurns,
            lastHitByBody = lastHitByBody,
            now = now,
            isTaskDiary = { com.openminis.app.data.repository.MemoryRepository.looksLikeTaskDiary(it) },
        )
        if (decisions.isEmpty() && userTurns.isNotEmpty() && !prefs.llmFused()) {
            val quotes = userTurns.take(12).joinToString("\n") { "- ${it.take(200)}" }
            val ruleBlock = rules.joinToString("\n") { it.raw }
            val raw = llm.complete(
                system = REFLECT_SYSTEM,
                user = "Rules:\n$ruleBlock\n\nLater user quotes:\n$quotes",
                maxTokens = 500,
            )
            if (raw != null) {
                decisions = WeeklyReflection.fromLlm(EvolutionJson.parseReflect(raw), rules)
            }
        }
        for (d in decisions) emitReflect(d)
    }

    private fun emitReflect(d: ReflectDecision) {
        val already = store.proposals.value.any { p ->
            p.type == EvolutionProposal.Type.RETRACT &&
                p.status != EvolutionProposal.Status.REJECTED &&
                p.status != EvolutionProposal.Status.ROLLED_BACK &&
                LearnedPrefsStore.parseItem(p.draftText).body.equals(d.rule.body, ignoreCase = true)
        }
        if (already) return
        val title = if (d.reason == "unused") "Retract unused rule" else "Retract contradicted rule"
        store.insertProposal(
            EvolutionProposal(
                id = UUID.randomUUID().toString(),
                type = EvolutionProposal.Type.RETRACT,
                status = EvolutionProposal.Status.PENDING,
                beliefId = null,
                title = title,
                draftText = d.rule.raw,
                evidence = d.evidence.take(EvolutionPrefs.MAX_EVIDENCE_CHARS),
                skillId = null,
                rollbackText = null,
                createdAt = System.currentTimeMillis(),
                decidedAt = null,
                scene = d.rule.scene,
            ),
        )
        val tighten = d.tightenTo
        if (tighten != null) {
            store.insertProposal(
                EvolutionProposal(
                    id = UUID.randomUUID().toString(),
                    type = EvolutionProposal.Type.LEARNED_RULE,
                    status = EvolutionProposal.Status.PENDING,
                    beliefId = null,
                    title = "Tighten contradicted rule",
                    draftText = tighten,
                    evidence = "Was: ${d.rule.body.take(80)}\n${d.evidence}"
                        .take(EvolutionPrefs.MAX_EVIDENCE_CHARS),
                    skillId = null,
                    rollbackText = null,
                    createdAt = System.currentTimeMillis(),
                    decidedAt = null,
                    scene = d.rule.scene,
                ),
            )
        }
        AppLogger.info(TAG, "reflect ${d.reason} for ${d.rule.body.take(40)}")
    }

    private suspend fun sceneFor(sessionId: String, sample: String): SceneTag {
        val session = chatRepository.getSession(sessionId)
        return SceneClassifier.classify(session?.category, session?.title, sample)
    }

    private fun isChargingOrUnknown(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        if (bm.isCharging) return true
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            status == BatteryManager.BATTERY_STATUS_UNKNOWN
    }

    companion object {
        private const val TAG = "Evolution"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val SKILL_PATH_RE = Regex("/var/minis/skills/([^/]+)/")

        private const val RULE_SYSTEM =
            "Extract ONE durable user preference from a correction. Reply JSON only: " +
                "{\"rule\":\"- Prefer ...\",\"skip\":false}. " +
                "If it is not a lasting preference, {\"skip\":true}. " +
                "The rule must be one bullet, ≤120 chars, imperative. " +
                "Never propose editing SOUL.md or GLOBAL.md."

        private const val SKILL_SYSTEM =
            "Propose a short SKILL.md addition (not a rewrite) that would prevent the failures. " +
                "JSON only: {\"patch\":\"markdown bullets\",\"skip\":false}. " +
                "Do not rewrite the whole skill. Do not touch identity files."

        private const val REFLECT_SYSTEM =
            "Review standing LEARNED.md rules against later user quotes. " +
                "JSON only: {\"items\":[{\"action\":\"retract\"|\"tighten\"|\"keep\"," +
                "\"rule\":\"exact rule body\",\"replacement\":\"- ...\",\"evidence\":\"quote\"}],\"skip\":false}. " +
                "Retract if the user clearly reversed the rule. Tighten if they narrowed it. Keep otherwise. " +
                "At most 3 non-keep items. Never edit SOUL.md or GLOBAL.md. Never invent rules not listed."
    }
}
