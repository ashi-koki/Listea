package me.ashikoki.listea

import me.ashikoki.listea.data.FolderListScope
import me.ashikoki.listea.data.SourceItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The folder display semantics V4 will render: what a folder *is*, what progress it shows, and
 * who may show a webhook.
 */
class FolderTypeTest {

    private fun scope(
        id: Long,
        path: String,
        total: Int = 4,
        completed: Int = 2,
        webhookEnabled: Boolean = true
    ) = FolderListScope(
        id = id,
        title = "list-$id",
        relativePath = path,
        completedAt = if (total > 0 && completed == total) 1L else null,
        totalItems = total,
        completedItems = completed,
        webhookEnabled = webhookEnabled,
        lastDeliveryStatus = null,
        lastDeliveryCode = null
    )

    private fun ownership(scopes: List<FolderListScope>, items: List<SourceItemRef>) =
        buildFolderOwnership(scopes, items)

    @Test
    fun `a folder owning its list is a List`() {
        val own = ownership(listOf(scope(1, "2026-07-11")), emptyList())
        assertEquals(FolderType.LIST, folderTypeOf(folderListStatus(own, "2026-07-11")))
    }

    @Test
    fun `a folder under an ancestor list is a Sublist`() {
        val own = ownership(
            listOf(scope(1, "2026-07-11")),
            listOf(SourceItemRef(1, "bilibili/a.jpg", true))
        )
        val status = folderListStatus(own, "2026-07-11/bilibili")
        assertEquals(FolderType.SUBLIST, folderTypeOf(status))
    }

    @Test
    fun `a folder with lists only beneath it is still None`() {
        // parent-folder owns nothing and no ancestor covers it; child-a and child-b own lists.
        val own = ownership(
            listOf(scope(1, "parent-folder/child-a"), scope(2, "parent-folder/child-b")),
            emptyList()
        )
        val status = folderListStatus(own, "parent-folder")
        assertEquals(FolderType.NONE, folderTypeOf(status))
        assertNull(folderProgressLabel(status))
        assertNull(statusWebhookLabel(status))
    }

    @Test
    fun `only a List may show webhook status`() {
        val own = ownership(
            listOf(scope(1, "2026-07-11")),
            listOf(SourceItemRef(1, "bilibili/a.jpg", true))
        )
        val direct = folderListStatus(own, "2026-07-11")
        val sub = folderListStatus(own, "2026-07-11/bilibili")

        assertEquals(FolderType.LIST, folderTypeOf(direct))
        assertEquals(FolderType.SUBLIST, folderTypeOf(sub))
        // The webhook belongs to the owning list and is shown only where it is configured.
        assertNull(statusWebhookLabel(sub))
        assertEquals("Webhook · On · Never sent", statusWebhookLabel(direct))
    }

    @Test
    fun `a Sublist reports its own subtree, not the whole list`() {
        val own = ownership(
            listOf(scope(1, "root", total = 3, completed = 1)),
            listOf(
                SourceItemRef(1, "bilibili/a.jpg", true),
                SourceItemRef(1, "bilibili/b.jpg", true),
                SourceItemRef(1, "danbooru/c.jpg", false)
            )
        )
        assertEquals("2 / 2 ✓", folderProgressLabel(folderListStatus(own, "root/bilibili")))
        assertEquals("0 / 1", folderProgressLabel(folderListStatus(own, "root/danbooru")))
    }

    @Test
    fun `progress reads as a number without the word complete`() {
        assertEquals("3 / 5", progressLabel(3, 5, false))
        assertEquals("5 / 5 ✓", progressLabel(5, 5, true))
    }
}
