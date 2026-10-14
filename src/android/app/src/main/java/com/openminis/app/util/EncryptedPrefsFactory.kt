package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * T-android-keystore-aead-fail: self-healing wrapper around
 * [EncryptedSharedPreferences.create].
 *
 * The default flow throws `AEADBadTagException` (wrapped as
 * `GeneralSecurityException`) on launch when the AndroidKeystore master
 * key can no longer decrypt the Tink keyset blob — observed on Samsung
 * One UI / Android 16 after backup-restore or biometric re-enroll. The
 * exception bubbles to the main thread and the app dies in a relaunch
 * loop because every cold start hits the same lazy init.
 *
 * Strategy:
 *  1. Try the normal create.
 *  2. On any crypto error: drop ONLY this file's own prefs XML and retry
 *     once. The user loses this store's credentials (re-paste the API key /
 *     re-login OAuth) but the app boots.
 *  3. If recreate still fails: hand back an in-memory store. The rest of the
 *     app sees an empty, read-write store and never crashes, and nothing
 *     persists.
 *
 * [T-android-keystore-cascade] Two things the earlier version did that are
 * deliberately gone:
 *  - It deleted [MasterKey.DEFAULT_MASTER_KEY_ALIAS] and the shared Tink
 *    keyset prefs. The alias AND the keyset blobs are app-wide — every
 *    encrypted file (provider_secrets, oauth_prefs, ssh_host_secrets,
 *    mcp_*_secrets, env_var_values, …) decrypts through them. One store
 *    throwing AEADBadTag therefore destroyed the key material for all the
 *    others: a single corrupted store escalated into total credential loss,
 *    and each victim then ran this same wipe on its own next launch.
 *  - It fell back to a plain-text SharedPreferences. That is not a safety
 *    net, it is a silent downgrade: every credential entered afterwards was
 *    written unencrypted to `_plain_fallback.xml` for the life of the
 *    install, while the log line claimed "credentials lost".
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"

    fun safeCreate(context: Context, fileName: String): SharedPreferences {
        runCatching { return build(context, fileName) }
            .onFailure { Log.w(TAG, "first create($fileName) failed: ${it.message}") }

        // Self-heal this file only. Shared key material stays untouched — see
        // [T-android-keystore-cascade].
        runCatching {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(dir, "$fileName.xml").delete()
        }.onFailure { Log.w(TAG, "wipe of $fileName.xml failed: ${it.message}") }

        runCatching { return build(context, fileName) }
            .onFailure {
                Log.e(TAG, "rebuild($fileName) failed: ${it.message}", it)
            }

        Log.e(
            TAG,
            "Encrypted storage unavailable for $fileName — falling back to an " +
                "IN-MEMORY store. Anything written here is lost on restart; " +
                "nothing is written to disk unencrypted.",
        )
        return InMemorySharedPreferences()
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        // The default alias stays: existing installs have their keysets wrapped
        // by it, and switching per file would orphan all stored credentials.
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Non-persisting [SharedPreferences] used when encrypted storage is
     * unavailable. Reads see what was written this process only; nothing reaches
     * the filesystem.
     */
    private class InMemorySharedPreferences : SharedPreferences {
        private val values = java.util.concurrent.ConcurrentHashMap<String, Any?>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap<String, Any?>(values)

        @Suppress("UNCHECKED_CAST")
        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.let { HashSet(it) } ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>(values)
            private var clearFirst = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { pending[key] = values?.let { HashSet(it) } }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending.remove(key) }
            override fun clear() = apply { pending.clear(); clearFirst = true }
            override fun commit(): Boolean {
                applyToStore()
                return true
            }
            override fun apply() { applyToStore() }
            private fun applyToStore() {
                if (!clearFirst) pending.keys.removeAll(values.keys - pending.keys)
                else { values.clear(); clearFirst = false }
                val changed = pending.keys.filter { values[it] != pending[it] }
                pending.forEach { (k, v) -> values[k] = v }
                synchronized(listeners) { listeners.toList() }.forEach { l ->
                    changed.forEach { l.onSharedPreferenceChanged(this@InMemorySharedPreferences, it) }
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) { if (listener != null) synchronized(listeners) { listeners.add(listener) } }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) { if (listener != null) synchronized(listeners) { listeners.remove(listener) } }
    }
}
