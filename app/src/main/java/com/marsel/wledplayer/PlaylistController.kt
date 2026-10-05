package com.marsel.wledplayer

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Вся логика плейлистов вынесена сюда, чтобы не раздувать MainActivity.
 */
@OptIn(UnstableApi::class)
class PlaylistController(private val activity: Activity) {

    /** Собирает ExoPlayer, подставляя headers (если пришли) и размер буфера для 4K. */
    fun buildPlayer(headers: Map<String, String>?, bufferSeconds: Int = 50): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory().apply {

            // 1. ПРИТВОРЯЕМСЯ БРАУЗЕРОМ, ЧТОБЫ ИЗБЕЖАТЬ ОШИБКИ 403 (Forbidden)
            setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

            // 2. РАЗРЕШАЕМ РЕДИРЕКТЫ МЕЖДУ HTTP И HTTPS (ИСПРАВЛЯЕТ ОШИБКУ 302)
            setAllowCrossProtocolRedirects(true)

            // Потоки из торрентов бывают медленными: ждём ответа дольше стандартных 8 секунд
            setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            setReadTimeoutMs(READ_TIMEOUT_MS)

            // 3. ПРИМЕНЯЕМ ЗАГОЛОВКИ ОТ ВОКИНО ИЛИ ПАРСЕРА
            if (!headers.isNullOrEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }

        val dataSourceFactory = DefaultDataSource.Factory(activity, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy())

        // НАСТРАИВАЕМ КАСТОМНЫЙ РАЗМЕР БУФЕРА
        val minBufferMs = bufferSeconds * 1000
        val maxBufferMs = minBufferMs * 2
        val bufferForPlaybackMs = 2500
        val bufferForPlaybackAfterRebufferMs = 5000

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            // Держим в памяти последние секунды уже просмотренного: перемотка «назад на 5 с»
            // идёт из памяти и не заставляет заново открывать поток на сервере
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()

        return ExoPlayer.Builder(activity)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
    }

    /** Загружает плейлист в плеер, запускает воспроизведение и следит за ошибками. */
    fun start(player: ExoPlayer, playlist: IncomingPlaylist) {
        player.setMediaItems(playlist.mediaItems, playlist.startIndex, playlist.startPositionMs)
        player.prepare()
        player.play()

        player.addListener(object : Player.Listener {
            private var retries = 0
            private var lastErrorAt = 0L

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retries = 0
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && playlist.returnResult) {
                    finishWithResult(player, Activity.RESULT_OK, "playback_completion", includePosition = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Если с прошлой ошибки прошло много времени, считаем попытки заново
                val now = SystemClock.elapsedRealtime()
                if (now - lastErrorAt > RETRY_RESET_MS) retries = 0
                lastErrorAt = now

                if (isNetworkError(error) && retries < MAX_RECOVERY_RETRIES) {
                    retries++
                    Log.w(TAG, "Поток прервался, переподключаюсь ($retries из $MAX_RECOVERY_RETRIES)", error)
                    Toast.makeText(activity, "Поток прервался, переподключаюсь…", Toast.LENGTH_SHORT).show()
                    player.prepare() // продолжит с того же места
                } else if (playlist.returnResult) {
                    finishWithResult(player, Activity.RESULT_FIRST_USER, "error", includePosition = true)
                }
            }
        })
    }

    /** Ошибки ввода-вывода (обрыв сети, сервер не ответил) исправимы повторной попыткой. */
    private fun isNetworkError(error: PlaybackException): Boolean =
        error.errorCode in IO_ERROR_CODES

    /** Вызывать при ручном закрытии (кнопка «назад»). */
    fun finishOnUserExit(player: ExoPlayer?, returnResult: Boolean) {
        if (returnResult && player != null) {
            finishWithResult(player, Activity.RESULT_OK, "user", includePosition = true)
        } else {
            activity.finish()
        }
    }

    private fun finishWithResult(player: ExoPlayer, resultCode: Int, endBy: String, includePosition: Boolean) {
        val resultIntent = Intent().apply {
            data = player.currentMediaItem?.localConfiguration?.uri
            putExtra("end_by", endBy)
            if (includePosition) {
                putExtra("position", player.currentPosition.toInt())
                putExtra("duration", player.duration.coerceAtLeast(0).toInt())
            }
        }
        activity.setResult(resultCode, resultIntent)
        activity.finish()
    }

    private companion object {
        const val TAG = "PlaylistController"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BACK_BUFFER_MS = 15_000 // 0 вернёт прежнее поведение (ничего не хранить позади)
        const val MAX_RECOVERY_RETRIES = 3
        const val RETRY_RESET_MS = 120_000L
        val IO_ERROR_CODES = 2000..2999 // коды PlaybackException.ERROR_CODE_IO_*
    }
}
