package com.marsel.wledplayer.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marsel.wledplayer.media.ImageLoader

/** Одна серия в ленте. imageUrl - кадр серии, если нашёлся. */
data class EpisodeItem(val index: Int, val label: String, val sub: String?, val imageUrl: String?)

/** Лента серий: кадры 320x180, у текущей серии метка "Сейчас". Выбор серии - кнопка OK. */
class EpisodeStrip(
    context: Context,
    private val dz: Dz,
    private val onPick: (Int) -> Unit
) : RecyclerView(context) {

    var accent: Int = UiColors.DEFAULT_ACCENT
        set(value) { field = value; episodeAdapter.notifyDataSetChanged() }

    private var items: List<EpisodeItem> = emptyList()
    private var currentIndex = -1
    private var currentFraction = 0f
    private val episodeAdapter = EpisodeAdapter()

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        setAdapter(episodeAdapter)
        clipToPadding = false
        clipChildren = false
        setPadding(dz.i(20f), dz.i(26f), dz.i(96f), dz.i(40f))
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        itemAnimator = null
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setItems(newItems: List<EpisodeItem>, current: Int, fraction: Float) {
        items = newItems
        currentIndex = current
        currentFraction = fraction
        episodeAdapter.notifyDataSetChanged()
    }

    /** Ставит фокус на текущую серию (после появления ленты на экране). */
    fun focusCurrent(attempt: Int = 0) {
        val pos = items.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0)
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
        post {
            val v = findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) v.requestFocus() else if (attempt < 6) postDelayed({ focusCurrent(attempt + 1) }, 50)
        }
    }

    private inner class Card(
        val root: LinearLayout,
        val frame: FrameLayout,
        val image: ImageView,
        val badge: TextView,
        val progressBg: FrameLayout,
        val progressFill: View,
        val title: TextView,
        val sub: TextView
    ) : ViewHolder(root) {
        var boundUrl: String? = null
    }

    private inner class EpisodeAdapter : Adapter<Card>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
            val ctx = parent.context
            val cardW = dz.i(320f)
            val cardH = dz.i(180f)

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                layoutParams = RecyclerView.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dz.i(28f) }
                pivotX = 0f
                pivotY = 0f
            }
            val frame = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(0x1AFFFFFF)
                    cornerRadius = dz.f(18f)
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dz.f(18f))
                    }
                }
            }
            val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(image, FrameLayout.LayoutParams(cardW, cardH))

            val badge = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dz.f(20f))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(0xFF241200.toInt())
                text = "Сейчас"
                gravity = Gravity.CENTER
                setPadding(dz.i(14f), 0, dz.i(14f), 0)
                visibility = View.GONE
            }
            frame.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dz.i(34f)).also {
                it.leftMargin = dz.i(12f)
                it.topMargin = dz.i(12f)
            })

            val progressBg = FrameLayout(ctx).apply {
                setBackgroundColor(0x47FFFFFF)
                visibility = View.GONE
            }
            val fill = View(ctx)
            progressBg.addView(fill, FrameLayout.LayoutParams(0, dz.i(7f)))
            frame.addView(progressBg, FrameLayout.LayoutParams(cardW, dz.i(7f), Gravity.BOTTOM))

            root.addView(frame, LinearLayout.LayoutParams(cardW, cardH))

            val title = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dz.f(28f))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(UiColors.TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(title, LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dz.i(16f) })
            val sub = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dz.f(24f))
                setTextColor(0xFFB4BBCC.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(sub, LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT))

            val card = Card(root, frame, image, badge, progressBg, fill, title, sub)
            root.setOnFocusChangeListener { _, focused ->
                val s = if (focused) 1.08f else 1f
                root.animate().scaleX(s).scaleY(s).setDuration(120).start()
                root.alpha = if (focused) 1f else 0.78f
                frame.foreground = if (focused) GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dz.i(10f), 0xFFFFFFFF.toInt())
                    cornerRadius = dz.f(18f)
                } else null
            }
            root.alpha = 0.78f
            return card
        }

        override fun onBindViewHolder(holder: Card, position: Int) {
            val item = items[position]
            holder.title.text = item.label
            holder.sub.text = item.sub ?: ""
            holder.sub.visibility = if (item.sub.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            holder.root.setOnClickListener { onPick(item.index) }

            val isCurrent = item.index == currentIndex
            holder.badge.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.badge.background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dz.f(17f)
            }
            val fraction = when {
                item.index < currentIndex -> 1f
                isCurrent -> currentFraction
                else -> 0f
            }
            holder.progressBg.visibility = if (fraction > 0f) View.VISIBLE else View.GONE
            holder.progressFill.setBackgroundColor(accent)
            holder.progressFill.layoutParams = FrameLayout.LayoutParams((dz.f(320f) * fraction).toInt(), dz.i(7f))

            holder.image.setImageDrawable(null)
            holder.boundUrl = item.imageUrl
            val url = item.imageUrl
            if (url != null) {
                ImageLoader.load(url, dz.i(320f)) { bmp ->
                    if (holder.boundUrl == url && bmp != null) holder.image.setImageBitmap(bmp)
                }
            }
        }
    }
}
