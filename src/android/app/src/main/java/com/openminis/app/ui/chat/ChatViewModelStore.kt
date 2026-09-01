package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Process-level cache of ChatViewModels keyed by sessionId. Mirrors iOS
 * `ViewModelCache` — a session's agent loop keeps running even if the user
 * leaves the chat screen, and the row in the sessions list shows a spinning
 * indicator while streaming is in flight.
 *
 * Without this, scoping the ChatViewModel to a NavBackStackEntry means
 * `popBackStack()` would cancel `viewModelScope` and kill the streaming job.
 */
object ChatViewModelStore {

    private const val TAG = "ChatVMStore"

    /**
     * One ViewModelStore per canonical sessionId. Each store contains at most
     * one ChatViewModel (the one created by our factory). When we want to drop
     * a session's VM, we call `clear()` on its store which triggers
     * `onCleared`.
     */
    private val stores = mutableMapOf<String, ViewModelStore>()

    /**
     * Draft → canonical mapping. When a draft ("__new__...") session is
     * persisted, we add `draftKey -> realId` here so lookups via the old key
     * (from a ChatScreen whose `sessionId` parameter is still the draft)
     * continue to hit the same live store.
     */
    private val aliases = mutableMapOf<String, String>()

    /**
     * [T-android-split-draft-highlight] Bumped every time [aliases] changes.
     *
     * [aliases] is a plain map, so writing it schedules no recomposition — and
     * the two-pane list resolves its highlight THROUGH that map. When a draft
     * was promoted on first send the pane key deliberately stayed
     * `__new__<uuid>` (see [rename]: aliasing rather than re-keying is what
     * keeps the streaming ViewModel alive), so the highlight went on resolving
     * to the draft id, matched no persisted row, and the running session sat
     * unhighlighted in the list even after it had a title and a group.
     *
     * Exposing the generation as observable state gives Compose something to
     * subscribe to: readers key on it, the write invalidates them, and the
     * lookup re-runs against the now-populated alias.
     */
    private val aliasGeneration = androidx.compose.runtime.mutableIntStateOf(0)

    private fun resolveKey(sessionId: String): String =
        aliases[sessionId] ?: sessionId

    /**
     * [T-android-tablet-split] Public view of [resolveKey]: the PERSISTED id a
     * (possibly draft) session id now stands for.
     *
     * The two-pane list highlights whichever session the detail pane is
     * showing. When the detail holds a draft, the pane's key stays
     * `__new__<uuid>` even after the first send promotes it — [rename] aliases
     * the key rather than renaming it, precisely so the running screen and its
     * ViewModel are not disturbed mid-stream. Without this lookup the list
     * would go on highlighting nothing after the draft became a real row,
     * because no persisted row ever matches a `__new__` id.
     *
     * Returns the input unchanged when there is no alias, so a plain persisted
     * id and an un-promoted draft both behave sensibly.
     */
    @Synchronized
    fun resolvePersistedId(sessionId: String): String = resolveKey(sessionId)

    /**
     * [T-android-split-draft-highlight] Compose-aware [resolvePersistedId].
     *
     * Reading [aliasGeneration] inside a composition subscribes the caller to
     * alias changes, so a draft that gets promoted mid-stream re-resolves to
     * its real id and the list highlight follows it. Call this from
     * composables; [resolvePersistedId] remains for non-Compose callers.
     */
    @androidx.compose.runtime.Composable
    fun rememberPersistedId(sessionId: String): String {
        val generation = aliasGeneration.intValue
        return androidx.compose.runtime.remember(sessionId, generation) {
            resolveKey(sessionId)
        }
    }

    @Synchronized
    fun ownerFor(sessionId: String): ViewModelStoreOwner {
        val key = resolveKey(sessionId)
        val store = stores.getOrPut(key) {
            Log.d(TAG, "allocate store for $key (total=${stores.size + 1})")
            ViewModelStore()
        }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /**
     * Drop the cached VM for this session (cancels `viewModelScope`, triggers
     * `ChatViewModel.onCleared`). Call when the session is deleted. Also
     * clears any draft alias pointing at this canonical id.
     */
    @Synchronized
    fun release(sessionId: String) {
        val key = resolveKey(sessionId)
        aliases.entries.removeAll { it.value == key }
        aliasGeneration.intValue++
        stores.remove(key)?.let {
            it.clear()
            Log.d(TAG, "release store for $key (remaining=${stores.size})")
        }
    }

    /**
     * Mark `fromSessionId` (a draft key) as an alias for `toSessionId` (the
     * real, persisted id). The live store stays under the real id; future
     * `ownerFor(draftKey)` lookups resolve to the same store so a ChatScreen
     * still rendering with the draft route continues to see the running VM.
     */
    /**
     * T311: id of the chat the user has on screen right now. Set by
     * `ChatScreen`'s lifecycle hook on enter, cleared on dispose.
     * `minis-config session.*` reads this so reads/writes target the
     * "current session" the same way iOS `AIChatViewModel.activeSessionId`
     * does. `null` = no chat is foregrounded → reads return empty / writes
     * throw `No active session`. Resolves through `aliases` so a draft id
     * still maps to the persisted row.
     */
    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        get() = activeSessionIdInternal?.let { resolveKey(it) }

    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdInternal = sessionId
    }

    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val store = stores.remove(fromSessionId)
        if (store != null) {
            stores[toSessionId] = store
        }
        aliases[fromSessionId] = toSessionId
        // Invalidate anything resolving through the alias map — see
        // [aliasGeneration].
        aliasGeneration.intValue++
        Log.d(TAG, "rename store $fromSessionId -> $toSessionId (alias kept)")
    }

    /**
     * One-shot stash for the "Move to…" capsule flow. The source session
     * writes (inputText + attachments) here, navigates to the target,
     * and the target's ChatScreen drains it via [consumePendingTransfer].
     * Mirrors iOS `ViewModelCache.pendingTransfer`. Volatile + simple
     * read/write — only ever touched from the main thread.
     */
    data class PendingTransfer(
        val inputText: String,
        val attachments: List<InputAttachment>,
        /**
         * [T-android-moveto-stash-binding] Session this content was moved TO.
         * Only that session may drain the stash. Previously absent, so
         * whichever ChatScreen composed first ate the content — if the
         * navigation to the target didn't land (or the user backed out and
         * opened something else), the moved text/attachments surfaced in an
         * unrelated session. Mirrors iOS 6c3093c8 (GH OpenMinis#120).
         */
        val targetId: String,
        /** Wall-clock stash time; drives the [STASH_TTL_MS] staleness drop. */
        val stashedAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * [T-android-moveto-stash-binding] A stash older than this is considered
     * abandoned and dropped rather than injected. Without it an unclaimed
     * stash sat forever and could ambush a session opened much later.
     */
    private const val STASH_TTL_MS = 300_000L

    @Volatile
    private var pendingTransfer: PendingTransfer? = null

    fun stashPendingTransfer(transfer: PendingTransfer) {
        pendingTransfer = transfer
        Log.d(
            TAG,
            "stashPendingTransfer: target=${transfer.targetId} " +
                "text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
    }

    /**
     * Drain the pending-transfer slot exactly once, and only for the session
     * it was addressed to.
     *
     * [sessionId] is the draining screen's session. A mismatch leaves the
     * stash in place so the real target can still claim it when it opens.
     * An expired stash is dropped outright.
     */
    fun consumePendingTransfer(sessionId: String): PendingTransfer? {
        val t = pendingTransfer ?: return null
        if (System.currentTimeMillis() - t.stashedAtMs > STASH_TTL_MS) {
            pendingTransfer = null
            Log.d(TAG, "consumePendingTransfer: dropping stale stash (target=${t.targetId})")
            return null
        }
        // Compare through the draft→canonical alias map, the same way ownerFor /
        // release / activeSessionId do. Today MoveToSessionSheet only offers
        // PERSISTED sessions and rename() only fires for drafts, so raw ids
        // would already match — but resolving makes this correct by
        // construction instead of relying on that invariant, so a future
        // "move into a new chat" target can't strand the transfer.
        if (resolveKey(t.targetId) != resolveKey(sessionId)) {
            // Not ours — leave it for the intended target.
            Log.d(
                TAG,
                "consumePendingTransfer: session=$sessionId is not target=${t.targetId}, leaving stash",
            )
            return null
        }
        pendingTransfer = null
        Log.d(
            TAG,
            "consumePendingTransfer: target=${t.targetId} " +
                "text=${t.inputText.length}ch attachments=${t.attachments.size}",
        )
        return t
    }
}
