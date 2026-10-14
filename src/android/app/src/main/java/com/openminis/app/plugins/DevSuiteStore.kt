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
            val raw = p.getString(id, null) ?: continue
            val parts = raw.split('\u0001', limit = 3)
            val status = when (parts.getOrNull(0)) {
                "INSTALLING" -> Status.INSTALLING
                "INSTALLED" -> Status.INSTALLED
                "FAILED" -> Status.FAILED
                else -> Status.NOT_INSTALLED
            }
            out[id] = ComponentState(
                componentId = id,
                status = status,
                progressLine = parts.getOrNull(1).orEmpty(),
                updatedAtMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
            )
        }
        _states.value = out
        return out
    }

    fun update(context: Context, componentId: String, status: Status, progressLine: String = "") {
        val p = prefs(context)
        p.edit()
            .putString(componentId, "$status\u0001$progressLine\u0001${System.currentTimeMillis()}")
            .apply()
        snapshot(context)
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
}
