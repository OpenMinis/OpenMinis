package com.openminis.app.auth

/**
 * [T-android-oauth-log-redact] Log-safe renderings of OAuth secrets.
 *
 * App log files are exported with bug reports, so an authorization code, PKCE
 * verifier, `state`, device/user code or access/refresh token must never reach
 * them verbatim. Callers log [secret] (length + first 4 chars, enough to tell
 * two values apart when diagnosing a mismatch) or [url] for a redirect/request
 * line whose query string carries such values.
 *
 * Pure Kotlin (no android.*) so it is unit-testable on the JVM.
 */
object OAuthLogRedaction {

    /** Query parameters whose values are credentials or one-time secrets. */
    private val SECRET_PARAMS = listOf(
        "code", "state", "code_verifier", "code_challenge",
        "access_token", "refresh_token", "id_token", "token",
        "key", "api_key", "client_secret", "user_code", "device_code",
    )

    private val PARAM_REGEX = Regex(
        "(?<=[?&#])(" + SECRET_PARAMS.joinToString("|") { Regex.escape(it) } + ")=([^&#\\s]*)",
    )

    /** Values shorter than this are shown as length only — 4 chars of a 6-char value is most of it. */
    private const val MIN_LEN_FOR_PREFIX = 8

    /** `<len=N prefix=abcd…>`; never more than the first 4 chars. */
    fun secret(value: String?): String {
        if (value == null) return "<null>"
        if (value.length < MIN_LEN_FOR_PREFIX) return "<len=${value.length}>"
        return "<len=${value.length} prefix=${value.take(4)}…>"
    }

    /** Replaces the value of every secret-bearing query/fragment parameter in [url] with [secret]. */
    fun url(url: String?): String {
        if (url == null) return "<null>"
        return PARAM_REGEX.replace(url) { m -> "${m.groupValues[1]}=${secret(m.groupValues[2])}" }
    }
}
