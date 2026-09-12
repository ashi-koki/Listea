package me.ashikoki.listea

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import me.ashikoki.listea.ui.theme.ListeaMediaChromeTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The video's transport, in one strip along the bottom: play, where it has got to, seek, speed
 * and mute.
 *
 * Listea's own rather than Media3's `PlayerControlView`, which is the whole reason this file
 * exists. That view brings a full-bleed 60% scrim of its own and lays its transport out down the
 * middle of whatever box it is handed, so putting it over a full-screen video meant blacking the
 * video out in order to show three buttons — the picture the user came to look at was the one
 * thing the controls hid. It also collapses to a cut-down "minimal mode" as soon as it is laid
 * out shorter than 192dp, which is roughly what a landscape phone has left once two bars have
 * taken their share, so the settings button came and went with the orientation.
 *
 * A row of Compose controls has neither problem. It is only as tall as it draws, it dims only the
 * strip it actually occupies, and it sits at the bottom edge where a player's controls belong —
 * so the video stays visible while you use them, which is the whole point of controls for a video.
 *
 * Composed only while the chrome is up, and deliberately inside the swipe card rather than beside
 * it: Compose offers a touch to the deepest thing under it first and every control here consumes
 * what it is given, so dragging the scrubber is abandoned by the paging detector instead of
 * turning the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoControlBar(
    player: Player,
    muted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Polled rather than listened to, because position is the one thing a player does not
    // announce. The loop is scoped to the composition, and the bar composes only while the chrome
    // is up, so it runs exactly as long as there is something on screen reading it.
    var progress by remember(player) { mutableStateOf(player.readProgress()) }
    LaunchedEffect(player) {
        while (true) {
            progress = player.readProgress()
            delay(ProgressTickMs)
        }
    }

    // Where the thumb is being dragged to, while it is being dragged. Without it the next poll
    // would haul the thumb back out from under the finger every tick, because the player has not
    // been told where it is going until the drag ends.
    var scrubbingTo by remember(player) { mutableStateOf<Float?>(null) }

    // Read once from the player and then owned here: speed lives on the player, survives a change
    // of media item, and so stays put across a swipe the way a setting should.
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }

    val fraction = scrubbingTo ?: progress.fraction
    val shownPosition = scrubbingTo?.let { positionOf(it, progress.durationMs) } ?: progress.positionMs

    ListeaMediaChromeTheme {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MediaChromeScrim)
                // Swallow taps that land on the bar itself rather than on one of its controls.
                // A tap on the media is what puts the chrome away, and aiming at the pause button
                // and missing it by a few pixels should not be read as asking for that.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = ListeaDimens.RowGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    player.togglePlayPause()
                    // Reading straight back rather than waiting up to a tick for the poll: the
                    // one control whose own effect the user is watching for should not lag it.
                    progress = player.readProgress()
                }
            ) {
                Icon(
                    if (progress.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (progress.playing) "Pause" else "Play"
                )
            }

            Text(
                videoTimeLabel(shownPosition, progress.durationMs),
                style = MaterialTheme.typography.labelMedium
            )

            Slider(
                value = fraction,
                onValueChange = { scrubbingTo = it },
                onValueChangeFinished = {
                    scrubbingTo?.let { player.seekTo(positionOf(it, progress.durationMs)) }
                    scrubbingTo = null
                    progress = player.readProgress()
                },
                // A stream still being measured has no fraction to be at, and a thumb that can be
                // dragged somewhere meaningless is worse than one that cannot be dragged.
                enabled = progress.durationMs > 0L,
                thumb = { SeekThumb() },
                track = { SeekTrack(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = ListeaDimens.RowGap)
            )

            SpeedControl(
                speed = speed,
                onSpeed = {
                    speed = it
                    player.playbackParameters = PlaybackParameters(it)
                }
            )

            IconButton(onClick = onToggleMute) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (muted) "Unmute" else "Mute"
                )
            }
        }
    }
}

/**
 * The scrubber's handle: a dot, not Material's standing bar.
 *
 * The stock thumb is a 4×44dp upright, which over a video reads as a cut through the picture and
 * is taller than the strip it sits in has any business being. A dot says the same thing — here is
 * where you are, drag it — in a shape that does not divide what is behind it.
 *
 * Sized well under the slider's own touch target, which is what is actually being grabbed. The
 * dot is only the part you can see.
 */
@Composable
private fun SeekThumb() {
    Box(
        Modifier
            .size(SeekThumbSize)
            .background(LocalContentColor.current, CircleShape)
    )
}

/**
 * The scrubber's line: how far through, at the weight of a hairline.
 *
 * Drawn rather than assembled out of the stock track, which brings a gap either side of the thumb
 * and a stop indicator at the end — detail that earns its place on a settings page and clutters a
 * strip laid over a moving picture. Two rounded lines, the played part solid and the rest dimmed
 * to the same white, so the control reads as one object at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekTrack(state: SliderState) {
    // The slider is left at its default range, so the value is already the fraction.
    val fraction = state.value.coerceIn(0f, 1f)
    val color = LocalContentColor.current

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(SeekTrackThickness)
    ) {
        val middle = size.height / 2f
        val start = Offset(middle, middle)
        val end = Offset(size.width - middle, middle)

        drawLine(
            color = color.copy(alpha = SeekTrackRestAlpha),
            start = start,
            end = end,
            strokeWidth = size.height,
            cap = StrokeCap.Round
        )
        if (fraction > 0f) {
            drawLine(
                color = color,
                start = start,
                end = Offset(start.x + (end.x - start.x) * fraction, middle),
                strokeWidth = size.height,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Playback speed as a menu behind its own label, rather than a button that cycles.
 *
 * Cycling is smaller but makes 2× four taps away from 0.75× and gives no way to see what is on
 * offer without visiting all of it. The label doubles as the readout, so the row spends no width
 * saying "speed" next to it.
 */
@Composable
private fun SpeedControl(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = ListeaDimens.RowGap)
        ) {
            Text(speedLabel(speed), style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VideoSpeeds.forEach { option ->
                DropdownMenuItem(
                    text = { Text(speedLabel(option)) },
                    onClick = {
                        onSpeed(option)
                        open = false
                    },
                    trailingIcon = {
                        if (option == speed) Icon(Icons.Filled.Check, contentDescription = null)
                    }
                )
            }
        }
    }
}

/** What the transport knows about the player, sampled. */
@Immutable
data class VideoProgress(
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean
) {
    /**
     * How far through, as the scrubber wants it. Zero for anything with no duration yet: a
     * fraction of an unknown length is not a small number, it is not a number.
     */
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The player as the transport sees it right now.
 *
 * [VideoProgress.playing] follows `playWhenReady` rather than `isPlaying`, so the button answers
 * the tap that caused it instead of waiting for the buffer. A player that has reached the end is
 * showing a play button, because that is what its next tap will do.
 */
private fun Player.readProgress(): VideoProgress = VideoProgress(
    positionMs = currentPosition.coerceAtLeast(0L),
    durationMs = duration.let { if (it == C.TIME_UNSET || it < 0L) 0L else it },
    playing = playWhenReady &&
        playbackState != Player.STATE_IDLE &&
        playbackState != Player.STATE_ENDED
)

/** Play, pause, or start a finished video again — whichever the button is currently offering. */
private fun Player.togglePlayPause() {
    when {
        playbackState == Player.STATE_ENDED -> {
            seekTo(0L)
            play()
        }

        playWhenReady -> pause()
        else -> play()
    }
}

/** Where a scrubber fraction lands, in milliseconds. */
fun positionOf(fraction: Float, durationMs: Long): Long =
    if (durationMs <= 0L) 0L else (fraction.coerceIn(0f, 1f) * durationMs).toLong()

/**
 * `mm:ss`, growing to `h:mm:ss` only once there is an hour to show.
 *
 * Fixed-width minutes and seconds so the label does not change width as it counts, which would
 * shove the scrubber sideways every ten seconds. [Locale.ROOT] because these are digits in a
 * timestamp, not a number in prose.
 */
fun formatVideoTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val seconds = total % 60L
    val minutes = total / 60L % 60L
    val hours = total / 3600L

    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Position and duration together, or the position alone while the duration is still unknown.
 *
 * A live stream and a file still being measured both report no duration, and "00:07 / 00:00" says
 * something false about both of them.
 */
fun videoTimeLabel(positionMs: Long, durationMs: Long): String =
    if (durationMs <= 0L) {
        formatVideoTime(positionMs)
    } else {
        "${formatVideoTime(positionMs)} / ${formatVideoTime(durationMs)}"
    }

/**
 * A speed as the menu writes it: `1×`, `1.5×`, `0.75×`.
 *
 * Trailing zeroes are dropped rather than padded to a fixed shape, because normal speed is the
 * one people read most and `1×` is what it is called.
 */
fun speedLabel(speed: Float): String {
    val hundredths = (speed * 100f).roundToInt()
    val whole = hundredths / 100
    val remainder = hundredths % 100

    val number = when {
        remainder == 0 -> whole.toString()
        remainder % 10 == 0 -> "$whole.${remainder / 10}"
        else -> String.format(Locale.ROOT, "%d.%02d", whole, remainder)
    }
    return "$number×"
}

/** Visible weight of the scrubber. A hairline: it reports, it is not the subject. */
private val SeekTrackThickness = 3.dp

/** How far the untravelled part of the line is faded, rather than being a second colour. */
private const val SeekTrackRestAlpha = 0.3f

/** The dot, comfortably inside the slider's own 48dp touch target. */
private val SeekThumbSize = 12.dp

/** What the speed menu offers. Media3 will take any positive float; these are the useful ones. */
val VideoSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * How often the position is re-read while the controls are up. Fast enough that the scrubber
 * moves rather than steps, slow enough to be nothing on a frame budget.
 */
private const val ProgressTickMs = 250L
