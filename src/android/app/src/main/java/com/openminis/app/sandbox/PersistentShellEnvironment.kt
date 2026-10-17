package com.openminis.app.sandbox

/** Commands used to refresh environment state in a long-lived guest shell. */
internal object PersistentShellEnvironment {
    fun pathRefreshCommand(path: String): String {
        val escaped = path.replace("'", "'\\''")
        return "export PATH='$escaped'; hash -r\n"
    }
}
