package com.openminis.app.provider.voice

import com.openminis.app.ProductionSources
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-zhipu-tts] + [T-android-tts-engine-visibility] +
 * [T-android-tts-init-timeout] — Android read-aloud silent on OPPO PEHM00 with a
 * Zhipu `glm-tts` entry: the Zhipu request 404'd, and the system fallback could
 * not see the user's TTS engine.
 *
 * The Zhipu wire details are UNVERIFIED against a real key (see the provider's
 * KDoc); these tests pin the request this code builds, not Zhipu's acceptance
 * of it.
 */
class ZhipuVoiceProviderTest {

    private fun body(req: okhttp3.Request): JSONObject {
        val buf = Buffer()
        req.body!!.writeTo(buf)
        return JSONObject(buf.readUtf8())
    }

    private fun speech(base: String, request: VoiceOutputRequest): okhttp3.Request =
        ZhipuVoiceProvider("zp", base, "sk-test").buildVoiceOutputRequest(request)

    @Test
    fun `the reported base no longer composes the 404 path`() {
        val req = speech("https://open.bigmodel.cn/api/paas/v4", VoiceOutputRequest(input = "你好", model = "glm-tts"))
        assertEquals("https://open.bigmodel.cn/api/paas/v4/audio/speech", req.url.toString())
        // What the generic provider built for the same base.
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/v1/audio/speech",
            VoiceProvider("g", "https://open.bigmodel.cn/api/paas/v4", "sk").composedUrlString("/v1/audio/speech"),
        )
    }

    @Test
    fun `every base variant resolves to the same speech endpoint`() {
        for (base in listOf(
            "https://open.bigmodel.cn",
            "https://open.bigmodel.cn/",
            "https://open.bigmodel.cn/api/paas/v4/",
            "https://open.bigmodel.cn/api/coding/paas/v4",
            "https://open.bigmodel.cn/v4",
        )) {
            assertEquals(base, "https://open.bigmodel.cn/api/paas/v4/audio/speech", speech(base, VoiceOutputRequest(input = "x")).url.toString())
        }
        assertEquals(
            "https://api.z.ai/api/paas/v4/audio/speech",
            speech("https://api.z.ai/api/paas/v4", VoiceOutputRequest(input = "x")).url.toString(),
        )
        assertEquals(
            "a non-default port survives",
            "http://10.0.0.2:8080/api/paas/v4/audio/speech",
            speech("http://10.0.0.2:8080/api/paas/v4", VoiceOutputRequest(input = "x")).url.toString(),
        )
    }

    @Test
    fun `the model id passed as voice falls back to the default voice`() {
        // Read-aloud and Quick Test send voice = model id.
        val b = body(speech("https://open.bigmodel.cn/api/paas/v4", VoiceOutputRequest(input = "hi", model = "glm-tts", voice = "glm-tts")))
        assertEquals("glm-tts", b.getString("model"))
        assertEquals("tongtong", b.getString("voice"))
        assertEquals("hi", b.getString("input"))
    }

    @Test
    fun `a real voice passes through, a blank one falls back`() {
        val real = body(speech("https://open.bigmodel.cn", VoiceOutputRequest(input = "hi", model = "cogtts", voice = "chuichui")))
        assertEquals("chuichui", real.getString("voice"))
        val blank = body(speech("https://open.bigmodel.cn", VoiceOutputRequest(input = "hi", voice = " ")))
        assertEquals("tongtong", blank.getString("voice"))
        assertEquals("cogtts", blank.getString("model"))
    }

    @Test
    fun `always asks for wav, never the mp3 default`() {
        val b = body(speech("https://open.bigmodel.cn", VoiceOutputRequest(input = "hi", responseFormat = VoiceOutputFormat.MP3)))
        assertEquals("wav", b.getString("response_format"))
    }

    @Test
    fun `bearer auth and the transcription path`() {
        val p = ZhipuVoiceProvider("zp", "https://open.bigmodel.cn/api/paas/v4", "sk-test")
        assertEquals("Bearer sk-test", p.buildVoiceOutputRequest(VoiceOutputRequest(input = "x")).header("Authorization"))
        assertEquals("https://open.bigmodel.cn/api/paas/v4/audio/transcriptions", p.composedUrlString(p.voiceInputEndpointPath()))
    }

    @Test
    fun `routing matches Zhipu bases and not the neighbours`() {
        for (b in listOf("https://open.bigmodel.cn/api/paas/v4", "https://api.z.ai/api/paas/v4", "https://zhipu.example.com/v1")) {
            assertTrue(b, ZhipuVoiceProvider.matches(b))
        }
        // Doubao's ASR MODEL is named "bigmodel", but its base is bytedance.
        for (b in listOf("https://openspeech.bytedance.com", "https://dashscope.aliyuncs.com/compatible-mode", "https://api.openai.com")) {
            assertFalse(b, ZhipuVoiceProvider.matches(b))
        }
        val factory = ProductionSources.read("provider/voice/VoiceProviderFactory.kt")
        assertTrue(factory.contains("ZhipuVoiceProvider.matches(normalizedBase) ->"))
    }

    // ---- system engine (P0) --------------------------------------------------

    @Test
    fun `the manifest can see TTS engines`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml", "src/android/app/src/main/AndroidManifest.xml")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queries.contains("<action android:name=\"android.intent.action.TTS_SERVICE\" />"))
    }

    @Test
    fun `android-speak waits the manager's budget and lists engines through PackageManager`() {
        val src = ProductionSources.read("sandbox/offload/SpeakOffloadHandler.kt")
        assertFalse("no 2 s wait left", src.contains("waitForInit(2_000)"))
        assertEquals(2, Regex("""waitForInit\(TextToSpeechManager\.INIT_TIMEOUT_MS\)""").findAll(src).count())
        val probe = src.substringAfter("private fun probeEngineNames()").substringBefore("\n    }\n")
        assertTrue(probe.contains("Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)"))
        assertTrue(probe.contains("queryIntentServices"))
        assertFalse("no throw-away TextToSpeech probe", probe.contains("TextToSpeech(context"))
    }
}
