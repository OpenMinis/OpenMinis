package com.openminis.app.inbound

/**
 * [inbound-task-api] What the HTTP layer asks the app to do.
 * The scheduled-task implementation is [ScheduledInboundTaskStarter]; tests
 * pass a fake so the socket behavior can be checked without a database.
 */
interface InboundTaskStarter {
    fun start(prompt: String, session: String?, label: String?): StartOutcome
    fun status(id: String): TaskView?
}

sealed class StartOutcome {
    data class Started(val id: String, val sessionId: String, val target: String) : StartOutcome()
    data class Rejected(val httpStatus: Int, val code: String) : StartOutcome()
}
