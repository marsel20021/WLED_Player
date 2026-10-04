package com.marsel.wledplayer

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log

/**
 * "Умный звук": компрессор + лимитер (не просто буст громкости), чтобы разные
 * фильмы с разной "громкостью мастеринга" звучали на похожем уровне.
 * context пока не используется движком эффекта напрямую (ему достаточно
 * audioSessionId), но оставлен в конструкторе для совместимости с местом
 * вызова в MainActivity - предупреждение IDE об этом безвредно.
 */
class SmartAudioController(private val context: Context) {

    private var dynamicsProcessing: DynamicsProcessing? = null

    companion object {
        private const val TAG = "SmartAudio"
        private const val ATTACK_MS = 15f
        private const val RELEASE_MS = 250f
        private const val RATIO = 4f
        private const val THRESHOLD_DB = -30f
        private const val KNEE_WIDTH_DB = 10f
        private const val NOISE_GATE_DB = -80f
        private const val MAKEUP_GAIN_DB = 6f
    }

    fun attachTo(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || audioSessionId == 0) return

        release() // не плодим второй эффект поверх уже висящего

        try {
            val dp = DynamicsProcessing(audioSessionId)

            val mbcBandCount = dp.config.mbcBandCount
            for (band in 0 until mbcBandCount) {
                val existing = dp.getMbcBandByChannelIndex(0, band)
                val tuned = DynamicsProcessing.MbcBand(
                    true,
                    existing.cutoffFrequency,
                    ATTACK_MS,
                    RELEASE_MS,
                    RATIO,
                    THRESHOLD_DB,
                    KNEE_WIDTH_DB,
                    NOISE_GATE_DB,
                    1f,
                    0f,
                    MAKEUP_GAIN_DB
                )
                dp.setMbcBandAllChannelsTo(band, tuned)
            }

            val existingLimiter = dp.getLimiterByChannelIndex(0)
            val tunedLimiter = DynamicsProcessing.Limiter(
                true,
                true,
                existingLimiter.linkGroup,
                5f,
                60f,
                10f,
                -1f,
                0f
            )
            dp.setLimiterAllChannelsTo(tunedLimiter)

            dp.enabled = true
            dynamicsProcessing = dp
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось включить умный звук", e)
        }
    }

    fun release() {
        dynamicsProcessing?.let {
            try { it.release() } catch (e: Exception) { Log.w(TAG, "Ошибка при отключении умного звука", e) }
        }
        dynamicsProcessing = null
    }
}