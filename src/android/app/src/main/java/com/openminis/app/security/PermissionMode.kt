package com.openminis.app.security

/**
 * Adapted from XINCODE-Public PermissionMode (GPL-3.0-or-later).
 * https://github.com/kusesad-1122/XINCODE-Public
 */
enum class PermissionMode {
    ALLOW_ALL,
    ASK,
    DENY_ALL,
    READ_ONLY,
    PLAN,
    ;

    /** Session-facing pair. Sub-agent fences still use READ_ONLY / PLAN / DENY_ALL. */
    fun isYoyo(): Boolean = this == ALLOW_ALL

    companion object {
        /**
         * Session default is ASK. Only the 2.0.4+ menu token "YOYO" stays
         * full-auto. Null, blank, READ_ONLY / PLAN / DENY_ALL, leftover
         * global ALLOW_ALL, and unknown names all land on ASK so a
         * 2.0.3 → 2.0.4 upgrade never inherits "全部允许".
         */
        fun sessionDefault(raw: String?): PermissionMode {
            val token = raw?.trim()?.uppercase().orEmpty()
            return if (token == "YOYO") ALLOW_ALL else ASK
        }
    }
}

/**
 * Session "允许本会话全部操作" and the global ALLOW_ALL switch must hit the
 * same gate. Rules are still evaluated first inside [SecurityGate.decide].
 */
fun effectivePermissionMode(stored: PermissionMode, sessionAllowAll: Boolean): PermissionMode =
    if (sessionAllowAll) PermissionMode.ALLOW_ALL else stored

/**
 * Session allow-all skips ordinary confirms. A fatal command still prompts,
 * the same as global ALLOW_ALL: `rm -rf /` asks, it does not run.
 */
fun sessionAllowAllSkipsPrompt(sessionAllowAll: Boolean, mustPrompt: Boolean): Boolean =
    sessionAllowAll && !mustPrompt
