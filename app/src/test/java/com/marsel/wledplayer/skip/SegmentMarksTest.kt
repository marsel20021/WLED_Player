package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMarksTest {
    private val duration = 1_000_000L

    @Test
    fun `заставка и титры без конца превращаются в доли`() {
        val tl = SkipTimeline(
            listOf(
                SkipSegment(SkipKind.INTRO, 100_000, 200_000, 3, "T"),
                SkipSegment(SkipKind.CREDITS, 900_000, null, 3, "T")
            )
        )
        val m = SegmentMarks.of(tl, duration)
        assertEquals(2, m.size)
        assertEquals(0.1f, m[0].startFraction, 0.001f)
        assertEquals(0.2f, m[0].endFraction, 0.001f)
        assertEquals(0.9f, m[1].startFraction, 0.001f)
        assertEquals(1.0f, m[1].endFraction, 0.001f)
    }

    @Test
    fun `нет данных или длительности - участков нет`() {
        assertTrue(SegmentMarks.of(null, duration).isEmpty())
        assertTrue(SegmentMarks.of(SkipTimeline(emptyList()), duration).isEmpty())
        val tl = SkipTimeline(listOf(SkipSegment(SkipKind.INTRO, 100_000, 200_000, 3, "T")))
        assertTrue(SegmentMarks.of(tl, 0).isEmpty())
    }

    @Test
    fun `кривой участок отбрасывается`() {
        val tl = SkipTimeline(listOf(SkipSegment(SkipKind.INTRO, 100_000, 900_000, 3, "T")))
        assertTrue(SegmentMarks.of(tl, duration).isEmpty())
    }
}
