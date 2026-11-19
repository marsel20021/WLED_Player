package com.marsel.wledplayer.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Маленький загрузчик картинок: память + фоновые потоки. Ответ приходит на главном потоке. */
object ImageLoader {
    private const val MAX_BYTES = 6_000_000

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun cached(url: String): Bitmap? = cache.get(url)

    /** maxWidth: картинку уменьшаем при чтении, чтобы не держать в памяти лишнее. */
    fun load(url: String, maxWidth: Int, done: (Bitmap?) -> Unit) {
        cache.get(url)?.let { done(it); return }
        io.execute {
            val bmp = try {
                download(url, maxWidth)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(url, bmp)
            main.post { done(bmp) }
        }
    }

    private fun download(url: String, maxWidth: Int): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "WledPlayer")
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } finally {
            conn.disconnect()
        }
    }
}
