package com.marsel.wledplayer.skip

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Простое хранилище "ключ - строка". В приложении это SharedPreferences, в тестах - память. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** Ответ из кэша. Miss - в кэше ничего нет (или устарело). Hit(null) - мы уже спрашивали и данных нет. */
sealed interface Cached<out T> {
    data object Miss : Cached<Nothing>
    data class Hit<T>(val value: T?) : Cached<T>
}

/**
 * Кэш: сериал и сезоны живут долго, тайм-коды ещё дольше, "данных нет" - недолго,
 * ошибки сети сюда не пишутся вообще (это решает вызывающий код).
 */
class SkipCache(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val SERIES_TTL_MS = 7 * DAY_MS
        const val TIMELINE_TTL_MS = 30 * DAY_MS
        const val NO_DATA_TTL_MS = 2 * DAY_MS
        const val PICK_TTL_MS = 3L * 60 * 60 * 1000
        const val MAX_ENTRIES = 300
    }

    // ---------- сериал ----------

    fun seriesKey(title: String, year: Int?): String = "s:" + KinopoiskParser.norm(title) + "|" + (year ?: 0)

    fun getSeries(key: String): Cached<SeriesInfo> = read(key, SERIES_TTL_MS) { v ->
        val o = v as? JSONObject ?: return@read null
        val counts = LinkedHashMap<Int, Int>()
        val c = o.getJSONObject("c")
        for (k in c.keys()) counts[k.toInt()] = c.getInt(k)
        SeriesInfo(
            o.getInt("id"), o.getString("imdb"), o.getString("title"), if (o.isNull("y")) null else o.getInt("y"), counts,
            o.optString("p").takeIf { it.isNotEmpty() }
        )
    }

    fun putSeries(key: String, info: SeriesInfo?) {
        write(key, info?.let {
            JSONObject().put("id", it.kinopoiskId).put("imdb", it.imdbId).put("title", it.title)
                .put("y", it.year ?: JSONObject.NULL)
                .put("p", it.posterUrl ?: "")
                .put("c", JSONObject().also { c -> it.seasonCounts.forEach { (s, n) -> c.put(s.toString(), n) } })
        })
    }

    // ---------- тайм-коды серии ----------

    fun timelineKey(imdbId: String, season: Int, episode: Int): String = "t2:$imdbId:$season:$episode"

    fun getTimeline(key: String): Cached<SkipTimeline> = read(key, TIMELINE_TTL_MS) { v ->
        val o = v as? JSONObject ?: return@read null
        val arr = o.getJSONArray("s")
        val list = ArrayList<SkipSegment>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            list.add(
                SkipSegment(
                    SkipKind.valueOf(s.getString("k")), s.getLong("a"),
                    if (s.isNull("b")) null else s.getLong("b"), s.getInt("v"), s.optString("src", "")
                )
            )
        }
        SkipTimeline(list, if (o.isNull("p")) null else o.getLong("p"))
    }

    fun putTimeline(key: String, timeline: SkipTimeline?) {
        write(key, timeline?.let { t ->
            JSONObject().put("p", t.postCreditsStartMs ?: JSONObject.NULL).put("s", JSONArray().also { arr ->
                t.segments.forEach { s ->
                    arr.put(
                        JSONObject().put("k", s.kind.name).put("a", s.startMs).put("b", s.endMs ?: JSONObject.NULL)
                            .put("v", s.votes).put("src", s.source)
                    )
                }
            })
        })
    }

    // ---------- выбранный сезон ----------

    fun getPick(fingerprint: String): Int? {
        val r = read("p:$fingerprint", PICK_TTL_MS) { v -> (v as? Number)?.toInt() }
        return (r as? Cached.Hit)?.value
    }

    fun putPick(fingerprint: String, season: Int) {
        write("p:$fingerprint", season)
    }

    // ---------- внутреннее ----------

    private fun <T> read(key: String, ttlMs: Long, decode: (Any) -> T?): Cached<T> {
        val raw = store.get(key) ?: return Cached.Miss
        try {
            val e = JSONObject(raw)
            val age = clock() - e.getLong("t")
            val noData = e.isNull("v")
            val ttl = if (noData) NO_DATA_TTL_MS else ttlMs
            if (age < 0 || age > ttl) {
                store.remove(key)
                return Cached.Miss
            }
            if (noData) return Cached.Hit(null)
            val value = decode(e.get("v"))
            if (value == null) {
                store.remove(key)
                return Cached.Miss
            }
            return Cached.Hit(value)
        } catch (ex: JSONException) {
            store.remove(key)
            return Cached.Miss
        } catch (ex: IllegalArgumentException) {
            store.remove(key)
            return Cached.Miss
        }
    }

    private fun write(key: String, value: Any?) {
        val e = JSONObject().put("t", clock()).put("v", value ?: JSONObject.NULL)
        store.put(key, e.toString())
        prune()
    }

    private fun prune() {
        val keys = store.keys()
        if (keys.size <= MAX_ENTRIES) return
        val aged = keys.map { k ->
            val t = try { JSONObject(store.get(k) ?: "").getLong("t") } catch (e: JSONException) { 0L }
            k to t
        }.sortedBy { it.second }
        val removeCount = keys.size - (MAX_ENTRIES * 9 / 10)
        aged.take(removeCount).forEach { store.remove(it.first) }
    }
}
