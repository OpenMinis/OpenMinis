package com.openminis.app.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Guard for the "provider cannot report a length" half of the save contract.
 *
 * Robolectric's ContentResolver lets a test register the exact streams a
 * misbehaving SAF provider would hand back: an output stream that accepts the
 * copy, and an input stream that can (or cannot) read the bytes afterwards.
 * `openAssetFileDescriptor` has no provider here, so it reports UNKNOWN — the
 * exact shape that used to be folded into "success".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VerifiedCopyTest {

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var shadow: ShadowContentResolver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resolver = context.contentResolver
        shadow = Shadows.shadowOf(resolver)
    }

    private fun uri(): Uri = Uri.parse("content://test.docs/doc-${System.nanoTime()}")

    private fun sourceFile(bytes: Int): File =
        File(context.cacheDir, "source-$bytes-${System.nanoTime()}.bin").apply {
            writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        }

    @Test
    fun unknownLengthWithReadableBytesIsVerified() {
        val src = sourceFile(4096)
        val target = uri()
        shadow.registerOutputStream(target, ByteArrayOutputStream())
        shadow.registerInputStream(target, ByteArrayInputStream(src.readBytes()))

        val result = VerifiedCopy.copyFileToDocument(resolver, src, target)
        assertEquals(result.reason, VerifiedCopy.Status.VERIFIED, result.status)
        assertTrue(result.ok)
    }

    @Test
    fun emptyDocumentWithUnknownLengthIsNotReportedAsSuccess() {
        val target = uri()
        shadow.registerOutputStream(target, ByteArrayOutputStream())
        shadow.registerInputStream(target, ByteArrayInputStream(ByteArray(0)))

        val result = VerifiedCopy.verifyDocument(resolver, target, expectedLen = 4096L)
        assertEquals(VerifiedCopy.Status.UNVERIFIED, result.status)
        assertFalse(result.ok)
    }

    @Test
    fun writeThatCannotBeReadBackIsNotReportedAsSuccess() {
        val src = sourceFile(64)
        val target = uri()
        // The copy "succeeds" into a void; no readable stream is registered.
        val result = VerifiedCopy.copyFileToDocument(resolver, src, target)
        assertEquals(VerifiedCopy.Status.UNVERIFIED, result.status)
        assertFalse(result.ok)
    }

    @Test
    fun fileCopyIsAtomicAndVerified() {
        val src = sourceFile(1024)
        val dest = File(context.cacheDir, "verified-copy-${System.nanoTime()}.bin")
        val result = VerifiedCopy.copyFileToFile(src, dest)
        assertTrue(result.ok)
        assertArrayEquals(src.readBytes(), dest.readBytes())
        // The sidecar must not survive a successful save.
        assertTrue(dest.parentFile!!.listFiles { f -> f.name.endsWith(".part") }.isNullOrEmpty())
    }

    @Test
    fun writerRefusalLeavesNoTarget() {
        val dest = File(context.cacheDir, "refused-${System.nanoTime()}.bin")
        val result = VerifiedCopy.writeFileAtomically(dest, expectedLen = 10L) { false }
        assertFalse(result.ok)
        assertFalse(dest.exists())
    }
}
