package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-offload-readback] (GH#343) Recognising a `file_read` of a file we
 * already offloaded, so it is re-stubbed against the existing file instead of
 * being copied to a new one.
 *
 * The defect: the offload scan skips a part that already
 * `startsWith(OFFLOADED_PREFIX)`, but `FileReadTool` prepends
 * `[<path> | <n> bytes | <m> lines | showing a-b of c]`, so a readback starts
 * with `[/var/minis/offloads/…` and the check misses. The content is offloaded
 * again into a second file holding identical bytes.
 *
 * Bounded, not infinite: the replacement stub is ~130 chars, under the scan's
 * 500-char floor, so the copy is not re-offloaded in turn. One redundant file
 * per readback.
 */
class OffloadReadbackTest {

    private val dir = ContextOffload.LINUX_OFFLOADS_DIR

    @Test
    fun `a plain file_read of an offloaded file is recognised`() {
        val content = "[$dir/tools/shell_ab12cd.txt | 456789 bytes | 1200 lines | " +
            "showing 1-1200 of 1200]\nthe original large output…"
        assertEquals("$dir/tools/shell_ab12cd.txt", ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `the truncated header variant is recognised`() {
        // FileReadTool appends `| truncated at N chars, next_offset=M` when the
        // read was capped — the path is still the first field.
        val content = "[$dir/tools/shell_ab12cd.txt | 999999 bytes | 5000 lines | " +
            "showing 1-800 of 5000 | truncated at 60000 chars, next_offset=801]\nbody"
        assertEquals("$dir/tools/shell_ab12cd.txt", ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `a file_read of an ordinary file is not a readback`() {
        // The whole point of keeping this narrow: a normal large read must
        // still be offloaded, or it pins its bytes in context forever.
        val content = "[/var/minis/workspace/data.csv | 900000 bytes | 20000 lines | " +
            "showing 1-20000 of 20000]\nrows…"
        assertNull(ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `a sibling directory sharing the prefix does not match`() {
        // Guards the anchoring: `/var/minis/offloads-backup` must not pass just
        // because it starts with the same characters.
        val content = "[${dir}-backup/tools/x.txt | 10 bytes | 1 lines | showing 1-1 of 1]\nx"
        assertNull(ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `the offloads dir itself is accepted`() {
        val content = "[$dir | 10 bytes | 1 lines | showing 1-1 of 1]\nx"
        assertEquals(dir, ContextOffload.offloadReadbackPath(content))
    }

    @Test
    fun `content that is not a file_read header is not a readback`() {
        assertNull(ContextOffload.offloadReadbackPath("just some tool output"))
        assertNull(ContextOffload.offloadReadbackPath(""))
        // An already-stubbed part: handled by the existing OFFLOADED_PREFIX
        // check, and must not be mistaken for a readback here.
        assertNull(
            ContextOffload.offloadReadbackPath(
                ContextOffload.stub(100, 200, "$dir/tools/x.txt"),
            ),
        )
    }

    @Test
    fun `a malformed header does not throw`() {
        assertNull(ContextOffload.offloadReadbackPath("["))
        assertNull(ContextOffload.offloadReadbackPath("[]"))
        assertNull(ContextOffload.offloadReadbackPath("[ | 5 bytes]"))
        assertNull(ContextOffload.offloadReadbackPath("[no-close-bracket $dir/x"))
    }

    @Test
    fun `the stub that replaces a readback stays under the rescan floor`() {
        // Why the original "infinite loop" report does not hold: the scan only
        // considers parts longer than 500 chars.
        val stub = ContextOffload.stub(12345, 456789, "$dir/tools/shell_ab12cd.txt")
        org.junit.Assert.assertTrue(
            "stub must stay well under the 500-char offload floor, was ${stub.length}",
            stub.length < 500,
        )
    }
}
