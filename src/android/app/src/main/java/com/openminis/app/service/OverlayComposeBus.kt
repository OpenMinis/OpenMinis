package com.openminis.app.service

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Overlay mini-composer → ChatViewModel. Sticky so a prompt survives until
 * the target session's ViewModel is alive.
 */
object OverlayComposeBus {

    data class Request(val sessionId: String, val text: String)

    private val _pending = MutableSharedFlow<Request>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pending: SharedFlow<Request> = _pending.asSharedFlow()

    /**
     * Read-and-clear must be one atomic step. Two collectors racing a plain
     * `get()` + `set(null)` could both observe the same request and deliver
     * the prompt twice; a CAS loop gives exactly one winner.
     */
    private val sticky = AtomicReference<Request?>(null)

    fun submit(sessionId: String, text: String) {
        val sid = sessionId.trim()
        val body = text.trim()
        if (sid.isEmpty() || body.isEmpty()) return
        val req = Request(sid, body)
        sticky.set(req)
        _pending.tryEmit(req)
    }

    fun consumeSticky(sessionId: String): String? {
        while (true) {
            val current = sticky.get() ?: return null
            if (current.sessionId != sessionId) return null
            if (sticky.compareAndSet(current, null)) return current.text
            // Lost the race to another consumer or a newer submit; re-read.
        }
    }

    fun peekStickySessionId(): String? = sticky.get()?.sessionId
}
