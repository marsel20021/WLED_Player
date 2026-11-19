package com.marsel.wledplayer.ui

import com.marsel.wledplayer.ui.overlay.CountdownClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownClockTest {
    @Test
    fun `между значениями движка полоска едет сама`() {
        val c = CountdownClock(3_000)
        c.update(0.1f, true, 1_000)
        assertEquals(0.1f, c.value(1_000), 0.001f)
        assertEquals(0.2f, c.value(1_300), 0.001f)
        assertEquals(0.6f, c.value(2_500), 0.001f)
    }

    @Test
    fun `небольшое расхождение не дёргает полоску`() {
        val c = CountdownClock(3_000)
        c.update(0.10f, true, 1_000)
        // пришло значение чуть впереди предсказания (0.183 против 0.20 по часам): остаёмся на часах
        c.update(0.183f, true, 1_250)
        assertEquals(0.1f + 0.25f / 3f, c.value(1_250), 0.001f)
    }

    @Test
    fun `большое расхождение подстраивает часы`() {
        val c = CountdownClock(3_000)
        c.update(0.7f, true, 1_000)
        c.update(0.05f, true, 1_250) // отсчёт начался заново
        assertEquals(0.05f, c.value(1_250), 0.001f)
    }

    @Test
    fun `отсчёт отменён - полоска сразу сбрасывается`() {
        val c = CountdownClock(3_000)
        c.update(0.4f, true, 1_000)
        c.update(0f, true, 1_250)
        assertFalse(c.running)
        assertEquals(0f, c.value(5_000), 0f)
    }

    @Test
    fun `дальше конца не уезжает, у ручной кнопки стоит`() {
        val c = CountdownClock(3_000)
        c.update(0.9f, true, 0)
        assertEquals(1f, c.value(10_000), 0f)
        c.update(0.5f, false, 11_000)
        assertFalse(c.running)
        assertTrue(c.value(20_000) == 0.5f)
    }
}
