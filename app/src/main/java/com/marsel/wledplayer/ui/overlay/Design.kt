package com.marsel.wledplayer.ui.overlay

import android.content.Context
import android.graphics.Color

/** Размеры в дизайне считались для экрана 1920 px в ширину. Здесь они пересчитываются под реальный экран. */
class Dz(context: Context) {
    val k: Float = context.resources.displayMetrics.widthPixels / 1920f
    fun f(v: Float): Float = v * k
    fun i(v: Float): Int = (v * k + 0.5f).toInt()
}

object UiColors {
    val TEXT = Color.parseColor("#F4F5F9")
    val MUTED = Color.parseColor("#C3C9D8")
    val DEFAULT_ACCENT = Color.parseColor("#F2A65A")
}

fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (color and 0x00FFFFFF) or (a shl 24)
}
