package com.marsel.wledplayer.skip

enum class SkipKind { INTRO, RECAP, CREDITS }

/** Отрезок, который можно пропустить. endMs = null значит "до конца серии". votes - сколько людей это разметили. */
data class SkipSegment(
    val kind: SkipKind,
    val startMs: Long,
    val endMs: Long?,
    val votes: Int,
    val source: String
)

/**
 * Данные для одной серии.
 * postCreditsStartMs: где начинается сцена после титров (если её кто-то разметил), титры её не перепрыгивают.
 */
data class SkipTimeline(
    val segments: List<SkipSegment>,
    val postCreditsStartMs: Long? = null
)

/** Что нужно сделать на ТВ. */
sealed interface SkipAction {
    data class SeekTo(val ms: Long) : SkipAction
    data object NextEpisode : SkipAction
}

/**
 * Что показать на экране. Обычно идёт отсчёт: progress 0..1, secondsLeft - сколько секунд осталось.
 * manual = true: отсчёта нет, просто кнопка, пока человек внутри отрезка.
 */
data class SkipOffer(val kind: SkipKind, val progress: Float, val secondsLeft: Int, val manual: Boolean = false)

/** Команда на прыжок. returnToMs: куда вернуть по кнопке "Вернуть" (null - вернуть нельзя). */
data class SkipFire(val kind: SkipKind, val action: SkipAction, val returnToMs: Long?)

data class SkipStep(val offer: SkipOffer?, val fire: SkipFire?)
