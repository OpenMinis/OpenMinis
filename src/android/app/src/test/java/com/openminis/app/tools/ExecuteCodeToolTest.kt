package com.openminis.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-5: `execute_code` is documented as a JavaScript sandbox. Rhino's default
 * interop exposes Java classes (`java.lang.Runtime`, `Packages`, reflection);
 * the class shutter must make those invisible while keeping the JS bridge
 * (print / __minis_call) working.
 */
class ExecuteCodeToolTest {

    private fun run(code: String): ToolExecutionResult = runBlocking {
        val args = org.json.JSONObject().put("code", code).toString()
        ExecuteCodeTool.execute(args) { _, _ ->
            ToolExecutionResult("ok", true)
        }
    }

    @Test
    fun plainJavaScriptAndPrintStillWork() {
        val result = run("print('hello ' + (1 + 2))")
        assertTrue(result.success)
        assertTrue(result.output.contains("hello 3"))
    }

    @Test
    fun javaRuntimeIsNotVisible() {
        val result = run("java.lang.Runtime.getRuntime().exec('id')")
        assertFalse(result.success)
    }

    @Test
    fun packagesJavaIsNotVisible() {
        val result = run("var f = Packages.java.io.File('/etc/passwd'); f.exists()")
        assertFalse(result.success)
    }

    @Test
    fun javaClassLookupIsNotVisible() {
        val result = run("var c = java.lang.Class.forName('java.lang.Runtime'); c.name")
        assertFalse(result.success)
    }
}
