package com.openminis.app.util

import java.security.MessageDigest

/**
 * S-14: command lines frequently contain credentials — `curl -H
 * "Authorization: Bearer …"`, `ssh -i <private key>`, URLs with `?token=…`,
 * `MYSQL_PWD=…`. AppLogger writes logcat into filesDir/logs and bug reports ask
 * users to attach those files, so the full line must never reach a log.
 *
 * Keep only what diagnostics need: the program name, the length, and a short
 * SHA-256 prefix (correlates repeated commands without revealing them).
 */
object LogRedaction {

    fun command(command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return "cmd=<empty>"
        val rawHead = trimmed.substringBefore(' ').substringAfterLast('/')
        // `SECRET=value cmd` would put the secret in the "program" slot.
        val head = if (rawHead.contains('=')) "<env>" else rawHead.take(24)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "cmd=$head len=${command.length} sha=$digest"
    }
}
