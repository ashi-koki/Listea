package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the delete preview says is about to happen.
 *
 * The deletion itself goes through a content provider and is exercised on device; what these pin
 * is the listing, because that is the only thing standing between the user and a permanent,
 * irreversible action. A file grouped under the wrong folder, or dropped from the listing
 * entirely, would be deleted anyway — so the listing has to be exactly the set that goes.
 */
class FileManagementTest {

    @Test
    fun `a file is listed under the folder that holds it`() {
        val groups = groupCheckedFiles(listOf("xxx/yyyy/file.jpg"))

        assertEquals(listOf("xxx/yyyy"), groups.map { it.folderPath })
        assertEquals(listOf("file.jpg"), groups.single().fileNames)
    }

    @Test
    fun `files in the same folder share one line, in the requested shape`() {
        val groups = groupCheckedFiles(
            listOf("xxx/zzz/file3.mp4", "xxx/yyy/file.jpg", "xxx/zzz/file2.txt")
        )

        assertEquals(
            listOf(
                "xxx/yyy" to "->file.jpg",
                "xxx/zzz" to "->file2.txt, file3.mp4"
            ),
            groups.map { it.folderPath to it.filesLine }
        )
    }

    @Test
    fun `files checked from anywhere are listed together when they share a folder`() {
        // A file checked in a folder's Quick Review and one checked from a List's own page are
        // the same kind of thing now: both are just checked files at a path. The preview is about
        // folders of files, not about where each decision happened to be made.
        val groups = groupCheckedFiles(listOf("a/b/one.jpg", "a/b/two.jpg"))

        assertEquals(1, groups.size)
        assertEquals("a/b", groups.single().folderPath)
        assertEquals(listOf("one.jpg", "two.jpg"), groups.single().fileNames)
    }

    @Test
    fun `a file sitting in the root folder itself is grouped under no path`() {
        // "" is the root, which only the screen can name — it is the one folder whose display name
        // is not in the path. Grouping it under "" rather than inventing a name keeps that so.
        val groups = groupCheckedFiles(listOf("loose.png"))

        assertEquals("", groups.single().folderPath)
        assertEquals("->loose.png", groups.single().filesLine)
    }

    @Test
    fun `every checked file reaches the listing`() {
        val paths = (1..40).map { "dir${it % 4}/sub/f$it.jpg" }

        assertEquals(40, groupCheckedFiles(paths).sumOf { it.fileNames.size })
    }

    @Test
    fun `the outcome says what is gone and what is still there`() {
        assertEquals("3 files were deleted from the device.", deleteOutcomeMessage(3, 0))
        assertEquals("1 file was deleted from the device.", deleteOutcomeMessage(1, 0))
        assertEquals(
            "0 files were deleted from the device. 2 files could not be deleted and are still there.",
            deleteOutcomeMessage(0, 2)
        )
        assertEquals(
            "5 files were deleted from the device. 1 file could not be deleted and is still there.",
            deleteOutcomeMessage(5, 1)
        )
    }

    @Test
    fun `the storage line counts folders and files separately`() {
        assertEquals("2 folders · 1 file · 12 B", FolderStats(2, 1, 12).summary)
        assertEquals("1 folder · 0 files · 0 B", FolderStats(1, 0, 0).summary)
    }
}
