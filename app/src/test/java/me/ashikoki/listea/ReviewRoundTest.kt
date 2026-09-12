package me.ashikoki.listea

import me.ashikoki.listea.data.DecisionTarget
import me.ashikoki.listea.data.fileIdentity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.newItemPublicId
import me.ashikoki.listea.data.ReviewDecisions
import me.ashikoki.listea.data.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two round-based rules and their interaction: a review can be narrowed to what was unchecked
 * when it opened, and a payload can be narrowed to what is checked when it is sent. Together they
 * are what makes "send the decisions of this round" work, so the seam between them is worth
 * pinning down without a device.
 *
 * The seam has a shape now: a round narrows [ReviewItem]s, hands on their ids, and the payload is
 * built from the rows those ids identify. Both halves are exercised the way the delivery path
 * actually joins them.
 */
class ReviewRoundTest {

    private var nextId = 1L

    private fun item(completed: Boolean = false): ReviewItem {
        val id = nextId++
        return ReviewItem(
            id = id,
            publicId = newItemPublicId(fileIdentity("folder/item$id.jpg"), now = id),
            title = "item $id",
            sourceUri = "content://tree/doc/$id",
            relativePath = "item$id.jpg",
            sourceMissing = false,
            target = DecisionTarget.File("content://tree/Listea", "folder/item$id.jpg"),
            decisions = ReviewDecisions(isCompleted = completed)
        )
    }

    /** What a round takes on entry: the ids it queues, in the order it walks them. */
    private fun roundIds(
        items: List<ReviewItem>,
        arrangement: FileArrangement = FileArrangement.Default,
        uncheckedOnly: Boolean = true
    ): LongArray =
        reviewRound(items, arrangement, uncheckedOnly, now = 0L).map { it.id }.toLongArray()

    private fun checked(item: ReviewItem) =
        item.copy(decisions = item.decisions.copy(isCompleted = true))

    /** The stored row a queued item resolves back to when its payload is built. */
    private fun row(item: ReviewItem) = ListItemEntity(
        id = item.id,
        publicId = item.publicId,
        listId = 1,
        title = item.title,
        isCompleted = item.decisions.isCompleted,
        sortOrder = 0,
        createdAt = 0,
        sourceUri = item.sourceUri,
        sourceRelativePath = item.relativePath,
        rootRelativePath = (item.target as DecisionTarget.File).relativePath
    )

    @Test
    fun `every new switch is off, so nothing changes until one is turned on`() {
        val defaults = AppSettings()
        assertFalse(defaults.quickReviewWebhookEnabled)
        assertFalse(defaults.webhookCompletedItemsOnly)
        assertFalse(defaults.reviewUncheckedOnly)
        assertFalse(defaults.webhookOnReviewExit)
    }

    @Test
    fun `a narrowed round queues only what was unchecked on entry`() {
        val items = listOf(item(completed = true), item(), item(completed = true), item())
        val queued = roundIds(items)

        assertEquals(listOf(items[1].id, items[3].id), queued.toList())
        assertEquals(listOf(items[1], items[3]), queuedItems(items, queued))
    }

    @Test
    fun `checking an item mid-round leaves it in the queue`() {
        val items = listOf(item(), item())
        val queued = roundIds(items)

        // The card the user just swiped must not vanish from under the queue it is part of.
        val afterSwipe = listOf(checked(items[0]), items[1])
        assertEquals(afterSwipe, queuedItems(afterSwipe, queued))
    }

    @Test
    fun `items that disappear mid-round shorten the queue instead of breaking it`() {
        val items = listOf(item(), item(), item())
        val queued = roundIds(items)

        val afterCleanup = listOf(items[0], items[2])
        assertEquals(afterCleanup, queuedItems(afterCleanup, queued))
    }

    @Test
    fun `the queue is walked in the snapshot's order, not the order the items arrive in`() {
        // Quick Review snapshots the folder page's sort order, and the database re-emits its own
        // order on every change. The snapshot is the only record of the former, so it is what
        // decides — normal Review snapshots in stored order and is therefore left untouched.
        val items = listOf(item(), item(), item())
        val queued = longArrayOf(items[2].id, items[0].id)

        assertEquals(listOf(items[2], items[0]), queuedItems(items, queued))
    }

    @Test
    fun `a round with nothing left unchecked queues nothing`() {
        val items = listOf(item(completed = true), item(completed = true))
        assertTrue(queuedItems(items, roundIds(items)).isEmpty())
    }

    @Test
    fun `by default a payload carries every item it covers`() {
        val rows = listOf(item(completed = true), item()).map(::row)
        assertEquals(rows, webhookItems(rows, AppSettings()))
    }

    @Test
    fun `the filter drops the items that are not checked`() {
        val rows = listOf(item(completed = true), item(), item(completed = true)).map(::row)
        val settings = AppSettings(webhookCompletedItemsOnly = true)

        assertEquals(listOf(rows[0], rows[2]), webhookItems(rows, settings))
    }

    @Test
    fun `a round where nothing was checked narrows to nothing, rather than to everything`() {
        // An empty result is what the delivery path turns into a "Nothing to send" notice
        // instead of posting; the filter's job is only to be honest about there being none.
        val rows = listOf(item(), item()).map(::row)
        assertTrue(webhookItems(rows, AppSettings(webhookCompletedItemsOnly = true)).isEmpty())
    }

    @Test
    fun `both switches on send exactly the decisions of the round just reviewed`() {
        val items = listOf(item(completed = true), item(), item())
        // The round queues the two unchecked items; the user checks the first of them.
        val queued = roundIds(items)
        val afterRound = listOf(items[0], checked(items[1]), items[2])

        // What delivery does: narrow to the round, then read those items back as rows.
        val roundIds = queuedItems(afterRound, queued).map { it.id }.toSet()
        val rows = afterRound.map(::row).filter { it.id in roundIds }

        val sent = webhookItems(rows, AppSettings(webhookCompletedItemsOnly = true))

        // Not the item that was already checked before the round started.
        assertEquals(listOf(items[1].id), sent.map { it.id })
    }
}
