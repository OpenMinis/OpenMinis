package com.openminis.app.plugins

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-dev-suite] Install state for dev-suite components.
 *
 * SharedPreferences rather than Room: the state is a flat per-component
 * marker (installed / installing / failed) with no relational shape, and
 * avoiding a schema migration keeps the change low-risk. The guest itself
 * is the source of truth for "is it really ready" — [DevSuiteInstaller]
 * re-probes checkCommand on demand; this store only caches the last known
 * outcome so the UI doesn't shell out on every recompose.
 */
object DevSuiteStore {
    private const val PREFS = "dev_suite_state"

    /** Per-component last outcome. */
    enum class Status { NOT_INSTALLED, INSTALLING, INSTALLED, FAILED }

    data class ComponentState(
        val componentId: String,
        val status: Status = Status.NOT_INSTALLED,
        /** Last progress line from the installer (for the UI card). */
        val progressLine: String = "",
        val updatedAtMs: Long = 0L,
    )

    private val _states = MutableStateFlow<Map<String, ComponentState>>(emptyMap())
    val states: StateFlow<Map<String, ComponentState>> = _states.asStateFlow()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(context: Context): Map<String, ComponentState> {
        val p = prefs(context)
        val out = mutableMapOf<String, ComponentState>()
        for (id in p.all.keys) {
            out[id] = decodeComponentState(id, p.getString(id, null)) ?: continue
        }
        _states.value = out
        return out
    }

    fun update(context: Context, componentId: String, status: Status, progressLine: String = "") {
        val previous = _states.value[componentId]
        val next = ComponentState(
            componentId = componentId,
            status = status,
            progressLine = progressLine,
            updatedAtMs = System.currentTimeMillis(),
        )
        // [T-dev-suite-prefs-storm] Persist ONLY status transitions. The
        // installer feeds every apt/pip output line through here — hundreds
        // of full-XML SharedPreferences rewrites per install, and each
        // apply() queues a disk write that QueuedWork.waitToFinish() can
        // later force the MAIN thread to drain (observed as a multi-second
        // main-thread stall during installs that deferred the foreground
        // service's createService dispatch past its deadline — the
        // install-crash amplifier). Progress lines are UI-only state: they
        // flow through the in-memory StateFlow and never touch disk. Crash
        // recovery (recoverInterrupted) only needs the status marker, and
        // it overwrites the line anyway.
        if (shouldPersistComponentState(previous?.status, status)) {
            prefs(context).edit()
                .putString(componentId, encodeComponentState(status, progressLine, next.updatedAtMs))
                .apply()
        }
        _states.value = _states.value + (componentId to next)
    }

    /**
     * Crash recovery: a component stuck in INSTALLING means the app process
     * died mid-install. Mark it FAILED so the UI offers a retry instead of
     * pretending an install is still running (taixu's recover() equivalent,
     * scoped to the marker rather than a full snapshot).
     */
    fun recoverInterrupted(context: Context): List<String> {
        val current = snapshot(context)
        val interrupted = current.values
            .filter { it.status == Status.INSTALLING }
            .map { it.componentId }
        interrupted.forEach { update(context, it, Status.FAILED, "安装被中断") }
        return interrupted
    }

    /**
     * [T-dev-suite-prefs-storm] True when this update must hit disk: ONLY
     * status transitions. Progress-line refreshes (the installer feeds every
     * apt/pip output line through [update]) stay in memory — see the block
     * comment in [update]. Pure so the throttle contract is unit-testable.
     */
    internal fun shouldPersistComponentState(previous: Status?, next: Status): Boolean =
        previous != next

    /** Encodes one component marker for SharedPreferences. Pure (testable). */
    internal fun encodeComponentState(status: Status, progressLine: String, updatedAtMs: Long): String =
        "$status\u0001$progressLine\u0001$updatedAtMs"

    /** Decodes one component marker; null for absent/corrupt rows. Pure (testable). */
    internal fun decodeComponentState(componentId: String, raw: String?): ComponentState? {
        if (raw == null) return null
        val parts = raw.split('\u0001', limit = 3)
        val status = when (parts.getOrNull(0)) {
            "INSTALLING" -> Status.INSTALLING
            "INSTALLED" -> Status.INSTALLED
            "FAILED" -> Status.FAILED
            else -> Status.NOT_INSTALLED
        }
        return ComponentState(
            componentId = componentId,
            status = status,
            progressLine = parts.getOrNull(1).orEmpty(),
            updatedAtMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
        )
    }
}
