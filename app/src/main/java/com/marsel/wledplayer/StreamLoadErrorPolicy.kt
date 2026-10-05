package com.marsel.wledplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Политика повторов при ошибках загрузки потока.
 *
 * Стандартная политика ExoPlayer считает ошибку «позиция вне диапазона» окончательной и
 * сразу сдаётся. Но серверы, раздающие торренты (TorrServer и подобные), иногда отвечают
 * так временно: не отдали нужный кусок файла, оборвали соединение. Поэтому такие ошибки
 * мы повторяем несколько раз с растущей паузой, а остальные ошибки обрабатываются как
 * обычно, только с большим числом попыток.
 */
@OptIn(UnstableApi::class)
class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(MIN_RETRIES) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val delay = super.getRetryDelayMsFor(loadErrorInfo)
        if (delay != C.TIME_UNSET) return delay

        // Стандартная политика отказалась повторять. Для «позиции вне диапазона» делаем исключение.
        val outOfRange = DataSourceException.isCausedByPositionOutOfRange(loadErrorInfo.exception)
        if (outOfRange && loadErrorInfo.errorCount <= MAX_OUT_OF_RANGE_RETRIES) {
            return minOf(loadErrorInfo.errorCount * 1000L, MAX_DELAY_MS)
        }
        return C.TIME_UNSET
    }

    private companion object {
        const val MIN_RETRIES = 10
        const val MAX_OUT_OF_RANGE_RETRIES = 8
        const val MAX_DELAY_MS = 5000L
    }
}
