package com.openminis.app.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Main-thread hang watchdog.
 *
 * Posts a heartbeat task to the main looper at a fixed cadence and waits on a
 * background thread for it to fire. If the heartbeat fails to land within
 * [HANG_THRESHOLD_MS], the main thread is considered hung — capture its stack
 * trace, append a record to a daily `stall-<date>.log` file under the app's
 * logs/ dir, increment a persisted hang counter, and continue.
 *
 * After [HANG_LIMIT_FOR_BREAKER] hangs accumulate the [shouldForceHomeOnLaunch]
 * gate flips true. AppNavigation reads it on cold start and overrides the
 * user's "open last session" / "open new chat" launch preference to "open
 * home" (mode 3) so the user isn't trapped in a loop where every cold start
 * lands on a session that hangs the UI again.
 *
 * The counter resets when the user successfully runs a chat session for
 * [RESET_AFTER_QUIET_MS] without another hang firing — see [markHealthyTick].
 *
 * [T-android-hang-false-positive] Gaps are measured on the MONOTONIC uptime
 * clock, the same clock the heartbeat's postDelayed runs on. The wall clock
 * used before kept running through deep sleep and jumped on time sync, so a
 * phone waking up (09-25: "41509s") or correcting its clock after boot
 * (09-28: "16521299s", main thread idle in nativePollOnce) was recorded and
 * COUNTED as a hang. Two more guards keep a false positive out of the count:
 * a main thread found idle at trip time, and a gap no real hang can reach.
 *
 * No iOS counterpart yet (intentional — iOS only has the DEBUG-mode RPC hang
 * detector at src/ios/Debug/DebugRPCHangDetector.swift; this is a production
 * circuit breaker).
 */
object HangDetector {

    private const val TAG = "HangDetector"

    /** Heartbeat cadence — how often the watchdog pings the main thread. */
    private const val HEARTBEAT_INTERVAL_MS = 1_000L

    /** A hang fires once the main thread has missed a heartbeat for this long. */
    private const val HANG_THRESHOLD_MS = 3_000L

    /**
     * [T-android-hangdetector-midhang-sample] While a hang episode is still
     * ongoing, re-sample the main-thread stack this often. Multiple samples
     * across one long hang show whether the thread is stuck in ONE frame
     * (a single blocking call) or churning through related frames (a loop)
     * — and guarantee samples land while the work is actually on the stack.
     * Replaces the old MIN_GAP_BETWEEN_LOGS_MS single-shot dedupe, whose one
     * trip-time snapshot was frequently an idle stack (background process
     * freezes resume with a huge heartbeat gap but an already-idle main
     * thread — that's why historical stall logs were full of
     * nativePollOnce frames).
     */
    private const val MID_HANG_RESAMPLE_MS = 3_000L

    /** Once `count >= this`, AppNavigation forces launch mode = home. */
    private const val HANG_LIMIT_FOR_BREAKER = 3

    /**
     * [T-android-hang-false-positive] A heartbeat gap longer than this is not a
     * hang the watchdog can have observed: the system ANR-kills a main thread
     * blocked for ~5 s of input, and the longest real stall on record here is
     * ~33 s. Anything this long is the process having been frozen or
     * suspended as a whole (cached-app freezer, debugger), so it is logged and
     * not counted.
     */
    private const val MAX_PLAUSIBLE_GAP_MS = 5 * 60_000L

    /** Other app threads written per episode, and frames per thread. */
    private const val PEER_THREAD_LIMIT = 16
    private const val PEER_STACK_DEPTH = 30

    /** Quiet period (no hang firing) after which the count resets. */
    private const val RESET_AFTER_QUIET_MS = 10_000L

    private const val PREFS_NAME = "hang_detector_prefs"
    private const val KEY_HANG_COUNT = "hang_count"
    private const val KEY_LAST_HANG_AT = "last_hang_at_ms"

    private const val STALL_LOG_DIR = "logs"
    private const val STALL_LOG_PREFIX = "stall-"

    /**
     * [T-android-hang-caller-attribution] Frames persisted per sample into
     * stall-<date>.log. Was 25, which is not enough to leave Compose's text
     * stack: StaticLayout → TextLayout → AndroidParagraph → MultiParagraph →
     * TextStringSimpleNode → LayoutNode measure/remeasure alone runs past 25,
     * so a text-layout stall never showed which UI laid the text out. A
     * 12.4 s stall on a user device (zzz, 2026-09-22 14:04:40) could not be
     * attributed to any composable for exactly this reason.
     */
    internal const val STALL_STACK_DEPTH = 120

    /** Package prefix that marks a frame as ours rather than framework. */
    internal const val APP_FRAME_PREFIX = "com.openminis.app."

    /**
     * [T-android-hang-caller-attribution] Navigation route of the screen on top,
     * published by MainActivity's back-stack collector. Written on the main
     * thread, read by the watchdog thread — hence @Volatile. A stall's
     * JankDiag line carries it so the daily log (the file users actually send)
     * says which screen hung without needing the separate stall log.
     */
    @Volatile
    var currentScreen: String = "unknown"
        private set

    fun noteScreen(route: String?) {
        currentScreen = route?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    /**
     * First frame that belongs to this app, formatted `Class.method:line`, or
     * null when the whole stack is framework / library code.
     *
     * The top of a hung main thread is almost always framework (Paint,
     * LineBreaker, StaticLayout…) — true, but it names what was slow, not who
     * asked for it. The first app frame below it is the caller that matters.
     */
    internal fun firstAppFrame(stack: Array<StackTraceElement>): String? {
        val frame = stack.firstOrNull { it.className.startsWith(APP_FRAME_PREFIX) } ?: return null
        return "${frame.className.removePrefix(APP_FRAME_PREFIX)}.${frame.methodName}:${frame.lineNumber}"
    }

    /**
     * The compact attribution appended to every JankDiag line. Pure so it can
     * be tested on the JVM; [writeStallSample] supplies the live values.
     */
    internal fun attributionFields(screen: String, stack: Array<StackTraceElement>): String =
        " screen=$screen appFrame=${firstAppFrame(stack) ?: "none"}"
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    /** Heartbeat ticks bumped by the main-thread side of each ping. */
    private val lastHeartbeatAt = AtomicLong(0L)
    private val lastLogAt = AtomicLong(0L)

    private var appContext: Context? = null

    /** Start the watchdog. Idempotent; safe to call from MinisApp.onCreate(). */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        lastHeartbeatAt.set(SystemClock.uptimeMillis())
        scheduleHeartbeat()
        // Use a non-daemon thread so the watchdog isn't reaped while the app
        // is still alive but scheduled out. Daemon threads also die earlier
        // when the JVM is winding down, which can suppress the very stalls
        // we want to capture.
        thread(name = "HangDetector-watch", isDaemon = false) { watchLoop() }
        // [T-HANG-DIAG] echo via stdout *and* logcat so the start banner
        // shows up regardless of whether the user has Settings → Logging
        // enabled. AppLogger replaces System.out with its file-writing
        // PrintStream when logging is on; when logging is off this still
        // surfaces under `adb logcat`. Same pattern is used by recordHang
        // so its output is also captured both ways.
        val banner = "[T-HANG-DIAG] HangDetector started: threshold=${HANG_THRESHOLD_MS}ms " +
            "interval=${HEARTBEAT_INTERVAL_MS}ms limit=$HANG_LIMIT_FOR_BREAKER"
        println(banner)
        Log.i(TAG, banner)
    }

    /**
     * Called by long-running healthy UI surfaces (e.g. ChatScreen) to confirm
     * the main thread has been responsive for [RESET_AFTER_QUIET_MS] since the
     * last hang. Cheap on the hot path — early-returns when the count is
     * already 0.
     */
    fun markHealthyTick() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_HANG_COUNT, 0) == 0) return
        val lastHangAt = prefs.getLong(KEY_LAST_HANG_AT, 0L)
        if (lastHangAt > 0 && System.currentTimeMillis() - lastHangAt < RESET_AFTER_QUIET_MS) return
        prefs.edit()
            .putInt(KEY_HANG_COUNT, 0)
            .putLong(KEY_LAST_HANG_AT, 0L)
            .apply()
        Log.i(TAG, "hang count reset after quiet period")
    }

    /**
     * AppNavigation calls this on cold start. Returns true once the breaker
     * threshold is hit, asking the launch resolver to ignore the user's
     * "open last session / open new chat" preference and land on the home
     * screen instead — the only safe destination when the previous launches
     * have been hanging.
     */
    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HANG_COUNT, 0) >= HANG_LIMIT_FOR_BREAKER
    }

    /** Manual reset (Settings → "Reset hang counter") — clears immediately. */
    fun resetHangCount(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HANG_COUNT, 0)
            .putLong(KEY_LAST_HANG_AT, 0L)
            .apply()
    }

    fun currentHangCount(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_HANG_COUNT, 0)

    // -- internals -----------------------------------------------------------

    private fun scheduleHeartbeat() {
        mainHandler.postDelayed({
            lastHeartbeatAt.set(SystemClock.uptimeMillis())
            scheduleHeartbeat()
        }, HEARTBEAT_INTERVAL_MS)
    }

    private fun watchLoop() {
        // [T-HANG-DIAG] one-line confirmation that the watchdog thread itself
        // actually entered its loop — distinct from start() which only proves
        // the thread was *spawned*.
        println("[T-HANG-DIAG] HangDetector watchLoop entered")
        var ticks = 0L
        // [T-android-hangdetector-midhang-sample] Episode state: a hang
        // episode starts when the heartbeat gap first crosses the threshold
        // and ends when a heartbeat lands again. The episode is COUNTED once
        // (breakers depend on count semantics) but SAMPLED repeatedly.
        var hangActive = false
        var lastSampleAt = 0L
        var escalation = 0
        var episodePeakSinceMs = 0L
        // [T-android-hang-false-positive] One log line per skipped gap, not one per tick.
        var skipLogged = false
        while (true) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
            ticks++
            val now = SystemClock.uptimeMillis()
            val since = now - lastHeartbeatAt.get()
            // [T-HANG-DIAG] every 30 ticks (~15s) emit a liveness ping so we
            // can confirm the watchdog is alive even when nothing hangs.
            // Volume is intentionally tiny (~4 lines / minute).
            if (ticks % 30L == 0L) {
                println("[T-HANG-DIAG] HangDetector tick=$ticks sinceHeartbeat=${since}ms")
            }
            if (since < HANG_THRESHOLD_MS) {
                skipLogged = false
                if (hangActive) {
                    // Episode over — the heartbeat landed. One labeled
                    // post-recovery snapshot closes the record (its stack is
                    // expectedly idle; it documents WHEN the thread came
                    // back and the episode's peak gap).
                    hangActive = false
                    writeStallSample("post-recovery", episodePeakSinceMs, escalation)
                    println(
                        "[T-HANG-DIAG] hang episode ENDED peak=${episodePeakSinceMs}ms midHangSamples=${escalation + 1}",
                    )
                }
                continue
            }
            if (!hangActive) {
                // [T-android-hang-false-positive] Not a hang: the process was
                // frozen or suspended as a whole, or the main thread is idle
                // and simply has not run the heartbeat yet. Logged, never
                // counted — a false positive in the count is what used to
                // trip the launch breaker and (until it was removed) degrade
                // every streaming reply.
                val notAHang = when {
                    since > MAX_PLAUSIBLE_GAP_MS -> "gap beyond ${MAX_PLAUSIBLE_GAP_MS}ms (process frozen/suspended)"
                    isIdleMainStack(mainThreadStack()) -> "main thread idle in the looper"
                    else -> null
                }
                if (notAHang != null) {
                    if (!skipLogged) {
                        skipLogged = true
                        println("[T-HANG-DIAG] heartbeat gap ${since}ms NOT counted: $notAHang")
                    }
                    continue
                }
                hangActive = true
                escalation = 0
                episodePeakSinceMs = since
                lastSampleAt = now
                lastLogAt.set(now)
                // Counts once per episode + writes the first mid-hang sample.
                recordHang(durationMs = since)
                continue
            }
            episodePeakSinceMs = maxOf(episodePeakSinceMs, since)
            if (now - lastSampleAt >= MID_HANG_RESAMPLE_MS) {
                lastSampleAt = now
                escalation++
                writeStallSample("mid-hang", since, escalation)
            }
        }
    }

    /**
     * [T-android-hangdetector-midhang-sample] Capture the MAIN thread's stack
     * right now and persist it: full ~25 frames into stall-<date>.log, a
     * compact top-5 line into stdout/logcat (feeds the daily AppLogger file)
     * tagged [JankDiag] for grep. Thread.getStackTrace on a hung thread is
     * safe and cheap (VM suspends just that thread for the walk); no count /
     * breaker side effects — those live in [recordHang].
     */
    private fun writeStallSample(label: String, durationMs: Long, escalation: Int) {
        val ctx = appContext ?: return
        val mainStack = try {
            Looper.getMainLooper().thread.stackTrace
        } catch (t: Throwable) {
            arrayOf<StackTraceElement>()
        }
        val ts = TIMESTAMP_FORMAT.format(Date())
        val date = DATE_FORMAT.format(Date())
        val builder = StringBuilder()
        builder.append(
            "===== HANG @ $ts (duration ~${durationMs}ms) sample=$label escalation=$escalation =====\n",
        )
        builder.append("thread: main screen=$currentScreen\n")
        for (frame in mainStack.take(STALL_STACK_DEPTH)) builder.append("  at $frame\n")
        builder.append("\n")

        val top5 = mainStack.take(5).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        // [T-android-content-perf-diag] Attach the currently-rendering large
        // message's structural fingerprint (if any) so a Matcher/Pattern stall
        // stack maps straight to "this message, this content shape" from the log.
        val renderFields = ContentDiag.currentRenderLogFields()
        // [T-android-hang-caller-attribution] Screen + first app frame, so the
        // daily log alone names who hung, not just which framework call was slow.
        val attribution = attributionFields(currentScreen, mainStack)
        println(
            "[T-HANG-DIAG][JankDiag] sample=$label escalation=$escalation duration=${durationMs}ms top5: $top5$renderFields$attribution",
        )

        try {
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            val file = File(dir, "$STALL_LOG_PREFIX$date.log")
            FileWriter(file, /* append = */ true).use { it.write(builder.toString()) }
        } catch (t: Throwable) {
            val msg = "[T-HANG-DIAG] FAILED to write stall log: ${t.javaClass.simpleName}: ${t.message}"
            println(msg)
            Log.w(TAG, msg)
        }
    }

    private fun recordHang(durationMs: Long) {
        val ctx = appContext ?: return
        // [T-android-hangdetector-midhang-sample] The trip-time stack IS a
        // mid-hang sample (the heartbeat is 3s stale and the main thread is
        // still stuck); the watchdog keeps re-sampling every
        // MID_HANG_RESAMPLE_MS via writeStallSample while the episode lasts.
        writeStallSample("mid-hang", durationMs, escalation = 0)

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newCount = prefs.getInt(KEY_HANG_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_HANG_COUNT, newCount)
            .putLong(KEY_LAST_HANG_AT, System.currentTimeMillis())
            .apply()
        // [T-android-hang-false-positive] Who else was running: a main thread
        // WAITING on a lock (09-24/09-25: 21 s in ProviderRepository.getInstances
        // -> synchronized(configLock)) never shows the thread holding it.
        writePeerThreads()
        val tail = "hang detected duration=${durationMs}ms count=$newCount " +
            "launchBreaker=${newCount >= HANG_LIMIT_FOR_BREAKER}"
        println("[T-HANG-DIAG] $tail")
        Log.w(TAG, tail)
    }

    // -- [T-android-hang-false-positive] helpers ----------------------------

    private fun mainThreadStack(): Array<StackTraceElement> = try {
        Looper.getMainLooper().thread.stackTrace
    } catch (t: Throwable) {
        arrayOf()
    }

    /**
     * True when the main thread is parked in the looper waiting for work, i.e.
     * not hung: the top frame is `MessageQueue.nativePollOnce`.
     */
    internal fun isIdleMainStack(stack: Array<StackTraceElement>): Boolean {
        val top = stack.firstOrNull() ?: return false
        return top.className == "android.os.MessageQueue" && top.methodName == "nativePollOnce"
    }

    /**
     * Append the stacks of the app's other threads to today's stall log: every
     * thread that is BLOCKED, plus any with an app frame on its stack, capped.
     * Once per hang episode, so the cost (one getAllStackTraces) is bounded.
     */
    private fun writePeerThreads() {
        val ctx = appContext ?: return
        try {
            val main = Looper.getMainLooper().thread
            val peers = Thread.getAllStackTraces().entries
                .filter { (t, st) ->
                    t !== main && st.isNotEmpty() &&
                        (t.state == Thread.State.BLOCKED || st.any { it.className.startsWith(APP_FRAME_PREFIX) })
                }
                .sortedBy { (t, _) -> if (t.state == Thread.State.BLOCKED) 0 else 1 }
                .take(PEER_THREAD_LIMIT)
            val b = StringBuilder("---- other app threads (${peers.size}) ----\n")
            for ((t, st) in peers) {
                b.append("thread: ${t.name} state=${t.state}\n")
                for (frame in st.take(PEER_STACK_DEPTH)) b.append("  at $frame\n")
            }
            b.append("\n")
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            FileWriter(File(dir, "$STALL_LOG_PREFIX${DATE_FORMAT.format(Date())}.log"), true)
                .use { it.write(b.toString()) }
        } catch (t: Throwable) {
            println("[T-HANG-DIAG] peer thread dump failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
