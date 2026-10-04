package com.marsel.wledplayer

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.min

/**
 * Общие мелочи протокола DDP (цвета диодов по UDP).
 *
 * Заголовок 10 байт: байт 0 - флаги (0x40, плюс 0x01 «показать кадр» у последнего пакета),
 * байт 1 - номер пакета (не используем), байты 2-3 - тип данных и адрес устройства (RGB, 1),
 * байты 4-7 - смещение данных в байтах, байты 8-9 - длина данных; дальше цвета R, G, B.
 */
object Ddp {

    private const val TAG = "Ddp"
    const val HEADER_SIZE = 10
    const val MAX_LEDS_PER_PACKET = 480

    fun writeHeader(buf: ByteArray, offsetBytes: Int, dataLength: Int, push: Boolean) {
        buf[0] = (if (push) 0x41 else 0x40).toByte()
        buf[1] = 0
        buf[2] = 0x01
        buf[3] = 0x01
        buf[4] = (offsetBytes ushr 24).toByte()
        buf[5] = (offsetBytes ushr 16).toByte()
        buf[6] = (offsetBytes ushr 8).toByte()
        buf[7] = offsetBytes.toByte()
        buf[8] = (dataLength ushr 8).toByte()
        buf[9] = dataLength.toByte()
    }

    /** Гасит всю ленту одним коротким отправлением (отдельный сокет, можно вызывать из любого потока). */
    fun sendBlank(ip: String, port: Int, ledCount: Int) {
        if (ledCount <= 0) return
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            val address = InetAddress.getByName(ip)
            val buf = ByteArray(HEADER_SIZE + MAX_LEDS_PER_PACKET * 3)
            var offset = 0
            while (offset < ledCount) {
                val count = min(MAX_LEDS_PER_PACKET, ledCount - offset)
                val bytes = count * 3
                writeHeader(buf, offset * 3, bytes, offset + count >= ledCount)
                java.util.Arrays.fill(buf, HEADER_SIZE, HEADER_SIZE + bytes, 0.toByte())
                socket.send(DatagramPacket(buf, HEADER_SIZE + bytes, address, port))
                offset += count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось погасить ленту", e)
        } finally {
            socket?.close()
        }
    }
}
