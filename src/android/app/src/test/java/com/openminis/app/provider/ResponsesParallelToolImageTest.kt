package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.ui.chat.ImageInputPreflight
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-responses-toolresult-image-split] Field report (zzz, deepseek-flash
 * over /v1/responses): the model ran read_image and shell_execute in parallel;
 * the next request came back `[400] No tool output found for tool call call_01_…`
 * with "this model may not support image input" appended.
 *
 * The Responses builder emitted read_image's image carrier (a `user` item) right
 * after call_00's output, i.e. BETWEEN the two outputs. The Chat Completions
 * builder had been fixed for exactly this in 030d059cb; the Responses one was
 * not. iOS buffers the carriers in both paths.
 *
 * These drive the real OpenAIProvider.buildResponsesAPIBody.
 */
class ResponsesParallelToolImageTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(32)

    private val vision = LLMModel(id = "deepseek-flash", displayName = "DeepSeek Flash", provider = "test",
        inputModalities = listOf("text", "image"))

    private fun provider() = OpenAIProvider(apiKey = "k", model = vision,
        basePath = "https://example.invalid/v1", useResponsesAPI = true)

    private fun toolUse(id: String, name: String) =
        AgentContentPart.ToolUse(id = id, name = name, input = JSONObject())

    private fun result(id: String, name: String, image: Boolean) = AgentContentPart.ToolResult(
        id = id, name = name, content = "ok $id",
        imageData = if (image) jpeg else null, imageMimeType = if (image) "image/jpeg" else null,
    )

    /** The reported turn: two parallel calls, the FIRST one returns an image. */
    private fun history(vararg results: AgentContentPart.ToolResult) = listOf(
        LLMMessage(LLMMessage.Role.USER, "can't this model read images?"),
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "",
            contentParts = results.map { toolUse(it.id, it.name) }),
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = results.toList()),
    )

    /** One token per input item: fc:<call>, out:<call>, img (a tool-image carrier), user, other. */
    private fun sequence(messages: List<LLMMessage>): List<String> {
        val input = provider().buildResponsesAPIBody(messages = messages, systemPrompt = null,
            maxTokens = 1024, stream = false).getJSONArray("input")
        return (0 until input.length()).map { i ->
            val item = input.getJSONObject(i)
            when (item.optString("type")) {
                "function_call" -> "fc:" + item.getString("call_id")
                "function_call_output" -> "out:" + item.getString("call_id")
                else -> if (item.optString("role") == "user" &&
                    item.optJSONArray("content")?.toString()?.contains("Image returned by") == true) "img" else "user"
            }
        }
    }

    /** Every output must come after its calls and before any carrier or other item. */
    private fun outputsContiguous(seq: List<String>): Boolean {
        val firstOut = seq.indexOfFirst { it.startsWith("out:") }
        val lastOut = seq.indexOfLast { it.startsWith("out:") }
        if (firstOut < 0) return true
        return seq.subList(firstOut, lastOut + 1).all { it.startsWith("out:") }
    }

    @Test fun `the reported turn keeps both outputs together, image after them`() {
        val seq = sequence(history(
            result("call_00_read", "read_image", image = true),
            result("call_01_shell", "shell_execute", image = false),
        ))
        assertTrue(seq.toString(), outputsContiguous(seq))
        assertEquals(seq.toString(), listOf("out:call_00_read", "out:call_01_shell", "img"),
            seq.dropWhile { !it.startsWith("out:") })
    }

    @Test fun `several image results all follow the whole run, in call order`() {
        val seq = sequence(history(
            result("call_00", "read_image", image = true),
            result("call_01", "shell_execute", image = false),
            result("call_02", "read_image", image = true),
        ))
        assertTrue(seq.toString(), outputsContiguous(seq))
        assertEquals(listOf("out:call_00", "out:call_01", "out:call_02", "img", "img"),
            seq.dropWhile { !it.startsWith("out:") })
    }

    @Test fun `a single image result is unchanged - output then its image`() {
        val seq = sequence(history(result("call_00", "read_image", image = true)))
        assertEquals(listOf("out:call_00", "img"), seq.dropWhile { !it.startsWith("out:") })
    }

    @Test fun `results without images produce no carrier`() {
        val seq = sequence(history(result("call_00", "a", false), result("call_01", "b", false)))
        assertEquals(listOf("out:call_00", "out:call_01"), seq.dropWhile { !it.startsWith("out:") })
    }

    // -- the misleading "may not support image input" hint --

    private fun err(detail: String) = LLMError.ProviderError(detail, httpStatus = 400)

    @Test fun `a tool pairing 400 does not get the image hint`() {
        assertFalse(ImageInputPreflight.isLikelyImageRejection(
            err("No tool output found for tool call call_01_nusBBf7vBtlUfhDKuPzS3578. (request_id: 041adf98)"), 1))
        assertFalse(ImageInputPreflight.isLikelyImageRejection(err("messages with role 'tool' must follow tool_calls"), 1))
    }

    @Test fun `a context length 400 does not get the image hint`() {
        assertFalse(ImageInputPreflight.isLikelyImageRejection(err("This model's maximum context length is 128000 tokens"), 2))
    }

    @Test fun `image-looking and generic 400s keep the hint`() {
        assertTrue(ImageInputPreflight.isLikelyImageRejection(
            err("input[0].image[0]: You have uploaded an unsupported image."), 1))
        assertTrue(ImageInputPreflight.isLikelyImageRejection(err("请求参数或所选模型能力不受支持"), 1))
        assertTrue(ImageInputPreflight.isLikelyImageRejection(err("bad request"), 1))
    }
}
