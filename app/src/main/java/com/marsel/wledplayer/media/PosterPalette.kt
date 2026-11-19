package com.marsel.wledplayer.media

import android.graphics.Bitmap

/** Цвета для фона и акцентов: основной, яркий акцент и тёмный. */
data class PosterColors(val main: Int, val accent: Int, val dark: Int) {
    companion object {
        val DEFAULT = PosterColors(0xFF3A3F5C.toInt(), 0xFF8C9EFF.toInt(), 0xFF12131A.toInt())
    }
}

/** Достаёт цвета из постера. Считает по пикселям, поэтому проверяется обычными тестами. */
object PosterPalette {
    private const val HUES = 24

    fun fromBitmap(src: Bitmap): PosterColors {
        val small = Bitmap.createScaledBitmap(src, 48, 72, true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== src) small.recycle()
        return fromPixels(px)
    }

    fun fromPixels(pixels: IntArray): PosterColors {
        if (pixels.isEmpty()) return PosterColors.DEFAULT
        val weight = DoubleArray(HUES)
        for (c in pixels) {
            val hsv = toHsv(c)
            val v = hsv[2]
            // серые и почти чёрные/белые пиксели цвет не несут
            if (hsv[1] < 0.2f || v < 0.2f || v > 0.97f) continue
            val bucket = ((hsv[0] / 360f) * HUES).toInt().coerceIn(0, HUES - 1)
            weight[bucket] += (hsv[1] * v).toDouble()
        }
        val top = weight.indices.maxByOrNull { weight[it] } ?: 0
        if (weight[top] <= 0.0) return PosterColors.DEFAULT
        val hue = (top + 0.5f) / HUES * 360f
        return PosterColors(fromHsv(hue, 0.55f, 0.45f), fromHsv(hue, 0.45f, 1.0f), fromHsv(hue, 0.45f, 0.10f))
    }

    /** Цвет ARGB -> [оттенок 0..360, насыщенность 0..1, яркость 0..1]. Своя формула, чтобы не зависеть от Android. */
    fun toHsv(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf(if (h < 0f) h + 360f else h, if (max == 0f) 0f else d / max, max)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
