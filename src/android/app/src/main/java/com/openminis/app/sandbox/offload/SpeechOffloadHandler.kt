package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * android-speech — speech recognition (audio → text).
 *
 * T61: re-aligned with iOS `apple-speech` (NativeOffloads/SpeechOffload.m).
 * Subcommand model:
 *
 *   android-speech transcribe [--source mic|<path>] [--language tag] [--duration N]
 *   android-speech listen [...]              # legacy alias for transcribe
 *   android-speech languages
 *   android-speech status
 *
 * What changed from the pre-T61 surface:
 *   - `transcribe` is the new canonical subcommand; `listen` is an alias kept
 *     for back-compat with prompts that learned the old form.
 *   - `--source <mic|path>` mirrors apple-speech: defaults to system mic,
 *     also accepts a Linux file path under /var/minis/... resolved via
 *     [PRootKernel.resolveHostPath]. **Audio-file transcription is not
 *     yet wired through the recognizer** — Android's [SpeechRecognizer]
 *     only exposes microphone input on most vendor implementations
 *     (the API 31 `EXTRA_AUDIO_SOURCE` extension is not honoured by
 *     Google's recogniser on Pixel-class devices and is absent on most
 *     OEM ROMs). When the user passes a file path we currently return a
 *     structured `not_supported` error; the path is validated and
 *     reported back so future versions can route through a streaming
 *     decoder + an alternative engine without changing the CLI.
 *   - `languages` enumerates installed recognition locales via the
 *     [RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS] ordered broadcast.
 *     Mirrors iOS `[SFSpeechRecognizer supportedLocales]`.
 *
 * Permission flow: when a transcribe attempt needs RECORD_AUDIO and the
 * user has already permanently denied it, we route through the same
 * 3-stage flow the chat composer's mic button uses (T67).
 */
class SpeechOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help") || args.positional.isEmpty()) {
            return NativeOffloadResult(if (args.positional.isEmpty()) 2 else 0, HELP)
        }

        return try {
            when (val sub = args.positional[0]) {
                "status" -> cmdStatus(args)
                "languages" -> cmdLanguages(args)
                "transcribe", "listen" -> cmdTranscribe(args)
                else -> NativeOffloadResult(2, "android-speech: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject()
                .put("error", "internal")
                .put("message", e.message ?: "unknown")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────────

    private fun cmdStatus(args: OffloadArgs): NativeOffloadResult {
        val ok = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Throwable) { false }
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val body = JSONObject()
            .put("available", ok)
            .put("has_record_audio_permission", hasMic)
            .toString(2)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun cmdTranscribe(args: OffloadArgs): NativeOffloadResult {
        val available = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Throwable) { false }
        if (!available) {
            val body = JSONObject()
                .put("error", "recognizer_unavailable")
                .put(
                    "message",
                    "No speech recognition service is registered on this device. " +
                        "This is common on devices without Google Mobile Services " +
                        "(Huawei HMS-only, some stripped China ROMs). Ask the user " +
                        "to install a speech recognition engine (e.g. Google, or an " +
                        "OEM-provided alternative).",
                )
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }

        // [T-android-speech-engine-flag] `--engine` selects the recognition
        // path. The provider (cloud ASR) engine only exists inside the in-app
        // voice input: it streams through the bound voice-input model and needs
        // the app's own audio session, so the CLI cannot drive it. Rather than
        // accept the flag and silently do something else, `provider` gets an
        // actionable answer and `auto` is defined as "system, labelled" — the
        // caller always learns which engine actually ran.
        val requestedEngine = (args.get("engine") ?: "system").trim().lowercase()
        if (requestedEngine !in ENGINE_CHOICES) {
            return NativeOffloadResult(
                2,
                "android-speech: --engine must be one of ${ENGINE_CHOICES.joinToString("|")}\n",
            )
        }
        if (requestedEngine == "provider") {
            val body = JSONObject()
                .put("error", "engine_not_available_in_cli")
                .put("requested_engine", requestedEngine)
                .put(
                    "message",
                    "The provider (cloud ASR) engine only runs inside the app's voice " +
                        "input, where it streams through the bound voice-input model. " +
                        "The CLI transcribes with the system recognizer: re-run with " +
                        "--engine system, or --engine auto to accept that fallback.",
                )
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }

        // --source: default mic, accept "mic" / "system-mic" / any /var/minis/... path.
        val source = args.get("source")
        if (source != null && !sourceIsMic(source)) {
            // T61: file-source path. Validate the path resolves and the
            // file exists, but bail with `not_supported` because Android
            // SpeechRecognizer doesn't accept arbitrary audio file inputs
            // on the platforms we ship to. Reported path is what the
            // future implementation would consume.
            val resolved = resolveSourcePath(source)
            val exists = resolved?.exists() == true
            AppLogger.warning(
                TAG,
                "transcribe --source=$source resolved=${resolved?.absolutePath} exists=$exists — file source not yet wired",
            )
            val body = JSONObject()
                .put("error", "not_supported")
                .put(
                    "message",
                    "Audio-file transcription is not yet supported on Android. " +
                        "Only microphone source works today. The given path was " +
                        "${if (exists) "found" else "not found"} on the host. Use " +
                        "`android-speech transcribe --source mic` or omit --source.",
                )
                .put("requested_path", source)
                .put("resolved_host_path", resolved?.absolutePath ?: "")
                .put("file_exists", exists)
                .toString()
            return NativeOffloadResult(2, OffloadOutput.formatBody(body, args) + "\n")
        }

        // Mic path. Permission gate first.
        ensureMicPermission(args)?.let { return it }

        // iOS-style flag names: --language, --duration, --on-device.
        // Pre-T61 callers also passed --max / --timeout; preserve those.
        val lang = args.get("language")
        val maxResults = args.getInt("max") ?: 3
        val durationSec = (args.getInt("duration") ?: args.getInt("timeout") ?: 30).coerceIn(1, 120)
        val text = recognize(lang, maxResults, durationSec.toLong())
        // [T-android-speech-engine-flag] Stamp the engine fields onto whatever
        // recognize() produced, including its error shapes — a caller that asked
        // for `auto` still needs to know the system recognizer ran.
        val labeled = try {
            JSONObject(text)
                .put("requested_engine", requestedEngine)
                .put("engine_used", "system")
                .toString()
        } catch (_: Exception) {
            text
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(labeled, args) + "\n")
    }

    /**
     * Enumerate recognition locales installed on the device. Uses the
     * [RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS] ordered broadcast — the
     * standard non-deprecated way to get the supported language list on
     * Android. Times out at 3s if no recognizer responds (which happens on
     * devices without GMS).
     *
     * Mirrors iOS `[SFSpeechRecognizer supportedLocales]`. Output schema
     * matches: `{ locales: [{locale, display_name}, ...], count: N }`.
     */
    private fun cmdLanguages(args: OffloadArgs): NativeOffloadResult {
        val latch = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val extras = getResultExtras(false)
                @Suppress("DEPRECATION")
                val langs: ArrayList<String>? = extras?.getStringArrayList(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
                )
                resultRef.set(langs?.toList() ?: emptyList())
                latch.countDown()
            }
        }
        try {
            // sendOrderedBroadcast with our resultReceiver — the recogniser
            // populates resultExtras with EXTRA_SUPPORTED_LANGUAGES.
            context.sendOrderedBroadcast(
                Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
                null,
                receiver,
                Handler(Looper.getMainLooper()),
                0,
                null,
                null,
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "languages broadcast failed: ${e.message}")
        }
        latch.await(3, TimeUnit.SECONDS)

        val prefix = args.get("language")
        val items = JSONArray()
        for (tag in resultRef.get().sorted()) {
            if (!prefix.isNullOrBlank() && !tag.startsWith(prefix)) continue
            val locale = java.util.Locale.forLanguageTag(tag.replace('_', '-'))
            items.put(
                JSONObject()
                    .put("locale", tag)
                    .put("display_name", locale.displayName.ifEmpty { tag }),
            )
        }
        val body = JSONObject()
            .put("locales", items)
            .put("count", items.length())
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sourceIsMic(source: String): Boolean {
        val s = source.trim().lowercase()
        return s.isEmpty() || s == "mic" || s == "system-mic" || s == "system_mic" || s == "microphone"
    }

    /**
     * Resolve a `/var/minis/...`-style Linux path to a host File via
     * [PRootKernel]'s global bind-mount table. Per-session paths
     * (attachments / offloads / workspace / browser) work as long as the
     * owning session is the most-recent shell to boot — last-writer-wins
     * per the kernel's documentation.
     */
    private fun resolveSourcePath(source: String): File? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("/")) {
                PRootKernel.resolveHostPath(trimmed) ?: File(trimmed)
            } else {
                File(trimmed)
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "resolveSourcePath('$trimmed') failed: ${e.message}")
            null
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun ensureMicPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasMic()) return null
        AppLogger.warning(TAG, "RECORD_AUDIO not granted — routing through permission flow")
        val result = runBlocking {
            withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS) {
            var r = OffloadPermissionManager.requestAndroidPermission(
                listOf(Manifest.permission.RECORD_AUDIO),
            )
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasMic() })
            ) {
                AppLogger.info(TAG, "Mic permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = Manifest.permission.RECORD_AUDIO,
                        title = "Microphone permission needed",
                        message = "Minis needs microphone permission to transcribe speech. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasMic() },
                )
            }
            r
                }
        } ?: OffloadPermissionManager.AndroidPermissionResult.TIMEOUT
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the microphone permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the microphone permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun recognize(language: String?, maxResults: Int, timeoutSec: Long): String {
        val latch = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference("""{"error": "no result"}""")
        val mainHandler = Handler(Looper.getMainLooper())

        // [T-android-speech-error10] C4: every exit path must destroy the
        // recognizer exactly once. Previously destroy() only ran in the
        // onResults/onError callbacks — a latch timeout ORPHANED the
        // recognizer, and repeated runs exhausted the system recognizer
        // service quota, after which every listen failed with
        // ERROR_TOO_MANY_REQUESTS (10). Destroy always runs on the main
        // looper (the creation thread, as SpeechRecognizer requires).
        val recognizerRef = java.util.concurrent.atomic.AtomicReference<SpeechRecognizer?>(null)
        val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun destroyRecognizerOnce() {
            if (!destroyed.compareAndSet(false, true)) return
            val r = recognizerRef.getAndSet(null) ?: return
            try {
                r.destroy()
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "recognizer destroy threw: ${e.message}")
            }
        }

        mainHandler.post {
            val recognizer = try {
                // [T-android-speech-error10] Prefer the on-device recognizer
                // on S+ (mirrors SystemSpeechRecognitionEngine): it avoids
                // the shared cloud recognizer service whose request quota
                // the orphaned sessions were exhausting. Fall back to the
                // regular recognizer when on-device isn't supported.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } catch (_: Throwable) {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Throwable) {
                resultRef.set(
                    JSONObject().put("error", "recognizer_creation_failed")
                        .put("message", "Could not create SpeechRecognizer: ${e.message}")
                        .toString(),
                )
                latch.countDown()
                return@post
            }
            recognizerRef.set(recognizer)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
                if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confs = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val json = JSONObject()
                    json.put("text", matches?.firstOrNull() ?: "")
                    if (matches != null && matches.size > 1) {
                        val alts = JSONArray()
                        for (i in matches.indices) {
                            val a = JSONObject().put("text", matches[i])
                            if (confs != null && i < confs.size) a.put("confidence", String.format("%.2f", confs[i]))
                            alts.put(a)
                        }
                        json.put("alternatives", alts)
                    }
                    resultRef.set(json.toString(2))
                    destroyRecognizerOnce()
                    latch.countDown()
                }

                override fun onError(error: Int) {
                    val requestedLang = language ?: "default"
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions (RECORD_AUDIO required)"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                        // [T-android-speech-error10] Codes below use literal
                        // ints: their constants need API 31/33 while we
                        // compile against older listener call sites too.
                        // 10 = ERROR_TOO_MANY_REQUESTS (API 31)
                        10 -> "Too many recognition requests — the system recognizer service is saturated (orphaned sessions?)"
                        // 11 = ERROR_SERVER_DISCONNECTED (API 31)
                        11 -> "Recognition service disconnected"
                        // 12 = ERROR_LANGUAGE_NOT_SUPPORTED (API 33)
                        12 -> "Language not supported by the recognizer (requested: $requestedLang)"
                        // 13 = ERROR_LANGUAGE_UNAVAILABLE (API 33)
                        13 -> "Language not currently available — model may need downloading (requested: $requestedLang)"
                        else -> "Unknown error ($error)"
                    }
                    resultRef.set(JSONObject().put("error", msg).toString(2))
                    destroyRecognizerOnce()
                    latch.countDown()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        }

        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            // [T-android-speech-error10] The timeout path previously leaked
            // the recognizer (see destroyRecognizerOnce above). Destroy on
            // the main handler — the thread that created it.
            mainHandler.post { destroyRecognizerOnce() }
            return """{"error": "speech recognition timed out after ${timeoutSec}s"}"""
        }
        return resultRef.get()
    }

    companion object {
        private const val TAG = "SpeechOffload"
        private val ENGINE_CHOICES = listOf("system", "provider", "auto")
        private const val HELP = """android-speech — speech recognition (audio → text)

Usage:
  android-speech <command> [options]

COMMANDS:
  transcribe   Transcribe from system mic (file source coming later)
  listen       Alias for transcribe (legacy)
  languages    List available recognition locales
  status       Check speech recognition availability

OPTIONS:
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only data field
  --source <mic|path>  Source: system mic (default) or audio file path
  --duration <sec>     Recording duration for mic source (default: 30)
  --language <locale>  Recognition locale (e.g. en-US, zh-CN)
  --max <N>            Maximum number of result candidates (default: 3)
  --engine <e>         system (default) | provider | auto. `provider` needs the
                       in-app voice input pipeline, so the CLI answers
                       `error: engine_not_available_in_cli` and points at
                       `system` / `auto`; the reply always carries
                       requested_engine + engine_used.

EXAMPLES:
  android-speech transcribe --duration 5
  android-speech transcribe --source mic --language zh-CN --duration 15
  android-speech transcribe --source /var/minis/attachments/meeting.m4a
  android-speech languages
  android-speech languages --language en
  android-speech status

Notes:
  - Requires RECORD_AUDIO permission for mic transcription.
  - Audio-file transcription returns `error: not_supported` on Android
    today; the path is validated and reported back so future engine work
    can wire through without changing the CLI.
"""
    }
}
