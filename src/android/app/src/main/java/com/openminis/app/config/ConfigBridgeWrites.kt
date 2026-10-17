package com.openminis.app.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * CFG-1: the offload handler's suspending write entry point.
 *
 * The confirmation gate can wait up to 120 s. Running it under `runBlocking`
 * on the native-offload handler thread parked that thread long after the
 * 20 s handler watchdog had already replied; the suspend version lets the
 * watchdog cancel the wait. Kept as a top-level function so `ConfigBridge.kt`
 * stays under its architecture-policy ceiling.
 */
suspend fun writeFieldsSuspending(
    items: JSONArray,
    caption: String?,
    actorRaw: String,
    sessionId: String?,
): JSONObject {
    if (!MinisConfigPermissionStore.isEnabled) return ConfigBridge.disabledErrorEnvelope()
    return ConfigBridge.performWriteBatch(
        items = items,
        caption = caption,
        actorRaw = actorRaw,
        sessionId = sessionId,
        skipConfirmation = false,
    )
}
