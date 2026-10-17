package com.openminis.app.provider

/**
 * Minimal SSE (text/event-stream) event assembler.
 *
 * The three streaming providers used to read `data:` one line at a time and
 * hand each line to `JSONObject` independently. That is not what SSE means: an
 * event ends at a blank line and MAY carry several `data:` lines which must be
 * joined with `\n`. A server/proxy that folds a long JSON event across lines
 * therefore produced a parse failure on every fragment, which the providers
 * swallowed with `catch { continue }` — the stream looked empty or stalled.
 *
 * This parser is provider-neutral: it keeps `event:` / `id:` for callers that
 * need them and returns one [Event] per blank-line terminator. Comments (`:`)
 * and unknown fields are ignored per the specification.
 */
internal class SseEventParser {

    data class Event(
        val event: String?,
        val id: String?,
        val data: String,
    )

    private val data = StringBuilder()
    private var event: String? = null
    private var id: String? = null

    /** Feed one physical line. Returns a completed event at a blank line. */
    fun feed(rawLine: String): Event? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        // Exactly ONE leading space belongs to the field separator.
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "event" -> event = value
            "id" -> id = value
        }
        return null
    }

    /**
     * Flush a final event at EOF. Some proxies close without the trailing
     * blank line; dropping a complete JSON payload there loses the last chunk
     * (often the finish_reason). A truncated payload simply fails the caller's
     * JSON parse and is ignored by it.
     */
    fun finish(): Event? = flush()

    private fun flush(): Event? {
        if (data.isEmpty()) {
            event = null
            id = null
            return null
        }
        val out = Event(event, id, data.toString())
        data.setLength(0)
        event = null
        id = null
        return out
    }
}

/**
 * Assemble [BufferedReader] lines into `data` payloads. Provider loops only
 * care about the payload; `[DONE]` is preserved verbatim.
 */
internal fun ssePayloads(reader: java.io.BufferedReader): Sequence<String> = sequence {
    val parser = SseEventParser()
    while (true) {
        val line = reader.readLine() ?: break
        val event = parser.feed(line) ?: continue
        if (event.data.isNotEmpty()) yield(event.data)
    }
    val tail = parser.finish() ?: return@sequence
    if (tail.data.isNotEmpty()) yield(tail.data)
}
