package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipDeciderTest {
    private val duration = 1_440_000L // 24 минуты
    private val intro = SkipSegment(SkipKind.INTRO, 60_000, 150_000, 3, "T")
    private val credits = SkipSegment(SkipKind.CREDITS, 1_290_000, null, 1, "T")

    /** Мини-проигрыватель: время идёт по 100 мс, решатель вызывается на каждом шаге, прыжки применяются сразу. */
    private inner class Sim(
        timeline: SkipTimeline?,
        startPos: Long,
        var hasNext: Boolean = true,
        var allowRecap: Boolean = true,
        var allowHold: Boolean = true
    ) {
        val decider = SkipDecider()
        var now = 0L
        var pos = startPos
        var playing = true
        val fires = ArrayList<SkipFire>()
        var offer: SkipOffer? = null

        init {
            update() // новая серия: решатель сбрасывается
            decider.setTimeline("ep", timeline)
        }

        fun snap() = SkipSnapshot("ep", pos, duration, playing, true, hasNext, now, allowRecap, allowHold)

        private fun update() {
            val step = decider.update(snap())
            offer = step.offer
            step.fire?.let { fires.add(it); apply(it) }
        }

        private fun apply(f: SkipFire) {
            when (val a = f.action) {
                is SkipAction.SeekTo -> pos = a.ms
                is SkipAction.NextEpisode -> playing = false
            }
        }

        fun run(ms: Long) {
            var left = ms
            while (left > 0) {
                now += 100
                if (playing) pos += 100
                update()
                left -= 100
            }
        }

        fun seek(to: Long) { pos = to }
    }

    private fun tl(vararg s: SkipSegment, post: Long? = null) = SkipTimeline(s.toList(), post)

    @Test
    fun `без данных ничего не показываем`() {
        val d = SkipDecider()
        assertNull(d.update(SkipSnapshot("ep", 100_000, duration, true, true, true, 10_000)).offer)
    }

    @Test
    fun `заставка пропускается сама через три секунды`() {
        val sim = Sim(tl(intro), 50_000)
        sim.run(20_000)
        assertEquals(1, sim.fires.size)
        assertEquals(SkipAction.SeekTo(150_000), sim.fires[0].action)
        assertEquals(60_000L, sim.fires[0].returnToMs)
    }

    @Test
    fun `в первые секунды после смены серии ничего не показываем`() {
        val sim = Sim(tl(intro), 70_000)
        sim.run(2_000)
        assertNull(sim.offer)
        assertTrue(sim.fires.isEmpty())
    }

    @Test
    fun `отсчёт идёт с трёх секунд`() {
        val sim = Sim(tl(intro), 70_000)
        sim.run(2_600)
        val o = sim.offer
        assertNotNull(o)
        assertEquals(SkipKind.INTRO, o!!.kind)
        assertFalse(o.manual)
        assertEquals(3, o.secondsLeft)
    }

    @Test
    fun `человек сам перемотал в заставку - автоскипа нет, кнопка без отсчёта через секунду`() {
        val sim = Sim(tl(intro), 0)
        sim.run(5_000)
        sim.seek(90_000)
        sim.run(3_000)
        assertTrue(sim.fires.isEmpty())
        assertTrue(sim.offer!!.manual)
        val fire = sim.decider.skipNow(sim.snap())
        assertEquals(SkipAction.SeekTo(150_000), fire!!.action)
    }

    @Test
    fun `сразу после перемотки кнопки нет, через секунду появляется`() {
        val sim = Sim(tl(intro), 0)
        sim.run(5_000)
        sim.seek(90_000)
        sim.run(500)
        assertNull(sim.offer)
        sim.run(1_500)
        assertNotNull(sim.offer)
    }

    @Test
    fun `пауза во время отсчёта - отсчёт стоит, кнопка остаётся`() {
        val sim = Sim(tl(intro), 58_000)
        sim.run(4_000)
        sim.playing = false
        sim.run(10_000)
        assertTrue(sim.fires.isEmpty())
        assertNotNull(sim.offer)
    }

    @Test
    fun `отмена убирает автоскип, но кнопка остаётся`() {
        val sim = Sim(tl(intro), 58_000)
        sim.run(4_000)
        sim.decider.cancel()
        sim.run(3_000)
        assertTrue(sim.fires.isEmpty())
        assertTrue(sim.offer!!.manual)
    }

    @Test
    fun `не держать кнопку - после перемотки кнопки нет`() {
        val sim = Sim(tl(intro), 0, allowHold = false)
        sim.run(5_000)
        sim.seek(90_000)
        sim.run(3_000)
        assertNull(sim.offer)
    }

    @Test
    fun `титры без конца - следующая серия`() {
        val sim = Sim(tl(credits), 1_290_000)
        sim.run(20_000)
        assertEquals(1, sim.fires.size)
        assertEquals(SkipAction.NextEpisode, sim.fires[0].action)
        assertNull(sim.fires[0].returnToMs)
    }

    @Test
    fun `на последней серии титры не трогаем`() {
        val sim = Sim(tl(credits), 1_290_000, hasNext = false)
        sim.run(20_000)
        assertTrue(sim.fires.isEmpty())
    }

    @Test
    fun `сцена после титров не перепрыгивается`() {
        val withEnd = SkipSegment(SkipKind.CREDITS, 1_300_000, 1_440_000, 1, "T")
        val sim = Sim(tl(withEnd, post = 1_400_000), 1_295_000)
        sim.run(15_000)
        assertEquals(SkipAction.SeekTo(1_400_000), sim.fires[0].action)
    }

    @Test
    fun `пересказ выключен - не пропускаем`() {
        val recap = SkipSegment(SkipKind.RECAP, 0, 30_000, 1, "T")
        val sim = Sim(tl(recap), 0, allowRecap = false)
        sim.run(15_000)
        assertTrue(sim.fires.isEmpty())
    }

    @Test
    fun `пересказ включён - пропускаем`() {
        val recap = SkipSegment(SkipKind.RECAP, 0, 30_000, 1, "T")
        val sim = Sim(tl(recap), 0)
        sim.run(15_000)
        assertEquals(SkipAction.SeekTo(30_000), sim.fires[0].action)
    }

    @Test
    fun `кривые данные отбрасываются`() {
        val long = SkipSegment(SkipKind.INTRO, 0, 400_000, 5, "T")
        assertTrue(SkipRules.isUsable(intro, duration))
        assertFalse(SkipRules.isUsable(long, duration))
        val reversed = SkipSegment(SkipKind.INTRO, 100_000, 90_000, 5, "T")
        assertFalse(SkipRules.isUsable(reversed, duration))
        val lateIntro = SkipSegment(SkipKind.INTRO, 1_000_000, 1_090_000, 5, "T")
        assertFalse(SkipRules.isUsable(lateIntro, duration))
        val earlyCredits = SkipSegment(SkipKind.CREDITS, 100_000, 190_000, 5, "T")
        assertFalse(SkipRules.isUsable(earlyCredits, duration))
    }

    @Test
    fun `чужая серия не получает чужие данные`() {
        val d = SkipDecider()
        d.update(SkipSnapshot("e1", 70_000, duration, true, true, true, 0))
        d.setTimeline("e2", tl(intro))
        assertNull(d.update(SkipSnapshot("e1", 70_000, duration, true, true, true, 10_000)).offer)
    }
}
