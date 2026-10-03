package com.openminis.app.sandbox

/**
 * [T-android-parity-fixes] The single place a shell tool result is annotated
 * with its exit status.
 *
 * Two layers used to each append their own: the coordinator wrote
 * `\n(exit code: N)` and the chat layer then added ` (exit code N)`, so the
 * model read `…\n(exit code: 1) (exit code 1)` on every failure. The
 * coordinator keeps annotating (it knows 124 is reported by its own timeout
 * notice), and the chat layer only fills in a status the coordinator left
 * out, in the same format.
 */
internal object ShellExitCode {

    fun suffix(code: Int): String = "(exit code: $code)"

    /** [output] with its exit status appended, unless it already ends with it. */
    fun ensureSuffix(output: String, code: Int): String =
        if (output.trimEnd().endsWith(suffix(code))) output else "$output\n${suffix(code)}"
}
