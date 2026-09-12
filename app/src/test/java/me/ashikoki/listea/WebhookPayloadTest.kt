package me.ashikoki.listea

import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.decisions
import me.ashikoki.listea.data.encodeActionIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the two pure pieces of the webhook item payload. The JSON assembly itself needs
 * org.json, which is not available to host tests, so it is exercised on device instead.
 */
class WebhookPayloadTest {

    private fun list(relativePath: String?) = ListEntity(
        id = 1,
        title = "bilibili",
        createdAt = 0,
        sourceRootUri = relativePath?.let { "content://tree/Listea" },
        sourceRelativePath = relativePath
    )

    private fun item(
        sourcePath: String?,
        vararg actions: String,
        favorite: Boolean = false
    ) = ListItemEntity(
        id = 173,
        publicId = "0AT9K3QWMB-4H7ZP2-XC5N0V",
        listId = 1,
        title = "example.jpg",
        sortOrder = 0,
        createdAt = 0,
        sourceRelativePath = sourcePath,
        isFavorite = favorite,
        customActions = encodeActionIds(actions.toSet())
    )

    /** The two out-of-the-box actions, with whatever wire values a test is about. */
    private fun settings(c1Value: String = "cust1", c2Value: String = "cust2") = AppSettings(
        customActions = listOf(
            CustomAction(id = "custom1", displayName = "C1", webhookValue = c1Value),
            CustomAction(id = "custom2", displayName = "C2", webhookValue = c2Value)
        )
    )

    @Test
    fun `path is relative to the sync root, not to the list folder`() {
        assertEquals(
            "2026-07-11/bilibili",
            webhookRelativePath(list("2026-07-11/bilibili"), item("example.jpg"))
        )
    }

    @Test
    fun `nested item keeps its subfolders`() {
        assertEquals(
            "2026-07-11/bilibili/sub",
            webhookRelativePath(list("2026-07-11/bilibili"), item("sub/a.jpg"))
        )
    }

    @Test
    fun `the file name is never part of the path`() {
        // A file and a folder that share a name must still resolve to the same parent.
        assertEquals(
            "2026-07-11/bilibili/sub",
            webhookRelativePath(list("2026-07-11/bilibili"), item("sub/sub"))
        )
    }

    @Test
    fun `an item directly in a list linked to the root has an empty path`() {
        assertEquals("", webhookRelativePath(list(""), item("a.jpg")))
    }

    @Test
    fun `a list linked to the root itself adds no prefix`() {
        assertEquals("sub", webhookRelativePath(list(""), item("sub/a.jpg")))
    }

    @Test
    fun `stray separators cannot produce a doubled slash`() {
        assertEquals(
            "2026-07-11/bilibili/sub",
            webhookRelativePath(list("/2026-07-11/bilibili/"), item("/sub/a.jpg"))
        )
    }

    @Test
    fun `manual items have no path at all`() {
        assertNull(webhookRelativePath(list(null), item(null)))
    }

    @Test
    fun `actions serialize in a fixed order, whatever is set`() {
        val settings = AppSettings()
        assertEquals(emptyList<String>(), itemActionNames(item("a.jpg").decisions, settings))
        assertEquals(
            listOf("favorite"),
            itemActionNames(item("a.jpg", favorite = true).decisions, settings)
        )
        assertEquals(
            listOf("favorite", "cust2"),
            itemActionNames(item("a.jpg", "custom2", favorite = true).decisions, settings)
        )
        assertEquals(
            listOf("favorite", "cust1", "cust2"),
            itemActionNames(
                item("a.jpg", "custom1", "custom2", favorite = true).decisions,
                settings
            )
        )
    }

    @Test
    fun `completion and actions are independent`() {
        val settings = AppSettings()
        val unchecked = item("a.jpg", "custom1").decisions.copy(isCompleted = false)
        assertEquals(listOf("cust1"), itemActionNames(unchecked, settings))
        assertEquals(listOf("cust1"), itemActionNames(unchecked.copy(isCompleted = true), settings))
    }

    @Test
    fun `configured wire values replace the defaults`() {
        val settings = settings(c1Value = "tag1", c2Value = "tag2")
        assertEquals(
            listOf("favorite", "tag1", "tag2"),
            itemActionNames(
                item("a.jpg", "custom1", "custom2", favorite = true).decisions,
                settings
            )
        )
    }

    @Test
    fun `favorite is not configurable`() {
        // Renaming a custom action must never touch the one fixed action.
        val settings = settings(c1Value = "favorite")
        assertEquals(
            listOf("favorite"),
            itemActionNames(item("a.jpg", favorite = true).decisions, settings)
        )
    }

    @Test
    fun `two actions sharing a wire value emit it once`() {
        val settings = settings(c1Value = "tag", c2Value = "tag")
        assertEquals(
            listOf("tag"),
            itemActionNames(item("a.jpg", "custom1", "custom2").decisions, settings)
        )
    }

    @Test
    fun `renaming an action does not change which actions are selected`() {
        // The item holds the id, so it is untouched and only the emitted string moves. This is
        // the whole point of keeping names out of the item row.
        val marked = item("a.jpg", "custom1").decisions
        assertEquals(listOf("cust1"), itemActionNames(marked, AppSettings()))
        assertEquals(listOf("archive"), itemActionNames(marked, settings(c1Value = "archive")))
    }

    @Test
    fun `an action the user deleted is carried but not sent`() {
        // Deleting a button is a decision about the bar, not about the files already marked with
        // it: the id stays on the row, and stops resolving to anything the webhook can name.
        val marked = item("a.jpg", "custom1", favorite = true).decisions
        val settings = AppSettings(customActions = emptyList())

        assertEquals(setOf("custom1"), marked.customActions)
        assertEquals(listOf("favorite"), itemActionNames(marked, settings))
    }
}
