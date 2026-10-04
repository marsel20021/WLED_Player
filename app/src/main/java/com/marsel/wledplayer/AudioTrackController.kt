package com.marsel.wledplayer

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * Свой диалог выбора аудиодорожки вместо стандартного окна ExoPlayer (шестерёнка
 * в контролах плеера). Штатный DefaultTrackNameProvider у media3 строит название
 * трека только из языка, полностью игнорируя Format.label - поэтому несколько
 * русских дорожек от разных студий озвучки показываются просто как "Русский,
 * Русский, ...". Публичного способа подменить TrackNameProvider у
 * PlayerControlView сейчас нет (в исходниках media3 так и висит
 * "TODO: Add setTrackNameProvider"), поэтому проще и надёжнее сделать свой список.
 *
 * Если у аудиодорожки в HLS/DASH манифесте задан NAME (EXT-X-MEDIA -> Format.label),
 * используем его - как правило там и лежит название студии озвучки. Если названия
 * нет, откатываемся на "Язык" (и добавляем порядковый номер, только если языков
 * с одинаковым названием несколько).
 */
@OptIn(UnstableApi::class)
class AudioTrackController(private val activity: Activity) {

    companion object {
        private const val TAG = "AudioTrackController"
    }

    private data class TrackEntry(
        val group: Tracks.Group,
        val trackIndex: Int,
        val name: String,
        val isSelected: Boolean
    )

    fun show(player: Player) {
        val entries = collectAudioTracks(player)
        if (entries.isEmpty()) {
            Toast.makeText(activity, "Аудиодорожки не найдены", Toast.LENGTH_SHORT).show()
            return
        }

        val names = entries.map { it.name }.toTypedArray()
        val checkedIndex = entries.indexOfFirst { it.isSelected }.coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("Озвучка")
            .setSingleChoiceItems(names, checkedIndex) { dialog, which ->
                applySelection(player, entries[which])
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun collectAudioTracks(player: Player): List<TrackEntry> {
        val rawTracks = mutableListOf<Pair<Tracks.Group, Int>>()
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                rawTracks += group to i
            }
        }

        // Считаем, сколько дорожек без явного названия делят один и тот же язык,
        // чтобы номер добавлять только там, где он правда нужен для различия.
        val languageTotals = mutableMapOf<String, Int>()
        for ((group, i) in rawTracks) {
            val format = group.getTrackFormat(i)
            if (format.label.isNullOrBlank()) {
                val key = format.language ?: "?"
                languageTotals[key] = (languageTotals[key] ?: 0) + 1
            }
        }

        val perLanguageCount = mutableMapOf<String, Int>()
        return rawTracks.map { (group, i) ->
            val format = group.getTrackFormat(i)
            Log.d(TAG, "Аудиодорожка: label=${format.label} language=${format.language} id=${format.id}")

            val key = format.language ?: "?"
            val ordinal = if (format.label.isNullOrBlank() && (languageTotals[key] ?: 0) > 1) {
                perLanguageCount[key] = (perLanguageCount[key] ?: 0) + 1
                perLanguageCount[key]
            } else null

            TrackEntry(
                group = group,
                trackIndex = i,
                name = trackDisplayName(format, ordinal),
                isSelected = group.isTrackSelected(i)
            )
        }
    }

    private fun trackDisplayName(format: Format, ordinal: Int?): String {
        val label = format.label
        if (!label.isNullOrBlank()) return label

        val languageName = format.language
            ?.let { runCatching { Locale.forLanguageTag(it).displayLanguage }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }
            ?: "Неизвестно"

        return if (ordinal != null) "$languageName $ordinal" else languageName
    }

    private fun applySelection(player: Player, entry: TrackEntry) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(entry.group.mediaTrackGroup, entry.trackIndex))
            .build()
    }
}