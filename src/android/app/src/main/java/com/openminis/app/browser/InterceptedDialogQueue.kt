package com.openminis.app.browser

/**
 * [T-android-js-dialogs-256] Bounded record of JS dialogs the agent browser
 * answered with a default instead of showing. Android port of the
 * `pendingDialogEvents` / `recordInterceptedDialog` / `drainInterceptedDialogReport`
 * trio on iOS `BrowserUseManager` (BrowserUseManager.swift L67-90, L2627-2720).
 *
 * Split out of [BrowserUseManager] rather than inlined there because that class
 * requires a live `WebView` to construct, which a JVM unit test cannot provide —
 * the eviction and drain-once semantics are the parts worth pinning, and this
 * way they are testable without an instrumentation run.
 *
 * Thread-safe: WebChromeClient callbacks arrive on the UI thread while drains
 * happen from the action coroutine, so record/drain genuinely race. iOS gets
 * this for free via @MainActor; here it costs a lock.
 */
class InterceptedDialogQueue(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {

    companion object {
        /**
         * Cap on retained records. A page can call alert() in a loop and this
         * queue only drains when a tool result is produced, so without a bound a
         * buggy (or adversarial) page could grow it without limit. Once full the
         * OLDEST are dropped and the drained report says how many, keeping the
         * most recent dialogs — the ones the agent is likely acting on.
         */
        const val DEFAULT_MAX_EVENTS = 20
    }

    /** One alert()/confirm()/prompt() answered with a default. */
    data class Event(
        val kind: String,
        val message: String,
        val defaultText: String?,
        val pageURL: String?,
        /** The value handed back to the page, rendered for the model. */
        val defaultResponse: String,
    )

    private val events = mutableListOf<Event>()
    private var dropped = 0
    private val lock = Any()

    /** Append, evicting the oldest once [maxEvents] is reached. */
    fun record(
        kind: String,
        message: String,
        defaultText: String?,
        pageURL: String?,
        defaultResponse: String,
    ) {
        synchronized(lock) {
            if (events.size >= maxEvents) {
                events.removeAt(0)
                dropped++
            }
            events.add(Event(kind, message, defaultText, pageURL, defaultResponse))
        }
    }

    /** Test/diagnostic view of the current depth. */
    val size: Int get() = synchronized(lock) { events.size }

    /**
     * Drain into a block of text for the model, or null when empty. Draining
     * clears the queue, so each dialog is reported exactly once.
     *
     * Known limitation (same as iOS): this only runs when a tool result is
     * produced for the tab. If the agent triggers a dialog and then makes no
     * further browser call, the records die with the tab — deliberately not
     * defended against, since the memory is bounded and every interception is
     * already logged at record time.
     */
    fun drainReport(): String? {
        val drainedEvents: List<Event>
        val drainedDropped: Int
        synchronized(lock) {
            if (events.isEmpty()) return null
            drainedEvents = events.toList()
            drainedDropped = dropped
            events.clear()
            dropped = 0
        }

        val lines = drainedEvents.map { e ->
            buildString {
                append("- ${e.kind}(): \"${e.message}\"")
                if (!e.defaultText.isNullOrEmpty()) append(" [default text: \"${e.defaultText}\"]")
                if (e.pageURL != null) append(" on ${e.pageURL}")
                append(" — page received `${e.defaultResponse}`")
            }
        }.toMutableList()
        if (drainedDropped > 0) {
            lines.add(
                "- ($drainedDropped earlier dialog${if (drainedDropped == 1) "" else "s"} " +
                    "dropped; only the most recent $maxEvents are kept)"
            )
        }

        val plural = if (drainedEvents.size == 1) "a dialog" else "${drainedEvents.size} dialogs"
        return "[Browser Dialog Intercepted] The page tried to show $plural, which was " +
            "NOT shown to the user — this is headless automation and cannot wait for " +
            "human input. The page was given the default response shown below and " +
            "continued running.\n" +
            lines.joinToString("\n") + "\n" +
            "If you need a different outcome, use execute_js to drive the page's " +
            "DOM/JS state directly instead of relying on native dialogs (for example " +
            "override window.confirm before triggering the action).\n\n" +
            "[Original tool result below]\n\n"
    }
}
