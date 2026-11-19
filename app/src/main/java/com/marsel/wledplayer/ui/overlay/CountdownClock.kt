package com.marsel.wledplayer.ui.overlay

import kotlin.math.abs

/**
 * Плавный прогресс отсчёта. Движок присылает его 4 раза в секунду, а отсчёт всегда идёт ровно totalMs,
 * поэтому между значениями полоску двигаем по часам сами. Новое значение подстраивает часы, только если
 * разошлись сильно (отсчёт начался заново или перемотали).
 */
class CountdownClock(private val totalMs: Long, private val resyncGap: Float = 0.12f) {
    private var base = 0f
    private var baseAt = 0L
    var running = false
        private set

    /** progress > 0 и active: идёт отсчёт. Иначе стоим на присланном значении. */
    fun update(progress: Float, active: Boolean, nowMs: Long) {
        val p = progress.coerceIn(0f, 1f)
        if (!active || p <= 0f) {
            running = false
            base = p
            return
        }
        if (!running || abs(value(nowMs) - p) > resyncGap) {
            base = p
            baseAt = nowMs
        }
        running = true
    }

    fun value(nowMs: Long): Float =
        if (running) (base + (nowMs - baseAt).toFloat() / totalMs).coerceIn(0f, 1f) else base
}
