package com.openminis.app

import java.io.File

/**
 * Locates production Kotlin sources from a unit test so a test can add a
 * SOURCE-GREP DRIFT GUARD next to a ported decision: when the decision logic
 * is entangled in ChatViewModel (Context + DB + provider to construct), the
 * test ports the algorithm verbatim and then asserts the production file still
 * carries the load-bearing lines. If production changes without the port, the
 * guard fails instead of the port silently drifting.
 *
 * Gradle runs `:app:testDebugUnitTest` with the module directory as the
 * working directory, so `src/main/java/...` resolves directly; the fallback
 * walk covers an IDE runner started from the repository root or a subdir.
 *
 * Only `src/main` is consulted — never `build/`, which can hold stale
 * generated copies of deleted classes.
 */
object ProductionSources {

    private const val MAIN_ROOT = "src/main/java/com/openminis/app"

    /** The `src/main/java/com/openminis/app` directory, or null if not found. */
    fun mainRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, MAIN_ROOT)
            if (direct.isDirectory) return direct
            val viaModule = File(dir, "src/android/app/$MAIN_ROOT")
            if (viaModule.isDirectory) return viaModule
            val viaAndroid = File(dir, "app/$MAIN_ROOT")
            if (viaAndroid.isDirectory) return viaAndroid
            dir = dir.parentFile
        }
        return null
    }

    /** Read one production file by its path relative to `com/openminis/app`. */
    fun read(relPath: String): String {
        val root = mainRoot() ?: error("production source root not found from ${File("").absolutePath}")
        val f = File(root, relPath)
        require(f.isFile) { "production source missing: ${f.absolutePath}" }
        return f.readText()
    }

    /** Every `.kt` file under `src/main`, for whole-tree symbol scans. */
    fun allKotlinFiles(): List<File> {
        val root = mainRoot() ?: error("production source root not found")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
