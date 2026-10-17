package com.openminis.app.plugins

/** Structured failure from one dev-suite installation stage. */
internal data class DevSuiteInstallError(
    val componentId: String,
    val stage: Stage,
    val exitCode: Int,
    val command: String,
    val outputTail: String,
) {
    enum class Stage { APT, POST_INSTALL, PROBE, UNINSTALL }

    fun displayText(): String = buildString {
        append(stage.name.lowercase())
        append(" failed (exit ")
        append(exitCode)
        append(")")
        if (outputTail.isNotBlank()) {
            append(": ")
            append(outputTail)
        }
    }
}

internal data class DevSuiteCommandResult(
    val exitCode: Int,
    val outputTail: String,
)

internal object DevSuiteInstallDiagnostics {
    fun text(error: DevSuiteInstallError): String = error.displayText().take(1200)

    fun tail(output: String): String = output.takeLast(2400).trim()
}
