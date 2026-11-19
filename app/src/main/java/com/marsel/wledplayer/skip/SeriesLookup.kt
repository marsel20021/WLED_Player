package com.marsel.wledplayer.skip

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Сериал, найденный в Кинопоиске. seasonCounts: сезон -> число серий. */
data class SeriesInfo(
    val kinopoiskId: Int,
    val imdbId: String,
    val title: String,
    val year: Int?,
    val seasonCounts: Map<Int, Int>,
    val posterUrl: String? = null
)

sealed interface SeriesLookupResult {
    data class Found(val info: SeriesInfo) : SeriesLookupResult
    /** Такого сериала нет, или он неоднозначный, или у него нет IMDb. Это можно запомнить на пару дней. */
    data object NotFound : SeriesLookupResult
    /** Сбой (нет сети, лимит, токен). Запоминать нельзя. quota = кончился суточный лимит. */
    data class Failed(val reason: String, val quota: Boolean = false) : SeriesLookupResult
}

sealed interface SeriesPick {
    data class One(val kinopoiskId: Int, val imdbId: String?, val title: String, val year: Int?, val posterUrl: String? = null) : SeriesPick
    data object None : SeriesPick
    /** Несколько сериалов с таким названием, выбрать нельзя. */
    data object Many : SeriesPick
}

/** Чистый разбор ответов Кинопоиска, без сети и без Android. */
object KinopoiskParser {
    private val seriesTypes = setOf("TV_SERIES", "MINI_SERIES", "TV_SHOW")
    private val imdbRegex = Regex("^tt\\d{6,10}$")

    fun norm(s: String?): String {
        val lower = (s ?: "").lowercase().replace('ё', 'е')
        return lower.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    /** Берёт из результатов поиска единственный сериал с точно таким названием (и годом, если он известен). */
    fun pickSeries(json: String, title: String, year: Int?): SeriesPick {
        val items = try {
            JSONObject(json).optJSONArray("items")
        } catch (e: JSONException) {
            null
        } ?: return SeriesPick.None

        val want = norm(title)
        if (want.isEmpty()) return SeriesPick.None
        val found = ArrayList<SeriesPick.One>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.str("type") !in seriesTypes) continue
            val names = listOf(o.str("nameRu"), o.str("nameEn"), o.str("nameOriginal"))
            if (names.none { norm(it) == want }) continue
            val itemYear = o.str("year")?.toIntOrNull()
            if (year != null && itemYear != year) continue
            val id = o.optInt("kinopoiskId", 0)
            if (id <= 0) continue
            found.add(SeriesPick.One(id, o.str("imdbId"), names.firstOrNull { it != null } ?: title, itemYear, o.str("posterUrlPreview") ?: o.str("posterUrl")))
        }
        return when (found.size) {
            0 -> SeriesPick.None
            1 -> found[0]
            else -> SeriesPick.Many
        }
    }

    fun isImdbId(s: String?): Boolean = s != null && imdbRegex.matches(s)

    /** Сезон -> число серий. Сезон без серий в списке остаётся с нулём (число серий неизвестно). */
    fun parseSeasons(json: String): Map<Int, Int> {
        val items = try {
            JSONObject(json).optJSONArray("items")
        } catch (e: JSONException) {
            null
        } ?: return emptyMap()
        val out = LinkedHashMap<Int, Int>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val number = o.optInt("number", 0)
            if (number < 1) continue
            out[number] = o.optJSONArray("episodes")?.length() ?: 0
        }
        return out
    }

    fun imdbFromCard(json: String): String? = try {
        JSONObject(json).str("imdbId")
    } catch (e: JSONException) {
        null
    }

    class Card(val imdbId: String?, val title: String?, val year: Int?, val posterUrl: String?)

    fun parseCard(json: String): Card? = try {
        val o = JSONObject(json)
        Card(
            o.str("imdbId"),
            o.str("nameRu") ?: o.str("nameEn") ?: o.str("nameOriginal"),
            o.str("year")?.toIntOrNull(),
            o.str("posterUrlPreview") ?: o.str("posterUrl")
        )
    } catch (e: JSONException) {
        null
    }
}

/** Ищет сериал и его сезоны в Кинопоиске. Токен приходит из сборки (секрет GitHub или local.properties). */
class SeriesLookup(private val token: String) {

    /** override: строка из таблицы поправок. Тогда id берём из неё, а сезоны тоже, если они там заданы. */
    fun lookup(title: String, year: Int?, override: SeriesOverride? = null): SeriesLookupResult {
        if (token.isBlank()) return SeriesLookupResult.Failed("нет токена Кинопоиска")
        val headers = mapOf("X-API-KEY" to token)
        return try {
            val one = if (override != null) {
                SeriesPick.One(override.kinopoiskId, null, override.names.first(), null)
            } else {
                val search = SkipHttp.get(
                    "$BASE/v2.2/films?keyword=${URLEncoder.encode(title, "UTF-8")}&page=1", headers
                )
                failure(search.code)?.let { return it }
                when (val pick = KinopoiskParser.pickSeries(search.body, title, year)) {
                    SeriesPick.None, SeriesPick.Many -> return SeriesLookupResult.NotFound
                    is SeriesPick.One -> pick
                }
            }

            var imdb = one.imdbId
            var name = one.title
            var itemYear = one.year
            var poster = one.posterUrl
            if (override != null || !KinopoiskParser.isImdbId(imdb)) {
                val card = SkipHttp.get("$BASE/v2.2/films/${one.kinopoiskId}", headers)
                // неверный id в таблице: такого фильма нет
                if (override != null && card.code == 404) return SeriesLookupResult.NotFound
                failure(card.code)?.let { return it }
                val c = KinopoiskParser.parseCard(card.body)
                imdb = c?.imdbId
                if (override != null && c != null) {
                    name = c.title ?: name
                    itemYear = c.year
                    poster = c.posterUrl
                }
            }
            if (!KinopoiskParser.isImdbId(imdb)) return SeriesLookupResult.NotFound

            val counts = override?.seasons ?: run {
                val seasonsResp = SkipHttp.get("$BASE/v2.2/films/${one.kinopoiskId}/seasons", headers)
                failure(seasonsResp.code)?.let { return it }
                KinopoiskParser.parseSeasons(seasonsResp.body)
            }
            if (counts.isEmpty()) return SeriesLookupResult.NotFound

            SeriesLookupResult.Found(SeriesInfo(one.kinopoiskId, imdb!!, name, itemYear, counts, poster))
        } catch (e: IOException) {
            SeriesLookupResult.Failed("нет сети: ${e.message}")
        }
    }

    private fun failure(code: Int): SeriesLookupResult.Failed? = when {
        code in 200..299 -> null
        code == 402 -> SeriesLookupResult.Failed("кончился суточный лимит Кинопоиска", quota = true)
        code == 401 -> SeriesLookupResult.Failed("токен Кинопоиска не подошёл")
        else -> SeriesLookupResult.Failed("Кинопоиск ответил HTTP $code")
    }

    private companion object {
        const val BASE = "https://kinopoiskapiunofficial.tech/api"
    }
}
