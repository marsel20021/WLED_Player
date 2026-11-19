package com.marsel.wledplayer.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import com.marsel.wledplayer.skip.SegmentMark

/** Полоса времени: дорожка, заполнение цветом акцента и бегунок. Не управляется напрямую, перемотка идёт кнопками пульта. */
class SeekBarView(context: Context, private val dz: Dz) : View(context) {

    var accent: Int = UiColors.DEFAULT_ACCENT
        set(value) { field = value; invalidate() }

    /** Куда перемотать при Влево/Вправо, когда на полосе фокус. */
    var onStep: ((Long) -> Unit)? = null

    var marks: List<SegmentMark> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var fraction = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }
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

        // участки, которые можно пропустить (заставка, титры): светлые блоки на дорожке
        paint.color = 0x61FFFFFF
        val mh = dz.f(14f)
        for (m in marks) {
            rect.set(left + (right - left) * m.startFraction, cy - mh / 2, left + (right - left) * m.endFraction, cy + mh / 2)
            canvas.drawRoundRect(rect, mh / 2, mh / 2, paint)
        }

        // свечение под заполнением
        paint.color = withAlpha(accent, 0.25f)
        rect.set(left, cy - h, x, cy + h)
        canvas.drawRoundRect(rect, h, h, paint)
        paint.color = accent
        rect.set(left, cy - h / 2, x, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        // у бегунка при фокусе кольцо побольше
        val focused = isFocused
        paint.color = withAlpha(accent, if (focused) 0.40f else 0.32f)
        canvas.drawCircle(x, cy, dz.f(if (focused) 30f else 23f), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, cy, dz.f(if (focused) 20f else 16f), paint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val step = if (event.repeatCount > 8) 30_000L else 10_000L
            onStep?.invoke(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
