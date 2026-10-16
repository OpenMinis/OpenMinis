package com.openminis.app.ui.chat

/** Keep Compose text layout bounded while preserving the newest thinking. */
internal fun visibleThinkingTail(content: String, window: Int): String {
    if (window <= 0 || content.length <= window) return content
    val start = content.length - window
    val newline = content.indexOf('\n', start)
    return if (newline in start until start + 200) content.substring(newline + 1)
    else content.substring(start)
}
