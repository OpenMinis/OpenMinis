package com.openminis.app.io

/**
 * Pure decision core for "did the bytes actually land?".
 *
 * The old contract treated an unknown destination length (`-1`) as success.
 * That is exactly the shape that produced a 0-byte file plus a "已保存" toast
 * on OEM SAF providers: `openAssetFileDescriptor(...).length` reports
 * [UNKNOWN_LENGTH], so the guard could not tell "written but unmeasurable"
 * from "never written" and defaulted to success.
 *
 * The contract is now three-state:
 *  - [Verdict.INTACT]   — demonstrably correct (length matches the source's
 *                         emptiness class, or a content probe found bytes).
 *  - [Verdict.EMPTY]    — demonstrably wrong (expected bytes, landed none, or
 *                         an empty source produced bytes).
 *  - [Verdict.UNKNOWN]  — the provider cannot report a length *and* the
 *                         caller has not supplied a content probe. Callers
 *                         must not render this as success.
 *
 * [VerifiedCopy] owns the provider I/O and turns [Verdict.UNKNOWN] into a
 * content probe; this object stays side-effect free so the decision table is
 * unit-testable on the JVM.
 */
object FileSaveGuard {

    /** `AssetFileDescriptor.UNKNOWN_LENGTH`. */
    const val UNKNOWN_LENGTH = -1L

    enum class Verdict { INTACT, EMPTY, UNKNOWN }

    /**
     * Length-only decision. Never returns [Verdict.INTACT] for an unknown
     * destination length — callers that can read content back should use
     * [resolve] with the probe result instead.
     */
    fun verify(srcLen: Long, dstLen: Long): Verdict = when {
        dstLen == UNKNOWN_LENGTH -> Verdict.UNKNOWN
        srcLen <= 0L -> if (dstLen == 0L) Verdict.INTACT else Verdict.EMPTY
        dstLen > 0L -> Verdict.INTACT
        else -> Verdict.EMPTY
    }

    /**
     * Resolve with an optional content probe (`true` = at least one byte was
     * readable back from the destination, `false` = EOF, `null` = probe not
     * attempted / failed).
     *
     * A probe only upgrades an [Verdict.UNKNOWN]; it never overrides a
     * length verdict that already proved emptiness.
     */
    fun resolve(srcLen: Long, dstLen: Long, probeHasBytes: Boolean?): Verdict {
        val byLength = verify(srcLen, dstLen)
        if (byLength != Verdict.UNKNOWN || probeHasBytes == null) return byLength
        return when {
            srcLen <= 0L -> if (probeHasBytes) Verdict.EMPTY else Verdict.INTACT
            probeHasBytes -> Verdict.INTACT
            else -> Verdict.UNKNOWN
        }
    }
}
