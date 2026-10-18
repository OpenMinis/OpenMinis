package com.openminis.app.hostcapability.api

import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.tools.ToolConcurrency
import kotlinx.coroutines.withTimeout

/** Preserves the existing argv/env/exit-code contract while exposing the new API. */
class NativeOffloadAdapter(
    override val name: String,
    private val delegate: NativeOffloadHandler,
    override val permission: CapabilityPermission = CapabilityPermission.NONE,
    private val defaultTimeoutMs: Long = 20_000L,
) : HostCapabilityHandler {
    override suspend fun handle(request: HostCapabilityRequest): HostCapabilityResponse =
        withTimeout(request.timeoutMs ?: defaultTimeoutMs) {
            // [T-android-tool-pool-cap] Host handlers block (guest process fork,
            // WebView drive, recognizer). Run them on the bounded offload
            // dispatcher so a fan-out cannot occupy the shared IO pool that
            // provider streams and DB writes also need.
            ToolConcurrency.onOffload {
                val result = delegate.handle(
                    NativeOffloadRequest(
                        pid = 0,
                        argv = request.arguments,
                        env = request.environment,
                        cwd = request.workingDirectory,
                        sessionId = request.sessionId,
                    ),
                )
                HostCapabilityResponse(result.exitCode, result.output)
            }
        }
}
