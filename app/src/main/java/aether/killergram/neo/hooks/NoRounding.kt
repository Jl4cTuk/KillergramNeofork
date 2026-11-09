package aether.killergram.neo.hooks

import aether.killergram.neo.log
import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import aether.killergram.neo.xposed.XC_MethodHook
import java.util.Locale

fun Hooks.noRounding() {
    log("Disabling rounding...")

    val localeControllerClass = loadClass("org.telegram.messenger.LocaleController") ?: return

    // formatShortNumber(int number, int[] rounded): "1.2K"/"3M" style counters. Since
    // Telegram 13.2 the parameters come in the other order.
    val formatShortNumber = method(localeControllerClass, "formatShortNumber", 2) {
        returnType("java.lang.String")
        paramCount(2)
        modifiers(java.lang.reflect.Modifier.STATIC)
        usingStrings("K", "M")
        usingNumbers(1000)
    }

    hook(formatShortNumber, "LocaleController.formatShortNumber", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val number = param.args.firstOrNull { it is Int } as? Int ?: return
            val formatter = NumberFormat.getInstance(Locale.US) as DecimalFormat
            formatter.applyPattern("#,###")
            param.result = formatter.format(number)
        }
    })
}
