package com.marsel.wledplayer.skip

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Одна запись таблицы поправок. seasons = null: сезоны берём из Кинопоиска как обычно. */
data class SeriesOverride(
    val kinopoiskId: Int,
    val names: List<String>,
    val seasons: Map<Int, Int>?
) {
    /** Подпись записи: меняется, когда её поправили. Нужна для ключа кэша. */
    val sig: String = kinopoiskId.toString() + (seasons?.entries?.joinToString(",", "|") { "${it.key}=${it.value}" } ?: "")
}

/**
 * Таблица поправок. JSON:
 *   {"series": [{"id": 1049731, "names": ["Чёрный клевер", "Black Clover"], "seasons": [170, 13], "note": "для себя"}]}
 * seasons и note не обязательны. Сломанная запись пропускается, остальные работают.
 */
class OverrideTable private constructor(private val byName: Map<String, SeriesOverride>) {

    fun find(title: String): SeriesOverride? = byName[KinopoiskParser.norm(title)]

    /** Пустая строка, если для сериала поправки нет. */
    fun sig(title: String): String = find(title)?.sig ?: ""

    val size: Int get() = byName.values.toSet().size

    companion object {
        val EMPTY = OverrideTable(emptyMap())

        /** Встроенная копия: работает, пока таблицу ещё не скачали. */
        val BUILT_IN = listOf(
            SeriesOverride(1049731, listOf("Чёрный клевер", "Black Clover"), mapOf(1 to 170, 2 to 13))
        )

        /** Если названия повторяются, побеждает запись ниже. */
        fun of(entries: List<SeriesOverride>): OverrideTable {
            val map = LinkedHashMap<String, SeriesOverride>()
            for (o in entries) for (name in o.names) map[KinopoiskParser.norm(name)] = o
            return OverrideTable(map)
        }

        /** null - текст не похож на таблицу (не JSON или нет списка series). */
        fun parse(text: String): List<SeriesOverride>? {
            val root = try {
                JSONObject(text.removePrefix("\uFEFF").trim())
            } catch (e: JSONException) {
                return null
            }
            val arr = root.optJSONArray("series") ?: return null
            val out = ArrayList<SeriesOverride>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseEntry(o)?.let { out.add(it) }
            }
            return out
        }

        private fun parseEntry(o: JSONObject): SeriesOverride? {
            val id = o.optInt("id", 0)
            if (id < 1) return null
            val names = ArrayList<String>()
            val namesArr = o.optJSONArray("names") ?: return null
            for (i in 0 until namesArr.length()) {
                val n = namesArr.optString(i, "").trim()
                if (KinopoiskParser.norm(n).isNotEmpty()) names.add(n)
            }
            if (names.isEmpty()) return null
            val seasonsArr = o.optJSONArray("seasons")
            var seasons: Map<Int, Int>? = null
            if (seasonsArr != null && seasonsArr.length() > 0) {
                val m = LinkedHashMap<Int, Int>()
                for (i in 0 until seasonsArr.length()) {
                    val n = seasonsArr.optInt(i, 0)
                    if (n < 1) return null
                    m[i + 1] = n
                }
                seasons = m
            }
            return SeriesOverride(id, names, seasons)
        }
    }
}

/**
 * Берёт таблицу по ссылке и хранит последнюю удачную копию.
 * Всегда включает встроенную копию; записи из файла идут после неё и главнее.
 */
class OverridesSource(
    private val url: String,
    private val store: KeyValueStore,
    private val fetch: (String) -> SkipHttpResponse = { SkipHttp.get(it) }
) {
    @Volatile
    var table: OverrideTable = build(store.get(KEY))
        private set

    /** Скачивает файл. Не вызывать с главного потока. true, если файл принят. */
    fun refresh(): Boolean {
        if (url.isBlank()) return false
        val resp = try {
            fetch(url)
        } catch (e: IOException) {
            return false
        }
        if (resp.code !in 200..299) return false
        val body = resp.body
        // Страница с ошибкой или мусор: старую копию не трогаем
        val entries = OverrideTable.parse(body) ?: return false
        store.put(KEY, body)
        table = OverrideTable.of(OverrideTable.BUILT_IN + entries)
        return true
    }

    private companion object {
        const val KEY = "overrides_text"

        fun build(saved: String?): OverrideTable =
            OverrideTable.of(OverrideTable.BUILT_IN + (saved?.let { OverrideTable.parse(it) } ?: emptyList()))
    }
}
