package com.openminis.app.ui.chat

/**
 * [T-android-vm-evict-busy] Decides whether a cached ChatViewModel has work in
 * flight that evicting it would destroy.
 *
 * `ChatViewModelStore` used `SessionActivityTracker.activeSessions` as its only
 * "busy" signal. That set is populated by `setActive`, which the stream job
 * calls AFTER `SessionConcurrencyManager.acquireSlot()` — a suspension that
 * can last as long as other sessions hold the slots. So a session whose user
 * had just pressed Send, but which was still waiting for a slot, read as idle;
 * an LRU trim or an `onTrimMemory` pass cleared its store, `viewModelScope`
 * was cancelled with it, and the send died with no reply and no error.
 *
 * The same blind spot covers a parent whose background sub agents are still
 * running or queued (post 519c76825 the parent's own turn ends while children
 * run): the tracker is inactive, the parent is evicted, `onCleared` removes
 * its queued-delegation starter and the child's finished callback has nowhere
 * to land.
 *
 * Every input here is a fact the view model can read synchronously; the
 * store combines this with the tracker rather than replacing it.
 */
internal object EvictionGuard {

    fun hasWorkInFlight(
        isStreaming: Boolean,
        streamJobActive: Boolean,
        hasAgentWork: Boolean,
        hasQueuedPrompts: Boolean,
    ): Boolean = isStreaming || streamJobActive || hasAgentWork || hasQueuedPrompts
}
