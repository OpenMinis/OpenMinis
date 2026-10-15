package com.openminis.app.ui.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-save-as-null-stream] Pins the decision core that keeps a null
 * SAF stream or an empty provider-side copy from surfacing as "已保存" with a
 * 0-byte file in the user's storage.
 */
class FileSaveGuardTest {

    @Test fun nonEmptySourceLandingBytesIsIntact() {
        assertTrue(FileSaveGuard.copiedIntact(srcLen = 4096L, dstLen = 4096L))
    }

    @Test fun nonEmptySourceLandingZeroBytesIsFailure() {
        // The null-stream shape: `openOutputStream` returned null, the old
        // `?.use { copy }` no-op still reported success, dst stayed 0.
        assertFalse(FileSaveGuard.copiedIntact(srcLen = 4096L, dstLen = 0L))
    }

    @Test fun unknownDestinationLengthIsAccepted() {
        // Providers legitimately report UNKNOWN_LENGTH — must not misjudge.
        assertTrue(FileSaveGuard.copiedIntact(srcLen = 4096L, dstLen = FileSaveGuard.UNKNOWN_LENGTH))
    }

    @Test fun emptySourceWithEmptyDestinationIsIntact() {
        // Copying a genuinely empty file to an empty destination is correct.
        assertTrue(FileSaveGuard.copiedIntact(srcLen = 0L, dstLen = 0L))
    }

    @Test fun emptySourceWithNonEmptyDestinationIsSuspicious() {
        // An empty source cannot legitimately produce bytes — treat as
        // failure so the pending row is deleted rather than published.
        assertFalse(FileSaveGuard.copiedIntact(srcLen = 0L, dstLen = 12L))
    }

    @Test fun negativeSourceLengthTreatedAsEmpty() {
        // File.length() on a vanished file reports 0; a defensive caller
        // passing -1 must not crash the decision.
        assertTrue(FileSaveGuard.copiedIntact(srcLen = -1L, dstLen = 0L))
        assertFalse(FileSaveGuard.copiedIntact(srcLen = -1L, dstLen = 5L))
    }
}
