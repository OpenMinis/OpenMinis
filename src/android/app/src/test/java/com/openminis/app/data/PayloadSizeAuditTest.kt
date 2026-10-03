package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-payload-size-audit] (GH#352) The audit must fire on the reported
 * failure and stay silent on everything ordinary.
 *
 * The false-positive half matters more than the true-positive half here: the
 * audit only writes a log line, so a miss costs a diagnostic, while noise would
 * train the next investigator to ignore it.
 */
class PayloadSizeAuditTest {

    /** Base64 of the reporter's 1,030,449-byte PNG. */
    private val gh352Bytes = (1_030_449 + 2) / 3 * 4

    @Test
    fun `the GH#352 image is flagged suspicious`() {
        // ~1.37 MB on the wire, estimated by the vision grid heuristic at a
        // couple of thousand tokens — the disagreement this exists to surface.
        val f = PayloadSizeAudit.audit(gh352Bytes, estimatedTokens = 1_792, contextWindowTokens = 1_048_576)
        assertTrue("1.37MB vs 1792 tokens must be suspicious", f.suspicious)
        assertEquals(gh352Bytes / 1_792, f.bytesPerToken)
    }

    @Test
    fun `the GH#352 image is flagged dangerous against a 1M window`() {
        val f = PayloadSizeAudit.audit(gh352Bytes, 1_792, contextWindowTokens = 1_048_576)
        assertTrue(
            "1.37MB as text would exceed half of a 1M window",
            f.dangerous,
        )
    }

    @Test
    fun `ordinary prose is never flagged`() {
        // ~3.5 bytes/token. Even a 500 KB document stays far below the ratio.
        val bytes = 500_000
        val f = PayloadSizeAudit.audit(bytes, estimatedTokens = bytes / 4, contextWindowTokens = 200_000)
        assertFalse("prose must not be suspicious, ratio=${f.bytesPerToken}", f.suspicious)
    }

    @Test
    fun `JSON-heavy tool input is never flagged`() {
        // Worst realistic text ratio, ~8 bytes/token — still 8x under the bar.
        val bytes = 200_000
        val f = PayloadSizeAudit.audit(bytes, estimatedTokens = bytes / 8, contextWindowTokens = 200_000)
        assertFalse("JSON must not be suspicious, ratio=${f.bytesPerToken}", f.suspicious)
    }

    @Test
    fun `a normal photo sent as a structured image block is not dangerous`() {
        // 200 KB JPEG → ~267 KB base64, ~1500 tokens. Suspicious by ratio (it
        // IS image bytes), but must not be called dangerous against a big
        // window — nothing should act on it.
        val f = PayloadSizeAudit.audit(267_000, 1_500, contextWindowTokens = 1_048_576)
        assertFalse("a normal photo must not be dangerous", f.dangerous)
    }

    @Test
    fun `small parts are never audited`() {
        // Below the floor the ratio is meaningless — a 100-byte part with a
        // 1-token estimate would otherwise read as 100 bytes/token.
        val f = PayloadSizeAudit.audit(100, 1, contextWindowTokens = 200_000)
        assertFalse(f.suspicious)
        assertFalse(f.dangerous)
        assertEquals(0, f.bytesPerToken)
    }

    @Test
    fun `an unknown window disables the danger test`() {
        // No window is no basis for calling anything dangerous.
        val f = PayloadSizeAudit.audit(gh352Bytes, 1_792, contextWindowTokens = 0)
        assertTrue("ratio still applies", f.suspicious)
        assertFalse("but danger needs a window", f.dangerous)
    }

    @Test
    fun `a zero token estimate does not divide by zero`() {
        val f = PayloadSizeAudit.audit(100_000, estimatedTokens = 0, contextWindowTokens = 200_000)
        assertEquals(100_000, f.bytesPerToken)
        assertTrue(f.suspicious)
    }

    @Test
    fun `the suspicious boundary is inclusive`() {
        val bytes = PayloadSizeAudit.MIN_BYTES_TO_AUDIT * 4
        val atBar = PayloadSizeAudit.audit(bytes, bytes / PayloadSizeAudit.SUSPICIOUS_BYTES_PER_TOKEN, 0)
        assertTrue("at the bar counts", atBar.suspicious)
        val underBar = PayloadSizeAudit.audit(bytes, bytes / (PayloadSizeAudit.SUSPICIOUS_BYTES_PER_TOKEN - 1), 0)
        assertFalse("just under does not", underBar.suspicious)
    }
}
