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
class ConfigRegistry internal constructor() {
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
     * children (`<base>.<id>.<leaf>`). Returns null for unknown paths.
     */
    fun resolveField(path: String): ConfigField? {
        fields[path]?.let { return it }
        val (base, id, leaf) = splitCollectionPath(path) ?: return null
        val coll = collections[base] ?: return null
        // [T-config-path-dotted-id] OpenMinis#390. Match by LEAF, not by the
        // whole path. The collection builds its canonical path from its own
        // spelling of the id (the pre-#390 `~d`-escaped model id, or an alias),
        // which the caller's spelling need not equal — whole-path comparison
        // would turn a correctly split path back into `unknown_path`. Leaves
        // are unique within one child.
        return coll.fields(forId = id).firstOrNull { leafOf(it.path) == leaf }
    }

    /**
     * [T-config-path-dotted-id] OpenMinis#390. The `reason` for an
     * `unknown_path` answer, saying which part was wrong and how to find the
     * right one. The error code stays `unknown_path`; only the text changes.
     * Keep the wording in step with iOS `ConfigRegistry.explainUnknownPath`
     * (cef38f13b) — the two are compared byte for byte by ConfigPathSplitTest.
     */
    fun explainUnknownPath(path: String): String {
        val dot = path.indexOf('.')
        val base = if (dot >= 0) path.substring(0, dot) else path
        val topicKnown = collections[base] != null ||
            fields.values.any { it.access != ConfigAccess.HIDDEN && (it.path == base || it.path.startsWith("$base.")) }
        if (base.isEmpty() || !topicKnown) {
            return "No topic '$base'. Run `minis-config list-topics`."
        }
        val coll = collections[base]
        if (coll == null || dot < 0) {
            return "No registered field at '$path'."
        }
        val rest = path.substring(dot + 1)
        fun leaves(id: String): List<String> =
            coll.fields(forId = id).filter { it.access != ConfigAccess.HIDDEN }.map { leafOf(it.path) }
        // The whole remainder is an entry id (dots allowed): no field given.
        val entryLeaves = leaves(rest)
        if (entryLeaves.isNotEmpty()) {
            return "'$path' names an entry, not a field. Append a field, e.g. $base.$rest.${entryLeaves[0]}. Fields: ${entryLeaves.joinToString(", ")}."
        }
        val split = splitCollectionPath(path)
        if (split != null) {
            val (_, id, leaf) = split
            val idLeaves = leaves(id)
            if (idLeaves.isNotEmpty()) {
                return "Unknown field '$leaf' for $base entry '$id'. Fields: ${idLeaves.joinToString(", ")}."
            }
            return noEntryReason(base, id)
        }
        return noEntryReason(base, rest)
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
         * [T-config-path-dotted-id] OpenMinis#390. Split a collection child
         * path into (base, id, leaf): the topic runs to the FIRST dot, the
         * field starts after the LAST dot, and everything in between is the
         * entry id — dots and slashes included.
         *
         * Before this, the path was split with `limit = 3` into exactly three
         * parts, so an id was cut at its first dot:
         * `models.<uuid>/mimo-v2.6-pro.contextWindow` became id
         * `<uuid>/mimo-v2`, leaf `6-pro.contextWindow`, and every model id with
         * a dot (most of them: glm-5.1, gpt-4.1, …) answered `unknown_path`.
         * The earlier workaround escaped dots as `~d`, which no caller could
         * guess; `get models` prints the raw id.
         *
         * Relies on leaves being a single segment, which every collection
         * honours (guarded by ConfigPathSplitTest). Null when any part would be
         * empty or there are fewer than two dots (`topic.entry` names an entry,
         * not a field). Empty segments are NOT collapsed, so `models..x` stays
         * invalid. Same algorithm as iOS `splitCollectionPath` (cef38f13b).
         */
        fun splitCollectionPath(path: String): Triple<String, String, String>? {
            val first = path.indexOf('.')
            val last = path.lastIndexOf('.')
            if (first < 0 || first >= last) return null
            val base = path.substring(0, first)
            val id = path.substring(first + 1, last)
            val leaf = path.substring(last + 1)
            if (base.isEmpty() || id.isEmpty() || leaf.isEmpty()) return null
            return Triple(base, id, leaf)
        }

        /** The part of a field path after its last dot. */
        fun leafOf(path: String): String = path.substringAfterLast('.')

        internal fun noEntryReason(base: String, id: String): String =
            "No entry '$id' under '$base'. Run `minis-config get $base` and use an entry_id verbatim — ids may contain dots and slashes, no escaping needed: $base.<entry_id>.<field>."

        /**
         * Process-wide singleton. The first caller to invoke [init] wins;
         * subsequent calls are no-ops so idempotent registration is safe.
         */
        @Volatile private var INSTANCE: ConfigRegistry? = null

        fun get(): ConfigRegistry =
            INSTANCE ?: error("ConfigRegistry not initialized; call init() from Application.onCreate")

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
