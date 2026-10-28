package app.sable.playback

import android.content.SharedPreferences

/** The scoreboard's counters in a private SharedPreferences file (one small int per week, place and counter). */
class PrefsCounterStore(private val prefs: SharedPreferences) : CounterStore {
    override fun count(key: String): Int = runCatching { prefs.getInt(key, 0) }.getOrDefault(0)
    override fun add(key: String, n: Int) { prefs.edit().putInt(key, count(key) + n).apply() }
    override fun text(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()
    override fun setText(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
