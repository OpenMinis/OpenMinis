package com.openminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/**
 * Wrapper around `NavController.navigate` that drops the call when the
 * current `NavBackStackEntry`'s lifecycle is not at `RESUMED`.
 *
 * Android Navigation has a long-standing rough edge: when the user taps
 * one button that pops the stack and *immediately* taps another button
 * (e.g. "back" then "settings") the second `navigate` lands while the
 * source destination is still mid-pop — the new destination ends up on
 * the stack but with no UI rendered (the Composition for the popped
 * screen is gone, the new one never inflates because the ViewTree is
 * confused by two state transitions in one frame). The user sees a blank
 * white screen and the only recovery is to press back again.
 *
 * Compose Multiplatform docs and the Now-In-Android sample both call out
 * the same fix: only allow `navigate` from a destination whose lifecycle
 * is RESUMED (i.e. fully on-screen and stable). Anything else means
 * we're in a transition window — drop the call.
 *
 * Usage:
 *
 *   onSettingsClick = { navController.safeNavigate(Routes.SETTINGS) }
 *
 * Mirror `popBackStack` with the same guard via `safePopBackStack`.
 */
fun NavController.safeNavigate(
    route: String,
    builder: (NavOptionsBuilder.() -> Unit)? = null,
) {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    if (builder != null) navigate(route, builder) else navigate(route)
}

/**
 * Pop back to [route] under the same lifecycle guard as [safePopBackStack].
 *
 * For a screen reachable at more than one depth, "go back to X" is the
 * intent; counting pops is not. See the RESTORE_BROWSE caller, where the same
 * screen sits one level below BACKUP on one path and two on another, and a
 * fixed single pop stranded the user on the intermediate step.
 */
fun NavController.safePopBackStack(route: String, inclusive: Boolean = false): Boolean {
    val state = currentBackStackEntry?.lifecycle?.currentState ?: return false
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    return popBackStack(route, inclusive)
}

fun NavController.safePopBackStack(): Boolean {
    // User-triggered pops (top-bar back button) must not be silently
    // swallowed during transient transitions. RESUMED is too strict —
    // when a ModalBottomSheet / Dialog has the chat NavBackStackEntry
    // demoted to STARTED, the back tap would otherwise no-op with only
    // a ripple animation to show for it. Bug 3 in the MIUI feedback
    // report was exactly this case.
    val state = currentBackStackEntry?.lifecycle?.currentState ?: return false
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    return popBackStack()
}
