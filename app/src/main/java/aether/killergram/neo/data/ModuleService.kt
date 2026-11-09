package aether.killergram.neo.data

import aether.killergram.neo.core.PreferenceKeys
import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Xposed framework service (libxposed API 102). While it is bound the module is
 * active, and the settings are mirrored into its remote preferences, which the hooks
 * read inside Telegram.
 */
object ModuleService {
    private val bound = MutableStateFlow<XposedService?>(null)
    private lateinit var context: Context

    val service: StateFlow<XposedService?> = bound.asStateFlow()

    val isActive: Boolean get() = bound.value != null

    fun init(context: Context) {
        this.context = context.applicationContext
        Thread(::migrateLegacyPreferences, "kg-prefs-migration").start()
    }

    /**
     * Before libxposed API 102 the settings lived in the legacy XSharedPreferences file,
     * which Vector keeps under /data/misc/<random>/prefs/<package>/. The app can't list
     * that path, so it is read once as root.
     */
    private fun migrateLegacyPreferences() {
        val state = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (state.getBoolean(LEGACY_MIGRATED, false)) return
        val local = localPreferences(context)
        if (local.all.isEmpty()) {
            val xml = RootActions.readRootOutput(
                "cat /data/misc/*/prefs/${context.packageName}/${PreferenceKeys.PREFS_NAME}.xml"
            ) ?: return  // No root yet: try again on the next start.
            val editor = local.edit()
            runCatching { parsePreferencesXml(xml).forEach { (key, value) -> editor.putValue(key, value) } }
            editor.commit()
            remotePreferences()?.let { copyAll(local, it) }
        }
        state.edit().putBoolean(LEGACY_MIGRATED, true).apply()
    }

    /** Values of a SharedPreferences XML file, with their types. */
    private fun parsePreferencesXml(xml: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        var set: MutableSet<String>? = null
        var setName: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    val name = parser.getAttributeValue(null, "name")
                    val value = parser.getAttributeValue(null, "value")
                    when (parser.name) {
                        "boolean" -> result[name] = value.toBoolean()
                        "int" -> value.toIntOrNull()?.let { result[name] = it }
                        "long" -> value.toLongOrNull()?.let { result[name] = it }
                        "float" -> value.toFloatOrNull()?.let { result[name] = it }
                        "string" -> if (set != null) set.add(parser.nextText()) else result[name] = parser.nextText()
                        "set" -> { set = LinkedHashSet(); setName = name }
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "set") {
                    setName?.let { result[it] = set }
                    set = null
                    setName = null
                }
            }
        }
        return result
    }

    private const val MIGRATION_PREFS = "kgneo_migration"
    private const val LEGACY_MIGRATED = "legacy_prefs_migrated"

    fun onBind(service: XposedService) {
        bound.value = service
        // Settings changed while the framework was away, or saved by older versions of
        // the module in the same local file, reach the hooks now.
        runCatching { copyAll(localPreferences(context), remotePreferences() ?: return) }
    }

    fun onDied(service: XposedService) {
        if (bound.value === service) bound.value = null
    }

    fun localPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)

    fun remotePreferences(): SharedPreferences? =
        runCatching { bound.value?.getRemotePreferences(PreferenceKeys.PREFS_NAME) }.getOrNull()

    private fun copyAll(from: SharedPreferences, to: SharedPreferences) {
        val editor = to.edit()
        from.all.forEach { (key, value) -> editor.putValue(key, value) }
        editor.apply()
    }

    @Suppress("UNCHECKED_CAST")
    fun SharedPreferences.Editor.putValue(key: String, value: Any?): SharedPreferences.Editor = when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value as Set<String>)
        null -> remove(key)
        else -> this
    }
}
