package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledTargetModeTest {
    @Test
    fun `every target mode round-trips through encode and decode`() {
        val modes = listOf(
            ScheduledTargetMode.NewSession,
            ScheduledTargetMode.AppendToSession("S1"),
            ScheduledTargetMode.RerunMessage("S1", "M1"),
            ScheduledTargetMode.ChildOfCurrent("P1"),
        )
        for (m in modes) assertEquals(m, ScheduledTargetMode.decode(m.encode()))
        assertEquals("CHILD_OF:P1", ScheduledTargetMode.ChildOfCurrent("P1").encode())
        assertEquals("P1", ScheduledTargetMode.ChildOfCurrent("P1").sessionIdOrNull)
        assertEquals(ScheduledTargetMode.NewSession, ScheduledTargetMode.decode("garbage"))
    }
}
