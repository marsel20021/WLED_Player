package com.marsel.wledplayer.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.content.ContextCompat
import com.marsel.wledplayer.R

/**
 * Кнопка пропуска. Пока идёт отсчёт, слева заливается цвет акцента.
 * active = false: приглушённая (например, "Вернуть").
 */
class SkipButtonView(context: Context, private val dz: Dz) : View(context) {

    var accent: Int = UiColors.DEFAULT_ACCENT
        set(value) { field = value; invalidate() }

    private var text = ""
    private var progress = 0f
    private var active = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiColors.TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = dz.f(38f)
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_next)?.mutate()
    private val rect = RectF()
    private val clip = android.graphics.Path()

    private val pad = dz.f(16f)
    private val h = dz.f(100f)

    fun set(text: String, progress: Float, active: Boolean) {
        this.text = text
        this.progress = progress.coerceIn(0f, 1f)
        this.active = active
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = dz.f(36f) + dz.f(44f) + dz.f(20f) + textPaint.measureText(text) + dz.f(44f) + pad * 2
        setMeasuredDimension(w.toInt(), (h + pad * 2).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val l = pad
        val t = pad
        val r = width - pad
        val b = height - pad
        val radius = h / 2

        if (active) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(accent, 0.26f)
            rect.set(l - dz.f(12f), t - dz.f(12f), r + dz.f(12f), b + dz.f(12f))
            canvas.drawRoundRect(rect, radius + dz.f(12f), radius + dz.f(12f), paint)
        }

        rect.set(l, t, r, b)
        paint.style = Paint.Style.FILL
        paint.color = 0xB814161E.toInt()
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (progress > 0f) {
            canvas.save()
            clip.reset()
            clip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
            canvas.clipPath(clip)
            paint.color = withAlpha(accent, 0.42f)
            canvas.drawRect(l, t, l + (r - l) * progress, b, paint)
            canvas.restore()
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dz.f(3f)
        paint.color = if (active) 0xFFFFFFFF.toInt() else 0x8CFFFFFF.toInt()
        rect.inset(dz.f(1.5f), dz.f(1.5f))
        canvas.drawRoundRect(rect, radius, radius, paint)

        val cy = (t + b) / 2
        val iconSize = dz.f(44f)
        val ix = l + dz.f(36f)
        icon?.let {
            it.setBounds(ix.toInt(), (cy - iconSize / 2).toInt(), (ix + iconSize).toInt(), (cy + iconSize / 2).toInt())
            it.setTint(UiColors.TEXT)
            it.draw(canvas)
        }
        val fm = textPaint.fontMetrics
        canvas.drawText(text, ix + iconSize + dz.f(20f), cy - (fm.ascent + fm.descent) / 2, textPaint)
    }
}
