package com.marsel.wledplayer

import android.content.SharedPreferences

/**
 * Общие хелперы для чтения значений SharedPreferences независимо от того, каким
 * виджетом (EditTextPreference, SwitchPreferenceCompat и т.д.) было сохранено
 * значение.
 */
fun rawStringValue(prefs: SharedPreferences, key: String, def: String): String {
    return when (val v = prefs.all[key]) {
        null -> def
        is String -> v
        is Int -> v.toString()
        is Float -> v.toString()
        is Boolean -> v.toString()
        else -> def
    }
}

fun rawBooleanValue(prefs: SharedPreferences, key: String, def: Boolean): Boolean {
    return when (val v = prefs.all[key]) {
        null -> def
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: def
        is Int -> v != 0
        else -> def
    }
}