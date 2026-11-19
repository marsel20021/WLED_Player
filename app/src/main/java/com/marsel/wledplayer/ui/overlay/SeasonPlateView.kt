package com.marsel.wledplayer.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View

/**
 * Плашка "Сезон N" над лентой серий. Когда на ней фокус, Влево/Вправо меняют сезон.
 * Всё рисуется внутри самого вида (запас под кольцо фокуса), чтобы ничего не обрезалось.
 */
class SeasonPlateView(context: Context, private val dz: Dz) : View(context) {

    var accent: Int = UiColors.DEFAULT_ACCENT
        set(value) { field = value; invalidate() }

    /** Человек выбрал сезон. */
    var onPick: ((Int) -> Unit)? = null

    private var seasons: List<Int> = emptyList()
    private var selected: Int? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiColors.TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = dz.f(32f)
    }

    private val pad = dz.f(16f)
    private val h = dz.f(64f)
    private val side = dz.f(26f)
    private val chevron = dz.f(14f)
    private val gap = dz.f(18f)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun set(seasons: List<Int>, selected: Int?) {
        if (this.seasons == seasons && this.selected == selected) return
        val resize = this.seasons != seasons || (this.selected == null) != (selected == null) ||
            label(this.selected).length != label(selected).length
        this.seasons = seasons
        this.selected = selected
        if (resize) requestLayout()
        invalidate()
    }

    private fun label(season: Int?) = "Сезон " + (season?.toString() ?: "?")

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // ширину считаем по самой длинной подписи, чтобы плашка не прыгала при смене сезона
        val widest = (seasons + listOfNotNull(selected)).maxOrNull()
        val textW = maxOf(textPaint.measureText(label(widest)), textPaint.measureText(label(null)))
        val w = side + chevron + gap + textW + gap + chevron + side + pad * 2
        setMeasuredDimension(w.toInt(), (h + pad * 2).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val l = pad
        val t = pad
        val r = width - pad
        val b = height - pad
        val radius = h / 2
        val focused = isFocused

        if (focused) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(accent, 0.26f)
            rect.set(l - dz.f(10f), t - dz.f(10f), r + dz.f(10f), b + dz.f(10f))
            canvas.drawRoundRect(rect, radius + dz.f(10f), radius + dz.f(10f), paint)
        }
        rect.set(l, t, r, b)
        paint.style = Paint.Style.FILL
        paint.color = if (focused) 0xE014161E.toInt() else 0x1AFFFFFF
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dz.f(if (focused) 4f else 2f)
        paint.color = if (focused) 0xFFFFFFFF.toInt() else 0x2EFFFFFF
        rect.inset(dz.f(2f), dz.f(2f))
        canvas.drawRoundRect(rect, radius, radius, paint)

        val cy = (t + b) / 2
        val idx = selected?.let { seasons.indexOf(it) } ?: -1
        val canLeft = seasons.isNotEmpty() && idx != 0
        val canRight = seasons.isNotEmpty() && idx < seasons.size - 1

        drawChevron(canvas, l + side + chevron, cy, left = true, enabled = canLeft)
        drawChevron(canvas, r - side - chevron, cy, left = false, enabled = canRight)

        val text = label(selected)
        val fm = textPaint.fontMetrics
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, (l + r) / 2, cy - (fm.ascent + fm.descent) / 2, textPaint)
    }

    private fun drawChevron(canvas: Canvas, x: Float, cy: Float, left: Boolean, enabled: Boolean) {
        val half = chevron
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dz.f(4.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = if (enabled) 0xFFFFFFFF.toInt() else 0x40FFFFFF
        path.reset()
        if (left) {
            path.moveTo(x, cy - half)
            path.lineTo(x - half, cy)
            path.lineTo(x, cy + half)
        } else {
            path.moveTo(x - half, cy - half)
            path.lineTo(x, cy)
            path.lineTo(x - half, cy + half)
        }
        canvas.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.repeatCount == 0 && seasons.isNotEmpty()) {
                val idx = selected?.let { seasons.indexOf(it) } ?: -1
                val next = when {
                    idx < 0 -> 0
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> maxOf(0, idx - 1)
                    else -> minOf(seasons.size - 1, idx + 1)
                }
                if (next != idx) {
                    // сразу показываем новый сезон, не дожидаясь круга обновления
                    selected = seasons[next]
                    invalidate()
                    onPick?.invoke(seasons[next])
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
