package com.openminis.app.crash

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.openminis.app.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * T-android-crash-freq-share: local fallback for Crashlytics (#458).
 *
 * Scans `filesDir/logs/` at app launch for `crash-*.log` (ACRA) and
 * `native-crash-*.log` (NDK signal handler) files modified within the
 * last hour. When the count crosses [THRESHOLD], stash the list and the
 * next foreground Activity gets a one-shot AlertDialog offering to share
 * the logs via ACTION_SEND_MULTIPLE.
 *
 * Self-contained on purpose: zero Minis-internal dependencies (no
 * AppLogger / ChatViewModel / ProviderRepository etc), uses
 * `android.util.Log` directly, every entry point wrapped in try/catch.
 * Runs early enough that a partial-init / pre-DI launch can't break it.
 */
object CrashFrequencyDetector {
    private const val TAG = "CrashFreqDetector"
    private const val WINDOW_MS: Long = 60L * 60L * 1000L  // 1 hour
    private const val THRESHOLD = 2

    // T-android-crash-safe-mode-v2: persisted "user dismissed at" timestamp.
    // Only crash files whose mtime is GREATER than this timestamp count
    // toward the safe-mode threshold on subsequent cold starts. Without
    // this, the same pre-existing crash files keep tripping safe-mode on
    // every launch until they age out of the 1-hour window.
    private const val PREFS = "crash_freq_prefs"
    private const val KEY_DISMISSED_AT = "dismissed_at"
    // Force-home grace period: once a crash burst trips, every cold start
    // for the next FORCE_HOME_GRACE_MS lands on Home regardless of the
    // user's "Launch Session" preference. Same idea as HangDetector's
    // shouldForceHomeOnLaunch, but driven by crash files rather than
    // hang heartbeats. Survives an explicit Dismiss — user dismissing the
    // share dialog only silences the *prompt*; we still want to avoid
    // re-entering the chat that may have been the crash trigger.
    private const val KEY_FORCE_HOME_UNTIL = "force_home_until"
    private const val FORCE_HOME_GRACE_MS: Long = 60L * 60L * 1000L  // 1 hour
    // "Not now" hard-suppress window. When the user explicitly opts out
    // of sharing we don't want to nag them again every cold start — they
    // saw the prompt and said no. Hide the prompt entirely for 24 hours,
    // independent of however many fresh crashes pile up in that window.
    // After 24h we resume the normal 1-hour rolling burst detection.
    private const val KEY_SUPPRESS_UNTIL = "suppress_until"
    private const val SUPPRESS_AFTER_DISMISS_MS: Long = 24L * 60L * 60L * 1000L  // 24 hours

    @Volatile
    var pendingShareFiles: List<File>? = null
        private set

    /**
     * T-android-crash-safe-mode-v2: listeners notified when safe-mode
     * transitions ON → OFF (i.e. user dismissed the share dialog). Lets
     * subscribers (ChatViewModel etc.) retry the init work they skipped
     * while safe-mode was active. CopyOnWriteArrayList keeps add/iterate
     * thread-safe with zero locking on the hot read path.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Register a callback fired once when safe-mode flips OFF. Returns
     * an unsubscribe lambda. Listeners are invoked on the calling thread
     * of [setSafeMode] (currently the main thread via dialog buttons /
     * Handler), wrapped in runCatching so a misbehaving listener can't
     * stall the others.
     */
    @JvmStatic
    fun registerSafeModeClearedListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun fireSafeModeCleared() {
        listeners.toList().forEach { runCatching { it() } }
    }

    /** Centralized setter so the ON → OFF edge can fire listeners exactly once. */
    private fun setSafeMode(value: Boolean) {
        val was = _safeMode.getAndSet(value)
        if (was && !value) {
            android.util.Log.i(TAG, "safe-mode cleared, firing ${listeners.size} listener(s)")
            fireSafeModeCleared()
        }
    }

    private fun getDismissedAt(ctx: Context): Long =
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DISMISSED_AT, 0L)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "getDismissedAt failed: ${t.message}")
            0L
        }

    private fun setDismissedAt(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_DISMISSED_AT, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setDismissedAt failed: ${t.message}")
        }
    }

    private fun setSuppressUntil(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_SUPPRESS_UNTIL, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setSuppressUntil failed: ${t.message}")
        }
    }

    private fun getSuppressUntil(ctx: Context): Long =
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_SUPPRESS_UNTIL, 0L)
        } catch (t: Throwable) {
            0L
        }

    private fun setForceHomeUntil(ctx: Context, ts: Long) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_FORCE_HOME_UNTIL, ts).apply()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setForceHomeUntil failed: ${t.message}")
        }
    }

    /**
     * AppNavigation.LaunchedEffect calls this on cold start. Returns true
     * while a recent crash burst is still inside the
     * [FORCE_HOME_GRACE_MS] grace window — even if the user has already
     * dismissed the share dialog. Pairs with [HangDetector.shouldForceHomeOnLaunch]
     * so the launch resolver has a single combined "land on Home this
     * time, the previous run wasn't healthy" signal.
     *
     * Self-expiring: returns false once the grace timestamp has passed,
     * so the user's "Launch Session" preference reasserts itself
     * automatically.
     */
    @JvmStatic
    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val until = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_FORCE_HOME_UNTIL, 0L)
        } catch (t: Throwable) {
            return false
        }
        return until > 0 && System.currentTimeMillis() < until
    }

    /**
     * T-android-crash-detected-halt: when [checkAtLaunch] finds the
     * crash burst, flip this flag so heavy session-restore code paths
     * (e.g. ChatViewModel.loadSession) can short-circuit and avoid
     * re-tripping the same crash before the user sees the share dialog.
     *
     * Lenient policy: cleared as soon as the dialog is dismissed
     * (either share or dismiss button) so the app resumes normal
     * operation. The next launch starts from a fresh flag value.
     *
     * Kept as a static getter to preserve the "zero Minis internal
     * dependencies" contract — callers from anywhere in the app can
     * check the flag without introducing a reverse import on this file.
     */
    private val _safeMode = java.util.concurrent.atomic.AtomicBoolean(false)

    @JvmStatic
    fun isSafeMode(): Boolean = _safeMode.get()

    /**
     * Inspect `filesDir/logs/` and remember recent crash files for the
     * next [maybeShowOnActivity] call. Safe to call from
     * Application.onCreate; never throws.
     */
    fun checkAtLaunch(app: Application) {
        try {
            // "Not now" 24-hour hard suppress. Once the user explicitly
            // dismisses, skip the whole detection path on cold starts
            // until the suppress window expires. After expiry we
            // resume normal 1-hour rolling burst detection — fresh
            // crashes in the new live window can still trigger a prompt.
            val now0 = System.currentTimeMillis()
            val suppressUntil = getSuppressUntil(app)
            if (suppressUntil > 0 && now0 < suppressUntil) {
                pendingShareFiles = null
                android.util.Log.i(
                    TAG,
                    "checkAtLaunch: suppressed (user dismissed; " +
                        "${(suppressUntil - now0) / 60_000L} min remaining)",
                )
                return
            }
            val logsDir = File(app.filesDir, "logs")
            if (!logsDir.isDirectory) {
                pendingShareFiles = null
                return
            }
            val all = logsDir.listFiles { f ->
                val n = f.name
                (n.startsWith("crash-") || n.startsWith("native-crash-")) && n.endsWith(".log")
            } ?: return
            val now = System.currentTimeMillis()
            // Dismiss is now window-scoped, not permanent. While the
            // dismiss timestamp is still inside the 1-hour window we keep
            // filtering files older than it (so "I just clicked Dismiss"
            // doesn't immediately re-prompt on the same burst). Once the
            // dismiss itself ages out of WINDOW_MS, we ignore it — any
            // crash files that survive in the live window are by
            // definition newer than what the user saw last time, and we
            // shouldn't miss a fresh burst just because an old Dismiss
            // marker is still on disk.
            val rawDismissedAt = getDismissedAt(app)
            val dismissedAt = if (rawDismissedAt > 0 && now - rawDismissedAt <= WINDOW_MS) {
                rawDismissedAt
            } else {
                0L
            }
            val recent = all.filter {
                val mt = it.lastModified()
                mt > dismissedAt && now - mt <= WINDOW_MS
            }
            android.util.Log.i(
                TAG,
                "checkAtLaunch: total=${all.size} recent=${recent.size} " +
                    "threshold=$THRESHOLD dismissedAt=$dismissedAt",
            )
            if (recent.size < THRESHOLD) {
                pendingShareFiles = null
                return
            }
            pendingShareFiles = recent.sortedByDescending { it.lastModified() }
            // T-android-crash-detected-halt: enter safe-mode BEFORE the
            // rest of MinisApp.onCreate finishes and any Activity / VM
            // starts restoring sessions. Cleared once the user dismisses
            // the share dialog in [maybeShowOnActivity].
            setSafeMode(true)
            // Persist a "force Home on launch" grace window. After the
            // user resolves the dialog and the app boots normally, the
            // launch resolver in AppNavigation honours this flag and
            // bypasses the user's Launch Session preference — so even a
            // Dismiss doesn't drop the user back into the session that
            // may have been the crash trigger.
            setForceHomeUntil(app, now + FORCE_HOME_GRACE_MS)
            android.util.Log.w(
                TAG,
                "Recent crashes: ${recent.size} in last hour — safe-mode ON, " +
                    "force-home grace set, will prompt on next Activity resume",
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "checkAtLaunch failed: ${t.message}")
        }
    }

    /**
     * Called from the first foreground Activity onResume. One-shot —
     * clears [pendingShareFiles] before showing the dialog so a config
     * change / second resume doesn't re-prompt.
     *
     * [onClosed] fires after any of the three exit paths (Share /
     * Dismiss / Cancel). When MinisApp.onCreate has early-returned (safe
     * mode), MainActivity passes `finish()` here so the half-init app
     * doesn't linger on screen.
     */
    /**
     * Show the crash-share dialog tree.
     *
     * [saveLauncher] is the bridge into the host Activity's
     * `registerForActivityResult(ACTION_CREATE_DOCUMENT)` flow used by
     * the "Save to..." button. The detector lives outside any Activity
     * so it can't call `registerForActivityResult` itself; instead the
     * caller hands in a launcher that takes (zip file, onSaveDone)
     * and is responsible for writing the zip into the URI the user
     * picks plus opening it via ACTION_VIEW. When null, "Save to..."
     * falls back to writing into the public Downloads directory and
     * showing a path toast (legacy behaviour).
     */
    fun maybeShowOnActivity(
        activity: Activity,
        onClosed: (() -> Unit)? = null,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)? = null,
    ) {
        val triggerFiles = pendingShareFiles ?: run {
            // No pending burst — still invoke onClosed so safe-mode
            // callers that pass `finish()` aren't stranded. With no
            // dialog to show there's nothing to wait for.
            onClosed?.invoke()
            return
        }
        pendingShareFiles = null
        try {
            // Always reset the dismiss / burst clock the moment the user
            // sees the picker — regardless of which exit path they take
            // (Continue → method picker, Not now, or back-cancel). After
            // this point new crashes only count toward the next prompt if
            // they're newer than `now`. Without this, the same files that
            // triggered THIS dialog stayed in the 1-hour window and tripped
            // the next cold start again.
            val dismissAtCheckpoint = System.currentTimeMillis()
            setDismissedAt(activity, dismissAtCheckpoint)

            val pickable = buildPickableEntries(activity, triggerFiles)
            showFilePickerDialog(
                activity = activity,
                entries = pickable,
                onContinue = { selected ->
                    if (selected.isEmpty()) {
                        // "Continue" with nothing checked is effectively
                        // the same as Dismiss — treat as opt-out.
                        finishClose(onClosed, dismissContext = activity)
                        return@showFilePickerDialog
                    }
                    showMethodPicker(activity, selected, onClosed, saveLauncher)
                },
                onCancel = {
                    finishClose(onClosed, dismissContext = activity)
                    android.util.Log.i(TAG, "file picker cancelled — 24h suppress applied")
                },
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "dialog failed: ${t.message}")
            setSafeMode(false)
            onClosed?.invoke()
        }
    }

    /**
     * Single entry shown in the multi-select picker.
     *
     * [section] = 0 → crash log (last 24h, max 10); defaults to checked.
     * [section] = 1 → daily AppLogger run-log (last 3 days); defaults to
     * unchecked — the user opts in if they want to share runtime context.
     */
    private data class PickableEntry(
        val file: File,
        val label: String,
        val section: Int,
        val defaultChecked: Boolean,
    )

    private const val PICK_CRASH_WINDOW_MS: Long = 24L * 60L * 60L * 1000L  // 24 hours
    private const val PICK_CRASH_LIMIT: Int = 10
    private const val PICK_RUN_LOG_WINDOW_MS: Long = 3L * 24L * 60L * 60L * 1000L  // 3 days

    private fun buildPickableEntries(
        ctx: Context,
        triggerFiles: List<File>,
    ): List<PickableEntry> {
        val logsDir = File(ctx.filesDir, "logs")
        val now = System.currentTimeMillis()
        val triggerSet = triggerFiles.map { it.absolutePath }.toHashSet()

        // Last-24h crashes capped to 10 most recent. We deliberately drop
        // the "older crashes" bucket — if the user wants those they can
        // dig into Settings → Logs; this dialog is for the immediate
        // "you just crashed, here's what to send" workflow.
        val crashes = (logsDir.listFiles { f ->
            val n = f.name
            (n.startsWith("crash-") || n.startsWith("native-crash-")) && n.endsWith(".log")
        } ?: emptyArray<File>())
            .toList()
            .filter {
                it.absolutePath in triggerSet || now - it.lastModified() <= PICK_CRASH_WINDOW_MS
            }
            .sortedByDescending { it.lastModified() }
            .take(PICK_CRASH_LIMIT)

        // Daily AppLogger logs (minis-YYYY-MM-DD.log) for the last 3
        // days. Default unchecked — they're large and only useful for
        // tracking down what the agent was doing right before the
        // crash; the user opts in when they care about that context.
        val dailyLogs = (logsDir.listFiles { f ->
            f.name.startsWith("minis-") && f.name.endsWith(".log")
        } ?: emptyArray<File>())
            .toList()
            .filter { now - it.lastModified() <= PICK_RUN_LOG_WINDOW_MS }
            .sortedByDescending { it.lastModified() }

        val out = mutableListOf<PickableEntry>()
        val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        for (f in crashes) {
            out += PickableEntry(
                file = f,
                label = "${f.name}  ·  ${formatBytes(f.length())}  ·  ${timeFmt.format(Date(f.lastModified()))}",
                section = 0,
                defaultChecked = true,
            )
        }
        for (f in dailyLogs) {
            out += PickableEntry(
                file = f,
                label = "${f.name}  ·  ${formatBytes(f.length())}",
                section = 1,
                defaultChecked = false,
            )
        }
        return out
    }

    private fun showFilePickerDialog(
        activity: Activity,
        entries: List<PickableEntry>,
        onContinue: (List<File>) -> Unit,
        onCancel: () -> Unit,
    ) {
        val checkBoxes = ArrayList<CheckBox>(entries.size)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        var lastSection = -1
        entries.forEachIndexed { idx, e ->
            if (e.section != lastSection) {
                lastSection = e.section
                val header = TextView(activity).apply {
                    text = activity.getString(
                        when (e.section) {
                            0 -> R.string.crash_freq_pick_section_crashes
                            else -> R.string.crash_freq_pick_section_logs
                        }
                    )
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, if (idx == 0) 0 else dp(12), 0, dp(4))
                    alpha = 0.6f
                }
                column.addView(header)
            }
            val cb = CheckBox(activity).apply {
                text = e.label
                isChecked = e.defaultChecked
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            checkBoxes.add(cb)
            column.addView(cb)
        }

        val scroll = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(column)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.crash_freq_pick_files_title)
            .setView(scroll)
            .setPositiveButton(R.string.crash_freq_pick_continue) { _, _ ->
                val picked = entries
                    .mapIndexedNotNull { i, e -> if (checkBoxes[i].isChecked) e.file else null }
                onContinue(picked)
            }
            .setNegativeButton(R.string.crash_freq_dialog_dismiss) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        applyRoundedBackground(activity, dialog)
        dialog.show()
    }

    /**
     * Replace the system AlertDialog's default rectangular window
     * background with a rounded-corner rectangle so the share prompt
     * looks like a modern Material sheet instead of a 1990s system
     * alert. We do this manually rather than pulling in
     * com.google.android.material:material (one new dependency for one
     * dialog).
     */
    private fun applyRoundedBackground(activity: Activity, dialog: AlertDialog) {
        try {
            val density = activity.resources.displayMetrics.density
            // Light / dark adaptive: pull the colorBackground attribute
            // from the activity theme so we don't have to hard-code
            // colors for night mode.
            val bgColor = run {
                val tv = android.util.TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                    tv.data
                } else {
                    0xFFFFFFFF.toInt()
                }
            }
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(bgColor)
            }
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(bg)
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "applyRoundedBackground failed: ${t.message}")
        }
    }

    /**
     * Second step: vertical 3-option menu — Email / Share to… / Save
     * to… (Downloads). Each path zips the selected files first.
     */
    private fun showMethodPicker(
        activity: Activity,
        files: List<File>,
        onClosed: (() -> Unit)?,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)? = null,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val dialogRef = arrayOfNulls<AlertDialog>(1)

        fun addRow(textRes: Int, action: () -> Unit) {
            val tv = TextView(activity).apply {
                text = activity.getString(textRes)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isClickable = true
                isFocusable = true
                background = androidx.core.content.ContextCompat
                    .getDrawable(activity, android.R.drawable.list_selector_background)
                setOnClickListener {
                    dialogRef[0]?.dismiss()
                    action()
                }
            }
            column.addView(tv)
        }

        addRow(R.string.crash_freq_method_email) {
            dispatchZippedShare(activity, files, emailOnly = true, onClosed)
        }
        addRow(R.string.crash_freq_method_share) {
            dispatchZippedShare(activity, files, emailOnly = false, onClosed)
        }
        addRow(R.string.crash_freq_method_save) {
            if (saveLauncher != null) {
                dispatchZippedSaveViaPicker(activity, files, saveLauncher, onClosed)
            } else {
                dispatchZippedSave(activity, files, onClosed)
            }
        }

        val dlg = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.crash_freq_method_title, files.size))
            .setView(column)
            .setNegativeButton(R.string.crash_freq_dialog_dismiss) { _, _ ->
                finishClose(onClosed, dismissContext = activity)
            }
            .setOnCancelListener { finishClose(onClosed, dismissContext = activity) }
            .create()
        applyRoundedBackground(activity, dlg)
        dialogRef[0] = dlg
        dlg.show()
    }

    /**
     * Common close path — clears safe-mode and invokes onClosed.
     *
     * [dismissContext] = the activity, when the close was triggered by
     * an explicit "Not now" / back / cancel exit. Triggers the 24-hour
     * hard-suppress window so the user isn't nagged at every cold start
     * for a day. Share / Save paths pass null because the user already
     * engaged with the prompt — fresh future bursts should still alert.
     */
    private fun finishClose(onClosed: (() -> Unit)?, dismissContext: Context? = null) {
        if (dismissContext != null) {
            val until = System.currentTimeMillis() + SUPPRESS_AFTER_DISMISS_MS
            setSuppressUntil(dismissContext, until)
            android.util.Log.i(
                TAG,
                "user dismissed — suppress next 24h " +
                    "(until ts=$until)",
            )
        }
        setSafeMode(false)
        android.util.Log.i(TAG, "safe-mode OFF (flow finished)")
        onClosed?.invoke()
    }

    /**
     * Zip the [files] into cacheDir/share/ and fire ACTION_SEND with
     * application/zip. When [emailOnly] is true we hint at an email
     * client (EMAIL extra + setType message/rfc822 fallback) so the
     * chooser surfaces Gmail / Outlook first; otherwise we use the
     * generic share sheet so the user gets every share target.
     */
    private fun dispatchZippedShare(
        ctx: Context,
        files: List<File>,
        emailOnly: Boolean,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = packageZip(ctx, files) ?: run {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.crash_freq_zip_failed, "no files"),
                    Toast.LENGTH_LONG,
                ).show()
                finishClose(onClosed)
                return
            }
            val authority = "${ctx.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(ctx, authority, zip)
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val subject = ctx.getString(R.string.crash_freq_email_subject, date)
            // Project crash-report inbox — a public alias, safe to ship in
            // open-source builds (replaced the maintainer's personal email).
            val recipient = "dev@openminis.app"

            val launched: Boolean = if (emailOnly) {
                tryLaunchMailto(ctx, recipient, subject, uri)
            } else {
                tryLaunchShare(ctx, uri, subject)
            }
            if (!launched) {
                // Fallback path: generic ACTION_SEND chooser. Works
                // everywhere even if no email app is installed (file
                // managers / messengers will appear in the chooser).
                tryLaunchShare(ctx, uri, subject)
            }
            // 500ms lets the chooser surface before MainActivity (which
            // passed `finish()` as onClosed) tears itself down. Matches
            // the prior share path's behaviour.
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ finishClose(onClosed) }, 500L)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "share dispatch failed: ${t.message}")
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_zip_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
            finishClose(onClosed)
        }
    }

    /**
     * Launch a mail-only intent that carries the crash zip as a real
     * attachment. Previously this used `ACTION_SENDTO mailto:` with
     * EXTRA_STREAM, but most major mail clients (Gmail in particular,
     * reproduced on 0.10-preview) silently drop
     * EXTRA_STREAM when the action is ACTION_SENDTO — the user lands
     * in a compose window with To/Subject filled but no attachment,
     * and the developer never gets the log.
     *
     * Correct shape per the Android docs:
     *   - Primary intent = ACTION_SEND with the attachment + recipient
     *     + subject + body. ACTION_SEND is the one mail clients actually
     *     honour for EXTRA_STREAM.
     *   - Use `selector` = ACTION_SENDTO mailto: so the chooser is
     *     filtered to email apps only (no messengers / file managers
     *     leaking into the list).
     *
     * Returns false when no email app is installed — caller falls back
     * to the generic share path so the user still has some way to ship
     * the zip out.
     */
    private fun tryLaunchMailto(
        ctx: Context,
        recipient: String,
        subject: String,
        attachment: Uri,
    ): Boolean {
        return try {
            // Body that names the attachment by hand. Most mail clients
            // still render the attachment chip themselves, but if a
            // client somehow drops the attachment again this paragraph
            // tells the recipient what was supposed to be there.
            val body = ctx.getString(R.string.crash_freq_email_body)
            // Selector restricts the chooser to apps that can handle a
            // bare mailto:. Without a body the selector intent doesn't
            // need data beyond the scheme — Gmail / Outlook / Inbox all
            // match `mailto:` with no recipient.
            val selector = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                // application/zip matches the packaged attachment; mail
                // clients route MIME → "attach as file" regardless of
                // the extension hint, so this is what gets the chip to
                // appear in Gmail.
                type = "application/zip"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, attachment)
                // FLAG_GRANT_READ_URI_PERMISSION on the OUTER intent
                // covers both the chooser shim and the eventual mail
                // app receiving the EXTRA_STREAM URI.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                this.selector = selector
            }
            // resolveActivity on a selector-bearing ACTION_SEND returns
            // null on some OEM builds even when mail apps are present
            // (the resolver doesn't fold selectors when probing). Probe
            // the selector directly — if any mail app matches mailto:,
            // the launch will succeed.
            if (selector.resolveActivity(ctx.packageManager) == null) {
                android.util.Log.w(TAG, "mailto selector: no email app resolved")
                return false
            }
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "mailto launch failed: ${t.message}")
            false
        }
    }

    /** Generic share-sheet path used by the "Share to…" button and as the mailto fallback. */
    private fun tryLaunchShare(
        ctx: Context,
        attachment: Uri,
        subject: String,
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, attachment)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                ctx.getString(R.string.crash_freq_share_chooser),
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "share launch failed: ${t.message}")
            false
        }
    }

    /**
     * "Save to…" preferred path — lets the user pick the destination via
     * the system file picker (ACTION_CREATE_DOCUMENT). The host
     * Activity passed in a [saveLauncher] that owns the
     * `registerForActivityResult` registration; here we just zip and
     * hand off. The launcher copies the zip into the chosen URI and
     * fires ACTION_VIEW so the user jumps straight to the saved file.
     */
    private fun dispatchZippedSaveViaPicker(
        ctx: Context,
        files: List<File>,
        saveLauncher: (zip: File, onSaveDone: () -> Unit) -> Unit,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = packageZip(ctx, files) ?: run {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.crash_freq_zip_failed, "no files"),
                    Toast.LENGTH_LONG,
                ).show()
                finishClose(onClosed)
                return
            }
            // Defer safe-mode clear / Activity finish until the picker
            // result lands. Without this, MainActivity would finish() the
            // moment the method picker dismissed and the system file
            // picker would have no foreground Activity to return to.
            saveLauncher(zip) { finishClose(onClosed) }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "save (picker) failed: ${t.message}")
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_save_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
            finishClose(onClosed)
        }
    }

    /**
     * "Save to…" legacy fallback — write the zipped bundle into the
     * public Downloads directory when no save launcher is wired up
     * (e.g. callers outside the safe-mode MainActivity path). On Q+ we
     * use MediaStore.Downloads (no permission required, scoped storage
     * friendly); on older API levels we fall back to the public
     * Downloads directory directly.
     */
    private fun dispatchZippedSave(
        ctx: Context,
        files: List<File>,
        onClosed: (() -> Unit)?,
    ) {
        try {
            val zip = packageZip(ctx, files) ?: run {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.crash_freq_zip_failed, "no files"),
                    Toast.LENGTH_LONG,
                ).show()
                finishClose(onClosed)
                return
            }
            val savedPath: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                resolver.openOutputStream(target).use { out ->
                    requireNotNull(out) { "MediaStore openOutputStream returned null" }
                    FileInputStream(zip).use { input -> input.copyTo(out) }
                }
                "Downloads/${zip.name}"
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                downloads.mkdirs()
                val dest = File(downloads, zip.name)
                FileInputStream(zip).use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                dest.absolutePath
            }
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_save_success, savedPath),
                Toast.LENGTH_LONG,
            ).show()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "save failed: ${t.message}")
            Toast.makeText(
                ctx,
                ctx.getString(R.string.crash_freq_save_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        } finally {
            finishClose(onClosed)
        }
    }

    /**
     * Bundle [files] into a single zip under cacheDir/share/. Returns the
     * zip File on success or null if the input list is effectively empty.
     * Uses the cache dir so the OS cleans up stale archives if the user
     * shares but never re-opens Minis; FileProvider already grants the
     * receiving app read access via FLAG_GRANT_READ_URI_PERMISSION.
     */
    private fun packageZip(ctx: Context, files: List<File>): File? {
        val readable = files.filter { it.exists() && it.length() > 0 }
        if (readable.isEmpty()) return null
        val shareDir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(shareDir, "minis-logs-$stamp.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zout ->
            val buf = ByteArray(64 * 1024)
            for (f in readable) {
                try {
                    zout.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { fin ->
                        while (true) {
                            val n = fin.read(buf)
                            if (n <= 0) break
                            zout.write(buf, 0, n)
                        }
                    }
                    zout.closeEntry()
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "zip skip ${f.name}: ${t.message}")
                }
            }
        }
        return zipFile
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }
}
