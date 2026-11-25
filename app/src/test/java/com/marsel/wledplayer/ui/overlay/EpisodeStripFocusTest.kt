package com.marsel.wledplayer.ui.overlay

import android.app.Activity
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Стенд для проверки фокуса в ленте серий. Телевизор не нужен: Robolectric запускает
 * настоящий Android-фреймворк (поиск фокуса, раскладка вью), поэтому поведение фокуса
 * можно проверить на JVM.
 *
 * Сцена собрана как в плеере: контейнер, в начале которого строка с заголовком и плашкой
 * сезонов, а под ней — лента серий. Кадры серий не загружаются вовсе (imageUrl = null):
 * проверяем фокус, а не картинки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
class EpisodeStripFocusTest {

    private companion object {
        const val SCREEN_W = 1920
        const val SCREEN_H = 1080
        const val EPISODES = 30
    }

    private class Scene(
        val activity: Activity,
        val root: FrameLayout,
        val strip: EpisodeStrip,
        val plate: SeasonPlateView
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun measureAndLayout(v: View, w: Int = SCREEN_W, h: Int = SCREEN_H) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, w, h)
    }

    private fun scene(): Scene {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dz = Dz(activity)
        val root = FrameLayout(activity)
        activity.setContentView(root)

        // --- строка над лентой: заголовок, подпись сезона и плашка сезонов (как в PlayerOverlay) ---
        val plate = SeasonPlateView(activity, dz)
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(TextView(activity).apply { text = "Серии" })
            addView(
                TextView(activity).apply { text = "Сезон 1" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = dz.i(28f) }
            )
            addView(
                plate,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = dz.i(12f) }
            )
        }

        val strip = EpisodeStrip(activity, dz) { }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                head,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.leftMargin = dz.i(20f) }
            )
            addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dz.i(330f)))
        }

        root.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).also { it.leftMargin = dz.i(96f) }
        )

        plate.set(listOf(1, 2, 3), 1)

        strip.setItems(episodeList(), 0, 0f)

        measureAndLayout(root)
        idle()
        measureAndLayout(root)
        idle()
        return Scene(activity, root, strip, plate)
    }

    private fun episodeList() = (0 until EPISODES).map { EpisodeItem(it, "Серия ${it + 1}", null, null) }

    /** Ставит фокус на карточку с указанной позицией в списке. */
    private fun focusCard(s: Scene, position: Int): View {
        (s.strip.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
        idle()
        measureAndLayout(s.root)
        idle()
        val holder = s.strip.findViewHolderForAdapterPosition(position)
            ?: error("карточка $position не создана: раскладка не прошла")
        holder.itemView.requestFocus()
        idle()
        assertTrue("фокус не встал на карточку $position", holder.itemView.isFocused)
        return holder.itemView
    }

    private fun where(s: Scene, view: View?): String = when {
        view == null -> "нигде (null)"
        s.strip.findContainingViewHolder(view) != null -> "карточка в ленте"
        view is SeasonPlateView -> "ПЛАШКА СЕЗОНОВ"
        else -> "что-то ещё: ${view.javaClass.simpleName}"
    }

    @Test
    fun diagnostics_of_the_focus_scene() {
        val s = scene()
        println("=== СТЕНД ===")
        println("экран=${s.root.width}x${s.root.height}, лента=${s.strip.width}x${s.strip.height}")
        println("карточек всего=${s.strip.adapter?.itemCount}, видимых=${s.strip.childCount}")
        s.strip.getChildAt(0)?.let { println("первая карточка: ${it.width}x${it.height}") }
        println("плашка сезонов: ${s.plate.width}x${s.plate.height}, видима=${s.plate.visibility == View.VISIBLE}")

        val card = focusCard(s, 0)
        println("фокус на карточке 0 -> ${where(s, s.activity.currentFocus ?: s.root.findFocus())}")
        println("поиск вправо с карточки 0 -> ${where(s, card.focusSearch(View.FOCUS_RIGHT))}")
        println("поиск влево с карточки 0 -> ${where(s, card.focusSearch(View.FOCUS_LEFT))}")

        val last = focusCard(s, s.strip.adapter!!.itemCount - 1)
        println("поиск вправо с ПОСЛЕДНЕЙ карточки -> ${where(s, last.focusSearch(View.FOCUS_RIGHT))}")
        println("поиск вверх с последней карточки -> ${where(s, last.focusSearch(View.FOCUS_UP))}")

        assertNotNull(last.focusSearch(View.FOCUS_RIGHT))
    }

    /** Правило из кода: вбок фокус из ленты не уходит ни на одном краю. */
    @Test
    fun sideways_search_never_leaves_the_strip() {
        val s = scene()
        for (pos in listOf(0, 1, s.strip.adapter!!.itemCount - 1)) {
            val card = focusCard(s, pos)
            for (dir in listOf(View.FOCUS_LEFT, View.FOCUS_RIGHT)) {
                val result = card.focusSearch(dir)
                println("позиция $pos направление $dir -> ${where(s, result)}")
                assertTrue(
                    "поиск фокуса увёл из ленты (позиция $pos, направление $dir): ${where(s, result)}",
                    result != null && s.strip.findContainingViewHolder(result) != null
                )
            }
        }
    }

    /** Обновление списка (в плеере — на каждом обновлении состояния и при приходе кадров) не должно ронять фокус. */
    @Test
    fun focus_stays_in_strip_when_the_list_is_updated() {
        val s = scene()
        val card = focusCard(s, 3)

        val before = s.strip.findFocus()
        println("до обновления: фокус в ленте = ${where(s, before)}, привязана к окну = ${card.isAttachedToWindow}")

        s.strip.setItems(episodeList(), 0, 0f)
        println("сразу после setItems: фокус в ленте = ${where(s, s.strip.findFocus())}")
        idle()
        measureAndLayout(s.root)
        idle()

        val focused = s.activity.currentFocus ?: s.root.findFocus()
        println("после обновления ленты: фокус = ${where(s, focused)}")
        println("  карточка 3 всё ещё привязана = ${card.isAttachedToWindow}, фокус на ней = ${card.isFocused}")
        println("  лента видит фокус = ${where(s, s.strip.findFocus())}, плашка = ${where(s, s.plate)}")

        assertTrue(
            "фокус ушёл из ленты после обновления списка: ${where(s, focused)}",
            focused != null && s.strip.findContainingViewHolder(focused) != null
        )
    }

    /** Вверх — задуманный выход на плашку сезонов (так описано в комментарии к ленте). */
    @Test
    fun vertical_search_reaches_the_season_plate() {
        val s = scene()
        val card = focusCard(s, 3)
        val up = card.focusSearch(View.FOCUS_UP)
        println("поиск вверх -> ${where(s, up)}")
        assertEquals("вверх должен вести на плашку сезонов", true, up is SeasonPlateView)
    }
}
