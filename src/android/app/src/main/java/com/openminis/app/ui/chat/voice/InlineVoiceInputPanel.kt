package com.openminis.app.ui.chat.voice

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring

/**
 * [T-android-voice-panel] Inline voice input panel — Android port of iOS
 * InlineVoiceInputView + VoiceInputViewModel behaviors that map onto the
 * SpeechRecognitionManager engine stack:
 *
 *  • Two modes: compact (~92dp, mic only + buffered-chars hint) and expanded
 *    (transcript band + status tips + focal mic + delete + engine chip).
 *    Expanded state is remembered across sessions (VoiceModePrefs, backed by
 *    SharedPreferences), reset to expanded on text→voice entry and collapsed
 *    after a send — same rules.
 *  • Tap mic: start capture; tap again to stop ("Tap to transcribe"). The
 *    system engine also auto-stops on silence (matches iOS VAD auto-stop);
 *    finals APPEND to the running transcript, partials preview live.
 *  • Transcript mirrors into the composer text both ways; double-tap to edit
 *    (capture paused), Exit chip / mic resumes.
 *  • Delete key: tap = one char, hold = repeat delete, long-press menu-free
 *    (hold ≥2s keeps deleting; "clear all" via transcript long-press like iOS
 *    context menu is covered by the hold-to-repeat reaching empty).
 *  • Engine chip "Group · Model ⇅" opens the single-select voice-input picker.
 *  • Globe menu switches the recognition language (system engine locales).
 */

/**
 * Voice-mode runtime state (mirrors iOS VoiceModePreference).
 *
 * Distinct from [com.openminis.app.ui.chat.ComposerInputModePrefs], which is a
 * different concern: that one records which composer mode the user PREFERS
 * (written on send, currently read by nobody since auto-enter-voice was removed
 * in T-android-remove-auto-enter-voice). This object is the LIVE state of the
 * panel for the current process.
 *
 * Only [expanded] is a genuine user preference and is persisted. The other
 * three are intentionally transient:
 *  - [isVoiceActive] must start false every launch — the composer always opens
 *    in text mode (T-android-remove-auto-enter-voice); persisting it would
 *    resurrect the auto-enter behaviour that was deliberately removed.
 *  - [enteredFromText] is a one-shot within a single transition.
 *  - [lastSpokenAssistantKey] guards against re-reading a reply; persisting it
 *    would wrongly suppress read-aloud for that message after a restart.
 */
object VoiceModePrefs {

    private const val PREFS = "voice_prefs"
    private const val KEY_EXPANDED = "voice.panel.expanded"

    /** Set once from [init]; without it [expanded] can't reach SharedPreferences. */
    private var appContext: android.content.Context? = null

    var isVoiceActive by mutableStateOf(false)

    private var expandedState by mutableStateOf(true)

    /**
     * Panel expanded vs compact. Backed by SharedPreferences so the choice
     * survives process death — previously this was in-memory only despite the
     * panel's KDoc claiming it was "remembered across sessions".
     */
    var expanded: Boolean
        get() = expandedState
        set(value) {
            expandedState = value
            appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(KEY_EXPANDED, value)?.apply()
        }

    /** One-shot: text→voice entry opens expanded that one time. */
    var enteredFromText = false

    /** Hash of the last auto-spoken assistant reply so history isn't re-read. */
    var lastSpokenAssistantKey: Int = 0

    /** Load the persisted [expanded] value. Idempotent; safe to call per composition. */
    fun init(context: android.content.Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        expandedState = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPANDED, true)
    }
}


@Composable
fun InlineVoiceInputPanel(
    providerRepository: ProviderRepository,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    ensureMicPermission: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    // [T-android-correction-context-wiring] Supplies the conversation context
    // for AI correction, built from the chat's current messages. Mirrors iOS
    // InlineVoiceInputView's `conversationContext` closure. Defaults to EMPTY so
    // the legacy/standalone construction still compiles and runs.
    conversationContextProvider: () -> com.openminis.app.speech.correction.ConversationContext = {
        com.openminis.app.speech.correction.ConversationContext.EMPTY
    },
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sttState by SpeechRecognitionManager.state.collectAsState()
    val locale by SpeechRecognitionManager.locale.collectAsState()
    val supportedLocales by SpeechRecognitionManager.supportedLocales.collectAsState()
    val levels by SpeechRecognitionManager.audioLevels.collectAsState()

    // Load the persisted expanded/compact choice before it is read below.
    // Idempotent, so running on every composition is harmless.
    val panelContext = androidx.compose.ui.platform.LocalContext.current
    VoiceModePrefs.init(panelContext)

    var expanded by remember { mutableStateOf(VoiceModePrefs.expanded) }
    var transcript by remember { mutableStateOf(inputText) }
    var isEditing by remember { mutableStateOf(false) }

    // [T-android-voice-correction] Transcript captured when edit mode opens, so
    // leaving it can hand the before/after pair to the learning recorder. The
    // recorder decides whether the change is a CORRECTION (phonetically close)
    // or the user simply rewriting; nothing here needs to judge that.
    var editSnapshot by remember { mutableStateOf<String?>(null) }

    // [T-android-voice-correction] Manual AI correction of the transcript.
    var isCorrecting by remember { mutableStateOf(false) }
    var showCorrectionConsent by remember { mutableStateOf(false) }

    // Every exit path funnels through here so no route can silently skip
    // capture. Capture is fire-and-forget — leaving edit mode never waits on
    // segmentation or SQLite.
    fun leaveEditMode() {
        val before = editSnapshot
        editSnapshot = null
        isEditing = false
        if (before != null) {
            com.openminis.app.speech.correction.VoiceCorrection.captureTranscriptEdit(
                context = panelContext,
                before = before,
                after = transcript,
            )
        }
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var transcribeError by remember { mutableStateOf<String?>(null) }
    var recordingTipIndex by remember { mutableIntStateOf(0) }
    var resultTipIndex by remember { mutableIntStateOf(0) }
    var showModelSelector by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    // Base text captured at capture start; partials render as base + partial.
    var captureBase by remember { mutableStateOf("") }

    val isRecording = sttState == RecognitionState.RECORDING || sttState == RecognitionState.STARTING
    val isTranscribing = sttState == RecognitionState.FINISHING

    val config by providerRepository.config.collectAsState()
    val choice = remember(config) { providerRepository.resolveVoiceInputChoice() }
    val groupName = remember(config) { providerRepository.voiceInputGroupName() }
    val modelLabel = if (choice.isSystem) {
        stringResource(
            if (choice.systemPreferOffline == true) R.string.voice_system_recognition_offline
            else R.string.voice_system_recognition_online,
        )
    } else {
        choice.entry!!.second.model.displayName
    }

    fun persistExpanded(v: Boolean) {
        expanded = v
        VoiceModePrefs.expanded = v
    }

    fun setTranscript(text: String) {
        transcript = text
        if (inputText != text) onInputTextChange(text)
    }

    // Runs the correction engine over the current transcript and applies the
    // result in place. Two outcomes are reported DIFFERENTLY on purpose: "no
    // corrections needed" is a semantic verdict, while a timeout or API error
    // is a failure — collapsing them would disguise an outage as "your text
    // was fine".
    fun runCorrection() {
        val text = transcript.trim()
        if (text.isEmpty() || isCorrecting) return
        val engine = com.openminis.app.speech.correction.VoiceCorrection.engine(panelContext)
        if (engine == null) {
            android.widget.Toast.makeText(
                panelContext,
                panelContext.getString(R.string.voice_correction_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        isCorrecting = true
        scope.launch {
            // [T-android-correction-context-wiring] Feed the real conversation
            // context (rare-term digest + recent excerpts) instead of EMPTY, so
            // the model can resolve homophones against proper nouns / terms the
            // conversation already established. Built off the UI thread.
            val convoContext = withContext(Dispatchers.Default) {
                runCatching { conversationContextProvider() }
                    .getOrDefault(com.openminis.app.speech.correction.ConversationContext.EMPTY)
            }
            val suggestion = engine.correct(text, context = convoContext)
            isCorrecting = false
            when {
                suggestion.hasChange -> {
                    setTranscript(suggestion.corrected)
                    // Applying a correction the user ASKED for is itself the
                    // acceptance signal; requiring a second confirming tap is
                    // why iOS measured an acceptance rate of zero.
                    com.openminis.app.speech.correction.VoiceCorrection
                        .captureSuggestionAccepted(
                            panelContext,
                            suggestion.original,
                            suggestion.corrected,
                            suggestion.diffSummary,
                        )
                }
                suggestion.rejectedReason == null ||
                    suggestion.rejectedReason == "empty_input" -> {
                    android.widget.Toast.makeText(
                        panelContext,
                        panelContext.getString(R.string.voice_correction_none_needed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                else -> {
                    android.widget.Toast.makeText(
                        panelContext,
                        panelContext.getString(R.string.voice_correction_failed),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun stopCapture() {
        SpeechRecognitionManager.stopRecording()
    }

    fun startCapture() {
        // Route the engine per the resolved choice (mirrors iOS provider resolve
        // on prepare/refresh).
        val engineId = if (choice.isSystem) "system" else "provider"
        SpeechRecognitionManager.selectEngine(engineId)
        (SpeechRecognitionManager.availableEngines()
            .firstOrNull { it.id == "system" } as? com.openminis.app.speech.SystemSpeechRecognitionEngine)
            ?.preferOffline = (choice.systemPreferOffline == true)
        captureBase = transcript
        transcribeError = null
        SpeechRecognitionManager.startRecording(
            // [T-android-vad] Commit only on a FINAL result.
            //
            // Previously every interim hypothesis was written straight into the
            // composer, which is what made Android feel live while iOS waited
            // for a silence window. The engines no longer forward partials at
            // all, so in practice this fires once per utterance — but the guard
            // is explicit rather than assumed, so a future engine that does
            // emit partials cannot silently reintroduce streaming.
            //
            // Appending (not replacing) matches iOS: successive utterances join
            // with a single space (VoiceInputPanel.swift:931).
            onPartialOrFinal = { text, isFinal ->
                if (isEditing) return@startRecording
                if (!isFinal) return@startRecording
                if (text.isBlank()) return@startRecording
                val sep = if (captureBase.isEmpty() || captureBase.endsWith(" ")) "" else " "
                val joined = captureBase + sep + text
                captureBase = joined
                setTranscript(joined)
            },
            onError = { error, message ->
                when (error) {
                    com.openminis.app.speech.RecognitionError.NO_MATCH -> {}
                    com.openminis.app.speech.RecognitionError.PERMISSION_DENIED ->
                        permissionDenied = true
                    else -> transcribeError = message ?: error.name
                }
            },
        )
    }

    fun handleMicTap() {
        when {
            isTranscribing -> {
                // Spinner while idle → X cancels the in-flight transcription.
                SpeechRecognitionManager.cancelRecording()
            }
            isRecording -> stopCapture()
            else -> scope.launch {
                // Re-check on every tap: a permission granted moments after a
                // DENIED race (settings-gate cancel) must not dead-end the mic.
                if (!ensureMicPermission()) {
                    permissionDenied = true
                    return@launch
                }
                permissionDenied = false
                if (isEditing) leaveEditMode()
                startCapture()
            }
        }
    }

    // ── Lifecycle: prepare on mount (default groups + engine warm), stop on exit.
    LaunchedEffect(Unit) {
        providerRepository.ensureDefaultVoiceInputGroup()
        providerRepository.ensureDefaultVoiceOutputGroup()
        // [T-android-voice-correction] Load the jieba dictionaries now, while
        // the user is still reaching for the mic. The first segment call costs
        // seconds (~5MB dictionary + HMM model); paying it here rather than on
        // the first capture keeps correction from feeling broken.
        com.openminis.app.speech.correction.VoiceCorrection.warmUp(panelContext)
        // Seed the transcript from whatever was typed (text→voice carry).
        transcript = inputText
        if (VoiceModePrefs.enteredFromText) {
            VoiceModePrefs.enteredFromText = false
            persistExpanded(true)
        }
        SpeechRecognitionManager.refreshSupportedLocales()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                SpeechRecognitionManager.cancelRecording()
            }
        }
    }

    // External input change (send clears it; "add reply to input") → sync back.
    LaunchedEffect(inputText) {
        if (inputText != transcript) {
            transcript = inputText
            captureBase = inputText
            if (inputText.isEmpty()) {
                // A send emptied the composer → collapse to compact (iOS
                // collapseAfterSendToken) and stop any live capture.
                if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                    SpeechRecognitionManager.cancelRecording()
                }
                leaveEditMode()
                persistExpanded(false)
            } else if (!expanded) {
                persistExpanded(true)
            }
        }
    }
    // Recognized text while compact → expand so the user sees it.
    LaunchedEffect(transcript) {
        if (transcript.isNotEmpty() && !expanded) persistExpanded(true)
    }

    // ── Tip cycling (4s), mirrors iOS beginRecordingLabelCycle/ResultTipCycle.
    LaunchedEffect(isRecording) {
        recordingTipIndex = 0
        while (isRecording) {
            delay(4000)
            recordingTipIndex += 1
        }
    }
    LaunchedEffect(transcript.isNotEmpty(), isRecording) {
        resultTipIndex = 0
        while (!isRecording && transcript.isNotEmpty()) {
            delay(4000)
            resultTipIndex += 1
        }
    }

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val maxPanel = screenH * 0.6f

    // [T-android-voice-entry-always-available] No usable ASR engine. The mic
    // button no longer hides in this case (hiding it stranded users inside
    // voice mode), so the panel owns the explanation: say WHY nothing will be
    // transcribed and link to the two things that fix it — the system speech
    // service, or an ASR provider configured in Minis. The composer's toggle
    // stays visible throughout, so leaving is always one tap away.
    val engineAvailable by SpeechRecognitionManager.isAvailable.collectAsState()
    if (!engineAvailable) {
        VoiceEngineUnavailableNotice(
            expanded = expanded,
            onToggleExpanded = { persistExpanded(!expanded) },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (expanded) ExpandedBase else CompactHeight, max = maxPanel)
            .animateContentSize(animationSpec = tween(280))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Top-left: expand/collapse chevron.
            CircleIconButton(
                icon = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(
                    if (expanded) R.string.voice_panel_collapse else R.string.voice_panel_expand,
                ),
                modifier = Modifier.align(Alignment.TopStart),
            ) { persistExpanded(!expanded) }

            // [T-android-voice-correction] One-time collection-consent prompt,
            // shown on the very first tap of the correction button. After the
            // user answers — either way — it never appears again, and the
            // correction runs regardless: consent governs whether we STORE the
            // learning data, not whether the feature works.
            if (showCorrectionConsent) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        // Dismissing without choosing is not an answer; leave
                        // hasPrompted unset so the question can be asked again.
                        showCorrectionConsent = false
                    },
                    title = { Text(stringResource(R.string.voice_correction_consent_title)) },
                    text = { Text(stringResource(R.string.voice_correction_consent_body)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            com.openminis.app.speech.correction.VoiceCorrectionConsent
                                .setEnabled(panelContext, true)
                            com.openminis.app.speech.correction.VoiceCorrectionConsent
                                .setPrompted(panelContext, true)
                            showCorrectionConsent = false
                            runCorrection()
                        }) {
                            Text(stringResource(R.string.voice_correction_consent_enable))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            com.openminis.app.speech.correction.VoiceCorrectionConsent
                                .setPrompted(panelContext, true)
                            showCorrectionConsent = false
                            runCorrection()
                        }) {
                            Text(stringResource(R.string.voice_correction_consent_not_now))
                        }
                    },
                )
            }

            // [T-android-voice-correction] Top-right slot: the AI-correction
            // button REPLACES the language globe once there is a transcript to
            // fix (mirrors iOS). Same 30dp circle, so swapping the glyph never
            // reflows the panel. Correction is a manual, one-shot action — no
            // debounce, no automatic pass — because a surprise rewrite of what
            // the user just dictated is worse than no correction at all.
            if (!isEditing && transcript.isNotBlank()) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    if (isCorrecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ChatColors.secondaryText,
                        )
                    } else {
                        CircleIconButton(
                            icon = Icons.Default.AutoAwesome,
                            contentDescription = stringResource(
                                R.string.voice_correction_button_a11y,
                            ),
                        ) {
                            // First tap ever shows the one-time consent prompt;
                            // afterwards it runs directly. The correction itself
                            // runs regardless of the answer — consent governs
                            // STORING data, not using the feature.
                            if (!com.openminis.app.speech.correction.VoiceCorrectionConsent
                                    .hasPrompted(panelContext)
                            ) {
                                showCorrectionConsent = true
                            } else {
                                runCorrection()
                            }
                        }
                    }
                }
            } else if (!isEditing) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    CircleIconButton(
                        icon = Icons.Default.Language,
                        contentDescription = stringResource(R.string.voice_panel_language),
                    ) { showLanguageMenu = true }
                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false },
                    ) {
                        val options = supportedLocales.ifEmpty { listOf(locale) }
                        options.forEach { opt ->
                            DropdownMenuItem(
                                text = {
                                    val selected = opt.toLanguageTag() == locale.toLanguageTag()
                                    Text(
                                        (if (selected) "✓ " else "") + opt.getDisplayName(opt),
                                    )
                                },
                                onClick = {
                                    SpeechRecognitionManager.selectLocale(opt)
                                    showLanguageMenu = false
                                },
                            )
                        }
                    }
                }
            }

            if (expanded) {
                ExpandedContent(
                    transcript = transcript,
                    isEditing = isEditing,
                    isRecording = isRecording,
                    isTranscribing = isTranscribing,
                    permissionDenied = permissionDenied,
                    transcribeError = transcribeError,
                    recordingTipIndex = recordingTipIndex,
                    resultTipIndex = resultTipIndex,
                    levels = levels,
                    groupName = groupName,
                    modelLabel = modelLabel,
                    onMicTap = { handleMicTap() },
                    onTranscriptChange = { setTranscript(it) },
                    onBeginEdit = {
                        if (!isEditing) {
                            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                                SpeechRecognitionManager.cancelRecording()
                            }
                            editSnapshot = transcript
                            isEditing = true
                        }
                    },
                    onExitEdit = { leaveEditMode() },
                    onDeleteLast = { if (transcript.isNotEmpty()) setTranscript(transcript.dropLast(1)) },
                    onOpenSelector = { showModelSelector = true },
                )
            } else {
                CompactContent(
                    transcript = transcript,
                    isRecording = isRecording,
                    isTranscribing = isTranscribing,
                    levels = levels,
                    onMicTap = { handleMicTap() },
                )
            }
        }
    }

    if (showModelSelector) {
        VoiceInputPickerSheet(
            providerRepository = providerRepository,
            onDismiss = { showModelSelector = false },
        )
    }
}

// ── Expanded layout ─────────────────────────────────────────────────────────

@Composable
private fun ExpandedContent(
    transcript: String,
    isEditing: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    permissionDenied: Boolean,
    transcribeError: String?,
    recordingTipIndex: Int,
    resultTipIndex: Int,
    levels: List<Float>,
    groupName: String?,
    modelLabel: String,
    onMicTap: () -> Unit,
    onTranscriptChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onExitEdit: () -> Unit,
    onDeleteLast: () -> Unit,
    onOpenSelector: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Transcript band — grows, then scrolls; double-tap to edit.
        if (isEditing || transcript.isNotEmpty()) {
            TranscriptArea(
                transcript = transcript,
                isEditing = isEditing,
                onTranscriptChange = onTranscriptChange,
                onBeginEdit = onBeginEdit,
                onClearAll = { onTranscriptChange("") },
            )
            Spacer(Modifier.height(6.dp))
        }

        // Status line (+ Exit chip while editing). Inset 36dp each side so a
        // long message never runs under the corner circle buttons.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Text(
                text = statusLabel(
                    permissionDenied, isEditing, isRecording, isTranscribing,
                    transcript, recordingTipIndex, resultTipIndex,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = ChatColors.secondaryText,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (isEditing) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.voice_panel_exit_edit),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.secondaryText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ChatColors.secondaryText.copy(alpha = 0.15f))
                        .clickable { onExitEdit() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }

        // Transcribe-error pill.
        if (transcribeError != null) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF9500).copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text("⚠︎", fontSize = 10.sp, color = Color(0xFFFF9500))
                Spacer(Modifier.width(5.dp))
                Text(
                    transcribeError,
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatColors.secondaryText,
                    maxLines = 2,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Focal mic + delete to its right (only when there's text).
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Never disabled: tapping while "denied" re-runs the permission
            // flow (covers the granted-after-race case).
            MicCircleButton(
                diameter = ExpandedMic,
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                levels = levels,
                enabled = true,
                onTap = onMicTap,
            )
            if (transcript.isNotEmpty() && !isEditing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoiceDeleteButton(onDeleteOne = onDeleteLast)
                    Spacer(Modifier.width(30.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Active ASR chip: "Voice Input · Model ⇅".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ChatColors.secondaryText.copy(alpha = 0.1f))
                .clickable { onOpenSelector() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (groupName != null) {
                Text(
                    groupName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  ·  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatColors.secondaryText.copy(alpha = 0.5f),
                )
            }
            Text(
                modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = ChatColors.secondaryText.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Compact layout ──────────────────────────────────────────────────────────

@Composable
private fun CompactContent(
    transcript: String,
    isRecording: Boolean,
    isTranscribing: Boolean,
    levels: List<Float>,
    onMicTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(CompactHeight - 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (transcript.isNotEmpty()) {
            Text(
                stringResource(R.string.voice_panel_buffered_chars, transcript.length),
                style = MaterialTheme.typography.labelSmall,
                color = ChatColors.secondaryText,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
        }
        MicCircleButton(
            diameter = CompactMic,
            isRecording = isRecording,
            isTranscribing = isTranscribing,
            levels = levels,
            enabled = true,
            onTap = onMicTap,
        )
    }
}

// ── Pieces ──────────────────────────────────────────────────────────────────

@Composable
private fun TranscriptArea(
    transcript: String,
    isEditing: Boolean,
    onTranscriptChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onClearAll: () -> Unit,
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val bandMax = screenH * 0.6f - ExpandedBase
    // [T-android-voice-dbltap-select] Word range computed by the double-tap
    // below, consumed once when the editor composes. Declared outside the
    // branch split so the display state can write it and the editor state
    // can read it across the same recomposition.
    var pendingSelection by remember { mutableStateOf<TextRange?>(null) }
    if (isEditing) {
        val focus = remember { FocusRequester() }
        // TextFieldValue overload (not the String one) so entering edit mode
        // can arrive with the double-tapped word already selected. Keyed on
        // isEditing: re-seeded on every entry, preserved across keystrokes.
        var editValue by remember(isEditing) {
            mutableStateOf(
                TextFieldValue(transcript, pendingSelection ?: TextRange(transcript.length))
            )
        }
        // Mirror external transcript changes (input-text carry) into the
        // field — the String overload did this implicitly. Keystrokes round
        // trip as no-ops because editValue.text already equals transcript.
        LaunchedEffect(transcript) {
            if (editValue.text != transcript) {
                val sel = editValue.selection
                editValue = TextFieldValue(
                    transcript,
                    TextRange(
                        sel.start.coerceIn(0, transcript.length),
                        sel.end.coerceIn(0, transcript.length),
                    ),
                )
            }
        }
        BasicTextField(
            value = editValue,
            onValueChange = { new ->
                editValue = new
                onTranscriptChange(new.text)
            },
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp, max = bandMax)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
                .focusRequester(focus),
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
    } else {
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = bandMax)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            // Tap detection lives on the Text itself (not the band) so the
            // reported position is already in text-layout space and maps
            // straight onto getOffsetForPosition — no coordinate algebra
            // across the band's padding/scroll.
            Text(
                transcript,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                onTextLayout = { textLayout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(transcript) {
                        detectTapGestures(
                            onDoubleTap = { pos ->
                                val layout = textLayout
                                val charOffset = if (layout != null) {
                                    runCatching { layout.getOffsetForPosition(pos) }
                                        .getOrDefault(transcript.length)
                                } else {
                                    transcript.length
                                }
                                pendingSelection = voiceWordRangeAt(transcript, charOffset)
                                onBeginEdit()
                            },
                            onLongPress = { onClearAll() },
                        )
                    },
            )
        }
    }
}

