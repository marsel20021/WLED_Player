package com.marsel.wledplayer.skip

import com.marsel.wledplayer.media.PosterQuery
import com.marsel.wledplayer.media.PosterTitle
import java.security.MessageDigest

/** Что сейчас играет на ТВ. */
data class PlaybackInfo(
    val title: String,
    val movieTitle: String?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val currentIndex: Int
)

/** Выбор сезона для плашки. selected = null: сезон ещё не выбран. */
data class SeasonChoice(val seasons: List<Int>, val selected: Int?)

/** После прыжка: можно вернуться назад. */
data class SkipUndo(val kind: SkipKind)

/** Всё, что нужно нарисовать на экране. */
data class SkipUiState(
    val offer: SkipOffer? = null,
    val seasonChoice: SeasonChoice? = null,
    val undo: SkipUndo? = null
)

/** Команды ТВ. */
interface SkipActions {
    fun seekTo(positionMs: Long)
    fun skipNext()
}

/** Что сейчас происходит. nowMs - монотонное время телефона. */
data class SkipInput(
    val enabled: Boolean,
    val connected: Boolean,
    val playback: PlaybackInfo,
    val playlist: List<String>,
    val nowMs: Long,
    /** Пропускать ли пересказ (настройка). */
    val allowRecap: Boolean = true,
    /** Держать кнопку пропуска внутри отрезка после перемотки (настройка). */
    val allowHold: Boolean = true
)

/**
 * Склеивает всё вместе: разбор названия, поиск сериала, сезон, тайм-коды, решатель.
 * Работает в одном потоке (его обеспечивает SkipController). Сеть вызывается через runAsync,
 * а результат возвращается обратно через post, поэтому внутри нет блокировок.
 */
class SkipEngine(
    private val cache: SkipCache,
    private val lookup: (title: String, year: Int?) -> SeriesLookupResult,
    private val intro: (imdbId: String, season: Int, episode: Int) -> IntroResult,
    private val runAsync: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val actions: SkipActions,
    private val log: (String) -> Unit = {}
) {
    private enum class Load { NONE, LOADING, READY, MISSING, FAILED }

    private class Ctx(val ref: EpisodeRef, val movieTitle: String, val query: PosterQuery)
    private class Undo(val kind: SkipKind, val returnToMs: Long, val expiresAt: Long)

    private val decider = SkipDecider()
    private var now = 0L

    // сериал
    private var seriesId: String? = null
    private var series: SeriesInfo? = null

    /** Найденный сериал и сезон, нужны экрану, чтобы показать кадры серий. */
    val currentSeries: SeriesInfo? get() = series
    var currentSeason: Int? = null
        private set

    /** Тайм-коды текущей серии, если уже загружены. */
    val currentTimeline: SkipTimeline? get() = if (tlState == Load.READY) tlValue else null
    private var seriesState = Load.NONE
    private var seriesRetryAt = 0L
    private var seriesFails = 0
    private var fingerprint: String? = null
    private var manualPick: Int? = null
    private var cachedPickFor: String? = null
    private var cachedPick: Int? = null

    // серия
    private var epKey: String? = null
    private var tlState = Load.NONE
    private var tlRetryAt = 0L
    private var tlFails = 0
    private var tlValue: SkipTimeline? = null
    private var tlApplied = false

    private var undo: Undo? = null
    private var lastInput: SkipInput? = null
    private var lastSnap: SkipSnapshot? = null

    fun evaluate(input: SkipInput): SkipUiState {
        now = input.nowMs
        lastInput = input
        val ctx = context(input)
        if (ctx == null) {
            lastSnap = null
            undo = null
            return SkipUiState()
        }
        val pb = input.playback

        // Новый сериал или новый плейлист: выбор сезона и данные начинаем заново
        val sid = KinopoiskParser.norm(ctx.query.title) + "|" + (ctx.query.year ?: 0)
        if (sid != seriesId) {
            seriesId = sid
            series = null
            seriesState = Load.NONE
            seriesRetryAt = 0L
            seriesFails = 0
            fingerprint = null
            manualPick = null
            epKey = null
            undo = null
        }
        val fp = fingerprintOf(sid, input.playlist)
        if (fp != fingerprint) {
            fingerprint = fp
            manualPick = null
            cachedPickFor = null
        }

        ensureSeries(sid, ctx.query)
        val info = series
        if (seriesState != Load.READY || info == null) {
            lastSnap = null
            return SkipUiState(undo = currentUndo())
        }

        // Какой сезон: из названия, единственный, или выбранный человеком
        val seasons = info.seasonCounts.keys.sorted()
        val fixedSeason = ctx.ref.season ?: EpisodeParser.findSeason(ctx.movieTitle) ?: seasons.singleOrNull()
        val season = fixedSeason ?: manualPick?.takeIf { it in seasons } ?: loadPick(fp, seasons)
        val choice = if (fixedSeason == null && seasons.size >= 2) SeasonChoice(seasons, season) else null
        currentSeason = season
        if (season == null) {
            lastSnap = null
            return SkipUiState(seasonChoice = choice)
        }
        // Номер серии берём из названия как есть: базы тайм-кодов сами скажут, есть ли такая серия
        val episode = ctx.ref.episode

        val key = "$fp|${pb.currentIndex}|${pb.title}|S${season}E$episode"
        if (key != epKey) {
            epKey = key
            tlState = Load.NONE
            tlRetryAt = 0L
            tlFails = 0
            tlValue = null
            tlApplied = false
            undo = null
        }
        ensureTimeline(key, info.imdbId, season, episode)

        val snap = SkipSnapshot(
            episodeKey = key,
            positionMs = pb.positionMs,
            durationMs = pb.durationMs,
            isPlaying = pb.isPlaying,
            connected = input.connected,
            hasNextEpisode = pb.currentIndex < input.playlist.size - 1,
            nowMs = now,
            allowRecap = input.allowRecap,
            allowHold = input.allowHold
        )
        lastSnap = snap
        val step = decider.update(snap)
        if (tlState == Load.READY && !tlApplied) {
            decider.setTimeline(key, tlValue)
            tlApplied = true
        }
        step.fire?.let { execute(it, pb.durationMs) }
        return SkipUiState(offer = step.offer, seasonChoice = choice, undo = currentUndo())
    }

    /** Человек нажал кнопку "Пропустить". */
    fun skipNow(nowMs: Long) {
        now = nowMs
        val snap = lastSnap ?: return
        val fire = decider.skipNow(snap.copy(nowMs = nowMs)) ?: return
        execute(fire, snap.durationMs)
    }

    /** Человек нажал ✕. */
    fun cancel() = decider.cancel()

    /** Человек нажал "Вернуть". */
    fun undo(nowMs: Long) {
        now = nowMs
        val u = undo ?: return
        undo = null
        if (nowMs <= u.expiresAt) actions.seekTo(u.returnToMs)
    }

    /** Человек выбрал сезон на плашке. */
    fun pickSeason(season: Int) {
        val info = series ?: return
        val fp = fingerprint ?: return
        if (season !in info.seasonCounts.keys) return
        manualPick = season
        cache.putPick(fp, season)
    }

    // ---------- внутреннее ----------

    private fun context(input: SkipInput): Ctx? {
        if (!input.enabled || !input.connected) return null
        // Один файл - это фильм, для фильмов ничего не делаем
        if (input.playlist.size <= 1) return null
        val pb = input.playback
        val ref = EpisodeParser.parse(pb.title) ?: return null
        val movie = pb.movieTitle ?: return null
        val query = PosterTitle.parse(EpisodeParser.stripSeason(movie)) ?: return null
        return Ctx(ref, movie, query)
    }

    private fun fingerprintOf(sid: String, playlist: List<String>): String {
        val raw = sid + "|" + playlist.size + "|" + playlist.first() + "|" + playlist.last()
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun loadPick(fp: String, seasons: List<Int>): Int? {
        if (cachedPickFor != fp) {
            cachedPickFor = fp
            cachedPick = cache.getPick(fp)
        }
        return cachedPick?.takeIf { it in seasons }
    }

    private fun currentUndo(): SkipUndo? {
        val u = undo ?: return null
        if (now > u.expiresAt) {
            undo = null
            return null
        }
        return SkipUndo(u.kind)
    }

    private fun execute(fire: SkipFire, durationMs: Long) {
        log("пропуск ${fire.kind}: ${fire.action}")
        when (val a = fire.action) {
            is SkipAction.SeekTo -> {
                val target = if (durationMs > 0) minOf(a.ms, durationMs - SkipRules.END_MARGIN_MS) else a.ms
                actions.seekTo(maxOf(0L, target))
            }
            is SkipAction.NextEpisode -> actions.skipNext()
        }
        undo = fire.returnToMs?.let { Undo(fire.kind, it, now + UNDO_MS) }
    }

    private fun ensureSeries(sid: String, q: PosterQuery) {
        if (seriesState == Load.FAILED && now >= seriesRetryAt) seriesState = Load.NONE
        if (seriesState != Load.NONE) return
        val cacheKey = cache.seriesKey(q.title, q.year)
        when (val c = cache.getSeries(cacheKey)) {
            is Cached.Hit -> {
                val info = c.value
                if (info != null) {
                    series = info
                    seriesState = Load.READY
                } else {
                    seriesState = Load.MISSING
                }
                return
            }
            Cached.Miss -> Unit
        }
        seriesState = Load.LOADING
        runAsync {
            val result = lookup(q.title, q.year)
            post { onSeries(sid, cacheKey, result) }
        }
    }

    private fun onSeries(sid: String, cacheKey: String, result: SeriesLookupResult) {
        if (sid != seriesId) return
        when (result) {
            is SeriesLookupResult.Found -> {
                cache.putSeries(cacheKey, result.info)
                series = result.info
                seriesState = Load.READY
                seriesFails = 0
                log("сериал найден: ${result.info.title}, сезонов ${result.info.seasonCounts.size}")
            }
            SeriesLookupResult.NotFound -> {
                cache.putSeries(cacheKey, null)
                seriesState = Load.MISSING
                log("сериал не найден")
            }
            is SeriesLookupResult.Failed -> {
                seriesState = Load.FAILED
                seriesFails++
                seriesRetryAt = now + if (result.quota) QUOTA_RETRY_MS else backoff(seriesFails)
                log("сбой поиска: ${result.reason}")
            }
        }
    }

    private fun ensureTimeline(key: String, imdbId: String, season: Int, episode: Int) {
        if (tlState == Load.FAILED && now >= tlRetryAt) tlState = Load.NONE
        if (tlState != Load.NONE) return
        val cacheKey = cache.timelineKey(imdbId, season, episode)
        when (val c = cache.getTimeline(cacheKey)) {
            is Cached.Hit -> {
                tlValue = c.value
                tlState = Load.READY
                return
            }
            Cached.Miss -> Unit
        }
        tlState = Load.LOADING
        runAsync {
            val result = intro(imdbId, season, episode)
            post { onTimeline(key, cacheKey, result) }
        }
    }

    private fun onTimeline(key: String, cacheKey: String, result: IntroResult) {
        if (key != epKey) return
        when (result) {
            is IntroResult.Found -> {
                cache.putTimeline(cacheKey, result.timeline)
                tlValue = result.timeline
                tlState = Load.READY
                log("тайм-коды найдены: ${result.timeline.segments.size} шт.")
            }
            IntroResult.NoData -> {
                cache.putTimeline(cacheKey, null)
                tlValue = null
                tlState = Load.READY
                log("тайм-кодов нет")
            }
            is IntroResult.Failed -> {
                tlState = Load.FAILED
                tlFails++
                tlRetryAt = now + backoff(tlFails)
                log("сбой тайм-кодов: ${result.reason}")
            }
        }
    }

    /** Паузы между повторами при сбоях: минута, 5 минут, дальше 15 минут. */
    private fun backoff(fails: Int): Long = when {
        fails <= 1 -> 60_000L
        fails == 2 -> 300_000L
        else -> 900_000L
    }

    companion object {
        const val UNDO_MS = 8_000L
        const val QUOTA_RETRY_MS = 60L * 60 * 1000
    }
}
