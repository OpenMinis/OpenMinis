package com.openminis.app.backup.remote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-android-backup-local-folder] Delivers a finished `.minisbak` package to a
 * folder the user picked on this device, through SAF.
 *
 * This is the local-folder peer of [RcloneChunkedUpload]: same job, same
 * contract (throw on failure, report progress), but a plain ContentResolver
 * stream copy instead of an rclone transfer — a destination that is a
 * directory on the phone has no server to talk to.
 *
 * Built on raw [DocumentsContract] rather than `androidx.documentfile`:
 * DocumentFile would be the more convenient API, but it is not a dependency of
 * this module and the rest of the app (PRootKernel, MountedFoldersScreen)
 * already talks to SAF at this level. Adding a library for one class, when the
 * three calls needed are one-liners, is not worth the extra dependency.
 *
 * The write goes to a `.partial` scratch name and is renamed only after the
 * bytes are flushed, for the same reason the rclone path does it: an
 * interrupted write (process death, storage full, an SD card pulled) must not
 * leave a truncated file under a name the restore picker accepts. The restore
 * list matches `.minisbak` exactly, so a leftover `.partial` reads as scratch
 * rather than as a corrupt backup.
 */
class LocalFolderDelivery(private val context: Context) {

    class DeliveryException(message: String) : Exception(message)

    /**
     * Copy [packageFile] into the tree [treeUri] points at.
     *
     * [onProgress] receives bytes-written / total as the copy runs, so a large
     * package on slow storage (an SD card, a USB drive over OTG) still shows
     * movement rather than a frozen status line.
     */
    fun deliver(
        packageFile: File,
        treeUri: String,
        // [T-android-backup-webdav-deadline] Polled per buffer, so Stop ends a
        // copy to a slow folder (a network-backed provider) mid-file.
        isCancelled: () -> Boolean = { false },
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) {
        if (!packageFile.exists()) throw DeliveryException("Couldn't read the backup file.")

        val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: throw DeliveryException("That folder is no longer available.")
        // The tree URI addresses the tree; writes need the DOCUMENT uri for
        // the same node. Passing the tree uri to createDocument throws.
        val parentDoc = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        }.getOrNull() ?: throw DeliveryException("That folder is no longer available.")

        val name = packageFile.name
        val partialName = "$name.$PARTIAL_SUFFIX"

        // Re-delivering the same backup id, or retrying after a failure, must
        // not accumulate "name (1).minisbak" copies: SAF's createDocument
        // de-duplicates names silently rather than overwriting.
        deleteChild(parentDoc, partialName)

        val partial = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver, parentDoc, MIME_TYPE, partialName,
            )
        }.getOrNull() ?: throw DeliveryException(
            // A persisted permission outlives the folder: an SD card removed, a
            // USB drive unplugged, the directory deleted in a file manager. All
            // of them surface here, so say the folder is unreachable rather
            // than leaking a provider-specific exception.
            "Couldn't create a file in that folder — it may have been moved or removed.",
        )

        val total = packageFile.length()
        try {
            context.contentResolver.openOutputStream(partial, "w")?.use { out ->
                packageFile.inputStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sent = 0L
                    while (true) {
                        if (isCancelled()) throw DeliveryException("Delivery stopped.")
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        sent += read
                        onProgress?.invoke(sent, total)
                    }
                    // The provider buffers; without this the rename below can
                    // promote a file whose tail is still in flight, producing a
                    // `.minisbak` that fails to unzip.
                    out.flush()
                }
            } ?: throw DeliveryException("Couldn't open that folder for writing.")
        } catch (e: Exception) {
            // Leave nothing half-written behind under any name.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) }
            throw if (e is DeliveryException) e
            else DeliveryException(e.message ?: "Couldn't write to that folder.")
        }

        // Replacing an existing package of the same name is deliberate: the
        // name carries the backup id, so a same-name file IS this backup being
        // re-delivered. SAF will not rename onto an existing name.
        deleteChild(parentDoc, name)
        val renamed = runCatching {
            DocumentsContract.renameDocument(context.contentResolver, partial, name)
        }.getOrNull()
        if (renamed == null) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, partial) }
            throw DeliveryException("Couldn't finish writing to that folder.")
        }

        AppLogger.info(TAG, "[LocalDest] wrote $name ($total bytes) to $treeUri")
    }

    /**
     * Delete a direct child of [parentDoc] by display name, if it exists.
     *
     * SAF has no "delete by name" call, so the children have to be listed.
     * Failures are swallowed on purpose: this only clears the way for a write
     * that reports its own errors, and a folder we cannot enumerate should
     * surface as "couldn't create the file", not as a delete failure.
     */
    /**
     * [T-android-backup-local-folder-delete] Delete the backup package named
     * [name] from the folder [treeUri], reporting failure.
     *
     * Issue #367: "Delete Record and Files" could never succeed for a local
     * folder. The delete path routed every destination through rclone, and a
     * local folder is a SAF `content://` tree that is never registered as an
     * rclone remote — so the delete silently did nothing while the UI reported
     * success and dropped the history record, orphaning the file on disk.
     *
     * Idempotent by design: a file that is already gone is a success, because
     * the caller's goal (no such package in this folder) is satisfied. Anything
     * that leaves the file in place — an unreadable tree, a refused delete, a
     * revoked permission — throws, so the caller can keep the record and tell
     * the user which destination failed.
     *
     * Deliberately NOT [deleteChild]: that one swallows every error on purpose
     * because it only clears the way for a write that reports its own failures
     * (see its doc). Reusing it here would reintroduce exactly the silent
     * success this fixes.
     *
     * @throws IllegalStateException when the folder cannot be enumerated or the
     *   provider refuses the delete.
     * @throws SecurityException when the persisted tree permission is gone.
     */
    fun delete(treeUri: String, name: String) {
        val tree = Uri.parse(treeUri)
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDoc,
            DocumentsContract.getDocumentId(parentDoc),
        )
        // A null cursor means the tree could not be read at all — never treat
        // that as "the file isn't there", or a revoked grant reads as success.
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        ) ?: throw IllegalStateException("Could not open the folder to delete '$name'.")

        var target: Uri? = null
        cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) != name) continue
                target = DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0))
                break
            }
        }
        // Already absent: the postcondition holds, so this is a success.
        val doc = target ?: return
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, doc)
        if (!deleted) throw IllegalStateException("The folder refused to delete '$name'.")
    }

    private fun deleteChild(parentDoc: Uri, displayName: String) {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDoc,
                DocumentsContract.getDocumentId(parentDoc),
            )
        }.getOrNull() ?: return

        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) != displayName) continue
                    val doc = DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0))
                    DocumentsContract.deleteDocument(context.contentResolver, doc)
                    return
                }
            }
        }
    }

    companion object {
        private const val TAG = "Backup"
        private const val PARTIAL_SUFFIX = "partial"

        /**
         * `.minisbak` has no registered MIME type. octet-stream keeps SAF from
         * appending an extension of its own choosing to the name we pass —
         * which is what it does with a type it recognises.
         */
        private const val MIME_TYPE = "application/octet-stream"
    }
}
