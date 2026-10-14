package com.openminis.app.shared

import android.content.Context
import java.io.File

/**
 * [T-android-text-segmenter] Chinese segmentation path for [TextSegmenter],
 * backed by CppJieba via JNI (see cpp/jieba_jni.cpp).
 *
 * The native layer reads its dictionaries from a directory path, but the two
 * dict files ship inside the APK's Assets, which have no filesystem path. So on
 * first use we gunzip `jieba/jieba.dict.utf8.gz` + `jieba/hmm_model.utf8.gz`
 * out of Assets into `filesDir/jieba/` and hand that directory to
 * `nativeInit`. The copy is a one-time cost gated by a version stamp in
 * SharedPreferences — bump [DICT_VERSION] to force a re-extract when the
 * shipped dictionaries change.
 *
 * All native calls are serialized inside the C++ layer (a global mutex); on this
 * side [ensureInitialized] is `@Synchronized` so concurrent first-callers race
 * safely. Native failures surface as `initialized == false`, and every segment
 * call falls back to an empty list rather than throwing.
 */
internal class JiebaEngine(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var initialized = false

    /** True once the native dictionaries have loaded successfully. */
    val isReady: Boolean get() = initialized

    /**
     * Extract the dictionaries from Assets (first run only) and load them into
     * the native layer. Idempotent and cheap after the first successful call.
     * Returns true when Jieba is ready to segment.
     */
    @Synchronized
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        return try {
            val dictDir = extractDictionaries()
            initialized = nativeInit(dictDir.absolutePath)
            initialized
        } catch (t: Throwable) {
            // Missing asset, I/O error, or native load failure — leave
            // initialized=false so the facade routes to the system engine.
            android.util.Log.e(TAG, "Jieba init failed", t)
            false
        }
    }

    /** Exact-mode segmentation (HMM on). Empty list if init failed. */
    fun segment(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return com.openminis.app.data.body.Admission.nativeCall(text) { nativeSegment(text).asList() }
    }

    /** Search-engine-mode segmentation. Empty list if init failed. */
    fun segmentForSearch(text: String): List<String> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        return com.openminis.app.data.body.Admission.nativeCall(text) { nativeSegmentForSearch(text).asList() }
    }

    /**
     * [T-android-voice-correction] Part-of-speech tagging: `(token, tag)` pairs
     * using jieba's tag set (nr person, ns place, nz other proper noun,
     * nt organization, …). Empty list if init failed.
     *
     * The native side returns a FLAT array of alternating token/tag (see
     * jieba_jni.cpp for why); pair it up here. An odd-length array would mean a
     * truncated native result, so the trailing element is dropped rather than
     * paired with a null tag.
     */
    fun posTag(text: String): List<Pair<String, String>> {
        if (text.isEmpty() || !ensureInitialized()) return emptyList()
        val flat = com.openminis.app.data.body.Admission.nativeCall(text) { nativePosTag(text) }
        val out = ArrayList<Pair<String, String>>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            out.add(flat[i] to flat[i + 1])
            i += 2
        }
        return out
    }

    /**
     * Copy the dictionary assets into `filesDir/jieba/` if not already present
     * for [DICT_VERSION]. Returns the target directory.
     */
    private fun extractDictionaries(): File {
        val targetDir = File(appContext.filesDir, "jieba")
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val extractedVersion = prefs.getInt(KEY_DICT_VERSION, -1)

        val allPresent = DICT_FILES.all { File(targetDir, it).exists() }
        if (extractedVersion == DICT_VERSION && allPresent) {
            return targetDir
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw java.io.IOException("Cannot create dict dir: $targetDir")
        }
        for (name in DICT_FILES) {
            // [T-apk-size] Dictionaries ship gzipped in assets (~5.4MB →
            // ~2.1MB); they are gunzipped onto disk here so the native side
            // keeps reading the plain file names. DICT_VERSION stays at 1:
            // the dictionary *content* is unchanged, and already-extracted
            // copies in filesDir remain valid (allPresent short-circuits
            // before assets are touched).
            copyAsset("jieba/$name.gz", File(targetDir, name))
        }
        prefs.edit().putInt(KEY_DICT_VERSION, DICT_VERSION).apply()
        return targetDir
    }

    /** Gunzip one Asset to [dest] (Assets can't be read by path). */
    private fun copyAsset(assetPath: String, dest: File) {
        appContext.assets.open(assetPath).use { input ->
            java.util.zip.GZIPInputStream(input).use { gz ->
                dest.outputStream().use { output ->
                    gz.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        }
    }

    private external fun nativeInit(dictDir: String): Boolean
    private external fun nativeSegment(text: String): Array<String>
    private external fun nativeSegmentForSearch(text: String): Array<String>
    private external fun nativePosTag(text: String): Array<String>

    companion object {
        private const val TAG = "JiebaEngine"
        private const val PREFS_NAME = "text_segmenter"
        private const val KEY_DICT_VERSION = "jieba_dict_version"

        /** Bump when the shipped dictionaries change to force a re-extract. */
        private const val DICT_VERSION = 1

        private val DICT_FILES = listOf("jieba.dict.utf8", "hmm_model.utf8")

        init {
            System.loadLibrary("jieba_jni")
        }
    }
}
