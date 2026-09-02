package me.ashikoki.listea

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.max

/** How far a pinch can go. Past this a photograph is mostly the decoder's guesswork. */
private const val MaxScale = 5f

/** Scale is the product of a long run of gesture deltas; compare it with slack, never with ==. */
private const val ScaleTolerance = 0.001f

/**
 * The pinch-zoom and pan of one media card: how far in it is, and which part of it is on screen.
 *
 * Owned by [SwipeCard], because zooming and paging are the same gesture until the moment they are
 * not. A drag is a pan while there is still image to pan onto and a page turn once there is not,
 * and only something holding both pieces of state can tell those apart — which is why this is not
 * a self-contained zoom modifier that a screen could drop on top of a swipe detector. The card
 * feeds gestures in through [onGesture] and acts on what comes back.
 *
 * The transform itself is applied by [mediaZoom], deliberately at the renderer rather than around
 * the whole card: a video's transport controls sit in the same box as its picture and must not be
 * dragged off screen with it.
 */
@Stable
class MediaZoomState {
    /**
     * Written by the gesture loop and read in the draw phase, so a pinch redraws without
     * recomposing anything.
     */
    var scale by mutableFloatStateOf(1f)
        internal set

    /** How far the media is displaced from centre, in pixels, after scaling. */
    var offset by mutableStateOf(Offset.Zero)
        internal set

    /**
     * The card's own size. Not snapshot state: nothing draws from it, and the gesture loop reads
     * it fresh on every pointer event anyway.
     */
    private var viewportSize: Size = Size.Zero

    /**
     * Width over height of what is actually being drawn, or null while nothing is known about it.
     *
     * Reported by the renderer, because it is the only thing that knows. It matters more than it
     * looks: a 16:9 photograph on a tall phone is letterboxed, so at 2x it is still shorter than
     * the screen and there is nothing to pan to vertically. Sizing the pan limits from the card
     * instead of from the picture would let the user drag the picture off into the black bars.
     */
    var contentAspect: Float? = null

    /** Whether the media is showing at anything other than its fitted size. */
    val isZoomedIn: Boolean get() = scale > 1f + ScaleTolerance

    /** Back to fit, centred. What every card change and every completed page turn lands on. */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /**
     * Records the card's size and pulls the media back inside it.
     *
     * The re-clamp is what makes rotating while zoomed in behave: the picture keeps its
     * magnification, but a part of it that has just gone out of reach is dragged back into view
     * rather than left stranded off screen until the next pan.
     */
    fun onViewportChanged(size: Size) {
        viewportSize = size
        offset = clamp(offset)
    }

    /**
     * Folds one step of a pinch or drag in, and hands back the horizontal movement it could not
     * use.
     *
     * That leftover is the whole arbitration between zooming and paging, and it needs no mode
     * flag to work. At fitted size there is no pan range at all, so every pixel of a horizontal
     * drag comes straight back out and the card pages exactly as it always did. Zoomed in, the
     * drag is absorbed as panning until the picture's edge reaches the screen's, and only then
     * does it start coming back — which is precisely "swipe left at the right-hand edge to reach
     * the next item".
     *
     * Vertical leftover is dropped on the floor rather than returned. There is nowhere above or
     * below to page to, so hitting the top or bottom of a zoomed picture simply stops.
     */
    fun onGesture(centroid: Offset, pan: Offset, zoomChange: Float): Float {
        val previous = scale
        val next = (previous * zoomChange).coerceIn(1f, MaxScale)

        // Zoom about the point between the fingers, so the bit of the picture under them stays
        // under them. Scaling about the centre instead would slide whatever the user is looking
        // at out from beneath the pinch.
        val fromCentre = centroid - Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val anchored = if (next == previous) {
            offset
        } else {
            fromCentre - (fromCentre - offset) * (next / previous)
        }

        val wanted = anchored + pan
        scale = next
        offset = clamp(wanted)
        return wanted.x - offset.x
    }

    /** [offset] pulled inside whatever pan range the current scale actually leaves. */
    private fun clamp(value: Offset): Offset {
        val limit = panLimit()
        return Offset(
            value.x.coerceIn(-limit.x, limit.x),
            value.y.coerceIn(-limit.y, limit.y)
        )
    }

    /**
     * Half the amount by which the scaled media overhangs the card, per axis, and zero when it
     * does not overhang at all — an axis with nothing hidden has nothing to pan to.
     */
    private fun panLimit(): Offset {
        val content = fittedContentSize()
        return Offset(
            max(0f, (content.width * scale - viewportSize.width) / 2f),
            max(0f, (content.height * scale - viewportSize.height) / 2f)
        )
    }

    /**
     * What the media occupies at fitted size: the card, letterboxed down to [contentAspect].
     *
     * An unknown aspect falls back to the whole card, which is the right answer for the things
     * that report none — the placeholders, which really do fill it.
     */
    private fun fittedContentSize(): Size {
        val viewport = viewportSize
        if (viewport.width <= 0f || viewport.height <= 0f) return Size.Zero
        val aspect = contentAspect?.takeIf { it > 0f && it.isFinite() } ?: return viewport
        return if (viewport.width / viewport.height > aspect) {
            Size(viewport.height * aspect, viewport.height)
        } else {
            Size(viewport.width, viewport.width / aspect)
        }
    }
}

/**
 * Draws the media at whatever [state] currently says.
 *
 * A draw-phase transform: the lambda form of [graphicsLayer] reads the scale and the offset when
 * the frame is drawn rather than when the tree is composed, so a pinch never recomposes the
 * renderer underneath it. A null [state] leaves the modifier alone, for anything shown outside a
 * swipeable card.
 *
 * Nothing is clipped here. The scaled media is meant to overflow its own bounds; the card clips
 * it, which is what keeps a zoomed picture inside the page it belongs to.
 */
fun Modifier.mediaZoom(state: MediaZoomState?): Modifier =
    if (state == null) this else graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

/** A drawn size as a width-over-height ratio, or null when there is nothing usable to report. */
fun Size.aspectRatioOrNull(): Float? {
    if (isUnspecified) return null
    if (width <= 0f || height <= 0f || !width.isFinite() || !height.isFinite()) return null
    return width / height
}
