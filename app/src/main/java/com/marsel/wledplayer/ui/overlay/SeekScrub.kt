package com.marsel.wledplayer.ui.overlay

/**
 * Шаг перемотки: нажатие - 5 секунд, если держать, шаг растёт.
 * repeat - сколько раз кнопка уже повторилась (KeyEvent.repeatCount), у первого нажатия 0.
 */
object SeekScrub {
    fun step(repeat: Int): Long = when {
        repeat < 10 -> 5_000L
        repeat < 25 -> 10_000L
        repeat < 60 -> 30_000L
        else -> 60_000L
    }

    /** Куда доедет полоса после нажатия: dir = -1 или 1, результат остаётся внутри видео. */
    fun next(baseMs: Long, dir: Int, repeat: Int, durationMs: Long): Long =
        (baseMs + dir * step(repeat)).coerceIn(0L, durationMs.coerceAtLeast(0L))
}
