package com.openminis.app.backup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-restore-ios-session-nesting] iOS and Android write the SAME
 * record family in two different shapes, and the importer has to read both.
 *
 * The bug this pins: restoring a real iPhone package on a Pixel 6 imported
 * providers and files but not one single chat. iOS nests the session under a
 * `session` key with the wrapper's own fields as siblings:
 *
 *     {"memoryEnabled":true,"session":{"id":…,"title":…}}
 *
 * while Android writes those fields flat. Reading only the flat shape made
 * every iOS record look like it had no `id`, so all 2350 sessions were counted
 * unreadable — and every message went with them, because a message row needs
 * its parent session to satisfy the foreign key. The restore then reported
 * success having written no chats at all.
 *
 * The samples below are taken verbatim from the package that failed
 * (`iPhone-17-Pro-20260823`) and from Android's own exporter, so this test
 * fails if either writer's shape drifts.
 */
class CrossPlatformSessionShapeTest {

    /** Mirrors `BackupImporter.unwrapNested`. Inner wins; outer fills in. */
    private fun JsonObject.unwrapNested(key: String): JsonObject {
        val inner = (this[key] as? JsonObject) ?: return this
        return JsonObject(this.filterKeys { it != key } + inner)
    }

    private fun payload(line: String): JsonObject =
        BackupFormat.json.parseToJsonElement(line).jsonObject["d"]!!.jsonObject

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it.toString() != "null" }
            ?.runCatching { jsonPrimitive.content }?.getOrNull()

    @Test
    fun `an iOS session is readable once unwrapped`() {
        val ios = """
            {"t":"SessionV2","v":1,"d":{"memoryEnabled":true,"session":{
            "category":"support","createdAt":"2026-07-22T08:15:58Z",
            "id":"0006B985-A43A-48C3-A489-4189F792ED0F","modelId":"claude-sonnet-5",
            "title":"理想超充站充电流程","updatedAt":"2026-07-22T08:43:38Z"}}}
        """.trimIndent().replace("\n", "")

        val raw = payload(ios)
        // The regression itself: read flat, and the record has no id at all.
        assertNull("precondition: the id is NOT at the top level", raw.str("id"))

        val s = raw.unwrapNested("session")
        assertEquals("0006B985-A43A-48C3-A489-4189F792ED0F", s.str("id"))
        assertEquals("理想超充站充电流程", s.str("title"))
        assertEquals("claude-sonnet-5", s.str("modelId"))
        // The wrapper's sibling field survives the merge.
        assertEquals("true", s.str("memoryEnabled"))
    }

    @Test
    fun `an Android session is unchanged by unwrapping`() {
        val android = """
            {"t":"SessionV2","v":1,"d":{"id":"A1","title":"local","modelId":"m",
            "memoryEnabled":true,"createdAt":"2026-08-01T00:00:00Z",
            "updatedAt":"2026-08-01T00:00:00Z"}}
        """.trimIndent().replace("\n", "")

        val s = payload(android).unwrapNested("session")
        assertEquals("A1", s.str("id"))
        assertEquals("local", s.str("title"))
        assertEquals("true", s.str("memoryEnabled"))
    }

    @Test
    fun `iOS names a folder's description desc`() {
        // Android's exporter writes `description`; iOS's Folder model calls it
        // `desc`. Reading only one name drops the field on a cross-platform
        // restore.
        val ios = """{"t":"FolderV2","v":1,"d":{"id":"F1","name":"Work","desc":"from iPhone"}}"""
        val f = payload(ios)
        assertNull(f.str("description"))
        assertEquals("from iPhone", f.str("description") ?: f.str("desc"))
    }

    @Test
    fun `messages are flat on both platforms`() {
        // Verbatim from the same iPhone package: unlike sessions, message
        // records carry id/sessionId directly, so they need no unwrapping.
        val ios = """
            {"t":"MessageV2","v":1,"d":{"createdAt":"2026-07-22T08:15:58Z",
            "id":"E1AF8DBD-F647-4A41-ADDF-D255EF27D396","role":"user",
            "sessionId":"0006B985-A43A-48C3-A489-4189F792ED0F","sortOrder":0}}
        """.trimIndent().replace("\n", "")
        val m = payload(ios)
        assertEquals("E1AF8DBD-F647-4A41-ADDF-D255EF27D396", m.str("id"))
        assertEquals("0006B985-A43A-48C3-A489-4189F792ED0F", m.str("sessionId"))
    }
}
