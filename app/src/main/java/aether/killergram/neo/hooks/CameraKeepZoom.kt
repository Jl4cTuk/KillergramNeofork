package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun Hooks.cameraKeepZoom() {
    log("Enabling persistent zoom for video notes...")

    val instantCameraClass = loadClass("org.telegram.ui.Components.InstantCameraView", logOnFailure = false)
    if (instantCameraClass == null) {
        keepObfuscatedCameraZoom()
        return
    }

    runCatching {
        XposedBridge.hookAllMethods(
            instantCameraClass,
            "finishZoom",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val recording = runCatching {
                        XposedHelpers.getBooleanField(param.thisObject, "recording")
                    }.getOrDefault(false)

                    if (recording) {
                        param.result = null
                        log("Blocked zoom reset during recording", "DEBUG")
                    }
                }
            }
        )
    }.onFailure {
        log("Failed to hook InstantCameraView.finishZoom: ${it.message}", "ERROR")
    }
}

/*
 * Telegram 12.10.2+: finishZoom is resolved with DexKit. Pinch zoom only starts while a
 * video note is recording, so finishZoom only ever runs then and is blocked outright.
 */
private fun Hooks.keepObfuscatedCameraZoom() {
    val camera = TgDeobf.instantCamera(lpparam) ?: return
    val finishZoom = TgDeobf.instantCameraFinishZoom(lpparam, camera.className) ?: return
    XposedBridge.hookMethod(finishZoom, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
        }
    })
}
