package com.marsel.wledplayer

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Движок подсветки: берёт картинку с экрана плеера и отправляет цвета на ленту WLED.
 * В плеере есть две реализации (классическая и v2), выбирает между ними AmbilightController.
 */
@OptIn(UnstableApi::class)
interface AmbilightEngine {

    /** Плеер, за которым следим (играет ли видео, HDR или нет). */
    fun attachPlayer(player: ExoPlayer)

    /** Какое окно с видео сейчас активно и является ли оно SurfaceView (4K/HDR) или TextureView. */
    fun updatePlayerView(view: PlayerView, isSurface: Boolean)

    fun start()

    /**
     * Полностью останавливает движок: потоки, сокет, захват кадров. Возвращается, только когда
     * движок точно остановлен, чтобы следующий мог безопасно начать отправку на ту же ленту.
     */
    fun release()
}
