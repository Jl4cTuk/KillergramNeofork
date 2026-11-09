package aether.killergram.neo.hooks

import aether.killergram.neo.BuildConfig
import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge

private const val MODULE_PACKAGE = BuildConfig.APPLICATION_ID
private const val MODULE_SETTINGS_ACTIVITY = "aether.killergram.neo.MainActivity"

/**
 * Adds a "Killergram" entry to the chat list "⋮" menu that opens the module settings.
 *
 * Telegram builds that menu with ItemOptions in one DialogsActivity method; while it
 * runs, the entry is added right after the Saved Messages item (see
 * TgDeobf.itemOptions). Up to Telegram 13.1 an empty ApplicationLoader.addItemOptions
 * extension point sat there, R8 removed it in 13.2.
 */
fun Hooks.addSettingsEntry() {
    val menu = TgDeobf.itemOptions(lpparam) ?: run {
        log("Chat list menu unresolved — settings entry not added", "ERROR")
        return
    }
    val buildingMenu = ThreadLocal.withInitial { false }

    XposedBridge.hookMethod(menu.builder, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            buildingMenu.set(true)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            buildingMenu.set(false)
        }
    })

    XposedBridge.hookMethod(menu.addItem, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (buildingMenu.get() != true || param.args[0] != menu.savedIcon) return
            val options = param.thisObject ?: return
            val context = TgDeobf.applicationContext(lpparam) ?: return

            val icon = context.resources
                .getIdentifier("msg_customize", "drawable", context.packageName)
            // The entry is added with the same method; don't come back here for it.
            buildingMenu.set(false)
            runCatching {
                menu.addItem.invoke(
                    options,
                    icon,
                    "Killergram",
                    Runnable { openModuleSettings(context) },
                    false
                )
            }.onFailure {
                log("Failed to add Killergram menu entry: ${it.message}", "ERROR")
            }
            buildingMenu.set(true)
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
