package aether.killergram.neo.data

import aether.killergram.neo.data.ModuleService.putValue
import android.content.Context
import android.content.SharedPreferences

/**
 * The module settings. The app keeps them in its own preferences and mirrors every change
 * into the framework's remote preferences, where the hooks read them.
 */
class ModulePrefsStore private constructor(
    private val preferences: SharedPreferences
) {
    fun isEnabled(key: String): Boolean = preferences.getBoolean(key, false)

    fun getStates(keys: Collection<String>): Map<String, Boolean> {
        return keys.associateWith(::isEnabled)
    }

    fun setEnabled(key: String, enabled: Boolean) = put(key, enabled)

    fun getInt(key: String, default: Int): Int = preferences.getInt(key, default)

    fun setInt(key: String, value: Int) = put(key, value)

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        return preferences.getStringSet(key, default)?.toSet() ?: default
    }

    fun setStringSet(key: String, values: Set<String>) = put(key, values.toSet())

    fun getString(key: String, default: String = ""): String {
        return preferences.getString(key, default) ?: default
    }

    fun setString(key: String, value: String) = put(key, value)

    private fun put(key: String, value: Any) {
        preferences.edit().putValue(key, value).apply()
        runCatching { ModuleService.remotePreferences()?.edit()?.putValue(key, value)?.apply() }
    }

    companion object {
        fun create(context: Context): ModulePrefsStore? =
            ModulePrefsStore(ModuleService.localPreferences(context))
    }
}
