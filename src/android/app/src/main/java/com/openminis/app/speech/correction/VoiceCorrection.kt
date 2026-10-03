package com.openminis.app.speech.correction

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.shared.TextSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [T-android-voice-correction] Single entry point for the correction feature —
 * builds the object graph once and exposes the few calls the UI layer needs.
 *
 * Everything here degrades to a no-op when the database can't be opened, so a
 * caller never has to null-check or reason about availability. Capture calls
 * are fire-and-forget on an IO scope: the user's typing or recording must never
 * wait on segmentation or SQLite.
 */
object VoiceCorrection {

    private const val TAG = "VoiceCorrection"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    private var db: VoiceCorrectionDb? = null
    private var recorder: VoiceCorrectionRecorder? = null
    private var engine: VoiceCorrectionEngine? = null
    private var vocabularyBuilder: TypedVocabularyBuilder? = null

    // [T-android-correction-context-wiring] Kept so buildConversationContext can
    // feed the same segmenter + rarity ranking the retrieval path uses, without
    // exposing them or rebuilding jieba. Null until ensureInitialized succeeds.
    private var segment: ((String) -> List<String>)? = null
    private var rank: ((String) -> Int?)? = null

    /** Build the graph. Idempotent; safe to call from any entry point. */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        initialized = true

        db = VoiceCorrectionDb.getInstance(app)
        val database = db
        if (database == null) {
            Log.w(TAG, "database unavailable — correction disabled for this process")
            return
        }

        val segmenter = TextSegmenter.getInstance(app)
        val segment: (String) -> List<String> = { segmenter.segment(it) }
        val posTag: (String) -> List<Pair<String, String>> = { segmenter.posTag(it) }
        val background = BackgroundWordFrequency.getInstance(app)
        val rank: (String) -> Int? = { background.rank(it) }
        this.segment = segment
        this.rank = rank

        recorder = VoiceCorrectionRecorder(app, database, segment)

        // [T-android-safemode-lateinit-crash-147] subsystemsReady() first: the
        // safe call only rules out a null Application, while the lateinit
        // getter itself throws when onCreate early-returned under safe-mode.
        // Null here already degrades gracefully (correction is skipped).
        val repository = (app as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository
        if (repository != null) {
            engine = VoiceCorrectionEngine(
                sources = listOf(
                    ConfusionDictionarySource(database),
                    TypedVocabularySource(database),
                ),
                strategy = LlmCorrectionStrategy(app, repository),
                db = database,
                segment = segment,
                consent = { VoiceCorrectionConsent.isEnabled(app) },
            )
        }

        // Straight from the Room singleton rather than adding an accessor to
        // MinisApp — AppDatabase.getInstance is already the shared handle.
        val dao = runCatching {
            com.openminis.app.data.db.AppDatabase.getInstance(app).chatDao()
        }.getOrNull()
        if (dao != null) {
            vocabularyBuilder = TypedVocabularyBuilder(app, database, dao, posTag, segment, rank)
        }
    }

    /**
     * Prime the segmenter dictionaries off the critical path. The first jieba
     * call loads ~5MB and takes seconds; without this the first correction (or
     * first capture) stalls visibly.
     */
    fun warmUp(context: Context) {
        ensureInitialized(context)
        scope.launch { engine?.warmUp() }
    }

    /** The correction engine, or null when unavailable. */
    fun engine(context: Context): VoiceCorrectionEngine? {
        ensureInitialized(context)
        return engine
    }

    /**
     * [T-android-correction-context-wiring] Build the budgeted conversation
     * context for a correction pass from the chat's in-memory UI messages.
     * Android port of iOS `voiceCorrectionContext(from:)` (AIChatView.swift).
     *
     * Only real conversation turns feed the context:
     *  - `system` rows (compact dividers + systemInfo notices) are dropped, so
     *    UI copy never leaks into the rare-terms digest — mirrors iOS filtering
     *    out compactDivider / systemInfo.
     *  - assistant prose lives in text `toolBlocks`, not always in `content`
     *    (which can hold a stale title fragment), so for assistant turns the
     *    joined text blocks are the source of truth and `content` is the
     *    fallback. tool_use / thinking / info blocks stay out — commands and
     *    traces are not conversational vocabulary.
     * Returns [ConversationContext.EMPTY] when correction is unavailable or
     * there is nothing to draw from.
     */
    fun buildConversationContext(
        context: Context,
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
        screen: ScreenContextBuilder.Snapshot? = null,
    ): ConversationContext {
        ensureInitialized(context)
        val seg = segment ?: return ConversationContext.EMPTY
        val rnk = rank ?: return ConversationContext.EMPTY

        // [T-android-voice-viewport-context] The on-screen blocks, next to (not
        // instead of) the history-mined ones below.
        val screenContext = runCatching {
            ScreenContextBuilder.build(screen, latestReply(messages))
        }.onFailure { Log.w(TAG, "screen context failed", it) }.getOrNull()
        screenContext?.let { logScreenContext(it) }

        val source = toSourceMessages(messages)
        if (source.isEmpty()) {
            return if (screenContext == null) ConversationContext.EMPTY
            else ConversationContext(screen = screenContext)
        }
        return CorrectionContextBuilder.build(source, seg, rnk).copy(screen = screenContext)
    }

    /**
     * [T-android-voice-viewport-context] The newest assistant reply's text,
     * read the same way [toSourceMessages] reads assistant turns (text blocks
     * first, `content` as fallback, attachment markup stripped). Its message id
     * lets the screen block drop the same reply's on-screen rows.
     */
    internal fun latestReply(
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
    ): ScreenContextBuilder.LatestReply? {
        for (msg in messages.asReversed()) {
            if (msg.role != "assistant") continue
            var text = msg.content
            if (msg.toolBlocks.isNotEmpty()) {
                val blockText = msg.toolBlocks.filter { it.isText }.joinToString("\n") { it.content }
                if (blockText.isNotBlank()) text = blockText
            }
            val stripped = TypedVocabularyBuilder.stripAttachmentMarkup(text).trim()
            if (stripped.isNotEmpty()) return ScreenContextBuilder.LatestReply(msg.id, stripped)
        }
        return null
    }

    /**
     * Debug builds print the exact text the screen blocks carry, so extraction
     * can be checked against what is on the phone: filter logcat on
     * `CorrectionContext` and look for `[Screen:viewport]` / `[Screen:latest]`.
     * Release builds keep only the counts line from [ScreenContextBuilder.build]:
     * this is conversation content. Chunked because logcat truncates long lines.
     */
    private fun logScreenContext(screen: ScreenContext) {
        if (!com.openminis.app.BuildConfig.DEBUG) return
        fun chunked(prefix: String, text: String) {
            text.chunked(800).forEachIndexed { i, part ->
                Log.d("CorrectionContext", "$prefix${if (i > 0) " (cont.)" else ""} $part")
            }
        }
        screen.viewportLines.forEachIndexed { i, line ->
            chunked("[Screen:viewport] #${i + 1}/${screen.viewportLines.size} len=${line.length}", line)
        }
        screen.latestReply?.let {
            chunked("[Screen:latest] onScreen=${screen.latestReplyOnScreen} len=${it.length}", it)
        }
    }

    /**
     * [T-android-correction-context-wiring] The pure UI-message → source-message
     * transform behind [buildConversationContext]. Kept device-independent (no
     * segmenter / SQLite) so the role filter and assistant-text-block extraction
     * can be unit-tested. See [buildConversationContext] for the rules.
     */
    internal fun toSourceMessages(
        messages: List<com.openminis.app.ui.chat.ChatMessage>,
    ): List<CorrectionSourceMessage> = messages.mapNotNull { msg ->
        val role = when (msg.role) {
            "user" -> CorrectionSourceMessage.Role.USER
            "assistant" -> CorrectionSourceMessage.Role.ASSISTANT
            else -> return@mapNotNull null // drop system dividers / notices
        }
        var text = msg.content
        if (role == CorrectionSourceMessage.Role.ASSISTANT && msg.toolBlocks.isNotEmpty()) {
            val blockText = msg.toolBlocks
                .filter { it.isText }
                .joinToString("\n") { it.content }
            if (blockText.isNotBlank()) text = blockText
        }
        val stripped = TypedVocabularyBuilder.stripAttachmentMarkup(text)
        if (stripped.isBlank()) null else CorrectionSourceMessage(role, stripped)
    }

    // -- capture (fire-and-forget) --

    /**
     * The user edited an ASR transcript. Capability #2.
     */
    fun captureTranscriptEdit(
        context: Context,
        before: String,
        after: String,
        locale: String = "zh",
    ) {
        if (before == after) return
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordTranscriptEdit(before, after, locale) }
                .onFailure { Log.w(TAG, "transcript capture failed", it) }
        }
    }

    /**
     * The user selected text in a plain input field and replaced it.
     * Capability #3 — silent background capture, no UI.
     */
    fun captureTextInputEdit(
        context: Context,
        before: String,
        after: String,
        locale: String = "zh",
    ) {
        if (before == after) return
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordTextInputEdit(before, after, locale) }
                .onFailure { Log.w(TAG, "text-input capture failed", it) }
        }
    }

    /** The user applied an AI correction — metrics only. */
    fun captureSuggestionAccepted(
        context: Context,
        original: String,
        corrected: String,
        summary: String,
    ) {
        ensureInitialized(context)
        val r = recorder ?: return
        scope.launch {
            runCatching { r.recordSuggestionAccepted(original, corrected, summary) }
                .onFailure { Log.w(TAG, "accept capture failed", it) }
        }
    }

    /** Mine typed vocabulary if the throttle allows. Cheap to call on send. */
    fun mineVocabularyIfNeeded(context: Context) {
        ensureInitialized(context)
        val b = vocabularyBuilder ?: return
        scope.launch {
            runCatching { b.buildIfNeeded() }
                .onFailure { Log.w(TAG, "vocabulary build failed", it) }
        }
    }

    /** Wipe all learning data. Backs the settings "Clear Collected Data" action. */
    fun clearAllData(context: Context) {
        ensureInitialized(context)
        scope.launch {
            db?.clearAll()
            engine?.clearCache()
        }
    }

    /** Row counts for the settings screen. */
    suspend fun counts(context: Context): Map<String, Int> {
        ensureInitialized(context)
        return db?.counts() ?: emptyMap()
    }
}
