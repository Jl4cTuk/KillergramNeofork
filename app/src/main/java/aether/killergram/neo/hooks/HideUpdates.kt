package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.content.Context
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XC_MethodReplacement
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers

fun Hooks.hideAppUpdates() {
    log("Disabling in-app update prompts...")

    val sharedConfigClass = loadClass("org.telegram.messenger.SharedConfig") ?: return
    val launchActivityClass = loadClass("org.telegram.ui.LaunchActivity")
    val appUpdateClass = loadClass("org.telegram.tgnet.TLRPC\$TL_help_appUpdate") ?: return

    // SharedConfig.pendingAppUpdate: its only static TL_help_appUpdate field.
    val pendingAppUpdate = runCatching { sharedConfigClass.getDeclaredField("pendingAppUpdate") }.getOrNull()
        ?: sharedConfigClass.declaredFields.singleOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == appUpdateClass
        }
    pendingAppUpdate?.isAccessible = true

    // isAppUpdateAvailable(): the static no-arg boolean that reads pendingAppUpdate.
    // Blocks update badges and buttons. Builds that only update through Google Play
    // (official Telegram 13.2+) have neither this nor setNewAppVersionAvailable.
    method(sharedConfigClass, "isAppUpdateAvailable", 0, optional = true) {
        returnType("boolean")
        paramCount(0)
        modifiers(java.lang.reflect.Modifier.STATIC)
        pendingAppUpdate?.let {
            addUsingField(
                "L${sharedConfigClass.name.replace('.', '/')};->${it.name}:" +
                    "L${appUpdateClass.name.replace('.', '/')};",
                org.luckypray.dexkit.query.enums.UsingType.Read
            )
        }
    }?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.returnConstant(false)) }

    // setNewAppVersionAvailable(update): prevents new update state from being accepted.
    method(sharedConfigClass, "setNewAppVersionAvailable", 1, optional = true) {
        returnType("boolean")
        paramTypes(appUpdateClass.name)
        modifiers(java.lang.reflect.Modifier.STATIC)
    }?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.returnConstant(false)) }

    // Blocks the hard update screen route used for non-skippable updates (kept name
    // in non-obfuscated builds only).
    if (launchActivityClass != null) {
        XposedBridge.hookAllMethods(
            launchActivityClass,
            "showUpdateActivity",
            XC_MethodReplacement.returnConstant(null)
        )
    }

    runCatching { pendingAppUpdate?.set(null, null) }

    // Update state persisted by earlier runs: cleared once the application is up.
    TgDeobf.applicationLoader(lpparam)?.let { loader ->
        XposedHelpers.findAndHookMethod(loader, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { pendingAppUpdate?.set(null, null) }
                clearPersistedUpdateState(param.thisObject as? Context)
            }
        })
    }
}

private fun clearPersistedUpdateState(context: Context?) {
    context ?: return
    listOf("userconfing", "mainconfig").forEach { prefsName ->
        runCatching {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .remove("appUpdate")
                .remove("appUpdateCheckTime")
                .remove("appUpdateBuild")
                .apply()
        }.onFailure {
            log("Failed clearing $prefsName update keys: ${it.message}", "DEBUG")
        }
    }
}
