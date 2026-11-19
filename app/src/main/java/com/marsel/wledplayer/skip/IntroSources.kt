package com.marsel.wledplayer.skip

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.math.roundToLong

/** Что вернул один сервис с тайм-кодами. */
data class SourceData(val segments: List<SkipSegment>, val postCreditsStartMs: Long?)

/** Чистый разбор ответов TheIntroDB и IntroDB, без сети. */
object IntroParser {

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.roundToLong()

    /** Элемент бывает объектом или массивом объектов: приводим к списку. */
    private fun items(root: JSONObject, key: String): List<JSONObject> {
        val v = root.opt(key)
        return when (v) {
            is JSONObject -> listOf(v)
            is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
            else -> emptyList()
        }
    }

    /** Время в миллисекундах: берём точное поле, а если его нет - секунды (с дробной частью). */
    private fun JSONObject.timeMs(msKey: String, secKey: String): Long? {
        longOrNull(msKey)?.let { return it }
        if (isNull(secKey) || !has(secKey)) return null
        val d = optDouble(secKey, Double.NaN)
        return if (d.isNaN()) null else (d * 1000).roundToLong()
    }

    private fun root(json: String): JSONObject? = try {
        val o = JSONObject(json)
        // на случай, если данные лежат внутри "data"
        if (!o.has("intro") && !o.has("credits") && !o.has("outro") && o.optJSONObject("data") != null) o.getJSONObject("data") else o
    } catch (e: JSONException) {
        null
    }

    /** TheIntroDB: время в миллисекундах; start=null - с начала, end=null - до конца серии. */
    fun parseTheIntroDb(json: String): SourceData? {
        val o = root(json) ?: return null
        val out = ArrayList<SkipSegment>()
        fun add(key: String, kind: SkipKind) {
            for (it in items(o, key)) {
                val votes = it.optInt("submission_count", 0)
                val start = it.longOrNull("start_ms")
                val end = it.longOrNull("end_ms")
                when (kind) {
                    SkipKind.INTRO, SkipKind.RECAP -> {
                        if (end == null) continue
                        out.add(SkipSegment(kind, start ?: 0L, end, votes, "TheIntroDB"))
                    }
                    SkipKind.CREDITS -> {
                        // начало титров неизвестно - прыгать не от чего
                        if (start == null) continue
                        out.add(SkipSegment(kind, start, end, votes, "TheIntroDB"))
                    }
                }
            }
        }
        add("intro", SkipKind.INTRO)
        add("recap", SkipKind.RECAP)
        add("credits", SkipKind.CREDITS)
        return SourceData(sortByVotes(out), null)
    }

    /** IntroDB: время в секундах; outro = титры, post_credits = сцена после титров. */
    fun parseIntroDb(json: String): SourceData? {
        val o = root(json) ?: return null
        val out = ArrayList<SkipSegment>()
        fun add(key: String, kind: SkipKind) {
            for (it in items(o, key)) {
                val votes = it.optInt("submission_count", 0)
                val start = it.timeMs("start_ms", "start_sec")
                val end = it.timeMs("end_ms", "end_sec")
                when (kind) {
                    SkipKind.INTRO, SkipKind.RECAP -> {
                        if (end == null) continue
                        out.add(SkipSegment(kind, start ?: 0L, end, votes, "IntroDB"))
                    }
                    SkipKind.CREDITS -> {
                        if (start == null) continue
                        out.add(SkipSegment(kind, start, end, votes, "IntroDB"))
                    }
                }
            }
        }
        add("intro", SkipKind.INTRO)
        add("recap", SkipKind.RECAP)
        add("outro", SkipKind.CREDITS)
        val post = items(o, "post_credits").mapNotNull { it.timeMs("start_ms", "start_sec") }.minOrNull()
        return SourceData(sortByVotes(out), post)
    }

    /** Больше отметок - выше. При равенстве остаётся порядок из ответа. */
    private fun sortByVotes(list: List<SkipSegment>): List<SkipSegment> = list.sortedByDescending { it.votes }

    /**
     * Склейка: сначала берём TheIntroDB, второй сервис дополняет то, чего нет.
     * Титры: если у первого только "до конца серии", а у второго есть точный конец - берём второго.
     */
    fun merge(primary: SourceData?, secondary: SourceData?): SkipTimeline {
        val a = primary?.segments ?: emptyList()
        val b = secondary?.segments ?: emptyList()
        val result = ArrayList<SkipSegment>()
        for (kind in SkipKind.values()) {
            val fromA = a.filter { it.kind == kind }
            val fromB = b.filter { it.kind == kind }
            val chosen = when {
                fromA.isEmpty() -> fromB
                kind == SkipKind.CREDITS && fromA.all { it.endMs == null } && fromB.any { it.endMs != null } -> fromB
                else -> fromA
            }
            result.addAll(chosen)
        }
        val post = primary?.postCreditsStartMs ?: secondary?.postCreditsStartMs
        return SkipTimeline(result, post)
    }

    /** Нужен ли второй сервис: нет заставки, пересказа, титров или у титров нет конца. */
    fun needsSecond(first: SourceData?): Boolean {
        val s = first?.segments ?: return true
        val credits = s.filter { it.kind == SkipKind.CREDITS }
        return s.none { it.kind == SkipKind.INTRO } || s.none { it.kind == SkipKind.RECAP } || credits.isEmpty() || credits.all { it.endMs == null }
    }
}

sealed interface IntroResult {
    data class Found(val timeline: SkipTimeline) : IntroResult
    /** Оба сервиса ответили, что данных нет. Можно запомнить ненадолго. */
    data object NoData : IntroResult
    /** Сбой сети или сервера: запоминать нельзя. */
    data class Failed(val reason: String) : IntroResult
}

/** Спрашивает TheIntroDB, при нехватке данных - IntroDB. Ключи не нужны. */
class IntroSources {

    fun fetch(imdbId: String, season: Int, episode: Int): IntroResult {
        var failure: String? = null

        val first = when (val r = query(
            "https://api.theintrodb.org/v4/media?imdb_id=$imdbId&season=$season&episode=$episode&details=true"
        )) {
            is Reply.Ok -> IntroParser.parseTheIntroDb(r.body)
            Reply.Empty -> null
            is Reply.Fail -> { failure = r.reason; null }
        }

        var second: SourceData? = null
        if (IntroParser.needsSecond(first)) {
            second = when (val r = query(
                "https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$episode"
            )) {
                is Reply.Ok -> IntroParser.parseIntroDb(r.body)
                Reply.Empty -> null
                is Reply.Fail -> { if (failure == null) failure = r.reason; null }
            }
        }

        val timeline = IntroParser.merge(first, second)
        if (timeline.segments.isNotEmpty()) return IntroResult.Found(timeline)
        return if (failure != null) IntroResult.Failed(failure) else IntroResult.NoData
    }

    private sealed interface Reply {
        data class Ok(val body: String) : Reply
        data object Empty : Reply
        data class Fail(val reason: String) : Reply
    }

    private fun query(url: String): Reply = try {
        val r = SkipHttp.get(url)
        when {
            r.code in 200..299 -> Reply.Ok(r.body)
            r.code == 404 || r.code == 400 -> Reply.Empty
            else -> Reply.Fail("HTTP ${r.code}")
        }
    } catch (e: IOException) {
        Reply.Fail("нет сети: ${e.message}")
    }
}
