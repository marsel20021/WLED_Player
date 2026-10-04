package com.marsel.wledplayer

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.ColorInfo
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.json.JSONObject
import org.json.JSONArray
import io.ktor.server.application.install
import io.ktor.server.routing.get
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import io.ktor.server.application.call

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    private val smartAudioController = SmartAudioController(this)
    private lateinit var ambilightController: AmbilightController
    private var serverEngine: ApplicationEngine? = null

    // Два плеера для переключения на лету
    private lateinit var playerViewTexture: PlayerView
    private lateinit var playerViewSurface: PlayerView

    private var playlistController: PlaylistController? = null
    private var returnResultRequested = false

    private lateinit var appPrefs: SharedPreferences

    companion object {
        private const val TAG = "WledPlayer"
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Сначала инициализируем настройки, они нужны для записи краша
        appPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        // --- ЛОВУШКА ДЛЯ СМЕРТЕЛЬНЫХ КРАШЕЙ ---
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Собираем лог ошибки
            val stackTrace = android.util.Log.getStackTraceString(throwable)
            // Жестко и синхронно (commit) сохраняем в память перед тем как умереть
            appPrefs.edit().putString("last_crash_log", stackTrace).commit()
            // Отдаем управление системе, чтобы она закрыла приложение
            defaultHandler?.uncaughtException(thread, throwable)
        }
        // --------------------------------------

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.hide()
        hideSystemBars()
        setContentView(R.layout.activity_main)

        playerViewTexture = findViewById(R.id.playerViewTexture)
        playerViewSurface = findViewById(R.id.playerViewSurface)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val visibilityListener = PlayerView.ControllerVisibilityListener { visibility ->
            btnSettings.visibility = visibility
        }
        playerViewTexture.setControllerVisibilityListener(visibilityListener)
        playerViewSurface.setControllerVisibilityListener(visibilityListener)

        ambilightController = AmbilightController(this, playerViewTexture, appPrefs)
        playlistController = PlaylistController(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                playlistController?.finishOnUserExit(player, returnResultRequested)
            }
        })

        initializePlayer(intent)
        ambilightController.start()
        AppUpdater(this).check()
        startRemoteServer()
    }

    private fun updatePlayerSurfaceMode() {
        val format = player?.videoFormat ?: return
        val modeStr = rawStringValue(appPrefs, "capture_mode", "auto")

        val useSurface = when (modeStr) {
            "pixel_copy" -> true
            "texture_view" -> false
            else -> {
                val isHighRes = Math.max(format.width, format.height) >= 3000
                val isHdr = ColorInfo.isTransferHdr(format.colorInfo)
                isHighRes || isHdr
            }
        }

        if (useSurface) {
            if (playerViewSurface.visibility != View.VISIBLE) {
                playerViewTexture.player = null
                playerViewTexture.visibility = View.GONE

                playerViewSurface.visibility = View.VISIBLE
                playerViewSurface.player = player
                ambilightController.updatePlayerView(playerViewSurface, isSurface = true)
            }
        } else {
            if (playerViewTexture.visibility != View.VISIBLE) {
                playerViewSurface.player = null
                playerViewSurface.visibility = View.GONE

                playerViewTexture.visibility = View.VISIBLE
                playerViewTexture.player = player
                ambilightController.updatePlayerView(playerViewTexture, isSurface = false)
            }
        }
    }

    private fun initializePlayer(sourceIntent: Intent) {
        val incoming = PlaybackIntentParser.parse(sourceIntent)
        val controller = playlistController ?: return

        val bufferSec = rawStringValue(appPrefs, "buffer_size_sec", "50").toIntOrNull() ?: 50
        player = controller.buildPlayer(incoming?.headers, bufferSec)

        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updatePlayerSurfaceMode()
            }
        })

        playerViewTexture.player = player
        player?.let { ambilightController.attachPlayer(it) }

        val smartAudioEnabled = rawBooleanValue(appPrefs, "smart_audio_enabled", true)
        if (smartAudioEnabled) {
            player?.audioSessionId?.let { smartAudioController.attachTo(it) }
        }

        if (incoming != null) {
            returnResultRequested = incoming.returnResult
            controller.start(player!!, incoming)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ambilightController.release()
        smartAudioController.release()
        serverEngine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        player?.release()
    }

    private fun startRemoteServer() {
        serverEngine = embeddedServer(CIO, port = 8080) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    val snapshot = JSONObject().apply {
                        put("type", "settingsSnapshot")
                        put("wledIp", rawStringValue(appPrefs, "wled_ip", "192.168.1.110"))
                        put("udpPort", rawStringValue(appPrefs, "udp_port", "4048"))
                        put("brightness", rawStringValue(appPrefs, "brightness", "100").toIntOrNull() ?: 100)
                        put("gamma", rawStringValue(appPrefs, "gamma", "22").toIntOrNull() ?: 22)
                        put("fps", rawStringValue(appPrefs, "fps", "30").toIntOrNull() ?: 30)
                        put("smoothing", rawStringValue(appPrefs, "smoothing", "50").toIntOrNull() ?: 50)
                        put("blackThreshold", rawStringValue(appPrefs, "black_threshold", "15").toIntOrNull() ?: 15)
                        put("useV2", rawBooleanValue(appPrefs, "use_v2", true))
                        put("marginX", rawStringValue(appPrefs, "margin_x", "2").toIntOrNull() ?: 2)
                        put("marginY", rawStringValue(appPrefs, "margin_y", "2").toIntOrNull() ?: 2)
                        put("captureDepth", rawStringValue(appPrefs, "capture_depth", "5").toIntOrNull() ?: 5)
                        put("ledsTop", rawStringValue(appPrefs, "leds_top", "15").toIntOrNull() ?: 15)
                        put("ledsBottom", rawStringValue(appPrefs, "leds_bottom", "15").toIntOrNull() ?: 15)
                        put("ledsLeft", rawStringValue(appPrefs, "leds_left", "10").toIntOrNull() ?: 10)
                        put("ledsRight", rawStringValue(appPrefs, "leds_right", "10").toIntOrNull() ?: 10)
                        put("ledOffset", rawStringValue(appPrefs, "led_offset", "0").toIntOrNull() ?: 0)
                        put("smartAudioEnabled", rawBooleanValue(appPrefs, "smart_audio_enabled", true))
                        put("ambilightEnabled", rawBooleanValue(appPrefs, "ambilight_enabled", true))
                        put("captureMode", rawStringValue(appPrefs, "capture_mode", "auto"))
                        put("bufferSizeSec", rawStringValue(appPrefs, "buffer_size_sec", "50").toIntOrNull() ?: 50)
                        put("ambilightEngine", rawStringValue(appPrefs, "ambilight_engine", "classic"))
                        put("v2Smoothing", rawStringValue(appPrefs, "v2_smoothing", "50").toIntOrNull() ?: 50)
                        put("v2Saturation", rawStringValue(appPrefs, "v2_saturation", "115").toIntOrNull() ?: 115)
                        put("v2BlackThreshold", rawStringValue(appPrefs, "v2_black_threshold", "15").toIntOrNull() ?: 15)
                        put("v2DetectBars", rawBooleanValue(appPrefs, "v2_detect_bars", false))
                    }
                    try { send(Frame.Text(snapshot.toString())) } catch (e: Exception) { }

                    val playlistJson = JSONObject().apply {
                        put("type", "playlist")
                        val items = JSONArray()
                        withContext(Dispatchers.Main) {
                            player?.let { p ->
                                for (i in 0 until p.mediaItemCount) {
                                    val title = p.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Видео ${i + 1}"
                                    items.put(title)
                                }
                            }
                        }
                        put("items", items)
                    }
                    try { send(Frame.Text(playlistJson.toString())) } catch (e: Exception) { }

                    val job = launch {
                        while (true) {
                            val state = JSONObject()
                            withContext(Dispatchers.Main) {
                                state.put("type", "playbackState")
                                state.put("isPlaying", player?.isPlaying == true)
                                state.put("positionMs", player?.currentPosition ?: 0L)
                                state.put("durationMs", player?.duration ?: 0L)
                                state.put("title", player?.mediaMetadata?.title?.toString() ?: "Без названия")
                                state.put("currentIndex", player?.currentMediaItemIndex ?: 0)
                            }
                            try {
                                send(Frame.Text(state.toString()))
                            } catch (e: Exception) {
                            }
                            delay(1000)
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val msg = try { JSONObject(text) } catch (e: Exception) { null }

                                msg?.let { json ->
                                    when (json.optString("action")) {
                                        "playPause" -> withContext(Dispatchers.Main) {
                                            if (player?.isPlaying == true) player?.pause() else player?.play()
                                        }
                                        "seek" -> withContext(Dispatchers.Main) {
                                            val pos = json.optLong("position", -1L)
                                            if (pos >= 0) player?.seekTo(pos)
                                        }
                                        "skipNext" -> withContext(Dispatchers.Main) {
                                            player?.seekToNext()
                                        }
                                        "skipPrev" -> withContext(Dispatchers.Main) {
                                            player?.seekToPrevious()
                                        }
                                        "playIndex" -> withContext(Dispatchers.Main) {
                                            val idx = json.optInt("index", -1)
                                            val count = player?.mediaItemCount ?: 0
                                            if (idx in 0 until count) {
                                                player?.seekTo(idx, 0L)
                                            }
                                        }
                                        "settingsUpdate" -> {
                                            val editor = appPrefs.edit()
                                            var surfaceModeChanged = false

                                            if (json.has("wledIp")) editor.putString("wled_ip", json.getString("wledIp"))
                                            if (json.has("udpPort")) editor.putString("udp_port", json.getString("udpPort"))
                                            if (json.has("brightness")) editor.putString("brightness", json.getInt("brightness").toString())
                                            if (json.has("gamma")) editor.putString("gamma", json.getInt("gamma").toString())
                                            if (json.has("fps")) editor.putString("fps", json.getInt("fps").toString())
                                            if (json.has("ledsTop")) editor.putString("leds_top", json.getInt("ledsTop").toString())
                                            if (json.has("ledsBottom")) editor.putString("leds_bottom", json.getInt("ledsBottom").toString())
                                            if (json.has("ledsLeft")) editor.putString("leds_left", json.getInt("ledsLeft").toString())
                                            if (json.has("ledsRight")) editor.putString("leds_right", json.getInt("ledsRight").toString())
                                            if (json.has("ledOffset")) editor.putString("led_offset", json.getInt("ledOffset").toString())
                                            if (json.has("captureDepth")) editor.putString("capture_depth", json.getInt("captureDepth").toString())
                                            if (json.has("marginX")) editor.putString("margin_x", json.getInt("marginX").toString())
                                            if (json.has("marginY")) editor.putString("margin_y", json.getInt("marginY").toString())
                                            if (json.has("smoothing")) editor.putString("smoothing", json.getInt("smoothing").toString())
                                            if (json.has("blackThreshold")) editor.putString("black_threshold", json.getInt("blackThreshold").toString())
                                            if (json.has("useV2")) editor.putBoolean("use_v2", json.getBoolean("useV2"))
                                            if (json.has("ambilightEnabled")) editor.putBoolean("ambilight_enabled", json.getBoolean("ambilightEnabled"))

                                            if (json.has("bufferSizeSec")) editor.putString("buffer_size_sec", json.getInt("bufferSizeSec").toString())

                                            // Движок подсветки: допускаем только известные значения
                                            if (json.has("ambilightEngine")) {
                                                val engine = json.getString("ambilightEngine")
                                                if (engine == "classic" || engine == "v2") editor.putString("ambilight_engine", engine)
                                            }
                                            if (json.has("v2Smoothing")) editor.putString("v2_smoothing", json.getInt("v2Smoothing").toString())
                                            if (json.has("v2Saturation")) editor.putString("v2_saturation", json.getInt("v2Saturation").toString())
                                            if (json.has("v2BlackThreshold")) editor.putString("v2_black_threshold", json.getInt("v2BlackThreshold").toString())
                                            if (json.has("v2DetectBars")) editor.putBoolean("v2_detect_bars", json.getBoolean("v2DetectBars"))

                                            if (json.has("captureMode")) {
                                                editor.putString("capture_mode", json.getString("captureMode"))
                                                surfaceModeChanged = true
                                            }

                                            if (json.has("smartAudioEnabled")) {
                                                val enabled = json.getBoolean("smartAudioEnabled")
                                                editor.putBoolean("smart_audio_enabled", enabled)

                                                withContext(Dispatchers.Main) {
                                                    player?.let { p ->
                                                        if (enabled) {
                                                            smartAudioController.attachTo(p.audioSessionId)
                                                        } else {
                                                            smartAudioController.release()
                                                        }
                                                    }
                                                }
                                            }
                                            editor.apply()

                                            if (surfaceModeChanged) {
                                                withContext(Dispatchers.Main) {
                                                    updatePlayerSurfaceMode()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        job.cancel()
                    }
                }

                // --- НОВЫЕ РОУТЫ ДЛЯ ДЕБАГА ЧЕРЕЗ БРАУЗЕР ---
                get("/logcat") {
                    try {
                        // Запускаем системную утилиту logcat, берем последние 1000 строк
                        val process = Runtime.getRuntime().exec("logcat -d -v threadtime -t 1000")
                        val log = process.inputStream.bufferedReader().use { it.readText() }
                        call.respondText(log.ifEmpty { "Лог пуст" }, ContentType.Text.Plain)
                    } catch (e: Exception) {
                        call.respondText("Ошибка чтения логов: ${e.message}", ContentType.Text.Plain)
                    }
                }

                get("/crash") {
                    // Достаем последний сохраненный краш
                    val crash = appPrefs.getString("last_crash_log", "Крашей пока не было, ура!") ?: "Пусто"
                    call.respondText(crash, ContentType.Text.Plain)
                }
                // --------------------------------------------
            }
        }
        serverEngine?.start(wait = false)
    }
}