package com.openminis.app.ui.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMediaPickersTest {

    @Test
    fun `android 11 never uses the photo picker`() {
        assertFalse(VisualMediaPickers.isReliablePhotoPicker(sdkInt = 30, photoPickerAvailable = true))
        assertFalse(VisualMediaPickers.isReliablePhotoPicker(sdkInt = 32, photoPickerAvailable = true))
    }

    @Test
    fun `android 13 uses photo picker only when the module is present`() {
        assertTrue(VisualMediaPickers.isReliablePhotoPicker(sdkInt = 33, photoPickerAvailable = true))
        assertFalse(VisualMediaPickers.isReliablePhotoPicker(sdkInt = 33, photoPickerAvailable = false))
    }
}
