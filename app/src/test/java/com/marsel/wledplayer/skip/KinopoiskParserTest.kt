package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KinopoiskParserTest {

    private val search = """
    {"total":5,"items":[
      {"kinopoiskId":1,"imdbId":"tt0944947","nameRu":"Игра престолов","nameEn":null,"nameOriginal":"Game of Thrones","year":2011,"type":"TV_SERIES"},
      {"kinopoiskId":2,"imdbId":null,"nameRu":"Игра престолов: Сезон 2 – Приглашение на съемочную площадку","nameEn":null,"nameOriginal":null,"year":2012,"type":"VIDEO"},
      {"kinopoiskId":3,"imdbId":"tt1","nameRu":"Игра престолов. Последний дозор","nameEn":null,"nameOriginal":null,"year":2019,"type":"FILM"}
    ]}
    """.trimIndent()

    @Test
    fun `выбирается единственный сериал с точным названием`() {
        val pick = KinopoiskParser.pickSeries(search, "Игра престолов", null)
        assertTrue(pick is SeriesPick.One)
        pick as SeriesPick.One
        assertEquals(1, pick.kinopoiskId)
        assertEquals("tt0944947", pick.imdbId)
        assertEquals(2011, pick.year)
    }

    @Test
    fun `название сравнивается без регистра, ё и знаков`() {
        assertTrue(KinopoiskParser.pickSeries(search, "игра   ПРЕСТОЛОВ!", null) is SeriesPick.One)
        assertTrue(KinopoiskParser.pickSeries(search, "game of thrones", null) is SeriesPick.One)
    }

    @Test
    fun `частичное совпадение и не сериалы отбрасываются`() {
        assertEquals(SeriesPick.None, KinopoiskParser.pickSeries(search, "Игра", null))
        assertEquals(SeriesPick.None, KinopoiskParser.pickSeries(search, "Игра престолов. Последний дозор", null))
    }

    @Test
    fun `год отсеивает лишнее`() {
        assertEquals(SeriesPick.None, KinopoiskParser.pickSeries(search, "Игра престолов", 2012))
        assertTrue(KinopoiskParser.pickSeries(search, "Игра престолов", 2011) is SeriesPick.One)
    }

    @Test
    fun `несколько сериалов с одним названием - выбрать нельзя`() {
        val two = """
        {"items":[
          {"kinopoiskId":10,"nameRu":"Шерлок","year":2010,"type":"TV_SERIES"},
          {"kinopoiskId":11,"nameRu":"Шерлок","year":2013,"type":"TV_SERIES"}
        ]}
        """.trimIndent()
        assertEquals(SeriesPick.Many, KinopoiskParser.pickSeries(two, "Шерлок", null))
        assertTrue(KinopoiskParser.pickSeries(two, "Шерлок", 2013) is SeriesPick.One)
    }

    @Test
    fun `мусор вместо ответа не ломает разбор`() {
        assertEquals(SeriesPick.None, KinopoiskParser.pickSeries("oops", "Шерлок", null))
        assertEquals(SeriesPick.None, KinopoiskParser.pickSeries("{}", "Шерлок", null))
        assertEquals(SeriesPick.None, KinopoiskParser.pickSeries(search, "", null))
    }

    @Test
    fun `сезоны и число серий`() {
        val json = """
        {"total":3,"items":[
          {"number":1,"episodes":[{"episodeNumber":1},{"episodeNumber":2},{"episodeNumber":3}]},
          {"number":2,"episodes":[{"episodeNumber":1}]},
          {"number":3,"episodes":[]},
          {"number":0,"episodes":[{"episodeNumber":1}]}
        ]}
        """.trimIndent()
        assertEquals(mapOf(1 to 3, 2 to 1, 3 to 0), KinopoiskParser.parseSeasons(json))
        assertEquals(emptyMap<Int, Int>(), KinopoiskParser.parseSeasons("oops"))
    }

    @Test
    fun `IMDb номер проверяется по форме`() {
        assertTrue(KinopoiskParser.isImdbId("tt0944947"))
        assertTrue(KinopoiskParser.isImdbId("tt30217403"))
        assertFalse(KinopoiskParser.isImdbId("tt1"))
        assertFalse(KinopoiskParser.isImdbId(null))
        assertFalse(KinopoiskParser.isImdbId("0944947"))
    }

    @Test
    fun `IMDb из карточки фильма`() {
        assertEquals("tt0944947", KinopoiskParser.imdbFromCard("""{"kinopoiskId":1,"imdbId":"tt0944947"}"""))
        assertEquals(null, KinopoiskParser.imdbFromCard("""{"kinopoiskId":1,"imdbId":null}"""))
        assertEquals(null, KinopoiskParser.imdbFromCard("oops"))
    }

    @Test
    fun `карточка фильма - название, год, постер и IMDb`() {
        val c = KinopoiskParser.parseCard(
            """{"kinopoiskId":1049731,"imdbId":"tt7441658","nameRu":"Чёрный клевер","year":2017,"posterUrlPreview":"http://p"}"""
        )!!
        assertEquals("tt7441658", c.imdbId)
        assertEquals("Чёрный клевер", c.title)
        assertEquals(2017, c.year)
        assertEquals("http://p", c.posterUrl)
        assertEquals(null, KinopoiskParser.parseCard("oops"))
        assertEquals(null, KinopoiskParser.parseCard("""{"nameRu":null,"nameEn":null}""")!!.title)
    }
}
