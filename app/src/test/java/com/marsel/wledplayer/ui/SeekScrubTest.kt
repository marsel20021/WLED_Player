package com.marsel.wledplayer.ui

import com.marsel.wledplayer.ui.overlay.SeekScrub
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekScrubTest {
    @Test
    fun `шаг - двадцатая часть длительности`() {
        assertEquals(72_000L, SeekScrub.step(1_440_000))
        assertEquals(180_000L, SeekScrub.step(3_600_000))
    }

    @Test
    fun `у коротких видео шаг не меньше секунды`() {
        assertEquals(1_000L, SeekScrub.step(10_000))
    }

    @Test
    fun `нажатия копятся и не выходят за видео`() {
        val dur = 1_440_000L
        var pos = 100_000L
        pos = SeekScrub.next(pos, 1, dur)
        pos = SeekScrub.next(pos, 1, dur)
        assertEquals(244_000L, pos)
        pos = SeekScrub.next(pos, -1, dur)
        assertEquals(172_000L, pos)
        assertEquals(0L, SeekScrub.next(30_000, -1, dur))
        assertEquals(dur, SeekScrub.next(dur - 10_000, 1, dur))
    }
}
