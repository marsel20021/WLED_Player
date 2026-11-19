package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNumberTest {

    @Test
    fun `номер серии в разных записях`() {
        assertEquals(EpisodeRef(null, 3), EpisodeParser.parse("3 серия"))
        assertEquals(EpisodeRef(null, 3), EpisodeParser.parse("Серия 3"))
        assertEquals(EpisodeRef(null, 12), EpisodeParser.parse("12-я серия"))
        assertEquals(EpisodeRef(null, 5), EpisodeParser.parse("Episode 5"))
        assertEquals(EpisodeRef(null, 7), EpisodeParser.parse("Эпизод 7. Название"))
    }

    @Test
    fun `сезон и серия вместе`() {
        assertEquals(EpisodeRef(1, 5), EpisodeParser.parse("S01E05"))
        assertEquals(EpisodeRef(2, 10), EpisodeParser.parse("s2 e10"))
        assertEquals(EpisodeRef(1, 3), EpisodeParser.parse("1 сезон 3 серия"))
        assertEquals(EpisodeRef(1, 3), EpisodeParser.parse("Сезон 1 Серия 3"))
        assertEquals(EpisodeRef(2, 7), EpisodeParser.parse("Серия 7, 2 сезон"))
    }

    @Test
    fun `голое число это серия, но только если в строке больше ничего нет`() {
        assertEquals(EpisodeRef(null, 12), EpisodeParser.parse("12"))
        assertEquals(EpisodeRef(null, 4), EpisodeParser.parse(" 4 "))
        assertNull(EpisodeParser.parse("1917"))
        assertNull(EpisodeParser.parse("Дандадан 12"))
    }

    @Test
    fun `без явного номера не угадываем`() {
        assertNull(EpisodeParser.parse(null))
        assertNull(EpisodeParser.parse(""))
        assertNull(EpisodeParser.parse("Пилот"))
        assertNull(EpisodeParser.parse("Ничего не воспроизводится"))
        assertNull(EpisodeParser.parse("Серия"))
        assertNull(EpisodeParser.parse("0 серия"))
    }

    @Test
    fun `сезон из названия сериала`() {
        assertEquals(2, EpisodeParser.findSeason("Дандадан 2 сезон"))
        assertEquals(2, EpisodeParser.findSeason("Дандадан - Сезон 2"))
        assertEquals(3, EpisodeParser.findSeason("Dandadan Season 3"))
        assertEquals(2, EpisodeParser.findSeason("Dandadan 2nd Season"))
        assertEquals(2, EpisodeParser.findSeason("Дандадан 2-й сезон"))
        assertNull(EpisodeParser.findSeason("Дандадан"))
        assertNull(EpisodeParser.findSeason("Дэдпул 2"))
        assertNull(EpisodeParser.findSeason(null))
    }

    @Test
    fun `слова про сезон вырезаются из названия`() {
        assertEquals("Дандадан", EpisodeParser.stripSeason("Дандадан 2 сезон"))
        assertEquals("Дандадан", EpisodeParser.stripSeason("Дандадан - Сезон 2"))
        assertEquals("Dandadan", EpisodeParser.stripSeason("Dandadan Season 3"))
        assertEquals("Игра престолов", EpisodeParser.stripSeason("Игра престолов"))
    }

    @Test
    fun `номер в пределах сезона берётся как есть`() {
        val counts = mapOf(1 to 12, 2 to 12)
        assertEquals(ResolvedEpisode(1, 5, false), SeasonMath.resolve(1, 5, counts))
        assertEquals(ResolvedEpisode(2, 12, false), SeasonMath.resolve(2, 12, counts))
    }

    @Test
    fun `сквозной номер пересчитывается и помечается`() {
        val counts = mapOf(1 to 51, 2 to 51, 3 to 51, 4 to 17)
        assertEquals(ResolvedEpisode(2, 9, true), SeasonMath.resolve(2, 60, counts))
        assertEquals(ResolvedEpisode(4, 1, true), SeasonMath.resolve(4, 154, counts))
    }

    @Test
    fun `если не влезает или числа серий неизвестны, пропуска нет`() {
        val counts = mapOf(1 to 12, 2 to 12)
        assertNull(SeasonMath.resolve(1, 13, counts))
        assertNull(SeasonMath.resolve(2, 40, counts))
        assertNull(SeasonMath.resolve(3, 1, counts))
        assertNull(SeasonMath.resolve(2, 14, mapOf(1 to 0, 2 to 12)))
        assertNull(SeasonMath.resolve(1, 1, mapOf(1 to 0)))
        assertNull(SeasonMath.resolve(0, 1, counts))
    }
}
