package me.ashikoki.listea

import me.ashikoki.listea.data.ListItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "delete what the webhook sent" is allowed to mean.
 *
 * The whole feature rests on this one function, because it is where the offer's scope is decided
 * and it is the only part of the flow a host test can reach — the deletion itself goes through a
 * content provider. A path that gets in here wrongly is a file deleted that the user never agreed
 * to lose, which is the failure this feature can actually cause.
 *
 * Note what is *not* tested, because it is not this function's job: the input is already the
 * payload's own items, so "review unchecked items only" and "send checked items only" have both
 * had their say before anything arrives here. This adds exactly one rule to what was sent.
 */
class SentCheckedFilesTest {

    private fun item(
        title: String,
        checked: Boolean = true,
        rootPath: String? = title,
        missing: Boolean = false
    ) = ListItemEntity(
        id = 0,
        publicId = title,
        listId = 1,
        title = title,
        isCompleted = checked,
        sortOrder = 0,
        createdAt = 0,
        sourceRelativePath = rootPath,
        rootRelativePath = rootPath,
        sourceMissing = missing
    )

    @Test
    fun `only the items the receiver was told are checked can be deleted`() {
        val paths = sentCheckedFilePaths(
            listOf(
                item("a/one.jpg", checked = true),
                item("a/two.jpg", checked = false),
                item("a/three.jpg", checked = true)
            )
        )

        assertEquals(listOf("a/one.jpg", "a/three.jpg"), paths)
    }

    @Test
    fun `a payload with nothing checked offers nothing, rather than everything`() {
        val paths = sentCheckedFilePaths(
            listOf(item("a/one.jpg", checked = false), item("a/two.jpg", checked = false))
        )

        assertTrue(paths.isEmpty())
    }

    @Test
    fun `a manual item has no file, so it is never counted towards the total`() {
        // Deleting is about files. A manual entry is a line of text and there is nothing on the
        // device to remove — counting it would promise a deletion that could never happen.
        val paths = sentCheckedFilePaths(
            listOf(item("typed by hand", rootPath = null), item("a/one.jpg"))
        )

        assertEquals(listOf("a/one.jpg"), paths)
    }

    @Test
    fun `a file already known to be gone is left out`() {
        val paths = sentCheckedFilePaths(
            listOf(item("a/gone.jpg", missing = true), item("a/here.jpg"))
        )

        assertEquals(listOf("a/here.jpg"), paths)
    }

    @Test
    fun `the same file in two lists is offered once`() {
        // Otherwise it would be deleted once and reported as having failed the second time, which
        // reads as a partial failure of a run that did exactly what it was asked to.
        val paths = sentCheckedFilePaths(listOf(item("a/one.jpg"), item("a/one.jpg")))

        assertEquals(listOf("a/one.jpg"), paths)
    }

    @Test
    fun `the offer is off until it is switched on`() {
        assertEquals(false, AppSettings().askDeleteAfterWebhook)
    }
}
