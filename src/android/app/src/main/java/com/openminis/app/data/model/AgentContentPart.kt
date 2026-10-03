package com.openminis.app.data.model

import org.json.JSONObject

/**
 * Structured content parts for agent loop messages.
 * Used to represent tool_use/tool_result blocks in the conversation history
 * that providers serialize into their native format.
 */
sealed class AgentContentPart {
    data class Text(val text: String) : AgentContentPart()

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JSONObject,
        // [T-android-gemini3-thoughtsig / #179] Opaque Gemini 3.x thought
        // signature captured at tool-call time; replayed on the historical
        // functionCall part (required by gemini-3.x or the request 400s). Null
        // for non-Gemini providers and for pre-fix / migrated history.
        val thoughtSignature: String? = null,
        /**
         * [T-offload-stub-system-reminder] (GH#374) True when the context
         * offloader replaced this call's `content` argument with a
         * pruned-argument notice because the real payload was moved to
         * `/var/minis/offloads/…`.
         *
         * Provenance, not presentation: the notice text is also detectable with
         * [com.openminis.app.data.ContextOffload.isOffloadPlaceholder], but that
         * is a string test on a payload the model can imitate, whereas this flag
         * is set only by the offloader. Defaults to false so every existing
         * construction site and all history persisted before this change decode
         * unchanged. Mirrors iOS `isOffloadedArgument`.
         */
        val isOffloadedArgument: Boolean = false,
    ) : AgentContentPart()

    data class ToolResult(
        val id: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        val imageData: ByteArray? = null,
        val imageMimeType: String? = null,
        /**
         * iSH-visible linux path for [imageData], if it was persisted. Used
         * by request-level image budgeting to emit a re-fetchable text
         * placeholder when the bytes are elided.
         */
        val imageLinuxPath: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ToolResult) return false
            return id == other.id && name == other.name && content == other.content &&
                isError == other.isError && imageData.contentEquals(other.imageData) &&
                imageMimeType == other.imageMimeType && imageLinuxPath == other.imageLinuxPath
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + isError.hashCode()
            result = 31 * result + (imageData?.contentHashCode() ?: 0)
            result = 31 * result + (imageMimeType?.hashCode() ?: 0)
            result = 31 * result + (imageLinuxPath?.hashCode() ?: 0)
            return result
        }
    }

    data class ImageData(
        val data: ByteArray,
        val mimeType: String,
        /**
         * iSH-visible linux path the bytes were originally persisted to, if
         * any. See [LLMMessage.ImagePart.linuxPath] for usage notes.
         */
        val linuxPath: String? = null,
        /**
         * [T-android-vision-group / GH#182] Provider substitutes this for the
         * pixels on the T264 no-native-vision path. See
         * [LLMMessage.ImagePart.noVisionPlaceholder]. Seeded by ChatViewModel
         * only when a Vision Group is configured; null → provider default literal.
         */
        val noVisionPlaceholder: String? = null,
    ) : AgentContentPart() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageData) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType &&
                linuxPath == other.linuxPath && noVisionPlaceholder == other.noVisionPlaceholder
        }

        override fun hashCode(): Int {
            var result = 31 * data.contentHashCode() + mimeType.hashCode()
            result = 31 * result + (linuxPath?.hashCode() ?: 0)
            result = 31 * result + (noVisionPlaceholder?.hashCode() ?: 0)
            return result
        }
    }
}
