package com.marsel.wledplayer

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Выбирает, какой движок подсветки сейчас работает: классический или новый (v2).
 *
 * Выбор лежит в настройке «ambilight_engine» (по умолчанию классический). Менять её можно в
 * любой момент, в том числе во время просмотра: прежний движок полностью останавливается
 * (потоки, сокет, захват кадров), лента гасится, и только потом запускается выбранный.
 * Одновременно на ленту всегда отправляет только один движок.
 *
 * Снаружи класс выглядит как прежний AmbilightController: те же конструктор и методы.
 */
@OptIn(UnstableApi::class)
class AmbilightController(
    private val activity: Activity,
    initialPlayerView: PlayerView,
    private val prefs: SharedPreferences
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Останавливать движок может быть долго (ждём завершения потока), поэтому не в главном потоке
    private val switcher: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AmbilightSwitch")
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView = initialPlayerView
    private var isSurface = false

    private var engine: AmbilightEngine? = null
    private var engineKind: String? = null
    private var started = false
    private var released = false
    private var switchGeneration = 0

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ENGINE && started && !released) selectEngine()
    }

    fun attachPlayer(player: ExoPlayer) {
        this.player = player
        engine?.attachPlayer(player)
    }

    fun updatePlayerView(view: PlayerView, isSurface: Boolean) {
        this.playerView = view
        this.isSurface = isSurface
        engine?.updatePlayerView(view, isSurface)
    }

    fun start() {
        if (started) return
        started = true
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        selectEngine()
    }

    fun release() {
        if (released) return
        released = true
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        switchGeneration++

        val old = engine
        engine = null
        engineKind = null
        switcher.execute { old?.release() }
        switcher.shutdown()
    }

    /** Запускает нужный движок; если сейчас работает другой, сначала останавливает его и гасит ленту. */
    private fun selectEngine() {
        val wanted = wantedKind()
        if (wanted == engineKind) return

        val old = engine
        engine = null
        engineKind = wanted
        val generation = ++switchGeneration

        switcher.execute {
            if (old != null) {
                old.release()
                Ddp.sendBlank(
                    ip = rawStringValue(prefs, "wled_ip", "192.168.1.110"),
                    port = intPref("udp_port", 4048),
                    ledCount = intPref("leds_top", 15) + intPref("leds_bottom", 15) +
                        intPref("leds_left", 10) + intPref("leds_right", 10)
                )
            }
            mainHandler.post {
                // За время остановки выбор мог поменяться ещё раз или приложение закрыли
                if (!released && generation == switchGeneration) startEngine(wanted)
            }
        }
    }

    private fun startEngine(kind: String) {
        val created: AmbilightEngine = if (kind == ENGINE_V2) {
            AmbilightEngineV2(activity, prefs)
        } else {
            ClassicAmbilightEngine(activity, playerView, prefs)
        }
        player?.let { created.attachPlayer(it) }
        created.updatePlayerView(playerView, isSurface)
        engine = created
        created.start()
    }

    private fun wantedKind(): String =
        if (rawStringValue(prefs, KEY_ENGINE, ENGINE_CLASSIC) == ENGINE_V2) ENGINE_V2 else ENGINE_CLASSIC

    private fun intPref(key: String, default: Int): Int =
        rawStringValue(prefs, key, default.toString()).toIntOrNull() ?: default

    private companion object {
        const val KEY_ENGINE = "ambilight_engine"
        const val ENGINE_CLASSIC = "classic"
        const val ENGINE_V2 = "v2"
    }
}
