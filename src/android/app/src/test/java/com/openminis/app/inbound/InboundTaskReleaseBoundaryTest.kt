package com.openminis.app.inbound

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [inbound-task-api] The debug server stays debug-only. The inbound listener
 * is a separate release path, and its token prefs are kept out of backup.
 */
class InboundTaskReleaseBoundaryTest {

    @Test
    fun `debug server is unchanged in role and the inbound listener is separate`() {
        val app = ProductionSources.read("MinisApp.kt")
        assertTrue(app.contains("if (BuildConfig.DEV_TOOLS) {\n            try {\n                com.openminis.app.debug.DebugServer(this).start()"))
        assertTrue(app.contains("InboundTaskApi.applyEnabled"))
        val debugAt = app.indexOf("DebugServer(this).start()")
        val inboundAt = app.indexOf("InboundTaskApi.applyEnabled")
        assertTrue(debugAt in 0 until inboundAt)

        val debug = ProductionSources.read("debug/DebugServer.kt")
        assertFalse(debug.contains("9473"))
        assertFalse(debug.contains("InboundTask"))
        assertTrue(debug.contains("fun isAuthorized"))

        val manifest = File(ProductionSources.mainRoot()!!, "../../../../AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".inbound.InboundTaskApiService"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))

        val server = ProductionSources.read("inbound/InboundTaskServer.kt")
        val logCalls = Regex("""log\(([^)\n]*)\)""").findAll(server).map { it.groupValues[1] }.toList()
        assertTrue(logCalls.isNotEmpty())
        for (call in logCalls) {
            assertFalse(call, call.contains("prompt"))
            assertFalse(call, call.contains("token"))
            assertFalse(call, call.contains("authorization"))
        }
        val starter = ProductionSources.read("inbound/ScheduledInboundTaskStarter.kt")
        val starterLogs = Regex("""AppLogger\.\w+\(([^)\n]*)\)""").findAll(starter).map { it.groupValues[1] }.toList()
        assertTrue(starterLogs.isNotEmpty())
        for (call in starterLogs) {
            assertFalse(call, call.contains("prompt"))
            assertFalse(call, call.contains("token"))
        }
    }

    @Test
    fun `token prefs are excluded from backup and device transfer`() {
        val xmlDir = File(ProductionSources.mainRoot()!!, "../../../../res/xml")
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = File(xmlDir, name).readText()
            assertTrue("$name misses the token prefs", xml.contains("path=\"inbound_task_api.xml\""))
        }
    }
}
