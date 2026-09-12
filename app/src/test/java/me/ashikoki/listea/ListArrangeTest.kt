package me.ashikoki.listea

import me.ashikoki.listea.data.DecisionTarget
import me.ashikoki.listea.data.fileIdentity
import me.ashikoki.listea.data.ListDetail
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.manualItemIdentity
import me.ashikoki.listea.data.newItemPublicId
import me.ashikoki.listea.data.ReviewDecisions
import me.ashikoki.listea.data.ReviewItem
import me.ashikoki.listea.data.toReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtering and sorting a List's items, and the line that separates it from everything else.
 *
 * The arrangement decides what the Items page shows and what a Review launched from it walks. It
 * decides nothing else, and the tests that matter most here are the ones that pin down what it
 * must leave alone: the progress a list reports, and therefore its completion and its webhook.
 * Those read the whole list, and a round that reviews half of it must still leave the page saying
 * half.
 */
class ListArrangeTest {

    private val now = 1_700_000_000_000L

    private fun daysAgo(days: Int) = now - days * 24L * 60L * 60L * 1000L

    private var nextId = 1L

    /** A folder-backed row, resolved the way the detail page resolves one. */
    private fun sourceItem(
        name: String,
        completed: Boolean = false,
        sizeBytes: Long? = null,
        lastModified: Long? = null
    ): ReviewItem {
        val id = nextId++
        return ListItemEntity(
            id = id,
            publicId = newItemPublicId(fileIdentity("folder/$name"), now = id),
            listId = 1,
            title = name,
            isCompleted = completed,
            sortOrder = id.toInt(),
            createdAt = 0,
            sourceUri = "content://tree/Listea/doc/$name",
            sourceRelativePath = name,
            rootRelativePath = "folder/$name",
            sourceSizeBytes = sizeBytes,
            sourceModifiedAt = lastModified
        ).toReviewItem(rootUri = null).copy(
            // A source-backed row reads its decision from the file state, which the query
            // projects into the entity; `rootUri = null` above only keeps the target simple.
            decisions = ReviewDecisions(isCompleted = completed)
        )
    }

    /** A typed entry: no file, so nothing to measure. */
    private fun manualItem(name: String, completed: Boolean = false): ReviewItem {
        val id = nextId++
        return ReviewItem(
            id = id,
            publicId = newItemPublicId(manualItemIdentity(1, name), now = id),
            title = name,
            sourceUri = null,
            relativePath = null,
            sourceMissing = false,
            target = DecisionTarget.ManualItem(1, id),
            decisions = ReviewDecisions(isCompleted = completed)
        )
    }

    private fun names(items: List<ReviewItem>) = items.map { it.title }

    private fun shown(items: List<ReviewItem>, arrangement: FileArrangement) =
        arrangeFiles(items, arrangement, now, ::reviewFacts)

    // ------------------------------------------------------------------ the facts

    @Test
    fun `an item's facts come off the row, measurements included`() {
        val item = sourceItem("a.jpg", completed = true, sizeBytes = 2048, lastModified = 99L)
        val facts = reviewFacts(item)

        assertEquals("a.jpg", facts.name)
        assertEquals(2048L, facts.sizeBytes)
        assertEquals(99L, facts.lastModified)
        assertEquals(true, facts.isChecked)
    }

    @Test
    fun `a manual item is unmeasured but never uncheckable`() {
        // Checked is always an answer, because every queued item has a decision behind it. Size
        // and time genuinely are not: a typed entry is not a file.
        val facts = reviewFacts(manualItem("call the bank", completed = true))

        assertNull(facts.sizeBytes)
        assertNull(facts.lastModified)
        assertEquals(true, facts.isChecked)
    }

    @Test
    fun `an unmeasured item survives every filter that would need a measurement`() {
        // A list created before the measurements existed, or one whose folder has not been
        // rescanned. The unknown rule: it is shown, not hidden for a reason nobody can see.
        val items = listOf(
            sourceItem("measured.jpg", lastModified = daysAgo(40), sizeBytes = 10),
            sourceItem("unmeasured.jpg"),
            manualItem("typed")
        )

        assertEquals(
            listOf("unmeasured.jpg", "typed"),
            names(shown(items, FileArrangement(freshness = setOf(Freshness.TODAY))))
        )
    }

    // ------------------------------------------------------------------ the page

    @Test
    fun `the default arrangement leaves a list in its own order`() {
        // Stored order, not alphabetical: a manual list is in the order it was built, and the
        // page must not quietly rearrange it.
        val items = listOf(manualItem("water the plants"), manualItem("book flights"))

        assertEquals(
            listOf("water the plants", "book flights"),
            names(shown(items, FileArrangement.Default))
        )
    }

    @Test
    fun `filtering the page keeps the items it hides in the list`() {
        val items = listOf(
            sourceItem("a.jpg"),
            sourceItem("b.txt"),
            sourceItem("c.mp4")
        )
        val filtered = shown(items, FileArrangement(mediaKinds = setOf(MediaKind.PICTURE)))

        assertEquals(listOf("a.jpg"), names(filtered))
        // Nothing was removed from anything: the page is a view, and this is the whole promise.
        assertEquals(3, items.size)
    }

    // ------------------------------------------------------------------ the round

    @Test
    fun `a review launched from a filtered page walks only that subset`() {
        val items = listOf(
            sourceItem("a.jpg", lastModified = daysAgo(1)),
            sourceItem("b.txt", lastModified = daysAgo(1)),
            sourceItem("c.jpg", lastModified = daysAgo(40))
        )

        val round = reviewRound(
            items,
            FileArrangement(mediaKinds = setOf(MediaKind.PICTURE), sort = FileSort.TIME_DESC),
            uncheckedOnly = false,
            now = now
        )

        assertEquals(listOf("a.jpg", "c.jpg"), names(round))
    }

    @Test
    fun `the page's filter and the unchecked only setting narrow together`() {
        val items = listOf(
            sourceItem("a.jpg", completed = true),
            sourceItem("b.jpg"),
            sourceItem("c.txt")
        )

        assertEquals(
            listOf("b.jpg"),
            names(
                reviewRound(
                    items,
                    FileArrangement(mediaKinds = setOf(MediaKind.PICTURE)),
                    uncheckedOnly = true,
                    now = now
                )
            )
        )
    }

    @Test
    fun `filtering to checked while the setting says unchecked only leaves nothing`() {
        val items = listOf(sourceItem("a.jpg", completed = true), sourceItem("b.jpg"))

        assertTrue(
            reviewRound(
                items,
                FileArrangement(checked = setOf(CheckedState.CHECKED)),
                uncheckedOnly = true,
                now = now
            ).isEmpty()
        )
    }

    // ----------------------------------------------------------------- the counts

    @Test
    fun `reviewing a filtered half of a list still leaves the list half done`() {
        // The rule the user gets to see: a hundred items, fifty filtered in, all fifty reviewed.
        // The page must read 50 of 100 — never 50 of 50 — because progress is the list's and the
        // round only ever decided what to show.
        val list = ListEntity(id = 1, title = "Photos", createdAt = 0)
        val before = List(100) { index ->
            sourceItem("f$index.jpg", lastModified = if (index < 50) daysAgo(0) else daysAgo(40))
        }
        val arrangement = FileArrangement(freshness = setOf(Freshness.TODAY))

        val round = reviewRound(before, arrangement, uncheckedOnly = false, now = now)
        assertEquals(50, round.size)

        // The round is walked to the end: every item in it comes back checked.
        val queued = round.map { it.id }.toSet()
        val after = before.map {
            if (it.id in queued) it.copy(decisions = it.decisions.copy(isCompleted = true)) else it
        }

        val detail = ListDetail(list, after)
        assertEquals(50, detail.completedCount)
        assertEquals(100, detail.items.size)
        // And explicitly not the queue's own arithmetic, which would have called it finished.
        assertNotEquals(detail.items.size, round.size)
        assertEquals(
            ReviewProgress(completed = 50, total = 100),
            ReviewProgress(detail.completedCount, detail.items.size)
        )
    }

    @Test
    fun `a list is only complete when all of it is, however narrow the round was`() {
        val list = ListEntity(id = 1, title = "Photos", createdAt = 0)
        val items = listOf(
            sourceItem("a.jpg", completed = true),
            sourceItem("b.txt")
        )
        // Reviewing only the pictures checked everything the round contained.
        val round = reviewRound(
            items,
            FileArrangement(mediaKinds = setOf(MediaKind.PICTURE)),
            uncheckedOnly = false,
            now = now
        )

        assertEquals(round.size, round.count { it.decisions.isCompleted })
        // Completion is the stored `completedAt`, recalculated over every row by the database —
        // the round has no way to reach it, and the unfiltered count says why it must not.
        assertEquals(1, ListDetail(list, items).completedCount)
        assertEquals(false, ListDetail(list, items).isComplete)
    }

    // -------------------------------------------------------------------- the key

    @Test
    fun `a list's arrangement key cannot collide with a folder's`() {
        // They share one stored map so that "same filter and sort everywhere" can mean everywhere.
        val folder = folderArrangementKey("content://tree/Listea", "2026-07-11/bilibili")

        assertNotEquals(folder, listArrangementKey(1))
        assertNotEquals(listArrangementKey(1), listArrangementKey(2))
        assertEquals(
            mapOf(listArrangementKey(7) to FileArrangement(sort = FileSort.SIZE_DESC)),
            decodeFolderArrangements(
                encodeFolderArrangements(
                    mapOf(listArrangementKey(7) to FileArrangement(sort = FileSort.SIZE_DESC))
                )
            )
        )
    }
}
