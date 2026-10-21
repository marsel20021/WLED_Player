package com.marsel.wledplayer

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.ColorInfo
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Тестовый движок подсветки Lab. Это «оболочка»: захват кадра, ритм вывода, отправка по DDP, пауза.
 * Весь цвет (веса к краю, линейный свет, блики, фон, сглаживание, дизеринг) считает [LabPipeline].
 *
 * Отличия от v2:
 *  - захват 128x72 вместо 96x54;
 *  - кадры берём не чаще ~30 раз в секунду, а на ленту выводим с частотой «lab_fps» (30 или 60):
 *    между кадрами цвета плавно доезжают до цели, а при 60 Гц включается дизеринг в темноте;
 *  - на паузе лента не гаснет: последний кадр повторяется раз в полсекунды, чтобы WLED не вышел
 *    из режима реального времени.
 *
 * Чтобы перенести удачные части в v2, достаточно заменить в нём расчёт цвета вызовами LabPipeline.
 */
@OptIn(UnstableApi::class)
class AmbilightEngineLab(
    private val activity: Activity,
    private val prefs: SharedPreferences
) : AmbilightEngine {

    private data class Config(
        val enabled: Boolean,
        val ip: String,
        val port: Int,
        val captureMode: String,
        val detectBars: Boolean,
        val params: LabParams,
        val geometry: LabGeometry
    )

    // ---------- Состояние, которое пишет главный поток, а читает рабочий ----------

    @Volatile private var player: ExoPlayer? = null
    @Volatile private var playerView: PlayerView? = null
    @Volatile private var isSurface = false
    @Volatile private var playing = false
    @Volatile private var hdrContent = false
    @Volatile private var highRes = false
    @Volatile private var running = false
    @Volatile private var settingsDirty = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null
    private val captureInFlight = AtomicBoolean(false)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        settingsDirty = true
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            refreshVideoInfo()
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshVideoInfo()
        }
    }

    // ---------- Состояние рабочего потока (трогает только он) ----------

    private val pipeline = LabPipeline(GRID_W, GRID_H)
    private var config: Config = readConfig()
    private var rgb = ByteArray(0)
    private val pixels = IntArray(GRID_W * GRID_H)
    private val bitmap8888: Bitmap by lazy { Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888) }
    private var bitmapF16: Bitmap? = null

    private var socket: DatagramSocket? = null
    private val packetBuf = ByteArray(Ddp.HEADER_SIZE + Ddp.MAX_LEDS_PER_PACKET * 3)
    private val packet = DatagramPacket(packetBuf, packetBuf.size)
    private var address: InetAddress? = null
    private var addressIp = ""
    private var addressRetryAt = 0L
    private var lastSendErrorAt = 0L

    // Чёрные полосы: применённые значения (в клетках сетки с каждой стороны) и кандидат
    private var appliedBarsV = 0
    private var appliedBarsH = 0
    private var candBarsV = 0
    private var candBarsH = 0
    private var candSinceMs = 0L

    // ---------- Интерфейс движка ----------

    override fun attachPlayer(player: ExoPlayer) {
        runOnMain {
            this.player?.removeListener(playerListener)
            this.player = player
            player.addListener(playerListener)
            playing = player.isPlaying
            refreshVideoInfo()
        }
    }

    override fun updatePlayerView(view: PlayerView, isSurface: Boolean) {
        this.playerView = view
        this.isSurface = isSurface
    }

    override fun start() {
        if (running) return
        running = true
        settingsDirty = true
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        pixelCopyThread = HandlerThread("AmbilightLabPixelCopy").also {
            it.start()
            pixelCopyHandler = Handler(it.looper)
        }
        worker = Thread({ runLoop() }, "AmbilightLab").also { it.start() }
    }

    override fun release() {
        running = false
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)

        val thread = worker
        worker = null
        thread?.interrupt()
        try {
            thread?.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        pixelCopyThread?.quitSafely()
        pixelCopyThread = null
        pixelCopyHandler = null

        val oldPlayer = player
        player = null
        mainHandler.post { oldPlayer?.removeListener(playerListener) }
    }

    // ---------- Основной цикл ----------

    private fun runLoop() {
        socket = try {
            DatagramSocket()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть UDP-сокет", e)
            null
        }
        applyConfig(readConfig())

        var wasActive = false
        var lastStepNs = 0L
        var nextTickNs = System.nanoTime()
        var nextCaptureNs = 0L
        var lastKeepAliveMs = 0L

        try {
            while (running) {
                if (settingsDirty) {
                    settingsDirty = false
                    applyConfig(readConfig())
                }
                val c = config

                // Подсветка выключена: один раз гасим ленту и спим
                if (!c.enabled || pipeline.zoneCount == 0) {
                    if (wasActive) {
                        sendBlank()
                        wasActive = false
                    }
                    pipeline.requestSnap()
                    if (!sleepMs(IDLE_SLEEP_MS)) break
                    nextTickNs = System.nanoTime()
                    continue
                }

                // Пауза: лента держит последний цвет; повторяем его, чтобы WLED не вышел из режима реального времени
                if (!playing) {
                    val now = SystemClock.elapsedRealtime()
                    if (wasActive && now - lastKeepAliveMs >= KEEPALIVE_MS) {
                        lastKeepAliveMs = now
                        sendFrame(pipeline.zoneCount)
                    }
                    pipeline.requestSnap()
                    if (!sleepMs(IDLE_SLEEP_MS)) break
                    nextTickNs = System.nanoTime()
                    lastStepNs = 0L
                    nextCaptureNs = 0L
                    continue
                }

                val periodNs = 1_000_000_000L / c.params.outputFps
                val captureEveryNs = max(periodNs, MIN_CAPTURE_PERIOD_NS)
                val nowNs = System.nanoTime()

                // Захват кадра: не чаще ~30 раз в секунду
                if (nowNs >= nextCaptureNs && captureFrame(c)) {
                    if (c.detectBars && updateBars()) {
                        pipeline.configure(c.params, geometryWithBars(c))
                        ensureOutputBuffer()
                    }
                    if (pipeline.ingest(pixels, hdrContent)) wasActive = true
                    nextCaptureNs = max(nextCaptureNs + captureEveryNs, nowNs)
                }

                // Вывод на ленту: каждый тик, цвета плавно доезжают до цели
                if (wasActive) {
                    val stepNs = System.nanoTime()
                    val dtMs = if (lastStepNs == 0L) FIRST_STEP_MS else (stepNs - lastStepNs) / 1_000_000f
                    lastStepNs = stepNs
                    pipeline.step(dtMs, rgb)
                    sendFrame(pipeline.zoneCount)
                }

                // Время следующего тика считаем от метки, чтобы ритм не проседал на длительности работы
                nextTickNs += periodNs
                val remainingNs = nextTickNs - System.nanoTime()
                if (remainingNs > 0) {
                    if (!sleepNanos(remainingNs)) break
                } else if (-remainingNs > 2 * periodNs) {
                    nextTickNs = System.nanoTime() // сильно отстали: не догоняем
                }
            }
        } finally {
            sendBlank()
            socket?.close()
            socket = null
        }
    }

    private fun sleepMs(ms: Long): Boolean = try {
        Thread.sleep(ms)
        running
    } catch (e: InterruptedException) {
        false
    }

    private fun sleepNanos(ns: Long): Boolean = try {
        Thread.sleep(ns / 1_000_000L, (ns % 1_000_000L).toInt())
        running
    } catch (e: InterruptedException) {
        false
    }

    // ---------- Настройки ----------

    private fun applyConfig(new: Config) {
        config = new
        if (!new.detectBars) {
            appliedBarsV = 0; appliedBarsH = 0
            candBarsV = 0; candBarsH = 0
        }
        pipeline.configure(new.params, geometryWithBars(new))
        ensureOutputBuffer()
    }

    private fun geometryWithBars(c: Config): LabGeometry =
        c.geometry.copy(barsV = appliedBarsV, barsH = appliedBarsH)

    private fun ensureOutputBuffer() {
        val need = pipeline.zoneCount * 3
        if (rgb.size != need) rgb = ByteArray(need)
    }

    private fun readConfig(): Config {
        fun str(key: String, def: String) = rawStringValue(prefs, key, def)
        fun int(key: String, def: Int, lo: Int, hi: Int) =
            (str(key, def.toString()).toIntOrNull() ?: def).coerceIn(lo, hi)

        val glow = when (str("lab_glow", "mid")) {
            "off" -> 0f
            "low" -> 0.004f
            "high" -> 0.018f
            "max" -> 0.030f
            else -> 0.009f
        }
        val jitter = when (str("lab_jitter", "mid")) {
            "off" -> 0
            "high" -> 2
            else -> 1
        }
        val outputFps = if (int("lab_fps", 60, 30, 60) >= 45) 60 else 30

        val params = LabParams(
            gamma = int("gamma", 22, 10, 30) / 10.0,
            brightness = int("brightness", 100, 0, 100) / 100f,
            vivid = int("lab_vivid", 50, 0, 100),
            speed = int("lab_speed", 50, 0, 100),
            edge = int("lab_edge", 50, 0, 100),
            glow = glow,
            accentHighlights = str("lab_highlight", "soft") == "accent",
            jitter = jitter,
            outputFps = outputFps
        )
        val geometry = LabGeometry(
            top = int("leds_top", 15, 0, 300),
            right = int("leds_right", 10, 0, 300),
            bottom = int("leds_bottom", 15, 0, 300),
            left = int("leds_left", 10, 0, 300),
            reverse = int("direction", 0, 0, 1) == 1,
            offset = int("led_offset", 0, 0, 1000),
            marginX = int("margin_x", 2, 0, 45),
            marginY = int("margin_y", 2, 0, 45),
            depth = int("lab_depth", 22, 5, 50),
            barsV = 0,
            barsH = 0
        )
        return Config(
            enabled = rawBooleanValue(prefs, "ambilight_enabled", true),
            ip = str("wled_ip", "192.168.1.110"),
            port = int("udp_port", 4048, 1, 65535),
            captureMode = str("capture_mode", "auto"),
            detectBars = rawBooleanValue(prefs, "lab_detect_bars", false),
            params = params,
            geometry = geometry
        )
    }

    // ---------- Захват кадра ----------

    private fun captureFrame(c: Config): Boolean {
        if (captureInFlight.get()) return false // прошлый снимок ещё не готов: пропускаем кадр
        val view = playerView ?: return false

        val usePixelCopy = isSurface || when (c.captureMode) {
            "pixel_copy" -> true
            "texture_view" -> false
            else -> hdrContent || highRes
        }

        return if (usePixelCopy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // F16 нужен только для HDR: он даёт правильное сжатие цветов в обычный диапазон
            val bitmap = if (hdrContent) obtainF16Bitmap() else bitmap8888
            capturePixelCopy(view, bitmap) && readPixels(bitmap)
        } else {
            captureTexture(view, bitmap8888) && readPixels(bitmap8888)
        }
    }

    private fun readPixels(bitmap: Bitmap): Boolean = try {
        bitmap.getPixels(pixels, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        true
    } catch (e: Exception) {
        false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun obtainF16Bitmap(): Bitmap =
        bitmapF16 ?: Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.RGBA_F16).also { bitmapF16 = it }

    /** TextureView: getBitmap можно вызывать только из главного потока, ждём не дольше таймаута. */
    private fun captureTexture(view: PlayerView, bitmap: Bitmap): Boolean {
        val texture = view.videoSurfaceView as? TextureView ?: return false
        val latch = CountDownLatch(1)
        val success = AtomicBoolean(false)
        captureInFlight.set(true)
        mainHandler.post {
            try {
                success.set(texture.getBitmap(bitmap) != null)
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка getBitmap", e)
            } finally {
                captureInFlight.set(false)
                latch.countDown()
            }
        }
        return awaitLatch(latch, TEXTURE_TIMEOUT_MS) && success.get()
    }

    /** SurfaceView (4K/HDR): PixelCopy, результат приходит в отдельный поток. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun capturePixelCopy(view: PlayerView, bitmap: Bitmap): Boolean {
        val handler = pixelCopyHandler ?: return false
        val latch = CountDownLatch(1)
        val result = AtomicInteger(-1)
        captureInFlight.set(true)

        val listener = PixelCopy.OnPixelCopyFinishedListener { code ->
            result.set(code)
            captureInFlight.set(false)
            latch.countDown()
        }
        mainHandler.post {
            try {
                val surfaceView = view.videoSurfaceView as? SurfaceView
                if (surfaceView != null && surfaceView.holder.surface.isValid) {
                    PixelCopy.request(surfaceView, bitmap, listener, handler)
                } else {
                    PixelCopy.request(activity.window, bitmap, listener, handler)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка PixelCopy", e)
                captureInFlight.set(false)
                latch.countDown()
            }
        }
        return awaitLatch(latch, PIXEL_COPY_TIMEOUT_MS) && result.get() == PixelCopy.SUCCESS
    }

    private fun awaitLatch(latch: CountDownLatch, timeoutMs: Long): Boolean = try {
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    // ---------- Отправка ----------

    private fun sendBlank() {
        val n = pipeline.zoneCount
        if (n <= 0) return
        java.util.Arrays.fill(rgb, 0.toByte())
        pipeline.clear()
        sendFrame(n)
    }

    /** Отправляет кадр. Больше 480 диодов делится на несколько пакетов, «показать» ставится у последнего. */
    private fun sendFrame(n: Int) {
        val sock = socket ?: return
        val destination = resolveAddress(config) ?: return
        if (rgb.size < n * 3) return

        var led = 0
        while (led < n) {
            val count = min(Ddp.MAX_LEDS_PER_PACKET, n - led)
            val bytes = count * 3
            Ddp.writeHeader(packetBuf, led * 3, bytes, led + count >= n)
            System.arraycopy(rgb, led * 3, packetBuf, Ddp.HEADER_SIZE, bytes)
            packet.setData(packetBuf, 0, Ddp.HEADER_SIZE + bytes)
            packet.address = destination
            packet.port = config.port
            try {
                sock.send(packet)
            } catch (e: Exception) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSendErrorAt > ERROR_LOG_INTERVAL_MS) {
                    lastSendErrorAt = now
                    Log.w(TAG, "Не удалось отправить пакет на WLED", e)
                }
                return
            }
            led += count
        }
    }

    /** Адрес кэшируется; если не получилось разобрать, повторяем не чаще раза в 3 секунды. */
    private fun resolveAddress(c: Config): InetAddress? {
        if (c.ip != addressIp) {
            addressIp = c.ip
            address = null
            addressRetryAt = 0L
        }
        address?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < addressRetryAt) return null
        return try {
            InetAddress.getByName(c.ip).also { address = it }
        } catch (e: Exception) {
            addressRetryAt = now + ADDRESS_RETRY_MS
            Log.w(TAG, "Не удалось разобрать адрес WLED: ${c.ip}", e)
            null
        }
    }

    // ---------- Чёрные полосы (опционально) ----------

    /**
     * Ищет вшитые в видео чёрные полосы. Возвращает true, когда применённые значения изменились
     * и зоны надо пересчитать. Полосы появляются с задержкой 1,2 с, исчезают с задержкой 0,35 с.
     */
    private fun updateBars(): Boolean {
        val measured = measureBars() ?: return false // почти чёрный кадр игнорируем
        val nowMs = SystemClock.elapsedRealtime()

        if (abs(measured[0] - candBarsV) > 1 || abs(measured[1] - candBarsH) > 1) {
            candBarsV = measured[0]
            candBarsH = measured[1]
            candSinceMs = nowMs
            return false
        }
        if (candBarsV == appliedBarsV && candBarsH == appliedBarsH) return false

        val growing = candBarsV > appliedBarsV || candBarsH > appliedBarsH
        val needMs = if (growing) BAR_GROW_MS else BAR_SHRINK_MS
        if (nowMs - candSinceMs < needMs) return false

        appliedBarsV = candBarsV
        appliedBarsH = candBarsH
        return true
    }

    /** Возвращает [полосы сверху и снизу, полосы слева и справа] в клетках или null, если кадр почти чёрный. */
    private fun measureBars(): IntArray? {
        val rowBright = IntArray(GRID_H)
        val colBright = IntArray(GRID_W)
        var total = 0
        for (y in 0 until GRID_H) {
            for (x in 0 until GRID_W) {
                val c = pixels[y * GRID_W + x]
                val m = max(max((c shr 16) and 0xFF, (c shr 8) and 0xFF), c and 0xFF)
                if (m > BAR_BLACK_MAX) {
                    rowBright[y]++
                    colBright[x]++
                    total++
                }
            }
        }
        if (total < GRID_W * GRID_H / 100) return null

        val rowAllow = GRID_W * BAR_BRIGHT_ALLOW_PERCENT / 100
        val colAllow = GRID_H * BAR_BRIGHT_ALLOW_PERCENT / 100

        var top = 0
        while (top < GRID_H && rowBright[top] <= rowAllow) top++
        var bottom = 0
        while (bottom < GRID_H && rowBright[GRID_H - 1 - bottom] <= rowAllow) bottom++
        var left = 0
        while (left < GRID_W && colBright[left] <= colAllow) left++
        var right = 0
        while (right < GRID_W && colBright[GRID_W - 1 - right] <= colAllow) right++

        val v = min(min(top, bottom), GRID_H * BAR_MAX_PERCENT / 100)
        val h = min(min(left, right), GRID_W * BAR_MAX_PERCENT / 100)
        return intArrayOf(if (v >= BAR_MIN_CELLS) v else 0, if (h >= BAR_MIN_CELLS) h else 0)
    }

    // ---------- Главный поток ----------

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** HDR и 4K определяем по формату видео; ExoPlayer трогаем только из главного потока. */
    private fun refreshVideoInfo() {
        val format = player?.videoFormat
        hdrContent = format != null && ColorInfo.isTransferHdr(format.colorInfo)
        highRes = format != null && max(format.width, format.height) >= HIGH_RES_THRESHOLD
    }

    private companion object {
        const val TAG = "AmbilightLab"

        // Сетка захвата. Больше, чем у v2 (96x54): меньше мерцания мелких ярких деталей
        const val GRID_W = 128
        const val GRID_H = 72

        const val IDLE_SLEEP_MS = 150L
        const val KEEPALIVE_MS = 500L
        const val JOIN_TIMEOUT_MS = 1000L
        const val TEXTURE_TIMEOUT_MS = 200L
        const val PIXEL_COPY_TIMEOUT_MS = 500L
        const val HIGH_RES_THRESHOLD = 3000
        const val ADDRESS_RETRY_MS = 3000L
        const val ERROR_LOG_INTERVAL_MS = 5000L

        const val MIN_CAPTURE_PERIOD_NS = 33_000_000L // захват не чаще ~30 раз в секунду
        const val FIRST_STEP_MS = 16.7f

        const val BAR_BLACK_MAX = 20
        const val BAR_BRIGHT_ALLOW_PERCENT = 4
        const val BAR_MAX_PERCENT = 40
        const val BAR_MIN_CELLS = 3
        const val BAR_GROW_MS = 1200L
        const val BAR_SHRINK_MS = 350L
    }
}
