package com.openminis.app.scheduled

import com.openminis.app.scheduled.ScheduledTargetDelivery.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-android-scheduled-default-follow-up] Port of iOS ScheduledTargetDeliveryTests (c32dda5a7). */
class ScheduledTargetDeliveryTest {

    private fun resolve(explicit: String?, session: String?, caller: String?) =
        ScheduledTargetDelivery.resolveTarget(explicit, session, caller)

    @Test fun `an explicit target always wins and is not defaulted`() {
        assertEquals("new" to false, resolve("new", null, "caller"))
        assertEquals("follow-up" to false, resolve(" Follow-Up ", null, null))
        assertEquals("rerun" to false, resolve("RERUN", "s", "caller"))
    }

    @Test fun `inside a chat the default is follow-up`() {
        assertEquals("follow-up" to true, resolve(null, null, "caller"))
        assertEquals("follow-up" to true, resolve(null, "other", null))
        assertEquals("follow-up" to true, resolve("  ", null, "caller"))
    }

    @Test fun `with no chat behind the command the default stays new`() {
        assertEquals("new" to true, resolve(null, null, null))
    }

    @Test fun `delivery sentences name where fires land`() {
        val here = ScheduledTargetDelivery.sentence(Destination.FollowUp("abcdef1234", "Weather", true), defaulted = true)
        assertTrue(here, here.startsWith("Delivery: each fire runs as a new turn IN THIS CHAT (session abcdef12 “Weather”)."))
        assertTrue(here.contains("--target defaulted to follow-up"))
        val hereExplicit = ScheduledTargetDelivery.sentence(Destination.FollowUp("abcdef1234", null, true), defaulted = false)
        assertFalse(hereExplicit.contains("defaulted"))
        val other = ScheduledTargetDelivery.sentence(Destination.FollowUp("zzzz9999", null, false), defaulted = false)
        assertTrue(other.contains("ANOTHER chat (session zzzz9999)"))
        val fresh = ScheduledTargetDelivery.sentence(Destination.NewChat("Morning"), defaulted = false)
        assertTrue(fresh.contains("NEW, separate chat titled “Scheduled · Morning”") && fresh.contains("--target follow-up"))
        val freshDefault = ScheduledTargetDelivery.sentence(Destination.NewChat(null), defaulted = true)
        assertTrue(freshDefault.contains("defaulted to new because no chat was running this command"))
        val rerun = ScheduledTargetDelivery.sentence(Destination.Rerun("sess1234abcd", "msg98765432", null), false)
        assertTrue(rerun.contains("RE-RUNS user message msg98765 in session sess1234"))
    }

    @Test fun `summary is the structured twin`() {
        val m = ScheduledTargetDelivery.summary(Destination.FollowUp("s1", "T", true), defaulted = true)
        assertEquals("follow-up", m["target"]); assertEquals(true, m["defaulted"])
        assertEquals("s1", m["sessionId"]); assertEquals(true, m["isThisChat"]); assertEquals("T", m["sessionTitle"])
        val n = ScheduledTargetDelivery.summary(Destination.NewChat("L"), defaulted = false)
        assertEquals("Scheduled · L", n["newChatTitle"])
    }
}
