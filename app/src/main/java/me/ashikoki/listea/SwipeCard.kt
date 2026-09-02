package me.ashikoki.listea

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How far the card must travel before a swipe counts as navigation. */
private val SWIPE_THRESHOLD = 96.dp

/** How far a blocked swipe (one with nowhere to go) is allowed to rubber band. */
private const val RESIST_LIMIT = 80f

/**
 * A horizontally swipeable, pinch-zoomable card: drag left to go forward, right to go back, and
 * two fingers to look closer.
 *
 * The one media gesture implementation in the app, so Review and the folder file viewer feel
 * identical. It knows nothing about what it is moving between — a blocked direction rubber bands
 * instead of navigating, and a committed swipe animates the card off-screen before reporting.
 * What forward and back *mean* is entirely the caller's business: Review checks the item off on
 * its way out, the file viewer simply shows the next file.
 *
 * Zoom and paging are deliberately not two detectors that have to agree on who owns a drag. They
 * are one detector and one number: every drag is offered to [MediaZoomState] as panning first,
 * and only what the picture cannot absorb is left to move the card. At fitted size a picture
 * absorbs nothing, so paging behaves exactly as it did before there was any zoom at all; zoomed
 * in, the card does not begin to move until the picture's edge has reached the screen's. Nothing
 * anywhere has to ask "are we in zoom mode".
 *
 * [key] identifies what is currently displayed; changing it recentres the card *and* drops the
 * zoom, which is what makes the item a swipe lands on arrive at its own fitted size.
 *
 * [onTap] is the other half of the gallery gesture: a tap that is not part of a drag or a pinch.
 * It shares the node with the drag detector rather than being decided by it, so a swipe is never
 * delayed waiting to see whether it was a tap, and a tap is cancelled the moment the drag
 * detector consumes movement.
 *
 * [content] is handed the zoom state so the renderer can apply [mediaZoom] to the picture itself
 * and report what shape it turned out to be.
 */
@Composable
fun SwipeCard(
    key: Any,
    canSwipeForward: Boolean,
    canSwipeBack: Boolean,
    onSwipeForward: () -> Unit,
    onSwipeBack: () -> Unit,
    modifier: Modifier,
    onTap: (() -> Unit)? = null,
    content: @Composable (MediaZoomState) -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val zoom = remember { MediaZoomState() }
    LaunchedEffect(key) {
        offsetX.snapTo(0f)
        zoom.reset()
    }
    // Read through a holder so the detector is installed once: callers pass a fresh lambda every
    // recomposition, and re-keying pointerInput on it would tear the gesture down mid-touch.
    val currentOnTap by rememberUpdatedState(onTap)

    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val thresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    zoom.onViewportChanged(Size(it.width.toFloat(), it.height.toFloat()))
                }
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(Unit) {
                    detectTapGestures { currentOnTap?.invoke() }
                }
                .pointerInput(key, canSwipeForward, canSwipeBack) {
                    detectZoomPanGestures(
                        onGesture = { centroid, pan, zoomChange, pointers ->
                            val leftover = zoom.onGesture(centroid, pan, zoomChange)
                            // A pinch is never a page turn, however far its centroid wanders.
                            // Only a plain one-finger drag is allowed to move the card, and only
                            // with the part of itself the picture had no room for.
                            if (pointers == 1 && leftover != 0f) {
                                scope.launch {
                                    offsetX.snapTo(resisted(offsetX.value + leftover, canSwipeForward, canSwipeBack))
                                }
                            }
                        },
                        onGestureEnd = {
                            scope.launch {
                                when {
                                    canSwipeForward && offsetX.value < -thresholdPx -> {
                                        offsetX.animateTo(-widthPx, tween(180))
                                        onSwipeForward()
                                    }

                                    canSwipeBack && offsetX.value > thresholdPx -> {
                                        offsetX.animateTo(widthPx, tween(180))
                                        onSwipeBack()
                                    }

                                    else -> offsetX.animateTo(0f, tween(180))
                                }
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            content(zoom)
        }
    }
}

/** A direction with nowhere to go resists instead of navigating. */
private fun resisted(value: Float, canSwipeForward: Boolean, canSwipeBack: Boolean): Float = when {
    !canSwipeBack && value > RESIST_LIMIT -> RESIST_LIMIT
    !canSwipeForward && value < -RESIST_LIMIT -> -RESIST_LIMIT
    else -> value
}

/**
 * One pointer loop for pinching, panning and dragging, reporting each step and then the end of
 * the gesture.
 *
 * This is Compose's own `detectTransformGestures` with two things added: the number of fingers
 * down, so a pinch can be told from a drag, and a callback when the touch finishes, so the card
 * can decide whether it travelled far enough to turn the page. Neither is reachable from the
 * stock detector, and running the stock detector *beside* a drag detector does not work — the
 * first one past the touch slop consumes the movement and the other never sees the gesture.
 *
 * A gesture whose events arrive already consumed is abandoned. That is what leaves the video's
 * own transport buttons working: a press that Media3 has taken never becomes a pan.
 */
private suspend fun PointerInputScope.detectZoomPanGestures(
    onGesture: (centroid: Offset, pan: Offset, zoomChange: Float, pointers: Int) -> Unit,
    onGestureEnd: () -> Unit
) {
    awaitEachGesture {
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    accumulatedZoom *= zoomChange
                    accumulatedPan += panChange

                    // Measured against the spread of the fingers, so a pinch on a small gap has
                    // to travel as far as one on a wide gap before it counts.
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - accumulatedZoom) * centroidSize
                    if (zoomMotion > touchSlop || accumulatedPan.getDistance() > touchSlop) {
                        pastTouchSlop = true
                    }
                }

                if (pastTouchSlop) {
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onGesture(
                            event.calculateCentroid(useCurrent = false),
                            panChange,
                            zoomChange,
                            event.changes.count { it.pressed }
                        )
                    }
                    // Claim the movement, so nothing underneath — the tap detector, a scrolling
                    // ancestor — also acts on it.
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })

        onGestureEnd()
    }
}
