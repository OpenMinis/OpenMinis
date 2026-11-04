package com.openminis.app.inbound

import android.content.Context
import java.security.SecureRandom

/**
 * [inbound-task-api] User switch and bearer token for the inbound listener.
 *
 * Off by default. The token is generated the first time it is read so the
 * settings screen can show it before the listener is turned on. Prefs name
 * [PREFS] is excluded from backup — see res/xml/backup_rules.xml. The token
 * is never written to the log.
 */
class InboundTaskSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    /** Current token, creating one the first time. Stable until [regenerateToken]. */
    fun token(): String {
        val existing = prefs.getString(KEY_TOKEN, null)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val created = newToken()
        prefs.edit().putString(KEY_TOKEN, created).commit()
        return created
    }

    /** Replaces the token immediately. In-flight requests still holding the old one fail closed. */
    fun regenerateToken(): String {
        val created = newToken()
        prefs.edit().putString(KEY_TOKEN, created).commit()
        return created
    }

    companion object {
        const val PREFS = "inbound_task_api"
        const val KEY_ENABLED = "enabled"
        const val KEY_TOKEN = "token"

        fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
