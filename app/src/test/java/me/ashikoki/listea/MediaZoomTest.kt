package me.ashikoki.listea

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a pinch-zoomable gallery is expected to follow, none of which are about drawing.
 *
 * All of it lives in the arithmetic that [MediaZoomState.onGesture] does before anything is
 * rendered: what it clamps, and what it hands back as unused. That returned leftover is the whole
 * of the zoom-versus-page arbitration — the card turns the page when, and only when, a horizontal
 * drag comes back unspent — so these are as much tests of paging as of zooming.
 *
 * The card is a portrait 1000x2000 throughout, holding a landscape 2:1 picture. That combination
 * is the interesting one: fitted, the picture is 1000x500 and most of the card is letterbox, so
 * an implementation that sized its pan limits from the card rather than from the picture would
 * pass the horizontal cases and fail every vertical one.
 */
class MediaZoomTest {

    private val viewport = Size(1000f, 2000f)

    /** Fitted, this is 1000 wide and 500 tall, centred, with 750 of letterbox above and below. */
    private fun landscapeCard() = MediaZoomState().apply {
        onViewportChanged(viewport)
        contentAspect = 2f
    }

    /**
     * Centred, to within a float's idea of it.
     *
     * Not `assertEquals(Offset.Zero, ...)`: [Offset] packs two floats into a Long and compares the
     * packing, and clamping a leftward drag against a zero-width pan range lands on -0.0f, which
     * is a different bit pattern from 0.0f while being the same position on screen.
     */
    private fun assertCentred(offset: Offset) {
        assertEquals(0f, offset.x, 0.0001f)
        assertEquals(0f, offset.y, 0.0001f)
    }

    /** A pinch at the centre of the card, which is what leaves the offset arithmetic alone. */
    private fun MediaZoomState.pinch(factor: Float) =
        onGesture(Offset(500f, 1000f), Offset.Zero, factor)

    private fun MediaZoomState.drag(dx: Float, dy: Float = 0f) =
        onGesture(Offset(500f, 1000f), Offset(dx, dy), 1f)

    @Test
    fun `a fitted picture cannot be pinched any smaller`() {
        val zoom = landscapeCard()

        zoom.pinch(0.25f)

        assertEquals(1f, zoom.scale, 0.0001f)
        assertFalse(zoom.isZoomedIn)
    }

    @Test
    fun `zooming out from a zoomed picture stops at its fitted size`() {
        val zoom = landscapeCard()
        zoom.pinch(3f)
        assertEquals(3f, zoom.scale, 0.0001f)

        // Further than it takes to get back, which is the ordinary way a user unzooms.
        zoom.pinch(0.1f)

        assertEquals(1f, zoom.scale, 0.0001f)
        assertCentred(zoom.offset)
    }

    @Test
    fun `a fitted picture passes every horizontal drag straight back for paging`() {
        val zoom = landscapeCard()

        val leftover = zoom.drag(dx = -120f)

        assertEquals(-120f, leftover, 0.0001f)
        assertCentred(zoom.offset)
    }

    @Test
    fun `a zoomed picture absorbs a drag as panning instead of paging`() {
        val zoom = landscapeCard()
        zoom.pinch(2f)

        // Scaled, the picture is 2000 wide against a 1000 card: 500 of overhang each side.
        val leftover = zoom.drag(dx = -200f)

        assertEquals(0f, leftover, 0.0001f)
        assertEquals(-200f, zoom.offset.x, 0.0001f)
    }

    @Test
    fun `panning past the right edge of a zoomed picture spills into a forward page turn`() {
        val zoom = landscapeCard()
        zoom.pinch(2f)
        // All the way to the right-hand edge, and no further.
        assertEquals(0f, zoom.drag(dx = -500f), 0.0001f)
        assertEquals(-500f, zoom.offset.x, 0.0001f)

        val leftover = zoom.drag(dx = -80f)

        assertEquals(-80f, leftover, 0.0001f)
        assertEquals(-500f, zoom.offset.x, 0.0001f)
    }

    @Test
    fun `panning past the left edge of a zoomed picture spills into a backward page turn`() {
        val zoom = landscapeCard()
        zoom.pinch(2f)
        assertEquals(0f, zoom.drag(dx = 500f), 0.0001f)

        val leftover = zoom.drag(dx = 60f)

        assertEquals(60f, leftover, 0.0001f)
        assertEquals(500f, zoom.offset.x, 0.0001f)
    }

    @Test
    fun `a letterboxed picture has nowhere to pan vertically until it outgrows the card`() {
        val zoom = landscapeCard()
        // 500 tall fitted, so 2x is 1000 and 4x is 2000: still no taller than the 2000 card.
        zoom.pinch(4f)

        zoom.drag(dx = 0f, dy = -300f)

        assertEquals(0f, zoom.offset.y, 0.0001f)
    }

    @Test
    fun `vertical panning stops dead at the top and bottom rather than spilling anywhere`() {
        val zoom = landscapeCard()
        // Asking for 6x gets the 5x ceiling, which makes the 500-tall picture 2500 against a
        // 2000 card: 250 of overhang top and bottom, and nothing beyond it.
        zoom.pinch(6f)
        assertEquals(MaxScaleCeiling, zoom.scale, 0.0001f)

        val leftover = zoom.drag(dx = 0f, dy = -900f)

        // Clamped to the overhang, and nothing handed back: there is no page above or below.
        assertEquals(-250f, zoom.offset.y, 0.0001f)
        assertEquals(0f, leftover, 0.0001f)
    }

    @Test
    fun `pinching off centre keeps the point under the fingers where it was`() {
        val zoom = landscapeCard()

        // A point 250px left of the card's centre, doubled about itself.
        zoom.onGesture(Offset(250f, 1000f), Offset.Zero, 2f)

        // screen = centre + offset + scale * (local - centre), solved for the anchor staying put.
        val anchorAfter = 500f + zoom.offset.x + zoom.scale * (250f - 500f)
        assertEquals(250f, anchorAfter, 0.0001f)
    }

    @Test
    fun `an unknown shape is treated as filling the card`() {
        val zoom = MediaZoomState().apply { onViewportChanged(viewport) }
        zoom.pinch(2f)

        // No letterbox to account for, so both axes have half a card of overhang.
        zoom.drag(dx = -900f, dy = -1500f)

        assertEquals(-500f, zoom.offset.x, 0.0001f)
        assertEquals(-1000f, zoom.offset.y, 0.0001f)
    }

    @Test
    fun `resetting returns the card to fitted and centred`() {
        val zoom = landscapeCard()
        zoom.pinch(3f)
        zoom.drag(dx = -100f)
        assertEquals(-100f, zoom.offset.x, 0.0001f)

        zoom.reset()

        assertEquals(1f, zoom.scale, 0.0001f)
        assertCentred(zoom.offset)
        assertFalse(zoom.isZoomedIn)
    }

    @Test
    fun `a shrinking card pulls a zoomed picture back into view`() {
        val zoom = landscapeCard()
        zoom.pinch(2f)
        zoom.drag(dx = -500f)
        assertEquals(-500f, zoom.offset.x, 0.0001f)

        // Rotating, near enough: the card gets wider, so less of the picture is off screen.
        zoom.onViewportChanged(Size(2000f, 1000f))

        // Fitted in a 2000x1000 card the 2:1 picture is 2000x1000, and 2x makes it 4000 wide
        // against 2000: 1000 each side, so the old 500 is still reachable and is left alone.
        assertEquals(-500f, zoom.offset.x, 0.0001f)

        // A card narrow enough to leave no overhang at all takes the pan back entirely.
        zoom.onViewportChanged(Size(4000f, 1000f))
        assertEquals(0f, zoom.offset.x, 0.0001f)
    }

    @Test
    fun `being zoomed in is what a page turn is measured against`() {
        val zoom = landscapeCard()
        assertFalse(zoom.isZoomedIn)

        zoom.pinch(1.5f)

        assertTrue(zoom.isZoomedIn)
    }

    /** The ceiling [MediaZoomState] clamps to; named here so a test can say why 6x became 5x. */
    private val MaxScaleCeiling = 5f
}
