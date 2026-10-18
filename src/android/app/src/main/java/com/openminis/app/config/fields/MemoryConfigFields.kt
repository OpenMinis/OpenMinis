package com.openminis.app.config.fields

import android.content.Context
import com.openminis.app.config.ConfigRegistry

/**
 * [T-android-memory-enabled-minisconfig] `memory.enabled` — the global default
 * for the persistent memory feature.
 *
 * Mirrors Settings → Memory (MemoryGlobalPrefs, `memory.global.enabled`,
 * default true) and applies to NEW sessions only: already-open sessions keep
 * their own toggle (SessionMemorySheet / `/memory`). Registering the same
 * prefs+key keeps the CLI and the switch from disagreeing.
 *
 * Extracted from ConfigBuiltins, which is on the architecture-policy baseline
 * and may only shrink.
 */
internal fun registerMemoryFields(r: ConfigRegistry, context: Context) {
    val prefs = context.getSharedPreferences("minis_memory_prefs", Context.MODE_PRIVATE)
    r.register(
        PrefsBoolField(
            path = "memory.enabled",
            displayName = "Memory enabled (global default)",
            description = "Default for whether NEW chat sessions start with memory on. When on, a new session auto-injects GLOBAL.md + recent daily logs into the system prompt and exposes the memory_get / memory_write tools. When off, new sessions get neither. Already-open sessions keep their own per-session setting (toggle in the session memory sheet) and are not changed by this.",
            prefs = prefs,
            key = "memory.global.enabled",
            defaultValue = true,
        ),
    )
}
