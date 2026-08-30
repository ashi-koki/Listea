package me.ashikoki.listea

import me.ashikoki.listea.data.ListItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Quick Review queue rule: direct children of one folder only, from the owning list's real
 * items. These pin the scoping down without a device, since getting it wrong would silently show
 * the user the wrong files.
 */
class QuickReviewQueueTest {

    private var nextId = 1L

    private fun item(
        sourcePath: String?,
        missing: Boolean = false
    ) = ListItemEntity(
        id = nextId++,
        listId = 1,
        title = sourcePath?.substringAfterLast('/') ?: "manual note",
        sortOrder = 0,
        createdAt = 0,
        sourceUri = sourcePath?.let { "content://doc/$it" },
        sourceRelativePath = sourcePath,
        sourceMissing = missing
    )

    private fun titlesOf(items: List<ListItemEntity>) = items.map { it.title }

    @Test
    fun `only direct children of the browsed folder are queued`() {
        val items = listOf(
            item("a.jpg"),
            item("b.mp4"),
            item("folder1/c.jpg"),
            item("folder1/deeper/d.jpg")
        )
        // List owns 2026-07-11/bilibili and we are browsing it.
        val queue = directSourceItems(items, "2026-07-11/bilibili", "2026-07-11/bilibili")
        assertEquals(listOf("a.jpg", "b.mp4"), titlesOf(queue))
    }

    @Test
    fun `browsing a subfolder queues that level only`() {
        val items = listOf(
            item("a.jpg"),
            item("folder1/c.jpg"),
            item("folder1/deeper/d.jpg")
        )
        val queue = directSourceItems(items, "2026-07-11/bilibili", "2026-07-11/bilibili/folder1")
        assertEquals(listOf("c.jpg"), titlesOf(queue))
    }

    @Test
    fun `an inherited list is scoped exactly like a direct one`() {
        // The ancestor 2026-07-11 owns the list; we are browsing 2026-07-11/bilibili.
        val items = listOf(
            item("bilibili/a.jpg"),
            item("bilibili/sub/b.jpg"),
            item("danbooru/c.jpg")
        )
        val queue = directSourceItems(items, "2026-07-11", "2026-07-11/bilibili")
        assertEquals(listOf("a.jpg"), titlesOf(queue))
    }

    @Test
    fun `a sibling folder with a shared name prefix is not swept in`() {
        // Naive prefix matching would pull bilibili2 into bilibili.
        val items = listOf(item("bilibili/a.jpg"), item("bilibili2/b.jpg"))
        val queue = directSourceItems(items, "2026-07-11", "2026-07-11/bilibili")
        assertEquals(listOf("a.jpg"), titlesOf(queue))
    }

    @Test
    fun `manual items never appear`() {
        val items = listOf(item("a.jpg"), item(null))
        assertEquals(listOf("a.jpg"), titlesOf(directSourceItems(items, "", "")))
    }

    @Test
    fun `vanished sources never appear`() {
        val items = listOf(item("a.jpg"), item("gone.jpg", missing = true))
        assertEquals(listOf("a.jpg"), titlesOf(directSourceItems(items, "", "")))
    }

    @Test
    fun `a list linked to the root itself scopes to the root`() {
        val items = listOf(item("a.jpg"), item("sub/b.jpg"))
        assertEquals(listOf("a.jpg"), titlesOf(directSourceItems(items, "", "")))
        assertEquals(listOf("b.jpg"), titlesOf(directSourceItems(items, "", "sub")))
    }

    @Test
    fun `a folder holding only subfolders yields an empty queue`() {
        val items = listOf(item("sub1/a.jpg"), item("sub2/b.jpg"))
        assertEquals(emptyList<String>(), titlesOf(directSourceItems(items, "", "")))
    }

    @Test
    fun `stored order is preserved`() {
        val items = listOf(item("c.jpg"), item("a.jpg"), item("b.jpg"))
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), titlesOf(directSourceItems(items, "", "")))
    }
}
