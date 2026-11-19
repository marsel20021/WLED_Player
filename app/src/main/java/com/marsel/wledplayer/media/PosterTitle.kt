package com.marsel.wledplayer.media

/** Что именно искать: очищенное название и (если удалось определить) год. */
data class PosterQuery(val title: String, val year: Int?)

/** Одна статья Википедии из результатов поиска. */
data class WikiHit(
    val title: String,
    val imageUrl: String?,
    val width: Int,
    val height: Int,
    val index: Int
)

/** Один фильм из ответа Кинопоиска. */
data class KpFilm(
    val nameRu: String?,
    val nameEn: String?,
    val year: Int?,
    val posterUrl: String?
)

/**
 * Чистая логика (без Android): превращает то, что прислал плеер ("Название (2019) 1080p WEB-DL",
 * "Серия 1" и т.п.), в строку для поиска постера, и выбирает подходящий результат поиска.
 */
object PosterTitle {

    private val placeholders = setOf(
        "без названия",
        "ничего не воспроизводится",
        "wled remote",
        "подключение к тв",
        "подключено к тв"
    )

    // "Серия 1", "Видео 3", "1 серия", "12". Голое четырёхзначное число ("2012", "1917") - это название фильма
    private val placeholderRegex = Regex(
        "^(?:(?:видео|серия|эпизод|episode|ep|часть|part|сезон|season|трейлер|trailer)[\\s.#№]*\\d{1,4}|\\d{1,3})" +
            "(?:[\\s.]*(?:серия|эпизод|episode|сезон|season))?$",
        RegexOption.IGNORE_CASE
    )
    private val sxeRegex = Regex("^s\\d{1,2}\\s*e\\d{1,3}$", RegexOption.IGNORE_CASE)

    private val extRegex = Regex("\\.(mkv|mp4|avi|mov|m4v|ts|webm)$", RegexOption.IGNORE_CASE)
    private val bracketYearRegex = Regex("[(\\[]\\s*((?:19|20)\\d{2})\\s*[)\\]]")
    private val squareRegex = Regex("\\[[^\\]]*]")
    private val roundRegex = Regex("\\([^)]*\\)")
    private val bareYearRegex = Regex("\\s((?:19|20)\\d{2})(?=\\s|$)")
    private val qualityRegex = Regex(
        "\\b(?:2160p|1080p|1080i|720p|480p|4k|uhd|hdr10?|hdr|bdrip|bdremux|remux|web-?dl|web-?rip|hdrip|hdtv|" +
            "dvdrip|dvd|blu-?ray|x264|x265|hevc|h\\.?264|h\\.?265|avc|dub|dubbed|hd|sd|ts|cam)\\b",
        RegexOption.IGNORE_CASE
    )
    private val episodeTailRegex = Regex(
        "[\\s\\-–—:,.]*(?:\\d+\\s*(?:-?й\\s*)?сезон.*|сезон\\s*\\d+.*|\\d+\\s*(?:-?я\\s*)?серия.*|серия\\s*\\d+.*|" +
            "s\\d{1,2}\\s*e\\d{1,3}.*|season\\s*\\d+.*|episode\\s*\\d+.*)$",
        RegexOption.IGNORE_CASE
    )
    // Только "хвост" про серию: "Дандадан - Серия 1" -> "Дандадан". Сезон не трогаем: "Дандадан 2 сезон" остаётся как есть
    private val episodeOnlyTailRegex = Regex(
        "[\\s\\-–—:,.]*(?:\\d+\\s*(?:-?я\\s*)?серия.*|серия\\s*\\d+.*|эпизод\\s*\\d+.*|" +
            "s\\d{1,2}\\s*e\\d{1,3}.*|episode\\s*\\d+.*)$",
        RegexOption.IGNORE_CASE
    )
    private val spacesRegex = Regex("\\s+")
    private val nonWordRegex = Regex("[^\\p{L}\\p{N}\\s]")

    private const val MAX_YEAR = 2030

    /** true, если это не название фильма, а служебная строка или "Серия 1". */
    fun isPlaceholder(raw: String?): Boolean {
        val s = raw?.trim()?.lowercase() ?: return true
        if (s.isEmpty()) return true
        if (s in placeholders) return true
        return placeholderRegex.matches(s) || sxeRegex.matches(s)
    }

    /**
     * Название фильма для показа на экране и в плашке без "хвоста" серии.
     * Плеер Vokino присылает "Дандадан - Серия 1", а серия показывается отдельной строкой.
     * Если после обрезки ничего осмысленного не осталось - возвращаем как есть.
     */
    fun cleanMovieTitle(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cut = s.replace(episodeOnlyTailRegex, "").trim(' ', '-', '–', '—', ':', ',', '.', '|', '/')
        return if (cut.length >= 2 && !isPlaceholder(cut)) cut else s
    }

    /** null, если искать нечего. */
    fun parse(raw: String?): PosterQuery? {
        if (raw.isNullOrBlank() || isPlaceholder(raw)) return null

        var s = raw.trim().replace(extRegex, "")
        // Имя файла вида "The.Movie.2019.1080p": точки и подчёркивания вместо пробелов
        s = if (!s.contains(' ')) s.replace('.', ' ').replace('_', ' ') else s.replace('_', ' ')

        var year: Int? = bracketYearRegex.find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= MAX_YEAR }

        s = s.replace(squareRegex, " ").replace(roundRegex, " ")
        // После " / " обычно идёт оригинальное название - берём первое
        s = s.split(" / ", " | ").first()

        // Голый год в конце названия ("Фильм 2019 1080p"): всё после года отбрасываем
        val m = bareYearRegex.find(s)
        if (m != null) {
            val y = m.groupValues[1].toInt()
            if (y <= MAX_YEAR && m.range.first > 1) {
                if (year == null) year = y
                s = s.substring(0, m.range.first)
            }
        }

        s = s.replace(qualityRegex, " ")
        s = s.replace(episodeTailRegex, "")
        s = s.replace(spacesRegex, " ").trim(' ', '-', '–', '—', ':', ',', '.', '|', '/')

        if (s.length < 2 || isPlaceholder(s)) return null
        return PosterQuery(s, year)
    }

    /** Нижний регистр, ё->е, без знаков препинания. */
    fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace(nonWordRegex, " ").replace(spacesRegex, " ").trim()

    /** Подходит ли название найденной статьи/фильма под запрос. */
    fun matches(queryNorm: String, candidateTitle: String?): Boolean {
        if (candidateTitle.isNullOrBlank() || queryNorm.isEmpty()) return false
        val base = normalize(candidateTitle.substringBefore(" ("))
        if (base.isEmpty()) return false
        if (base == queryNorm) return true
        if (queryNorm.length >= 4 && base.startsWith(queryNorm)) return true
        if (base.length >= 5 && queryNorm.startsWith(base) && base.length * 10 >= queryNorm.length * 7) return true
        return false
    }

    /** Из результатов поиска Википедии берём первую подходящую статью с вертикальной картинкой (постером). */
    fun pickWikiHit(hits: List<WikiHit>, query: PosterQuery): WikiHit? {
        val q = normalize(query.title)
        return hits.sortedBy { it.index }.firstOrNull { h ->
            h.imageUrl != null && h.height > h.width && matches(q, h.title)
        }
    }

    /** Из результатов Кинопоиска берём фильм с подходящим названием (с тем же годом, если он известен). */
    fun pickKinopoisk(films: List<KpFilm>, query: PosterQuery): KpFilm? {
        val q = normalize(query.title)
        val ok = films.filter { it.posterUrl != null && (matches(q, it.nameRu) || matches(q, it.nameEn)) }
        if (ok.isEmpty()) return null
        val year = query.year
        return (if (year != null) ok.firstOrNull { it.year == year } else null) ?: ok.first()
    }
}
