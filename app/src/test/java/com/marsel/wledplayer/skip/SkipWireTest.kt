package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipWireTest {
    @Test
    fun `пустое состояние - все поля null`() {
        val o = SkipWire.toJson(SkipUiState())
        assertEquals("skipState", o.getString("type"))
        assertTrue(o.isNull("offer"))
        assertTrue(o.isNull("undo"))
        assertTrue(o.isNull("seasonChoice"))
    }

    @Test
    fun `отсчёт, вернуть и выбор сезона уходят полностью`() {
        val s = SkipUiState(
            offer = SkipOffer(SkipKind.INTRO, 0.456f, 2, manual = false),
            seasonChoice = SeasonChoice(listOf(1, 2, 3), null),
            undo = SkipUndo(SkipKind.CREDITS)
        )
        val o = SkipWire.toJson(s)
        val offer = o.getJSONObject("offer")
        assertEquals("INTRO", offer.getString("kind"))
        assertEquals(0.46, offer.getDouble("progress"), 0.0001)
        assertEquals(2, offer.getInt("secondsLeft"))
        assertEquals(false, offer.getBoolean("manual"))
        assertEquals("CREDITS", o.getJSONObject("undo").getString("kind"))
        val ch = o.getJSONObject("seasonChoice")
        assertEquals(3, ch.getJSONArray("seasons").length())
        assertTrue(ch.isNull("selected"))
    }
}
