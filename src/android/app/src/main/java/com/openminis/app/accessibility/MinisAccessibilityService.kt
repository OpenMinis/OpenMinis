package com.openminis.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MinisAccessibilityService — host-side AccessibilityService backing the
 * `android-a11y-cli` offload command. The user must enable it manually
 * under Settings → Accessibility; we never start it programmatically.
 */
class MinisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MinisA11y"
        const val SERVICE_ID = "com.openminis.app/.accessibility.MinisAccessibilityService"
        private const val EVENT_RING_CAP = 1024

        @Volatile
        private var _instance: MinisAccessibilityService? = null

        fun getInstance(): MinisAccessibilityService? = _instance
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val timestamp: Long,
        val viewId: String? = null,
        val contentDescription: String? = null,
        val xy: String? = null,
    )

    private val eventRing = ConcurrentLinkedQueue<RecordedEvent>()
    private val eventListeners = CopyOnWriteArrayList<(RecordedEvent) -> Unit>()

    val nodeRegistry: NodeRegistry = NodeRegistry()

    /**
     * [T-a11y-shared-executor] One daemon-thread executor shared by every
     * [captureScreenshot] call. The old code minted a new single-thread
     * ExecutorService per screenshot; `takeScreenshot` needs an executor only
     * as a callback delivery thread, so a fresh thread + pool object per call
     * was pure garbage — and a burst of captures (browser loop, skill replay)
     * paid it every time. Serializing callbacks on one thread is also what
     * the per-call pools effectively did.
     */
    private val screenshotExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "a11y-screenshot").apply { isDaemon = true }
        }

    override fun onCreate() {
        super.onCreate()
        _instance = this
        AppLogger.info(TAG, "service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        // [T-android-a11y-miui-service-failure] Breadcrumb on (re)bind. On OEM
        // ROMs (MIUI etc.) the system kills + rebinds this service repeatedly,
        // surfacing "此服务出现故障"; a connect log lets us correlate a degraded
        // window in stall/diag logs with a kill/rebind. No self-restart logic —
        // remediation is the user whitelisting autostart + battery (see
        // SystemPermissionsScreen OEM guidance).
        AppLogger.info(TAG, "service connected (manufacturer=${android.os.Build.MANUFACTURER})")
        // [T-android-a11y-force-stop-recovery] Latch that the grant existed, so
        // a later absence can be recognized as a revocation rather than a
        // never-configured service. This is the one callback that only fires
        // when the user has genuinely granted it.
        try {
            AccessibilityRecoveryManager.markGranted(this)
            AccessibilityRecoveryManager.refreshRevokedState(this)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "markGranted failed: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val copy = AccessibilityEvent.obtain(event)
        AccessibilityQueryGuard.submit {
            val src = try {
                copy.source
            } catch (_: Throwable) {
                null
            }
            try {
                val rect = android.graphics.Rect()
                src?.getBoundsInScreen(rect)
                val xy = if (src != null && !rect.isEmpty) "${rect.centerX()},${rect.centerY()}" else null
                val rec = RecordedEvent(
                    type = AccessibilityEvent.eventTypeToString(copy.eventType),
                    packageName = copy.packageName?.toString(),
                    className = copy.className?.toString(),
                    text = copy.text?.joinToString(" ") { it?.toString() ?: "" }?.takeIf { it.isNotBlank() },
                    timestamp = System.currentTimeMillis(),
                    viewId = src?.viewIdResourceName,
                    contentDescription = src?.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                    xy = xy,
                )
                eventRing.offer(rec)
                while (eventRing.size > EVENT_RING_CAP) eventRing.poll()
                for (listener in eventListeners) {
                    try { listener(rec) } catch (_: Throwable) {}
                }
            } finally {
                src?.recycle()
                copy.recycle()
            }
        }
    }

    override fun onInterrupt() {
        AppLogger.info(TAG, "service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance === this) _instance = null
        eventRing.clear()
        nodeRegistry.clear()
        eventListeners.clear()
        AppLogger.info(TAG, "service destroyed")
    }

    fun snapshotEvents(): List<RecordedEvent> = eventRing.toList()

    fun addEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun rootNodes(): List<AccessibilityNodeInfo> = AccessibilityQueryGuard.query(emptyList()) {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (w in windows ?: emptyList()) w.root?.let { out.add(it) }
        if (out.isEmpty()) rootInActiveWindow?.let { out.add(it) }
        out
    }

    fun windowInfos(): List<Map<String, Any?>> = AccessibilityQueryGuard.query(emptyList()) {
        val out = ArrayList<Map<String, Any?>>()
        try {
            for (w in windows ?: emptyList()) {
                out.add(mapOf(
                    "windowId" to w.id,
                    "type" to when (w.type) {
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                        else -> "other"
                    },
                    "title" to (w.title?.toString() ?: ""),
                    "focused" to w.isFocused,
                    "active" to w.isActive,
                ))
            }
        } catch (_: Throwable) {}
        out
    }

    fun foregroundPackage(): Pair<String?, String?> = AccessibilityQueryGuard.query(null to null) {
        val r = rootInActiveWindow
        (r?.packageName?.toString()) to (r?.className?.toString())
    }

    fun dispatchSimpleGesture(path: Path, startTime: Long, durationMs: Long, timeoutMs: Long = 5000): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
        }
        Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, callback, null) }
        return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && ok[0]
    }

    fun dispatchPinch(cx: Float, cy: Float, scale: Float, durationMs: Long): Boolean {
        val initialOffset = 200f
        val finalOffset = (initialOffset * scale).coerceAtLeast(20f)
        val (s0, e0) = if (scale > 1f) {
            (cx - finalOffset to cy) to (cx - initialOffset to cy)
        } else {
            (cx - initialOffset to cy) to (cx - finalOffset to cy)
        }
        val (s1, e1) = if (scale > 1f) {
            (cx + finalOffset to cy) to (cx + initialOffset to cy)
        } else {
            (cx + initialOffset to cy) to (cx + finalOffset to cy)
        }
        val p0 = Path().apply { moveTo(s0.first, s0.second); lineTo(e0.first, e0.second) }
        val p1 = Path().apply { moveTo(s1.first, s1.second); lineTo(e1.first, e1.second) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p0, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(p1, 0, durationMs))
            .build()
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        Handler(Looper.getMainLooper()).post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
                override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
            }, null)
        }
        return done.await(durationMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS) && ok[0]
    }

    /**
     * Result of [captureScreenshot]. On success [bitmap] is non-null and
     * caller is responsible for `bitmap.recycle()`.
     */
    data class ShotResult(
        val bitmap: Bitmap?,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Synchronously captures a system-wide screenshot via
     * `AccessibilityService.takeScreenshot` (API 30+). Blocks the calling
     * thread up to [timeoutMs]. Caller must check `Build.VERSION.SDK_INT`
     * before invoking — annotated `@RequiresApi(R)`.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000): ShotResult {
        val done = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference(
            ShotResult(null, "TIMEOUT", "takeScreenshot timed out")
        )
        // [T-a11y-shared-executor] Shared executor — deliberately NOT shut
        // down here: it belongs to the service lifetime, not the call.
        try {
            takeScreenshot(displayId, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val cs: ColorSpace? = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                        try { hwBuffer.close() } catch (_: Throwable) {}
                        if (bmp == null) {
                            resultRef.set(ShotResult(null, "DECODE_FAILED", "wrapHardwareBuffer returned null"))
                        } else {
                            // Copy to a software bitmap so we can encode + scale freely.
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            resultRef.set(ShotResult(sw))
                        }
                    } catch (t: Throwable) {
                        resultRef.set(ShotResult(null, "DECODE_FAILED", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    resultRef.set(ShotResult(null, "TAKE_SCREENSHOT_FAILED", "takeScreenshot error code=$errorCode"))
                    done.countDown()
                }
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                AppLogger.warning(TAG, "takeScreenshot timed out after ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            resultRef.set(ShotResult(null, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
        }
        return resultRef.get()
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return AccessibilityQueryGuard.query(false) { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
    }
}
