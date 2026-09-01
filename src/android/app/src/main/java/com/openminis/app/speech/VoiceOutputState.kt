package com.openminis.app.speech

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-tts-capsule] GLOBAL read-replies (TTS output) state — the single
 * source of truth shared by the composer's "Read replies" pill and the floating
 * speech-player capsule. Port of iOS `VoiceOutputState`
 * (VoiceProviderResolver.swift:66).
 *
 * Three-level model, in lockstep with iOS:
 *  • disabled (!isEnabled)          → no capsule, no TTS.
 *  • muted    (isEnabled && isMuted)→ capsule stays VISIBLE (so the user can
 *                                     un-mute), playback stopped, new reply
 *                                     TTS suppressed.
 *  • active   (isEnabled && !muted) → replies are spoken.
 *
 * Persisted in the same `voice_prefs` file the pill already used, under the
 * SAME "readReplies" key — so the pill, the capsule, and the streaming
 * read-aloud effect can never disagree about whether TTS is on.
 */
object VoiceOutputState {

    private const val PREFS = "voice_prefs"
    private const val KEY_ENABLED = "readReplies"
    private const val KEY_MUTED = "readReplies.muted"
    private const val KEY_SPEED = "readReplies.speed"
    private const val KEY_SYSTEM_VOICE = "readReplies.systemVoice"
    private const val KEY_SYSTEM_VOICE_LABEL = "readReplies.systemVoiceLabel"

    // [T-android-tts-capsule-drag] Dragged position, stored in DP as an offset
    // RELATIVE to the resting bottom-end corner — never as an absolute screen
    // coordinate. Same choice as iOS `VoiceOutputPreferences.capsuleAnchorOffset`
    // and for the same reason: a relative offset stays meaningful after a
    // rotation, a window resize, or a keyboard-driven height change, whereas an
    // absolute point would put the capsule off-screen. x is negative moving
    // LEFT from the right edge, y negative moving UP from the bottom.
    private const val KEY_DRAG_X_DP = "readReplies.capsuleDragXDp"
    private const val KEY_DRAG_Y_DP = "readReplies.capsuleDragYDp"

    /** Speed cycle, matching iOS `VoiceOutputPreferences.speedSteps`. */
    val SPEED_STEPS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)

    // @Volatile: init() runs from both ChatScreen's LaunchedEffect and
    // ReadAloudPlayer.init, and a player can be constructed off the main
    // thread — without it a caller could observe a half-published object.
    @Volatile
    private var prefs: SharedPreferences? = null

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /**
     * [T-android-tts-capsule-drag] Persisted drag offset in DP from the resting
     * bottom-end corner, or null when the user has never dragged (rest at the
     * default corner). Exposed as a flow so the capsule restores it on mount.
     */
    private val _dragOffsetDp = MutableStateFlow<Pair<Float, Float>?>(null)
    val dragOffsetDp: StateFlow<Pair<Float, Float>?> = _dragOffsetDp.asStateFlow()

    // Live state mirrored from the active ReadAloudPlayer so the capsule can
    // render playback/synthesis without owning the player.
    val isSpeaking = MutableStateFlow(false)
    val isSynthesizing = MutableStateFlow(false)

    /** Label of the model that is ACTUALLY synthesizing (post fail-over). */
    val activeModelLabel = MutableStateFlow<String?>(null)

    /**
     * [T-android-system-voice-catalog] Selected SYSTEM engine voice (engine
     * voice name, e.g. "cmn-cn-x-ssa-local"); null = engine default ("Auto").
     * Deliberately a system-engine detail rather than a provider-override
     * entry: it layers under resolveVoiceOutputChoice() without touching
     * ProviderRepository, while the picker still renders each voice as its own
     * selectable row like iOS.
     */
    private val _systemVoiceName = MutableStateFlow<String?>(null)
    val systemVoiceName: StateFlow<String?> = _systemVoiceName.asStateFlow()

    /** Human label for [systemVoiceName], persisted so the capsule chip can
     *  show it without re-enumerating the catalog. */
    private val _systemVoiceLabel = MutableStateFlow<String?>(null)
    val systemVoiceLabel: StateFlow<String?> = _systemVoiceLabel.asStateFlow()

    fun setSystemVoice(name: String?, label: String?) {
        _systemVoiceName.value = name
        _systemVoiceLabel.value = label
        prefs?.edit()
            ?.putString(KEY_SYSTEM_VOICE, name)
            ?.putString(KEY_SYSTEM_VOICE_LABEL, label)
            ?.apply()
    }

    /**
     * [T-android-tts-player-registry] Stack of live players, most recent last.
     * [activePlayer] is the top — the one the capsule's mute/close/speed act on.
     *
     * This was a single last-writer-wins slot, which broke as soon as TWO
     * players existed (the reply channel in ChatScreen, and the selection
     * Read-Aloud player that constructs lazily on first use):
     *
     *  - the selection player's `init` overwrote the slot mid-reply, so the
     *    capsule's mute/close stopped IT while the reply kept speaking;
     *  - when the selection player shut down, the slot went to null rather
     *    than back to the reply player, so the capsule permanently lost its
     *    handle even after the thief was gone.
     *
     * A stack fixes both: whoever is most recent gets the controls, and
     * dismissing them restores the previous owner. Deregistration is by
     * identity so a mid-stack player leaving doesn't disturb the rest.
     */
    private val players = ArrayDeque<ReadAloudPlayer>()

    val activePlayer: ReadAloudPlayer?
        get() = synchronized(players) { players.lastOrNull() }

    fun registerPlayer(player: ReadAloudPlayer) = synchronized(players) {
        players.remove(player)
        players.addLast(player)
    }

    fun unregisterPlayer(player: ReadAloudPlayer) = synchronized(players) {
        players.remove(player)
    }

    /** True when [player] currently owns the capsule — gates the live-state
     *  mirror writes so a background player can't flicker the UI. */
    fun isActivePlayer(player: ReadAloudPlayer): Boolean =
        synchronized(players) { players.lastOrNull() === player }

    /** Effective gate for producing reply audio: enabled AND not muted. */
    val canPlay: Boolean get() = _isEnabled.value && !_isMuted.value

    /**
     * [T-android-tts-stop-on-capture] Silence EVERY live player because the mic
     * is about to open, holding unspoken text for [resumeAllAfterCapture].
     *
     * ReadAloudPlayer.enqueue already refuses NEW utterances while the
     * recognizer is capturing, but that only covered text arriving after the
     * mic opened. Audio already in flight kept playing straight into the
     * microphone — the speaker feeds back into dictation, so the assistant's
     * own narration gets transcribed on top of what the user said, and
     * playback fights the recognizer for the audio route.
     *
     * Applies to all registered players, not just [activePlayer]: the reply
     * channel and the selection Read-Aloud player can both be live, and
     * leaving the non-active one talking is exactly the echo this prevents.
     */
    fun suspendAllForCapture() {
        val live = synchronized(players) { players.toList() }
        live.forEach { runCatching { it.suspendForCapture() } }
    }

    /** Resume every player parked by [suspendAllForCapture]. */
    fun resumeAllAfterCapture() {
        val live = synchronized(players) { players.toList() }
        live.forEach { runCatching { it.resumeAfterCapture() } }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _isEnabled.value = p.getBoolean(KEY_ENABLED, false)
        _isMuted.value = p.getBoolean(KEY_MUTED, false)
        _speed.value = p.getFloat(KEY_SPEED, 1.0f)
        _systemVoiceName.value = p.getString(KEY_SYSTEM_VOICE, null)
        _systemVoiceLabel.value = p.getString(KEY_SYSTEM_VOICE_LABEL, null)
        // [T-android-tts-capsule-drag] Both keys must be present: a half-written
        // pair (or a default sentinel) would silently anchor the capsule to a
        // bogus corner, so treat anything incomplete as "never dragged".
        _dragOffsetDp.value =
            if (p.contains(KEY_DRAG_X_DP) && p.contains(KEY_DRAG_Y_DP)) {
                p.getFloat(KEY_DRAG_X_DP, 0f) to p.getFloat(KEY_DRAG_Y_DP, 0f)
            } else {
                null
            }
    }

    /**
     * [T-android-tts-capsule-drag] Persist the capsule's dragged position.
     * Called once on drag RELEASE, not per movement frame — a SharedPreferences
     * write per pointer event would be dozens of disk commits per gesture.
     */
    fun setCapsuleDragOffsetDp(xDp: Float, yDp: Float) {
        _dragOffsetDp.value = xDp to yDp
        prefs?.edit()
            ?.putFloat(KEY_DRAG_X_DP, xDp)
            ?.putFloat(KEY_DRAG_Y_DP, yDp)
            ?.apply()
    }

    fun setEnabled(value: Boolean) {
        if (_isEnabled.value == value) return
        _isEnabled.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        if (!value) {
            // Off → stop whatever is being read, drop the queue, clear the
            // temporary mute (meaningless once fully off; a fresh enable
            // should start un-muted). Mirrors iOS isEnabled.didSet.
            activePlayer?.stop()
            setMuted(false)
        }
    }

    fun setMuted(value: Boolean) {
        if (_isMuted.value == value) return
        _isMuted.value = value
        prefs?.edit()?.putBoolean(KEY_MUTED, value)?.apply()
        // Mute silences the CURRENT playback too, not just future utterances.
        if (value) activePlayer?.stop()
    }

    fun setSpeed(value: Float) {
        if (_speed.value == value) return
        _speed.value = value
        prefs?.edit()?.putFloat(KEY_SPEED, value)?.apply()
    }

    /** Cycle 1× → 1.25× → 1.5× → 2× → 1×. */
    fun nextSpeed() {
        // Tolerance, not exact float equality: the current value round-trips
        // through SharedPreferences.getFloat, and any future step that isn't
        // exactly representable would miss, making idx -1 → the cycle would
        // RESET to 1× instead of advancing. Today's steps happen to round-trip
        // exactly; this stops that from being load-bearing.
        val idx = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - _speed.value) < 0.001f }
        setSpeed(SPEED_STEPS[(if (idx < 0) 0 else idx + 1) % SPEED_STEPS.size])
    }

    fun speedLabel(s: Float = _speed.value): String =
        if (s == s.toInt().toFloat()) "${s.toInt()}×" else "$s×"
}
