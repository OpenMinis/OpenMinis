package com.openminis.app.data

/**
 * [OpenMinis-Fix #7] Tool-result copy when a call is cut short.
 * The old "unexpected error" string hid whether the user stopped, a new
 * message arrived, or the process actually crashed — and it replaced any
 * stdout already produced.
 */
object ToolInterrupt {
    const val USER_STOP = "Tool execution was interrupted by the user."
    const val NEW_MESSAGE = "Tool execution was interrupted by a new user message."
    const val UNEXPECTED = "Tool execution was interrupted before a result was returned."

    fun withPartial(partial: String, reason: String): String {
        val body = partial.trim()
        return if (body.isEmpty()) reason else "${partial.trimEnd()}\n\n[$reason]"
    }
}
