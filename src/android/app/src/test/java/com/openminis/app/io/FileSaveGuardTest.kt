package com.openminis.app.io

import com.openminis.app.io.FileSaveGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the three-state save contract.
 *
 * The regression this guards: a SAF provider that reports
 * `AssetFileDescriptor.UNKNOWN_LENGTH` (-1) used to be treated as success, so
 * a 0-byte document shipped with a "已保存" toast. `UNKNOWN` is now a distinct
 * verdict that callers must resolve with a content probe; it is never success
 * on its own.
 */
class FileSaveGuardTest {

    @Test
    fun nonEmptySourceWithBytesIsIntact() {
        assertEquals(Verdict.INTACT, FileSaveGuard.verify(srcLen = 4096L, dstLen = 4096L))
    }

    @Test
    fun nonEmptySourceWithZeroBytesIsEmpty() {
        assertEquals(Verdict.EMPTY, FileSaveGuard.verify(srcLen = 4096L, dstLen = 0L))
    }

    @Test
    fun unknownDestinationLengthIsUnknownNotSuccess() {
        assertEquals(
            Verdict.UNKNOWN,
            FileSaveGuard.verify(srcLen = 4096L, dstLen = FileSaveGuard.UNKNOWN_LENGTH),
        )
    }

    @Test
    fun unknownLengthWithReadableBytesIsIntact() {
        assertEquals(
            Verdict.INTACT,
            FileSaveGuard.resolve(
                srcLen = 4096L,
                dstLen = FileSaveGuard.UNKNOWN_LENGTH,
                probeHasBytes = true,
            ),
        )
    }

    @Test
    fun unknownLengthWithEofProbeStaysUnknown() {
        // EOF is not proof of fullness: a provider may still be flushing.
        assertEquals(
            Verdict.UNKNOWN,
            FileSaveGuard.resolve(
                srcLen = 4096L,
                dstLen = FileSaveGuard.UNKNOWN_LENGTH,
                probeHasBytes = false,
            ),
        )
    }

    @Test
    fun unknownLengthWithoutProbeStaysUnknown() {
        assertEquals(
            Verdict.UNKNOWN,
            FileSaveGuard.resolve(
                srcLen = 4096L,
                dstLen = FileSaveGuard.UNKNOWN_LENGTH,
                probeHasBytes = null,
            ),
        )
    }

    @Test
    fun emptySourceWithEmptyDestinationIsIntact() {
        assertEquals(Verdict.INTACT, FileSaveGuard.verify(srcLen = 0L, dstLen = 0L))
    }

    @Test
    fun emptySourceWithNonEmptyDestinationIsEmpty() {
        assertEquals(Verdict.EMPTY, FileSaveGuard.verify(srcLen = 0L, dstLen = 12L))
    }

    @Test
    fun probeCannotUpgradeADemonstrablyEmptyCopy() {
        // A length verdict of EMPTY must not be rescued by a stale probe.
        assertEquals(
            Verdict.EMPTY,
            FileSaveGuard.resolve(srcLen = 4096L, dstLen = 0L, probeHasBytes = true),
        )
    }

    @Test
    fun negativeSourceLengthTreatedAsEmpty() {
        assertEquals(Verdict.INTACT, FileSaveGuard.verify(srcLen = -1L, dstLen = 0L))
        assertEquals(Verdict.EMPTY, FileSaveGuard.verify(srcLen = -1L, dstLen = 5L))
    }
}
