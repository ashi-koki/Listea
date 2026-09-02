package me.ashikoki.listea

import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.decisions
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
        favorite: Boolean = false,
        custom1: Boolean = false,
        custom2: Boolean = false
    ) = ListItemEntity(
        id = 173,
        listId = 1,
        title = "example.jpg",
        sortOrder = 0,
        createdAt = 0,
        sourceRelativePath = sourcePath,
        isFavorite = favorite,
        custom1 = custom1,
        custom2 = custom2
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
            itemActionNames(item("a.jpg", favorite = true, custom2 = true).decisions, settings)
        )
        assertEquals(
            listOf("favorite", "cust1", "cust2"),
            itemActionNames(
                item("a.jpg", favorite = true, custom1 = true, custom2 = true).decisions,
                settings
            )
        )
    }

    @Test
    fun `completion and actions are independent`() {
        val settings = AppSettings()
        val unchecked = item("a.jpg", custom1 = true).decisions.copy(isCompleted = false)
        assertEquals(listOf("cust1"), itemActionNames(unchecked, settings))
        assertEquals(listOf("cust1"), itemActionNames(unchecked.copy(isCompleted = true), settings))
    }

    @Test
    fun `configured wire values replace the defaults`() {
        val settings = AppSettings(custom1WebhookValue = "tag1", custom2WebhookValue = "tag2")
        assertEquals(
            listOf("favorite", "tag1", "tag2"),
            itemActionNames(
                item("a.jpg", favorite = true, custom1 = true, custom2 = true).decisions,
                settings
            )
        )
    }

    @Test
    fun `favorite is not configurable`() {
        // Renaming the custom slots must never touch the one fixed action.
        val settings = AppSettings(custom1WebhookValue = "favorite")
        assertEquals(
            listOf("favorite"),
            itemActionNames(item("a.jpg", favorite = true).decisions, settings)
        )
    }

    @Test
    fun `two slots sharing a wire value emit it once`() {
        val settings = AppSettings(custom1WebhookValue = "tag", custom2WebhookValue = "tag")
        assertEquals(
            listOf("tag"),
            itemActionNames(item("a.jpg", custom1 = true, custom2 = true).decisions, settings)
        )
    }

    @Test
    fun `renaming a slot does not change which slots are selected`() {
        // The boolean means "slot 1 is selected", so the item is untouched and only the emitted
        // string moves. This is the whole point of keeping names out of the item row.
        val marked = item("a.jpg", custom1 = true).decisions
        assertEquals(listOf("cust1"), itemActionNames(marked, AppSettings()))
        assertEquals(
            listOf("archive"),
            itemActionNames(marked, AppSettings(custom1WebhookValue = "archive"))
        )
    }
}
