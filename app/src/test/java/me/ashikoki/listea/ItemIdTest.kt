package me.ashikoki.listea

import me.ashikoki.listea.data.ITEM_PUBLIC_ID_LENGTH
import me.ashikoki.listea.data.fileIdentity
import me.ashikoki.listea.data.itemIdentityOf
import me.ashikoki.listea.data.itemPublicIdTime
import me.ashikoki.listea.data.manualItemIdentity
import me.ashikoki.listea.data.newItemPublicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four things a public id promises the receiver on the other end of the webhook.
 *
 * Uniqueness is the one that matters and the one that cannot be proven by a test, so what is
 * pinned instead is every mechanism uniqueness rests on: that the random field is actually part
 * of the id, that two arrivals of one file in one millisecond still differ, and that neither of
 * the other two fields can quietly stop varying. The rest — sorting, the fingerprint, the length
 * — are the properties that make the id pleasant rather than merely correct, and they are the
 * ones a careless change to the encoding would break silently.
 */
class ItemIdTest {

    private val identity = fileIdentity("2026-07-11/bilibili/a.jpg")

    @Test
    fun `an id is fixed width, so a column of them lines up and sorts`() {
        val id = newItemPublicId(identity, now = 1_767_100_000_000L, random = 12345L)

        assertEquals(24, ITEM_PUBLIC_ID_LENGTH)
        assertEquals(ITEM_PUBLIC_ID_LENGTH, id.length)
        assertEquals(listOf(10, 6, 6), id.split("-").map { it.length })
    }

    @Test
    fun `sorting ids as plain strings sorts them by when they were made`() {
        val early = newItemPublicId(identity, now = 1_000L, random = 999_999L)
        val late = newItemPublicId(identity, now = 1_767_100_000_000L, random = 1L)

        // Deliberately compared as strings and not decoded: a receiver ordering by this column
        // in SQL, or in a spreadsheet, is the whole reason the timestamp leads.
        assertTrue("$early should sort before $late", early < late)
    }

    @Test
    fun `a later millisecond always produces a later id, with no padding gaps`() {
        val ids = listOf(1L, 9L, 32L, 1_024L, 1_767_100_000_000L)
            .map { newItemPublicId(identity, now = it, random = 0L) }

        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `the same file written in twice gets two different ids`() {
        // The point of the whole scheme: one file arriving again is a second record downstream,
        // not an overwrite of the first. Same file, same millisecond — only the random differs.
        val first = newItemPublicId(identity, now = 1_767_100_000_000L, random = 1L)
        val second = newItemPublicId(identity, now = 1_767_100_000_000L, random = 2L)

        assertNotEquals(first, second)
    }

    @Test
    fun `the same file always fingerprints the same way, whenever it arrives`() {
        val morning = newItemPublicId(identity, now = 1_767_100_000_000L, random = 1L)
        val evening = newItemPublicId(identity, now = 1_767_140_000_000L, random = 2L)

        assertEquals(morning.substring(11, 17), evening.substring(11, 17))
    }

    @Test
    fun `different files fingerprint differently`() {
        val a = newItemPublicId(fileIdentity("folder/a.jpg"), now = 0L, random = 0L)
        val b = newItemPublicId(fileIdentity("folder/b.jpg"), now = 0L, random = 0L)

        assertNotEquals(a, b)
    }

    @Test
    fun `a manual item and a file of the same name are not the same thing`() {
        val file = newItemPublicId(fileIdentity("todo"), now = 0L, random = 0L)
        val manual = newItemPublicId(manualItemIdentity(1, "todo"), now = 0L, random = 0L)

        assertNotEquals(file, manual)
    }

    @Test
    fun `a manual item's identity is the list it was typed into as well as its title`() {
        val inOne = newItemPublicId(manualItemIdentity(1, "todo"), now = 0L, random = 0L)
        val inTwo = newItemPublicId(manualItemIdentity(2, "todo"), now = 0L, random = 0L)

        assertNotEquals(inOne, inTwo)
    }

    @Test
    fun `an id carries the time it was made, and gives it back`() {
        val at = 1_767_100_000_000L

        assertEquals(at, itemPublicIdTime(newItemPublicId(identity, now = at, random = 7L)))
    }

    @Test
    fun `the alphabet leaves out the characters that get misread`() {
        val id = newItemPublicId(identity, now = 1_767_100_000_000L, random = 987_654L)

        assertTrue(id, id.none { it in "ILOU" })
        assertTrue(id, id.all { it.isDigit() || it in 'A'..'Z' || it == '-' })
    }

    @Test
    fun `anything that is not one of ours decodes to nothing rather than to a wrong time`() {
        assertNull(itemPublicIdTime("173"))
        assertNull(itemPublicIdTime(""))
        // Right length, wrong alphabet: the excluded letters must not decode as something else.
        assertNull(itemPublicIdTime("IIIIIIIIII-4H7ZP2-XC5N0V"))
    }

    @Test
    fun `a backfilled row fingerprints exactly as a freshly inserted one would`() {
        // What the V10 migration promises: an item that predates the scheme is given an id
        // indistinguishable from the one it would have been given at insert. Everything but the
        // random field has to match, which is everything the migration actually decides.
        val at = 1_767_100_000_000L
        val backfilled = newItemPublicId(
            itemIdentityOf(rootRelativePath = "2026-07-11/bilibili/a.jpg", listId = 1, title = "a.jpg"),
            now = at
        )
        val inserted = newItemPublicId(fileIdentity("2026-07-11/bilibili/a.jpg"), now = at)

        assertEquals(inserted.substring(0, 17), backfilled.substring(0, 17))
    }

    @Test
    fun `a row with no source file falls back to being a manual item`() {
        val backfilled = newItemPublicId(
            itemIdentityOf(rootRelativePath = null, listId = 4, title = "call the vet"),
            now = 0L,
            random = 0L
        )

        assertEquals(newItemPublicId(manualItemIdentity(4, "call the vet"), 0L, 0L), backfilled)
    }

    @Test
    fun `ids generated for one folder of files are all distinct`() {
        // Not a proof of uniqueness — nothing here could be — but the shape of the real case:
        // a scan inserting a folder's worth of rows in the same millisecond, with the live
        // random rather than an injected one.
        val ids = (1..2_000).map { newItemPublicId(fileIdentity("folder/file$it.jpg")) }

        assertEquals(ids.size, ids.toSet().size)
    }
}
