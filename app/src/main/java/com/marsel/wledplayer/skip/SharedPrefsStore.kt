package com.marsel.wledplayer.skip

import android.content.Context

/** Хранилище для кэша пропуска в SharedPreferences. */
class SharedPrefsStore(context: Context, name: String = "skip_cache") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
