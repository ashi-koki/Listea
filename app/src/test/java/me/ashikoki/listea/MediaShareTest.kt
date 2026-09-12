package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What sharing decides before an intent is ever built.
 *
 * The chooser, the copy and whatever the receiving app does with the file are all on device and
 * out of Listea's hands. What is pinned here is the part that is entirely Listea's: the name the
 * copy is given — a name that breaks a path is a share that fails before it starts — and the two
 * judgement calls around it, which type to offer it as and when a staged copy is safe to sweep up.
 */
class MediaShareTest {

    @Test
    fun `an ordinary name is carried over untouched`() {
        assertEquals("holiday.jpg", shareFileName("holiday.jpg"))
    }

    @Test
    fun `separators are replaced, so a name can never escape the share folder`() {
        assertEquals("_etc_passwd", shareFileName("/etc/passwd"))
        assertEquals("a_b.mp4", shareFileName("a\\b.mp4"))
        assertFalse('/' in shareFileName("../../secret.jpg"))
    }

    @Test
    fun `a name with nothing usable left in it still produces something shareable`() {
        // "//" survives as "__", which is a perfectly good filename; whitespace does not survive
        // at all, and a copy has to be called something.
        assertEquals("__", shareFileName("//"))
        assertEquals("shared-file", shareFileName("   "))
        assertEquals("shared-file", shareFileName(""))
    }

    @Test
    fun `the extension survives, since it is what a typeless receiver falls back to`() {
        assertTrue(shareFileName("2026-07-11 22:33:44.mp4").endsWith(".mp4"))
    }

    @Test
    fun `the provider's type is used when it has one`() {
        assertEquals("image/jpeg", shareMimeType("image/jpeg", "holiday.jpg"))
    }

    // The extension fallback goes through MimeTypeMap, which is a framework stub off device, so
    // what it answers with is exercised there rather than mocked into meaninglessness here. What
    // matters at this level is that a type is never refused: the last resort is a wildcard.
    @Test
    fun `the last resort is a type that fits anything`() {
        assertEquals("*/*", AnyShareMimeType)
    }

    @Test
    fun `a copy is swept up only once nothing could still be reading it`() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day
        assertFalse(isStaleShare(now, now))
        assertFalse(isStaleShare(now - day / 2, now))
        assertTrue(isStaleShare(now - day - 1, now))
    }

    @Test
    fun `a share that opened the chooser says nothing at all`() {
        assertNull(shareOutcomeMessage(ShareResult.Opened))
    }

    @Test
    fun `everything else says what went wrong`() {
        assertEquals(
            "Could not share: the file could not be read",
            shareOutcomeMessage(ShareResult.Failed("the file could not be read"))
        )
        assertTrue(shareOutcomeMessage(ShareResult.NoApp)!!.isNotBlank())
    }
}
