package com.marsel.wledplayer.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Полоса времени: дорожка, заполнение цветом акцента и бегунок. Не управляется напрямую, перемотка идёт кнопками пульта. */
class SeekBarView(context: Context, private val dz: Dz) : View(context) {

    var accent: Int = UiColors.DEFAULT_ACCENT
        set(value) { field = value; invalidate() }

    private var fraction = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setProgress(positionMs: Long, durationMs: Long) {
        val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (f != fraction) {
            fraction = f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dz.i(44f))
    }

    override fun onDraw(canvas: Canvas) {
        val h = dz.f(8f)
        val cy = height / 2f
        val left = dz.f(16f)
        val right = width - dz.f(16f)
        val x = left + (right - left) * fraction

        paint.style = Paint.Style.FILL
        paint.color = 0x38FFFFFF
        rect.set(left, cy - h / 2, right, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        // свечение под заполнением
        paint.color = withAlpha(accent, 0.25f)
        rect.set(left, cy - h, x, cy + h)
        canvas.drawRoundRect(rect, h, h, paint)
        paint.color = accent
        rect.set(left, cy - h / 2, x, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        paint.color = withAlpha(accent, 0.32f)
        canvas.drawCircle(x, cy, dz.f(23f), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, cy, dz.f(16f), paint)
    }
}
