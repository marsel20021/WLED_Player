package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

private class FakeActions : SkipActions {
    val seeks = ArrayList<Long>()
    var nextCount = 0
    override fun seekTo(positionMs: Long) { seeks.add(positionMs) }
    override fun skipNext() { nextCount++ }
}

class SkipEngineTest {
    private val store = FakeStore()
    private val actions = FakeActions()

    private var lookupCalls = 0
    private var introCalls = 0
    private var lastIntroArgs: Triple<String, Int, Int>? = null
    private var lookupResult: SeriesLookupResult = SeriesLookupResult.Found(
        SeriesInfo(1, "tt0944947", "Игра престолов", 2011, mapOf(1 to 10))
    )
    private var introResult: IntroResult = IntroResult.Found(
        SkipTimeline(
            listOf(
                SkipSegment(SkipKind.INTRO, 80_000, 170_000, 3, "T"),
                SkipSegment(SkipKind.CREDITS, 1_332_000, 1_422_000, 3, "T")
            )
        )
    )

    // Сеть выполняется сразу, ответ приходит сразу: так проще проверять
    private fun engine(cache: SkipCache = SkipCache(store) { 1_000_000_000L }) = SkipEngine(
        cache = cache,
        lookup = { _, _ -> lookupCalls++; lookupResult },
        intro = { imdb, s, e -> introCalls++; lastIntroArgs = Triple(imdb, s, e); introResult },
        runAsync = { it() },
        post = { it() },
        actions = actions
    )

    private val playlist = (1..10).map { "$it серия" }

    private fun input(
        pos: Long,
        now: Long,
        title: String = "3 серия",
        movie: String? = "Игра престолов",
        index: Int = 2,
        list: List<String> = playlist,
        enabled: Boolean = true,
        connected: Boolean = true,
        playing: Boolean = true
    ) = SkipInput(
        enabled, connected,
        PlaybackInfo(title = title, movieTitle = movie, isPlaying = playing, positionMs = pos, durationMs = 1_440_000, currentIndex = index),
        list, now
    )

    @Test
    fun `фильм - один файл в плейлисте - ничего не запрашиваем`() {
        val e = engine()
        val ui = e.evaluate(input(100_000, 5_000, list = listOf("film.mkv")))
        assertNull(ui.offer)
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `нет номера серии - ничего не запрашиваем`() {
        val e = engine()
        e.evaluate(input(100_000, 5_000, title = "Бригада"))
        e.evaluate(input(100_000, 5_000, title = "Без названия"))
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `нет названия сериала или галочка выключена или нет связи - ничего не запрашиваем`() {
        val e = engine()
        e.evaluate(input(100_000, 5_000, movie = null))
        e.evaluate(input(100_000, 5_000, enabled = false))
        e.evaluate(input(100_000, 5_000, connected = false))
        assertEquals(0, lookupCalls)
        assertEquals(0, introCalls)
    }

    @Test
    fun `один сезон - плашки нет, заставка пропускается сама`() {
        val e = engine()
        var ui = e.evaluate(input(0, 0))
        assertNull(ui.seasonChoice)
        assertEquals(Triple("tt0944947", 1, 3), lastIntroArgs)
        ui = e.evaluate(input(100_000, 3_000))
        assertEquals(3, ui.offer!!.secondsLeft)
        ui = e.evaluate(input(103_000, 6_000))
        assertEquals(listOf(170_000L), actions.seeks)
        assertNotNull(ui.undo)
    }

    @Test
    fun `сериал и тайм-коды запрашиваются один раз на много обновлений`() {
        val e = engine()
        for (i in 0..20) e.evaluate(input(i * 1000L, i * 1000L))
        assertEquals(1, lookupCalls)
        assertEquals(1, introCalls)
    }

    @Test
    fun `несколько сезонов - сначала плашка, кнопки нет, после выбора работает`() {
        lookupResult = SeriesLookupResult.Found(SeriesInfo(1, "tt1", "Игра престолов", 2011, mapOf(1 to 10, 2 to 10, 3 to 8)))
        val e = engine()
        var ui = e.evaluate(input(100_000, 5_000))
        assertEquals(listOf(1, 2, 3), ui.seasonChoice!!.seasons)
        assertNull(ui.seasonChoice!!.selected)
        assertNull(ui.offer)
        assertEquals(0, introCalls)

        e.pickSeason(2)
        ui = e.evaluate(input(100_000, 6_000))
        assertEquals(2, ui.seasonChoice!!.selected)
        assertEquals(Triple("tt1", 2, 3), lastIntroArgs)
        ui = e.evaluate(input(100_000, 9_000))
        assertNotNull(ui.offer)
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `выбор сезона запоминается для того же плейлиста и сбрасывается для другого`() {
        lookupResult = SeriesLookupResult.Found(SeriesInfo(1, "tt1", "Игра престолов", 2011, mapOf(1 to 10, 2 to 10)))
        val e = engine()
        e.evaluate(input(0, 0))
        e.pickSeason(2)
        assertEquals(2, e.evaluate(input(0, 1_000)).seasonChoice!!.selected)
        // другой плейлист - выбор нужен заново
        val other = (1..8).map { "$it серия" }
        assertNull(e.evaluate(input(0, 2_000, list = other)).seasonChoice!!.selected)
    }

    @Test
    fun `сезон назван в названии серии - плашки нет`() {
        lookupResult = SeriesLookupResult.Found(SeriesInfo(1, "tt1", "Игра престолов", 2011, mapOf(1 to 10, 2 to 10)))
        val e = engine()
        val ui = e.evaluate(input(0, 0, title = "2 сезон 3 серия"))
        assertNull(ui.seasonChoice)
        assertEquals(Triple("tt1", 2, 3), lastIntroArgs)
    }

    @Test
    fun `номер серии не пересчитывается - запрос идёт как в названии`() {
        lookupResult = SeriesLookupResult.Found(SeriesInfo(1, "tt1", "Аниме", 2020, mapOf(1 to 12, 2 to 12)))
        val e = engine()
        val list = (1..24).map { "$it серия" }
        e.evaluate(input(0, 0, title = "15 серия", movie = "Аниме", index = 14, list = list))
        e.pickSeason(2)
        e.evaluate(input(0, 500, title = "15 серия", movie = "Аниме", index = 14, list = list))
        assertEquals(Triple("tt1", 2, 15), lastIntroArgs)
        val ui = e.evaluate(input(100_000, 5_000, title = "15 серия", movie = "Аниме", index = 14, list = list))
        assertNotNull(ui.offer)
    }

    @Test
    fun `сбой сети не запоминается и через минуту пробуем снова`() {
        val ok = lookupResult
        lookupResult = SeriesLookupResult.Failed("нет сети")
        val e = engine()
        e.evaluate(input(0, 0))
        e.evaluate(input(0, 30_000))
        assertEquals(1, lookupCalls)
        lookupResult = ok
        e.evaluate(input(0, 61_000))
        assertEquals(2, lookupCalls)
        assertEquals(1, introCalls)
    }

    @Test
    fun `лимит Кинопоиска - следующая попытка только через час`() {
        lookupResult = SeriesLookupResult.Failed("лимит", quota = true)
        val e = engine()
        e.evaluate(input(0, 0))
        e.evaluate(input(0, 120_000))
        assertEquals(1, lookupCalls)
        e.evaluate(input(0, 3_700_000))
        assertEquals(2, lookupCalls)
    }

    @Test
    fun `сериал не найден - запоминается, повторных запросов нет`() {
        lookupResult = SeriesLookupResult.NotFound
        val cache = SkipCache(store) { 1_000_000_000L }
        val e = engine(cache)
        e.evaluate(input(0, 0))
        e.evaluate(input(0, 5_000))
        assertEquals(1, lookupCalls)
        // даже новый движок (перезапуск приложения) не спрашивает снова
        val e2 = engine(cache)
        e2.evaluate(input(0, 0))
        assertEquals(1, lookupCalls)
        assertEquals(0, introCalls)
    }

    @Test
    fun `кэш работает после перезапуска - сеть не нужна`() {
        val cache = SkipCache(store) { 1_000_000_000L }
        engine(cache).evaluate(input(0, 0))
        assertEquals(1, lookupCalls)
        assertEquals(1, introCalls)
        val again = engine(cache)
        again.evaluate(input(0, 0))
        again.evaluate(input(100_000, 3_000))
        assertEquals(1, lookupCalls)
        assertEquals(1, introCalls)
    }

    @Test
    fun `тайм-кодов нет - кнопки нет, ничего не ломается`() {
        introResult = IntroResult.NoData
        val e = engine()
        e.evaluate(input(0, 0))
        val ui = e.evaluate(input(100_000, 5_000))
        assertNull(ui.offer)
        assertTrue(actions.seeks.isEmpty())
    }

    @Test
    fun `сбой тайм-кодов - повтор через минуту`() {
        val ok = introResult
        introResult = IntroResult.Failed("таймаут")
        val e = engine()
        e.evaluate(input(0, 0))
        e.evaluate(input(0, 10_000))
        assertEquals(1, introCalls)
        introResult = ok
        e.evaluate(input(100_000, 62_000))
        assertEquals(2, introCalls)
    }

    @Test
    fun `следующая серия - свои тайм-коды`() {
        val e = engine()
        e.evaluate(input(0, 0, index = 2, title = "3 серия"))
        e.evaluate(input(0, 10_000, index = 3, title = "4 серия"))
        assertEquals(Triple("tt0944947", 1, 4), lastIntroArgs)
        assertEquals(2, introCalls)
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `вернуть - перематывает к началу заставки`() {
        val e = engine()
        e.evaluate(input(0, 0))
        e.evaluate(input(100_000, 3_000))
        e.evaluate(input(103_000, 6_000))
        assertEquals(listOf(170_000L), actions.seeks)
        e.undo(8_000)
        assertEquals(listOf(170_000L, 80_000L), actions.seeks)
        // после возврата заставку заново не предлагаем
        e.evaluate(input(80_000, 12_000))
        val ui = e.evaluate(input(81_000, 13_000))
        assertNull(ui.offer)
    }

    @Test
    fun `вернуть поздно - не работает`() {
        val e = engine()
        e.evaluate(input(0, 0))
        e.evaluate(input(100_000, 3_000))
        e.evaluate(input(103_000, 6_000))
        e.undo(6_000 + SkipEngine.UNDO_MS + 1)
        assertEquals(1, actions.seeks.size)
    }

    @Test
    fun `выключили галочку - всё сбрасывается`() {
        val e = engine()
        e.evaluate(input(0, 0))
        e.evaluate(input(100_000, 3_000))
        val ui = e.evaluate(input(100_000, 4_000, enabled = false))
        assertNull(ui.offer)
        e.skipNow(5_000)
        assertTrue(actions.seeks.isEmpty())
    }

    @Test
    fun `на последней серии титры не пропускаем`() {
        introResult = IntroResult.Found(
            SkipTimeline(listOf(SkipSegment(SkipKind.CREDITS, 1_332_000, null, 3, "T")))
        )
        val e = engine()
        e.evaluate(input(0, 0, index = 9, title = "10 серия"))
        val ui = e.evaluate(input(1_340_000, 3_000, index = 9, title = "10 серия"))
        assertNull(ui.offer)
        e.evaluate(input(1_343_000, 6_000, index = 9, title = "10 серия"))
        assertTrue(actions.seeks.isEmpty())
        assertEquals(0, actions.nextCount)
    }

    @Test
    fun `титры до конца на обычной серии - следующая серия`() {
        introResult = IntroResult.Found(
            SkipTimeline(listOf(SkipSegment(SkipKind.CREDITS, 1_332_000, null, 3, "T")))
        )
        val e = engine()
        e.evaluate(input(0, 0))
        e.evaluate(input(1_340_000, 3_000))
        e.evaluate(input(1_343_000, 6_000))
        assertEquals(1, actions.nextCount)
        assertTrue(actions.seeks.isEmpty())
    }
}
