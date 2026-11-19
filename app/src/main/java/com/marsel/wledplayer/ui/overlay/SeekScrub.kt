package com.marsel.wledplayer.ui.overlay

/** Шаг перемотки как у стандартной полосы Media3: одно нажатие - 1/20 длительности, но не меньше секунды. */
object SeekScrub {
    const val PARTS = 20
    private const val MIN_STEP_MS = 1_000L

    fun step(durationMs: Long): Long = (durationMs / PARTS).coerceAtLeast(MIN_STEP_MS)

    /** Куда доедет полоса после нажатия: dir = -1 или 1, результат остаётся внутри видео. */
    fun next(baseMs: Long, dir: Int, durationMs: Long): Long =
        (baseMs + dir * step(durationMs)).coerceIn(0L, durationMs.coerceAtLeast(0L))
}
