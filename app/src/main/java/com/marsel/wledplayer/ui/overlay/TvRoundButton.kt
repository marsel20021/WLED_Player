package com.marsel.wledplayer.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Круглая кнопка плеера. Рисуется сама, чтобы цвет акцента и кольцо фокуса менялись на лету.
 * LED - кружок подсветки: короткое нажатие включает/выключает, удержание OK открывает настройки.
 */
class TvRoundButton(
    context: Context,
    private val dz: Dz,
    diameterDesign: Float,
    private val kind: Kind
) : View(context) {

    enum class Kind { NORMAL, PRIMARY, PLAIN, LED }

    var accent: Int = UiColors.DEFAULT_ACCENT
        set(value) { field = value; invalidate() }
    var ledOn: Boolean = true
        set(value) { field = value; invalidate() }
    var onLongPress: (() -> Unit)? = null

    private val diameter = dz.f(diameterDesign)
    private val pad = dz.f(20f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var icon: Drawable? = null
    private var hold = 0f
    private var holdAnim: ValueAnimator? = null
    private var longFired = false

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
    }

    fun setIcon(res: Int) {
        icon = ContextCompat.getDrawable(context, res)?.mutate()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (diameter + pad * 2).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) cancelHold()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = diameter / 2f
        val focused = isFocused

        if (focused) {
            val ringWhite = dz.f(if (kind == Kind.PRIMARY) 6f else 5f)
            val halo = dz.f(if (kind == Kind.PRIMARY) 18f else 16f)
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(accent, if (kind == Kind.PRIMARY) 0.28f else 0.26f)
            canvas.drawCircle(cx, cy, r + halo, paint)
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(cx, cy, r + ringWhite, paint)
        }

        when (kind) {
            Kind.PRIMARY -> {
                paint.style = Paint.Style.FILL
                paint.color = accent
                canvas.drawCircle(cx, cy, r, paint)
            }
            Kind.NORMAL, Kind.LED -> {
                paint.style = Paint.Style.FILL
                paint.color = 0xFF14161E.toInt()
                canvas.drawCircle(cx, cy, r, paint)
                paint.color = 0x1AFFFFFF
                canvas.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dz.f(2f)
                paint.color = 0x2EFFFFFF
                canvas.drawCircle(cx, cy, r - dz.f(1f), paint)
            }
            Kind.PLAIN -> {
                if (focused) {
                    paint.style = Paint.Style.FILL
                    paint.color = 0x24FFFFFF
                    canvas.drawCircle(cx, cy, r, paint)
                }
            }
        }

        if (kind == Kind.LED) {
            val dot = r * 0.30f
            paint.style = Paint.Style.FILL
            if (ledOn) {
                paint.color = withAlpha(accent, 0.18f)
                canvas.drawCircle(cx, cy, dot * 2.6f, paint)
                paint.color = withAlpha(accent, 0.30f)
                canvas.drawCircle(cx, cy, dot * 1.7f, paint)
                paint.color = accent
            } else {
                paint.color = 0x47FFFFFF
            }
            canvas.drawCircle(cx, cy, dot, paint)
            if (hold > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dz.f(7f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = accent
                val rr = r + dz.f(2f)
                canvas.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), -90f, 360f * hold, false, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
            return
        }

        icon?.let { d ->
            val half = r * (if (kind == Kind.PRIMARY) 0.52f else if (kind == Kind.PLAIN) 0.60f else 0.46f)
            d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            d.setTint(if (kind == Kind.PRIMARY) 0xFF241200.toInt() else 0xFFF4F5F9.toInt())
            d.draw(canvas)
        }
    }

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (kind == Kind.LED && isOk(keyCode)) {
            if (event.repeatCount == 0) startHold()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (kind == Kind.LED && isOk(keyCode)) {
            val fired = longFired
            cancelHold()
            longFired = false
            if (!fired) performClick()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startHold() {
        cancelHold()
        longFired = false
        holdAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HOLD_MS
            addUpdateListener {
                hold = it.animatedValue as Float
                if (hold >= 1f && !longFired) {
                    longFired = true
                    hold = 0f
                    cancel()
                    onLongPress?.invoke()
                }
                invalidate()
            }
            start()
        }
    }

    private fun cancelHold() {
        holdAnim?.removeAllUpdateListeners()
        holdAnim?.cancel()
        holdAnim = null
        hold = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelHold()
    }

    private companion object {
        const val HOLD_MS = 700L
    }
}
