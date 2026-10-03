package com.openminis.app.provider

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-image-budget-base64] The budget caps count bytes that go on the wire, and
 * images go base64-inlined. These pin the conversion and the two places that
 * consume it, because the failure they guard against is silent: a budget
 * policed in raw bytes still "passes" while the request is 1.333x over and the
 * gateway rejects it with request_too_large.
 */
class ImageBudgetBase64Test {

    /**
     * The estimate must never come in UNDER what Base64 actually produces —
     * that is the direction that overshoots the cap. Checked against the real
     * encoder across every remainder class of 3, since padding is exactly
     * where an off-by-one would hide.
     *
     * Uses `java.util.Base64`, not `android.util.Base64`: this module runs
     * unit tests with `isReturnDefaultValues = true`, so the android stub
     * would return 0 and the assertion would pass while testing nothing.
     */
    @Test
    fun `estimate matches the real encoder for every length mod 3`() {
        val encoder = Base64.getEncoder()
        for (n in 0..64) {
            val raw = ByteArray(n) { it.toByte() }
            val actual = encoder.encodeToString(raw).length.toLong()
            val estimated = ImageBudget.estimatedBase64Length(n)
            assertEquals("n=$n", actual, estimated)
        }
    }

    @Test
    fun `estimate is the classic 4-over-3 expansion`() {
        assertEquals(4L, ImageBudget.estimatedBase64Length(3))
        assertEquals(4L, ImageBudget.estimatedBase64Length(1))
        assertEquals(8L, ImageBudget.estimatedBase64Length(4))
        // 3 MB raw -> 4 MB encoded, the ratio the caps now account for.
        assertEquals(4L * 1024 * 1024, ImageBudget.estimatedBase64Length(3L * 1024 * 1024))
    }

    /** Long overload exists so request-level tallies cannot overflow Int. */
    @Test
    fun `estimate does not overflow past Int range`() {
        val huge = 3L * 1024 * 1024 * 1024 // 3 GB raw
        assertEquals(4L * 1024 * 1024 * 1024, ImageBudget.estimatedBase64Length(huge))
        assertTrue(ImageBudget.estimatedBase64Length(huge) > Int.MAX_VALUE)
    }

    /**
     * The raw compression target must encode to at most the per-image cap.
     * If this inverts, `compressUnderBudget`'s default silently produces
     * images that are individually over budget on the wire.
     */
    @Test
    fun `raw per-image target encodes within the per-image cap`() {
        val encoded = ImageBudget.estimatedBase64Length(ImageBudget.MAX_PER_IMAGE_RAW_BYTES)
        assertTrue(
            "raw target $encoded must fit ${ImageBudget.MAX_PER_IMAGE_BYTES}",
            encoded <= ImageBudget.MAX_PER_IMAGE_BYTES,
        )
    }

    /**
     * The regression itself, at request level: images whose RAW total sits
     * under the cap but whose ENCODED total does not. Before this change all
     * three were kept and the request went out ~1.333x over.
     */
    @Test
    fun `planRequestBudget drops images that only fit before base64`() {
        // Sized to sit BELOW the per-image cap so the clamp cannot mask the
        // difference: 3 MB raw encodes to 4 MB, both under the 5 MB ceiling.
        // 7 x 3 MB = 21 MB raw, which the old raw-basis reading kept whole;
        // encoded it is 28 MB, over the 25 MB request cap.
        val mb3 = 3 * 1024 * 1024
        val images = (1..7).map {
            ImageBudget.BudgetImage(ByteArray(mb3) { it.toByte() }, "/img$it.png", "image/png")
        }
        val plan = ImageBudget.planRequestBudget(images)
        assertTrue("expected at least one elision, got ${plan.droppedCount}", plan.droppedCount > 0)
        assertTrue(
            "kept ${plan.keptBytes} must stay within ${ImageBudget.MAX_REQUEST_BYTES}",
            plan.keptBytes <= ImageBudget.MAX_REQUEST_BYTES,
        )
        // Exactly the regression: a raw-byte tally of what survived would
        // still have had room, which is why the old basis let it through.
        val keptRaw = (images.size - plan.droppedCount).toLong() * mb3
        assertTrue(
            "raw tally $keptRaw would still look like it fits — that was the bug",
            keptRaw < ImageBudget.MAX_REQUEST_BYTES,
        )
    }

    /** Latest-first protection is unchanged by the new measurement basis. */
    @Test
    fun `planRequestBudget still protects the most recent images`() {
        // Same 3 MB sizing as above, and enough of them to force elision.
        val mb3 = 3 * 1024 * 1024
        val eldest = ByteArray(mb3) { 1 }
        val latest = ByteArray(mb3) { 9 }
        val middle = (2..6).map {
            ImageBudget.BudgetImage(ByteArray(mb3) { it.toByte() }, "/mid$it.png", "image/png")
        }
        val plan = ImageBudget.planRequestBudget(
            listOf(ImageBudget.BudgetImage(eldest, "/eldest.png", "image/png")) +
                middle +
                listOf(ImageBudget.BudgetImage(latest, "/latest.png", "image/png")),
        )
        assertTrue(
            "the newest image must survive",
            !plan.droppedIds.contains(ImageBudget.ImagePartId.of(latest)),
        )
        assertTrue(
            "the eldest is the one that goes",
            plan.droppedIds.contains(ImageBudget.ImagePartId.of(eldest)),
        )
    }

    /** A small set well under the cap must be untouched. */
    @Test
    fun `planRequestBudget keeps everything when comfortably under the cap`() {
        val small = ByteArray(64 * 1024) { 7 }
        val plan = ImageBudget.planRequestBudget(
            listOf(
                ImageBudget.BudgetImage(small, "/a.png", "image/png"),
                ImageBudget.BudgetImage(ByteArray(64 * 1024) { 8 }, "/b.png", "image/png"),
            ),
        )
        assertEquals(0, plan.droppedCount)
        assertTrue(plan.droppedIds.isEmpty())
    }
}
