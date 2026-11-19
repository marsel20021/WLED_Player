package com.marsel.wledplayer.skip

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.marsel.wledplayer.BuildConfig
import java.util.concurrent.Executors

/**
 * Автопропуск заставки, пересказа и титров. Работает на главном потоке: там же живёт плеер.
 * Сеть уходит в отдельный поток, ответ возвращается обратно на главный.
 * Каждые 250-500 мс собирает состояние плеера и отдаёт его решателю.
 */
class SkipController(
    context: Context,
    private val player: ExoPlayer,
    private val prefs: SharedPreferences,
    private val movieTitle: () -> String?,
    private val onState: (SkipUiState) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val series = SeriesLookup(BuildConfig.KINOPOISK_TOKEN)
    private val sources = IntroSources()

    private val engine = SkipEngine(
        cache = SkipCache(SharedPrefsStore(context)),
        lookup = { title, year -> series.lookup(title, year) },
        intro = { imdb, season, episode -> sources.fetch(imdb, season, episode) },
        runAsync = { job ->
            io.execute {
                try {
                    job()
                } catch (e: Exception) {
                    Log.w(TAG, "сбой фоновой задачи: ${e.message}")
                }
            }
        },
        post = { handler.post(it) },
        actions = object : SkipActions {
            override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
            override fun skipNext() = player.seekToNextMediaItem()
        },
        log = { Log.d(TAG, it) }
    )

    /** Последнее состояние, которое отдали экрану. */
    var state = SkipUiState()
        private set

    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            var next = SLOW_TICK_MS
            try {
                state = engine.evaluate(buildInput())
                onState(state)
                // Пока кнопка на экране, проверяем чаще: полоска отсчёта идёт плавно
                if (state.offer != null) next = FAST_TICK_MS
            } catch (e: Exception) {
                Log.w(TAG, "сбой в цикле пропуска: ${e.message}")
            }
            handler.postDelayed(this, next)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    fun skipNow() = engine.skipNow(now())
    fun cancel() = engine.cancel()
    fun undo() = engine.undo(now())
    fun pickSeason(season: Int) = engine.pickSeason(season)

    val currentSeries: SeriesInfo? get() = engine.currentSeries
    val currentSeason: Int? get() = engine.currentSeason

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun buildInput(): SkipInput {
        val count = player.mediaItemCount
        val titles = ArrayList<String>(count)
        for (i in 0 until count) {
            titles.add(player.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Видео ${i + 1}")
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        return SkipInput(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            connected = true,
            playback = PlaybackInfo(
                title = player.mediaMetadata.title?.toString() ?: "",
                movieTitle = movieTitle(),
                positionMs = player.currentPosition,
                durationMs = duration,
                isPlaying = player.isPlaying,
                currentIndex = player.currentMediaItemIndex
            ),
            playlist = titles,
            nowMs = now(),
            allowRecap = prefs.getBoolean(KEY_RECAP, true),
            allowHold = prefs.getBoolean(KEY_HOLD, true)
        )
    }

    companion object {
        private const val TAG = "WledSkip"
        private const val FAST_TICK_MS = 250L
        private const val SLOW_TICK_MS = 500L

        // Ключи настроек (экран настроек)
        const val KEY_ENABLED = "skip_enabled"
        const val KEY_RECAP = "skip_recap"
        const val KEY_HOLD = "skip_hold"
    }
}
