package com.openminis.app.data

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Stable per-install device identity. Mirrors iOS `DeviceIdentity` minus the
 * cross-end iCloud Keychain sync (Android has no equivalent; spec §4.4 calls
 * this out). The uuid survives app kill but not uninstall — that's the most
 * privacy-friendly stable id Android offers without relying on
 * `Settings.Secure.ANDROID_ID` (which some OEMs reset and which Play policy
 * restricts).
 *
 * Usage patterns across the app:
 *   - `zoneName` — namespace any future multi-device sync records.
 *   - `deviceName` — friendly "Pixel 8 · A3F7"-style label for debug logs
 *     and the provider-account UI (future).
 *   - `deviceId` — opaque UUID for any "my-device" disambiguation.
 */
object DeviceIdentity {
    private const val PREFS_NAME = "minis_device_identity"
    private const val KEY_DEVICE_ID = "deviceId"

    @Volatile private var cached: String? = null

    fun deviceId(context: Context): String {
        cached?.let { return it }
        val prefs = encryptedPrefs(context)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        cached = fresh
        return fresh
    }

    /** "Pixel 8 Pro · A3F7" — [displayName] plus the last four chars of [deviceId]. */
    fun deviceName(context: Context): String {
        val shortId = deviceId(context).takeLast(4).uppercase()
        return "${displayName(context)} · $shortId"
    }

    // -- User-set device name ([T-android-backup-device-name-setting]) --

    private const val NAME_PREFS = "minis_device_name"
    const val CUSTOM_NAME_KEY = "device.customName"
    /** Same cap as iOS `DeviceIdentity.customNameMaxLength`. */
    const val CUSTOM_NAME_MAX_LENGTH = 48

    /**
     * A name the user typed in Backup settings, or null. It leads every backup
     * filename and is the manifest's `device_name`, so two phones backing up
     * into one folder can be told apart — the iOS setting of the same name.
     *
     * Plain prefs, not the encrypted store holding [deviceId]: it is a
     * preference, not a secret, and a keystore failure must not lose it.
     */
    fun customName(context: Context): String? =
        context.getSharedPreferences(NAME_PREFS, Context.MODE_PRIVATE)
            .getString(CUSTOM_NAME_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Blank clears the override; values are trimmed and length-capped. */
    fun setCustomName(context: Context, value: String?) {
        val trimmed = value?.trim().orEmpty()
        val prefs = context.getSharedPreferences(NAME_PREFS, Context.MODE_PRIVATE).edit()
        if (trimmed.isEmpty()) prefs.remove(CUSTOM_NAME_KEY)
        else prefs.putString(CUSTOM_NAME_KEY, trimmed.take(CUSTOM_NAME_MAX_LENGTH))
        prefs.apply()
    }

    /**
     * What [displayName] falls back to: the name set in system Settings ▸
     * About phone (defaults to the model, e.g. "Pixel 6"), else the
     * manufacturer + model label. Shown as the field's placeholder.
     */
    fun automaticName(context: Context): String {
        val system = runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver, android.provider.Settings.Global.DEVICE_NAME,
            )
        }.getOrNull()?.trim()
        if (!system.isNullOrEmpty()) return system
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    /** The user's name if set, otherwise [automaticName]. */
    fun displayName(context: Context): String = customName(context) ?: automaticName(context)

    fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    /** Stable sync-zone namespace — mirrors iOS `"device-\(deviceId)"`. */
    fun zoneName(context: Context): String = "device-${deviceId(context)}"

    // T-android-keystore-aead-fail: self-healing wrapper handles
    // master-key invalidation on Samsung One UI / Android 16.
    private fun encryptedPrefs(context: Context) =
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, PREFS_NAME)
}
