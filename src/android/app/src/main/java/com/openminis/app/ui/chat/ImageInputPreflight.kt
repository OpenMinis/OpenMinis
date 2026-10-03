package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.effectiveInputModalities

/**
 * [T-android-image-input-preflight] Pre-send modality check for image
 * attachments.
 *
 * Background: attaching an image while a text-only model is selected (the
 * official DeepSeek chat/reasoner endpoints, many relay-hosted text models)
 * used to go straight to the network and come back as
 * `Provider error: [400] 请求参数或所选模型能力不受支持`. The OpenAI-compatible
 * provider already swaps pixels for a text placeholder when
 * [LLMModel.hasImageInput] is false, so that path never 400s — but it also
 * silently drops the user's image, and a model whose vision support is only
 * *assumed* (no declared modality list; provider-table default) still gets
 * the image and fails upstream.
 *
 * This object is pure so both rules are unit-testable:
 *  - [check]: refuse to send at all when the selected model is known not to
 *    accept images. The composer keeps its text and attachments so the user
 *    can switch model (or enable "Image Input" in the model's details when
 *    the catalogue is simply wrong) and resend.
 *  - [isLikelyImageRejection]: when an upstream 400 arrives for a request
 *    that did carry images, tell the user *why* it most likely failed instead
 *    of showing the bare provider message.
 */
internal object ImageInputPreflight {

    /**
     * [T-android-image-path-metadata] The BLOCKING half of this object
     * (`Verdict` / `check`) was removed.
     *
     * It refused the send outright when the resolved model's declared input
     * modalities lacked "image". That is not how the app decides image
     * handling anywhere else, and iOS has no such gate: the modality question
     * is answered while BUILDING the request, where a non-vision model gets a
     * text placeholder carrying the image's sandbox path instead of pixels.
     * Blocking at the composer pre-empted that, and also pre-empted every
     * non-vision use of an attachment — `shell_execute` can inspect, convert
     * or OCR the file without any vision capability at all.
     *
     * What remains is the REACTIVE half, which never blocks anything: when a
     * request that carried images comes back 400, it recognises the shape so
     * the user gets an actionable hint instead of the bare upstream string.
     */
    fun isLikelyImageRejection(error: Throwable, sentImageCount: Int): Boolean {
        if (sentImageCount <= 0) return false
        val providerError = error as? LLMError.ProviderError ?: return false
        if (providerError.httpStatus != 400) return false
        // [T-android-image-hint-not-for-tool-pairing] A 400 whose message names
        // a different cause is not about images. Field report: DeepSeek's
        // `No tool output found for tool call call_01_…` (a malformed parallel
        // tool run, T-android-responses-toolresult-image-split) came back with
        // "this model may not support image input" appended, and the user went
        // looking at the image. Generic 400s keep the hint: a text-only model's
        // rejection often names nothing at all ("请求参数或所选模型能力不受支持").
        val detail = providerError.detail.lowercase()
        return NOT_IMAGE_RELATED.none { it in detail }
    }

    /** Fragments of 400 messages that point at something other than the images. */
    private val NOT_IMAGE_RELATED = listOf(
        "tool output", "tool call", "tool_call", "function_call", "tool_result", "tool_use",
        "context length", "context_length", "maximum context", "prompt is too long", "too many tokens",
    )
}
