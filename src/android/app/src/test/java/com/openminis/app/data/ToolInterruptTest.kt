package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInterruptTest {

    @Test
    fun emptyPartialIsJustTheReason() {
        assertEquals(
            ToolInterrupt.USER_STOP,
            ToolInterrupt.withPartial("", ToolInterrupt.USER_STOP),
        )
        assertEquals(
            ToolInterrupt.NEW_MESSAGE,
            ToolInterrupt.withPartial("   \n", ToolInterrupt.NEW_MESSAGE),
        )
    }

    @Test
    fun keepsStdoutAndAppendsReason() {
        val out = ToolInterrupt.withPartial("step 3 ok\nclicked publish", ToolInterrupt.USER_STOP)
        assertTrue(out.startsWith("step 3 ok"))
        assertTrue(out.contains("clicked publish"))
        assertTrue(out.contains(ToolInterrupt.USER_STOP))
        assertFalse(out.contains("unexpected error"))
    }
}
