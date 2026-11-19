package com.marsel.wledplayer.media

import com.marsel.wledplayer.skip.KeyValueStore
import com.marsel.wledplayer.skip.SkipHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class MemStore : KeyValueStore {
    val m = LinkedHashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
    override fun keys(): Set<String> = m.keys.toSet()
}

class EpisodeFramesTest {
    private val kpd = """{"docs":[{"number":1,"episodes":[
        {"number":1,"name":"Зима близко","still":{"url":"https://img/1.jpg","previewUrl":"https://img/1p.jpg"}},
        {"number":2,"enName":"Road","still":{"previewUrl":"https://img/2p.jpg"}},
        {"number":3,"name":"Без кадра"}]}]}"""

    private val kitsuSearch = """{"data":[
        {"id":"7","attributes":{"canonicalTitle":"Movie","startDate":"2020-01-01","subtype":"movie"}},
        {"id":"8","attributes":{"canonicalTitle":"Show","startDate":"2020-04-01","subtype":"TV"}},
        {"id":"9","attributes":{"canonicalTitle":"Show 2","startDate":"2022-04-01","subtype":"TV"}}]}"""

    private val kitsuEps = """{"data":[
        {"attributes":{"number":1,"canonicalTitle":"Старт","thumbnail":{"original":"https://k/1.jpg"}}},
        {"attributes":{"number":2,"canonicalTitle":"Дальше","thumbnail":null}}]}"""

    @Test
    fun `kinopoisk dev - кадры серий достаются`() {
        val f = FrameParser.parseKinopoiskDev(kpd, 1)
        assertEquals(3, f.size)
        assertEquals("https://img/1.jpg", f[0].imageUrl)
        assertEquals("https://img/2p.jpg", f[1].imageUrl)
        assertEquals("Road", f[1].name)
        assertNull(f[2].imageUrl)
    }

    @Test
    fun `kinopoisk dev - мусор не роняет`() {
        assertTrue(FrameParser.parseKinopoiskDev("не json", 1).isEmpty())
        assertTrue(FrameParser.parseKinopoiskDev("""{"docs":[]}""", 1).isEmpty())
    }

    @Test
    fun `kitsu - выбираем сериал с подходящим годом`() {
        val list = FrameParser.parseKitsuSearch(kitsuSearch)
        assertEquals("8", FrameParser.pickKitsu(list, 2020)?.id)
        assertEquals("9", FrameParser.pickKitsu(list, 2022)?.id)
        assertEquals("8", FrameParser.pickKitsu(list, null)?.id)
    }

    @Test
    fun `kitsu - серии и пустая картинка`() {
        val f = FrameParser.parseKitsuEpisodes(kitsuEps)
        assertEquals("https://k/1.jpg", f[0].imageUrl)
        assertNull(f[1].imageUrl)
    }

    private fun frames(
        store: KeyValueStore = MemStore(),
        token: String = "tok",
        clock: () -> Long = { 1_000L },
        http: (String, Map<String, String>) -> SkipHttpResponse
    ) = EpisodeFrames(token, store, http, clock)

    @Test
    fun `кадры берутся у kinopoisk dev и кладутся в кэш на весь сезон`() {
        var calls = 0
        val f = frames { url, h ->
            calls++
            assertTrue(url.contains("movieId=5") && url.contains("number=1"))
            assertEquals("tok", h["X-API-KEY"])
            SkipHttpResponse(200, kpd)
        }
        val r1 = f.load(5, listOf("Игра"), 2011, 1) as FramesResult.Found
        assertEquals(3, r1.frames.size)
        val r2 = f.load(5, listOf("Игра"), 2011, 1) as FramesResult.Found
        assertEquals(3, r2.frames.size)
        assertEquals(1, calls)
    }

    @Test
    fun `нет кадров у kinopoisk dev - идём в kitsu`() {
        val f = frames { url, _ ->
            when {
                url.contains("kinopoisk.dev") || url.contains("poiskkino.dev") -> SkipHttpResponse(200, """{"docs":[{"number":1,"episodes":[{"number":1}]}]}""")
                url.contains("/anime?") -> SkipHttpResponse(200, kitsuSearch)
                url.contains("/anime/8/episodes") -> SkipHttpResponse(200, kitsuEps)
                else -> SkipHttpResponse(404, "")
            }
        }
        val r = f.load(5, listOf("Show"), 2020, 1) as FramesResult.Found
        assertEquals("https://k/1.jpg", r.frames[0].imageUrl)
    }

    @Test
    fun `без токена сразу kitsu`() {
        val urls = ArrayList<String>()
        val f = frames(token = "") { url, _ -> urls.add(url); SkipHttpResponse(200, """{"data":[]}""") }
        assertEquals(FramesResult.NoData, f.load(5, listOf("X"), null, 1))
        assertTrue(urls.none { it.contains("kinopoisk") || it.contains("poiskkino") })
    }

    @Test
    fun `ошибка сети не пишется в кэш`() {
        val store = MemStore()
        val f = frames(store) { _, _ -> throw IOException("нет сети") }
        assertEquals(FramesResult.Failed, f.load(5, listOf("X"), null, 1))
        assertTrue(store.m.isEmpty())
    }

    @Test
    fun `данных нет - помним недолго`() {
        var now = 1_000L
        var calls = 0
        val f = frames(clock = { now }) { _, _ -> calls++; SkipHttpResponse(200, """{"docs":[],"data":[]}""") }
        assertEquals(FramesResult.NoData, f.load(5, listOf("X"), null, 1))
        val first = calls
        assertEquals(FramesResult.NoData, f.load(5, listOf("X"), null, 1))
        assertEquals(first, calls)
        now += EpisodeFrames.NO_DATA_TTL_MS + 1
        f.load(5, listOf("X"), null, 1)
        assertTrue(calls > first)
    }

    @Test
    fun `палитра берёт главный цвет постера`() {
        val red = 0xFFD02020.toInt()
        val grey = 0xFF808080.toInt()
        val px = IntArray(100) { if (it < 70) red else grey }
        val c = PosterPalette.fromPixels(px)
        val h = PosterPalette.toHsv(c.main)[0]
        assertTrue("оттенок $h", h < 20f || h > 340f)
        assertNotNull(c.accent)
    }

    @Test
    fun `палитра у серого постера - цвета по умолчанию`() {
        val px = IntArray(50) { 0xFF777777.toInt() }
        assertEquals(PosterColors.DEFAULT, PosterPalette.fromPixels(px))
    }
}
