package aether.killergram.neo.hooks

import aether.killergram.neo.core.Features
import aether.killergram.neo.log
import android.media.MediaRecorder
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers

fun Hooks.cameraHigherBitrate(roundBitrate: () -> Int) {
    log("Applying higher video bitrate (round: ${roundBitrate()}kbps)...")

    runCatching {
        XposedBridge.hookAllMethods(
            MediaRecorder::class.java,
            "setVideoEncodingBitRate",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args[0] as? Int ?: return
                    param.args[0] = original * 3
                    log("Video bitrate: $original -> ${param.args[0]}", "DEBUG")
                }
            }
        )
    }.onFailure {
        log("Failed to hook MediaRecorder.setVideoEncodingBitRate: ${it.message}", "ERROR")
    }

    hookMessagesControllerField("roundVideoBitrate", roundBitrate)
}

fun Hooks.cameraHigherResolution(resolution: () -> Int) {
    log("Applying higher video note resolution (${resolution()}px)...")
    hookMessagesControllerField("roundVideoSize", resolution)
}

/**
 * Keeps a MessagesController int at [value] (read when applied) while the feature being
 * installed is on, and puts Telegram's own value back when it is turned off.
 */
private fun Hooks.hookMessagesControllerField(fieldName: String, value: () -> Int) {
    val mcClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val field = runCatching { XposedHelpers.findField(mcClass, fieldName) }.getOrNull()
    if (field != null) {
        hookNamedControllerField(mcClass, field, value)
    } else {
        hookControllerPreference(mcClass, fieldName, value)
    }
}

private fun hookNamedControllerField(mcClass: Class<*>, field: java.lang.reflect.Field, value: () -> Int) {
    // Telegram's own value per controller.
    val ownValues = java.util.WeakHashMap<Any, Int>()
    val hook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                // Config updates run often; only a value Telegram changed needs setting again.
                val controller = param.thisObject ?: return@runCatching
                val current = field.getInt(controller)
                val wanted = value()
                if (current == wanted) return@runCatching
                synchronized(ownValues) { ownValues[controller] = current }
                field.setInt(controller, wanted)
                log("Set ${field.name}: $current -> $wanted", "DEBUG")
            }.onFailure {
                log("Failed to set ${field.name}: ${it.message}", "DEBUG")
            }
        }
    }
    val feature = Features.current
    Features.onChange {
        val on = feature?.isOn ?: return@onChange
        synchronized(ownValues) {
            ownValues.forEach { (controller, own) -> field.setInt(controller, if (on) value() else own) }
        }
    }

    runCatching { XposedBridge.hookAllConstructors(mcClass, hook) }
        .onFailure { log("Failed to hook MessagesController constructor: ${it.message}", "ERROR") }

    for (method in listOf("applyAppConfig", "loadAppConfig", "updateConfig")) {
        runCatching { XposedBridge.hookAllMethods(mcClass, method, hook) }
            .onFailure { log("Failed to hook MessagesController.$method: ${it.message}", "DEBUG") }
    }
}

// SharedPreferences implementation classes, hooked once for every preference key.
private val preferenceKeys = java.util.concurrent.ConcurrentHashMap<String, ControllerPreference>()
private val loadingController = ThreadLocal.withInitial { false }
private val controllers: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

private class ControllerPreference(
    val marker: Int,
    val value: () -> Int,
    val feature: aether.killergram.neo.core.Feature?,
) {
    @Volatile var field: java.lang.reflect.Field? = null
    // Telegram's own value per controller, as loaded or last saved.
    val ownValues = java.util.WeakHashMap<Any, Int>()

    val isOn: Boolean get() = feature?.isOn ?: true

    fun apply(controller: Any) {
        val field = field ?: return
        val own = synchronized(ownValues) { ownValues[controller] } ?: return
        field.setInt(controller, if (isOn) value() else own)
    }
}

/**
 * Obfuscated builds (Telegram 13.2+): the controller field is found by its preference
 * key [key]. The constructor loads it with getInt(key, default); while it runs that read
 * returns a marker, and the int field holding the marker afterwards is the one. Server
 * config updates set the field and save it with putInt(key, …); that saved value becomes
 * Telegram's own one and the field is set again.
 *
 * The field can only be found while a controller is created, so a feature turned on later
 * takes effect after a restart; turning it off or changing the value applies right away.
 * The shared hooks serve both camera features, so they run whether either is on and check
 * each key's own feature.
 */
private fun hookControllerPreference(mcClass: Class<*>, key: String, value: () -> Int) {
    val first = preferenceKeys.isEmpty()
    val preference = ControllerPreference(MARKER_BASE + preferenceKeys.size, value, Features.current)
    preferenceKeys[key] = preference
    Features.onChange {
        synchronized(controllers) { controllers.forEach { runCatching { preference.apply(it) } } }
    }
    if (!first) return

    XposedBridge.withGate(null) { hookControllerPreferences(mcClass) }
}

private fun hookControllerPreferences(mcClass: Class<*>) {
    // Telegram's stored value of each key, read while a controller constructor runs.
    val loadedValues = ThreadLocal<MutableMap<ControllerPreference, Int>?>()

    XposedBridge.hookAllConstructors(mcClass, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            loadingController.set(true)
            loadedValues.set(HashMap())
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            loadingController.set(false)
            val loaded = loadedValues.get().orEmpty()
            loadedValues.set(null)
            val controller = param.thisObject ?: return
            synchronized(controllers) { controllers.add(controller) }
            preferenceKeys.forEach { (name, preference) ->
                val field = preference.field ?: mcClass.declaredFields.firstOrNull { field ->
                    field.type == Int::class.javaPrimitiveType &&
                        !java.lang.reflect.Modifier.isStatic(field.modifiers) && run {
                            field.isAccessible = true
                            field.getInt(controller) == preference.marker
                        }
                }?.also { preference.field = it }
                if (field == null) {
                    log("MessagesController field for $name not found", "ERROR")
                    return@forEach
                }
                val own = loaded[preference] ?: return@forEach
                synchronized(preference.ownValues) { preference.ownValues[controller] = own }
                preference.apply(controller)
                log("Set $name (${field.name}): $own -> ${field.getInt(controller)}", "DEBUG")
            }
        }
    })

    val preferencesClass = Class.forName("android.app.SharedPreferencesImpl")
    XposedHelpers.findAndHookMethod(
        preferencesClass, "getInt", String::class.java, Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (loadingController.get() != true) return
                val preference = preferenceKeys[param.args[0]] ?: return
                val stored = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as Int
                loadedValues.get()?.put(preference, stored)
                param.result = preference.marker
            }
        }
    )
    // A server config update sets the field and saves it; that is Telegram's new value.
    XposedHelpers.findAndHookMethod(
        Class.forName("android.app.SharedPreferencesImpl\$EditorImpl"), "putInt",
        String::class.java, Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val preference = preferenceKeys[param.args[0]] ?: return
                val field = preference.field ?: return
                val saved = param.args[1] as Int
                synchronized(controllers) {
                    controllers.forEach { controller ->
                        runCatching {
                            if (field.getInt(controller) == saved) {
                                synchronized(preference.ownValues) { preference.ownValues[controller] = saved }
                            }
                            preference.apply(controller)
                        }
                    }
                }
            }
        }
    )
}

// Distinct from any real size or bitrate.
private const val MARKER_BASE = 0x4b47_0000
