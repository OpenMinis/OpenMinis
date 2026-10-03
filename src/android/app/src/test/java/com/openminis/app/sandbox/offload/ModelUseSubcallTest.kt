package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [T26] `minis-model-use run` sub-call: no default persona, one path resolver
 * for every file argument, audio blocks survive serialization, audio-output
 * models get no thinking config.
 *
 * Field reports: GH#103/#104/#107 (a "You are Minis" persona was injected when
 * `--system` was absent, and the model silently changed identity — reported
 * three times), GH#105/#108 (`--system-file` must resolve like `--input`),
 * GH#67 (input_audio blocks were dropped on the wire), GH#135/#280/#226 (TTS
 * models routed with a thinking config they reject).
 *
 * The handler itself (ModelUseOffloadHandler) needs a Context + repository, so
 * the argument → system-prompt decision is ported verbatim (source cited per
 * function) with a grep drift guard on the production file, while the
 * serialization half drives the REAL providers: OpenAIProvider's `internal`
 * body builders and GeminiProvider through a MockWebServer.
 */
class ModelUseSubcallTest {

    private val productionFile =
        File("src/main/java/com/openminis/app/sandbox/offload/ModelUseOffloadHandler.kt")
    private val src by lazy { productionFile.readText() }

    // ── Port of ModelUseOffloadHandler.cmdRun (system-prompt resolution) ──
    //
    //   val explicitSystem = args.get("system") ?: args.get("system-file")?.let { readLinuxPath(it) }
    //   ...
    //   val inlineSystem = parsed.filter { it.role == "system" }.joinToString("\n") { it.content }
    //   val systemPrompt = when {
    //       explicitSystem != null && inlineSystem.isNotEmpty() -> "$explicitSystem\n\n$inlineSystem"
    //       explicitSystem != null -> explicitSystem
    //       inlineSystem.isNotEmpty() -> inlineSystem
    //       else -> null
    //   }
    private fun explicitSystem(args: OffloadArgs, readLinuxPath: (String) -> String?): String? =
        args.get("system") ?: args.get("system-file")?.let { readLinuxPath(it) }

    private fun systemPrompt(explicitSystem: String?, inlineSystem: String): String? = when {
        explicitSystem != null && inlineSystem.isNotEmpty() -> "$explicitSystem\n\n$inlineSystem"
        explicitSystem != null -> explicitSystem
        inlineSystem.isNotEmpty() -> inlineSystem
        else -> null
    }

    private fun args(vararg argv: String) = OffloadArgs(listOf("run", *argv))

    // ============================================================ no default persona

    /** GH#103/#104/#107 — without `--system` the request carries NO system prompt at all. */
    @Test
    fun `no --system and no inline system message means no system prompt`() {
        val a = args("--model", "gpt-4o", "--input", "/var/minis/workspace/in.json")
        assertNull(explicitSystem(a) { error("no file read expected") })
        assertNull(systemPrompt(null, ""))
    }

    @Test
    fun `system prompt composition follows the documented precedence`() {
        assertEquals("only explicit", systemPrompt("only explicit", ""))
        assertEquals("only inline", systemPrompt(null, "only inline"))
        assertEquals("explicit\n\ninline", systemPrompt("explicit", "inline"))
        // --system beats --system-file; the file is not even read.
        val both = args("--model", "m", "--system", "inline text", "--system-file", "/x/sys.md")
        var reads = 0
        assertEquals("inline text", explicitSystem(both) { reads++; "FILE" })
        assertEquals(0, reads)
    }

    /** Drift guard: the production handler must not carry a default persona literal. */
    @Test
    fun `production handler injects no default persona`() {
        assertTrue(productionFile.exists())
        assertTrue(src.contains("""val explicitSystem = args.get("system") ?: args.get("system-file")?.let { readLinuxPath(it) }"""))
        assertTrue(src.contains("""explicitSystem != null && inlineSystem.isNotEmpty() -> "${'$'}explicitSystem\n\n${'$'}inlineSystem""""))
        assertTrue(src.contains("else -> null"))
        // The only "You are …" text is in the --help example, never in the request path.
        val bodyBeforeHelp = src.substringBefore("private val HELP", src)
        assertFalse("a default persona must never be injected: found 'You are Minis'", bodyBeforeHelp.contains("You are Minis"))
        assertFalse(bodyBeforeHelp.contains("你是 Minis"))
    }

    // ============================================================ one path resolver

    /**
     * GH#105/#108 — `--system-file` and `--input` (the Android spelling of the
     * prompt file) go through the SAME Linux-path resolver, so a relative or
     * `/var/minis/...` path works for both or for neither.
     */
    @Test
    fun `system-file and input parse to the same relative path and share one resolver`() {
        val a = args("--model", "m", "--system-file", "./x.md", "--input", "./x.md")
        assertEquals("./x.md", a.get("system-file"))
        assertEquals("./x.md", a.get("input"))
        val seen = mutableListOf<String>()
        explicitSystem(a) { seen += it; "SYS" }
        assertEquals(listOf("./x.md"), seen)
        // Drift guard: both file arguments resolve through readLinuxPath → PRootKernel.resolveHostPath.
        assertTrue(src.contains("""args.get("system-file")?.let { readLinuxPath(it) }"""))
        assertTrue(src.contains("""readLinuxPath(args.get("input")!!)"""))
        assertTrue(src.contains("PRootKernel.resolveHostPath(linuxPath)"))
        assertEquals("exactly one resolver definition", 1, Regex("""private fun readLinuxPath\(""").findAll(src).count())
    }

    @Test
    fun `equals-form and space-form flags resolve identically`() {
        val a = args("--model=m", "--system-file=./x.md")
        val b = args("--model", "m", "--system-file", "./x.md")
        assertEquals(a.get("system-file"), b.get("system-file"))
        assertEquals(a.get("model"), b.get("model"))
        assertEquals(listOf("run"), a.positional)
    }

    // ============================================================ audio blocks survive serialization

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun audioModel(id: String = "gpt-4o-audio-preview") = LLMModel(
        id = id, displayName = id, provider = "OpenAI",
        supportsReasoning = false,
        inputModalities = listOf("text", "audio"),
    )

    private val audioMessage = LLMMessage(
        LLMMessage.Role.USER,
        "Transcribe this audio",
        audioParts = listOf(LLMMessage.AudioPart(format = "wav", base64Data = "UklGRgAAAABXQVZF")),
    )

    private fun findPart(content: JSONArray, type: String): JSONObject? =
        (0 until content.length()).map { content.getJSONObject(it) }.firstOrNull { it.optString("type") == type }

    /** GH#67 — Chat Completions: the official `input_audio` block is on the wire, next to the text. */
    @Test
    fun `input_audio block appears in the chat completions body`() {
        val provider = OpenAIProvider(apiKey = "k", model = audioModel(), basePath = server.url("/v1").toString().trimEnd('/'))
        val body = provider.buildRequestBody(
            messages = listOf(audioMessage), systemPrompt = null, maxTokens = 512, stream = true,
            temperature = null, imageParts = emptyList(),
        )
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val audio = findPart(content, "input_audio") ?: error("input_audio block dropped: $body")
        assertEquals("UklGRgAAAABXQVZF", audio.getJSONObject("input_audio").getString("data"))
        assertEquals("wav", audio.getJSONObject("input_audio").getString("format"))
        assertEquals("Transcribe this audio", findPart(content, "text")!!.getString("text"))
        assertFalse("no system message was injected", body.getJSONArray("messages").getJSONObject(0).getString("role") == "system")
    }

    /** GH#67 — Responses: same nested `input_audio` shape, text rides as `input_text`. */
    @Test
    fun `input_audio block appears in the responses body`() {
        val provider = OpenAIProvider(apiKey = "k", model = audioModel(), basePath = server.url("/v1").toString().trimEnd('/'), useResponsesAPI = true)
        val body = provider.buildResponsesAPIBody(
            messages = listOf(audioMessage), systemPrompt = null, maxTokens = 512, stream = true,
        )
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        val audio = findPart(content, "input_audio") ?: error("input_audio block dropped: $body")
        assertEquals("UklGRgAAAABXQVZF", audio.getJSONObject("input_audio").getString("data"))
        assertEquals("wav", audio.getJSONObject("input_audio").getString("format"))
        assertEquals("Transcribe this audio", findPart(content, "input_text")!!.getString("text"))
        assertFalse("no instructions were injected", body.has("instructions"))
    }

    /** A system prompt, when the caller DID pass one, lands in the right place on both paths. */
    @Test
    fun `an explicit system prompt is the only system content on either path`() {
        val chat = OpenAIProvider(apiKey = "k", model = audioModel(), basePath = server.url("/v1").toString().trimEnd('/'))
            .buildRequestBody(listOf(audioMessage), "You are a poet", 512, true, null, emptyList())
        val first = chat.getJSONArray("messages").getJSONObject(0)
        assertEquals("system", first.getString("role"))
        assertEquals("You are a poet", first.getString("content"))
        val resp = OpenAIProvider(apiKey = "k", model = audioModel(), basePath = server.url("/v1").toString().trimEnd('/'), useResponsesAPI = true)
            .buildResponsesAPIBody(listOf(audioMessage), "You are a poet", 512, true)
        assertEquals("You are a poet", resp.getString("instructions"))
    }

    // ============================================================ audio_output models

    /**
     * GH#226/#135/#280 — an audio-output model must go out with NO thinking
     * config (it 400s on one), no system instruction, and `responseModalities`
     * set so the audio bytes actually come back.
     */
    @Test
    fun `audio_output gemini model gets no thinking config and asks for AUDIO`() = runBlocking {
        val tts = LLMModel("gemini-2.5-flash-preview-tts", "tts", "Google Gemini", outputModalities = listOf("audio"))
        val provider = GeminiProvider(apiKey = "k", model = tts, basePath = server.url("/").toString().trimEnd('/'))
        // Text reply on purpose: android.util.Base64 is a stub on the JVM, so an
        // inlineData reply cannot be decoded here. The request shape is the point.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""),
        )
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "say hello")), "Be brief", 512, thinkingLevel = ThinkingLevel.HIGH)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val config = body.getJSONObject("generationConfig")
        assertFalse("TTS models reject thinkingConfig: $body", config.has("thinkingConfig"))
        assertFalse("TTS models reject systemInstruction: $body", body.has("systemInstruction"))
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        assertTrue("the user's text still goes out as contents", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text") == "say hello")
    }

    /**
     * Drift guard for the CLI's modality precheck: an `--output` whose extension
     * implies audio must be refused for a model that does not declare
     * `audio_output`, and OpenAI-compatible TTS is reachable through the
     * documented `/v1/audio/speech` passthrough endpoint.
     */
    @Test
    fun `cli precheck and tts passthrough are still wired`() {
        assertTrue(src.contains("""isAudioExt(outputExt) -> "audio_output".takeIf { "audio" !in outputs }"""))
        assertTrue(src.contains(""""audio" in outputs -> config.put("responseModalities", JSONArray().put("AUDIO"))""") ||
            File("src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt").readText()
                .contains(""""audio" in outputs -> config.put("responseModalities", JSONArray().put("AUDIO"))"""))
        assertTrue("TTS routing must stay documented as the audio/speech endpoint", src.contains("/v1/audio/speech"))
        assertTrue(src.contains("modality_not_supported"))
    }
}
