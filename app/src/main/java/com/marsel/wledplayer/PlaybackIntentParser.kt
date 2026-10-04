package com.marsel.wledplayer

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Разбор входящего Intent'а по контракту MX Player API (https://mx.j2inter.com/api),
 * который используют Vokino и похожие приложения для запуска стороннего плеера.
 */
data class IncomingPlaylist(
    val mediaItems: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val headers: Map<String, String>?,
    val returnResult: Boolean
)

object PlaybackIntentParser {

    private const val EXTRA_VIDEO_LIST = "video_list"
    private const val EXTRA_VIDEO_LIST_NAME = "video_list.name"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_POSITION = "position"
    private const val EXTRA_HEADERS = "headers"
    private const val EXTRA_RETURN_RESULT = "return_result"

    fun parse(intent: Intent): IncomingPlaylist? {
        val dataUri = intent.data ?: return null

        val listFromExtra = getVideoListUris(intent)
        // Если video_list не пришёл - считаем, что это одиночное видео (обратная совместимость)
        val uris = if (!listFromExtra.isNullOrEmpty()) listFromExtra else listOf(dataUri)

        val names = intent.getStringArrayExtra(EXTRA_VIDEO_LIST_NAME)
        val fallbackTitle = intent.getStringExtra(EXTRA_TITLE)

        val mediaItems = uris.mapIndexed { index, uri ->
            val title = names?.getOrNull(index) ?: fallbackTitle
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }

        val startIndex = uris.indexOf(dataUri).takeIf { it >= 0 } ?: 0
        val position = intent.getIntExtra(EXTRA_POSITION, -1)

        return IncomingPlaylist(
            mediaItems = mediaItems,
            startIndex = startIndex,
            startPositionMs = if (position >= 0) position.toLong() else 0L,
            headers = parseHeaders(intent.getStringArrayExtra(EXTRA_HEADERS)),
            returnResult = intent.getBooleanExtra(EXTRA_RETURN_RESULT, false)
        )
    }

    @Suppress("DEPRECATION") // getParcelableArrayExtra(String) без Class работает на всех API, просто помечен deprecated с API 33
    private fun getVideoListUris(intent: Intent): List<Uri>? {
        val array = intent.getParcelableArrayExtra(EXTRA_VIDEO_LIST) ?: return null
        return array.filterIsInstance<Uri>()
    }

    private fun parseHeaders(raw: Array<String>?): Map<String, String>? {
        if (raw == null || raw.size < 2) return null
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < raw.size) {
            map[raw[i]] = raw[i + 1]
            i += 2
        }
        return map
    }
}