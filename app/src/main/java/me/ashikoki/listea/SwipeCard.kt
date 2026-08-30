package me.ashikoki.listea

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** How far the card must travel before a swipe counts as navigation. */
private val SWIPE_THRESHOLD = 96.dp

/** How far a blocked swipe (one with nowhere to go) is allowed to rubber band. */
private const val RESIST_LIMIT = 80f

/**
 * A horizontally swipeable card: drag left to go forward, right to go back.
 *
 * The one swipe implementation in the app, so Review and the folder file viewer feel identical.
 * It knows nothing about what it is moving between — a blocked direction rubber bands instead of
 * navigating, and a committed swipe animates the card off-screen before reporting. What forward
 * and back *mean* is entirely the caller's business: Review checks the item off on its way out,
 * the file viewer simply shows the next file.
 *
 * [key] identifies what is currently displayed; changing it resets the card to centre.
 */
@Composable
fun SwipeCard(
    key: Any,
    canSwipeForward: Boolean,
    canSwipeBack: Boolean,
    onSwipeForward: () -> Unit,
    onSwipeBack: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(key) { offsetX.snapTo(0f) }

    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val thresholdPx = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(key, canSwipeForward, canSwipeBack) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, delta ->
                            scope.launch {
                                val next = offsetX.value + delta
                                // A direction with nowhere to go resists instead of navigating.
                                offsetX.snapTo(
                                    when {
                                        !canSwipeBack && next > RESIST_LIMIT -> RESIST_LIMIT
                                        !canSwipeForward && next < -RESIST_LIMIT -> -RESIST_LIMIT
                                        else -> next
                                    }
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, tween(150)) }
                        },
                        onDragEnd = {
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
            content()
        }
    }
}
