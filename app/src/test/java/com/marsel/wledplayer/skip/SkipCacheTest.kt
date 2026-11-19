package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryStore : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

class SkipCacheTest {
    private var now = 1_000_000_000L
    private val store = MemoryStore()
    private val cache = SkipCache(store) { now }

    private val info = SeriesInfo(1, "tt0944947", "Игра престолов", 2011, mapOf(1 to 10, 2 to 10))
    private val timeline = SkipTimeline(
        listOf(
            SkipSegment(SkipKind.INTRO, 8_000, 106_000, 9, "TheIntroDB"),
            SkipSegment(SkipKind.CREDITS, 3_000_000, null, 2, "TheIntroDB")
        ),
        postCreditsStartMs = 3_100_000
    )

    @Test
    fun `пустой кэш - промах`() {
        assertEquals(Cached.Miss, cache.getSeries("s:x|0"))
    }

    @Test
    fun `сериал сохраняется и читается`() {
        val key = cache.seriesKey("Игра  Престолов!", 2011)
        cache.putSeries(key, info)
        val hit = cache.getSeries(key) as Cached.Hit
        assertEquals(info, hit.value)
        assertEquals(key, cache.seriesKey("игра престолов", 2011))
    }

    @Test
    fun `сериал живёт неделю`() {
        val key = cache.seriesKey("A", null)
        cache.putSeries(key, info)
        now += SkipCache.SERIES_TTL_MS - 1000
        assertTrue(cache.getSeries(key) is Cached.Hit)
        now += 2000
        assertEquals(Cached.Miss, cache.getSeries(key))
        assertNull(store.get(key)) // устаревшее удалено
    }

    @Test
    fun `данных нет - запоминается, но ненадолго`() {
        val key = cache.seriesKey("A", null)
        cache.putSeries(key, null)
        val hit = cache.getSeries(key) as Cached.Hit
        assertNull(hit.value)
        now += SkipCache.NO_DATA_TTL_MS + 1000
        assertEquals(Cached.Miss, cache.getSeries(key))
    }

    @Test
    fun `тайм-коды сохраняются целиком`() {
        val key = cache.timelineKey("tt0944947", 1, 2)
        cache.putTimeline(key, timeline)
        val got = (cache.getTimeline(key) as Cached.Hit).value!!
        assertEquals(timeline.segments, got.segments)
        assertEquals(3_100_000L, got.postCreditsStartMs)
        assertNull(got.segments[1].endMs)
    }

    @Test
    fun `тайм-коды живут месяц`() {
        val key = cache.timelineKey("tt1", 1, 1)
        cache.putTimeline(key, timeline)
        now += SkipCache.TIMELINE_TTL_MS - 1000
        assertTrue(cache.getTimeline(key) is Cached.Hit)
        now += 2000
        assertEquals(Cached.Miss, cache.getTimeline(key))
    }

    @Test
    fun `испорченная запись считается промахом и удаляется`() {
        store.put("t:bad", "{{{")
        assertEquals(Cached.Miss, cache.getTimeline("t:bad"))
        assertNull(store.get("t:bad"))
        store.put("t:bad2", """{"t":${now},"v":{"s":[{"k":"WRONG","a":1,"b":2,"v":1}]}}""")
        assertEquals(Cached.Miss, cache.getTimeline("t:bad2"))
    }

    @Test
    fun `время ушло назад - запись не доверяем`() {
        val key = cache.timelineKey("tt1", 1, 1)
        cache.putTimeline(key, timeline)
        now -= 10_000
        assertEquals(Cached.Miss, cache.getTimeline(key))
    }

    @Test
    fun `выбранный сезон помнится недолго`() {
        assertNull(cache.getPick("fp"))
        cache.putPick("fp", 2)
        assertEquals(2, cache.getPick("fp"))
        now += SkipCache.PICK_TTL_MS + 1000
        assertNull(cache.getPick("fp"))
    }

    @Test
    fun `старые записи вытесняются при переполнении`() {
        for (i in 0 until SkipCache.MAX_ENTRIES + 20) {
            now += 1000
            cache.putTimeline("t:tt$i:1:1", timeline)
        }
        assertTrue(store.keys().size <= SkipCache.MAX_ENTRIES)
        assertNull(store.get("t:tt0:1:1"))
        assertTrue(store.get("t:tt${SkipCache.MAX_ENTRIES + 19}:1:1") != null)
    }
}
