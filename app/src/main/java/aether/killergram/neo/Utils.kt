package aether.killergram.neo

import aether.killergram.neo.core.PreferenceKeys
import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import aether.killergram.neo.xposed.XposedBridge

/**
 * The module settings inside the hooked app: the remote preferences the module app keeps
 * in sync (see ModulePrefsStore). They are live, no reload needed.
 */
class PreferencesUtils {
    fun getPrefsInstance(prefsName: String = PreferenceKeys.PREFS_NAME): SharedPreferences =
        cached.getOrPut(prefsName) { XposedBridge.framework.getRemotePreferences(prefsName) }

    private companion object {
        val cached = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()
    }
}

fun log(message: String, level: String = "") {
    // The module app's own process has no framework, only the hooks log.
    if (!XposedBridge.isReady) return
    val debugLogging = PreferencesUtils().getPrefsInstance()
        .getBoolean(PreferenceKeys.DEBUG_LOGGING, false)
    // The framework log also goes to logcat (tag "KG Neo").
    when (level) {
        "" -> XposedBridge.log(message, Log.INFO)
        "DEBUG" -> if (debugLogging) XposedBridge.log("[DEBUG] $message", Log.DEBUG)
        "ERROR" -> XposedBridge.log("[ERROR] $message", Log.ERROR)
        else -> XposedBridge.log("[$level] $message", Log.INFO)
    }
}

/** Whether the Xposed framework runs the module (its service is bound). */
@Suppress("UNUSED_PARAMETER")
fun isLsposedAvailable(context: Context): Boolean = aether.killergram.neo.data.ModuleService.isActive
