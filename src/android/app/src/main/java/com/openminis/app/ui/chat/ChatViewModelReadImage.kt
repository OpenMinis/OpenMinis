package com.openminis.app.ui.chat

import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * [T-android-vision-group / GH#182] read_image dispatch.
 *
 * Native-vision main models keep the original behaviour exactly: the tool
 * returns the pixels and the provider attaches them.
 *
 * A main model WITHOUT native image input only reaches here because a Vision
 * Group is configured (that's the tool-exposure gate in [agentTools]). For
 * that case we do NOT return pixels — a text-only model can't decode them and
 * the provider (OpenAIProvider T264) silently drops them to a placeholder.
 * Instead we hand the bytes to the Vision Group, get a text DESCRIPTION back,
 * and return that as the tool output. `imageData` is left null so no pixels
 * are attached, but `imageFilePath` is preserved so the on-screen tool block
 * still shows the image the user's model "read". Mirrors iOS
 * AIChatViewModel+ConcurrentTools read_image branch.
 */
internal suspend fun ChatViewModel.executeReadImageTool(argsJson: String): ToolExecutionResult {
    // [T-android-tool-pool-cap] decodeFile + resize + JPEG re-encode block for
    // hundreds of ms. Both dispatch sites (read_image and describe_image) come
    // through here, so the bounded file-tool dispatcher is applied once.
    val base = com.openminis.app.tools.ToolConcurrency.onFileOp {
        ReadImageTool.execute(argsJson, activeSessionId, context)
    }
    // [T-android-vision-group / GH#182] Optional caller instruction focusing
    // what to learn from the image.
    val customPrompt = try {
        JSONObject(argsJson).optString("prompt", "").trim().ifEmpty { null }
    } catch (_: Exception) { null }
    // Failed decode / missing file → unchanged.
    if (!base.success || base.imageData == null) return base
    if (currentModelHasNativeVision) {
        // The model sees the pixels itself; a prompt adds no routing here, but
        // echo it as context so the tool block reflects the model's intent.
        return if (customPrompt != null) {
            base.copy(output = base.output + "\n\n[Requested focus: " + customPrompt + "]")
        } else base
    }

    val bytes = base.imageData
    val mime = base.imageMimeType ?: "image/jpeg"
    val result = com.openminis.app.tools.VisionGroupResolver.describe(
        repo = providerRepository,
        context = context,
        imageData = bytes,
        mimeType = mime,
        seed = kotlin.math.abs(argsJson.hashCode()),
        customPrompt = customPrompt,
        // [T-vision-group-attribution / GH#182] iOS rewrites the tool block's
        // live content here so the card names the model as it works. Android
        // has no equivalent channel — no tool streams partial output to its
        // card, and the progress label ("Minis is reading Image",
        // ChatToolFormatting.kt:102) is a static per-tool string. Building
        // that plumbing is a separate change, so for now the per-attempt
        // signal goes to the log, where a fallback is still traceable. The
        // RESULT-side attribution (which model answered, what was tried
        // first) is fully implemented and is what the user actually reads.
        onAttempt = { a ->
            android.util.Log.i(
                "VisionGroup",
                "[Vision] attempt ${a.index}/${a.total} via ${a.modelName}",
            )
        },
    )
    val framed = when (result) {
        is com.openminis.app.tools.VisionGroupResolver.VisionResult.Success ->
            com.openminis.app.tools.VisionGroupResolver.framedDescription(
                result,
                com.openminis.app.tools.VisionGroupResolver.groupName(providerRepository),
                question = customPrompt,
            )
        is com.openminis.app.tools.VisionGroupResolver.VisionResult.Failure ->
            com.openminis.app.tools.VisionGroupResolver.failureText(result.reason)
    }
    // Deliberately still success=true even on describe failure: an errored
    // tool result tends to make models retry in a loop, whereas this lets the
    // model plainly tell the user the image couldn't be analyzed.
    return base.copy(
        output = base.output + "\n\n" + framed,
        imageData = null,
        imageMimeType = null,
    )
}
