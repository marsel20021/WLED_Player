package com.marsel.wledplayer.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroParserTest {

    private val theIntro = """
    {"intro":[{"start_ms":6000,"end_ms":102000,"submission_count":4},{"start_ms":8000,"end_ms":106000,"submission_count":9}],
     "recap":[],
     "credits":[{"start_ms":3000000,"end_ms":null,"submission_count":2}],
     "preview":[]}
    """.trimIndent()

    @Test
    fun `TheIntroDB отрезки сортируются по числу отметок`() {
        val d = IntroParser.parseTheIntroDb(theIntro)!!
        val intros = d.segments.filter { it.kind == SkipKind.INTRO }
        assertEquals(2, intros.size)
        assertEquals(8_000L, intros[0].startMs)
        assertEquals(9, intros[0].votes)
        val credits = d.segments.single { it.kind == SkipKind.CREDITS }
        assertNull(credits.endMs)
    }

    @Test
    fun `TheIntroDB пустое начало заставки - это ноль`() {
        val d = IntroParser.parseTheIntroDb("""{"intro":[{"start_ms":null,"end_ms":90000,"submission_count":1}]}""")!!
        assertEquals(0L, d.segments.single().startMs)
    }

    @Test
    fun `заставка без конца и титры без начала отбрасываются`() {
        val d = IntroParser.parseTheIntroDb(
            """{"intro":[{"start_ms":1000,"end_ms":null}],"credits":[{"start_ms":null,"end_ms":5000}]}"""
        )!!
        assertTrue(d.segments.isEmpty())
    }

    @Test
    fun `мусор вместо JSON даёт null`() {
        assertNull(IntroParser.parseTheIntroDb("oops"))
        assertNull(IntroParser.parseIntroDb("<html>"))
    }

    @Test
    fun `данные внутри data тоже читаются`() {
        val d = IntroParser.parseTheIntroDb("""{"data":{"intro":[{"start_ms":0,"end_ms":80000}]}}""")!!
        assertEquals(1, d.segments.size)
    }

    private val introDb = """
    {"intro":{"start_sec":8,"end_sec":106,"submission_count":5},
     "recap":null,
     "outro":{"start_sec":2950,"end_sec":3100,"submission_count":3},
     "post_credits":{"start_sec":3100,"end_sec":3200,"submission_count":1},
     "no_recap":true}
    """.trimIndent()

    @Test
    fun `IntroDB переводит секунды в миллисекунды и читает сцену после титров`() {
        val d = IntroParser.parseIntroDb(introDb)!!
        val intro = d.segments.single { it.kind == SkipKind.INTRO }
        assertEquals(8_000L, intro.startMs)
        assertEquals(106_000L, intro.endMs)
        val credits = d.segments.single { it.kind == SkipKind.CREDITS }
        assertEquals(2_950_000L, credits.startMs)
        assertEquals(3_100_000L, credits.endMs)
        assertEquals(3_100_000L, d.postCreditsStartMs)
    }

    @Test
    fun `второй сервис нужен, если нет заставки или у титров нет конца`() {
        assertTrue(IntroParser.needsSecond(null))
        assertTrue(IntroParser.needsSecond(IntroParser.parseTheIntroDb(theIntro))) // титры без конца
        val full = IntroParser.parseTheIntroDb(
            """{"recap":[{"start_ms":0,"end_ms":30000}],"intro":[{"start_ms":30000,"end_ms":80000}],"credits":[{"start_ms":1300000,"end_ms":1400000}]}"""
        )
        assertFalse(IntroParser.needsSecond(full))
    }

    @Test
    fun `склейка - первый сервис главный, второй дополняет`() {
        val a = IntroParser.parseTheIntroDb(theIntro)
        val b = IntroParser.parseIntroDb(introDb)
        val t = IntroParser.merge(a, b)
        // заставка от первого
        assertEquals("TheIntroDB", t.segments.first { it.kind == SkipKind.INTRO }.source)
        // титры: у первого нет конца, у второго есть - берём второго
        val credits = t.segments.single { it.kind == SkipKind.CREDITS }
        assertEquals("IntroDB", credits.source)
        assertEquals(3_100_000L, t.postCreditsStartMs)
    }

    @Test
    fun `склейка без второго сервиса оставляет первый`() {
        val t = IntroParser.merge(IntroParser.parseTheIntroDb(theIntro), null)
        assertNotNull(t.segments.firstOrNull { it.kind == SkipKind.CREDITS })
        assertNull(t.postCreditsStartMs)
    }

    @Test
    fun `склейка пустого`() {
        assertTrue(IntroParser.merge(null, null).segments.isEmpty())
    }
}
