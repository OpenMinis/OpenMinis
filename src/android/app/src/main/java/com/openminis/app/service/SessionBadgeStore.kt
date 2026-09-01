package com.openminis.app.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-session-paused-badge] Per-session badge-state queue shown in the
 * session-list cell's icon corner.
 *
 * Architecture mirror of the iOS design (spec /tmp/fix_session_paused_badge.md):
 * every session holds an ordered queue of [SessionBadgeState] values. The head
 * of the queue is what the cell renders; pushing a new state (e.g. [PAUSED]
 * when a background interruption is detected) prepends to the queue so it
 * shows first. When the user enters the session the [PAUSED] state is removed
 * — any underlying state (future: [ICLOUD_SYNCING]) re-surfaces. The store is
 * persisted to SharedPreferences so a process restart preserves badges.
 *
 * Only [PAUSED] has a wired producer today; [ICLOUD_SYNCING] is reserved for
 * the upcoming Android iCloud-equivalent sync surface and is plumbed through
 * the same data path so adding it later is a one-line emit.
 */
object SessionBadgeStore {
    private const val TAG = "SessionBadgeStore"
    private const val PREFS = "session_badge_store"

    /**
     * v1 format: `"id1=PAUSED,ICLOUD_SYNCING;id2=PAUSED"` — states only, no
     * timestamps. Still READ (once, at init) so an upgrading install keeps its
     * badges, never WRITTEN again.
     */
    private const val KEY = "badge_state_by_session"

    /**
     * [T-android-group-pause-badge-restamp] v2 format:
     * `"id1=PAUSED:1723449600000,ICLOUD_SYNCING:0;id2=PAUSED:1723449600000"`
     * — each state carries the epoch-millis at which the session ENTERED it,
     * `0` meaning "unknown age" (see below).
     *
     * Why a NEW key instead of extending the old one in place: the two formats
     * then coexist without either parser having to guess which shape it is
     * looking at, and an accidental downgrade (user rolls back to an older
     * build) still finds an intact v1 blob rather than a string full of
     * `PAUSED:172…` tokens that `valueOf` would drop, silently wiping every
     * badge. The v1 key is read once at init as a fallback and left untouched
     * on disk; v2 becomes authoritative the moment it exists.
     *
     * Migration semantics: a badge restored from v1 has UNKNOWN entry time, so
     * it is imported with NO timestamp and counts as STALE for the group-card
     * freshness window. That matches iOS ("A state with no recorded timestamp
     * — legacy persisted queues predate stamping — counts as stale: silencing
     * unknown-age leftovers is the point of the window"). Session ROWS still
     * render it, unfiltered, exactly as before — nothing visible is lost.
     */
    private const val KEY_V2 = "badge_state_by_session_v2"

    /** Sentinel written for "state present, entry time unknown" (legacy import). */
    private const val NO_STAMP = 0L

    enum class SessionBadgeState {
        /** Background-interrupted while a stream was active; shown until the user re-opens the session. */
        PAUSED,
        /** Reserved for the upcoming sync-status badge (mirroring iOS iCloud). Not produced yet. */
        ICLOUD_SYNCING,
    }

    @Volatile private var prefs: SharedPreferences? = null

    // sessionId -> ordered queue (head = currently displayed).
    private val _byId = MutableStateFlow<Map<String, List<SessionBadgeState>>>(emptyMap())
    val byId: StateFlow<Map<String, List<SessionBadgeState>>> = _byId.asStateFlow()

    /**
     * sessionId -> state -> epoch-millis at which the session LAST ENTERED that
     * state. Mirrors iOS `badgeTimestamps`. Mutated in lockstep with [_byId]
     * under the same lock; UI refresh rides [byId] plus [revision] (the latter
     * covering stamp-only changes). A badge that crosses the freshness window
     * purely by TIME ELAPSING is picked up on the next ambient list
     * recomposition rather than by a dedicated timer — same as iOS.
     *
     * A state present in [_byId] but ABSENT here = unknown age = stale (see
     * [KEY_V2]).
     */
    @Volatile
    private var stamps: Map<String, Map<SessionBadgeState, Long>> = emptyMap()

    /**
     * Bumped whenever a stamp is written or dropped. Exposed through [revision]
     * so a re-stamp that leaves the queue map `equals`-identical still reaches
     * the UI: `MutableStateFlow` conflates equal values, so re-stamping a badge
     * already at the head of its queue would otherwise emit nothing — and that
     * case matters, because a badge that had aged PAST the freshness window and
     * is then genuinely re-interrupted must flip its group card's `anyPaused`
     * back on.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val (restored, restoredStamps) = loadFromDisk(p)
        synchronized(this) {
            _byId.value = restored
            stamps = restoredStamps
        }
        Log.d(TAG, "init: restored ${restored.size} sessions with badges, ${restoredStamps.size} stamped")
    }

    /** Head of the queue, or null when empty. Render this in the cell. */
    fun headFor(sessionId: String): SessionBadgeState? =
        _byId.value[sessionId]?.firstOrNull()

    /**
     * Push a state to the head of [sessionId]'s queue.
     *
     * [restamp] distinguishes the two ways a state gets pushed, which the
     * caller knows and this store cannot infer (mirrors iOS
     * `pushFront(_:for:restamp:)`):
     *
     *  - `true` (default) — a REAL new entry into the state (an actual
     *    interruption just happened). The freshness window keys off the last
     *    genuine entry, so this OVERWRITES any existing stamp.
     *  - `false` — the state was merely RE-DETECTED for a session already in
     *    it (loading an old chat whose DB tail still looks interrupted, a
     *    cold-start scan, a background pre-warm). Keep the existing stamp;
     *    only stamp now when none survives.
     *
     * Why it matters: the group card only passes states entered within the
     * last 24h. If every push re-stamped, merely opening a chat interrupted
     * days ago would reset its stamp to now and a long-stale pause would flag
     * its whole group forever.
     *
     * NOTE the deliberate absence of the old `if (q.firstOrNull() == state)
     * return` early-exit: a genuine RE-interruption of a session whose PAUSED
     * badge is already at the head must still refresh the timestamp. The queue
     * shape is unchanged in that case, but the stamp is the whole point.
     */
    fun push(sessionId: String, state: SessionBadgeState, restamp: Boolean = true) {
        mutate { current ->
            val q = current[sessionId].orEmpty()
            val existing = stamps[sessionId]?.get(state)
            if (restamp || existing == null) {
                putStamp(sessionId, state, System.currentTimeMillis())
            }
            if (q.firstOrNull() == state) {
                // Queue unchanged — but a stamp may have been written above, so
                // return a COPY rather than the identity, which is what makes
                // `mutate` re-persist. `byId` itself won't re-emit (an equal
                // map is conflated); [revision], bumped by putStamp, is what
                // carries the change to the UI in that case.
                return@mutate if (restamp || existing == null) HashMap(current) else current
            }
            // De-dupe — if the same state is already elsewhere in the queue,
            // pull it forward instead of stacking duplicates.
            val deduped = q.filter { it != state }
            current + (sessionId to (listOf(state) + deduped))
        }
    }

    /** Remove [state] from [sessionId]'s queue; drop the key when empty. */
    fun remove(sessionId: String, state: SessionBadgeState) {
        mutate { current ->
            val q = current[sessionId] ?: return@mutate current
            val updated = q.filter { it != state }
            dropStamp(sessionId, state)
            if (updated.isEmpty()) current - sessionId else current + (sessionId to updated)
        }
    }

    /** Drop everything for [sessionId] (e.g. session deletion). */
    fun clear(sessionId: String) {
        mutate { current ->
            if (!current.containsKey(sessionId)) return@mutate current
            if (sessionId in stamps) {
                stamps = stamps - sessionId
                _revision.value += 1
            }
            current - sessionId
        }
    }

    /**
     * [T-android-group-pause-badge-restamp] Session ids carrying a non-unread
     * corner badge ENTERED within [windowMillis] of now. This is the
     * GROUP-CARD passthrough filter only — session rows render their badge
     * unfiltered regardless of age.
     *
     * Mirrors iOS `freshCornerBadgeSessionIds(within:)`. iOS skips its
     * `.unread` case here because that renders as a separate top-trailing dot;
     * Android's enum has no UNREAD member (PAUSED / ICLOUD_SYNCING are both
     * corner badges), so every queued state participates and no filter is
     * needed — if UNREAD is ever added it must be excluded here.
     *
     * A state with no recorded stamp (imported from the v1 persist format)
     * counts as STALE: silencing unknown-age leftovers is the point of the
     * window. The cutoff is computed ONCE for the whole pass, and the result
     * doubles as a recomposition key for the grouping pass.
     */
    fun freshCornerBadgeSessionIds(windowMillis: Long): Set<String> {
        val cutoff = System.currentTimeMillis() - windowMillis
        val states = _byId.value
        val ts = stamps
        if (states.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for ((sid, queue) in states) {
            val perState = ts[sid] ?: continue
            // `perState[st] == null` → unknown age → stale, never fresh.
            if (queue.any { st -> (perState[st] ?: Long.MIN_VALUE) >= cutoff }) {
                out.add(sid)
            }
        }
        return out
    }

    /**
     * [T-android-session-paused-badge-hardkill] Reconcile [PAUSED] badges against
     * the authoritative set of currently-interrupted sessions (derived from the
     * persisted message tail by the repository). Run once at launch so a session
     * hard-killed (force-quit / process death) while running — which never hit
     * the lifecycle-callback push path — still shows the badge after restart, and
     * a session that is NO longer interrupted gets its stale badge cleared.
     *
     * Only touches [PAUSED]; other queued states are left intact. Mirrors iOS
     * SessionBadgeStore.reconcileInterruptedSessions.
     */
    fun reconcileInterruptedSessions(interruptedIds: Set<String>) {
        mutate { current ->
            val next = current.toMutableMap()
            // Add PAUSED for interrupted sessions that don't have it yet.
            for (sid in interruptedIds) {
                val q = next[sid].orEmpty()
                if (q.firstOrNull() != SessionBadgeState.PAUSED && !q.contains(SessionBadgeState.PAUSED)) {
                    next[sid] = listOf(SessionBadgeState.PAUSED) + q
                    // Reconcile RESTORES a marker after a hard kill; it is not
                    // a new entry into the paused state, so an existing stamp
                    // is PRESERVED. Only stamp now when none survives (the
                    // original push never happened/persisted) — the best
                    // available approximation of the entry time. Mirrors iOS
                    // reconcileInterruptedSessions.
                    if (stamps[sid]?.get(SessionBadgeState.PAUSED) == null) {
                        putStamp(sid, SessionBadgeState.PAUSED, System.currentTimeMillis())
                    }
                }
            }
            // Remove PAUSED from sessions no longer interrupted.
            val iterIds = next.keys.toList()
            for (sid in iterIds) {
                val q = next[sid] ?: continue
                if (q.contains(SessionBadgeState.PAUSED) && !interruptedIds.contains(sid)) {
                    val updated = q.filter { it != SessionBadgeState.PAUSED }
                    dropStamp(sid, SessionBadgeState.PAUSED)
                    if (updated.isEmpty()) next.remove(sid) else next[sid] = updated
                }
            }
            next
        }
        Log.d(TAG, "reconcileInterruptedSessions: ${interruptedIds.size} interrupted")
    }

    // ── internals ────────────────────────────────────────────────────────

    /** Write one stamp. Callers hold the [mutate] lock. */
    private fun putStamp(sessionId: String, state: SessionBadgeState, at: Long) {
        val perState = stamps[sessionId].orEmpty() + (state to at)
        stamps = stamps + (sessionId to perState)
        _revision.value += 1
    }

    /** Drop one stamp, pruning the session key when it empties. Lock held. */
    private fun dropStamp(sessionId: String, state: SessionBadgeState) {
        val perState = stamps[sessionId] ?: return
        if (state !in perState) return
        val updated = perState - state
        stamps = if (updated.isEmpty()) stamps - sessionId else stamps + (sessionId to updated)
        _revision.value += 1
    }

    private fun mutate(block: (Map<String, List<SessionBadgeState>>) -> Map<String, List<SessionBadgeState>>) {
        synchronized(this) {
            val prev = _byId.value
            val next = block(prev)
            // Identity means "nothing changed at all" — including stamps, which
            // `push` signals by handing back a copy when it wrote one under an
            // otherwise-unchanged queue.
            if (next === prev) return
            _byId.value = next
            prefs?.let { persistToDisk(it, next) }
        }
    }

    private fun persistToDisk(p: SharedPreferences, map: Map<String, List<SessionBadgeState>>) {
        // v2 encoding: "id1=PAUSED:1723449600000,ICLOUD_SYNCING:0;id2=PAUSED:0".
        // Plain text is still fine — SharedPreferences atomicity covers the
        // whole string; entries are small (a few hundred sessions × short enum
        // name + 13-digit millis) so a JSON dependency would be overkill.
        // `:0` encodes "no stamp" (legacy import) and decodes back to absent.
        val ts = stamps
        val encoded = map.entries.joinToString(";") { (id, q) ->
            val perState = ts[id]
            "$id=" + q.joinToString(",") { "${it.name}:${perState?.get(it) ?: NO_STAMP}" }
        }
        runCatching { p.edit().putString(KEY_V2, encoded).apply() }
            .onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    private fun loadFromDisk(
        p: SharedPreferences,
    ): Pair<Map<String, List<SessionBadgeState>>, Map<String, Map<SessionBadgeState, Long>>> {
        val rawV2 = runCatching { p.getString(KEY_V2, null) }.getOrNull()
        if (!rawV2.isNullOrBlank()) return decodeV2(rawV2)
        // No v2 blob: either a fresh install (v1 also absent → empty) or an
        // upgrade from a build that only ever wrote v1. Import the v1 queues so
        // no badge is lost; they carry NO stamps, i.e. unknown age → stale for
        // the group-card window, which is the documented choice.
        val rawV1 = runCatching { p.getString(KEY, null) }.getOrNull()
        if (rawV1.isNullOrBlank()) return emptyMap<String, List<SessionBadgeState>>() to emptyMap()
        val migrated = decodeV1(rawV1)
        Log.d(TAG, "loadFromDisk: migrated ${migrated.size} sessions from v1 (unstamped → stale)")
        return migrated to emptyMap()
    }

    private fun decodeV2(
        raw: String,
    ): Pair<Map<String, List<SessionBadgeState>>, Map<String, Map<SessionBadgeState, Long>>> {
        val queues = LinkedHashMap<String, List<SessionBadgeState>>()
        val ts = LinkedHashMap<String, Map<SessionBadgeState, Long>>()
        for (entry in raw.split(';')) {
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.lastIndex) continue
            val id = entry.substring(0, eq)
            val q = mutableListOf<SessionBadgeState>()
            val perState = LinkedHashMap<SessionBadgeState, Long>()
            for (token in entry.substring(eq + 1).split(',')) {
                // "NAME:millis"; a bare "NAME" (defensive — a v1 string that
                // somehow landed under the v2 key) decodes as unstamped.
                val colon = token.lastIndexOf(':')
                val name = if (colon > 0) token.substring(0, colon) else token
                val state = runCatching { SessionBadgeState.valueOf(name) }.getOrNull() ?: continue
                q.add(state)
                val at = if (colon > 0) token.substring(colon + 1).toLongOrNull() ?: NO_STAMP else NO_STAMP
                if (at != NO_STAMP) perState[state] = at
            }
            if (q.isEmpty()) continue
            queues[id] = q
            if (perState.isNotEmpty()) ts[id] = perState
        }
        return queues to ts
    }

    private fun decodeV1(raw: String): Map<String, List<SessionBadgeState>> =
        raw.split(';').mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0 || eq == entry.lastIndex) return@mapNotNull null
            val id = entry.substring(0, eq)
            val q = entry.substring(eq + 1)
                .split(',')
                .mapNotNull { name -> runCatching { SessionBadgeState.valueOf(name) }.getOrNull() }
            if (q.isEmpty()) null else id to q
        }.toMap()
}
