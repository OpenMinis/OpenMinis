package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Rhino JS orchestration sandbox. Only print() returns to the model.
 *
 * Adapted from XINCODE-Public CodeExecTool (GPL-3.0-or-later).
 */
object ExecuteCodeTool {
    const val NAME = "execute_code"
    private const val MAX_TOOL_CALLS = 50
    private const val MAX_STDOUT = 50_000
    private const val INSTRUCTION_BUDGET = 20_000_000

    val SANDBOX_TOOLS = setOf(
        "web_search", "web_fetch", "file_read", "list_dir", "grep", "glob",
        "memory_get", "grep_source",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run JavaScript (ES5, Rhino) to orchestrate multiple read-only tool calls. " +
            "Functions: ${SANDBOX_TOOLS.joinToString(", ")}(obj). Only print() output returns. " +
            "No file_write/shell. JavaScript only — not Python.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "code" to AgentToolParam("string", "JavaScript (ES5) source. Use print(...) for output."),
        ),
        required = listOf("tool_title", "code"),
        propertyOrdering = listOf("tool_title", "code"),
    )

    class Bridge(private val invoke: suspend (String, String) -> ToolExecutionResult) {
        val out = StringBuilder()
        var toolCalls = 0
        fun print(v: Any?) {
            if (out.length < MAX_STDOUT) out.append(v?.toString() ?: "null").append('\n')
        }
        fun call(toolName: String, argsJson: String): String {
            if (toolName !in SANDBOX_TOOLS) return "ERROR: '$toolName' not in sandbox"
            if (++toolCalls > MAX_TOOL_CALLS) throw RuntimeException("tool call cap $MAX_TOOL_CALLS")
            return try {
                runBlocking {
                    val r = invoke(toolName, argsJson)
                    if (r.success) r.output else "ERROR: ${r.output}"
                }
            } catch (t: Throwable) {
                "ERROR: ${t::class.java.simpleName}: ${t.message}"
            }
        }
    }

    suspend fun execute(
        argsJson: String,
        invoke: suspend (String, String) -> ToolExecutionResult,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = args.optString("tool_title", NAME)
        val lang = args.optString("language").ifBlank { args.optString("lang") }.trim().lowercase()
        if (lang.isNotEmpty() && lang !in setOf("javascript", "js", "ecmascript", "node")) {
            return ToolExecutionResult(
                "execute_code only runs JavaScript (ES5), not $lang. Use shell_execute for Python.",
                false,
                toolTitle = toolTitle,
            )
        }
        val code = args.optString("code").ifBlank { args.optString("script") }.ifBlank { args.optString("source") }
        if (code.isBlank()) return ToolExecutionResult("code required", false, toolTitle = toolTitle)
        val bridge = Bridge(invoke)
        return try {
            withContext(Dispatchers.IO) {
            val factory = object : ContextFactory() {
                private var instructions = 0
                override fun makeContext(): Context {
                    val cx = super.makeContext()
                    cx.optimizationLevel = -1
                    cx.instructionObserverThreshold = 100_000
                    return cx
                }
                override fun observeInstructionCount(cx: Context?, count: Int) {
                    instructions += count
                    if (instructions > INSTRUCTION_BUDGET) throw RuntimeException("instruction budget exceeded")
                }
            }
            val cx = factory.enterContext()
            try {
                // SEC-5: Rhino's default interop exposes `java.lang.Runtime`,
                // file system and process APIs. `execute_code` is documented
                // as a JS sandbox, so no Java class may be visible to scripts;
                // the only host bridges are print/console/__minis_call.
                cx.setClassShutter(object : ClassShutter {
                    override fun visibleToScripts(fullClassName: String): Boolean = false
                })
                val scope = cx.initStandardObjects()
                val printFn = object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any>,
                    ): Any? {
                        bridge.print(args.getOrNull(0))
                        return Context.getUndefinedValue()
                    }
                }
                ScriptableObject.putProperty(scope, "print", printFn)
                val console = cx.newObject(scope)
                ScriptableObject.putProperty(console, "log", printFn)
                ScriptableObject.putProperty(scope, "console", console)
                val callFn = object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any>,
                    ): Any? = bridge.call(
                        args.getOrNull(0)?.toString().orEmpty(),
                        args.getOrNull(1)?.toString() ?: "{}",
                    )
                }
                ScriptableObject.putProperty(scope, "__minis_call", callFn)
                val prelude = buildString {
                    for (t in SANDBOX_TOOLS) {
                        append("function $t(p){")
                        // [T-execute-code-memory-get] memory_get takes `keywords`,
                        // not `name`: mapping a keyword search to a parameter the
                        // executor never reads made every memory_get("python") call
                        // look like an unfiltered request, i.e. a FULL memory dump
                        // (capped only at 500 lines) pushed into the JS bridge
                        // instead of the handful of matching lines asked for.
                        append("if(typeof p==='string'){var k={file_read:'path',list_dir:'path',grep:'pattern',glob:'pattern',web_search:'query',web_fetch:'url',memory_get:'keywords',grep_source:'pattern'};")
                        append("var o={};o[k['$t']||'query']=p;return __minis_call('$t', JSON.stringify(o));}")
                        append("if(p==null||typeof p!=='object'){return 'Error: $t expects an object like {path:\\\"...\\\"} or a string, got '+(typeof p);}")
                        append("return __minis_call('$t', JSON.stringify(p));}\n")
                    }
                }
                cx.evaluateString(scope, prelude + "\n" + code, "execute_code", 1, null)
            } finally {
                Context.exit()
            }
            val text = bridge.out.toString().ifBlank { "(no print output, ${bridge.toolCalls} tool calls)" }
            ToolExecutionResult(text.take(MAX_STDOUT), true, toolTitle = toolTitle)
            }
        } catch (t: Throwable) {
            ToolExecutionResult(
                "execute_code failed: ${t.javaClass.simpleName}: ${t.message}",
                false,
                toolTitle = toolTitle,
            )
        }
    }
}
