package com.marsel.wledplayer.skip

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Числа и проверки, которые защищают от кривых данных. */
object SkipRules {
    const val COUNTDOWN_MS = 3_000L         // сколько секунд идёт отсчёт перед пропуском
    const val SETTLE_MS = 2_500L            // после смены серии позицию не трогаем: первое состояние может быть от прошлой серии
    const val QUIET_MS = 2_500L             // после нашего прыжка не показываем ничего: плеер ещё присылает старую позицию
    const val MIN_REMAINING_MS = 3_000L     // если до конца отрезка меньше, прыгать уже незачем
    const val MAX_INTRO_MS = 300_000L       // заставка или пересказ дольше 5 минут - явная ошибка в данных
    const val END_MARGIN_MS = 1_000L
    const val RESTART_BELOW_MS = 5_000L     // серию запустили с начала
    const val RESTART_AFTER_MS = 60_000L    // ...хотя до этого мы были уже далеко
    const val SEEK_JUMP_MS = 2_000L         // позиция ушла от обычного хода больше чем на это - человек перемотал
    const val BUTTON_DELAY_MS = 1_000L      // кнопка без отсчёта появляется, когда после перемотки прошла секунда

    fun isUsable(seg: SkipSegment, durationMs: Long): Boolean {
        if (seg.startMs < 0) return false
        val end = seg.endMs
        return when (seg.kind) {
            SkipKind.INTRO, SkipKind.RECAP -> {
                if (end == null || end <= seg.startMs) {
                    false
                } else if (end - seg.startMs > MAX_INTRO_MS) {
                    false
                } else {
                    durationMs <= 0 || seg.startMs <= durationMs / 2
                }
            }
            SkipKind.CREDITS -> {
                if (durationMs <= 0) {
                    false
                } else if (end != null && end <= seg.startMs) {
                    false
                } else {
                    seg.startMs >= durationMs / 2
                }
            }
        }
    }
}

/**
 * Снимок того, что сейчас играет. nowMs - монотонное время телефона.
 * allowRecap: пропускать ли пересказ. allowHold: держать ли кнопку внутри отрезка после перемотки/пропуска.
 */
data class SkipSnapshot(
    val episodeKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val connected: Boolean,
    val hasNextEpisode: Boolean,
    val nowMs: Long,
    val allowRecap: Boolean = true,
    val allowHold: Boolean = true
)

/**
 * Решает, что показать и когда прыгать. Без Android, чтобы проверить обычными тестами.
 * Автоскип каждого отрезка срабатывает один раз за серию; повторный просмотр с начала это сбрасывает.
 * Пока человек внутри отрезка, кнопка остаётся (после первого раза уже без отсчёта).
 */
class SkipDecider {
    private var key: String? = null
    private var timeline: SkipTimeline? = null
    private var keyChangedAt = 0L
    private var quietUntil = 0L
    private var maxPosition = 0L
    private val done = HashSet<SkipKind>()
    private var countdownKind: SkipKind? = null
    private var countdownStart = 0L
    private var offeredKind: SkipKind? = null
    private var lastPos = -1L
    private var lastPosAt = 0L
    private var lastSeekAt = NEVER

    /** Данные для серии готовы (или их нет: timeline = null). Ответ для чужой серии игнорируем. */
    fun setTimeline(forKey: String, timeline: SkipTimeline?) {
        if (forKey == key) this.timeline = timeline
    }

    fun update(s: SkipSnapshot): SkipStep {
        if (s.episodeKey != key) reset(s)
        val jumped = trackJump(s)
        if (!s.connected) return idle()
        val tl = timeline ?: return idle()
        if (s.nowMs - keyChangedAt < SkipRules.SETTLE_MS) return idle()
        if (s.nowMs < quietUntil) return idle()

        if (s.positionMs < SkipRules.RESTART_BELOW_MS && maxPosition > SkipRules.RESTART_AFTER_MS) {
            done.clear()
            maxPosition = 0L
        }
        maxPosition = max(maxPosition, s.positionMs)

        val seg = activeSegment(tl, s)
        if (jumped) {
            // Человек сам перемотал: во время отсчёта он отменяется, а в отрезок, куда попали, автоскип уже не пойдёт
            lastSeekAt = s.nowMs
            countdownKind = null
            if (seg != null) done.add(seg.kind)
        }
        if (seg == null) return idle()
        offeredKind = seg.kind

        if (seg.kind in done) {
            // Автоскипа нет, но кнопка остаётся, пока мы внутри. Пока человек мотает, её не показываем
            countdownKind = null
            if (!s.allowHold) return idle()
            if (s.nowMs - lastSeekAt < SkipRules.BUTTON_DELAY_MS) return idle()
            return SkipStep(SkipOffer(seg.kind, 0f, 0, manual = true), null)
        }
        if (!s.isPlaying) {
            // Пауза: кнопка остаётся, отсчёт обнуляется и пойдёт заново после продолжения
            countdownKind = null
            return SkipStep(SkipOffer(seg.kind, 0f, secondsLeft(SkipRules.COUNTDOWN_MS)), null)
        }
        if (countdownKind != seg.kind) {
            countdownKind = seg.kind
            countdownStart = s.nowMs
        }
        val elapsed = s.nowMs - countdownStart
        if (elapsed < SkipRules.COUNTDOWN_MS) {
            val progress = (elapsed.toFloat() / SkipRules.COUNTDOWN_MS).coerceIn(0f, 1f)
            return SkipStep(SkipOffer(seg.kind, progress, secondsLeft(SkipRules.COUNTDOWN_MS - elapsed)), null)
        }
        return SkipStep(null, fire(seg, tl, s))
    }

    /** Человек нажал кнопку. Возвращает команду, если сейчас есть что пропускать. */
    fun skipNow(s: SkipSnapshot): SkipFire? {
        if (s.episodeKey != key || !s.connected) return null
        val tl = timeline ?: return null
        val seg = activeSegment(tl, s) ?: return null
        return fire(seg, tl, s)
    }

    /** Человек нажал ✕: автоскипа для этого отрезка в этой серии больше не будет, но кнопка остаётся. */
    fun cancel() {
        offeredKind?.let { done.add(it) }
        countdownKind = null
        offeredKind = null
    }

    private fun secondsLeft(ms: Long): Int = max(1, ceil(ms / 1000.0).toInt())

    private fun reset(s: SkipSnapshot) {
        key = s.episodeKey
        timeline = null
        keyChangedAt = s.nowMs
        quietUntil = 0L
        maxPosition = 0L
        done.clear()
        countdownKind = null
        offeredKind = null
        lastPos = -1L
        lastSeekAt = NEVER
    }

    /**
     * Замечает перемотку: позиция изменилась не так, как идёт воспроизведение. Свои прыжки, первые секунды
     * после смены серии и паузу не считаем. Работает одинаково для пульта и для родного пульта ТВ.
     */
    private fun trackJump(s: SkipSnapshot): Boolean {
        val moved = s.positionMs != lastPos
        var jumped = false
        if (lastPos >= 0 && moved) {
            val expected = lastPos + if (s.isPlaying) s.nowMs - lastPosAt else 0L
            val calm = s.nowMs - keyChangedAt >= SkipRules.SETTLE_MS && s.nowMs >= quietUntil
            jumped = calm && abs(s.positionMs - expected) > SkipRules.SEEK_JUMP_MS
        }
        if (moved || lastPos < 0 || !s.isPlaying) {
            lastPos = s.positionMs
            lastPosAt = s.nowMs
        }
        return jumped
    }

    private fun idle(): SkipStep {
        countdownKind = null
        offeredKind = null
        return SkipStep(null, null)
    }

    private fun activeSegment(tl: SkipTimeline, s: SkipSnapshot): SkipSegment? {
        for (kind in SkipKind.values()) {
            // На последней серии титры не пропускаем: непонятно, что сделает ТВ в конце плейлиста
            if (kind == SkipKind.CREDITS && !s.hasNextEpisode) continue
            if (kind == SkipKind.RECAP && !s.allowRecap) continue
            val seg = tl.segments.firstOrNull { it.kind == kind && SkipRules.isUsable(it, s.durationMs) } ?: continue
            val end = effectiveEnd(seg, s.durationMs) ?: continue
            if (s.positionMs >= seg.startMs && end - s.positionMs > SkipRules.MIN_REMAINING_MS) {
                return seg
            }
        }
        return null
    }

    private fun effectiveEnd(seg: SkipSegment, durationMs: Long): Long? {
        val end = seg.endMs ?: return if (durationMs > 0) durationMs else null
        return if (durationMs > 0) min(end, durationMs) else end
    }

    private fun fire(seg: SkipSegment, tl: SkipTimeline, s: SkipSnapshot): SkipFire {
        done.add(seg.kind)
        countdownKind = null
        offeredKind = null
        quietUntil = s.nowMs + SkipRules.QUIET_MS
        return when (seg.kind) {
            SkipKind.INTRO, SkipKind.RECAP ->
                SkipFire(seg.kind, SkipAction.SeekTo(seg.endMs ?: s.positionMs), seg.startMs)
            SkipKind.CREDITS -> creditsFire(seg, tl, s)
        }
    }

    private fun creditsFire(seg: SkipSegment, tl: SkipTimeline, s: SkipSnapshot): SkipFire {
        var target = seg.endMs
        val post = tl.postCreditsStartMs
        // Сцену после титров не перепрыгиваем: останавливаемся у её начала
        if (post != null && post > s.positionMs && (target == null || target > post)) target = post
        if (target != null) return SkipFire(seg.kind, SkipAction.SeekTo(target), seg.startMs)
        if (s.hasNextEpisode) return SkipFire(seg.kind, SkipAction.NextEpisode, null)
        val last = max(s.positionMs, s.durationMs - SkipRules.END_MARGIN_MS)
        return SkipFire(seg.kind, SkipAction.SeekTo(last), seg.startMs)
    }

    private companion object {
        const val NEVER = -1_000_000L
    }
}
