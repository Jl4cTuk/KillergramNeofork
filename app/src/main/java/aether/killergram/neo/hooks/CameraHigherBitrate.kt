package aether.killergram.neo.hooks

import aether.killergram.neo.log
import android.media.MediaRecorder
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers

fun Hooks.cameraHigherBitrate(roundBitrate: Int) {
    log("Applying higher video bitrate (round: ${roundBitrate}kbps)...")

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

fun Hooks.cameraHigherResolution(resolution: Int) {
    log("Applying higher video note resolution (${resolution}px)...")
    hookMessagesControllerField("roundVideoSize", resolution)
}

private fun Hooks.hookMessagesControllerField(fieldName: String, value: Int) {
    val mcClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val field = runCatching { XposedHelpers.findField(mcClass, fieldName) }.getOrNull()
    if (field != null) {
        hookNamedControllerField(mcClass, field, value)
    } else {
        hookControllerPreference(mcClass, fieldName, value)
    }
}

private fun hookNamedControllerField(mcClass: Class<*>, field: java.lang.reflect.Field, value: Int) {
    val hook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                // Config updates run often; only a value Telegram changed needs setting again.
                val current = field.getInt(param.thisObject)
                if (current == value) return@runCatching
                field.setInt(param.thisObject, value)
                log("Set ${field.name}: $current -> $value", "DEBUG")
            }.onFailure {
                log("Failed to set ${field.name}: ${it.message}", "DEBUG")
            }
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

private class ControllerPreference(val marker: Int, val value: Int) {
    @Volatile var field: java.lang.reflect.Field? = null
}

/**
 * Obfuscated builds (Telegram 13.2+): the controller field is found by its preference
 * key [key]. The constructor loads it with getInt(key, default); while it runs that read
 * returns a marker, and the int field holding the marker afterwards is the one. Server
 * config updates set the field and save it with putInt(key, …), which puts [value] back.
 */
private fun hookControllerPreference(mcClass: Class<*>, key: String, value: Int) {
    val first = preferenceKeys.isEmpty()
    preferenceKeys[key] = ControllerPreference(MARKER_BASE + preferenceKeys.size, value)
    if (!first) return

    XposedBridge.hookAllConstructors(mcClass, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            loadingController.set(true)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            loadingController.set(false)
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
                field.setInt(controller, preference.value)
                log("Set $name (${field.name}) -> ${preference.value}", "DEBUG")
            }
        }
    })

    val preferencesClass = Class.forName("android.app.SharedPreferencesImpl")
    XposedHelpers.findAndHookMethod(
        preferencesClass, "getInt", String::class.java, Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (loadingController.get() != true) return
                preferenceKeys[param.args[0]]?.let { param.result = it.marker }
            }
        }
    )
    XposedHelpers.findAndHookMethod(
        Class.forName("android.app.SharedPreferencesImpl\$EditorImpl"), "putInt",
        String::class.java, Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val preference = preferenceKeys[param.args[0]] ?: return
                val field = preference.field ?: return
                param.args[1] = preference.value
                synchronized(controllers) {
                    controllers.forEach { runCatching { field.setInt(it, preference.value) } }
                }
            }
        }
    )
}

// Distinct from any real size or bitrate.
private const val MARKER_BASE = 0x4b47_0000
