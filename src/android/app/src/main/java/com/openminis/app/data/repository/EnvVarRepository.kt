package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages environment variables with encrypted value storage.
 * Metadata (key names, IDs) stored in JSON file.
 * Values stored in EncryptedSharedPreferences (AES256-GCM).
 * Mirrors iOS EnvVarStore.
 */
class EnvVarRepository(private val context: Context) {

    companion object {
        private const val TAG = "EnvVarRepository"
        private const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * Optional human-readable description of what this variable is for.
         * Empty when omitted. Stored in the JSON metadata file (not secret),
         * mirrors iOS EnvVarEntry.note.
         */
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: self-healing wrapper.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
    }

    private val metadataFile: File
        get() = File(context.filesDir, METADATA_FILE)

    private val warmupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    @Volatile
    private var loaded = false

    private val loadLock = Any()

    init {
        // [T-android-startup-repo-io] `loadMetadata()` used to run INLINE in this
        // constructor while MinisApp.onCreate() was still on the main thread — the
        // same missed spot #753 closed for ProviderRepository. (The
        // EncryptedSharedPreferences handle stays `by lazy`, so the keystore work is
        // no longer reached from the constructor either, but the metadata read was.)
        // The first read now happens on this repository's IO scope; [ensureLoaded]
        // keeps read semantics for anything that touches the list earlier.
        warmupScope.launch {
            runCatching { loadMetadata() }.onFailure {
                Log.e(TAG, "deferred env-var load failed: ${it.message}", it)
            }
            loaded = true
        }
    }

    /**
     * [T-android-startup-repo-io] Explicit suspend entry point for startup callers
     * (MinisApp) and for anyone reading [entries] that must not see an empty list.
     */
    suspend fun load() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        loadMetadata()
        loaded = true
    }

    /** Blocks only while the very first read is still outstanding. */
    private fun ensureLoaded() {
        if (loaded || Thread.holdsLock(loadLock)) return
        synchronized(loadLock) {
            if (loaded) return
            runCatching { loadMetadata() }.onFailure { Log.e(TAG, "ensureLoaded failed: ${it.message}") }
            loaded = true
        }
    }

    // -- Validation --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    // Keep printable ASCII (0x20-0x7E) and tab (0x09); iOS paste can inject
    // invisible control scalars (e.g. \u009B) that break shell env injection.
    private fun sanitizeValue(value: String): String =
        value.filter { ch ->
            val c = ch.code
            (c in 0x20..0x7E) || c == 0x09
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean {
        ensureLoaded()
        return _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }
    }

    // -- CRUD --

    fun add(key: String, value: String, note: String = ""): Boolean {
        ensureLoaded()
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (isDuplicateKey(normalizedKey)) return false

        val entry = EnvVarEntry(key = normalizedKey, note = note.trim())
        _entries.update { list -> list + entry }
        encryptedPrefs.edit().putString(normalizedKey, sanitizeValue(value)).apply()
        saveMetadata()
        Log.i(TAG, "Added env var: $normalizedKey")
        return true
    }

    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean {
        ensureLoaded()
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false

        val current = _entries.value.find { it.id == id } ?: return false

        // Check for duplicate (excluding self)
        if (isDuplicateKey(normalizedKey, excludeId = id)) return false

        // Delete old Keychain entry if key changed
        if (current.key != normalizedKey) {
            encryptedPrefs.edit().remove(current.key).apply()
        }

        // Update metadata
        _entries.update { list ->
            list.map { if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim()) else it }
        }

        // Save new value
        encryptedPrefs.edit().putString(normalizedKey, sanitizeValue(newValue)).apply()
        saveMetadata()
        Log.i(TAG, "Updated env var: ${current.key} → $normalizedKey")
        return true
    }

    fun delete(id: String) {
        ensureLoaded()
        val entry = _entries.value.find { it.id == id } ?: return
        encryptedPrefs.edit().remove(entry.key).apply()
        _entries.update { list -> list.filter { it.id != id } }
        saveMetadata()
        Log.i(TAG, "Deleted env var: ${entry.key}")
    }

    fun getValue(key: String): String? {
        ensureLoaded()
        return encryptedPrefs.getString(key, null)
    }

    /**
     * Returns all env vars as a Map<String, String> for sandbox injection.
     * Reads directly from storage to be thread-safe.
     *
     * [T-android-startup-repo-io] ensureLoaded() first: this is what
     * [com.openminis.app.data.EnvVarRedactor] feeds off, and an empty map during
     * the deferred-load window would silently disable secret redaction for the
     * first shell output of the session.
     */
    fun allAsDict(): Map<String, String> {
        ensureLoaded()
        val result = mutableMapOf<String, String>()
        for (entry in _entries.value) {
            val value = encryptedPrefs.getString(entry.key, null)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    // -- Metadata Persistence --

    /**
     * [T-android-env-empty-load-wipe] True when env-vars.json exists but could
     * not be read/parsed. Mirrors [T-android-provider-empty-load-wipe] and the
     * same guard in [MCPRepository]: a torn file used to collapse to "no
     * variables", and the next add/delete then rewrote the file from that empty
     * list — every other variable's metadata (key name, note) went with it while
     * its value stayed keyless in the encrypted prefs. Persistence is refused
     * until a clean read.
     */
    @Volatile
    private var diskUnreadable = false

    val isDiskUnreadable: Boolean get() = diskUnreadable

    /** Returns false when the write was refused or failed. */
    private fun saveMetadata(): Boolean {
        if (diskUnreadable) {
            Log.e(TAG, "env-vars.json unreadable — refusing to overwrite it from memory (${_entries.value.size} entries)")
            return false
        }
        return try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            // Atomic: a process death mid-write used to tear the file, and the
            // next launch's failed parse silently emptied every entry.
            writeTextAtomic(metadataFile, array.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
            false
        }
    }

    private fun loadMetadata() {
        if (!metadataFile.exists()) {
            diskUnreadable = false
            return
        }
        try {
            val array = JSONArray(metadataFile.readText())
            val entries = mutableListOf<EnvVarEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                entries.add(EnvVarEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    key = obj.optString("key", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0),
                ))
            }
            _entries.value = entries
            diskUnreadable = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata, refusing to rewrite env-vars.json: ${e.message}")
            diskUnreadable = true
        }
    }
}
