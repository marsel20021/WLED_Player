package com.marsel.wledplayer.skip

/** Серия, как она названа в тексте. season = null, если сезон в тексте не указан. */
data class EpisodeRef(val season: Int?, val episode: Int)

/** Серия после проверки по сезонам Кинопоиска. converted = номер был сквозным, мы его пересчитали (это догадка). */
data class ResolvedEpisode(val season: Int, val episode: Int, val converted: Boolean)

/**
 * Достаёт сезон и номер серии из названий, которые присылает Vokino ("3 серия", "S01E05", "Сезон 2 Серия 7").
 * Чистая логика без Android, чтобы её можно было проверить обычными тестами.
 * Если номер явно не написан, возвращаем null: угадывать по месту в плейлисте мы не будем.
 */
object EpisodeParser {
    private val opt = RegexOption.IGNORE_CASE
    private const val NOT_WORD = "(?<![\\p{L}\\p{N}])"
    private const val NOT_WORD_AFTER = "(?![\\p{L}\\p{N}])"

    private val sxe = Regex(NOT_WORD + "s(\\d{1,2})\\s*[.\\-_ ]?\\s*e(\\d{1,3})" + NOT_WORD_AFTER, opt)

    // "2 сезон", "2-й сезон", "сезон 2", "season 2", "2nd season"
    private val seasonNumFirst = Regex("(?<![\\d.])(\\d{1,2})\\s*-?\\s*(?:й\\s*)?сезон", opt)
    private val seasonWordFirst = Regex(NOT_WORD + "(?:сезон|season)\\s*[#№.:]?\\s*(\\d{1,2})(?!\\d)", opt)
    private val seasonEnglish = Regex("(?<![\\d.])(\\d{1,2})(?:st|nd|rd|th)\\s*season", opt)

    private val episodeNumFirst = Regex("(?<![\\d.])(\\d{1,3})\\s*-?\\s*(?:я\\s*)?сери[яиюе]", opt)
    private val episodeWordFirst = Regex(NOT_WORD + "(?:сери[яиюе]|эпизод|episode|ep\\.?)\\s*[#№.:]?\\s*(\\d{1,3})(?!\\d)", opt)
    private val bareNumber = Regex("^\\s*(\\d{1,3})\\s*$")

    private val seasonPhrases = listOf(seasonNumFirst, seasonWordFirst, seasonEnglish)

    /** Сезон, если он где-то написан. */
    fun findSeason(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        for (re in seasonPhrases) {
            val n = re.find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n >= 1) return n
        }
        return null
    }

    /** Название без слов про сезон: "Дандадан 2 сезон" -> "Дандадан". */
    fun stripSeason(text: String): String {
        var s = text
        for (re in seasonPhrases) s = re.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', ',', '.')
    }

    /** Серия из названия серии. null, если номер явно не указан. */
    fun parse(episodeTitle: String?): EpisodeRef? {
        val text = episodeTitle?.trim().orEmpty()
        if (text.isEmpty()) return null

        sxe.find(text)?.let { m ->
            val s = m.groupValues[1].toIntOrNull()
            val e = m.groupValues[2].toIntOrNull()
            if (s != null && e != null && s >= 1 && e >= 1) return EpisodeRef(s, e)
        }

        val season = findSeason(text)
        // Слова про сезон вырезаем, иначе "Сезон 1 Серия 3" прочитается как серия 1
        var rest = text
        for (re in seasonPhrases) rest = re.replace(rest, " ")

        val episode = episodeNumFirst.find(rest)?.groupValues?.get(1)?.toIntOrNull()
            ?: episodeWordFirst.find(rest)?.groupValues?.get(1)?.toIntOrNull()
            ?: bareNumber.find(rest)?.groupValues?.get(1)?.toIntOrNull()
        if (episode == null || episode < 1) return null
        return EpisodeRef(season, episode)
    }
}

/** Сопоставляет номер серии с сезонами, которые знает Кинопоиск. */
object SeasonMath {
    /**
     * counts: сколько серий в каждом сезоне по Кинопоиску (сезон -> число серий).
     * Номер в пределах сезона берём как есть. Если больше, пробуем считать его сквозным (так нумеруют аниме)
     * и вычитаем серии прошлых сезонов. Если у сезона неизвестно число серий, не угадываем: null.
     */
    fun resolve(season: Int, episode: Int, counts: Map<Int, Int>): ResolvedEpisode? {
        if (season < 1 || episode < 1) return null
        val inSeason = counts[season] ?: return null
        if (inSeason <= 0) return null
        if (episode <= inSeason) return ResolvedEpisode(season, episode, converted = false)

        var offset = 0
        for (s in 1 until season) {
            val c = counts[s] ?: return null
            if (c <= 0) return null
            offset += c
        }
        val local = episode - offset
        return if (local in 1..inSeason) ResolvedEpisode(season, local, converted = true) else null
    }
}
