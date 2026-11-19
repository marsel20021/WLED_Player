package com.marsel.wledplayer.skip

import org.json.JSONArray
import org.json.JSONObject

/**
 * Состояние пропуска для телефона: ТВ присылает его по вебсокету, телефон только показывает кнопку.
 * Формат: {"type":"skipState","offer":{kind,progress,secondsLeft,manual}|null,"undo":{kind}|null,
 *          "seasonChoice":{seasons:[..],selected:n|null}|null}
 */
object SkipWire {
    const val TYPE = "skipState"

    fun toJson(state: SkipUiState): JSONObject {
        val o = JSONObject().put("type", TYPE)
        val offer = state.offer
        o.put(
            "offer",
            if (offer == null) JSONObject.NULL else JSONObject()
                .put("kind", offer.kind.name)
                .put("progress", Math.round(offer.progress * 100) / 100.0)
                .put("secondsLeft", offer.secondsLeft)
                .put("manual", offer.manual)
        )
        val undo = state.undo
        o.put("undo", if (undo == null) JSONObject.NULL else JSONObject().put("kind", undo.kind.name))
        val choice = state.seasonChoice
        o.put(
            "seasonChoice",
            if (choice == null) JSONObject.NULL else JSONObject()
                .put("seasons", JSONArray(choice.seasons))
                .put("selected", choice.selected ?: JSONObject.NULL)
        )
        return o
    }
}
