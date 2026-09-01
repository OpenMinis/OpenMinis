package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-edit-loses-attachments] Rebuilding composer attachments from a
 * sent message, as editMessage does.
 *
 * The subtle part is the NAME pairing. A ChatMessage keeps its files in three
 * parallel-ish lists: `imageUris`, `attachmentUris`, and a single
 * `attachmentNames` that is built image-first — so the non-image names live in
 * its suffix, offset by `imageUris.size`. Getting that offset wrong pairs a PDF
 * with a photo's filename, which looks fine on screen right up until the user
 * sends it.
 *
 * ChatViewModel needs Android/DB plumbing to construct, so the pairing is
 * mirrored here as a pure function — the same convention AtomicCellSelectionTest
 * and SkillStaleMetadataTest use in this codebase.
 */
class EditRestoreAttachmentsTest {

    /** (name, uri, isImage) — what one rebuilt InputAttachment would carry. */
    private data class Restored(val name: String, val uri: String, val isImage: Boolean)

    /** Mirrors the rebuild loop in ChatViewModel.editMessage. */
    private fun restore(
        imageUris: List<String>,
        attachmentUris: List<String> = emptyList(),
        attachmentNames: List<String> = emptyList(),
    ): List<Restored> {
        val out = mutableListOf<Restored>()
        imageUris.forEachIndexed { i, uri ->
            val name = attachmentNames.getOrNull(i) ?: uri.substringAfterLast('/') ?: "image"
            out.add(Restored(name, uri, true))
        }
        attachmentUris.forEachIndexed { i, uri ->
            val name = attachmentNames.getOrNull(imageUris.size + i)
                ?: uri.substringAfterLast('/') ?: "file"
            out.add(Restored(name, uri, false))
        }
        return out
    }

    // ── The reported bug ─────────────────────────────────────────────────

    @Test
    fun `a message with one image restores one image attachment`() {
        // The exact user report: send a message with a picture, tap Edit, and
        // the picture must come back with the text.
        val r = restore(imageUris = listOf("file:///m/red.png"), attachmentNames = listOf("red.png"))
        assertEquals(1, r.size)
        assertEquals("red.png", r[0].name)
        assertEquals("file:///m/red.png", r[0].uri)
        assertTrue(r[0].isImage)
    }

    @Test
    fun `a text-only message restores nothing`() {
        assertTrue(restore(imageUris = emptyList()).isEmpty())
    }

    // ── Name pairing across the image / non-image split ──────────────────

    @Test
    fun `non-image names are read from the suffix, offset past the images`() {
        // attachmentNames is image-first: two images then two files.
        val r = restore(
            imageUris = listOf("file:///m/a.png", "file:///m/b.jpg"),
            attachmentUris = listOf("file:///m/c.pdf", "file:///m/d.txt"),
            attachmentNames = listOf("a.png", "b.jpg", "c.pdf", "d.txt"),
        )
        assertEquals(listOf("a.png", "b.jpg", "c.pdf", "d.txt"), r.map { it.name })
        assertEquals(
            "each name must land on its own uri",
            listOf("file:///m/a.png", "file:///m/b.jpg", "file:///m/c.pdf", "file:///m/d.txt"),
            r.map { it.uri },
        )
        assertEquals(listOf(true, true, false, false), r.map { it.isImage })
    }

    @Test
    fun `files-only message still reads names from the start of the list`() {
        // imageUris is empty, so the offset is 0 and the suffix IS the list.
        val r = restore(
            imageUris = emptyList(),
            attachmentUris = listOf("file:///m/notes.pdf"),
            attachmentNames = listOf("notes.pdf"),
        )
        assertEquals("notes.pdf", r.single().name)
        assertEquals(false, r.single().isImage)
    }

    @Test
    fun `images keep their order`() {
        val r = restore(
            imageUris = listOf("file:///m/1.png", "file:///m/2.png", "file:///m/3.png"),
            attachmentNames = listOf("1.png", "2.png", "3.png"),
        )
        assertEquals(listOf("1.png", "2.png", "3.png"), r.map { it.name })
    }

    // ── Degraded rows must not crash or mispair ──────────────────────────

    @Test
    fun `a short attachmentNames falls back to the uri filename`() {
        // A row persisted by an older build can carry fewer names than uris.
        // Indexing must not throw, and must not shift the remaining names onto
        // the wrong files.
        val r = restore(
            imageUris = listOf("file:///m/a.png", "file:///m/b.png"),
            attachmentNames = listOf("a.png"),
        )
        assertEquals(listOf("a.png", "b.png"), r.map { it.name })
    }

    @Test
    fun `an empty attachmentNames falls back entirely to uri filenames`() {
        val r = restore(
            imageUris = listOf("file:///m/photo.jpg"),
            attachmentUris = listOf("file:///m/doc.pdf"),
            attachmentNames = emptyList(),
        )
        assertEquals(listOf("photo.jpg", "doc.pdf"), r.map { it.name })
    }

    // ── MIME guessing ────────────────────────────────────────────────────
    //
    // Mirrors guessMimeType. Kind is decided by WHICH list the uri came from,
    // so a miss here can only affect the chip label, never the routing.

    private fun guess(fileName: String, fallback: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return fallback
        return mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "pdf" to "application/pdf",
        )[ext] ?: fallback
    }

    @Test
    fun `a known extension yields its mime type`() {
        assertEquals("image/png", guess("red.png", "image/*"))
        assertEquals("application/pdf", guess("notes.pdf", "application/octet-stream"))
    }

    @Test
    fun `an extensionless or unknown name falls back to the kind default`() {
        assertEquals("image/*", guess("screenshot", "image/*"))
        assertEquals("application/octet-stream", guess("archive.zzz", "application/octet-stream"))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        assertEquals("image/jpeg", guess("PHOTO.JPG", "image/*"))
    }
}
