package com.marsel.wledplayer.ui

import com.marsel.wledplayer.ui.overlay.SeekScrub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekScrubTest {
    @Test
    fun `короткое нажатие - 5 секунд`() {
        assertEquals(5_000L, SeekScrub.step(0))
        assertEquals(5_000L, SeekScrub.step(9))
    }

    @Test
    fun `при удержании шаг растёт`() {
        assertEquals(10_000L, SeekScrub.step(10))
        assertEquals(30_000L, SeekScrub.step(25))
        assertEquals(60_000L, SeekScrub.step(60))
        assertEquals(60_000L, SeekScrub.step(500))
    }

    @Test
    fun `шаг не уменьшается, пока кнопку держат`() {
        var prev = 0L
        for (r in 0..200) {
            val s = SeekScrub.step(r)
            assertTrue(s >= prev)
            prev = s
        }
    }

    @Test
    fun `нажатия копятся вперёд и назад`() {
        val dur = 1_440_000L
        var pos = 100_000L
        pos = SeekScrub.next(pos, 1, 0, dur)
        pos = SeekScrub.next(pos, 1, 0, dur)
        assertEquals(110_000L, pos)
        pos = SeekScrub.next(pos, -1, 0, dur)
        assertEquals(105_000L, pos)
        // при удержании назад шаг тоже растёт
        assertEquals(75_000L, SeekScrub.next(105_000, -1, 25, dur))
    }

    @Test
    fun `не выходит за начало и конец видео`() {
        val dur = 1_440_000L
        assertEquals(0L, SeekScrub.next(3_000, -1, 0, dur))
        assertEquals(0L, SeekScrub.next(0, -1, 100, dur))
        assertEquals(dur, SeekScrub.next(dur - 2_000, 1, 0, dur))
        assertEquals(dur, SeekScrub.next(dur, 1, 100, dur))
        assertEquals(0L, SeekScrub.next(0, 1, 0, 0))
    }
}
