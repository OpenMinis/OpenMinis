package com.openminis.app.auth

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [T-android-oauth-foreground-exchange] Hold a loopback-OAuth token exchange
 * until Minis is back in the foreground.
 *
 * Reported: after authorizing Anthropic in the browser — which then said the
 * tab could be closed — returning to Minis showed no sign-in at all. The
 * authorization had succeeded; the token exchange had not. Logcat on a Pixel 6
 * (Android 17 beta, WireGuard up):
 *
 *     OAuthCallbackServer: GET /callback?code=…&state=…
 *     ClaudeOAuth: Callback received — code length: 48
 *     ClaudeOAuth: Token exchange: POST https://claude.ai/v1/oauth/token
 *     OAuth sign-in failed: Unable to resolve host "claude.ai": No address associated with hostname
 *
 * 10 ms from POST to failure: not a timeout, a refusal. `dumpsys netpolicy` for
 * the app's uid shows `blocked=APP_BACKGROUND, allowed=FOREGROUND|TOP|…`.
 * While the user is in the Custom Tab, Minis is a background app and the OS
 * denies it the network — DNS answers "no address" at once — but the loopback
 * callback server still works, because localhost is never blocked. So the
 * redirect lands, the browser reports success, and the exchange fires into a
 * blocked network. The same fetch from the foreground returns 200.
 *
 * OpenAI appeared to work "after a while" only because its exchange retried
 * for ~3 s ("DNS can briefly fail after Custom Tab closes" — a guess at this
 * cause); a user who took longer to come back failed the same way. Anthropic
 * had no retry and failed every time.
 *
 * The fix is to not attempt the network while it is guaranteed to be refused:
 * after the callback, wait until the process is STARTED again (the user left
 * the browser), then exchange. The code is single-use but lives for minutes, so
 * the wait costs nothing; the managers keep a short retry behind this for the
 * brief lag between foregrounding and the policy lifting.
 *
 * Also publishes the sign-in [phase] so the provider screen can show "waiting
 * for authorization" / "completing sign-in" instead of looking signed-out.
 */
object OAuthForegroundGate {

    /** Where an interactive loopback sign-in currently is. */
    enum class Phase {
        /** Browser opened; waiting for the user to authorize. */
        AWAITING_BROWSER,

        /** Callback received; exchanging the code (possibly waiting to be foreground first). */
        COMPLETING,
    }

    private val _phase = MutableStateFlow<Phase?>(null)
    val phase: StateFlow<Phase?> = _phase.asStateFlow()

    /** Called by the UI when it starts a sign-in. */
    fun begin() { _phase.value = Phase.AWAITING_BROWSER }

    /** Called by the UI when the sign-in ends, however it ends. */
    fun end() { _phase.value = null }

    /**
     * Longest we hold a received code waiting for the user to come back.
     * Beyond this we try anyway and let the failure surface — better an error
     * the user can act on than a sign-in hanging forever.
     */
    internal const val MAX_WAIT_MS = 5 * 60_000L

    /** Foreground as far as network policy cares: at least STARTED. */
    internal fun isForeground(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.STARTED)

    /**
     * Suspend until the app is in the foreground, then return. Call right
     * before the token exchange, after the callback has delivered the code.
     */
    suspend fun awaitForeground(tag: String) {
        _phase.value = Phase.COMPLETING
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val state = withContext(Dispatchers.Main.immediate) { lifecycle.currentState }
        if (isForeground(state)) return

        AppLogger.info(
            tag,
            "[OAuth] callback arrived with the app in the background ($state) — " +
                "holding the token exchange until Minis is foreground (background network is blocked)",
        )
        val started = System.currentTimeMillis()
        val arrived = withTimeoutOrNull(MAX_WAIT_MS) {
            withContext(Dispatchers.Main.immediate) { awaitStarted(lifecycle) }
        }
        val waited = System.currentTimeMillis() - started
        if (arrived == null) {
            AppLogger.warning(tag, "[OAuth] still background after ${waited}ms — attempting the exchange anyway")
        } else {
            AppLogger.info(tag, "[OAuth] foreground after ${waited}ms — exchanging")
        }
    }

    /** Must run on the main thread: Lifecycle observers are main-thread only. */
    private suspend fun awaitStarted(lifecycle: Lifecycle): Unit =
        suspendCancellableCoroutine { cont ->
            if (isForeground(lifecycle.currentState)) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val observer = object : LifecycleEventObserver {
                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    if (isForeground(lifecycle.currentState) && cont.isActive) {
                        lifecycle.removeObserver(this)
                        cont.resume(Unit)
                    }
                }
            }
            lifecycle.addObserver(observer)
            cont.invokeOnCancellation {
                // Removal must also happen on main; post it.
                android.os.Handler(android.os.Looper.getMainLooper()).post { lifecycle.removeObserver(observer) }
            }
        }
}
