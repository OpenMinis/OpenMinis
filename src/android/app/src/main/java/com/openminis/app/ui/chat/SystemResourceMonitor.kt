package com.openminis.app.ui.chat

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Lightweight CPU/memory sampler used for shell-tool live HUDs.
 *
 * - **CPU**: This app process's own CPU usage, reported in top-style IRIX
 *   percentage where 100% means one fully-loaded core. On an 8-core
 *   device a hf-render burst that occupies 1.1 cores reads ~110%; full
 *   saturation reads up to 800%. Picked over the iOS-style normalized
 *   0-100% because it matches what `adb shell top` shows for the same
 *   process — a dev cross-checking the HUD against top sees identical
 *   numbers, and the value visually tracks the actual load (a busy
 *   render no longer reads as a deceptively-quiet 12%).
 *
 *   Computed from `/proc/self/stat`: `(Δutime + Δstime) / (Δwall * CLK_TCK)
 *   * 100`. Field 14 (utime) and 15 (stime) are 1-indexed in man proc, but
 *   field 2 is the parenthesized comm name which can contain spaces or
 *   close-parens — so we locate the *last* `)` and parse from there to
 *   avoid mis-splitting. Sampled every 2 s and sum-averaged over the last
 *   5 samples (≈10-s rolling window) — gives a stable reading that doesn't
 *   drop to 0 on a single GC pause or IO block. The first sample after
 *   construction returns 0% — there's no prior baseline to diff against.
 *
 *   T303 history: T244 (commit 8750340) had switched to `/proc/stat` to
 *   capture the system-wide load thinking shell_execute child processes
 *   wouldn't be attributed to the app — but in practice `/proc/stat` is
 *   permission-denied to apps on Android 8+, so the read silently failed
 *   and the HUD read "CPU 0%" under any load. PRoot on Android works via
 *   ptrace, so foreign syscalls run inside the app's own PID anyway and
 *   `/proc/self/stat` does cover hf-render etc. (verified: `top` reports
 *   the same ~110% on com.openminis.app while hf-render runs).
 * - **Memory**: total PSS for "used" (matches what task_info(TASK_VM_INFO)
 *   reports on iOS — proportional set size, the closest cross-platform
 *   equivalent to "what this process owns now"); `ActivityManager.MemoryInfo
 *   .totalMem` for the device cap.
 *
 *   [T-android-resource-hud-offmain] Read from `/proc/self/smaps_rollup`
 *   (`Pss` + `SwapPss`, the same total `Debug.MemoryInfo.getTotalPss()`
 *   reports), falling back to `Debug.getMemoryInfo()` where the rollup file
 *   is missing. `Debug.getMemoryInfo()` parses the FULL `/proc/self/smaps`,
 *   one record per mapping, and Minis maps a lot (WebView, the ONNX runtime,
 *   the proot loaders): profiled on a Pixel 6 perf build it cost ~20-25 ms
 *   per call — 5% of all app CPU during a shell run — and it ran on the main
 *   thread every 2 s, dropping a frame or two each time. The rollup is the
 *   kernel's pre-summed version: a few lines, well under a millisecond. The
 *   sampling also moved off the main thread (see [rememberSystemResourceMonitor]).
 *
 * The class itself is stateful (last-tick baselines) but doesn't push state
 * into Compose — pair it with [rememberSystemResourceMonitor] to drive a
 * recompose loop while the caller wants live numbers.
 */
class SystemResourceMonitor {
    // [T-android-resource-hud-offmain] Written by the IO sampler, read by the
    // UI — volatile so the UI sees each completed sample.
    @Volatile var cpuUsage: Float = 0f          // top-style IRIX %; 100% = 1 core, up to numCores * 100
        private set
    @Volatile var memUsedBytes: Long = 0L
        private set
    @Volatile var memTotalBytes: Long = 0L
        private set

    private var prevCpuTicks: Long? = null
    private var prevSampleNanos: Long? = null

    /**
     * Rolling window of recent (tickDelta, wallNanos) pairs. cpuUsage is
     * computed as the *sum-weighted* average over the window — that is,
     * sum(ticks)/(sum(wallSec) * CLK_TCK * cores), which mathematically
     * collapses to "what fraction of CPU did this app actually use over
     * the last [windowSec] seconds." This is strictly more accurate than
     * a single-window snapshot or an EMA: snapshots aliased at 2s missed
     * load that landed mostly inside the prior window, and EMAs trade off
     * recency vs. noise asymmetrically (slow to climb, slow to fall).
     *
     * Window size 5 with 1s sampling = 5-second running average. Long
     * enough that GC pauses / IO blocks inside the app don't drop the
     * reading to 0; short enough that the user sees load shifts within
     * the same shell command.
     */
    private val recentDeltas = ArrayDeque<Pair<Long, Long>>()
    private val windowSize = 5

    /** Resolved once. CLK_TCK is effectively 100 on Android historically,
     *  but ask the kernel rather than hard-coding so we stay correct on
     *  hypothetical future builds with a different jiffy rate. */
    private val clockTicksPerSec: Long = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    }.getOrNull()?.takeIf { it > 0 } ?: 100L

    // Sampled on IO while reset() can run on Main; one lock keeps the window consistent.
    @Synchronized
    fun sampleOnce(context: Context) {
        sampleCpu()
        sampleMemory(context)
    }

    /**
     * Reset baselines so a freshly-(re)started monitor reports 0% on its
     * first sample rather than averaging across a long idle gap.
     */
    @Synchronized
    fun reset() {
        prevCpuTicks = null
        prevSampleNanos = null
        recentDeltas.clear()
        cpuUsage = 0f
    }

    private fun sampleCpu() {
        val ticks = readSelfCpuTicks() ?: return
        val nowNanos = System.nanoTime()
        val prevTicks = prevCpuTicks
        val prevNanos = prevSampleNanos
        if (prevTicks != null && prevNanos != null) {
            val tickDelta = ticks - prevTicks
            val wallNanos = nowNanos - prevNanos
            if (tickDelta >= 0 && wallNanos > 0) {
                recentDeltas.addLast(tickDelta to wallNanos)
                while (recentDeltas.size > windowSize) recentDeltas.removeFirst()

                // Sum-weighted (not arithmetic) average: collapses the per-
                // sample fractions back into a single fraction over the full
                // window, which is the mathematically correct "% CPU over
                // last N seconds" — no bias toward shorter or longer slices.
                var sumTicks = 0L
                var sumNanos = 0L
                for ((t, n) in recentDeltas) { sumTicks += t; sumNanos += n }
                val sumWallSec = sumNanos / 1_000_000_000.0
                if (sumWallSec > 0) {
                    // top-style IRIX percentage: 100% = one fully-loaded
                    // core. On an 8-core device, hf-render burning 1.1
                    // cores → 110%; full saturation → 800%. Matches what
                    // `top` shows for the same process so the HUD and a
                    // dev's `adb shell top` agree.
                    val raw = sumTicks.toDouble() /
                        (clockTicksPerSec.toDouble() * sumWallSec)
                    cpuUsage = (raw * 100.0).toFloat()
                }
            }
        }
        prevCpuTicks = ticks
        prevSampleNanos = nowNanos
    }

    /**
     * Read `/proc/self/stat` and return `utime + stime` in clock ticks.
     *
     * Format (man proc):
     *   pid (comm) state ppid pgrp ... utime stime cutime cstime ...
     *
     * `comm` is parenthesized and may itself contain spaces or `)` — so we
     * locate the *last* `)` and split the remainder. After that anchor the
     * fields are 0-indexed: state=0, ppid=1, ..., utime=11, stime=12.
     *
     * cutime/cstime (waited-for-children CPU) are intentionally excluded —
     * shell_execute doesn't actually fork (PRoot uses ptrace), so the work
     * runs inside the app's own utime/stime; folding cutime in would only
     * double-count any genuine child reaper work.
     */
    private fun readSelfCpuTicks(): Long? = try {
        val raw = File("/proc/self/stat").readText()
        val rparen = raw.lastIndexOf(')')
        if (rparen < 0 || rparen + 2 >= raw.length) {
            null
        } else {
            val tail = raw.substring(rparen + 2)
                .split(' ')
                .filter { it.isNotEmpty() }
            val utime = tail[11].toLong()
            val stime = tail[12].toLong()
            utime + stime
        }
    } catch (_: Throwable) { null }

    private fun sampleMemory(context: Context) {
        val pssKb = try {
            parseTotalPssKb(File("/proc/self/smaps_rollup").readText())
        } catch (_: Throwable) { null }
            ?: try {
                // Fallback for kernels without smaps_rollup (pre-4.14).
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                mi.totalPss.toLong()
            } catch (_: Throwable) { null }
        // PSS is in KiB; bring to bytes for the formatter.
        if (pssKb != null) memUsedBytes = pssKb * 1024L
        // Device RAM never changes: ask once.
        if (memTotalBytes == 0L) try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val sysMem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(sysMem)
                memTotalBytes = sysMem.totalMem
            }
        } catch (_: Throwable) { /* keep prior value */ }
    }

    companion object {
        /**
         * [T-android-resource-hud-offmain] Total PSS in KiB from the text of
         * `/proc/<pid>/smaps_rollup`: `Pss` + `SwapPss`, the same total
         * `Debug.MemoryInfo.getTotalPss()` reports. Null when there is no
         * `Pss` line (not a rollup file).
         */
        internal fun parseTotalPssKb(rollup: String): Long? {
            var pss: Long? = null
            var swapPss = 0L
            for (line in rollup.lineSequence()) {
                val t = line.trim()
                val value = t.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
                when (t.substringBefore(':')) {
                    "Pss" -> pss = value
                    "SwapPss" -> swapPss = value
                }
            }
            return pss?.let { it + swapPss }
        }
    }

    fun formattedCpu(): String =
        // Allow values >100% (top-style IRIX) — coerce only the lower
        // bound so a transient negative tick delta doesn't leak through.
        // %.0f%% (no width) avoids reserving a column when the value is
        // 1- or 2-digit and overflowing visually when it's 3-digit.
        String.format("CPU %.0f%%", cpuUsage.coerceAtLeast(0f))

    fun formattedMem(compact: Boolean = false): String {
        val usedGB = memUsedBytes / 1_073_741_824.0
        if (compact) return String.format("MEM %.1fG", usedGB)
        val totalGB = memTotalBytes / 1_073_741_824.0
        return String.format("MEM %.1f/%.1f GB", usedGB, totalGB)
    }
}

/**
 * Compose-friendly entrypoint. Polls every 2 s while [active] is true,
 * triggering a recompose on every sample so the consumer can read the
 * monitor's fresh values straight from the returned instance.
 *
 * Stops sampling when [active] flips false (LaunchedEffect cancels its
 * coroutine). Cheap on backgrounded shells: no thread, no broadcast,
 * no allocations beyond the file reads.
 */
@Composable
fun rememberSystemResourceMonitor(active: Boolean): SystemResourceMonitor {
    val context = LocalContext.current
    val monitor = remember { SystemResourceMonitor() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        if (!active) {
            monitor.reset()
            return@LaunchedEffect
        }
        // Prime the baseline; first read returns 0% by design (no prior
        // tick snapshot to subtract). Two seconds later we have a real
        // delta, matching iOS's first-tick behavior.
        // [T-android-resource-hud-offmain] Sample on IO; only the tick that
        // publishes the result runs on the main thread. This used to sample
        // right here, on Main, every 2 s.
        withContext(Dispatchers.IO) { monitor.sampleOnce(context) }
        tick++
        while (isActive) {
            delay(2000)
            withContext(Dispatchers.IO) { monitor.sampleOnce(context) }
            tick++
        }
    }
    // Read `tick` so Compose tracks this snapshot state and recomposes
    // whenever it advances. Without the read the LaunchedEffect would
    // tick happily but no consumer ever sees fresh values.
    @Suppress("UNUSED_VARIABLE") val t = tick
    return monitor
}
