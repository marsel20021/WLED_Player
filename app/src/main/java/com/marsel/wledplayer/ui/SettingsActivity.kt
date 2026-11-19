package com.marsel.wledplayer.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.marsel.wledplayer.data.rawBooleanValue
import com.marsel.wledplayer.data.rawStringValue
import com.marsel.wledplayer.ui.overlay.Dz
import com.marsel.wledplayer.ui.overlay.UiColors
import com.marsel.wledplayer.ui.overlay.withAlpha
import com.marsel.wledplayer.ui.settings.ChoiceRow
import com.marsel.wledplayer.ui.settings.SettingRow
import com.marsel.wledplayer.ui.settings.SettingsSpec
import com.marsel.wledplayer.ui.settings.StepperRow
import com.marsel.wledplayer.ui.settings.TextRow
import com.marsel.wledplayer.ui.settings.ToggleRow

/**
 * Настройки: панель справа поверх видео. Вкладки, строки с переключателями и числами (Влево/Вправо меняют значение).
 * Всё пишется в те же SharedPreferences и под теми же ключами, что и раньше.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var dz: Dz
    private var accent = UiColors.DEFAULT_ACCENT
    private var tabIndex = -1

    private lateinit var rowsBox: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private val tabViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        dz = Dz(this)
        accent = intent.getIntExtra(EXTRA_ACCENT, UiColors.DEFAULT_ACCENT)

        val root = FrameLayout(this).apply { setBackgroundColor(0x8506070B.toInt()) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dz.i(80f), dz.i(72f), dz.i(80f), 0)
            val r = dz.f(64f)
            background = GradientDrawable().apply {
                setColor(0xF20C0E14.toInt())
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(dz.i(1160f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))

        // заголовок и состояние подсветки
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Настройки", 56f, UiColors.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusDot = View(this)
        header.addView(statusDot, LinearLayout.LayoutParams(dz.i(18f), dz.i(18f)).also { it.marginEnd = dz.i(16f) })
        statusText = label("", 26f, 0xFFB4BBCC.toInt())
        header.addView(statusText)
        panel.addView(header)

        // вкладки
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SettingsSpec.tabs.forEachIndexed { i, tab ->
            val v = label(tab.title, 26f, UiColors.TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(dz.i(26f), 0, dz.i(26f), 0)
                isFocusable = true
                isFocusableInTouchMode = false
                setOnFocusChangeListener { _, focused ->
                    if (focused) selectTab(i)
                    styleTabs()
                }
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(v)
            tabs.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dz.i(68f)).also { it.marginEnd = dz.i(12f) })
        }
        panel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dz.i(36f) })

        // строки
        rowsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dz.i(8f), 0, dz.i(96f))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(rowsBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = dz.i(28f) })

        setContentView(root)
        selectTab(0)
        updateStatus()
        rowsBox.post { rowsBox.getChildAt(0)?.requestFocus() }
    }

    private fun label(text: String, sizeDesign: Float, color: Int, bold: Boolean = true) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dz.f(sizeDesign))
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun selectTab(i: Int) {
        if (i == tabIndex) return
        tabIndex = i
        rebuildRows(null)
        styleTabs()
    }

    private fun styleTabs() {
        tabViews.forEachIndexed { i, v ->
            val active = i == tabIndex
            v.setTextColor(if (active) 0xFF0A0B10.toInt() else UiColors.TEXT)
            v.background = GradientDrawable().apply {
                cornerRadius = dz.f(34f)
                if (active) setColor(0xFFF4F5F9.toInt()) else {
                    setColor(0x12FFFFFF)
                    setStroke(dz.i(2f), 0x33FFFFFF)
                }
                if (v.isFocused) setStroke(dz.i(5f), accent)
            }
        }
    }

    private fun updateStatus() {
        val on = rawBooleanValue(prefs, "ambilight_enabled", true)
        statusText.text = if (on) "Подсветка включена" else "Подсветка выключена"
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) accent else 0x47FFFFFF)
        }
    }

    /** Пересобирает строки вкладки. focusKey - на какой строке оставить фокус. */
    private fun rebuildRows(focusKey: String?) {
        rowsBox.removeAllViews()
        val engine = rawStringValue(prefs, SettingsSpec.ENGINE_KEY, "classic")
        val rows = SettingsSpec.tabs[tabIndex].rows(engine)
        var focusView: View? = null
        rows.forEach { row ->
            val v = RowView(row)
            rowsBox.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dz.i(112f)).also { it.bottomMargin = dz.i(20f) })
            if (row.key == focusKey) focusView = v
        }
        focusView?.let { f -> rowsBox.post { f.requestFocus() } }
    }

    /** Один ряд: название слева, значение справа. */
    private inner class RowView(private val row: SettingRow) : LinearLayout(this@SettingsActivity) {
        private val valueView: TextView?
        private val switchView: PillSwitch?

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dz.i(44f), 0, dz.i(40f), 0)
            isFocusable = true
            isFocusableInTouchMode = false
            applyBackground(false)
            setOnFocusChangeListener { _, focused -> applyBackground(focused) }

            addView(label(row.title, 32f, UiColors.TEXT), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (row is ToggleRow) {
                switchView = PillSwitch(this@SettingsActivity, dz)
                valueView = null
                addView(switchView, LayoutParams(dz.i(108f), dz.i(60f)))
            } else {
                switchView = null
                valueView = label("", 30f, UiColors.TEXT).apply {
                    gravity = Gravity.CENTER
                    minWidth = dz.i(150f)
                    setPadding(dz.i(26f), 0, dz.i(26f), 0)
                    background = GradientDrawable().apply { cornerRadius = dz.f(30f); setColor(0x24FFFFFF) }
                }
                addView(valueView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dz.i(60f)))
            }
            refresh()

            setOnKeyListener { _, keyCode, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { change(-1, e.repeatCount); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { change(1, e.repeatCount); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (e.repeatCount == 0) activate()
                        true
                    }
                    else -> false
                }
            }
            setOnClickListener { activate() }
        }

        private fun applyBackground(focused: Boolean) {
            background = GradientDrawable().apply {
                cornerRadius = dz.f(40f)
                if (focused) {
                    setColor(0x1AFFFFFF)
                    setStroke(dz.i(4f), 0xFFF4F5F9.toInt())
                } else {
                    setColor(0x0AFFFFFF)
                }
            }
        }

        private fun current(): String = when (row) {
            is ToggleRow -> rawBooleanValue(prefs, row.key, row.def).toString()
            is StepperRow -> (rawStringValue(prefs, row.key, row.def.toString()).toIntOrNull() ?: row.def).toString()
            is ChoiceRow -> rawStringValue(prefs, row.key, row.def)
            is TextRow -> rawStringValue(prefs, row.key, row.def)
        }

        fun refresh() {
            when (row) {
                is ToggleRow -> switchView?.setOn(current().toBoolean(), accent)
                is StepperRow -> valueView?.text = "‹   ${current()}${row.unit}   ›"
                is ChoiceRow -> valueView?.text = "‹   ${row.options.firstOrNull { it.first == current() }?.second ?: current()}   ›"
                is TextRow -> valueView?.text = current()
            }
        }

        private fun change(direction: Int, repeat: Int) {
            when (row) {
                is ToggleRow -> setBool(direction > 0)
                is StepperRow -> {
                    val cur = current().toInt()
                    val times = if (repeat > 20) 10 else if (repeat > 8) 4 else if (repeat > 3) 2 else 1
                    prefs.edit().putString(row.key, SettingsSpec.stepped(row, cur, direction, times).toString()).apply()
                }
                is ChoiceRow -> {
                    prefs.edit().putString(row.key, SettingsSpec.nextChoice(row, current(), direction)).apply()
                    if (row.key == SettingsSpec.ENGINE_KEY) rebuildRows(row.key)
                }
                is TextRow -> {}
            }
            refresh()
            updateStatus()
        }

        private fun setBool(v: Boolean) {
            prefs.edit().putBoolean(row.key, v).apply()
        }

        private fun activate() {
            when (row) {
                is ToggleRow -> setBool(!current().toBoolean())
                is StepperRow -> {}
                is ChoiceRow -> change(1, 0)
                is TextRow -> { editText(row); return }
            }
            refresh()
            updateStatus()
        }

        private fun editText(r: TextRow) {
            val input = EditText(this@SettingsActivity).apply {
                setText(current())
                setSelection(text.length)
                setSingleLine()
                inputType = if (r.numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            val pad = (resources.displayMetrics.density * 24).toInt()
            val box = FrameLayout(this@SettingsActivity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(r.title)
                .setView(box)
                .setPositiveButton("OK") { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isNotEmpty()) {
                        prefs.edit().putString(r.key, v).apply()
                        refresh()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    /** Переключатель-таблетка: включён - цвет акцента и белый кружок справа. */
    private class PillSwitch(context: Context, private val dz: Dz) : View(context) {
        private var on = false
        private var accent = UiColors.DEFAULT_ACCENT
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun setOn(value: Boolean, accent: Int) {
            on = value
            this.accent = accent
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            if (on) {
                paint.color = withAlpha(accent, 0.28f)
                rect.set(-dz.f(4f), -dz.f(4f), w + dz.f(4f), h + dz.f(4f))
                canvas.drawRoundRect(rect, h, h, paint)
            }
            paint.color = if (on) accent else 0x33FFFFFF
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            val knob = h - dz.f(12f)
            val cx = if (on) w - dz.f(6f) - knob / 2 else dz.f(6f) + knob / 2
            paint.color = if (on) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
            canvas.drawCircle(cx, h / 2, knob / 2, paint)
        }
    }

    companion object {
        const val EXTRA_ACCENT = "accent"
    }
}
