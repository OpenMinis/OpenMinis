package com.openminis.app.plugins

import com.openminis.app.sandbox.ExecutionCoordinator

/** Executes long-running dev-suite commands and keeps bounded diagnostics. */
internal object DevSuiteCommandRunner {
    private const val SESSION_ID = "dev-suite-installer"
    private const val INSTALL_TIMEOUT_MS = 30 * 60_000L

    suspend fun run(
        command: String,
        timeoutMs: Long = INSTALL_TIMEOUT_MS,
        onLine: (String) -> Unit,
    ): DevSuiteCommandResult {
        val result = ExecutionCoordinator.execute(
            sessionId = SESSION_ID,
            command = command,
            timeout = timeoutMs,
            lineCallback = onLine,
        )
        return DevSuiteCommandResult(
            exitCode = result.exitCode,
            outputTail = DevSuiteInstallDiagnostics.tail(result.output),
        )
    }
}
