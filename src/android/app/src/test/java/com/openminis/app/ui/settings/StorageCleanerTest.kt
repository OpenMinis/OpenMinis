package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-android-storage-clean-logs-caches] The cleanup pass must reclaim what it
 * claims and nothing more.
 *
 * These assertions are the guardrail for the two ways a "clear caches" action
 * goes wrong in the field: deleting the directory itself (the app then fails to
 * recreate its cache path) and walking outside the directory it was given (a
 * bug there would delete chat history, which the action promises not to touch).
 */
class StorageCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cleanDirDeletesContentsAndKeepsTheDirectory() {
        val cache = tmp.newFolder("cache")
        val nested = cache.resolve("nested").apply { mkdirs() }
        cache.resolve("a.log").writeBytes(ByteArray(100))
        nested.resolve("b.bin").writeBytes(ByteArray(250))

        val (freed, deleted, failed) = StorageCleaner.cleanDir(cache)

        assertEquals(350L, freed)
        assertEquals(2, deleted)
        assertEquals(0, failed)
        assertTrue("the directory itself must survive", cache.isDirectory)
        assertEquals(0, cache.listFiles()?.size ?: 0)
    }

    @Test
    fun cleanDirCountsNestedTreeSize() {
        val dir = tmp.newFolder("logs")
        val deep = dir.resolve("a/b/c").apply { mkdirs() }
        deep.resolve("x.txt").writeBytes(ByteArray(10))
        dir.resolve("y.txt").writeBytes(ByteArray(5))
        assertEquals(15L, StorageCleaner.sizeOf(dir))
    }

    @Test
    fun emptyDirectoryIsANoOp() {
        val dir = tmp.newFolder("empty")
        val (freed, deleted, failed) = StorageCleaner.cleanDir(dir)
        assertEquals(0L, freed)
        assertEquals(0, deleted)
        assertEquals(0, failed)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun missingDirectoryIsANoOp() {
        // candidateDirs filters to real directories, but cleanDir must not throw
        // if a directory disappears between listing and cleaning.
        val (freed, deleted, failed) = StorageCleaner.cleanDir(tmp.root.resolve("gone"))
        assertEquals(0L, freed)
        assertEquals(0, deleted)
        assertEquals(0, failed)
    }

    @Test
    fun cleanDirLeavesSiblingDirectoriesAlone() {
        val root = tmp.newFolder("root")
        val cache = root.resolve("cache").apply { mkdirs() }
        val sessions = root.resolve("sessions").apply { mkdirs() }
        cache.resolve("c.tmp").writeBytes(ByteArray(8))
        sessions.resolve("chat.json").writeBytes(ByteArray(64))

        StorageCleaner.cleanDir(cache)

        assertTrue("only the given directory is cleaned", sessions.resolve("chat.json").isFile)
        assertEquals(64L, sessions.resolve("chat.json").length())
    }
}
