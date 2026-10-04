package com.marsel.wledplayer

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Классический движок подсветки. Логика не менялась: это прежний AmbilightController,
 * переименованный, чтобы рядом мог жить новый движок (см. AmbilightEngineV2).
 */
@OptIn(UnstableApi::class)
class ClassicAmbilightEngine(
    private val activity: Activity,
    initialPlayerView: PlayerView,
    private val prefs: SharedPreferences
) : AmbilightEngine {

    enum class CaptureMode(val prefValue: String) {
        AUTO("auto"),
        TEXTURE_VIEW("texture_view"),
        PIXEL_COPY("pixel_copy");

        companion object {
            fun fromPref(value: String): CaptureMode =
                values().firstOrNull { it.prefValue == value } ?: AUTO
        }
    }

    private enum class Engine { TEXTURE_VIEW, PIXEL_COPY }

    private data class AmbilightSettings(
        val enabled: Boolean,
        val ip: String,
        val port: Int,
        val fps: Int,
        val marginX: Float,
        val marginY: Float,
        val depth: Float,
        val ledsTop: Int,
        val ledsBottom: Int,
        val ledsLeft: Int,
        val ledsRight: Int,
        val direction: Int,
        val offset: Int,
        val brightness: Float,
        val gamma: Double,
        val smoothing: Float,
        val useV2: Boolean,
        val blackThreshold: Int,
        val captureMode: CaptureMode
    )

    companion object {
        private const val TAG = "ClassicAmbilight"
        private const val CAP_W = 64
        private const val CAP_H = 36
        private const val HIGH_RES_THRESHOLD = 3000
        private const val PIXEL_COPY_TIMEOUT_MS = 500L
    }

    private var player: ExoPlayer? = null

    // Динамическое управление плеерами (Surface / Texture)
    private var activePlayerView: PlayerView = initialPlayerView
    private var isSurfaceActive = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpSocket: DatagramSocket? = null

    private var reusableBitmap: Bitmap? = null
    private val pixelArray = IntArray(CAP_W * CAP_H)

    @Volatile private var settingsDirty = true
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        settingsDirty = true
    }

    private val pixelCopyThreadLazy = lazy { HandlerThread("AmbilightPixelCopy").apply { start() } }
    private val pixelCopyThread by pixelCopyThreadLazy
    private val pixelCopyHandler by lazy { Handler(pixelCopyThread.looper) }
    private var loggedPixelCopyUnavailable = false

    override fun attachPlayer(player: ExoPlayer) {
        this.player = player
    }

    override fun updatePlayerView(view: PlayerView, isSurface: Boolean) {
        this.activePlayerView = view
        this.isSurfaceActive = isSurface
    }

    override fun start() {
        try {
            udpSocket = DatagramSocket()
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть UDP-сокет", e)
        }

        // Принудительно используем RGBA_F16 для правильного HDR -> SDR тон-маппинга
        reusableBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Bitmap.createBitmap(CAP_W, CAP_H, Bitmap.Config.RGBA_F16)
        } else {
            Bitmap.createBitmap(CAP_W, CAP_H, Bitmap.Config.ARGB_8888)
        }

        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        settingsDirty = true
        runLoop()
    }

    override fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        udpSocket?.close()
        if (pixelCopyThreadLazy.isInitialized()) {
            pixelCopyThread.quitSafely()
        }
        reusableBitmap?.recycle()
        reusableBitmap = null
    }

    private fun runLoop() {
        scope.launch {
            var mapIndices: List<List<Int>> = emptyList()
            var currentColors = FloatArray(0)
            var settings = readSettings()
            var gammaLut = buildGammaLut(settings.gamma, settings.brightness)
            var lastGeometryKey = ""

            while (isActive) {
                if (settingsDirty) {
                    settingsDirty = false
                    val newSettings = readSettings()

                    val geometryKey = "${newSettings.marginX}-${newSettings.marginY}-${newSettings.depth}-" +
                            "${newSettings.ledsTop}-${newSettings.ledsBottom}-${newSettings.ledsLeft}-${newSettings.ledsRight}-" +
                            "${newSettings.direction}-${newSettings.offset}"

                    if (geometryKey != lastGeometryKey) {
                        mapIndices = generateMapping(
                            newSettings.marginX, newSettings.marginY, newSettings.depth,
                            newSettings.ledsTop, newSettings.ledsBottom, newSettings.ledsLeft, newSettings.ledsRight,
                            newSettings.direction, newSettings.offset
                        )
                        currentColors = FloatArray(max(1, mapIndices.size) * 3) { 0f }
                        lastGeometryKey = geometryKey
                    }

                    if (newSettings.gamma != settings.gamma || newSettings.brightness != settings.brightness) {
                        gammaLut = buildGammaLut(newSettings.gamma, newSettings.brightness)
                    }

                    if (settings.enabled && !newSettings.enabled) {
                        sendBlankFrame(mapIndices.size, newSettings.ip, newSettings.port)
                    }

                    settings = newSettings
                }

                if (!settings.enabled) {
                    delay(150)
                    continue
                }

                val delayTime = (1000 / max(1, settings.fps)).toLong()
                val inetAddress = try {
                    InetAddress.getByName(settings.ip)
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось разрешить IP адрес WLED: ${settings.ip}", e)
                    null
                }

                val (isPlaying, videoFormat) = withContext(Dispatchers.Main) {
                    Pair(player?.isPlaying == true, player?.videoFormat)
                }

                if (!isPlaying || inetAddress == null || mapIndices.isEmpty()) {
                    delay(100)
                    continue
                }

                val engine = resolveEngine(settings.captureMode, videoFormat)
                val bitmap = captureFrame(engine)

                if (bitmap != null && !bitmap.isRecycled) {
                    try {
                        bitmap.getPixels(pixelArray, 0, CAP_W, 0, 0, CAP_W, CAP_H)
                    } catch (e: Exception) {
                        delay(delayTime)
                        continue
                    }
                    val numLeds = mapIndices.size

                    val payload = ByteArray(10 + numLeds * 3)
                    payload[0] = 0x41.toByte()
                    payload[2] = 0x01.toByte()
                    payload[3] = 0x01.toByte()
                    val dataLen = numLeds * 3
                    payload[8] = ((dataLen shr 8) and 0xFF).toByte()
                    payload[9] = (dataLen and 0xFF).toByte()

                    var pIdx = 10
                    for ((i, zonePixels) in mapIndices.withIndex()) {
                        var rSum = 0; var gSum = 0; var bSum = 0
                        val count = zonePixels.size
                        for (idx in zonePixels) {
                            if (idx < 0 || idx >= pixelArray.size) continue
                            val c = pixelArray[idx]
                            rSum += (c shr 16) and 0xFF
                            gSum += (c shr 8) and 0xFF
                            bSum += c and 0xFF
                        }
                        if (count > 0) {
                            rSum /= count; gSum /= count; bSum /= count
                        }

                        if (settings.useV2 && rSum < settings.blackThreshold && gSum < settings.blackThreshold && bSum < settings.blackThreshold) {
                            rSum = 0; gSum = 0; bSum = 0
                        }

                        val base = i * 3
                        currentColors[base] += (rSum - currentColors[base]) * settings.smoothing
                        currentColors[base + 1] += (gSum - currentColors[base + 1]) * settings.smoothing
                        currentColors[base + 2] += (bSum - currentColors[base + 2]) * settings.smoothing

                        payload[pIdx++] = gammaLut[currentColors[base].toInt().coerceIn(0, 255)]
                        payload[pIdx++] = gammaLut[currentColors[base + 1].toInt().coerceIn(0, 255)]
                        payload[pIdx++] = gammaLut[currentColors[base + 2].toInt().coerceIn(0, 255)]
                    }

                    try {
                        udpSocket?.send(DatagramPacket(payload, payload.size, inetAddress, settings.port))
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось отправить UDP-пакет на WLED", e)
                    }
                }
                delay(delayTime)
            }
        }
    }

    private fun resolveEngine(mode: CaptureMode, format: Format?): Engine {
        if (isSurfaceActive) {
            return Engine.PIXEL_COPY
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (mode == CaptureMode.PIXEL_COPY && !loggedPixelCopyUnavailable) {
                loggedPixelCopyUnavailable = true
                Log.w(TAG, "PixelCopy недоступен на API < 26, используем TextureView")
            }
            return Engine.TEXTURE_VIEW
        }

        return when (mode) {
            CaptureMode.TEXTURE_VIEW -> Engine.TEXTURE_VIEW
            CaptureMode.PIXEL_COPY -> Engine.PIXEL_COPY
            CaptureMode.AUTO -> if (isHdrOrHighRes(format)) Engine.PIXEL_COPY else Engine.TEXTURE_VIEW
        }
    }

    private fun isHdrOrHighRes(format: Format?): Boolean {
        format ?: return false
        val isHighRes = max(format.width, format.height) >= HIGH_RES_THRESHOLD
        return isHighRes || ColorInfo.isTransferHdr(format.colorInfo)
    }

    private suspend fun captureFrame(engine: Engine): Bitmap? {
        val bmp = reusableBitmap ?: return null
        if (bmp.isRecycled) return null

        return when (engine) {
            Engine.TEXTURE_VIEW -> withContext(Dispatchers.Main) {
                (activePlayerView.videoSurfaceView as? TextureView)?.getBitmap(bmp)
            }
            Engine.PIXEL_COPY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    withTimeoutOrNull(PIXEL_COPY_TIMEOUT_MS) { capturePixelCopy(bmp) }
                } else {
                    null
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun capturePixelCopy(bmp: Bitmap): Bitmap? {
        val success = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                try {
                    val surfaceView = activePlayerView.videoSurfaceView as? SurfaceView
                    if (surfaceView != null && surfaceView.holder.surface.isValid) {
                        PixelCopy.request(surfaceView, bmp, { result ->
                            if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                        }, pixelCopyHandler)
                    } else {
                        PixelCopy.request(activity.window, bmp, { result ->
                            if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                        }, pixelCopyHandler)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка PixelCopy", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        return if (success) bmp else null
    }

    private fun sendBlankFrame(numLeds: Int, ip: String, port: Int) {
        if (numLeds <= 0) return
        try {
            val address = InetAddress.getByName(ip)
            val payload = ByteArray(10 + numLeds * 3)
            payload[0] = 0x41.toByte()
            payload[2] = 0x01.toByte()
            payload[3] = 0x01.toByte()
            val dataLen = numLeds * 3
            payload[8] = ((dataLen shr 8) and 0xFF).toByte()
            payload[9] = (dataLen and 0xFF).toByte()
            udpSocket?.send(DatagramPacket(payload, payload.size, address, port))
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось погасить подсветку", e)
        }
    }

    private fun readSettings(): AmbilightSettings {
        fun str(key: String, def: String) = rawStringValue(prefs, key, def)
        fun int(key: String, def: Int, min: Int, max: Int) =
            (str(key, def.toString()).toIntOrNull() ?: def).coerceIn(min, max)
        fun percent(key: String, def: Int) = int(key, def, 0, 100) / 100f

        val rawSmoothing = percent("smoothing", 50)
        val smoothingAlpha = (1f - rawSmoothing).coerceIn(0.03f, 1f)

        return AmbilightSettings(
            enabled = rawBooleanValue(prefs, "ambilight_enabled", true),
            ip = str("wled_ip", "192.168.1.110"),
            port = int("udp_port", 4048, 1, 65535),
            fps = int("fps", 30, 1, 60),
            marginX = percent("margin_x", 2),
            marginY = percent("margin_y", 2),
            depth = percent("capture_depth", 5).coerceAtLeast(0.01f),
            ledsTop = int("leds_top", 15, 0, 300),
            ledsBottom = int("leds_bottom", 15, 0, 300),
            ledsLeft = int("leds_left", 10, 0, 300),
            ledsRight = int("leds_right", 10, 0, 300),
            direction = int("direction", 0, 0, 1),
            offset = int("led_offset", 0, 0, 1000),
            brightness = percent("brightness", 100),
            gamma = int("gamma", 22, 10, 30) / 10.0,
            smoothing = smoothingAlpha,
            useV2 = rawBooleanValue(prefs, "use_v2", true),
            blackThreshold = int("black_threshold", 15, 0, 255),
            captureMode = CaptureMode.fromPref(str("capture_mode", CaptureMode.AUTO.prefValue))
        )
    }

    private fun buildGammaLut(gamma: Double, brightness: Float): ByteArray {
        return ByteArray(256) { i ->
            val corrected = Math.pow(i / 255.0, gamma) * brightness * 255
            corrected.toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun generateMapping(marginX: Float, marginY: Float, depth: Float, top: Int, bottom: Int, left: Int, right: Int, dir: Int, offset: Int): List<List<Int>> {
        val mx = (CAP_W * marginX).toInt()
        val my = (CAP_H * marginY).toInt()
        val dx = max(1, (CAP_W * depth).toInt())
        val dy = max(1, (CAP_H * depth).toInt())
        val ledCoords = mutableListOf<List<Int>>()

        if (top > 0) {
            val step = max(1f, (CAP_W - 1 - mx - mx).toFloat() / top)
            for (i in 0 until top) {
                val cx = (mx + step * i + step / 2f).toInt()
                val zone = mutableListOf<Int>()
                for (y in 0 until dy) zone.add((my + y) * CAP_W + cx)
                ledCoords.add(zone)
            }
        }
        if (right > 0) {
            val step = max(1f, (CAP_H - 1 - my - my).toFloat() / right)
            for (i in 0 until right) {
                val cy = (my + step * i + step / 2f).toInt()
                val zone = mutableListOf<Int>()
                for (x in 0 until dx) zone.add(cy * CAP_W + (CAP_W - 1 - mx - x))
                ledCoords.add(zone)
            }
        }
        if (bottom > 0) {
            val step = max(1f, (CAP_W - 1 - mx - mx).toFloat() / bottom)
            for (i in 0 until bottom) {
                val cx = (CAP_W - 1 - mx - step * i - step / 2f).toInt()
                val zone = mutableListOf<Int>()
                for (y in 0 until dy) zone.add((CAP_H - 1 - my - y) * CAP_W + cx)
                ledCoords.add(zone)
            }
        }
        if (left > 0) {
            val step = max(1f, (CAP_H - 1 - my - my).toFloat() / left)
            for (i in 0 until left) {
                val cy = (CAP_H - 1 - my - step * i - step / 2f).toInt()
                val zone = mutableListOf<Int>()
                for (x in 0 until dx) zone.add(cy * CAP_W + (mx + x))
                ledCoords.add(zone)
            }
        }

        var result = ledCoords.toList()
        if (dir == 1) result = result.reversed()
        if (offset > 0 && result.isNotEmpty()) {
            val off = offset % result.size
            result = result.drop(off) + result.take(off)
        }
        return result
    }
}