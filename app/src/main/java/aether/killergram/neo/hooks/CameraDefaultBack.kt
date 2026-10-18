package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private const val CAMERA2_SESSION = "org.telegram.messenger.camera.Camera2Session"

private const val FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT =
    "killergramneo_force_rear_on_next_instant_camera_init"

fun Hooks.cameraDefaultBack() {
    log("Setting default camera to rear...")

    // Generic camera widget lives in the kept org.telegram.messenger.camera package.
    // Forcing it rear on construction covers the attach/photo camera surfaces.
    loadClass("org.telegram.messenger.camera.CameraView")?.let { cameraViewClass ->
        runCatching {
            XposedBridge.hookAllConstructors(
                cameraViewClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            XposedHelpers.setBooleanField(param.thisObject, "isFrontface", false)
                            XposedHelpers.setBooleanField(param.thisObject, "initialFrontface", false)
                        }.onFailure {
                            log("Failed to set CameraView frontface fields: ${it.message}", "DEBUG")
                        }
                    }
                }
            )
        }.onFailure {
            log("Failed to hook CameraView constructors: ${it.message}", "ERROR")
        }
    }

    // Telegram 13's new round-video recorder (the "Camera2" round video option) does not
    // use InstantCameraView. It starts on the lens stored in the enum setting
    // "round_video_last_camera" (default FRONT, updated when the user switches lens),
    // which is read exactly once per recording, when the camera opens. Answer BACK there
    // so every recording starts on the rear lens; switching during a recording still works.
    TgDeobf.enumSettingGetter(lpparam)?.let { getter ->
        val keyField = getter.declaringClass.declaredFields
            .firstOrNull { it.type == String::class.java }
            ?.apply { isAccessible = true }
        if (keyField == null) {
            log("Enum setting key field not found — new recorder rear default skipped", "ERROR")
            return@let
        }
        XposedBridge.hookMethod(getter, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (keyField.get(param.thisObject) != TgDeobf.ROUND_VIDEO_LAST_CAMERA) return
                val current = param.result as? Enum<*> ?: return
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val lensType = current.declaringJavaClass as Class<out Enum<*>>
                    param.result = java.lang.Enum.valueOf(lensType as Class<Nothing>, "BACK")
                }.onFailure {
                    log("Failed to force rear lens for new round recorder: ${it.message}", "DEBUG")
                }
            }
        })
    }

    // The round video-note camera is InstantCameraView, R8-obfuscated in Telegram
    // 12.10.2+. Resolve its class, open method, selector and frontface flag by fingerprint.
    val ic = TgDeobf.instantCamera(lpparam) ?: run {
        log("InstantCameraView unresolved — round-video rear default skipped", "ERROR")
        return
    }
    val instantCameraClass =
        XposedHelpers.findClassIfExists(ic.className, lpparam.classLoader) ?: return

    // Dual Camera2 mode opens both lenses at once: session 0 (front) renders to GL surface
    // 0, session 1 (back) to surface 1, and the renderer shows the surface in this index.
    val sessionsField = instantCameraClass.declaredFields
        .firstOrNull { it.type.isArray && it.type.componentType?.name == CAMERA2_SESSION }
        ?.apply { isAccessible = true }
    val surfaceIndexField = TgDeobf.roundCameraSurfaceIndex(lpparam, instantCameraClass)

    // showCamera(fromPaused), fresh open (fromPaused == false):
    // - Camera2: showCamera keeps the frontface flag from the last recording and picks the
    //   active session by it. Clearing the flag starts on the rear lens. In dual mode the
    //   renderer's surface index is then pointed at the back surface, which is exactly the
    //   state Telegram leaves after a switch, so switching to the front lens mid-recording
    //   stays seamless (no session restart).
    // - Camera1: showCamera resets the flag to front itself; arm initCamera to clear it.
    runCatching {
        XposedBridge.hookAllMethods(
            instantCameraClass,
            ic.showCamera,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fromPaused = param.args.firstOrNull() as? Boolean
                    if (fromPaused == false) {
                        runCatching {
                            XposedHelpers.setBooleanField(param.thisObject, ic.frontface, false)
                        }
                        XposedHelpers.setAdditionalInstanceField(
                            param.thisObject,
                            FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT,
                            true
                        )
                    } else {
                        XposedHelpers.removeAdditionalInstanceField(
                            param.thisObject,
                            FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT
                        )
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() as? Boolean != false) return
                    runCatching {
                        val sessions = sessionsField?.get(param.thisObject) as? Array<*> ?: return
                        val dual = sessions.size >= 2 && sessions[0] != null && sessions[1] != null
                        if (!dual || surfaceIndexField == null) return
                        val front = XposedHelpers.getBooleanField(param.thisObject, ic.frontface)
                        surfaceIndexField.setInt(param.thisObject, if (front) 0 else 1)
                    }.onFailure {
                        log("Failed to align dual camera surface: ${it.message}", "DEBUG")
                    }
                }
            }
        )
    }.onFailure {
        log("Failed to hook InstantCameraView.showCamera: ${it.message}", "ERROR")
    }

    // initCamera(): runs inside showCamera right after the Camera1 front default is
    // applied. When armed (fresh open), flip the frontface flag to false so it selects
    // rear. Switching cameras goes through a different path and is left untouched.
    runCatching {
        XposedBridge.hookAllMethods(
            instantCameraClass,
            ic.initCamera,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val armed = XposedHelpers.getAdditionalInstanceField(
                            param.thisObject,
                            FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT
                        ) == true
                        if (!armed) return@runCatching
                        XposedHelpers.removeAdditionalInstanceField(
                            param.thisObject,
                            FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT
                        )
                        XposedHelpers.setBooleanField(param.thisObject, ic.frontface, false)
                        log("Forced rear on round camera (frontface=${ic.frontface}=false)", "DEBUG")
                    }.onFailure {
                        log("Failed to force rear on InstantCameraView: ${it.message}", "DEBUG")
                    }
                }
            }
        )
    }.onFailure {
        log("Failed to hook InstantCameraView.initCamera: ${it.message}", "ERROR")
    }
}
