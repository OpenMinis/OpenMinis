package com.openminis.app.speech

/**
 * [T-android-vad-reentrant-release] Owns a native object that must never be
 * freed while a call into it is still on the stack — including a call that
 * re-enters from its own callback.
 *
 * [VoiceActivityDetector] needs both guarantees:
 *  - Cross-thread (T-android-vad, 91c7b2944): stop() on another thread must
 *    wait for an in-flight `processAudio` instead of freeing underneath it.
 *    A plain lock held across the call does that.
 *  - Same-thread re-entrancy: the library fires `onVoiceEnd` from INSIDE
 *    `RealTimeCutVAD::algorithm()`, and a listener that stops the take there
 *    (ProviderSpeechRecognitionEngine, on a silence close) reaches
 *    `release()` on the capture thread that already holds the lock. JVM
 *    monitors are re-entrant, so the lock did not stop it:
 *    `destroyVADInstance` freed the object, the callback returned into
 *    `algorithm()`, and its next read of a member through the freed `this`
 *    faulted — SIGSEGV, fault addr 0x38 at
 *    libRealtimeCutVadLibrary.so `RealTimeCutVAD::algorithm+0x2a4`.
 *
 * [tearDown] reached from inside [call] therefore only detaches the object;
 * [call] frees it after the native call has fully returned. Holding [lock]
 * while inside [call] is what makes [inCall] unambiguous: another thread in
 * [tearDown] blocks on the lock, so seeing `inCall == true` there means this
 * is the calling thread re-entering.
 */
internal class ReentrantSafeRelease<T : Any>(private val release: (T) -> Unit) {
    private val lock = Any()
    private var live: T? = null // guarded by lock
    private var inCall = false // guarded by lock
    private var deferred: T? = null // guarded by lock

    fun attach(value: T) = synchronized(lock) { live = value }

    /**
     * Run [block] on the live object under the lock. Returns false when the
     * object was already torn down (block not run) or was torn down during
     * [block] by a re-entrant [tearDown]; in that case it is freed here, after
     * [block] returns.
     */
    fun call(block: (T) -> Unit): Boolean = synchronized(lock) {
        val target = live ?: return false
        inCall = true
        try {
            block(target)
        } finally {
            inCall = false
            deferred?.let { d ->
                deferred = null
                runCatching { release(d) }
            }
        }
        live != null
    }

    /** Detach and free — or, from inside [call], detach now and free when it returns. */
    fun tearDown() {
        synchronized(lock) {
            val target = live ?: return
            live = null
            if (inCall) {
                deferred = target
            } else {
                runCatching { release(target) }
            }
        }
    }
}
