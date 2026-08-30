package me.ashikoki.listea

import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.itemActionNames
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
            "2026-07-11/bilibili/example.jpg",
            webhookRelativePath(list("2026-07-11/bilibili"), item("example.jpg"))
        )
    }

    @Test
    fun `nested item keeps its subfolders`() {
        assertEquals(
            "2026-07-11/bilibili/sub/a.jpg",
            webhookRelativePath(list("2026-07-11/bilibili"), item("sub/a.jpg"))
        )
    }

    @Test
    fun `a list linked to the root itself adds no prefix`() {
        assertEquals("a.jpg", webhookRelativePath(list(""), item("a.jpg")))
    }

    @Test
    fun `stray separators cannot produce a doubled slash`() {
        assertEquals(
            "2026-07-11/bilibili/a.jpg",
            webhookRelativePath(list("/2026-07-11/bilibili/"), item("/a.jpg"))
        )
    }

    @Test
    fun `manual items have no path at all`() {
        assertNull(webhookRelativePath(list(null), item(null)))
    }

    @Test
    fun `actions serialize in a fixed order, whatever is set`() {
        assertEquals(emptyList<String>(), itemActionNames(item("a.jpg")))
        assertEquals(listOf("favorite"), itemActionNames(item("a.jpg", favorite = true)))
        assertEquals(
            listOf("favorite", "cust2"),
            itemActionNames(item("a.jpg", favorite = true, custom2 = true))
        )
        assertEquals(
            listOf("favorite", "cust1", "cust2"),
            itemActionNames(item("a.jpg", favorite = true, custom1 = true, custom2 = true))
        )
    }

    @Test
    fun `completion and actions are independent`() {
        val unchecked = item("a.jpg", custom1 = true).copy(isCompleted = false)
        assertEquals(listOf("cust1"), itemActionNames(unchecked))
        assertEquals(listOf("cust1"), itemActionNames(unchecked.copy(isCompleted = true)))
    }
}
