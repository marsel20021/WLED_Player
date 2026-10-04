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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Новый движок подсветки (v2). Работает в одном фоновом потоке; каждый кадр:
 *
 *  1. Захват. Кадр уменьшается до сетки 96x54 в один переиспользуемый Bitmap
 *     (TextureView через главный поток, SurfaceView через PixelCopy). Пока прошлый снимок не
 *     завершился, новый не запрашивается: кадр пропускается, очередь запросов не растёт.
 *  2. Зоны. У каждого диода свой прямоугольник на сетке, считается заранее и пересчитывается
 *     только при смене геометрии. Порядок по часовой: верх, право, низ, лево.
 *  3. Цвет диода: среднее по зоне, затем гашение тёмных зон, насыщенность, сглаживание
 *     (не зависит от FPS), мгновенная смена при смене сцены, гамма и яркость одной таблицей.
 *  4. Отправка по DDP (UDP), при необходимости несколькими пакетами.
 *  5. Ровный FPS: время следующего кадра считается от метки, а не «после работы».
 *
 * Дополнительно (по умолчанию выключено): определение чёрных полос, вшитых в видео.
 */
@OptIn(UnstableApi::class)
class AmbilightEngineV2(
    private val activity: Activity,
    private val prefs: SharedPreferences
) : AmbilightEngine {

    private data class Settings(
        val enabled: Boolean,
        val ip: String,
        val port: Int,
        val fps: Int,
        val brightness: Float,
        val gamma: Double,
        val smoothing: Int,
        val saturation: Float,
        val blackThreshold: Int,
        val detectBars: Boolean,
        val marginX: Int,
        val marginY: Int,
        val depth: Int,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val reverse: Boolean,
        val offset: Int,
        val captureMode: String
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

    private var settings: Settings = readSettings()
    private var lut = ByteArray(256)
    private var zoneKey = ""
    private var zones = IntArray(0)
    private var zoneCount = 0
    private var cur = FloatArray(0)
    private var target = FloatArray(0)
    private var rgb = ByteArray(0)
    private val pixels = IntArray(GRID_W * GRID_H)
    private val bitmap8888: Bitmap by lazy { Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888) }
    private var bitmapF16: Bitmap? = null
    private var snapNext = true

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

        pixelCopyThread = HandlerThread("AmbilightV2PixelCopy").also {
            it.start()
            pixelCopyHandler = Handler(it.looper)
        }
        worker = Thread({ runLoop() }, "AmbilightV2").also { it.start() }
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
        applySettings(readSettings())

        var wasActive = false
        var lastFrameNs = 0L
        var nextTickNs = System.nanoTime()

        try {
            while (running) {
                if (settingsDirty) {
                    settingsDirty = false
                    applySettings(readSettings())
                }
                val s = settings

                // Подсветка выключена: один раз гасим ленту и спим
                if (!s.enabled || zoneCount == 0) {
                    if (wasActive) {
                        sendBlank()
                        wasActive = false
                    }
                    snapNext = true
                    if (!sleepMs(IDLE_SLEEP_MS)) break
                    nextTickNs = System.nanoTime()
                    continue
                }

                // Видео на паузе: ничего не шлём, лента держит последний цвет
                if (!playing) {
                    snapNext = true
                    if (!sleepMs(IDLE_SLEEP_MS)) break
                    nextTickNs = System.nanoTime()
                    continue
                }

                val periodNs = 1_000_000_000L / s.fps
                val frameStartNs = System.nanoTime()

                if (captureFrame(s)) {
                    val dtMs = if (lastFrameNs == 0L || snapNext) {
                        FRAME_MS_AT_30
                    } else {
                        ((frameStartNs - lastFrameNs) / 1_000_000f).coerceIn(1f, 250f)
                    }
                    if (s.detectBars && updateBars()) rebuildZonesIfNeeded()
                    processAndSend(settings, dtMs)
                    lastFrameNs = frameStartNs
                    wasActive = true
                }

                // Время следующего кадра считаем от метки, чтобы FPS не проседал на длительности работы
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

    private fun applySettings(new: Settings) {
        val old = settings
        settings = new
        if (new.gamma != old.gamma || new.brightness != old.brightness || lut[255].toInt() == 0) {
            lut = buildLut(new)
        }
        if (!new.detectBars) {
            appliedBarsV = 0; appliedBarsH = 0
            candBarsV = 0; candBarsH = 0
        }
        rebuildZonesIfNeeded()
    }

    private fun readSettings(): Settings {
        fun str(key: String, def: String) = rawStringValue(prefs, key, def)
        fun int(key: String, def: Int, lo: Int, hi: Int) =
            (str(key, def.toString()).toIntOrNull() ?: def).coerceIn(lo, hi)

        return Settings(
            enabled = rawBooleanValue(prefs, "ambilight_enabled", true),
            ip = str("wled_ip", "192.168.1.110"),
            port = int("udp_port", 4048, 1, 65535),
            fps = int("fps", 30, 1, 60),
            brightness = int("brightness", 100, 0, 100) / 100f,
            gamma = int("gamma", 22, 10, 30) / 10.0,
            smoothing = int("v2_smoothing", 50, 0, 100),
            saturation = int("v2_saturation", 115, 0, 300) / 100f,
            blackThreshold = int("v2_black_threshold", 15, 0, 255),
            detectBars = rawBooleanValue(prefs, "v2_detect_bars", false),
            marginX = int("margin_x", 2, 0, 45),
            marginY = int("margin_y", 2, 0, 45),
            depth = int("capture_depth", 5, 1, 50),
            top = int("leds_top", 15, 0, 300),
            bottom = int("leds_bottom", 15, 0, 300),
            left = int("leds_left", 10, 0, 300),
            right = int("leds_right", 10, 0, 300),
            reverse = int("direction", 0, 0, 1) == 1,
            offset = int("led_offset", 0, 0, 1000),
            captureMode = str("capture_mode", "auto")
        )
    }

    /** Таблица на 256 значений: out = (i/255)^gamma * brightness * 255. */
    private fun buildLut(s: Settings): ByteArray = ByteArray(256) { i ->
        ((i / 255.0).pow(s.gamma) * s.brightness * 255.0).roundToInt().coerceIn(0, 255).toByte()
    }

    // ---------- Зоны ----------

    /** Пересчитывает зоны, только если поменялась геометрия (число диодов, отступы, глубина...). */
    private fun rebuildZonesIfNeeded() {
        val s = settings
        val key = "${s.marginX}-${s.marginY}-${s.depth}-${s.top}-${s.bottom}-${s.left}-${s.right}-" +
            "${s.reverse}-${s.offset}-$appliedBarsV-$appliedBarsH"
        if (key == zoneKey) return
        zoneKey = key

        zones = buildZones(s)
        val newCount = zones.size / 4
        if (newCount != zoneCount) {
            zoneCount = newCount
            cur = FloatArray(newCount * 3)
            target = FloatArray(newCount * 3)
            rgb = ByteArray(newCount * 3)
            snapNext = true
        }
    }

    private fun buildZones(s: Settings): IntArray {
        // Область картинки: вся сетка или то, что осталось после вычитания чёрных полос
        val areaX = appliedBarsH
        val areaY = appliedBarsV
        val areaW = max(4, GRID_W - 2 * appliedBarsH)
        val areaH = max(4, GRID_H - 2 * appliedBarsV)

        val mx = areaW * s.marginX / 100
        val my = areaH * s.marginY / 100
        val dx = max(1, areaW * s.depth / 100)
        val dy = max(1, areaH * s.depth / 100)
        val x0 = areaX + mx
        val x1 = areaX + areaW - mx
        val y0 = areaY + my
        val y1 = areaY + areaH - my

        val list = ArrayList<IntArray>(s.top + s.right + s.bottom + s.left)

        // Верх: слева направо
        for (i in 0 until s.top) {
            list += zone(segStart(x0, x1, i, s.top), y0, segStart(x0, x1, i + 1, s.top), y0 + dy)
        }
        // Право: сверху вниз
        for (i in 0 until s.right) {
            list += zone(x1 - dx, segStart(y0, y1, i, s.right), x1, segStart(y0, y1, i + 1, s.right))
        }
        // Низ: справа налево
        for (i in 0 until s.bottom) {
            val k = s.bottom - 1 - i
            list += zone(segStart(x0, x1, k, s.bottom), y1 - dy, segStart(x0, x1, k + 1, s.bottom), y1)
        }
        // Лево: снизу вверх
        for (i in 0 until s.left) {
            val k = s.left - 1 - i
            list += zone(x0, segStart(y0, y1, k, s.left), x0 + dx, segStart(y0, y1, k + 1, s.left))
        }

        if (s.reverse) list.reverse()
        if (list.isNotEmpty() && s.offset > 0) {
            Collections.rotate(list, -(s.offset % list.size))
        }

        val result = IntArray(list.size * 4)
        for (i in list.indices) System.arraycopy(list[i], 0, result, i * 4, 4)
        return result
    }

    private fun segStart(a: Int, b: Int, i: Int, n: Int): Int = a + (b - a) * i / n

    /** Прямоугольник зоны, обрезанный по сетке, минимум одна клетка. */
    private fun zone(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val cx0 = x0.coerceIn(0, GRID_W - 1)
        val cy0 = y0.coerceIn(0, GRID_H - 1)
        val cx1 = x1.coerceIn(cx0 + 1, GRID_W)
        val cy1 = y1.coerceIn(cy0 + 1, GRID_H)
        return intArrayOf(cx0, cy0, cx1, cy1)
    }

    // ---------- Захват кадра ----------

    private fun captureFrame(s: Settings): Boolean {
        if (captureInFlight.get()) return false // прошлый снимок ещё не готов: пропускаем кадр
        val view = playerView ?: return false

        val usePixelCopy = isSurface || when (s.captureMode) {
            "pixel_copy" -> true
            "texture_view" -> false
            else -> hdrContent || highRes
        }

        val ok = if (usePixelCopy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // F16 нужен только для HDR: он даёт правильное сжатие цветов в обычный диапазон
            val bitmap = if (hdrContent) obtainF16Bitmap() else bitmap8888
            capturePixelCopy(view, bitmap) && readPixels(bitmap)
        } else {
            captureTexture(view, bitmap8888) && readPixels(bitmap8888)
        }
        return ok
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

    // ---------- Цвета и отправка ----------

    private fun processAndSend(s: Settings, dtMs: Float) {
        val n = zoneCount
        if (n == 0) return
        val threshold = s.blackThreshold
        val sat = s.saturation

        var sceneDiff = 0f
        for (i in 0 until n) {
            val o = i * 4
            val x0 = zones[o]
            val y0 = zones[o + 1]
            val x1 = zones[o + 2]
            val y1 = zones[o + 3]

            // Среднее RGB по всем пикселям зоны
            var rs = 0
            var gs = 0
            var bs = 0
            var count = 0
            for (y in y0 until y1) {
                var p = y * GRID_W + x0
                for (x in x0 until x1) {
                    val c = pixels[p++]
                    rs += (c shr 16) and 0xFF
                    gs += (c shr 8) and 0xFF
                    bs += c and 0xFF
                    count++
                }
            }
            var r = if (count > 0) rs.toFloat() / count else 0f
            var g = if (count > 0) gs.toFloat() / count else 0f
            var b = if (count > 0) bs.toFloat() / count else 0f

            if (r < threshold && g < threshold && b < threshold) {
                // Тёмная зона: гасим
                r = 0f; g = 0f; b = 0f
            } else if (sat != 1f) {
                // Насыщенность: c = y + (c - y) * sat
                val y = luma(r, g, b)
                r = (y + (r - y) * sat).coerceIn(0f, 255f)
                g = (y + (g - y) * sat).coerceIn(0f, 255f)
                b = (y + (b - y) * sat).coerceIn(0f, 255f)
            }

            val t = i * 3
            target[t] = r
            target[t + 1] = g
            target[t + 2] = b
            sceneDiff += abs(luma(r, g, b) - luma(cur[t], cur[t + 1], cur[t + 2]))
        }

        // Смена сцены (или первый кадр после паузы): цвет переключается мгновенно
        val snap = snapNext || sceneDiff / n > SCENE_CHANGE_THRESHOLD
        snapNext = false

        if (snap) {
            System.arraycopy(target, 0, cur, 0, n * 3)
        } else {
            // Коэффициент задан «на кадр при 30 fps», пересчитываем под реальное время кадра
            val steps = dtMs / FRAME_MS_AT_30
            val a30 = (1f - s.smoothing / 100f).coerceIn(0.03f, 1f)
            val fall = 1f - (1f - a30).pow(steps)
            val rise = 1f - (1f - min(1f, a30 * 2f)).pow(steps) // рост быстрее, спад мягче
            for (i in 0 until n) {
                val t = i * 3
                val a = if (luma(target[t], target[t + 1], target[t + 2]) >
                    luma(cur[t], cur[t + 1], cur[t + 2])
                ) rise else fall
                cur[t] += (target[t] - cur[t]) * a
                cur[t + 1] += (target[t + 1] - cur[t + 1]) * a
                cur[t + 2] += (target[t + 2] - cur[t + 2]) * a
            }
        }

        for (i in 0 until n * 3) {
            rgb[i] = lut[cur[i].roundToInt().coerceIn(0, 255)]
        }
        sendFrame(n)
    }

    private fun luma(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

    private fun sendBlank() {
        val n = zoneCount
        if (n <= 0) return
        java.util.Arrays.fill(rgb, 0.toByte())
        java.util.Arrays.fill(cur, 0f)
        sendFrame(n)
    }

    /** Отправляет кадр. Больше 480 диодов делится на несколько пакетов, «показать» ставится у последнего. */
    private fun sendFrame(n: Int) {
        val sock = socket ?: return
        val destination = resolveAddress(settings) ?: return

        var led = 0
        while (led < n) {
            val count = min(Ddp.MAX_LEDS_PER_PACKET, n - led)
            val bytes = count * 3
            Ddp.writeHeader(packetBuf, led * 3, bytes, led + count >= n)
            System.arraycopy(rgb, led * 3, packetBuf, Ddp.HEADER_SIZE, bytes)
            packet.setData(packetBuf, 0, Ddp.HEADER_SIZE + bytes)
            packet.address = destination
            packet.port = settings.port
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
    private fun resolveAddress(s: Settings): InetAddress? {
        if (s.ip != addressIp) {
            addressIp = s.ip
            address = null
            addressRetryAt = 0L
        }
        address?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < addressRetryAt) return null
        return try {
            InetAddress.getByName(s.ip).also { address = it }
        } catch (e: Exception) {
            addressRetryAt = now + ADDRESS_RETRY_MS
            Log.w(TAG, "Не удалось разобрать адрес WLED: ${s.ip}", e)
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
        const val TAG = "AmbilightV2"

        const val GRID_W = 96
        const val GRID_H = 54

        const val IDLE_SLEEP_MS = 150L
        const val JOIN_TIMEOUT_MS = 1000L
        const val TEXTURE_TIMEOUT_MS = 200L
        const val PIXEL_COPY_TIMEOUT_MS = 500L
        const val HIGH_RES_THRESHOLD = 3000
        const val ADDRESS_RETRY_MS = 3000L
        const val ERROR_LOG_INTERVAL_MS = 5000L

        const val FRAME_MS_AT_30 = 33.3f
        const val SCENE_CHANGE_THRESHOLD = 70f

        const val BAR_BLACK_MAX = 20
        const val BAR_BRIGHT_ALLOW_PERCENT = 4
        const val BAR_MAX_PERCENT = 40
        const val BAR_MIN_CELLS = 2
        const val BAR_GROW_MS = 1200L
        const val BAR_SHRINK_MS = 350L
    }
}
