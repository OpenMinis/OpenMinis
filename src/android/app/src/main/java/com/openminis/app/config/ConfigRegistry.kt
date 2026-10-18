package com.openminis.app.config

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for every configurable setting in the app.
 *
 * Add a new setting in three steps:
 *   1. Pick a dot-path id (`appearance.theme`, `browser.uaProfile`, …).
 *   2. Construct a [ConfigField] (or a [ConfigCollection] for dynamic
 *      children) and register it in `ConfigBuiltins`.
 *   3. Done — the offload bridge, confirmation gate, audit log, revert
 *      flow, and topic-help output all derive from this registry.
 *
 * Mirrors iOS `ConfigRegistry`. The Android side is plain mutable maps
 * (no actor isolation needed — handler threads are the writers and the
 * registry is initialized once at boot before any reads).
 */
class ConfigRegistry private constructor() {
    private val fields = LinkedHashMap<String, ConfigField>()
    private val collections = LinkedHashMap<String, ConfigCollection>()
    private val initialized = AtomicBoolean(false)

    fun register(field: ConfigField) {
        fields[field.path] = field
    }

    fun register(collection: ConfigCollection) {
        collections[collection.basePath] = collection
    }

    /**
     * Look up a field by path. Handles both flat fields and collection
     * children (`<base>.<id>.<sub>`). Returns null for unknown paths.
     */
    fun resolveField(path: String): ConfigField? {
        fields[path]?.let { return it }
        // Collection child lookup: split into [base, id, leaf]. The leaf
        // may itself contain dots (e.g. `models.<uuid>.modality.video`),
        // so cap maxSplits at 2.
        val segments = path.split('.', limit = 3)
        if (segments.size != 3) return null
        val coll = collections[segments[0]] ?: return null
        return coll.fields(forId = segments[1]).firstOrNull { it.path == path }
    }

    fun collection(basePath: String): ConfigCollection? = collections[basePath]

    /** All registered top-level field paths (excluding hidden), sorted. */
    fun allVisibleFieldPaths(): List<String> =
        fields.values
            .filter { it.access != ConfigAccess.HIDDEN }
            .map { it.path }
            .sorted()

    /** Topic names = unique first segments of every visible path / collection base. Sorted. */
    fun topics(): List<String> {
        val set = LinkedHashSet<String>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            val head = f.path.substringBefore('.', missingDelimiterValue = "")
            if (head.isNotEmpty()) set.add(head)
        }
        for (c in collections.values) set.add(c.basePath)
        return set.sorted()
    }

    /**
     * All visible fields whose path equals `<topic>` (the bare topic
     * name — e.g. an aggregate `providers` summary) or starts with
     * `<topic>.`. When [topic] matches a registered collection, a
     * representative child's fields (using the first child id) are
     * also included so `topic-help <collection>` surfaces the per-
     * child schema instead of an empty list. Mirrors iOS.
     */
    fun fields(topic: String): List<ConfigField> {
        val out = ArrayList<ConfigField>()
        for (f in fields.values) {
            if (f.access == ConfigAccess.HIDDEN) continue
            if (f.path == topic || f.path.startsWith("$topic.")) out.add(f)
        }
        val coll = collections[topic]
        if (coll != null) {
            val firstId = coll.childIds().firstOrNull()
            if (firstId != null) {
                out.addAll(coll.fields(forId = firstId).filter { it.access != ConfigAccess.HIDDEN })
            }
        }
        return out.sortedBy { it.path }
    }

    companion object {
        /**
         * Process-wide singleton. The first caller to invoke [init] wins;
         * subsequent calls are no-ops so idempotent registration is safe.
         */
        @Volatile private var INSTANCE: ConfigRegistry? = null

        fun get(): ConfigRegistry =
            INSTANCE ?: error("ConfigRegistry not initialized; call init() from Application.onCreate")

        /**
         * A fresh, isolated registry for tests that register their own fields.
         *
         * Such tests must not use [get]: the singleton is process-wide, so a
         * test that registers into it leaks its fields into every later test in
         * the same JVM.
         */
        internal fun isolated(): ConfigRegistry = ConfigRegistry()

        fun init(
            context: Context,
            providerRepository: ProviderRepository,
            envVarRepository: EnvVarRepository,
            chatRepository: ChatRepository,
        ): ConfigRegistry {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val r = ConfigRegistry()
                if (r.initialized.compareAndSet(false, true)) {
                    ConfigBuiltins.registerInto(
                        r, context, providerRepository, envVarRepository, chatRepository,
                    )
                }
                INSTANCE = r
                return r
            }
        }
    }
}
