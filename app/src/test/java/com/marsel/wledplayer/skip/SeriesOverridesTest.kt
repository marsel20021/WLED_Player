package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SeriesOverridesTest {

    private fun table(vararg entries: String): OverrideTable =
        OverrideTable.of(OverrideTable.parse(json(*entries))!!)

    private fun json(vararg entries: String) = """{"series": [${entries.joinToString(",")}]}"""

    @Test
    fun `запись с сезонами`() {
        val o = table("""{"id": 1049731, "names": ["Чёрный клевер"], "seasons": [170, 13]}""").find("Чёрный клевер")
        assertNotNull(o)
        assertEquals(1049731, o!!.kinopoiskId)
        assertEquals(mapOf(1 to 170, 2 to 13), o.seasons)
    }

    @Test
    fun `запись без сезонов - только поправка поиска`() {
        val o = table("""{"id": 123, "names": ["Какой-то сериал"]}""").find("Какой-то сериал")
        assertNotNull(o)
        assertNull(o!!.seasons)
        assertNull(table("""{"id": 123, "names": ["Сериал"], "seasons": []}""").find("Сериал")!!.seasons)
    }

    @Test
    fun `несколько названий, регистр и ё не важны`() {
        val t = table("""{"id": 1, "names": ["Чёрный клевер", "Black Clover"], "seasons": [10]}""")
        assertNotNull(t.find("черный КЛЕВЕР"))
        assertNotNull(t.find("black clover"))
        assertNull(t.find("Клевер"))
    }

    @Test
    fun `заметка и лишние поля игнорируются`() {
        val t = table("""{"id": 7, "names": ["Сериал"], "seasons": [5, 6], "note": "для себя", "x": 1}""")
        assertEquals(1, t.size)
        assertEquals(mapOf(1 to 5, 2 to 6), t.find("Сериал")!!.seasons)
    }

    @Test
    fun `сломанные записи пропускаются, остальные работают`() {
        val t = table(
            """{"id": "abc", "names": ["Не число"]}""",
            """{"id": 0, "names": ["Нулевой id"]}""",
            """{"id": 1, "names": []}""",
            """{"id": 2}""",
            """{"id": 3, "names": ["Плохие сезоны"], "seasons": [5, "x"]}""",
            """{"id": 4, "names": ["Ноль серий"], "seasons": [0]}""",
            """"просто строка"""",
            """{"id": 6, "names": ["Нормальный"], "seasons": [12]}"""
        )
        assertEquals(1, t.size)
        assertEquals(6, t.find("Нормальный")!!.kinopoiskId)
        assertNull(t.find("Плохие сезоны"))
    }

    @Test
    fun `BOM в начале`() {
        val list = OverrideTable.parse("﻿" + json("""{"id": 1, "names": ["Первый"]}"""))
        assertEquals(1, list!!.size)
    }

    @Test
    fun `не JSON или нет списка series - таблицей не считается`() {
        assertNull(OverrideTable.parse("<html><body>Not found</body></html>"))
        assertNull(OverrideTable.parse(""))
        assertNull(OverrideTable.parse("[1, 2]"))
        assertNull(OverrideTable.parse("""{"other": []}"""))
        assertNull(OverrideTable.parse("""{"series": [ {"id": 1,"""))
        assertEquals(0, OverrideTable.parse("""{"series": []}""")!!.size)
    }

    @Test
    fun `одинаковое название - побеждает запись ниже`() {
        val t = table("""{"id": 1, "names": ["Сериал"]}""", """{"id": 2, "names": ["Сериал"]}""")
        assertEquals(2, t.find("Сериал")!!.kinopoiskId)
    }

    @Test
    fun `подпись меняется, когда запись поправили, и пустая, когда поправки нет`() {
        val a = table("""{"id": 1, "names": ["Сериал"], "seasons": [5, 6]}""").sig("Сериал")
        val b = table("""{"id": 1, "names": ["Сериал"], "seasons": [5, 7]}""").sig("Сериал")
        val c = table("""{"id": 2, "names": ["Сериал"], "seasons": [5, 6]}""").sig("Сериал")
        assertTrue(a.isNotEmpty())
        assertTrue(a != b)
        assertTrue(a != c)
        assertEquals("", table("""{"id": 1, "names": ["Сериал"]}""").sig("Другой"))
    }

    // ---------- загрузка ----------

    private class MemStore : KeyValueStore {
        val map = LinkedHashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private fun resp(body: String, code: Int = 200) = SkipHttpResponse(code, body)

    @Test
    fun `без файла работает встроенный Чёрный клевер`() {
        val s = OverridesSource("", MemStore())
        val o = s.table.find("Чёрный клевер")
        assertNotNull(o)
        assertEquals(1049731, o!!.kinopoiskId)
        assertEquals(mapOf(1 to 170, 2 to 13), o.seasons)
        assertNotNull(s.table.find("Black Clover"))
    }

    @Test
    fun `пустая ссылка - в сеть не ходим`() {
        var calls = 0
        val s = OverridesSource("", MemStore()) { calls++; resp("") }
        assertFalse(s.refresh())
        assertEquals(0, calls)
    }

    @Test
    fun `скачанный файл главнее встроенного и остаётся в кэше`() {
        val store = MemStore()
        val body = json(
            """{"id": 1049731, "names": ["Чёрный клевер"], "seasons": [170, 51]}""",
            """{"id": 9, "names": ["Новый"], "seasons": [4]}"""
        )
        val s = OverridesSource("https://x", store) { resp(body) }
        assertTrue(s.refresh())
        assertEquals(mapOf(1 to 170, 2 to 51), s.table.find("Чёрный клевер")!!.seasons)
        assertEquals(9, s.table.find("Новый")!!.kinopoiskId)

        // после перезапуска без сети берётся последняя копия
        val again = OverridesSource("https://x", store) { throw IOException("нет сети") }
        assertFalse(again.refresh())
        assertEquals(9, again.table.find("Новый")!!.kinopoiskId)
    }

    @Test
    fun `ошибка сети или HTTP - таблица не меняется`() {
        val store = MemStore()
        val ok = json("""{"id": 9, "names": ["Новый"], "seasons": [4]}""")
        assertTrue(OverridesSource("https://x", store) { resp(ok) }.refresh())

        val bad = OverridesSource("https://x", store) { resp("", 404) }
        assertFalse(bad.refresh())
        assertNotNull(bad.table.find("Новый"))

        val down = OverridesSource("https://x", store) { throw IOException("таймаут") }
        assertFalse(down.refresh())
        assertNotNull(down.table.find("Новый"))
    }

    @Test
    fun `мусор вместо таблицы (страница с ошибкой, битый JSON) - старая копия остаётся`() {
        val store = MemStore()
        OverridesSource("https://x", store) { resp(json("""{"id": 9, "names": ["Новый"]}""")) }.refresh()
        for (junk in listOf("<html><body>Not found</body></html>", """{"series": [ {"id": 1""")) {
            val s = OverridesSource("https://x", store) { resp(junk) }
            assertFalse(s.refresh())
            assertNotNull(s.table.find("Новый"))
        }
    }

    @Test
    fun `пустой список принимается - все свои записи удалены, встроенная остаётся`() {
        val store = MemStore()
        OverridesSource("https://x", store) { resp(json("""{"id": 9, "names": ["Новый"]}""")) }.refresh()
        val s = OverridesSource("https://x", store) { resp("""{"series": []}""") }
        assertTrue(s.refresh())
        assertNull(s.table.find("Новый"))
        assertNotNull(s.table.find("Black Clover"))
    }
}
