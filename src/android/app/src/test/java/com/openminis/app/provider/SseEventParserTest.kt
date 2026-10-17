package com.openminis.app.provider

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventParserTest {

    private fun parserOf(text: String): Pair<SseEventParser, MutableList<SseEventParser.Event>> {
        val parser = SseEventParser()
        val events = mutableListOf<SseEventParser.Event>()
        BufferedReader(StringReader(text)).useLines { lines ->
            lines.forEach { line ->
                parser.feed(line)?.let { events += it }
            }
        }
        parser.finish()?.let { events += it }
        return parser to events
    }

    @Test
    fun multiLineDataIsJoinedWithNewline() {
        val (_, events) = parserOf(
            """
            data: {"a":
            data: 1}
            
            """.trimIndent(),
        )
        assertEquals(1, events.size)
        assertEquals("{\"a\":\n1}", events[0].data)
    }

    @Test
    fun blankLineTerminatesAndResets() {
        val (_, events) = parserOf("data: one\n\ndata: two\n\n")
        assertEquals(listOf("one", "two"), events.map { it.data })
    }

    @Test
    fun eventAndIdArePreserved() {
        val (_, events) = parserOf("event: message\nid: 42\ndata: {}\n\n")
        assertEquals("message", events[0].event)
        assertEquals("42", events[0].id)
    }

    @Test
    fun commentsAndUnknownFieldsAreIgnored() {
        val (_, events) = parserOf(": keepalive\nretry: 100\ndata: x\n\n")
        assertEquals(1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test
    fun onlyOneLeadingSpaceIsStripped() {
        val (_, events) = parserOf("data:  two\n\n")
        assertEquals(" two", events[0].data)
    }

    @Test
    fun crlfLinesAreAccepted() {
        val (_, events) = parserOf("data: a\r\ndata: b\r\n\r\n")
        assertEquals("a\nb", events[0].data)
    }

    @Test
    fun eofFlushesAnEventWithoutTrailingBlankLine() {
        val parser = SseEventParser()
        assertNull(parser.feed("data: tail"))
        assertEquals("tail", parser.finish()?.data)
        assertNull(parser.finish())
    }

    @Test
    fun payloadSequenceYieldsAssembledEvents() {
        val payloads = ssePayloads(
            BufferedReader(StringReader("data: {\"x\":\ndata: 1}\n\ndata: [DONE]\n\n")),
        ).toList()
        assertEquals(listOf("{\"x\":\n1}", "[DONE]"), payloads)
    }

    @Test
    fun emptyStreamYieldsNothing() {
        assertEquals(emptyList<String>(), ssePayloads(BufferedReader(StringReader(""))).toList())
    }
}
