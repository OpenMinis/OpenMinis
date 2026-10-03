package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.speech.TextToSpeechManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * android-speak — speak text aloud via Android TTS.
 *
 * T60: re-aligned with iOS `apple-speak` (NativeOffloads/SpeakOffload.m).
 * Subcommand model:
 *
 *   android-speak speak <text> [--voice tag] [--rate F] [--pitch F] [--volume F]
 *   android-speak stop
 *   android-speak voices [--language prefix]
 *
 * Back-compat: the legacy positional form (`android-speak Hello world`) still
 * works because the dispatcher falls through to `speak` when the first
 * positional isn't a known subcommand keyword. The legacy `--stop` flag is
 * also still honoured so any agent prompt that learned the old form before
 * this commit doesn't regress.
 *
 * Init order: TTS initialisation is asynchronous, so the first `speak` call
 * after boot can race and silently no-op on slower devices. We wait up to
 * [TextToSpeechManager.INIT_TIMEOUT_MS] (the manager's own budget) for init,
 * and if still unready return a structured error so the model knows
 * speech didn't actually play.
 *
 * Voice resolution: iOS treats `--voice` as a BCP-47 language tag (passed to
 * `AVSpeechSynthesisVoice voiceWithLanguage:`). Android's TTS API exposes
 * voices by identifier AND by Locale; we accept the iOS-style language tag
 * via `Locale.forLanguageTag(...)` for cross-platform parity, and additionally
 * try to match an exact voice identifier when `voices` enumeration finds one.
 */
class SpeakOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private val tts = TextToSpeechManager().also { it.init(context) }

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)

        // Legacy `--stop` flag — pre-T60 callers used it as a synonym for
        // the `stop` subcommand. Honour it before the subcommand dispatch
        // so old prompts keep working.
        if (args.hasFlag("stop")) return cmdStop(args)
        // Legacy `--status` is dropped from the iOS spec but kept on
        // Android as a debug aid (no iOS analogue). Same back-compat.
        if (args.hasFlag("status")) return buildStatus(args)

        val sub = args.positional.firstOrNull()
        return try {
            when (sub) {
                "speak" -> cmdSpeak(args, dropFirstPositional = true)
                "stop" -> cmdStop(args)
                "voices" -> cmdVoices(args)
                null -> NativeOffloadResult(2, "android-speak: missing command\n$HELP")
                else -> {
                    // Backwards-compat: treat the whole positional list as
                    // free-form text. Mirrors the pre-T60 invocation
                    // `android-speak Hello world`.
                    cmdSpeak(args, dropFirstPositional = false)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            val body = JSONObject()
                .put("error", "internal")
                .put("message", e.message ?: "unknown")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
    }

    // ── Subcommands ──────────────────────────────────────────────────────────

    private fun cmdSpeak(args: OffloadArgs, dropFirstPositional: Boolean): NativeOffloadResult {
        val textParts = if (dropFirstPositional) args.positional.drop(1) else args.positional
        val text = (args.get("text") ?: textParts.joinToString(" ")).trim()
        if (text.isBlank()) {
            return NativeOffloadResult(2, "android-speak: missing <text>\n$HELP")
        }
        // [T-android-tts-init-timeout] Wait as long as the manager itself does:
        // 2 s gave up before TextToSpeechManager's own 4 s budget, so a slow
        // engine cold start (OPPO ColorOS) was reported as "no engine".
        if (!waitForInit(TextToSpeechManager.INIT_TIMEOUT_MS)) {
            val body = JSONObject()
                .put("error", "tts_unavailable")
                .put(
                    "message",
                    "No usable text-to-speech engine is installed on this device " +
                        "(common on Huawei HMS-only devices and some stripped China ROMs). " +
                        "Ask the user to install a TTS engine — on most devices " +
                        "'Google Text-to-speech' from the Play Store or an OEM equivalent works.",
                )
                .put("available_engines", JSONArray(probeEngineNames()))
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }

        // Voice / language: --voice takes precedence and is treated as a
        // BCP-47 language tag (matches iOS). Empty / invalid tags fall
        // through to the auto-detect path inside speak().
        val voiceTag = args.get("voice")
        if (!voiceTag.isNullOrBlank()) {
            tts.setLanguage(Locale.forLanguageTag(voiceTag))
        }

        args.getDouble("rate")?.let { tts.speechRate = it.toFloat() }
        args.getDouble("pitch")?.let { tts.speechPitch = it.toFloat() }
        args.getDouble("volume")?.let { tts.speechVolume = it.toFloat() }

        tts.speak(text)
        val preview = if (text.length > 100) text.take(100) + "..." else text
        val body = JSONObject()
            .put("text", preview)
            .put("voice", voiceTag ?: "auto")
            .put("rate", tts.speechRate.toDouble())
            .put("pitch", tts.speechPitch.toDouble())
            .put("characters", text.length)
            .put("status", "speaking")
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    private fun cmdStop(args: OffloadArgs): NativeOffloadResult {
        tts.stop()
        val body = JSONObject().put("status", "stopped").toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    /**
     * List installed voices. iOS returns id/name/language/quality/gender for
     * each `AVSpeechSynthesisVoice`. Android `TextToSpeech.voices` (API 21+)
     * exposes equivalent fields:
     *   - `Voice.name`            → both id+name (Android doesn't separate)
     *   - `Voice.locale.toLanguageTag()` → language
     *   - `Voice.quality`         → mapped to default/enhanced/premium
     *   - `Voice.features`        → no gender field, marked "unspecified"
     */
    private fun cmdVoices(args: OffloadArgs): NativeOffloadResult {
        if (!waitForInit(TextToSpeechManager.INIT_TIMEOUT_MS)) {
            val body = JSONObject()
                .put("error", "tts_unavailable")
                .put("voices", JSONArray())
                .put("count", 0)
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
        }
        val languagePrefix = args.get("language")
        val items = JSONArray()
        // Pull voices through a fresh probe — the long-lived
        // [TextToSpeechManager] doesn't expose them, and a probe is cheap.
        val probe = TextToSpeech(context.applicationContext, null)
        try {
            // Voices are enumerable as soon as the engine is installed; a
            // 200ms post-construction settle is enough on every device I've
            // tested. waitForInit above already established this engine is
            // alive globally, so the probe constructor inheriting the same
            // engine binding shouldn't need a long wait.
            Thread.sleep(200)
            val voices: Set<Voice>? = probe.voices
            voices?.forEach { v ->
                val tag = v.locale?.toLanguageTag() ?: ""
                if (!languagePrefix.isNullOrBlank() && !tag.startsWith(languagePrefix)) {
                    return@forEach
                }
                val quality = when (v.quality) {
                    Voice.QUALITY_VERY_HIGH, Voice.QUALITY_HIGH -> "premium"
                    Voice.QUALITY_NORMAL -> "enhanced"
                    else -> "default"
                }
                items.put(
                    JSONObject()
                        .put("id", v.name ?: "")
                        .put("name", v.name ?: "")
                        .put("language", tag)
                        .put("quality", quality)
                        .put("gender", "unspecified"),
                )
            }
        } finally {
            try { probe.shutdown() } catch (_: Throwable) {}
        }
        val body = JSONObject()
            .put("voices", items)
            .put("count", items.length())
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, args) + "\n")
    }

    // ── Diagnostics (Android-only, no iOS analogue) ──────────────────────────

    private fun buildStatus(args: OffloadArgs): NativeOffloadResult {
        val engines = probeEngineNames()
        val ready = waitForInit(500)
        val body = JSONObject()
            .put("ready", ready)
            .put("speaking", tts.isSpeaking.value)
            .put("rate", tts.speechRate.toDouble())
            .put("pitch", tts.speechPitch.toDouble())
            .put("available_engines", JSONArray(engines))
        return NativeOffloadResult(0, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    private fun waitForInit(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!tts.isInitialized && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        return tts.isInitialized
    }

    /**
     * Installed TTS engines (package names), best effort.
     *
     * [T-android-tts-engine-visibility] A direct PackageManager query for
     * TTS_SERVICE — the same query TextToSpeech.getEngines() makes, without
     * constructing a throw-away TextToSpeech that starts (and then abandons)
     * an engine binding just to read a list. Either way the result is subject
     * to package visibility: it is only complete because the manifest now
     * declares a TTS_SERVICE <queries> entry. Before that, a user-installed
     * engine was filtered out and this reported `available_engines: []` on a
     * device that had one.
     */
    private fun probeEngineNames(): List<String> = try {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        resolved.mapNotNull { it.serviceInfo?.packageName }.distinct()
    } catch (_: Throwable) {
        emptyList()
    }

    @Suppress("unused")
    private val androidApiLevel: Int = Build.VERSION.SDK_INT

    companion object {
        private const val TAG = "SpeakOffload"
        private const val HELP = """android-speak — speak text aloud via Android TTS

Usage:
  android-speak <command> [options]

COMMANDS:
  speak       Synthesize speech from text
  voices      List available voices
  stop        Stop current speech

COMMON OPTIONS:
  --help, -h           Show this help message
  --compact            Minimize JSON output
  -q, --quiet          Output only data field

SPEAK OPTIONS:
  --text <string>      Text to speak (positional also accepted)
  --voice <tag>        BCP-47 language tag (e.g. en-US, zh-CN)
  --rate <0.1-3.0>     Speech rate (default: 1.0)
  --pitch <0.5-2.0>    Pitch multiplier (default: 1.0)
  --volume <0.0-1.0>   Volume (default: 1.0)

VOICES OPTIONS:
  --language <prefix>  Filter by language prefix (e.g. "en")

EXAMPLES:
  android-speak speak "Hello world"
  android-speak speak --text "Bonjour" --voice fr-FR --rate 0.5
  android-speak voices --language en
  android-speak stop

Returns structured JSON on errors:
  {"error":"tts_unavailable","message":"...","available_engines":[...]}
"""
    }
}
