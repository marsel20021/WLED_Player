package com.marsel.wledplayer.skip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ответ сервера. code = HTTP-код. Сетевая ошибка (нет сети, таймаут) - это IOException, а не результат. */
class SkipHttpResponse(val code: Int, val body: String)

/** Простой GET. Таймауты короткие, размер ответа ограничен. */
object SkipHttp {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 1_000_000

    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): SkipHttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "WledRemote")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readLimited(it) } ?: ""
            return SkipHttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("Ответ слишком большой")
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
