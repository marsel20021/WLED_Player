package com.marsel.wledplayer.ui.settings

/** Одна строка настроек. key - ключ в SharedPreferences, как и раньше (старые значения подхватываются). */
sealed interface SettingRow {
    val key: String
    val title: String
}

data class ToggleRow(override val key: String, override val title: String, val def: Boolean) : SettingRow

/** Число. Значение хранится строкой, как у прежних EditTextPreference. */
data class StepperRow(
    override val key: String,
    override val title: String,
    val def: Int,
    val min: Int,
    val max: Int,
    val step: Int,
    val unit: String = ""
) : SettingRow

/** Выбор из списка: Влево/Вправо перебирают варианты. options: значение -> подпись. */
data class ChoiceRow(
    override val key: String,
    override val title: String,
    val def: String,
    val options: List<Pair<String, String>>
) : SettingRow

/** Текст (IP, порт): вводится в окошке. */
data class TextRow(
    override val key: String,
    override val title: String,
    val def: String,
    val numeric: Boolean = false
) : SettingRow

class SettingsTab(val title: String, val rows: (engine: String) -> List<SettingRow>)

object SettingsSpec {
    const val ENGINE_KEY = "ambilight_engine"

    private val engineChoice = ChoiceRow(
        ENGINE_KEY, "Движок подсветки", "classic",
        listOf("classic" to "Классический", "v2" to "Новый (v2)", "lab" to "Lab (тестовый)")
    )

    private fun engineRows(engine: String): List<SettingRow> = when (engine) {
        "v2" -> listOf(
            StepperRow("v2_smoothing", "Плавность", 50, 0, 100, 5),
            StepperRow("v2_saturation", "Насыщенность", 115, 50, 200, 5, "%"),
            StepperRow("v2_black_threshold", "Порог чёрного", 15, 0, 255, 5),
            ToggleRow("v2_detect_bars", "Чёрные полосы", false)
        )
        "lab" -> listOf(
            StepperRow("lab_vivid", "Яркость цвета", 50, 0, 100, 5),
            StepperRow("lab_speed", "Скорость реакции", 50, 0, 100, 5),
            StepperRow("lab_edge", "Вес к краю", 50, 0, 100, 5),
            StepperRow("lab_depth", "Глубина захвата", 22, 5, 50, 1, "%"),
            ChoiceRow("lab_highlight", "Яркие блики", "soft", listOf("soft" to "Приглушать", "accent" to "Акцент")),
            ChoiceRow(
                "lab_glow", "Свет в темноте", "mid",
                listOf("off" to "Выключен", "low" to "Едва заметный", "mid" to "Средний", "high" to "Заметный", "max" to "Максимум")
            ),
            ChoiceRow("lab_jitter", "Защита от дрожи", "mid", listOf("off" to "Выключена", "mid" to "Средняя", "high" to "Сильная")),
            ChoiceRow("lab_fps", "Частота вывода", "60", listOf("60" to "60 Гц", "30" to "30 Гц")),
            ToggleRow("lab_detect_bars", "Чёрные полосы", false)
        )
        else -> emptyList()
    }

    val tabs: List<SettingsTab> = listOf(
        SettingsTab("Подсветка") {
            listOf(
                ToggleRow("ambilight_enabled", "Подсветка", true),
                StepperRow("brightness", "Яркость", 100, 0, 100, 5),
                StepperRow("smoothing", "Плавность", 50, 0, 100, 5),
                StepperRow("gamma", "Гамма", 22, 10, 30, 1),
                StepperRow("fps", "Частота кадров", 30, 10, 60, 5),
                StepperRow("black_threshold", "Порог чёрного", 15, 0, 255, 5)
            )
        },
        SettingsTab("Изображение") {
            listOf(
                ChoiceRow(
                    "capture_mode", "Захват кадра", "auto",
                    listOf("auto" to "Авто", "texture_view" to "TextureView (SDR)", "pixel_copy" to "PixelCopy (4K/HDR)")
                ),
                StepperRow("buffer_size_sec", "Буфер 4K", 50, 10, 120, 5, " с"),
                StepperRow("margin_x", "Отступ слева/справа", 2, 0, 20, 1, "%"),
                StepperRow("margin_y", "Отступ сверху/снизу", 2, 0, 20, 1, "%"),
                StepperRow("capture_depth", "Глубина захвата", 5, 1, 30, 1, "%")
            )
        },
        SettingsTab("Движок") { engine -> listOf<SettingRow>(engineChoice) + engineRows(engine) },
        SettingsTab("Лента") {
            listOf(
                TextRow("wled_ip", "IP адрес ESP32", "192.168.1.110"),
                TextRow("udp_port", "UDP порт", "4048", numeric = true),
                ToggleRow("use_v2", "Протокол V2", true),
                StepperRow("leds_top", "Светодиодов сверху", 15, 0, 200, 1),
                StepperRow("leds_bottom", "Светодиодов снизу", 15, 0, 200, 1),
                StepperRow("leds_left", "Светодиодов слева", 10, 0, 200, 1),
                StepperRow("leds_right", "Светодиодов справа", 10, 0, 200, 1),
                ChoiceRow("direction", "Направление", "0", listOf("0" to "По часовой", "1" to "Против часовой")),
                StepperRow("led_offset", "Смещение", 0, 0, 300, 1)
            )
        },
        SettingsTab("Пропуск") {
            listOf(
                ToggleRow("skip_enabled", "Автопропуск", true),
                ToggleRow("skip_recap", "Пропускать пересказ", true),
                ToggleRow("skip_hold", "Держать кнопку", true)
            )
        }
    )

    /** Новое значение числа после нажатия Влево/Вправо. times - сколько шагов (при удержании больше). */
    fun stepped(row: StepperRow, current: Int, direction: Int, times: Int = 1): Int {
        val v = current + direction * row.step * times.coerceAtLeast(1)
        return v.coerceIn(row.min, row.max)
    }

    /** Следующий вариант в списке (по кругу). Неизвестное текущее значение считается первым. */
    fun nextChoice(row: ChoiceRow, current: String, direction: Int): String {
        val i = row.options.indexOfFirst { it.first == current }.let { if (it < 0) 0 else it }
        val n = row.options.size
        return row.options[((i + direction) % n + n) % n].first
    }
}
