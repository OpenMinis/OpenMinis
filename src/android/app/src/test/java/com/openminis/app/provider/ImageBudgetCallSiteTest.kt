package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T09] The image budget must apply at every send entry, prefer this turn's
 * attachments over history, degrade the eldest to text (never drop silently),
 * measure on-wire base64, and never let an image reach the request as a text
 * part carrying pixels (018e074fc, ad410ec1e, 74661a2eb, 5f0326197,
 * e1e6d4457, 02e254721, 376ff581b; issues #352 #323).
 *
 * ImageBudgetBase64Test pins the arithmetic. This file pins WHO calls it and
 * WHAT the wire looks like afterwards. On Android the main send, the
 * auto-retry and the group fallback all re-enter one `while (!collectDone)`
 * loop around a single `currentProvider.streamMessage(...)` call — so "three
 * entries" collapse to one call site, and the guard is that the call site
 * still wraps the outgoing history in `applyRequestImageBudget`.
 */
class ImageBudgetCallSiteTest {

    private val mb = 1024 * 1024

    private fun img(rawBytes: Int, path: String? = null) =
        ImageBudget.BudgetImage(ByteArray(rawBytes), path, "image/jpeg")

    // ── call sites (source grep) ───────────────────────────────────────────

    @Test
    fun `every request that leaves runAgentLoop passes through applyRequestImageBudget`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val loopStart = vm.indexOf("suspend fun runAgentLoop")
        assertTrue("runAgentLoop not found", loopStart > 0)
        val loop = vm.substring(loopStart)
        // Exactly one provider stream call inside the loop — retry and fallback
        // re-enter it rather than building their own request.
        val streamCalls = Regex("currentProvider\\.streamMessage\\(").findAll(loop).count()
        assertEquals("expected a single streamMessage call site in runAgentLoop", 1, streamCalls)
        val callIdx = loop.indexOf("currentProvider.streamMessage(")
        val window = loop.substring(callIdx, callIdx + 700)
        assertTrue("outgoing history must be budgeted", window.contains("applyRequestImageBudget("))
        assertTrue("budget wraps the effective history", window.contains("effectiveAgentHistory()"))
        assertTrue("byte audit sits outermost", window.contains("auditOutgoingPayload("))
        // Retry + fallback paths re-enter the same loop.
        assertTrue(loop.contains("while (!collectDone)"))
        assertTrue(loop.contains("retryAttempt = 0"))
        assertTrue(loop.contains("currentProvider = next"))
        // The budget function itself still exists and emits placeholders, not drops.
        assertTrue(vm.contains("private fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage>"))
        assertTrue(vm.contains("ImageBudget.elidedImagePlaceholder(path)"))
    }

    // ── priority: this turn's attachments beat history ─────────────────────

    @Test
    fun `history 6 plus this turn 2 over budget - the eldest history images degrade first`() {
        // 8 x 4 MB raw = 5.33 MB on the wire each, clamped to the 5 MB per-image
        // cap -> 40 MB total against a 25 MB request cap: 5 fit, 3 must go.
        val history = (0 until 6).map { img(4 * mb, "/var/minis/offloads/tools/h$it.jpg") }
        val current = (0 until 2).map { img(4 * mb, "/var/minis/attachments/uploads/c$it.jpg") }
        val all = history + current
        val plan = ImageBudget.planRequestBudget(all)

        assertEquals(3, plan.droppedCount)
        val droppedIdx = all.indices.filter { ImageBudget.ImagePartId.of(all[it].data) in plan.droppedIds }
        assertEquals("the three ELDEST history images are the ones elided", listOf(0, 1, 2), droppedIdx)
        for (c in current) {
            assertFalse("this turn's attachment must never be elided ahead of history",
                ImageBudget.ImagePartId.of(c.data) in plan.droppedIds)
        }
        // Degraded, not dropped: each elided image maps to its path for the placeholder.
        for (i in droppedIdx) {
            val id = ImageBudget.ImagePartId.of(all[i].data)
            assertEquals(all[i].linuxPath, plan.droppedPaths[id])
            assertTrue(ImageBudget.elidedImagePlaceholder(plan.droppedPaths[id]).contains(all[i].linuxPath!!))
        }
    }

    // ── identity: same bytes in history and this turn ──────────────────────

    @Test
    fun `the same image instance appearing twice is one identity - never half kept`() {
        val shared = img(4 * mb, "/var/minis/attachments/uploads/same.jpg")
        // Under budget: nothing dropped, even though the instance is listed twice.
        val small = ImageBudget.planRequestBudget(listOf(shared, img(1 * mb), shared))
        assertEquals(0, small.droppedCount)

        // Over budget: the shared identity is elided ONCE (one id), never counted
        // as two separate drops, and both occurrences resolve to the same id.
        val filler = (0 until 5).map { img(4 * mb) } // 5 x 5 MB = the whole cap
        val plan = ImageBudget.planRequestBudget(listOf(shared) + filler + listOf(shared))
        val sharedId = ImageBudget.ImagePartId.of(shared.data)
        assertEquals(sharedId, ImageBudget.ImagePartId.of(shared.data))
        assertTrue(plan.droppedIds.size == plan.droppedCount)
        assertEquals("one id per instance, not per occurrence",
            plan.droppedIds.size, plan.droppedIds.toSet().size)
    }

    // ── on-wire measurement ────────────────────────────────────────────────

    @Test
    fun `budget is judged on base64 size not decoded bytes`() {
        // 6 x 3.3 MB raw = 19.8 MB raw (under 25 MB) but 26.4 MB base64 (over).
        // Each image encodes to 4.4 MB — below the 5 MB per-image clamp, so the
        // clamp cannot mask the raw-vs-wire difference.
        val raw = (3.3 * mb).toInt()
        val five = (0 until 6).map { img(raw) }
        val rawTotal = five.sumOf { it.data.size.toLong() }
        assertTrue("fixture sanity: raw total under the cap", rawTotal < ImageBudget.MAX_REQUEST_BYTES)
        val wireTotal = five.sumOf { ImageBudget.estimatedBase64Length(it.data.size) }
        assertTrue("fixture sanity: wire total over the cap", wireTotal > ImageBudget.MAX_REQUEST_BYTES)
        assertTrue(ImageBudget.estimatedBase64Length(raw) < ImageBudget.MAX_PER_IMAGE_BYTES)

        val plan = ImageBudget.planRequestBudget(five)
        assertEquals("one image must be elided on the wire measurement", 1, plan.droppedCount)
        assertTrue(plan.keptBytes <= ImageBudget.MAX_REQUEST_BYTES)
        // The 1 MB-class image is never a candidate for text — it is well under
        // every cap and rides as pixels.
        val one = ImageBudget.planRequestBudget(listOf(img(1 * mb)))
        assertEquals(0, one.droppedCount)
    }

    // ── wire shape: pixels are never a text part ───────────────────────────

    private fun provider(model: LLMModel) = OpenAIProvider(
        apiKey = "test-key", model = model, basePath = "https://example.invalid/v1",
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    /** Every text-typed part in a structured user message, across the body. */
    private fun textParts(body: JSONObject): List<String> {
        val out = mutableListOf<String>()
        for (m in body.getJSONArray("messages").objects()) {
            val c = m.opt("content")
            when (c) {
                is String -> out.add(c)
                is JSONArray -> for (p in c.objects()) if (p.optString("type") == "text") out.add(p.getString("text"))
            }
        }
        return out
    }

    @Test
    fun `an image part never reaches the request as a text part`() {
        val pixels = ByteArray(1 * mb) { (it % 251).toByte() }
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = listOf(
                    AgentContentPart.Text("look"),
                    AgentContentPart.ImageData(pixels, "image/jpeg", linuxPath = "/var/minis/attachments/uploads/a.jpg"),
                ),
            ),
        )
        // Vision model: the pixels go as an image_url block.
        val vision = LLMModel("v", "V", "test", inputModalities = listOf("text", "image"))
        val body = provider(vision).buildRequestBody(history, null, 256, false, null, emptyList())
        val userContent = body.getJSONArray("messages").objects().last().getJSONArray("content").objects()
        assertTrue(userContent.any { it.optString("type") == "image_url" })
        for (t in textParts(body)) {
            assertTrue("a text part must never carry the image payload (len=${t.length})", t.length < 4096)
            assertFalse(t.contains("base64,"))
        }

        // Text-only model: a SHORT placeholder replaces the pixels — still not the bytes.
        val text = LLMModel("t", "T", "test", inputModalities = listOf("text"))
        val body2 = provider(text).buildRequestBody(history, null, 256, false, null, emptyList())
        val content2 = body2.getJSONArray("messages").objects().last().getJSONArray("content").objects()
        assertFalse(content2.any { it.optString("type") == "image_url" })
        for (t in textParts(body2)) assertTrue(t.length < 4096)
        assertTrue(content2.any { it.optString("type") == "text" && it.getString("text").contains("does not support vision") })

        // Elided-by-budget placeholder is likewise short and names the path.
        val ph = ImageBudget.elidedImagePlaceholder("/var/minis/attachments/uploads/a.jpg")
        assertTrue(ph.length < 512)
        assertTrue(ph.contains("read_image"))
    }
}
