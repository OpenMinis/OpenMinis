package com.openminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-use-no-media-fails] A generation that had to produce media
 * and produced none exits non-zero and writes nothing — so the agent cannot
 * read "success" and reference an image that does not exist.
 *
 * The handler's runtime path needs a live provider, so this pins the
 * decisions in source.
 */
class ModelUseNoMediaFailsTest {

    private val src = File("src/main/java/com/openminis/app/sandbox/offload/ModelUseOffloadHandler.kt").readText()

    @Test
    fun `the chat path fails when media was required and none came back, before writing anything`() {
        val run = src.substringAfter("private fun cmdRun(").substringBefore("private fun ")
        val check = run.indexOf("if (mediaRequired && response.mediaAttachments.isEmpty())")
        val write = run.indexOf("hostFile.writeText(response.text)")
        assertTrue("check present", check > 0)
        assertTrue("check precedes any write to --output", check < write)
        assertTrue(run.contains("isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt)"))
        assertTrue("pure image generators count", run.contains("(\"image\" in outputs && \"text\" !in outputs)"))
    }

    @Test
    fun `the image endpoint path fails on an empty result instead of warning`() {
        val img = src.substringAfter("private fun writeImageResult(").substringBefore("private fun noMediaResult(")
        assertTrue(img.contains("if (firstMedia == null) {\n            return noMediaResult("))
        assertFalse("no more exit-0 with a warning", src.contains("Image endpoint returned no image data."))
    }

    @Test
    fun `the failure exits 1 with a machine-readable error`() {
        val fn = src.substringAfter("private fun noMediaResult(").substringBefore("\n    }\n")
        assertTrue(fn.contains("put(\"error\", \"no_media_generated\")"))
        assertTrue(fn.contains("NativeOffloadResult(1,"))
    }
}
