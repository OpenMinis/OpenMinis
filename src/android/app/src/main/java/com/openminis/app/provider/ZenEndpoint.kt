package com.openminis.app.provider

/**
 * OpenCode Zen host recognition.
 *
 * The app used to ship a keyless "free lane" against this host; it is gone.
 * Measured 2026-10-17 against OpenCode 2.0.32 from one machine:
 *
 *  - the official CLI answers 200 on the free models with no stored credential
 *    (`opencode auth list` → none, `auth export` → `[]`, zero rows in the
 *    `account`/`credential` tables);
 *  - every third-party transport is refused with `403 FreeTierError` on every
 *    model: curl (Schannel), curl (OpenSSL), Node/undici, Python/OpenSSL and
 *    Chromium/BoringSSL, over HTTP/1.1 and HTTP/2;
 *  - replaying the CLI's own header set changes nothing. The 2.0.32 bundle
 *    builds `x-opencode-session-id`, `x-opencode-session`, `x-session-affinity`,
 *    `X-Session-Id`, `x-opencode-project` and
 *    `User-Agent: opencode/<channel>/<version>/<name>`; a canonical
 *    `ses_<12 hex><14 Base62>` id replayed with that exact set is still 403,
 *    with and without `Authorization: Bearer public`.
 *
 * So the refusal is not a header shape a client can imitate, and faking one is
 * not a fix. A Zen instance is usable only with a real account key — which is
 * also what the real inference host says out loud:
 * `inference.opencode.ai/v1` answers `401 Authentication Fails, Your api key:
 * ****blic is invalid` for the placeholder credential the old lane relied on.
 *
 * What survives here is the host check, because the Zen endpoint speaks its own
 * error dialect (`{"type":"error","error":{"type":"ModelError"|"RegionError"|
 * "FreeTierError"}}`) that the generic OpenAI mapping misreads as a bad key.
 */
internal object ZenEndpoint {
    fun isZenHost(baseURL: String?): Boolean =
        baseURL != null && (
            baseURL.contains("opencode.ai/zen", ignoreCase = true) ||
                baseURL.contains("opencode.ai/inference", ignoreCase = true) ||
                baseURL.contains("inference.opencode.ai", ignoreCase = true) ||
                baseURL.contains("gateway.opencode.ai", ignoreCase = true)
            )
}
