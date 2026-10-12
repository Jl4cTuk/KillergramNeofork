package aether.killergram.neo.hooks

import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Telegram 12.10+ stores the per-photo HD choice as a nullable Boolean on
 * MediaController.MediaEditState: null means "not chosen", and isHighQuality() then falls
 * back to SharedConfig.photoHighQualityDefault (the last HD/SD toggle the user made).
 * Every send path (attach menu, photo viewer, share intents) reads isHighQuality(), so
 * answering true for unchosen photos makes HD the default while an explicit SD toggle on
 * a photo still wins. MediaController is in the kept org.telegram.messenger package.
 */
fun Hooks.defaultHdMediaSending() {
    log("Enabling default HD media sending...")

    val editStateClass = loadClass("org.telegram.messenger.MediaController\$MediaEditState") ?: return
    val highQualityField = runCatching {
        XposedHelpers.findField(editStateClass, "highQuality")
    }.getOrNull()

    // Older Telegram and forks keep a primitive boolean that starts false: set it to HD
    // whenever a photo entry is created or reset.
    if (highQualityField?.type == java.lang.Boolean.TYPE) {
        loadClass("org.telegram.messenger.MediaController\$PhotoEntry")?.let { photoEntryClass ->
            val setDefaultHd = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val entry = param.thisObject ?: return
                    runCatching {
                        if (!XposedHelpers.getBooleanField(entry, "isVideo")) {
                            highQualityField.setBoolean(entry, true)
                        }
                    }
                }
            }
            XposedBridge.hookAllConstructors(photoEntryClass, setDefaultHd)
            XposedBridge.hookAllMethods(photoEntryClass, "reset", setDefaultHd)
        }
        return
    }
    if (highQualityField == null) {
        log("MediaEditState.highQuality not found — HD default skipped", "ERROR")
        return
    }

    XposedBridge.hookAllMethods(
        editStateClass,
        "isHighQuality",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val state = param.thisObject ?: return
                val isVideo = runCatching {
                    XposedHelpers.getBooleanField(state, "isVideo")
                }.getOrDefault(true)
                if (isVideo) return

                if (highQualityField.get(state) == null) {
                    param.result = true
                }
            }
        }
    )
}
