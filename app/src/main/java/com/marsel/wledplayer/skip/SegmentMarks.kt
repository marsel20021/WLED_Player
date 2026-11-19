package com.marsel.wledplayer.skip

/** Участок полосы времени, который можно пропустить: доли длительности от 0 до 1. */
data class SegmentMark(val startFraction: Float, val endFraction: Float)

object SegmentMarks {
    /** Участки для подсветки на полосе времени. Кривые данные отбрасываются теми же правилами, что и при пропуске. */
    fun of(timeline: SkipTimeline?, durationMs: Long): List<SegmentMark> {
        if (timeline == null || durationMs <= 0) return emptyList()
        val out = ArrayList<SegmentMark>()
        for (seg in timeline.segments) {
            if (!SkipRules.isUsable(seg, durationMs)) continue
            val end = minOf(seg.endMs ?: durationMs, durationMs)
            if (end <= seg.startMs) continue
            out.add(SegmentMark(seg.startMs.toFloat() / durationMs, end.toFloat() / durationMs))
        }
        return out
    }
}
