package com.marsel.wledplayer

import android.app.Activity
import android.content.Intent
import androidx.annotation.OptIn
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

            // 3. ПРИМЕНЯЕМ ЗАГОЛОВКИ ОТ ВОКИНО ИЛИ ПАРСЕРА
            if (!headers.isNullOrEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }

        val dataSourceFactory = DefaultDataSource.Factory(activity, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

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
            .build()

        return ExoPlayer.Builder(activity)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
    }

    /** Загружает плейлист в плеер и, если попросили, готовит возврат результата. */
    fun start(player: ExoPlayer, playlist: IncomingPlaylist) {
        player.setMediaItems(playlist.mediaItems, playlist.startIndex, playlist.startPositionMs)
        player.prepare()
        player.play()

        if (playlist.returnResult) {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        finishWithResult(player, Activity.RESULT_OK, "playback_completion", includePosition = false)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    finishWithResult(player, Activity.RESULT_FIRST_USER, "error", includePosition = true)
                }
            })
        }
    }

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
}