package com.marsel.wledplayer.ui.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.marsel.wledplayer.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Что нужно от экрана: команды плееру и подсветке. */
interface OverlayActions {
    fun playPause()
    fun setPlaying(play: Boolean)
    fun seekBy(deltaMs: Long)
    fun prev()
    fun next()
    fun playIndex(index: Int)
    fun toggleLed()
    fun openSettings()
    fun openAudio()
}

/** Что показывать: обновляется примерно дважды в секунду. */
data class OverlayModel(
    val title: String,
    val subtitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val ledOn: Boolean,
    val currentIndex: Int
)

/**
 * Весь экран поверх видео: меню, лента серий, перемотка, кнопка пропуска.
 *
 * Пульт: идёт фильм - ничего нет. OK или Вниз - меню. Вниз ещё раз - лента серий. Вверх - назад.
 * Влево/Вправо при скрытом меню перематывают, в меню и ленте двигают фокус.
 */
class PlayerOverlay(
    private val context: Context,
    private val root: FrameLayout,
    private val actions: OverlayActions
) {
    enum class Mode { HIDDEN, SEEK, MENU, EPISODES }

    var mode = Mode.HIDDEN
        private set

    private val dz = Dz(context)
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var accent = UiColors.DEFAULT_ACCENT
    val currentAccent: Int get() = accent
    private var model = OverlayModel("", "", 0, 0, false, true, 0)
    private var skipShown = false
    private var episodeItems: List<EpisodeItem> = emptyList()

    private fun scrim(orientation: GradientDrawable.Orientation, start: Int) = View(context).apply {
        background = GradientDrawable(orientation, intArrayOf(start, start and 0x00FFFFFF))
        visibility = View.GONE
    }

    private val topScrim = scrim(GradientDrawable.Orientation.TOP_BOTTOM, 0xD106070B.toInt())
    private val bottomScrim = scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xEB06070B.toInt())
    private val episodeScrim = scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xF706070B.toInt())
    private val skipScrim = scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xC706070B.toInt())

    private fun text(sizeDesign: Float, color: Int, bold: Boolean = true) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dz.f(sizeDesign))
        setTextColor(color)
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setShadowLayer(dz.f(8f), 0f, dz.f(2f), 0x99000000.toInt())
    }

    // ---------- верх ----------
    private val titleTv = text(54f, UiColors.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val subtitleTv = text(30f, accent)
    private val titleBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(titleTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(subtitleTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dz.i(4f) })
    }

    private val endsTv = text(34f, UiColors.TEXT)
    private val endsBox = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        val clock = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_clock)?.mutate()?.also { it.setTint(UiColors.TEXT) })
        }
        addView(clock, LinearLayout.LayoutParams(dz.i(38f), dz.i(38f)).also { it.marginEnd = dz.i(14f) })
        addView(endsTv)
    }

    // ---------- низ: время и кнопки ----------
    private val timeCur = text(30f, UiColors.TEXT).apply { gravity = Gravity.START }
    private val timeTotal = text(30f, UiColors.MUTED).apply { gravity = Gravity.END }
    private val seekBar = SeekBarView(context, dz)

    private fun round(size: Float, kind: TvRoundButton.Kind, icon: Int?, onClick: () -> Unit) =
        TvRoundButton(context, dz, size, kind).apply {
            icon?.let { setIcon(it) }
            setOnClickListener { onClick(); scheduleHide() }
        }

    private val listBtn = round(76f, TvRoundButton.Kind.NORMAL, R.drawable.ic_list) { show(Mode.EPISODES) }
    private val audioBtn = round(76f, TvRoundButton.Kind.NORMAL, R.drawable.ic_audio) { actions.openAudio() }
    private val prevBtn = round(72f, TvRoundButton.Kind.PLAIN, R.drawable.ic_prev) { actions.prev() }
    private val backBtn = round(92f, TvRoundButton.Kind.NORMAL, R.drawable.ic_back) { actions.seekBy(-5_000) }
    private val playBtn = round(132f, TvRoundButton.Kind.PRIMARY, R.drawable.ic_pause) { actions.playPause() }
    private val fwdBtn = round(92f, TvRoundButton.Kind.NORMAL, R.drawable.ic_forward) { actions.seekBy(15_000) }
    private val nextBtn = round(72f, TvRoundButton.Kind.PLAIN, R.drawable.ic_next) { actions.next() }
    private val ledBtn = round(76f, TvRoundButton.Kind.LED, null) { actions.toggleLed() }.also {
        it.onLongPress = { actions.openSettings() }
    }

    private val timeRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(timeCur, LinearLayout.LayoutParams(dz.i(110f), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(timeTotal, LinearLayout.LayoutParams(dz.i(110f), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun group(gravity: Int, vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity or Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private val buttonRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(group(Gravity.START, listBtn, audioBtn), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(group(Gravity.CENTER, prevBtn, backBtn, playBtn, fwdBtn, nextBtn), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(group(Gravity.END, ledBtn), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private val bottomPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dz.i(10f) })
    }

    // ---------- лента серий ----------
    private val strip = EpisodeStrip(context, dz) { index ->
        actions.playIndex(index)
        hide()
    }
    private val stripTitle = text(46f, UiColors.TEXT)
    private val stripSeason = text(32f, accent)
    private val episodesBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(stripTitle)
            addView(stripSeason, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = dz.i(28f) })
        }
        addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dz.i(20f) })
        addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dz.i(330f)))
    }

    // ---------- пропуск ----------
    private val skipButton = SkipButtonView(context, dz).apply { visibility = View.GONE }
    private val thinBar = FrameLayout(context).apply {
        setBackgroundColor(0x2EFFFFFF)
        visibility = View.GONE
    }
    private val thinFill = View(context).apply { setBackgroundColor(accent) }

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(topScrim, FrameLayout.LayoutParams(match, dz.i(340f), Gravity.TOP))
        root.addView(bottomScrim, FrameLayout.LayoutParams(match, dz.i(560f), Gravity.BOTTOM))
        root.addView(episodeScrim, FrameLayout.LayoutParams(match, dz.i(560f), Gravity.BOTTOM))
        root.addView(skipScrim, FrameLayout.LayoutParams(match, dz.i(420f), Gravity.BOTTOM))
        root.addView(titleBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).also {
            it.leftMargin = dz.i(96f); it.topMargin = dz.i(60f); it.rightMargin = dz.i(620f)
        })
        root.addView(endsBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).also {
            it.rightMargin = dz.i(96f); it.topMargin = dz.i(64f)
        })
        root.addView(episodesBox, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).also {
            it.leftMargin = dz.i(96f)
        })
        root.addView(bottomPanel, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).also {
            it.leftMargin = dz.i(96f - 20f); it.rightMargin = dz.i(96f - 20f); it.bottomMargin = dz.i(52f)
        })
        thinBar.addView(thinFill, FrameLayout.LayoutParams(0, dz.i(6f)))
        root.addView(thinBar, FrameLayout.LayoutParams(match, dz.i(6f), Gravity.BOTTOM))
        root.addView(skipButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        applySkipPosition()
    }

    // ---------- что снаружи ----------

    fun setAccent(color: Int) {
        accent = color
        subtitleTv.setTextColor(color)
        stripSeason.setTextColor(color)
        thinFill.setBackgroundColor(color)
        seekBar.accent = color
        playBtn.accent = color
        ledBtn.accent = color
        skipButton.accent = color
        strip.accent = color
    }

    fun setEpisodes(items: List<EpisodeItem>, seasonLabel: String) {
        episodeItems = items
        stripSeason.text = seasonLabel
        if (mode == Mode.EPISODES) strip.setItems(items, model.currentIndex, currentFraction())
    }

    fun update(m: OverlayModel) {
        val playingChanged = m.playing != model.playing
        model = m
        titleTv.text = m.title
        subtitleTv.text = m.subtitle
        subtitleTv.visibility = if (m.subtitle.isEmpty()) View.GONE else View.VISIBLE
        timeCur.text = formatTime(m.positionMs)
        timeTotal.text = formatTime(m.durationMs)
        seekBar.setProgress(m.positionMs, m.durationMs)
        ledBtn.ledOn = m.ledOn
        if (playingChanged) playBtn.setIcon(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (m.durationMs > 0) {
            val end = System.currentTimeMillis() + (m.durationMs - m.positionMs).coerceAtLeast(0)
            endsTv.text = "Закончится в " + clockFormat.format(Date(end))
        } else {
            endsTv.text = ""
        }
        if (skipShown) {
            val f = if (m.durationMs > 0) (m.positionMs.toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f
            thinFill.layoutParams = FrameLayout.LayoutParams((root.width * f).toInt(), dz.i(6f))
        }
    }

    /** text = null: кнопки нет. */
    fun setSkip(text: String?, progress: Float, active: Boolean) {
        skipShown = text != null
        if (text != null) skipButton.set(text, progress, active)
        updateVisibility()
    }

    // ---------- режимы ----------

    fun show(newMode: Mode) {
        mode = newMode
        updateVisibility()
        when (newMode) {
            Mode.MENU -> playBtn.requestFocus()
            Mode.EPISODES -> {
                strip.setItems(episodeItems, model.currentIndex, currentFraction())
                strip.focusCurrent()
            }
            else -> {}
        }
        scheduleHide()
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        mode = Mode.HIDDEN
        updateVisibility()
    }

    private fun currentFraction(): Float =
        if (model.durationMs > 0) (model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f) else 0f

    private fun updateVisibility() {
        fun vis(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        val menu = mode == Mode.MENU
        val eps = mode == Mode.EPISODES
        val seek = mode == Mode.SEEK
        vis(topScrim, menu || eps)
        vis(bottomScrim, menu || seek)
        vis(episodeScrim, eps)
        vis(skipScrim, skipShown && mode == Mode.HIDDEN)
        vis(titleBox, menu || eps)
        vis(endsBox, menu || eps)
        vis(bottomPanel, menu || seek)
        vis(buttonRow, menu)
        vis(episodesBox, eps)
        vis(thinBar, skipShown && mode == Mode.HIDDEN)
        vis(skipButton, skipShown)
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, dz.f(if (eps) 40f else 54f))
        subtitleTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, dz.f(if (eps) 28f else 30f))
        applySkipPosition()
    }

    private fun applySkipPosition() {
        val lp = skipButton.layoutParams as FrameLayout.LayoutParams
        lp.rightMargin = dz.i(96f - 28f)
        // над меню или лентой кнопка поднимается, чтобы не закрывать их
        lp.bottomMargin = if (mode == Mode.MENU || mode == Mode.EPISODES) dz.i(330f) else dz.i(110f - 28f)
        skipButton.layoutParams = lp
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        val delay = when (mode) {
            Mode.HIDDEN -> return
            Mode.SEEK -> 3_000L
            Mode.MENU -> if (model.playing) 6_000L else return
            Mode.EPISODES -> 15_000L
        }
        handler.postDelayed(hideRunnable, delay)
    }

    // ---------- пульт ----------

    /** true - нажатие обработано здесь. false - пусть дальше идёт как обычно (двигается фокус, нажимается кнопка). */
    fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) when (mode) {
                    Mode.HIDDEN, Mode.SEEK -> show(Mode.MENU)
                    Mode.MENU -> show(Mode.EPISODES)
                    Mode.EPISODES -> scheduleHide()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) when (mode) {
                    Mode.EPISODES -> show(Mode.MENU)
                    Mode.MENU, Mode.SEEK -> hide()
                    Mode.HIDDEN -> {}
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (mode == Mode.HIDDEN || mode == Mode.SEEK) {
                    if (down) {
                        val step = if (e.repeatCount > 8) 30_000L else 10_000L
                        actions.seekBy(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                        show(Mode.SEEK)
                    }
                    return true
                }
                if (down) scheduleHide()
                return false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (mode == Mode.HIDDEN || mode == Mode.SEEK) {
                    if (down && e.repeatCount == 0) show(Mode.MENU)
                    return true
                }
                if (down) scheduleHide()
                return false
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down && e.repeatCount == 0) actions.playPause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) actions.setPlaying(true); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) actions.setPlaying(false); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down) actions.next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down) actions.prev(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { actions.seekBy(30_000); show(Mode.SEEK) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { actions.seekBy(-10_000); show(Mode.SEEK) }; return true }
        }
        return false
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        fun formatTime(ms: Long): String {
            if (ms <= 0) return "00:00"
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
