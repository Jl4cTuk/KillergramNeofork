package aether.killergram.neo.hooks

import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers
import java.util.Locale

fun Hooks.showTimestampSeconds() {
    log("Enabling seconds in timestamps...")

    val localeControllerClass = loadClass("org.telegram.messenger.LocaleController") ?: return
    val fastDateFormatClass = loadClass("org.telegram.messenger.time.FastDateFormat") ?: return

    // getFormatterDay(): the message time formatter, built from the "formatterDay24H" /
    // "formatterDay12H" locale keys. getFormatterConstDay() (same pattern, no locale
    // keys) is gone since Telegram 13.2, so it is only hooked by name.
    val getFormatterDay = method(localeControllerClass, "getFormatterDay", 0) {
        returnType(fastDateFormatClass.name)
        paramCount(0)
        usingStrings("formatterDay24H", "formatterDay12H")
    }
    val getFormatterConstDay = localeControllerClass.declaredMethods.singleOrNull {
        it.name == "getFormatterConstDay" && it.parameterTypes.isEmpty()
    }

    listOfNotNull(getFormatterDay, getFormatterConstDay).forEach { getter ->
        // The getter caches its formatter in an obfuscated field, so the result is
        // replaced on every call instead.
        hook(getter, "LocaleController.${getter.name}", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val localeController = param.thisObject ?: return
                param.result = buildSecondsFormatter(fastDateFormatClass, localeController) ?: return
            }
        })
    }
    if (getFormatterDay == null) log("LocaleController.getFormatterDay not found", "ERROR")
}

private var secondsFormatter: Pair<String, Any>? = null

private fun buildSecondsFormatter(fastDateFormatClass: Class<*>, localeController: Any): Any? {
    val context = runCatching {
        aether.killergram.neo.xposed.AndroidAppHelper.currentApplication()
    }.getOrNull()
    val is24HourFormat = context?.let { android.text.format.DateFormat.is24HourFormat(it) } ?: true

    // LocaleController.currentLocale: the locale the app language is shown in.
    val locale = runCatching {
        XposedHelpers.getObjectField(localeController, "currentLocale") as? Locale
    }.getOrNull() ?: localeController.javaClass.declaredFields.firstNotNullOfOrNull { field ->
        if (field.type != Locale::class.java || java.lang.reflect.Modifier.isStatic(field.modifiers)) return@firstNotNullOfOrNull null
        field.isAccessible = true
        field.get(localeController) as? Locale
    } ?: Locale.getDefault()

    val lang = locale.language.lowercase(Locale.ROOT)
    val formatterLocale = if (lang == "ar" || lang == "ko") locale else Locale.US
    val pattern = if (is24HourFormat) "HH:mm:ss" else "h:mm:ss a"

    val key = "$pattern/$formatterLocale"
    secondsFormatter?.takeIf { it.first == key }?.let { return it.second }
    return runCatching {
        XposedHelpers.callStaticMethod(fastDateFormatClass, "getInstance", pattern, formatterLocale)
    }.getOrNull()?.also { secondsFormatter = key to it }
}
