package com.openminis.app.ui.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-file-preview-statusbar] FilePreviewScreen runs in its own Dialog
 * window (a NavHost `dialog` destination since 988bd6491). The status bar takes
 * its icon colour from that top window, which defaults to white icons — white
 * on the white preview bar in light mode. The screen must set the dialog
 * window's appearance from the app theme.
 */
class FilePreviewStatusBarTest {

    @Test
    fun `the preview is still a dialog destination`() {
        val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
        assertTrue(nav.contains("dialog(\n            route = Routes.FILE_PREVIEW,"))
    }

    @Test
    fun `the preview sets its own window's status bar icons from the theme`() {
        val src = ProductionSources.read("ui/sandbox/FilePreviewScreen.kt")
        assertTrue(src.contains("filePreviewDialogWindow(hostView)?.let { w ->"))
        assertTrue(src.contains("isAppearanceLightStatusBars = !previewDark"))
        assertTrue(src.contains("isAppearanceLightNavigationBars = !previewDark"))
        assertTrue(src.contains("if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window"))
        // Set before the Scaffold, after the image early-return (the gallery
        // manages its own window).
        val effect = src.indexOf("val previewDark = com.openminis.app.ui.theme.ChatColors.isDark")
        assertTrue(effect > src.indexOf("if (item.isImageFile) {"))
        assertTrue(effect < src.indexOf("    Scaffold(\n        topBar"))
    }
}
