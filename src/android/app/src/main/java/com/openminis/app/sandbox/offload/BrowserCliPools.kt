package com.openminis.app.sandbox.offload

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.ui.chat.ChatViewModelStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * [T-android-browser-cli-own-pool] Which tab pool a `minis-browser-use` call
 * drives. Port of iOS `BrowserUseOffloadBridge.pool(for:)`.
 *
 * The CLI used to run every call on `MinisApp.sharedBrowserTabPool`, one pool
 * for the whole process. Shells from different chats run concurrently, so
 * chats scripting the browser (for example two chats on two shopping sites)
 * navigated each other's page, and read text and screenshots from whichever
 * site the other chat had just opened. It also meant a chat's agent
 * (`browser_use`) and its shell (`minis-browser-use`) saw different tabs.
 *
 * Now the call runs on the CALLING chat's pool:
 *  1. The live ChatViewModel of the caller (`MINIS_CHAT_SESSION_ID`, set per
 *     process by the shell). A sub agent's view model has adopted its
 *     parent's pool, so a sub agent lands on the parent's tab set, as its
 *     `browser_use` does.
 *  2. The live view model of the session whose files are mounted
 *     ([ExecutionCoordinator.mountedSessionIdFor]: the parent for a helper
 *     whose own view model is gone).
 *  3. A fallback pool for that session, reused by later calls and created only
 *     while the session still exists. A deleted session is an error, not a
 *     zombie pool.
 *  4. No session at all (a shell that is not a chat's, e.g. the interactive
 *     terminal): the process-wide shared pool, iOS's "__unmounted__" sentinel.
 */
internal object BrowserCliPools {

    private const val TAG = "BrowserCliPools"

    /** Bridge-owned pools for sessions with no live view model. */
    private val fallbackPools = ConcurrentHashMap<String, BrowserTabPool>()

    sealed interface Resolution<out P> {
        /** [via] names the source, for the log line and tests. */
        data class Found<P>(val pool: P, val via: String) : Resolution<P>
        data class SessionGone(val sessionId: String) : Resolution<Nothing>
    }

    /**
     * The resolution order, separated from Android types so it is testable.
     * [liveVm] returns the pool of a live view model for a session id;
     * [fallback] an existing fallback pool; [create] makes (and records) one.
     */
    internal fun <P> choose(
        callerSid: String?,
        mountedSid: String?,
        liveVm: (String) -> P?,
        fallback: (String) -> P?,
        sessionExists: (String) -> Boolean,
        create: (String) -> P,
        shared: () -> P,
    ): Resolution<P> {
        if (callerSid == null) return Resolution.Found(shared(), "shared(no-session)")
        liveVm(callerSid)?.let { return Resolution.Found(it, "vm") }
        val owner = mountedSid ?: callerSid
        if (owner != callerSid) liveVm(owner)?.let { return Resolution.Found(it, "vm(mounted)") }
        fallback(owner)?.let { return Resolution.Found(it, "fallback") }
        if (!sessionExists(owner)) return Resolution.SessionGone(owner)
        return Resolution.Found(create(owner), "fallback(new)")
    }

    fun resolve(app: MinisApp, callerSid: String?): Resolution<BrowserTabPool> {
        val mounted = callerSid?.let { ExecutionCoordinator.mountedSessionIdFor(it) }
        return choose(
            callerSid = callerSid,
            mountedSid = mounted,
            liveVm = { sid -> ChatViewModelStore.existing(sid)?.browserTabPool },
            fallback = { sid -> fallbackPools[sid] },
            sessionExists = { sid ->
                // chatRepositoryOrNull: the lateinit repository throws when
                // onCreate stopped early (safe mode). Unknown means "gone".
                val dao = app.chatRepositoryOrNull?.dao
                dao != null && runCatching { runBlocking { dao.getSession(sid) } != null }.getOrDefault(false)
            },
            create = { sid ->
                fallbackPools.getOrPut(sid) {
                    Log.i(TAG, "fallback browser pool for session ${sid.take(8)} (no live chat view model)")
                    BrowserTabPool(app).also { it.setSession(sid) }
                }
            },
            shared = { app.sharedBrowserTabPool },
        )
    }

    /**
     * Drop a deleted session's fallback pool and its WebViews. Pools owned by a
     * live view model follow that view model's lifecycle and are untouched.
     */
    fun release(sessionId: String) {
        val pool = fallbackPools.remove(sessionId) ?: return
        Handler(Looper.getMainLooper()).post { pool.destroyAllTabs() }
        Log.i(TAG, "released fallback browser pool for session ${sessionId.take(8)}")
    }
}
