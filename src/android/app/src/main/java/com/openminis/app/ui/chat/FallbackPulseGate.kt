package com.openminis.app.ui.chat

/**
 * [T-android-fallback-pulse-replay] Should the red "model switched" pulse play?
 *
 * The chat title bar flashes red three times when a model-group fallback
 * swaps the session onto a different model. The signal behind it,
 * `ChatViewModel.fallbackTrigger`, is a monotonic counter that is never reset,
 * and ChatViewModel instances are reused across visits to a session
 * (ChatViewModelStore caches up to 4 normal / 6 child VMs). A session that fell
 * back during an earlier turn therefore presents a non-zero counter the instant
 * its screen is composed again.
 *
 * Compose's `LaunchedEffect(key)` fires on first composition as well as on
 * change, so a "has it ever been non-zero" test is not enough — it replays the
 * pulse on every re-entry into a session that had already recovered onto a
 * working model, which users read as "it is switching models again" even though
 * the persisted binding restored the resolved model correctly and nothing was
 * happening. iOS avoids this because `.onChange(of:)` ignores the initial
 * value; [shouldPulse] reproduces that semantic explicitly.
 *
 * @param current the counter's value now.
 * @param baseline its value when this screen last began observing it.
 */
internal fun shouldPulse(current: Int, baseline: Int): Boolean = current > baseline
