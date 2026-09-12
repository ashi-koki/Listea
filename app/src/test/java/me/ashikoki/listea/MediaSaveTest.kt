package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Save decides before it touches storage, and what it reports afterwards.
 *
 * The copy itself goes through MediaStore and is exercised on device. What these pin is the two
 * decisions made without one: whether a file belongs in a gallery album at all, and what the user
 * is told about a save that did not simply succeed — because a save reported as done that did not
 * happen is the failure mode that costs a file.
 */
class MediaSaveTest {

    @Test
    fun `the provider's type decides what the file is`() {
        assertEquals(SavedMediaKind.Image, mediaKindOf("image/jpeg", "holiday.jpg"))
        assertEquals(SavedMediaKind.Video, mediaKindOf("video/mp4", "clip.mp4"))
    }

    @Test
    fun `an animated gif is an image, which is what an album can hold`() {
        assertEquals(SavedMediaKind.Image, mediaKindOf("image/gif", "loop.gif"))
    }

    @Test
    fun `anything that is not media is refused rather than saved`() {
        assertNull(mediaKindOf("text/plain", "notes.txt"))
        assertNull(mediaKindOf("application/pdf", "manual.pdf"))
    }

    @Test
    fun `the album sits under DCIM so one album holds both photos and video`() {
        assertEquals("DCIM/$ListeaAlbumName", ListeaAlbumRelativePath)
    }

    @Test
    fun `a second save of the same file is reported as already there, not as a failure`() {
        val message = saveOutcomeMessage(SaveToAlbumResult.AlreadySaved("holiday.jpg"))

        assertTrue(message, message.contains("holiday.jpg"))
        assertTrue(message, message.contains("already"))
    }

    @Test
    fun `a failure says what went wrong instead of claiming a save`() {
        val message = saveOutcomeMessage(SaveToAlbumResult.Failed("the file could not be read"))

        assertEquals("Could not save: the file could not be read", message)
    }

    @Test
    fun `a save that worked names the album the user should go looking in`() {
        val message = saveOutcomeMessage(SaveToAlbumResult.Saved("holiday.jpg"))

        assertTrue(message, message.contains(ListeaAlbumName))
    }
}
