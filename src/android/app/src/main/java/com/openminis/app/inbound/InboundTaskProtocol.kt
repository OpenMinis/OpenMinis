package com.openminis.app.inbound

import org.json.JSONObject
import java.security.MessageDigest

/**
 * [inbound-task-api] Pure request rules for the release inbound task API.
 *
 * The debug server (port 5321 in the Android app, documented as 8321) is a
 * different process surface: debug builds only, and loopback skips its token.
 * This protocol requires a bearer token on every request, including loopback,
 * and it never echoes the prompt or the token back.
 */
object InboundTaskProtocol {

    /**
     * Well-known port. Not 8321 (the documented debug JSON-RPC port) and not
     * 5321 (the port [com.openminis.app.debug.DebugServer] actually binds).
     */
    const val PORT = 9473

    /** Hard cap on the raw HTTP body. Checked before the bytes are allocated. */
    const val MAX_BODY_BYTES = 256 * 1024

    const val MAX_PROMPT_CHARS = 100_000
    const val MAX_LABEL_CHARS = 120
    const val MAX_SESSION_CHARS = 80

    /** Task ids and session ids we will route. UUIDs fit; slashes do not. */
    val ID: Regex = Regex("^[A-Za-z0-9_-]{1,80}$")

    const val MAX_HEADER_LINE = 8 * 1024
    const val MAX_HEADERS = 64

    /**
     * Bearer check that does not stop at the first differing byte.
     *
     * Both sides are hashed to a fixed 32-byte digest and compared with
     * [MessageDigest.isEqual], so a wrong guess of a different length does
     * not take the compare down a shorter path through the secret. An empty
     * expected token never matches, including another empty string — a
     * missing token file must not open the listener.
     */
    fun tokensMatch(provided: String?, expected: String): Boolean {
        if (expected.isEmpty() || provided.isNullOrEmpty()) return false
        val a = MessageDigest.getInstance("SHA-256").digest(provided.toByteArray(Charsets.UTF_8))
        val b = MessageDigest.getInstance("SHA-256").digest(expected.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(a, b)
    }

    /**
     * `Authorization: Bearer <token>`. The scheme is case-insensitive.
     * Anything else — Basic, a bare token, a token with an internal space —
     * is treated as absent so the caller answers 401.
     */
    fun parseBearer(authorization: String?): String? {
        if (authorization.isNullOrBlank()) return null
        val parts = authorization.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return null
        if (!parts[0].equals("Bearer", ignoreCase = true)) return null
        val token = parts[1].trim()
        if (token.isEmpty() || token.any { it.isWhitespace() }) return null
        return token
    }

    /** Path from an origin-form or absolute-form request target, query stripped. */
    fun pathOf(target: String): String {
        val trimmed = target.trim()
        val withoutQuery = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                val schemeEnd = trimmed.indexOf("://")
                val slash = trimmed.indexOf('/', startIndex = schemeEnd + 3)
                if (slash < 0) "/" else trimmed.substring(slash)
            }
            else -> trimmed
        }
        val path = withoutQuery.substringBefore('?').substringBefore('#')
        if (path.length > 1 && path.endsWith('/')) return path.dropLast(1)
        return if (path.isEmpty()) "/" else path
    }

    fun route(method: String, target: String): Route {
        val path = pathOf(target)
        val verb = method.uppercase()
        return when {
            path == "/v1/health" && verb == "GET" -> Route.Health
            path == "/v1/health" -> Route.MethodNotAllowed
            path == "/v1/tasks" && verb == "POST" -> Route.CreateTask
            path == "/v1/tasks" -> Route.MethodNotAllowed
            path.startsWith("/v1/tasks/") && verb == "GET" -> {
                val id = path.removePrefix("/v1/tasks/")
                if (id.matches(ID)) Route.TaskStatus(id) else Route.NotFound
            }
            path.startsWith("/v1/tasks/") -> Route.MethodNotAllowed
            else -> Route.NotFound
        }
    }

    /**
     * Missing Content-Type is accepted (curl often omits it). A declared type
     * must be JSON, and a declared charset must be UTF-8.
     */
    fun acceptsJsonBody(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return true
        val parts = contentType.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true
        val mime = parts[0].lowercase()
        if (mime != "application/json" && mime != "text/json") return false
        val charset = parts.drop(1)
            .firstOrNull { it.lowercase().startsWith("charset=") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.lowercase()
        return charset == null || charset == "utf-8" || charset == "utf8"
    }

    fun parseCreateBody(text: String): CreateBody {
        val obj = try {
            JSONObject(text)
        } catch (_: Exception) {
            return CreateBody.Reject(400, "invalid_json")
        }
        if (!obj.has("prompt") || obj.isNull("prompt")) return CreateBody.Reject(400, "prompt_required")
        val promptRaw = obj.opt("prompt")
        if (promptRaw !is String) return CreateBody.Reject(400, "bad_request")
        val prompt = promptRaw.trim()
        if (prompt.isEmpty()) return CreateBody.Reject(400, "prompt_required")
        if (prompt.length > MAX_PROMPT_CHARS) return CreateBody.Reject(400, "prompt_too_long")

        val session = when (val field = optionalText(obj, "session", "session_invalid", MAX_SESSION_CHARS, idShape = true)) {
            is Field.Bad -> return field.body
            is Field.Value -> field.text
        }
        val label = when (val field = optionalText(obj, "label", "label_too_long", MAX_LABEL_CHARS, idShape = false)) {
            is Field.Bad -> return field.body
            is Field.Value -> field.text
        }
        return CreateBody.Ok(prompt, session, label)
    }

    /** Absent, JSON null, or blank → [Field.Value] with a null text. */
    private fun optionalText(
        obj: JSONObject,
        name: String,
        invalidCode: String,
        maxChars: Int,
        idShape: Boolean,
    ): Field {
        if (!obj.has(name) || obj.isNull(name)) return Field.Value(null)
        val value = obj.opt(name)
        if (value !is String) return Field.Bad(CreateBody.Reject(400, "bad_request"))
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return Field.Value(null)
        if (trimmed.length > maxChars) return Field.Bad(CreateBody.Reject(400, invalidCode))
        if (idShape && !trimmed.matches(ID)) return Field.Bad(CreateBody.Reject(400, invalidCode))
        if (!idShape && trimmed.any { it.code < 32 || it.code == 127 }) {
            return Field.Bad(CreateBody.Reject(400, "bad_request"))
        }
        return Field.Value(trimmed)
    }

    private sealed class Field {
        data class Value(val text: String?) : Field()
        data class Bad(val body: CreateBody.Reject) : Field()
    }

    /**
     * A fire the status endpoint can describe without the prompt.
     * [recorded] is true once a run has been written (lastFiredAt).
     * [ok] is the latest run's flag, ignored unless [recorded].
     */
    fun runStatus(running: Boolean, recorded: Boolean, ok: Boolean?): String = when {
        running -> "running"
        !recorded -> "accepted"
        ok == false -> "failed"
        else -> "completed"
    }

    fun healthJson(port: Int): String =
        JSONObject().put("ok", true).put("port", port).put("auth", "bearer").toString()

    fun createdJson(id: String, sessionId: String, target: String): String =
        JSONObject()
            .put("id", id)
            .put("sessionId", sessionId)
            .put("status", "running")
            .put("target", target)
            .toString()

    fun statusJson(view: TaskView): String = JSONObject().apply {
        put("id", view.id)
        put("label", view.label)
        put("status", view.status)
        put("target", view.target)
        if (view.sessionId == null) put("sessionId", JSONObject.NULL) else put("sessionId", view.sessionId)
        if (view.ok == null) put("ok", JSONObject.NULL) else put("ok", view.ok)
    }.toString()

    fun errorJson(code: String): String = JSONObject().put("error", code).toString()

    sealed class Route {
        data object Health : Route()
        data object CreateTask : Route()
        data class TaskStatus(val id: String) : Route()
        data object MethodNotAllowed : Route()
        data object NotFound : Route()
    }

    sealed class CreateBody {
        data class Ok(val prompt: String, val session: String?, val label: String?) : CreateBody()
        data class Reject(val http: Int, val code: String) : CreateBody()
    }
}

/** Status payload. Deliberately has no prompt field. */
data class TaskView(
    val id: String,
    val label: String,
    val status: String,
    val target: String,
    val sessionId: String?,
    val ok: Boolean?,
)
