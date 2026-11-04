package com.openminis.app.inbound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * [inbound-task-api] Keeps the process in the foreground while the user has
 * the inbound listener switched on.
 *
 * `specialUse` rather than `dataSync`: a dataSync foreground service is
 * capped at about six hours per day on Android 15 and then the system kills
 * the process. This listener is meant to stay up. The manifest property
 * states the use. On API 29–33, where specialUse does not exist yet, the
 * service is started as dataSync; those releases have no six-hour cap.
 *
 * Swiping the app away does not stop the listener. Force-stop does, until
 * the next launch or boot, because Android will not restart a force-stopped
 * app. The notification text is the port and a pointer to Settings — never
 * the token.
 */
class InboundTaskApiService : Service() {

    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // startForegroundService must be answered with startForeground within
        // a few seconds, even when the switch was turned off in between.
        if (!goForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!InboundTaskSettings(this).isEnabled()) {
            InboundTaskApi.stopListening()
            stopNow()
            return START_NOT_STICKY
        }
        InboundTaskApi.ensureListening(this)
        return START_STICKY
    }

    override fun onDestroy() {
        if (!InboundTaskSettings(this).isEnabled()) {
            InboundTaskApi.stopListening()
        }
        super.onDestroy()
    }

    /**
     * specialUse is not on the dataSync timeout list. If a release calls
     * this anyway, stop immediately: ignoring it kills the process.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warning(TAG, "foreground time limit reached")
        InboundTaskApi.stopListening()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on API 35")
    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    private fun goForeground(): Boolean {
        ensureChannel(this)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType())
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "startForeground refused (${e.javaClass.simpleName})")
            false
        }
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private fun notification(): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pending = open?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.inbound_api_notif_title))
            .setContentText(getString(R.string.inbound_api_notif_text, InboundTaskProtocol.PORT))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    companion object {
        private const val TAG = "InboundTask"
        const val CHANNEL_ID = "inbound_task_api"
        private const val NOTIFICATION_ID = 9473

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, InboundTaskApiService::class.java),
                )
            } catch (e: Exception) {
                AppLogger.error(TAG, "could not start keep-alive (${e.javaClass.simpleName})")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.inbound_api_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
