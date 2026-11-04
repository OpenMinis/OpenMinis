package com.openminis.app.inbound

import android.content.Context
import android.content.Intent
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger

/**
 * [inbound-task-api] Process-wide owner of the listener.
 *
 * [applyEnabled] is what Settings, cold start, and boot call. The socket
 * comes up only when the user switch is on. The foreground service is a
 * keep-alive around that socket so the process is not frozen while the
 * screen is off; if the service cannot start, the socket still listens for
 * as long as the process lives.
 */
object InboundTaskApi {

    private const val TAG = "InboundTask"
    private val lock = Any()
    private var server: InboundTaskServer? = null

    /**
     * Bring the listener in line with the saved switch.
     * @return false when the switch is on but the socket did not bind.
     *   The saved switch is left as the caller set it, except the settings
     *   screen reverts a failed turn-on itself.
     */
    fun applyEnabled(context: Context): Boolean {
        val app = context.applicationContext
        val settings = InboundTaskSettings(app)
        if (!settings.isEnabled()) {
            stopListening()
            runCatching { app.stopService(Intent(app, InboundTaskApiService::class.java)) }
            return true
        }
        val minis = app as? MinisApp
        if (minis != null && !minis.subsystemsReady()) {
            AppLogger.info(TAG, "not started: app subsystems are not ready")
            return false
        }
        settings.token()
        val listening = ensureListening(app)
        if (!listening) return false
        InboundTaskApiService.start(app)
        return true
    }

    fun ensureListening(context: Context): Boolean {
        val app = context.applicationContext
        if (!InboundTaskSettings(app).isEnabled()) {
            stopListening()
            return false
        }
        synchronized(lock) {
            val current = server
            if (current != null && current.isRunning()) return true
            val created = InboundTaskServer(
                token = { InboundTaskSettings(app).token() },
                starter = ScheduledInboundTaskStarter(app),
            )
            server = created
            created.start()
            val ok = created.awaitBound()
            if (!ok) {
                created.stop()
                if (server === created) server = null
                AppLogger.warning(TAG, "listener did not bind")
                return false
            }
            return true
        }
    }

    fun stopListening() {
        synchronized(lock) {
            server?.stop()
            server = null
        }
    }

    fun isListening(): Boolean = synchronized(lock) { server?.isRunning() == true }
}
