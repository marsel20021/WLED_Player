package com.marsel.wledplayer.media

import com.marsel.wledplayer.skip.Cached
import com.marsel.wledplayer.skip.KeyValueStore
import com.marsel.wledplayer.skip.SkipHttpResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Кадр одной серии. imageUrl может быть пустым: тогда на плашке просто номер. */
data class EpisodeFrame(val number: Int, val name: String?, val imageUrl: String?)

/** Запись аниме в Kitsu. У сезонов аниме там часто отдельные записи. */
data class KitsuAnime(val id: String, val title: String?, val year: Int?, val subtype: String?)

sealed interface FramesResult {
    data class Found(val frames: List<EpisodeFrame>) : FramesResult
    data object NoData : FramesResult
    data object Failed : FramesResult
}

/** Разбор ответов. Без Android, чтобы проверить обычными тестами. */
object FrameParser {

    /** kinopoisk.dev /v1.4/season: в docs лежат сезоны, в каждом episodes со still. */
    fun parseKinopoiskDev(body: String, season: Int): List<EpisodeFrame> {
        val root = try { JSONObject(body) } catch (e: JSONException) { return emptyList() }
        val docs = root.optJSONArray("docs") ?: root.optJSONArray("items") ?: return emptyList()
        var chosen: JSONObject? = null
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            if (d.optInt("number", -1) == season) { chosen = d; break }
            if (chosen == null) chosen = d
        }
        val eps = chosen?.optJSONArray("episodes") ?: return emptyList()
        val out = ArrayList<EpisodeFrame>()
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            val n = if (e.has("number")) e.optInt("number", -1) else e.optInt("episodeNumber", -1)
            if (n < 0) continue
            val still = e.optJSONObject("still")
            val img = still?.optString("url")?.takeIf { it.isNotBlank() }
                ?: still?.optString("previewUrl")?.takeIf { it.isNotBlank() }
                ?: e.optJSONObject("image")?.optString("url")?.takeIf { it.isNotBlank() }
            val name = e.optString("name").takeIf { it.isNotBlank() } ?: e.optString("enName").takeIf { it.isNotBlank() }
            out.add(EpisodeFrame(n, name, img))
        }
        return out.sortedBy { it.number }
    }

    fun parseKitsuSearch(body: String): List<KitsuAnime> {
        val data = try { JSONObject(body).optJSONArray("data") } catch (e: JSONException) { null } ?: return emptyList()
        val out = ArrayList<KitsuAnime>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i) ?: continue
            val at = a.optJSONObject("attributes") ?: continue
            val year = at.optString("startDate").take(4).toIntOrNull()
            out.add(KitsuAnime(a.optString("id"), at.optString("canonicalTitle").takeIf { it.isNotBlank() }, year, at.optString("subtype").takeIf { it.isNotBlank() }))
        }
        return out
    }

    /** Из найденного берём сериал (TV), у которого год совпадает; иначе первый из подходящих по типу. */
    fun pickKitsu(list: List<KitsuAnime>, year: Int?): KitsuAnime? {
        var best: KitsuAnime? = null
        var bestScore = -1.0
        list.forEachIndexed { i, a ->
            var score = 0.0
            if (a.subtype == "TV") score += 3
            if (year != null && a.year == year) score += 4
            else if (year != null && a.year != null && kotlin.math.abs(a.year - year) == 1) score += 1
            score -= i * 0.01
            if (score > bestScore) { bestScore = score; best = a }
        }
        return best
    }

    fun parseKitsuEpisodes(body: String): List<EpisodeFrame> {
        val data = try { JSONObject(body).optJSONArray("data") } catch (e: JSONException) { null } ?: return emptyList()
        val out = ArrayList<EpisodeFrame>()
        for (i in 0 until data.length()) {
            val at = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val n = at.optInt("number", -1)
            if (n < 0) continue
            val th = at.optJSONObject("thumbnail")
            val img = listOf("original", "large", "medium", "small")
                .firstNotNullOfOrNull { th?.optString(it)?.takeIf { s -> s.isNotBlank() && s != "null" } }
            val name = at.optString("canonicalTitle").takeIf { it.isNotBlank() && it != "null" }
            out.add(EpisodeFrame(n, name, img))
        }
        return out
    }

    fun hasImages(frames: List<EpisodeFrame>) = frames.any { it.imageUrl != null }
}

/**
 * Кадры серий сезона. Сначала kinopoisk.dev (по id Кинопоиска), если картинок там нет - Kitsu (аниме).
 * Результат лежит в кэше целый сезон сразу: после первой загрузки сеть не нужна.
 * Ошибки сети в кэш не пишем.
 */
class EpisodeFrames(
    private val token: String,
    private val store: KeyValueStore,
    private val http: (url: String, headers: Map<String, String>) -> SkipHttpResponse,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TTL_MS = 30 * DAY_MS
        const val NO_DATA_TTL_MS = 2 * DAY_MS
        private const val MAX_ENTRIES = 60
        private const val KITSU_PAGE = 20
        private val KITSU_HEADERS = mapOf("Accept" to "application/vnd.api+json")
        private const val KITSU_MAX_PAGES = 5
    }

    fun key(kinopoiskId: Int, season: Int) = "f1:$kinopoiskId:$season"

    /** Блокирует поток (сеть): вызывать из фонового потока. */
    fun load(kinopoiskId: Int, titles: List<String>, year: Int?, season: Int): FramesResult {
        val k = key(kinopoiskId, season)
        when (val c = read(k)) {
            is Cached.Hit -> return c.value?.let { FramesResult.Found(it) } ?: FramesResult.NoData
            Cached.Miss -> {}
        }
        var failed = false

        if (token.isNotBlank()) {
            for (base in listOf("https://api.poiskkino.dev", "https://api.kinopoisk.dev")) {
                try {
                    val r = http("$base/v1.4/season?page=1&limit=10&movieId=$kinopoiskId&number=$season", mapOf("X-API-KEY" to token))
                    if (r.code in 200..299) {
                        val frames = FrameParser.parseKinopoiskDev(r.body, season)
                        if (FrameParser.hasImages(frames)) return found(k, frames)
                        break // сервис ответил, но кадров нет: другой адрес того же сервиса не поможет
                    }
                    if (r.code == 429 || r.code >= 500) failed = true
                } catch (e: IOException) {
                    failed = true
                }
            }
        }

        val kitsu = loadKitsu(titles, year)
        if (kitsu == null) failed = true
        else if (FrameParser.hasImages(kitsu)) return found(k, kitsu)

        if (failed) return FramesResult.Failed
        write(k, null)
        return FramesResult.NoData
    }

    // null - сеть не ответила; пустой список - ответили, но аниме нет
    private fun loadKitsu(titles: List<String>, year: Int?): List<EpisodeFrame>? {
        try {
            val cands = LinkedHashMap<String, KitsuAnime>()
            for (t in titles.filter { it.isNotBlank() }.distinct().take(3)) {
                val r = http("https://kitsu.io/api/edge/anime?filter%5Btext%5D=${URLEncoder.encode(t, "UTF-8")}&page%5Blimit%5D=8", KITSU_HEADERS)
                if (r.code !in 200..299) return null
                FrameParser.parseKitsuSearch(r.body).forEach { cands.putIfAbsent(it.id, it) }
            }
            val pick = FrameParser.pickKitsu(cands.values.toList(), year) ?: return emptyList()
            val all = ArrayList<EpisodeFrame>()
            for (page in 0 until KITSU_MAX_PAGES) {
                val r = http(
                    "https://kitsu.io/api/edge/anime/${pick.id}/episodes?sort=number&page%5Blimit%5D=$KITSU_PAGE&page%5Boffset%5D=${page * KITSU_PAGE}",
                    KITSU_HEADERS
                )
                if (r.code !in 200..299) return null
                val part = FrameParser.parseKitsuEpisodes(r.body)
                all.addAll(part)
                if (part.size < KITSU_PAGE) break
            }
            return all
        } catch (e: IOException) {
            return null
        }
    }

    private fun found(k: String, frames: List<EpisodeFrame>): FramesResult {
        write(k, frames)
        return FramesResult.Found(frames)
    }

    // ---------- кэш ----------

    private fun read(k: String): Cached<List<EpisodeFrame>> {
        val raw = store.get(k) ?: return Cached.Miss
        return try {
            val o = JSONObject(raw)
            val ttl = if (o.isNull("v")) NO_DATA_TTL_MS else TTL_MS
            if (clock() - o.getLong("t") > ttl) {
                store.remove(k)
                return Cached.Miss
            }
            if (o.isNull("v")) return Cached.Hit(null)
            val arr = o.getJSONArray("v")
            val list = (0 until arr.length()).map {
                val e = arr.getJSONObject(it)
                EpisodeFrame(e.getInt("n"), e.optString("m").takeIf { s -> s.isNotEmpty() }, e.optString("i").takeIf { s -> s.isNotEmpty() })
            }
            Cached.Hit(list)
        } catch (e: JSONException) {
            store.remove(k)
            Cached.Miss
        }
    }

    private fun write(k: String, frames: List<EpisodeFrame>?) {
        val o = JSONObject().put("t", clock())
        if (frames == null) o.put("v", JSONObject.NULL)
        else o.put("v", JSONArray().also { a ->
            frames.forEach { a.put(JSONObject().put("n", it.number).put("m", it.name ?: "").put("i", it.imageUrl ?: "")) }
        })
        store.put(k, o.toString())
        trim()
    }

    private fun trim() {
        val mine = store.keys().filter { it.startsWith("f1:") }
        if (mine.size <= MAX_ENTRIES) return
        val dated = mine.mapNotNull { k ->
            val t = try { JSONObject(store.get(k) ?: return@mapNotNull null).getLong("t") } catch (e: JSONException) { 0L }
            k to t
        }.sortedBy { it.second }
        dated.take(mine.size - MAX_ENTRIES).forEach { store.remove(it.first) }
    }
}
