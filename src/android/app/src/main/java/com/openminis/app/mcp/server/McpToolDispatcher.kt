package com.openminis.app.mcp.server

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.scheduled.CronScheduler
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import android.content.pm.PackageManager
import java.util.Calendar
import java.util.UUID
import com.openminis.app.security.Decision
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.SecurityGate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tool implementation layer for the built-in MCP server.
 * Dispatches tools/call to the app's real capabilities.
 *
 * [gate] is injectable for tests; defaults to the process-wide SecurityGate.
 *
 * [shellMode] supplies the PermissionMode used by the shell_exec gate decision:
 * the settings UI exposes this as the shell policy (auto → ALLOW_ALL, strict
 * → ASK). Defaults to ALLOW_ALL so a bare dispatcher stays usable; the manager
 * always wires it to the persisted policy. NeedConfirm/Denied outcomes still
 * map to CallResult(errorCode=GATE_DENIED) — the MCP channel has no interactive
 * approval path.
 */
class McpToolDispatcher(
    // Nullable for JVM unit tests (plain-junit, no Robolectric): every
    // dereference below is guarded and degrades to an error result.
    private val context: Context?,
    private val gate: SecurityGate,
    private val shellMode: () -> PermissionMode = { PermissionMode.ALLOW_ALL },
) : McpServerCore.ToolDispatcher {

    companion object {
        const val SESSION_ID = "mcp-server"
        const val MAX_FILE_READ_CHARS = 80_000
        /** Max chars accepted by file_write per call (256 KB). */
        const val MAX_FILE_WRITE_CHARS = 262_144
        val FILE_READ_PREFIXES = listOf("/var/minis/", "/sdcard/")

        /** Strict-policy hint appended to shell_exec gate denial messages. */
        const val SHELL_STRICT_HINT =
            "当前 shell 策略为 strict，命令需要用户确认（MCP 通道无法弹确认）。" +
                "可在 设置 → MCP → 内置 Server 把 shell 策略切到 auto。"

        /** Maximum coordinate value accepted for ui_action tap/long_press/swipe. */
        const val MAX_COORD = 20_000

        /** Allowed ui_action action names. */
        val UI_ACTIONS = setOf("tap", "long_press", "type", "swipe", "scroll", "back", "open_app", "open_url")

        /** Allowed scroll directions. */
        val SCROLL_DIRECTIONS = setOf("up", "down", "left", "right")

        /**
         * Validate a screen coordinate coming from JSON. Accepts only true
         * integers (JSON numbers without a fraction, or numeric strings) in
         * [0, MAX_COORD]; rejects everything else with a reason instead of
         * silently substituting an empty string.
         */
        fun validateCoord(raw: Any?, coordName: String): Pair<Int?, String?> {
            if (raw == null || raw == JSONObject.NULL) return null to "missing '$coordName' coordinate"
            val d: Double = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                    ?: return null to "'$coordName' is not a number: '$raw'"
                else -> return null to "'$coordName' has unexpected type: ${raw::class.simpleName}"
            }
            if (d != d.toLong().toDouble()) return null to "'$coordName' is not an integer: $d"
            val i = d.toInt()
            if (i < 0) return null to "'$coordName' is negative: $i"
            if (i > MAX_COORD) return null to "'$coordName' exceeds maximum $MAX_COORD: $i"
            return i to null
        }

        /**
         * Shell-escape a value into a single-quoted POSIX word:
         * wrap in single quotes and turn each embedded quote into '\''.
         * Every string interpolated into a shell command must go through this.
         */
        fun shellEscape(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        fun text(content: String): JSONObject = JSONObject().apply {
            put("type", "text")
            put("text", content)
        }

        /**
         * Pure path validation, exposed for unit tests. Returns null when the
         * normalized path is allowed, otherwise a deny reason string.
         */
        fun validateFilePath(rawPath: String): String? {
            val normalized = normalizePath(rawPath)
            if (normalized.isEmpty()) return "path is empty"
            val allowed = FILE_READ_PREFIXES.any { prefix ->
                val root = prefix.trimEnd('/')
                normalized == prefix || normalized == root || normalized.startsWith(prefix)
            }
            if (!allowed) {
                return "path '$normalized' is outside allowed prefixes (${FILE_READ_PREFIXES.joinToString()})"
            }
            // Reject traversal attempts that survived normalization.
            val segments = normalized.split('/')
            if (segments.any { it == ".." }) return "path contains '..'"
            return null
        }

        /** Normalize slashes and collapse '.'/'..' segments (no filesystem access). */
        fun normalizePath(path: String): String {
            var p = path.trim().replace('\\', '/')
            while (p.contains("//")) p = p.replace("//", "/")
            val segments = mutableListOf<String>()
            for (seg in p.split('/')) {
                when {
                    seg.isEmpty() || seg == "." -> Unit
                    seg == ".." -> {
                        // [T-removelast-jdk21] NOT removeLast(): Kotlin 2.1
                        // resolving against a JDK 21+ classpath binds
                        // MutableList.removeLast() to the SequencedCollection
                        // member, which does not exist on JDK 17 runtimes —
                        // CI's test JVM died with NoSuchMethodError here —
                        // nor in Android's java.util. removeAt(index) is
                        // ArrayList.remove(int): present everywhere, on
                        // every JDK and on ART.
                        if (segments.isNotEmpty() && segments.last() != "..") segments.removeAt(segments.size - 1)
                        else if (segments.isEmpty()) return "" // escaping above root
                    }
                    else -> segments.add(seg)
                }
            }
            val body = segments.joinToString("/")
            return when {
                !p.startsWith("/") -> body
                body.isEmpty() -> "/"
                p.endsWith("/") -> "/$body/"
                else -> "/$body"
            }
        }
    }

    override fun listTools(): JSONArray {
        return JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "device_info")
                    put("description", "Get device information: model, manufacturer, Android version, battery level.")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "shell_exec")
                    put(
                        "description",
                        "Execute a command in an isolated Linux process (Ubuntu 24.04 arm64 via PRoot). " +
                            "The command runs in GNU bash (/bin/bash). stdout and stderr are merged. " +
                            "Use 'command' to specify the shell command, optional 'timeout_sec' to limit execution.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().apply { put("type", "string") })
                            put("timeout_sec", JSONObject().apply { put("type", "integer") })
                        })
                        put("required", JSONArray().put("command"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "ui_read")
                    put("description", "Read a bounded snapshot of the current foreground screen via the accessibility tree.")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "ui_action")
                    put(
                        "description",
                        "Perform one UI action via the accessibility service: tap, type, swipe, scroll, back, open_app, open_url, long_press.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().apply { put("type", "string") })
                            put("x", JSONObject().apply { put("type", "integer") })
                            put("y", JSONObject().apply { put("type", "integer") })
                            put("x2", JSONObject().apply { put("type", "integer") })
                            put("y2", JSONObject().apply { put("type", "integer") })
                            put("text", JSONObject().apply { put("type", "string") })
                            put("direction", JSONObject().apply { put("type", "string") })
                            put("package_name", JSONObject().apply { put("type", "string") })
                            put("url", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("action"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "file_read")
                    put(
                        "description",
                        "Read a file from the Linux filesystem. Restricted to /var/minis/ and /sdcard/ prefixes. Binary files return a type guess and a bounded hex preview instead of raw contents.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("path"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "file_write")
                    put(
                        "description",
                        "Write a UTF-8 text file to the Linux filesystem. Restricted to /var/minis/ and /sdcard/ prefixes; max 256 KB per call.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply { put("type", "string") })
                            put("content", JSONObject().apply { put("type", "string") })
                            put("append", JSONObject().apply {
                                put("type", "boolean")
                                put("description", "Append to the file instead of overwriting (default false).")
                            })
                        })
                        put("required", JSONArray().put("path").put("content"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "file_transfer_pull")
                    put(
                        "description",
                        "Read a binary or text file from the Linux filesystem and return it base64-encoded with size and sha256. Same path restrictions as file_read; max 8 MB per call.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("path"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "file_transfer_push")
                    put(
                        "description",
                        "Write base64-encoded content to a file on the Linux filesystem (binary-safe). Same path restrictions as file_write; max 8 MB decoded per call.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("path", JSONObject().apply { put("type", "string") })
                            put("content_base64", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("path").put("content_base64"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "schedule_task_create")
                    put(
                        "description",
                        "Create a scheduled task that fires an agent prompt. Schedule: '30m', '2h', '1d' (once) or 'every 30m' / 'every 2h' / 'every 1d' (repeating).",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("name", JSONObject().apply { put("type", "string") })
                            put("schedule", JSONObject().apply { put("type", "string") })
                            put("prompt", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("schedule").put("prompt"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "schedule_task_list")
                    put("description", "List all scheduled tasks with id, name, schedule, next trigger, and enabled state.")
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {})
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "schedule_task_update")
                    put(
                        "description",
                        "Enable or disable a scheduled task by id (accepts a unique id prefix).",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("id", JSONObject().apply { put("type", "string") })
                            put("enabled", JSONObject().apply { put("type", "boolean") })
                        })
                        put("required", JSONArray().put("id").put("enabled"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "schedule_task_delete")
                    put(
                        "description",
                        "Delete a scheduled task by id (accepts a unique id prefix).",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("id", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("id"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "alarm_reminder_create")
                    put(
                        "description",
                        "Create a one-shot reminder that runs an agent prompt after a delay. Equivalent to a schedule_task with a 'once' schedule.",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("delay_sec", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Seconds from now until the reminder fires (60-86400).")
                            })
                            put("message", JSONObject().apply {
                                put("type", "string")
                                put("description", "Agent prompt to run when the reminder fires.")
                            })
                            put("name", JSONObject().apply { put("type", "string") })
                        })
                        put("required", JSONArray().put("delay_sec").put("message"))
                    })
                },
            )
            put(
                JSONObject().apply {
                    put("name", "context_apps_query")
                    put(
                        "description",
                        "List launchable apps on this device (label + package name). Optional 'filter' substring matches label or package, case-insensitive. Optional 'limit' caps results (default 50, max 200).",
                    )
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("filter", JSONObject().apply { put("type", "string") })
                            put("limit", JSONObject().apply { put("type", "integer") })
                        })
                    })
                },
            )
        }
    }

    override fun callTool(name: String, arguments: JSONObject): McpServerCore.CallResult {
        return when (name) {
            "device_info" -> deviceInfo()
            "shell_exec" -> shellExec(arguments)
            "ui_read" -> uiRead()
            "ui_action" -> uiAction(arguments)
            "file_read" -> fileRead(arguments)
            "file_write" -> fileWrite(arguments)
            "file_transfer_pull" -> fileTransferPull(arguments)
            "file_transfer_push" -> fileTransferPush(arguments)
            "schedule_task_create" -> scheduleTaskCreate(arguments)
            "schedule_task_list" -> scheduleTaskList()
            "schedule_task_update" -> scheduleTaskUpdate(arguments)
            "schedule_task_delete" -> scheduleTaskDelete(arguments)
            "alarm_reminder_create" -> alarmReminderCreate(arguments)
            "context_apps_query" -> contextAppsQuery(arguments)
            else -> McpServerCore.CallResult(
                JSONArray().put(text("Unknown tool: $name")),
                isError = true,
            )
        }
    }

    // ─── device_info ───────────────────────────────────────────────────────

    private fun deviceInfo(): McpServerCore.CallResult {
        val battery = batteryPercent()
        val result = JSONObject().apply {
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("android_version", Build.VERSION.RELEASE)
            put("battery_percent", battery)
        }
        return McpServerCore.CallResult(
            JSONArray().put(text(result.toString(2))),
        )
    }

    private fun batteryPercent(): Int? {
        return runCatching {
            val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
    }

    // ─── shell_exec ────────────────────────────────────────────────────────

    private fun shellExec(arguments: JSONObject): McpServerCore.CallResult {
        val command = arguments.optString("command", "").ifEmpty {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'command' is required")),
                isError = true,
            )
        }
        val timeoutSec = arguments.optInt("timeout_sec", 60).coerceIn(1, 900)

        // Gate: classify + decide with the policy-driven mode (auto → ALLOW_ALL,
        // strict → ASK). No interactive approval on this channel — need-confirm /
        // denied both become a JSON-RPC error so the client gets an explicit verdict.
        val argsJson = JSONObject().put("command", command).toString()
        val gateCommand = gate.classify("shell_execute", argsJson)
        val mode = shellMode()
        val decision = gate.decide(gateCommand, mode)
        gate.audit(gateCommand, decision, null)
        when (decision) {
            is Decision.Denied -> {
                return McpServerCore.CallResult(
                    JSONArray().put(text("SecurityGate denied: ${decision.reason}")),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                // Under strict the ask-for-approval verdict is expected policy
                // behavior, not an anomaly — append a hint pointing at the
                // settings toggle so MCP clients (and their humans) can recover.
                val policyHint = if (mode == PermissionMode.ASK) " $SHELL_STRICT_HINT" else ""
                return McpServerCore.CallResult(
                    JSONArray().put(
                        text(
                            "This command requires user approval which is not available on the " +
                                "MCP server channel. Reason: ${decision.reason}. Preview: $preview.$policyHint",
                        ),
                    ),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.Allow -> {
                // proceed
            }
        }

        // Execute via ExecutionCoordinator in the server's own workspace.
        val result = runCatching {
            runBlocking {
                ExecutionCoordinator.execute(
                    sessionId = SESSION_ID,
                    command = command,
                    timeout = timeoutSec * 1000L,
                )
            }
        }.getOrElse {
            return McpServerCore.CallResult(
                JSONArray().put(text("Execution failed: ${it.message}")),
                isError = true,
            )
        }

        val output = JSONObject().apply {
            put("exit_code", result.exitCode)
            put("output", result.output)
            put("duration_ms", result.durationMs)
        }
        return McpServerCore.CallResult(
            JSONArray().put(text(output.toString(2))),
            isError = result.exitCode != 0,
        )
    }

    // ─── ui_read ───────────────────────────────────────────────────────────

    private fun uiRead(): McpServerCore.CallResult {
        val command = """
            if [ -x /usr/local/bin/android-a11y-cli ]; then
              android-a11y-cli read-screen 2>&1 | head -c 6000 || true
            else
              echo "android-a11y-cli not available in sandbox"
            fi
        """.trimIndent()

        // Gate the assembled command string exactly like shell_exec:
        // classify → decide → audit; denied / need-confirm map to -32001.
        val argsJson = JSONObject().put("command", command).toString()
        val gateCommand = gate.classify("ui_read", argsJson)
        val mode = shellMode()
        val decision = gate.decide(gateCommand, mode)
        gate.audit(gateCommand, decision, null)
        when (decision) {
            is Decision.Denied -> {
                return McpServerCore.CallResult(
                    JSONArray().put(text("SecurityGate denied: ${decision.reason}")),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                val policyHint = if (mode == PermissionMode.ASK) " $SHELL_STRICT_HINT" else ""
                return McpServerCore.CallResult(
                    JSONArray().put(
                        text(
                            "This command requires user approval which is not available on the " +
                                "MCP server channel. Reason: ${decision.reason}. Preview: $preview.$policyHint",
                        ),
                    ),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.Allow -> {
                // proceed
            }
        }

        return executeInGuest(command, 30)
    }

    // ─── ui_action ─────────────────────────────────────────────────────────

    private fun uiAction(arguments: JSONObject): McpServerCore.CallResult {
        val action = arguments.optString("action", "").lowercase()
        if (action.isEmpty()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'action' is required (${UI_ACTIONS.joinToString("/")})")),
                isError = true,
            )
        }
        if (action !in UI_ACTIONS) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: unknown action '$action'. Allowed: ${UI_ACTIONS.joinToString(", ")}")),
                isError = true,
            )
        }

        // Build the command string with validateCoord + shellEscape on every
        // user-supplied value. Coordinates must be true integers in [0, MAX_COORD];
        // direction is validated against SCROLL_DIRECTIONS; strings are
        // single-quote-escaped.
        val cmd: String = when (action) {
            "tap" -> {
                val (x, xErr) = validateCoord(arguments.opt("x"), "x")
                val (y, yErr) = validateCoord(arguments.opt("y"), "y")
                val err = xErr ?: yErr
                if (err != null) return McpServerCore.CallResult(
                    JSONArray().put(text("Error: $err")), isError = true)
                "android-a11y-cli tap ${shellEscape(x!!.toString())} ${shellEscape(y!!.toString())}"
            }
            "long_press" -> {
                val (x, xErr) = validateCoord(arguments.opt("x"), "x")
                val (y, yErr) = validateCoord(arguments.opt("y"), "y")
                val err = xErr ?: yErr
                if (err != null) return McpServerCore.CallResult(
                    JSONArray().put(text("Error: $err")), isError = true)
                "android-a11y-cli long-press ${shellEscape(x!!.toString())} ${shellEscape(y!!.toString())}"
            }
            "type" -> {
                val textVal = arguments.optString("text", "")
                "android-a11y-cli type ${shellEscape(textVal)}"
            }
            "swipe" -> {
                val (x, xErr) = validateCoord(arguments.opt("x"), "x")
                val (y, yErr) = validateCoord(arguments.opt("y"), "y")
                val (x2, x2Err) = validateCoord(arguments.opt("x2"), "x2")
                val (y2, y2Err) = validateCoord(arguments.opt("y2"), "y2")
                val err = xErr ?: yErr ?: x2Err ?: y2Err
                if (err != null) return McpServerCore.CallResult(
                    JSONArray().put(text("Error: $err")), isError = true)
                "android-a11y-cli swipe ${shellEscape(x!!.toString())} ${shellEscape(y!!.toString())} ${shellEscape(x2!!.toString())} ${shellEscape(y2!!.toString())}"
            }
            "scroll" -> {
                val direction = arguments.optString("direction", "down").lowercase()
                if (direction !in SCROLL_DIRECTIONS) {
                    return McpServerCore.CallResult(
                        JSONArray().put(text(
                            "Error: invalid direction '$direction'. Allowed: ${SCROLL_DIRECTIONS.joinToString(", ")}")),
                        isError = true,
                    )
                }
                "android-a11y-cli scroll ${shellEscape(direction)}"
            }
            "back" -> "android-a11y-cli back"
            "open_app" -> {
                val pkg = arguments.optString("package_name", "")
                "android-shizuku-cli launch ${shellEscape(pkg)}"
            }
            "open_url" -> {
                val url = arguments.optString("url", "")
                "android-open ${shellEscape(url)}"
            }
            else -> error("unreachable: action validated above")
        }

        val script = """
            if [ -x /usr/local/bin/android-a11y-cli ]; then
              $cmd 2>&1 || true
            else
              echo "android-a11y-cli not available in sandbox"
            fi
        """.trimIndent()

        // SecurityGate: classify → decide → audit on the assembled command.
        val argsJson = JSONObject().put("command", script).toString()
        val gateCommand = gate.classify("ui_action", argsJson)
        val mode = shellMode()
        val decision = gate.decide(gateCommand, mode)
        gate.audit(gateCommand, decision, null)
        when (decision) {
            is Decision.Denied -> {
                return McpServerCore.CallResult(
                    JSONArray().put(text("SecurityGate denied: ${decision.reason}")),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.NeedConfirm -> {
                val preview = decision.preview.take(240).ifBlank { decision.reason }
                val policyHint = if (mode == PermissionMode.ASK) " $SHELL_STRICT_HINT" else ""
                return McpServerCore.CallResult(
                    JSONArray().put(
                        text(
                            "This command requires user approval which is not available on the " +
                                "MCP server channel. Reason: ${decision.reason}. Preview: $preview.$policyHint",
                        ),
                    ),
                    isError = true,
                    errorCode = McpServerCore.ErrorCode.GATE_DENIED,
                    errorData = decision.reason,
                )
            }
            is Decision.Allow -> {
                // proceed
            }
        }

        return executeInGuest(script, 30)
    }

    /**
     * Execute a shell command inside the PRoot guest via [ExecutionCoordinator],
     * exactly like [shellExec]. Replaces the old runHostShell, which used a host
     * ProcessBuilder("/bin/bash") — stock Android has no /bin/bash on the host,
     * and the android-a11y-cli binary lives inside the guest rootfs, so the host
     * path could never work on a real device.
     *
     * ExecutionCoordinator.execute is a suspend function that needs no coroutine
     * context from the caller (runBlocking suffices, same as shellExec) and runs
     * commands for SESSION_ID ("mcp-server") — the workspace dirs for that id
     * are materialized at server start via ensureMcpServerWorkspace.
     */
    private fun executeInGuest(script: String, timeoutSec: Int = 30): McpServerCore.CallResult {
        val result = runCatching {
            runBlocking {
                ExecutionCoordinator.execute(
                    sessionId = SESSION_ID,
                    command = script,
                    timeout = timeoutSec * 1000L,
                )
            }
        }.getOrElse {
            return McpServerCore.CallResult(
                JSONArray().put(text("Execution failed: ${it.message}")),
                isError = true,
            )
        }
        val output = if (result.output.isNotBlank()) result.output.trim() else "(no output)"
        return McpServerCore.CallResult(
            JSONArray().put(text(output)),
            isError = result.exitCode != 0,
        )
    }

    // ─── file_read ────────────────────────────────────────────────────────


    private fun fileRead(arguments: JSONObject): McpServerCore.CallResult {
        val rawPath = arguments.optString("path", "").ifEmpty {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'path' is required")),
                isError = true,
            )
        }

        validateFilePath(rawPath)?.let { reason ->
            return McpServerCore.CallResult(
                JSONArray().put(text("Path denied: $reason")),
                isError = true,
                errorCode = McpServerCore.ErrorCode.PATH_DENIED,
                errorData = reason,
            )
        }

        val normalized = normalizePath(rawPath)

        // Resolve the Linux path to a host file through the session workspace,
        // exactly like FileReadTool does — so /var/minis/workspace reads resolve
        // inside the mcp-server session tree, not some other chat's.
        val ctx = context
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: no host context available")),
                isError = true,
            )
        val file: File = PRootKernel.resolveSessionHostPath(SESSION_ID, normalized, ctx)
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: cannot resolve path '$normalized' (rootfs-only guest path?)")),
                isError = true,
            )

        if (!file.exists()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: file not found: $normalized")),
                isError = true,
            )
        }
        if (file.isDirectory) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: path is a directory: $normalized")),
                isError = true,
            )
        }

        return runCatching {
            val size = file.length()
            // [T-binary-preview] shared with the agent-side file_read: a
            // binary is a type guess plus a bounded hex preview, not a dead
            // end; UTF-16 BOM text decodes instead of mislabelled binary.
            val head = com.openminis.app.tools.FileReadTool.readHead(file, minOf(8192L, size).toInt())
            val textCharset = com.openminis.app.tools.FileReadTool.utf16CharsetOrNull(head)
            val isBinary = textCharset == null && head.any { it == 0.toByte() }
            if (isBinary) {
                return McpServerCore.CallResult(
                    JSONArray().put(
                        text(com.openminis.app.tools.FileReadTool.binaryPreview(normalized, size, head))
                    ),
                )
            }
            val raw = file.readText(textCharset ?: Charsets.UTF_8)
            val content = if (raw.length > MAX_FILE_READ_CHARS) {
                raw.take(MAX_FILE_READ_CHARS) + "\n... (truncated at $MAX_FILE_READ_CHARS chars)"
            } else {
                raw
            }
            val header = "[$normalized | $size bytes]"
            McpServerCore.CallResult(JSONArray().put(text("$header\n$content")))
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error reading file: ${it.message}")),
                isError = true,
            )
        }
    }

    // ─── file_write ───────────────────────────────────────────────────────

    private fun fileWrite(arguments: JSONObject): McpServerCore.CallResult {
        val rawPath = arguments.optString("path", "").ifEmpty {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'path' is required")),
                isError = true,
            )
        }
        val content = arguments.optString("content", "")
        if (content.length > MAX_FILE_WRITE_CHARS) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: content exceeds $MAX_FILE_WRITE_CHARS chars (got ${content.length})")),
                isError = true,
            )
        }
        val append = arguments.optBoolean("append", false)

        validateFilePath(rawPath)?.let { reason ->
            return McpServerCore.CallResult(
                JSONArray().put(text("Path denied: $reason")),
                isError = true,
                errorCode = McpServerCore.ErrorCode.PATH_DENIED,
                errorData = reason,
            )
        }

        val normalized = normalizePath(rawPath)
        val ctx = context
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: no host context available")),
                isError = true,
            )
        val file: File = PRootKernel.resolveSessionHostPath(SESSION_ID, normalized, ctx)
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: cannot resolve path '$normalized' (rootfs-only guest path?)")),
                isError = true,
            )
        if (file.isDirectory) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: path is a directory: $normalized")),
                isError = true,
            )
        }

        return runCatching {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            McpServerCore.CallResult(
                JSONArray().put(text("[wrote $normalized | ${content.length} chars | ${if (append) "appended" else "overwritten"}]")),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error writing file: ${it.message}")),
                isError = true,
            )
        }
    }

    // ─── file_transfer_pull / file_transfer_push ──────────────────────────

    /** Max bytes per transfer direction — base64 inflates by 4/3, so an 8 MB
     *  payload arrives as ~10.7 MB of JSON; keep both under control. */
    private val maxTransferBytes = 8L * 1024 * 1024

    private fun fileTransferPull(arguments: JSONObject): McpServerCore.CallResult {
        val rawPath = arguments.optString("path", "").ifEmpty {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'path' is required")),
                isError = true,
            )
        }
        validateFilePath(rawPath)?.let { reason ->
            return McpServerCore.CallResult(
                JSONArray().put(text("Path denied: $reason")),
                isError = true,
                errorCode = McpServerCore.ErrorCode.PATH_DENIED,
                errorData = reason,
            )
        }
        val normalized = normalizePath(rawPath)
        val ctx = context
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: no host context available")),
                isError = true,
            )
        val file: File = PRootKernel.resolveSessionHostPath(SESSION_ID, normalized, ctx)
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: cannot resolve path '$normalized' (rootfs-only guest path?)")),
                isError = true,
            )
        if (!file.exists()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: file not found: $normalized")),
                isError = true,
            )
        }
        if (file.isDirectory) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: path is a directory: $normalized")),
                isError = true,
            )
        }
        if (file.length() > maxTransferBytes) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: file is ${file.length()} bytes, exceeds $maxTransferBytes byte limit")),
                isError = true,
            )
        }
        return runCatching {
            val bytes = file.readBytes()
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            // Structured payload so the client can machine-parse instead of
            // scraping text: a header line then the base64 block.
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            McpServerCore.CallResult(
                JSONArray().put(
                    text("[path: $normalized | size: ${bytes.size} | sha256: $sha]\n$b64"),
                ),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error reading file: ${it.message}")),
                isError = true,
            )
        }
    }

    private fun fileTransferPush(arguments: JSONObject): McpServerCore.CallResult {
        val rawPath = arguments.optString("path", "").ifEmpty {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'path' is required")),
                isError = true,
            )
        }
        val b64 = arguments.optString("content_base64", "")
        if (b64.isEmpty()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'content_base64' is required")),
                isError = true,
            )
        }
        // Reject before decode: base64 length maps 4:3 to bytes, so the byte
        // cap can be enforced on the encoded length without allocating.
        if (b64.length > (maxTransferBytes * 4 / 3 + 4)) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: content exceeds $maxTransferBytes byte limit (base64 length ${b64.length})")),
                isError = true,
            )
        }
        validateFilePath(rawPath)?.let { reason ->
            return McpServerCore.CallResult(
                JSONArray().put(text("Path denied: $reason")),
                isError = true,
                errorCode = McpServerCore.ErrorCode.PATH_DENIED,
                errorData = reason,
            )
        }
        val normalized = normalizePath(rawPath)
        val ctx = context
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: no host context available")),
                isError = true,
            )
        val file: File = PRootKernel.resolveSessionHostPath(SESSION_ID, normalized, ctx)
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: cannot resolve path '$normalized' (rootfs-only guest path?)")),
                isError = true,
            )
        if (file.isDirectory) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: path is a directory: $normalized")),
                isError = true,
            )
        }
        return runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            if (bytes.size > maxTransferBytes) {
                return McpServerCore.CallResult(
                    JSONArray().put(text("Error: decoded content is ${bytes.size} bytes, exceeds $maxTransferBytes limit")),
                    isError = true,
                )
            }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            McpServerCore.CallResult(
                JSONArray().put(text("[wrote $normalized | ${bytes.size} bytes | sha256: $sha]")),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error writing file: ${it.message}")),
                isError = true,
            )
        }
    }

    // ─── schedule_task_* / alarm_reminder_* ───────────────────────────────

    /** Resolve the ScheduledTaskManager, or an error result when no context. */
    private fun taskManager(): Pair<ScheduledTaskManager?, McpServerCore.CallResult?> {
        val ctx = context ?: return null to McpServerCore.CallResult(
            JSONArray().put(text("Error: no host context available")),
            isError = true,
        )
        return ScheduledTaskManager(ctx) to null
    }

    private fun scheduleTaskCreate(arguments: JSONObject): McpServerCore.CallResult {
        val schedule = arguments.optString("schedule", "").trim()
        val prompt = arguments.optString("prompt", "").trim()
        if (prompt.isEmpty()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'prompt' is required")),
                isError = true,
            )
        }
        val parsed = CronScheduler.parseSchedule(schedule)
            ?: return McpServerCore.CallResult(
                JSONArray().put(
                    text("Error: schedule must look like '30m', '2h', '1d', or 'every 30m'/'every 2h'/'every 1d'"),
                ),
                isError = true,
            )
        val (manager, err) = taskManager()
        if (manager == null) return err!!
        return runCatching { createScheduledTask(manager, schedule, prompt, arguments.optString("name", "").trim()) }
            .getOrElse {
                McpServerCore.CallResult(
                    JSONArray().put(text("Error creating task: ${it.message}")),
                    isError = true,
                )
            }
    }

    private fun alarmReminderCreate(arguments: JSONObject): McpServerCore.CallResult {
        val delaySec = arguments.optInt("delay_sec", 0)
        if (delaySec < 60 || delaySec > 86_400) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'delay_sec' must be 60-86400")),
                isError = true,
            )
        }
        val message = arguments.optString("message", "").trim()
        if (message.isEmpty()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'message' is required")),
                isError = true,
            )
        }
        val (manager, err) = taskManager()
        if (manager == null) return err!!
        return runCatching {
            val now = System.currentTimeMillis()
            val fireAt = now + delaySec * 1000L
            val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
            val task = ScheduledTask(
                id = UUID.randomUUID().toString(),
                label = arguments.optString("name", "").trim().ifBlank { message.take(32) },
                timeOfDayHour = cal.get(Calendar.HOUR_OF_DAY),
                timeOfDayMinute = cal.get(Calendar.MINUTE),
                repeatMode = ScheduledRepeatMode.ONCE,
                prompt = message,
                fireAtMs = fireAt,
                createdAt = now,
            )
            val saved = manager.create(task)
            McpServerCore.CallResult(
                JSONArray().put(
                    text(
                        JSONObject().apply {
                            put("ok", true)
                            put("id", saved.id)
                            put("name", saved.label)
                            put("fireAtMs", saved.fireAtMs ?: fireAt)
                            put("repeat", "ONCE")
                        }.toString(2),
                    ),
                ),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error creating reminder: ${it.message}")),
                isError = true,
            )
        }
    }

    private fun createScheduledTask(
        manager: ScheduledTaskManager,
        schedule: String,
        prompt: String,
        name: String,
    ): McpServerCore.CallResult {
        val parsed = CronScheduler.parseSchedule(schedule)
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: bad schedule '$schedule'")),
                isError = true,
            )
        val now = System.currentTimeMillis()
        val fireAt = now + parsed.firstDelayMs
        val cal = Calendar.getInstance().apply { timeInMillis = fireAt }
        val interval = parsed.kind == "interval"
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            label = name.ifBlank { prompt.take(32) },
            timeOfDayHour = cal.get(Calendar.HOUR_OF_DAY),
            timeOfDayMinute = cal.get(Calendar.MINUTE),
            repeatMode = if (interval) ScheduledRepeatMode.INTERVAL else ScheduledRepeatMode.ONCE,
            prompt = prompt,
            intervalMinutes = parsed.intervalMinutes,
            fireAtMs = fireAt,
            createdAt = now,
        )
        val saved = manager.create(task)
        return McpServerCore.CallResult(
            JSONArray().put(
                text(
                    JSONObject().apply {
                        put("ok", true)
                        put("id", saved.id)
                        put("name", saved.label)
                        put("schedule", schedule)
                        put("fireAtMs", saved.fireAtMs ?: fireAt)
                        put("repeat", saved.repeatMode.name)
                        if (saved.id != task.id) put("already_existed", true)
                    }.toString(2),
                ),
            ),
        )
    }

    private fun scheduleTaskList(): McpServerCore.CallResult {
        val (manager, err) = taskManager()
        if (manager == null) return err!!
        return runCatching {
            val tasks = manager.list()
            val arr = JSONArray()
            for (t in tasks) {
                arr.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("name", t.label)
                        put("enabled", t.enabled)
                        put("repeat", t.repeatMode.name)
                        if (t.intervalMinutes > 0) put("intervalMinutes", t.intervalMinutes)
                        if (t.fireAtMs != null) put("fireAtMs", t.fireAtMs)
                        put("nextTriggerMs", t.nextTriggerMs() ?: JSONObject.NULL)
                        put("prompt", t.prompt.take(200))
                    },
                )
            }
            McpServerCore.CallResult(
                JSONArray().put(text(JSONObject().put("count", tasks.size).put("tasks", arr).toString(2))),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error listing tasks: ${it.message}")),
                isError = true,
            )
        }
    }

    private fun scheduleTaskUpdate(arguments: JSONObject): McpServerCore.CallResult {
        val idRaw = arguments.optString("id", "").trim()
        if (idRaw.isEmpty()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'id' is required")),
                isError = true,
            )
        }
        val enabled = arguments.optBoolean("enabled", true)
        val (manager, err) = taskManager()
        if (manager == null) return err!!
        return runCatching {
            val id = manager.resolveId(idRaw)
                ?: return McpServerCore.CallResult(
                    JSONArray().put(text("Error: no task matches id/prefix '$idRaw'")),
                    isError = true,
                )
            manager.setEnabled(id, enabled)
            McpServerCore.CallResult(
                JSONArray().put(text("[$id ${if (enabled) "enabled" else "disabled"}]")),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error updating task: ${it.message}")),
                isError = true,
            )
        }
    }

    private fun scheduleTaskDelete(arguments: JSONObject): McpServerCore.CallResult {
        val idRaw = arguments.optString("id", "").trim()
        if (idRaw.isEmpty()) {
            return McpServerCore.CallResult(
                JSONArray().put(text("Error: 'id' is required")),
                isError = true,
            )
        }
        val (manager, err) = taskManager()
        if (manager == null) return err!!
        return runCatching {
            val id = manager.resolveId(idRaw)
                ?: return McpServerCore.CallResult(
                    JSONArray().put(text("Error: no task matches id/prefix '$idRaw'")),
                    isError = true,
                )
            val deleted = manager.delete(id)
            McpServerCore.CallResult(
                JSONArray().put(text(if (deleted) "[deleted $id]" else "[no task $id]")),
                isError = !deleted,
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error deleting task: ${it.message}")),
                isError = true,
            )
        }
    }

    // ─── context_apps_query ───────────────────────────────────────────────

    private fun contextAppsQuery(arguments: JSONObject): McpServerCore.CallResult {
        val ctx = context
            ?: return McpServerCore.CallResult(
                JSONArray().put(text("Error: no host context available")),
                isError = true,
            )
        val filter = arguments.optString("filter", "").trim().lowercase()
        val limit = arguments.optInt("limit", 50).coerceIn(1, 200)
        return runCatching {
            val pm = ctx.packageManager
            val launcher = android.content.Intent(
                android.content.Intent.ACTION_MAIN,
                null,
            ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .asSequence()
                .map { it.activityInfo }
                .filter { it.packageName != ctx.packageName }
                .map { info ->
                    val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                    Triple(label, info.packageName, info.name)
                }
                .filter { (label, pkg, _) ->
                    filter.isEmpty() ||
                        label.lowercase().contains(filter) ||
                        pkg.lowercase().contains(filter)
                }
                .sortedBy { (label, _, _) -> label.lowercase() }
                .take(limit)
                .toList()
            val arr = JSONArray()
            for ((label, pkg, activity) in apps) {
                arr.put(
                    JSONObject().apply {
                        put("label", label)
                        put("package", pkg)
                        put("activity", activity)
                    },
                )
            }
            McpServerCore.CallResult(
                JSONArray().put(
                    text(
                        JSONObject().put("count", apps.size).put("apps", arr).toString(2),
                    ),
                ),
            )
        }.getOrElse {
            McpServerCore.CallResult(
                JSONArray().put(text("Error querying apps: ${it.message}")),
                isError = true,
            )
        }
    }
}
