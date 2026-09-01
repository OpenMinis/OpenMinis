package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.speech.ReadAloudPlayer

/**
 * [T-android-selection-readaloud] Defers [ReadAloudPlayer] construction until
 * something is actually spoken.
 *
 * Constructing the player binds an Android TextToSpeech engine (see
 * `TextToSpeechManager.init`), so building one eagerly for every ChatScreen
 * would pay that cost for the large majority of sessions that never use the
 * selection toolbar's Read Aloud action. [shutdown] is safe to call when
 * nothing was ever created.
 */
internal class LazyReadAloudPlayer(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var player: ReadAloudPlayer? = null

    /** Speak [text] as one utterance, stopping anything already in flight. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val p = player ?: synchronized(this) {
            player ?: ReadAloudPlayer(appContext).also { player = it }
        }
        p.speak(text)
    }

    /**
     * [T-android-readaloud-stop-stale] Stop whatever is currently being read.
     *
     * Deliberately does NOT bind an engine: if nothing has ever been spoken
     * there is nothing to stop, and constructing a TTS engine here would undo
     * the whole point of the lazy player (an unused ChatScreen never binds
     * one). Idempotent and safe to call on every new reply.
     */
    fun stop() {
        player?.stop()
    }

    /** Release the engine if one was ever bound. Idempotent. */
    fun shutdown() {
        synchronized(this) {
            player?.shutdown()
            player = null
        }
    }
}
