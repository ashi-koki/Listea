package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the video transport puts on screen, and where a drag on its scrubber lands.
 *
 * All of it is arithmetic on a position and a duration, which is exactly the part worth pinning
 * down: a player that reports no duration yet is the normal state of every video for its first
 * moments, and the readouts have to survive it without inventing a number.
 */
class VideoControlsTest {

    @Test
    fun `a time is minutes and seconds, both at a fixed width`() {
        // Fixed width is not cosmetic: the label sits to the left of the scrubber, so a label
        // that grows a character at ten seconds would shove the scrubber sideways as it played.
        assertEquals("00:00", formatVideoTime(0L))
        assertEquals("00:01", formatVideoTime(1_000L))
        assertEquals("00:09", formatVideoTime(9_400L))
        assertEquals("02:19", formatVideoTime(139_000L))
        assertEquals("59:59", formatVideoTime(3_599_000L))
    }

    @Test
    fun `an hour is only spelled out once there is one`() {
        assertEquals("1:00:00", formatVideoTime(3_600_000L))
        assertEquals("1:01:01", formatVideoTime(3_661_000L))
        assertEquals("10:00:00", formatVideoTime(36_000_000L))
    }

    @Test
    fun `part of a second is not rounded up into one`() {
        // The position is what has been played, and 999ms of a second is not that second.
        assertEquals("00:00", formatVideoTime(999L))
        assertEquals("00:01", formatVideoTime(1_999L))
    }

    @Test
    fun `a position before the start reads as the start`() {
        // Media3 reports an unset position as a negative number rather than as zero.
        assertEquals("00:00", formatVideoTime(-1L))
        assertEquals("00:00", formatVideoTime(Long.MIN_VALUE))
    }

    @Test
    fun `an unknown duration is left out of the label rather than shown as zero`() {
        // A stream still being measured reports nothing, and "00:07 / 00:00" says something
        // false about it. Showing where it has got to is still true.
        assertEquals("00:07", videoTimeLabel(positionMs = 7_000L, durationMs = 0L))
        assertEquals("00:07", videoTimeLabel(positionMs = 7_000L, durationMs = -1L))
    }

    @Test
    fun `a known duration is shown beside the position`() {
        assertEquals("00:01 / 02:19", videoTimeLabel(positionMs = 1_000L, durationMs = 139_000L))
    }

    @Test
    fun `progress with no duration is not part-way through anything`() {
        assertEquals(0f, VideoProgress(positionMs = 7_000L, durationMs = 0L, playing = true).fraction, 0f)
    }

    @Test
    fun `progress is the position over the duration`() {
        val half = VideoProgress(positionMs = 50_000L, durationMs = 100_000L, playing = true)

        assertEquals(0.5f, half.fraction, 0.0001f)
    }

    @Test
    fun `a position past the end still leaves the thumb on the track`() {
        // Media3 can report a position a little beyond the duration as a stream finishes, and a
        // slider handed anything above its range throws rather than clamping.
        val over = VideoProgress(positionMs = 101_000L, durationMs = 100_000L, playing = false)

        assertEquals(1f, over.fraction, 0f)
    }

    @Test
    fun `a scrub lands where the fraction says`() {
        assertEquals(0L, positionOf(0f, 100_000L))
        assertEquals(25_000L, positionOf(0.25f, 100_000L))
        assertEquals(100_000L, positionOf(1f, 100_000L))
    }

    @Test
    fun `a scrub of something with no duration goes to the start rather than nowhere`() {
        assertEquals(0L, positionOf(0.5f, 0L))
    }

    @Test
    fun `a fraction out of range is brought back into it rather than passed to the player`() {
        assertEquals(100_000L, positionOf(1.5f, 100_000L))
        assertEquals(0L, positionOf(-0.5f, 100_000L))
    }

    @Test
    fun `scrubbing to where the video already is changes nothing`() {
        val progress = VideoProgress(positionMs = 30_000L, durationMs = 120_000L, playing = true)

        assertEquals(30_000L, positionOf(progress.fraction, progress.durationMs))
    }

    @Test
    fun `normal speed is called one, not one point zero`() {
        // It is the speed people read most, and every player calls it 1x.
        assertEquals("1×", speedLabel(1f))
        assertEquals("2×", speedLabel(2f))
    }

    @Test
    fun `a fractional speed keeps only the figures it needs`() {
        assertEquals("0.5×", speedLabel(0.5f))
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
    }

    @Test
    fun `every speed on offer has a label and normal speed is among them`() {
        // The menu ticks the entry matching the player's speed, so a player left at 1x with no
        // 1x on the menu would show a tick against nothing.
        assertTrue(VideoSpeeds.contains(1f))
        assertEquals(VideoSpeeds.size, VideoSpeeds.map { speedLabel(it) }.distinct().size)
        assertFalse(VideoSpeeds.any { it <= 0f })
    }
}
