package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * What the folder browser's filter and sort actually do, tested where they live rather than
 * through a screen.
 *
 * All of it is pure: a list of anything, four facts per element, a clock passed in. That is the
 * point of the shape — the same functions are meant to narrow a list's items and a review queue
 * later, and this is what will still be holding them to their word when they do.
 *
 * The unknown rule gets its own cases in both directions, because it is the one decision here
 * that could quietly hide a user's files: an absent fact never excludes anything, but it always
 * sorts last.
 */
class FileArrangeTest {

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 2, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** [daysAgo] whole calendar days before [now], at nine in the morning. */
    private fun daysBefore(daysAgo: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun file(
        name: String,
        size: Long? = 100L,
        daysAgo: Int? = 0,
        checked: Boolean? = false
    ) = FileFacts(
        name = name,
        sizeBytes = size,
        lastModified = daysAgo?.let(::daysBefore),
        isChecked = checked
    )

    private fun arrange(
        files: List<FileFacts>,
        arrangement: FileArrangement
    ): List<String> = arrangeFiles(files, arrangement, now) { it }.map { it.name }

    // ---------------------------------------------------------------- media type

    @Test
    fun `media kinds are recognised by extension and are case insensitive`() {
        assertEquals(MediaKind.PICTURE, mediaKindOf("Holiday.JPG"))
        assertEquals(MediaKind.VIDEO, mediaKindOf("clip.mp4"))
        assertEquals(MediaKind.TEXT, mediaKindOf("notes.TXT"))
    }

    @Test
    fun `anything outside the three kinds is reachable only through All`() {
        assertNull(mediaKindOf("archive.zip"))
        assertNull(mediaKindOf("README"))

        val files = listOf(file("archive.zip"), file("a.jpg"))
        // The default keeps both, in the order they came in: this is a test about the filter.
        assertEquals(listOf("archive.zip", "a.jpg"), arrange(files, FileArrangement.Default))
        assertEquals(
            listOf("a.jpg"),
            arrange(files, FileArrangement(mediaKinds = setOf(MediaKind.PICTURE)))
        )
    }

    @Test
    fun `selecting two media kinds keeps both`() {
        val files = listOf(file("a.jpg"), file("b.mp4"), file("c.txt"), file("d.zip"))

        val kept = arrange(
            files,
            FileArrangement(mediaKinds = setOf(MediaKind.PICTURE, MediaKind.VIDEO))
        )

        assertEquals(listOf("a.jpg", "b.mp4"), kept)
    }

    // ------------------------------------------------------------------ checked

    @Test
    fun `checked and unchecked can each be asked for alone`() {
        val files = listOf(file("done.jpg", checked = true), file("todo.jpg", checked = false))

        assertEquals(
            listOf("done.jpg"),
            arrange(files, FileArrangement(checked = setOf(CheckedState.CHECKED)))
        )
        assertEquals(
            listOf("todo.jpg"),
            arrange(files, FileArrangement(checked = setOf(CheckedState.UNCHECKED)))
        )
    }

    @Test
    fun `a file whose checked state is not tracked survives every checked filter`() {
        // What the folder page produces with review integration switched off: no ticks anywhere,
        // and so no basis on which to hide anything.
        val files = listOf(file("a.jpg", checked = null), file("b.jpg", checked = null))

        assertEquals(
            listOf("a.jpg", "b.jpg"),
            arrange(files, FileArrangement(checked = setOf(CheckedState.CHECKED)))
        )
    }

    // ---------------------------------------------------------------- freshness

    @Test
    fun `calendar days ignore the time of day`() {
        val lateLastNight = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
        }.timeInMillis

        // 14 hours ago by the clock, but yesterday by the calendar, which is what a user means.
        assertEquals(1, calendarDaysAgo(lateLastNight, now))
    }

    @Test
    fun `a timestamp in the future counts as today rather than as a negative`() {
        assertEquals(0, calendarDaysAgo(now + 5L * 24 * 60 * 60 * 1000, now))
    }

    @Test
    fun `freshness buckets are half open and do not overlap`() {
        val files = (0..30).map { file("d$it.jpg", daysAgo = it) }

        fun namesIn(bucket: Freshness) =
            arrange(files, FileArrangement(freshness = setOf(bucket)))

        assertEquals(listOf("d0.jpg"), namesIn(Freshness.TODAY))
        assertEquals(listOf("d1.jpg", "d2.jpg"), namesIn(Freshness.DAYS_1_3))
        assertEquals(
            listOf("d3.jpg", "d4.jpg", "d5.jpg", "d6.jpg"),
            namesIn(Freshness.DAYS_3_7)
        )
        assertEquals(7, namesIn(Freshness.DAYS_7_14).size)
        assertEquals(16, namesIn(Freshness.DAYS_14_30).size)
    }

    @Test
    fun `nothing older than a month is reachable except through All`() {
        val files = listOf(file("old.jpg", daysAgo = 40), file("new.jpg", daysAgo = 0))

        val everyBucket = FileArrangement(freshness = Freshness.entries.toSet())

        assertEquals(listOf("new.jpg"), arrange(files, everyBucket))
        assertEquals(listOf("old.jpg", "new.jpg"), arrange(files, FileArrangement.Default))
    }

    @Test
    fun `a file with no timestamp survives every freshness filter`() {
        val files = listOf(file("undated.jpg", daysAgo = null), file("old.jpg", daysAgo = 40))

        assertEquals(
            listOf("undated.jpg"),
            arrange(files, FileArrangement(freshness = setOf(Freshness.TODAY)))
        )
    }

    // ------------------------------------------------------------------- groups

    @Test
    fun `the three groups combine as an and`() {
        val files = listOf(
            file("keep.jpg", daysAgo = 0, checked = false),
            file("wrong-type.mp4", daysAgo = 0, checked = false),
            file("wrong-date.jpg", daysAgo = 20, checked = false),
            file("wrong-state.jpg", daysAgo = 0, checked = true)
        )

        val kept = arrange(
            files,
            FileArrangement(
                mediaKinds = setOf(MediaKind.PICTURE),
                checked = setOf(CheckedState.UNCHECKED),
                freshness = setOf(Freshness.TODAY)
            )
        )

        assertEquals(listOf("keep.jpg"), kept)
    }

    // --------------------------------------------------------------------- sort

    @Test
    fun `name order is case insensitive in both directions`() {
        val files = listOf(file("banana.jpg"), file("Apple.jpg"), file("cherry.jpg"))

        assertEquals(
            listOf("Apple.jpg", "banana.jpg", "cherry.jpg"),
            arrange(files, FileArrangement(sort = FileSort.NAME_ASC))
        )
        assertEquals(
            listOf("cherry.jpg", "banana.jpg", "Apple.jpg"),
            arrange(files, FileArrangement(sort = FileSort.NAME_DESC))
        )
    }

    @Test
    fun `time and size sort both ways`() {
        val files = listOf(
            file("mid.jpg", size = 50, daysAgo = 5),
            file("new.jpg", size = 10, daysAgo = 0),
            file("old.jpg", size = 90, daysAgo = 20)
        )

        assertEquals(
            listOf("new.jpg", "mid.jpg", "old.jpg"),
            arrange(files, FileArrangement(sort = FileSort.TIME_DESC))
        )
        assertEquals(
            listOf("old.jpg", "mid.jpg", "new.jpg"),
            arrange(files, FileArrangement(sort = FileSort.TIME_ASC))
        )
        assertEquals(
            listOf("old.jpg", "mid.jpg", "new.jpg"),
            arrange(files, FileArrangement(sort = FileSort.SIZE_DESC))
        )
        assertEquals(
            listOf("new.jpg", "mid.jpg", "old.jpg"),
            arrange(files, FileArrangement(sort = FileSort.SIZE_ASC))
        )
    }

    @Test
    fun `unknown sizes and times sink to the bottom whichever way the sort runs`() {
        val files = listOf(
            file("unknown.jpg", size = null, daysAgo = null),
            file("known.jpg", size = 10, daysAgo = 3)
        )

        for (sort in listOf(FileSort.TIME_DESC, FileSort.TIME_ASC)) {
            assertEquals(
                "sorting by $sort",
                listOf("known.jpg", "unknown.jpg"),
                arrange(files, FileArrangement(sort = sort))
            )
        }
        for (sort in listOf(FileSort.SIZE_DESC, FileSort.SIZE_ASC)) {
            assertEquals(
                "sorting by $sort",
                listOf("known.jpg", "unknown.jpg"),
                arrange(files, FileArrangement(sort = sort))
            )
        }
    }

    @Test
    fun `format sort groups by extension and orders within a group by name`() {
        val files = listOf(
            file("z.jpg"), file("a.mp4"), file("b.jpg"), file("m.txt")
        )

        assertEquals(
            listOf("b.jpg", "z.jpg", "a.mp4", "m.txt"),
            arrange(files, FileArrangement(sort = FileSort.FORMAT))
        )
    }

    @Test
    fun `checked sort puts what is left to do first, untracked files included`() {
        val files = listOf(
            file("done.jpg", checked = true),
            file("todo.jpg", checked = false),
            file("untracked.jpg", checked = null)
        )

        assertEquals(
            listOf("todo.jpg", "untracked.jpg", "done.jpg"),
            arrange(files, FileArrangement(sort = FileSort.CHECKED))
        )
    }

    @Test
    fun `every sort falls back to the name, so no listing is ever ambiguous`() {
        val files = listOf(
            file("b.jpg", size = 10, daysAgo = 1),
            file("a.jpg", size = 10, daysAgo = 1)
        )

        for (sort in FileSort.entries) {
            val ordered = arrange(files, FileArrangement(sort = sort))
            when (sort) {
                // Not a sort at all: it hands back what it was given, ties and all.
                FileSort.NATURAL -> assertEquals(listOf("b.jpg", "a.jpg"), ordered)
                FileSort.NAME_DESC -> Unit
                else -> assertEquals("sorting by $sort", listOf("a.jpg", "b.jpg"), ordered)
            }
        }
    }

    // ------------------------------------------------------------------ default

    @Test
    fun `the default arrangement is every file, in whatever order it arrived`() {
        // Not "by name": a folder already arrives in name order, but a List arrives in its own,
        // and a default that re-alphabetised would rearrange a list the user built by hand.
        val default = FileArrangement.Default

        assertTrue(default.isDefault)
        assertTrue(default.isFilterDefault)
        assertTrue(default.isSortDefault)
        assertEquals(FileSort.NATURAL, default.sort)

        val files = listOf(file("c.jpg"), file("a.jpg"), file("b.jpg"))
        assertEquals(listOf("c.jpg", "a.jpg", "b.jpg"), arrange(files, default))
    }

    @Test
    fun `the header can tell a filter apart from a sort`() {
        val filtered = FileArrangement(mediaKinds = setOf(MediaKind.VIDEO))
        assertFalse(filtered.isFilterDefault)
        assertTrue(filtered.isSortDefault)

        val sorted = FileArrangement(sort = FileSort.TIME_DESC)
        assertTrue(sorted.isFilterDefault)
        assertFalse(sorted.isSortDefault)
    }

    // -------------------------------------------------------------- persistence

    @Test
    fun `an arrangement survives a round trip through storage`() {
        val original = FileArrangement(
            mediaKinds = setOf(MediaKind.PICTURE, MediaKind.VIDEO),
            checked = setOf(CheckedState.UNCHECKED),
            freshness = setOf(Freshness.TODAY, Freshness.DAYS_7_14),
            sort = FileSort.SIZE_DESC
        )

        assertEquals(original, decodeArrangement(encodeArrangement(original)))
    }

    @Test
    fun `unrecognised stored values are dropped rather than breaking the read`() {
        val decoded = decodeArrangement("no_such_sort|picture,no_such_kind||")

        assertEquals(FileSort.NATURAL, decoded.sort)
        assertEquals(setOf(MediaKind.PICTURE), decoded.mediaKinds)
        assertTrue(decoded.checked.isEmpty())
    }

    @Test
    fun `the per-folder map survives a round trip, folder names with bars included`() {
        val map = mapOf(
            "content://tree/root|" to FileArrangement(sort = FileSort.TIME_DESC),
            "content://tree/root|2026-07-11/bilibili" to
                FileArrangement(mediaKinds = setOf(MediaKind.VIDEO))
        )

        assertEquals(map, decodeFolderArrangements(encodeFolderArrangements(map)))
    }

    @Test
    fun `putting a folder back to its default forgets it rather than storing it`() {
        val existing = mapOf("a" to FileArrangement(sort = FileSort.TIME_DESC))

        val updated = rememberArrangement(existing, "a", FileArrangement.Default)

        assertTrue(updated.isEmpty())
    }

    @Test
    fun `remembering is capped, dropping the folder arranged longest ago`() {
        var stored = emptyMap<String, FileArrangement>()
        repeat(205) { index ->
            stored = rememberArrangement(
                stored,
                "folder$index",
                FileArrangement(sort = FileSort.TIME_DESC)
            )
        }

        assertEquals(200, stored.size)
        assertFalse("folder0" in stored)
        assertTrue("folder204" in stored)
    }

    @Test
    fun `re-arranging a folder makes it the most recent, so the cap spares it`() {
        var stored = emptyMap<String, FileArrangement>()
        repeat(200) { index ->
            stored = rememberArrangement(stored, "folder$index", FileArrangement(sort = FileSort.TIME_DESC))
        }

        // Touched again, then pushed past the cap: the freshly touched one must survive.
        stored = rememberArrangement(stored, "folder0", FileArrangement(sort = FileSort.SIZE_ASC))
        stored = rememberArrangement(stored, "newcomer", FileArrangement(sort = FileSort.TIME_ASC))

        assertEquals(200, stored.size)
        assertTrue("folder0" in stored)
        assertFalse("folder1" in stored)
    }

    // ---------------------------------------------------------------- resolution

    @Test
    fun `shared mode ignores what a folder has of its own, and gives it back afterwards`() {
        val shared = FileArrangement(sort = FileSort.TIME_DESC)
        val mine = FileArrangement(mediaKinds = setOf(MediaKind.VIDEO))
        val arrangements = FolderArrangements(global = shared, byFolder = mapOf("here" to mine))

        assertEquals(shared, resolveArrangement(arrangements, "here", sharedEverywhere = true))
        assertEquals(mine, resolveArrangement(arrangements, "here", sharedEverywhere = false))
    }

    @Test
    fun `a folder nobody has arranged shows everything by name`() {
        val arrangements = FolderArrangements(global = FileArrangement(sort = FileSort.SIZE_DESC))

        assertEquals(
            FileArrangement.Default,
            resolveArrangement(arrangements, "untouched", sharedEverywhere = false)
        )
    }
}
