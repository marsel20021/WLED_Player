package com.marsel.wledplayer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
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
import com.marsel.wledplayer.BuildConfig
import com.marsel.wledplayer.R
import com.marsel.wledplayer.audio.AudioTrackController
import com.marsel.wledplayer.media.EpisodeFrame
import com.marsel.wledplayer.media.EpisodeFrames
import com.marsel.wledplayer.media.FramesResult
import com.marsel.wledplayer.media.ImageLoader
import com.marsel.wledplayer.media.PosterPalette
import com.marsel.wledplayer.skip.EpisodeParser
import com.marsel.wledplayer.skip.SegmentMarks
import com.marsel.wledplayer.skip.SharedPrefsStore
import com.marsel.wledplayer.skip.SkipHttp
import com.marsel.wledplayer.ui.overlay.EpisodeItem
import com.marsel.wledplayer.ui.overlay.OverlayActions
import com.marsel.wledplayer.ui.overlay.OverlayModel
import com.marsel.wledplayer.ui.overlay.PlayerOverlay
import com.marsel.wledplayer.ambilight.AmbilightController
import com.marsel.wledplayer.audio.SmartAudioController
import com.marsel.wledplayer.data.rawBooleanValue
import com.marsel.wledplayer.data.rawStringValue
import com.marsel.wledplayer.player.PlaybackIntentParser
import com.marsel.wledplayer.player.PlaylistController
import com.marsel.wledplayer.skip.SkipController
import com.marsel.wledplayer.skip.SkipKind
import com.marsel.wledplayer.skip.SkipUiState
import com.marsel.wledplayer.update.AppUpdater

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

    // Автопропуск заставок
    private var skipController: SkipController? = null
    private var okConsumed = false
    private var seasonDialog: AlertDialog? = null
    private var seasonDialogFor: String? = null

    // Экран поверх видео (меню, лента серий, кнопка пропуска)
    private lateinit var overlay: PlayerOverlay
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTick = object : Runnable {
        override fun run() {
            updateOverlay()
            uiHandler.postDelayed(this, 500)
        }
    }

    // Кадры серий: грузим один раз на сезон, в кэше лежат 30 дней
    private val frameIo = Executors.newSingleThreadExecutor()
    private val framesRepo by lazy {
        EpisodeFrames(BuildConfig.KINOPOISKDEV_TOKEN, SharedPrefsStore(this, "frames_cache"), { url, headers -> SkipHttp.get(url, headers) })
    }
    private var framesFor: String? = null
    private var framesRetryAt = 0L
    private var frames: List<EpisodeFrame> = emptyList()
    private var episodesKey: String? = null
    private var paletteFor: String? = null
    private val audioTrackController by lazy { AudioTrackController(this) }

    private val overlayActions = object : OverlayActions {
        override fun playPause() {
            player?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
        }

        override fun setPlaying(play: Boolean) {
            player?.let { if (play) it.play() else it.pause() }
        }

        override fun seekBy(deltaMs: Long) {
            val p = player ?: return
            var target = p.currentPosition + deltaMs
            val dur = p.duration
            if (dur != C.TIME_UNSET && dur > 0) target = target.coerceAtMost(dur - 1000)
            p.seekTo(target.coerceAtLeast(0L))
        }

        override fun prev() { player?.seekToPreviousMediaItem() }
        override fun next() { player?.seekToNextMediaItem() }

        override fun playIndex(index: Int) {
            val p = player ?: return
            if (index in 0 until p.mediaItemCount) {
                p.seekTo(index, 0L)
                p.play()
            }
        }

        override fun toggleLed() {
            val on = rawBooleanValue(appPrefs, "ambilight_enabled", true)
            appPrefs.edit().putBoolean("ambilight_enabled", !on).apply()
            updateOverlay()
        }

        override fun openSettings() {
            openingSettings = true // уход в настройки - это не выключение ТВ
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_ACCENT, overlay.currentAccent))
        }

        override fun openAudio() {
            player?.let { audioTrackController.show(it) }
        }
    }

    // Название фильма (extra "title" от Vokino) и сводка extras - уходят на пульт для поиска постера
    private var movieTitle: String? = null
    private var intentInfo: String = ""

    private lateinit var appPrefs: SharedPreferences

    // Закрытие плеера при выключении ТВ (режим ожидания)
    private var lastBackPressAt = 0L
    private var closingForStandby = false
    private var openingSettings = false
    private var screenOffReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "WledPlayer"
        private const val EXIT_CONFIRM_MS = 2000L // за сколько секунд нужно нажать «назад» второй раз
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
        overlay = PlayerOverlay(this, findViewById<FrameLayout>(R.id.overlayRoot), overlayActions)

        ambilightController = AmbilightController(this, playerViewTexture, appPrefs)
        playlistController = PlaylistController(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Идёт отсчёт автопропуска - «назад» отменяет его (кнопка останется)
                val offer = skipController?.state?.offer
                if (offer != null && !offer.manual) {
                    skipController?.cancel()
                    return
                }
                // 1) Меню или лента на экране - «назад» прячет их.
                // 2) Ничего нет - первое «назад» просит подтверждение, второе (в течение 2 с) закрывает плеер.
                if (overlay.mode != PlayerOverlay.Mode.HIDDEN) {
                    overlay.hide()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressAt <= EXIT_CONFIRM_MS) {
                    playlistController?.finishOnUserExit(player, returnResultRequested)
                } else {
                    lastBackPressAt = now
                    Toast.makeText(this@MainActivity, "Нажмите «назад» ещё раз для выхода", Toast.LENGTH_SHORT).show()
                }
            }
        })

        registerScreenOffReceiver()

        initializePlayer(intent)
        uiHandler.post(uiTick)
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Поставили на паузу - показываем меню, чтобы было видно время и кнопки
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !isFinishing) {
                    overlay.show(PlayerOverlay.Mode.MENU)
                }
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
            movieTitle = incoming.movieTitle
            intentInfo = incoming.intentInfo
            controller.start(player!!, incoming)
        }

        player?.let { p ->
            skipController = SkipController(this, p, appPrefs, { movieTitle }) { renderSkip(it) }.also { it.start() }
        }
    }

    /** Кнопка пропуска (или «Вернуть» после прыжка): то же, что нажатие OK на пульте. */
    private fun skipPressed() {
        val c = skipController ?: return
        val state = c.state
        when {
            state.offer != null -> c.skipNow()
            state.undo != null -> c.undo()
        }
    }

    private fun renderSkip(state: SkipUiState) {
        val offer = state.offer
        when {
            offer != null -> {
                val what = when (offer.kind) {
                    SkipKind.INTRO -> "заставку"
                    SkipKind.RECAP -> "пересказ"
                    SkipKind.CREDITS -> "титры"
                }
                val text = if (offer.manual) "Пропустить $what" else "Пропустить $what · ${offer.secondsLeft}"
                overlay.setSkip(text, if (offer.manual) 0f else offer.progress, true)
            }
            state.undo != null -> overlay.setSkip("Вернуть", 0f, false)
            else -> overlay.setSkip(null, 0f, true)
        }

        maybeLoadFrames()
        maybeLoadPalette()

        // Сезон не удалось определить: спрашиваем один раз для этого списка серий
        val choice = state.seasonChoice
        if (choice != null && choice.selected == null) {
            val key = choice.seasons.joinToString(",")
            if (seasonDialogFor != key && seasonDialog?.isShowing != true && !isFinishing) {
                seasonDialogFor = key
                seasonDialog = AlertDialog.Builder(this)
                    .setTitle("Какой это сезон?")
                    .setItems(choice.seasons.map { "Сезон $it" }.toTypedArray()) { _, which ->
                        skipController?.pickSeason(choice.seasons[which])
                    }
                    .show()
            }
        }
    }

    /** Когда стал известен сериал и сезон - один раз грузим кадры серий. */
    private fun maybeLoadFrames() {
        val c = skipController ?: return
        val info = c.currentSeries ?: return
        val season = c.currentSeason ?: return
        val key = "${info.kinopoiskId}:$season"
        if (key == framesFor) return
        if (SystemClock.elapsedRealtime() < framesRetryAt) return
        framesFor = key
        frames = emptyList()
        val titles = listOfNotNull(info.title, movieTitle)
        frameIo.execute {
            val result = try {
                framesRepo.load(info.kinopoiskId, titles, info.year, season)
            } catch (e: Exception) {
                FramesResult.Failed
            }
            runOnUiThread {
                if (isFinishing || framesFor != key) return@runOnUiThread
                when (result) {
                    is FramesResult.Found -> frames = result.frames
                    FramesResult.NoData -> {}
                    FramesResult.Failed -> {
                        // сеть не ответила: попробуем снова через минуту
                        framesFor = null
                        framesRetryAt = SystemClock.elapsedRealtime() + 60_000
                    }
                }
                episodesKey = null
            }
        }
    }

    /** Цвет акцента берём из постера сериала. Если постера нет, остаётся тёплый по умолчанию. */
    private fun maybeLoadPalette() {
        val url = skipController?.currentSeries?.posterUrl ?: return
        if (url == paletteFor) return
        paletteFor = url
        ImageLoader.load(url, 200) { bmp ->
            if (bmp != null && paletteFor == url && !isFinishing) {
                overlay.setAccent(PosterPalette.fromBitmap(bmp).accent)
            }
        }
    }

    /** Раз в полсекунды отдаёт экрану состояние плеера. */
    private fun updateOverlay() {
        val p = player ?: return
        val itemTitle = p.mediaMetadata.title?.toString().orEmpty()
        val ref = EpisodeParser.parse(itemTitle)
        val season = skipController?.currentSeason ?: ref?.season
        val subtitle = if (ref != null) (season?.let { "Сезон $it · " } ?: "") + "Серия ${ref.episode}" else ""
        val title = movieTitle?.takeIf { it.isNotBlank() } ?: itemTitle
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        overlay.update(
            OverlayModel(
                title = title,
                subtitle = subtitle,
                positionMs = p.currentPosition,
                durationMs = duration,
                playing = p.playWhenReady,
                ledOn = rawBooleanValue(appPrefs, "ambilight_enabled", true),
                currentIndex = p.currentMediaItemIndex,
                marks = SegmentMarks.of(skipController?.currentTimeline, duration)
            )
        )

        // Список серий для ленты: пересобираем, только если что-то поменялось
        val key = "${p.mediaItemCount}|$framesFor|${frames.size}|$season"
        if (key != episodesKey) {
            episodesKey = key
            val byNumber = frames.associateBy { it.number }
            val items = (0 until p.mediaItemCount).map { i ->
                val t = p.getMediaItemAt(i).mediaMetadata.title?.toString() ?: "Видео ${i + 1}"
                val num = EpisodeParser.parse(t)?.episode
                val frame = num?.let { byNumber[it] }
                EpisodeItem(i, if (num != null) "Серия $num" else t, frame?.name, frame?.imageUrl)
            }
            overlay.setEpisodes(items, if (season != null) "Сезон $season" else "")
        }
    }

    // OK на пульте: если на экране кнопка пропуска (или «Вернуть») и меню закрыто, нажимает её.
    // Всё остальное (меню, лента, перемотка) разбирает PlayerOverlay.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk) {
            val state = skipController?.state
            val hasButton = state != null && (state.offer != null || state.undo != null)
            val menuClosed = overlay.mode == PlayerOverlay.Mode.HIDDEN || overlay.mode == PlayerOverlay.Mode.SEEK
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && hasButton && menuClosed) {
                okConsumed = true
                skipPressed()
                return true
            }
            if (okConsumed) {
                if (event.action == KeyEvent.ACTION_UP) okConsumed = false
                return true
            }
        }
        if (overlay.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Экран погас (ТВ ушёл в ожидание): штатно закрываем плеер. */
    private fun registerScreenOffReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                closeForStandby()
            }
        }
        screenOffReceiver = receiver
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Останавливает звук и закрывает плеер так же, как кнопка «назад»:
     * позиция и длительность уходят вызвавшему приложению (Vokino) для таймкодов.
     */
    private fun closeForStandby() {
        if (closingForStandby || isFinishing) return
        closingForStandby = true
        player?.pause() // звук глохнет сразу
        playlistController?.finishOnUserExit(player, returnResultRequested)
    }

    override fun onResume() {
        super.onResume()
        openingSettings = false
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || isFinishing || openingSettings) return

        // Окно ушло с экрана. Звук в фоне телевизору не нужен - ставим на паузу.
        player?.pause()

        // Если при этом устройство ушло в сон (не "Домой", не настройки) - закрываем плеер.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) closeForStandby()
    }

    override fun onDestroy() {
        super.onDestroy()
        screenOffReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) { }
        }
        screenOffReceiver = null
        uiHandler.removeCallbacksAndMessages(null)
        overlay.release()
        frameIo.shutdownNow()
        skipController?.stop()
        skipController = null
        seasonDialog?.dismiss()
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
                        put("labVivid", rawStringValue(appPrefs, "lab_vivid", "50").toIntOrNull() ?: 50)
                        put("labSpeed", rawStringValue(appPrefs, "lab_speed", "50").toIntOrNull() ?: 50)
                        put("labEdge", rawStringValue(appPrefs, "lab_edge", "50").toIntOrNull() ?: 50)
                        put("labDepth", rawStringValue(appPrefs, "lab_depth", "22").toIntOrNull() ?: 22)
                        put("labHighlight", rawStringValue(appPrefs, "lab_highlight", "soft"))
                        put("labGlow", rawStringValue(appPrefs, "lab_glow", "mid"))
                        put("labJitter", rawStringValue(appPrefs, "lab_jitter", "mid"))
                        put("labFps", rawStringValue(appPrefs, "lab_fps", "60"))
                        put("labDetectBars", rawBooleanValue(appPrefs, "lab_detect_bars", false))
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
                                state.put("movieTitle", movieTitle ?: "")
                                state.put("intentInfo", intentInfo)
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
                                                if (engine == "classic" || engine == "v2" || engine == "lab") editor.putString("ambilight_engine", engine)
                                            }
                                            if (json.has("v2Smoothing")) editor.putString("v2_smoothing", json.getInt("v2Smoothing").toString())
                                            if (json.has("v2Saturation")) editor.putString("v2_saturation", json.getInt("v2Saturation").toString())
                                            if (json.has("v2BlackThreshold")) editor.putString("v2_black_threshold", json.getInt("v2BlackThreshold").toString())
                                            if (json.has("v2DetectBars")) editor.putBoolean("v2_detect_bars", json.getBoolean("v2DetectBars"))

                                            // Lab: числа с проверкой границ, выбор только из известных значений
                                            if (json.has("labVivid")) editor.putString("lab_vivid", json.getInt("labVivid").coerceIn(0, 100).toString())
                                            if (json.has("labSpeed")) editor.putString("lab_speed", json.getInt("labSpeed").coerceIn(0, 100).toString())
                                            if (json.has("labEdge")) editor.putString("lab_edge", json.getInt("labEdge").coerceIn(0, 100).toString())
                                            if (json.has("labDepth")) editor.putString("lab_depth", json.getInt("labDepth").coerceIn(5, 50).toString())
                                            if (json.has("labHighlight")) {
                                                val v = json.getString("labHighlight")
                                                if (v == "soft" || v == "accent") editor.putString("lab_highlight", v)
                                            }
                                            if (json.has("labGlow")) {
                                                val v = json.getString("labGlow")
                                                if (v in listOf("off", "low", "mid", "high", "max")) editor.putString("lab_glow", v)
                                            }
                                            if (json.has("labJitter")) {
                                                val v = json.getString("labJitter")
                                                if (v == "off" || v == "mid" || v == "high") editor.putString("lab_jitter", v)
                                            }
                                            if (json.has("labFps")) {
                                                val v = json.get("labFps").toString()
                                                if (v == "30" || v == "60") editor.putString("lab_fps", v)
                                            }
                                            if (json.has("labDetectBars")) editor.putBoolean("lab_detect_bars", json.getBoolean("labDetectBars"))

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