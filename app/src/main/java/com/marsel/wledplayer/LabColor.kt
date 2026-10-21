package com.marsel.wledplayer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Цветовая математика движка Lab. Не зависит от Android, поэтому её можно проверять обычными тестами.
 *
 * Два пространства:
 *  - «линейный свет» (linear RGB): числа пропорциональны количеству света. Здесь правильно усреднять
 *    и считать итоговую яркость диодов (светодиод линеен: удвоили значение = вдвое больше света);
 *  - Oklab (L, a, b): «перцептивное» пространство, где равные шаги выглядят глазу равными, а яркость,
 *    насыщенность и оттенок меняются независимо. Здесь правим цвет и сглаживаем его во времени.
 */
object LabColor {

    /** Таблица «число 0..255 из кадра → линейный свет», с той же гаммой, что и в остальных движках. */
    fun buildDecodeLut(gamma: Double): FloatArray =
        FloatArray(256) { (it / 255.0).pow(gamma).toFloat() }

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x >= e1) 1f else 0f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun cbrt(x: Float): Float = Math.cbrt(x.toDouble()).toFloat()

    /** Линейный sRGB (0..1) → Oklab. Записывает L, a, b в out[o], out[o+1], out[o+2]. */
    fun rgbToOklab(r: Float, g: Float, b: Float, out: FloatArray, o: Int) {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        out[o] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[o + 1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[o + 2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** Oklab → линейный sRGB, без обрезки (значения могут выйти за 0..1). */
    fun oklabToRgbRaw(L: Float, a: Float, b: Float, out: FloatArray, o: Int) {
        val l = L + 0.3963377774f * a + 0.2158037573f * b
        val m = L - 0.1055613458f * a - 0.0638541728f * b
        val s = L - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        out[o] = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        out[o + 1] = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        out[o + 2] = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
    }

    private const val GAMUT_EPS = 0.0008f

    private fun inGamut(r: Float, g: Float, b: Float): Boolean =
        r >= -GAMUT_EPS && g >= -GAMUT_EPS && b >= -GAMUT_EPS &&
            r <= 1f + GAMUT_EPS && g <= 1f + GAMUT_EPS && b <= 1f + GAMUT_EPS

    /**
     * Oklab → линейный sRGB в пределах 0..1 с «масштабированием вместо обрезки».
     * Если цвет не помещается в то, что умеет лента, мы не режем каналы по одному (от этого «едет»
     * оттенок), а уменьшаем насыщенность при той же яркости и том же оттенке, пока цвет не поместится.
     */
    fun oklabToRgbGamut(L: Float, a: Float, b: Float, out: FloatArray, o: Int) {
        val lc = L.coerceIn(0f, 1f)
        oklabToRgbRaw(lc, a, b, out, o)
        if (!inGamut(out[o], out[o + 1], out[o + 2])) {
            var lo = 0f // при нулевой насыщенности цвет всегда внутри
            var hi = 1f
            for (i in 0 until GAMUT_ITERATIONS) {
                val mid = 0.5f * (lo + hi)
                oklabToRgbRaw(lc, a * mid, b * mid, out, o)
                if (inGamut(out[o], out[o + 1], out[o + 2])) lo = mid else hi = mid
            }
            oklabToRgbRaw(lc, a * lo, b * lo, out, o)
        }
        out[o] = out[o].coerceIn(0f, 1f)
        out[o + 1] = out[o + 1].coerceIn(0f, 1f)
        out[o + 2] = out[o + 2].coerceIn(0f, 1f)
    }

    private const val GAMUT_ITERATIONS = 7

    /** Расстояние между двумя цветами в Oklab (около 0,02 = едва заметно глазу). */
    fun deltaE(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val dl = l1 - l2
        val da = a1 - a2
        val db = b1 - b2
        return Math.sqrt((dl * dl + da * da + db * db).toDouble()).toFloat()
    }

    fun maxOf3(a: Float, b: Float, c: Float): Float = max(a, max(b, c))
    fun minOf3(a: Float, b: Float, c: Float): Float = min(a, min(b, c))
}

/**
 * Заготовка под HDR. Сейчас ничего не меняет: цвет проходит как есть.
 *
 * Когда будем делать HDR, сюда ляжет кривая тонального маппинга (мягкое «колено»: средние тона как на
 * экране, яркие области сжимаются плавно). Вызывается для каждого канала в линейном свете, только если
 * в кадре HDR-видео, так что для обычного видео цена нулевая.
 */
object LabHdr {
    @Suppress("NOTHING_TO_INLINE")
    inline fun mapLinear(x: Float): Float = x
}
