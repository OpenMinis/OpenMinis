package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-js-dialogs-256] Semantics of the agent browser's intercepted-dialog
 * queue: bounded retention, oldest-first eviction with an honest count, and
 * drain-exactly-once.
 *
 * The interception itself (WebChromeClient answering with a default rather than
 * showing a modal) needs a real WebView and is verified on-device; what is
 * pinned here is the bookkeeping that decides what the model actually gets told.
 */
class InterceptedDialogQueueTest {

    private fun q(max: Int = InterceptedDialogQueue.DEFAULT_MAX_EVENTS) =
        InterceptedDialogQueue(maxEvents = max)

    private fun InterceptedDialogQueue.add(
        kind: String = "alert",
        message: String = "hello",
        defaultText: String? = null,
        pageURL: String? = "https://example.com/",
        defaultResponse: String = "(dismissed)",
    ) = record(kind, message, defaultText, pageURL, defaultResponse)

    @Test
    fun `empty queue drains to null`() {
        assertNull(q().drainReport())
    }

    @Test
    fun `a single alert is reported with its message and the answer given`() {
        val queue = q()
        queue.add(kind = "alert", message = "ALERT-MSG", defaultResponse = "(dismissed)")
        val r = queue.drainReport()
        assertNotNull(r)
        assertTrue("names the kind", r!!.contains("alert()"))
        assertTrue("quotes the message", r.contains("\"ALERT-MSG\""))
        assertTrue("states what the page received", r.contains("`(dismissed)`"))
        assertTrue("names the page", r.contains("https://example.com/"))
        assertTrue("singular wording", r.contains("show a dialog"))
    }

    @Test
    fun `confirm reports the false it was given, not a silent success`() {
        // The whole point of the fix: the agent answered false on the user's
        // behalf and the model has to know that specifically.
        val queue = q()
        queue.add(kind = "confirm", message = "Delete everything?", defaultResponse = "false")
        val r = queue.drainReport()!!
        assertTrue(r.contains("confirm()"))
        assertTrue(r.contains("`false`"))
    }

    @Test
    fun `prompt carries its default text through`() {
        val queue = q()
        queue.add(
            kind = "prompt", message = "Your name?",
            defaultText = "anon", defaultResponse = "null",
        )
        val r = queue.drainReport()!!
        assertTrue(r.contains("prompt()"))
        assertTrue("surfaces the page's suggested value", r.contains("[default text: \"anon\"]"))
        assertTrue(r.contains("`null`"))
    }

    @Test
    fun `plural wording switches with count`() {
        val queue = q()
        queue.add(); queue.add()
        assertTrue(queue.drainReport()!!.contains("show 2 dialogs"))
    }

    @Test
    fun `draining clears, so a dialog is reported exactly once`() {
        val queue = q()
        queue.add(message = "once")
        assertTrue(queue.drainReport()!!.contains("once"))
        assertNull("second drain must be empty", queue.drainReport())
    }

    @Test
    fun `queue is bounded and evicts the OLDEST`() {
        val queue = q(max = 3)
        queue.add(message = "m1")
        queue.add(message = "m2")
        queue.add(message = "m3")
        queue.add(message = "m4")   // evicts m1
        assertEquals(3, queue.size)
        val r = queue.drainReport()!!
        assertFalse("oldest was dropped", r.contains("m1"))
        assertTrue(r.contains("m2"))
        assertTrue(r.contains("m4"))
    }

    @Test
    fun `dropped count is reported honestly`() {
        val queue = q(max = 2)
        repeat(5) { queue.add(message = "m$it") }   // 3 evicted
        val r = queue.drainReport()!!
        assertTrue("says how many were lost", r.contains("(3 earlier dialogs dropped"))
        assertTrue("names the cap", r.contains("most recent 2"))
    }

    @Test
    fun `single dropped record uses singular wording`() {
        val queue = q(max = 1)
        queue.add(message = "a"); queue.add(message = "b")
        assertTrue(queue.drainReport()!!.contains("(1 earlier dialog dropped"))
    }

    @Test
    fun `dropped counter resets after a drain`() {
        val queue = q(max = 1)
        queue.add(); queue.add()                 // 1 dropped
        assertTrue(queue.drainReport()!!.contains("1 earlier dialog dropped"))
        queue.add()
        val second = queue.drainReport()!!
        assertFalse("stale drop count must not carry over", second.contains("dropped"))
    }

    @Test
    fun `report steers the model toward execute_js rather than retrying blindly`() {
        val queue = q()
        queue.add()
        val r = queue.drainReport()!!
        assertTrue(r.contains("execute_js"))
        assertTrue("frames it as not shown to a human", r.contains("NOT shown to the user"))
        assertTrue("original result still follows", r.trimEnd().endsWith("[Original tool result below]"))
    }

    @Test
    fun `a null pageURL is simply omitted`() {
        val queue = q()
        queue.add(pageURL = null, message = "no-url")
        val r = queue.drainReport()!!
        assertTrue(r.contains("no-url"))
        assertFalse(r.contains(" on null"))
    }

    @Test
    fun `concurrent records do not lose or corrupt entries`() {
        // record() runs on the UI thread (WebChromeClient) while drainReport()
        // runs on the action coroutine — the two genuinely race.
        val queue = q(max = 1000)
        val threads = (1..8).map { t ->
            Thread { repeat(50) { queue.add(message = "t$t-$it") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(400, queue.size)
    }
}
