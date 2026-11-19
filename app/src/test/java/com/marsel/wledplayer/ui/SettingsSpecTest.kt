package com.marsel.wledplayer.ui

import com.marsel.wledplayer.ui.settings.ChoiceRow
import com.marsel.wledplayer.ui.settings.SettingsSpec
import com.marsel.wledplayer.ui.settings.StepperRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSpecTest {
    private val row = StepperRow("brightness", "Яркость", 100, 0, 100, 5)

    @Test
    fun `число не выходит за границы`() {
        assertEquals(100, SettingsSpec.stepped(row, 98, 1))
        assertEquals(0, SettingsSpec.stepped(row, 3, -1))
        assertEquals(55, SettingsSpec.stepped(row, 50, 1))
        assertEquals(60, SettingsSpec.stepped(row, 50, 1, times = 2))
    }

    @Test
    fun `значение вне границ тянется внутрь`() {
        val leds = StepperRow("leds_top", "Сверху", 15, 0, 200, 1)
        assertEquals(200, SettingsSpec.stepped(leds, 250, 1))
    }

    @Test
    fun `выбор идёт по кругу`() {
        val c = ChoiceRow("x", "x", "a", listOf("a" to "A", "b" to "B", "c" to "C"))
        assertEquals("b", SettingsSpec.nextChoice(c, "a", 1))
        assertEquals("a", SettingsSpec.nextChoice(c, "c", 1))
        assertEquals("c", SettingsSpec.nextChoice(c, "a", -1))
        assertEquals("b", SettingsSpec.nextChoice(c, "неизвестно", 1))
    }

    @Test
    fun `у каждого движка свои строки и ключи не повторяются`() {
        val engineTab = SettingsSpec.tabs.first { it.title == "Движок" }
        assertEquals(1, engineTab.rows("classic").size)
        assertTrue(engineTab.rows("v2").size > 1)
        assertTrue(engineTab.rows("lab").size > engineTab.rows("v2").size)
        for (e in listOf("classic", "v2", "lab")) {
            val keys = SettingsSpec.tabs.flatMap { it.rows(e) }.map { it.key }
            assertEquals("повторы ключей для $e", keys.size, keys.toSet().size)
        }
    }
}
