package com.openminis.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-filepreview-nav-args] The previewed path travels as a nav
 * argument; it must round-trip through Uri encoding for paths containing
 * separators, CJK, spaces, and `+` (the media-path bug class from PR6 —
 * URLDecoder turned `+` into a space and the file was never found).
 */
class FilePreviewRouteTest {

    @Test fun plainPathEncodesToQueryForm() {
        assertEquals(
            "file_preview?path=%2Fvar%2Fminis%2Fworkspace%2Fnotes.md",
            Routes.filePreview("/var/minis/workspace/notes.md"),
        )
    }

    @Test fun routePatternCarriesTheArgument() {
        assertTrue(Routes.FILE_PREVIEW.startsWith("file_preview"))
        assertTrue(Routes.FILE_PREVIEW.contains("path={path}"))
    }

    @Test fun plusSignIsEncodedNotLeftBare() {
        val route = Routes.filePreview("/sdcard/a+b.png")
        // `+` must be percent-encoded: Navigation decodes query params with
        // Uri semantics where a bare `+` would be read back as a space.
        assertTrue(route.contains("a%2Bb.png"))
        assertTrue(!route.contains("+"))
    }

    @Test fun cjkPathRoundTripsThroughDecode() {
        val path = "/var/minis/attachments/图表.png"
        val encoded = Routes.filePreview(path)
        // Navigation decodes query params with Uri percent-decoding; a
        // standard decoder must restore the original path byte-for-byte.
        // (URLDecoder is safe here because Uri.encode leaves no bare `+`.)
        val decoded = java.net.URLDecoder.decode(
            encoded.substringAfter("path="),
            Charsets.UTF_8.name(),
        )
        assertEquals(path, decoded)
    }

    @Test fun spaceIsPercentEncoded() {
        val route = Routes.filePreview("/tmp/my file.txt")
        assertTrue(route.contains("my%20file.txt"))
    }
}
