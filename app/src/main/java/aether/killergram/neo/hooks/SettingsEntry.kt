package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private const val MODULE_PACKAGE = "aether.killergram.neo"
private const val MODULE_SETTINGS_ACTIVITY = "aether.killergram.neo.MainActivity"

/**
 * Adds a "Killergram" entry to the chat list "⋮" menu that opens the module settings.
 *
 * Telegram builds that menu with ItemOptions and, midway, calls the empty extension
 * point ApplicationLoader.addItemOptions(itemOptions). ApplicationLoader is in the kept
 * org.telegram.messenger package, so hooking it survives R8 renames; only the
 * ItemOptions "add item" method is resolved per build (see TgDeobf.itemOptions).
 */
fun Hooks.addSettingsEntry() {
    val appLoaderClass = loadClass("org.telegram.messenger.ApplicationLoader") ?: return
    val itemOptions = TgDeobf.itemOptions(lpparam) ?: run {
        log("ItemOptions unresolved — settings entry not added", "ERROR")
        return
    }

    XposedBridge.hookAllMethods(appLoaderClass, "addItemOptions", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val options = param.args.getOrNull(0) ?: return
            val context = runCatching {
                XposedHelpers.getStaticObjectField(appLoaderClass, "applicationContext") as? Context
            }.getOrNull() ?: return

            val icon = context.resources
                .getIdentifier("msg_customize", "drawable", context.packageName)
            runCatching {
                itemOptions.addItem.invoke(
                    options,
                    icon,
                    "Killergram",
                    Runnable { openModuleSettings(context) },
                    false
                )
            }.onFailure {
                log("Failed to add Killergram menu entry: ${it.message}", "ERROR")
            }
        }
    })
}

private fun openModuleSettings(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName(MODULE_PACKAGE, MODULE_SETTINGS_ACTIVITY))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        log("Module settings activity not reachable: ${e.message}", "ERROR")
        Toast.makeText(context, "Killergram settings unavailable", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        log("Module settings activity not reachable: ${e.message}", "ERROR")
        Toast.makeText(context, "Killergram settings unavailable", Toast.LENGTH_SHORT).show()
    }
}
