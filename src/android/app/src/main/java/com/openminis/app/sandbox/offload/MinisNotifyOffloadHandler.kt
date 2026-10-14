package com.openminis.app.sandbox.offload

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.notification.SandboxNotifyActionReceiver
import com.openminis.app.notification.SandboxNotifyActions
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * minis-notify — guest-facing notification with optional action buttons.
 *
 * Intent extras carry only a token; the command lives in SharedPreferences.
 */
class MinisNotifyOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help") || args.positional.firstOrNull() in setOf("help", "-h")) {
            return NativeOffloadResult(0, HELP)
        }
        val sub = args.positional.firstOrNull() ?: "post"
        return when (sub) {
            "post", "send" -> post(request, args)
            else -> NativeOffloadResult(2, "minis-notify: unknown subcommand '$sub'\n$HELP")
        }
    }

    private fun post(request: NativeOffloadRequest, args: OffloadArgs): NativeOffloadResult {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return NativeOffloadResult(77, "minis-notify: POST_NOTIFICATIONS not granted\n")
        }
        val title = args.get("title") ?: args.positional.getOrNull(1) ?: "Minis Ultra"
        val body = args.get("body", "message") ?: args.positional.drop(2).joinToString(" ").ifBlank { title }
        val sessionId = args.get("session") ?: request.sessionId ?: "default"
        val actions = parseActions(sessionId, args)
        if (actions.any { it.error != null }) {
            val err = actions.first { it.error != null }.error
            return NativeOffloadResult(2, "minis-notify: $err\n")
        }
        ensureChannel()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.take(80))
            .setContentText(body.take(240))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        for (act in actions) {
            val intent = Intent(context, SandboxNotifyActionReceiver::class.java).apply {
                this.action = SandboxNotifyActions.ACTION
                putExtra(SandboxNotifyActions.EXTRA_ACTION, act.token)
                putExtra(SandboxNotifyActions.EXTRA_SESSION_ID, sessionId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                seq.incrementAndGet(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, act.label.take(24), pi)
        }
        val id = 4100 + seq.incrementAndGet() % 1_000_000
        nm.notify(id, builder.build())
        val json = JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("actions", JSONArray(actions.map { it.label }))
        return NativeOffloadResult(0, json.toString() + "\n")
    }

    private data class Act(val label: String, val token: String, val error: String? = null)

    private fun parseActions(sessionId: String, args: OffloadArgs): List<Act> {
        val out = mutableListOf<Act>()
        val pairs = listOf(
            args.get("action-label") to args.get("action-command"),
            args.get("action-label-2") to args.get("action-command-2"),
        )
        for ((label, cmd) in pairs) {
            if (label.isNullOrBlank() && cmd.isNullOrBlank()) continue
            if (label.isNullOrBlank() || cmd.isNullOrBlank()) {
                out += Act("", "", "action-label and action-command required together")
                continue
            }
            val token = SandboxNotifyActions.registerDynamic(context, sessionId, cmd)
            if (token == null) {
                out += Act(label, "", "rejected command (path must be under /var/minis or /usr/local/bin/minis-*; no shell metacharacters)")
            } else {
                out += Act(label, token)
            }
        }
        return out
    }

    private fun ensureChannel() {
        // minSdk 26 (O): the old `< O` early-return was dead.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sandbox", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    companion object {
        private val seq = AtomicInteger(0)
        private const val CHANNEL = "minis_sandbox_notify"
        private const val HELP = """minis-notify — post a notification with optional action buttons

Usage:
  minis-notify post --title TEXT --body TEXT [--session ID]
      [--action-label L --action-command PATH]
      [--action-label-2 L --action-command-2 PATH]

PATH must be under /var/minis or /usr/local/bin/minis-* (no shell metacharacters).
The Intent extra is a token only — the command is stored on the host.
"""
    }
}
