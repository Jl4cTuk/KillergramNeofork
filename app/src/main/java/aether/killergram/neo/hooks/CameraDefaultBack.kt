package aether.killergram.neo.hooks

import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private const val FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT =
    "killergramneo_force_rear_on_next_instant_camera_init"

fun Hooks.cameraDefaultBack() {
    log("Setting default camera to rear...")

    val cameraViewClass = loadClass("org.telegram.messenger.camera.CameraView")
    if (cameraViewClass != null) {
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

    val instantCameraClass = loadClass("org.telegram.ui.Components.InstantCameraView") ?: return

    // Arm the rear-camera override only when a new video-note camera is opened.
    // showCamera(true) resumes an existing camera and must preserve its current facing.
    runCatching {
        XposedBridge.hookAllMethods(
            instantCameraClass,
            "showCamera",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val fromPaused = param.args.firstOrNull() as? Boolean ?: return@runCatching
                        val alreadyVisible = XposedHelpers.getObjectField(
                            param.thisObject,
                            "textureView"
                        ) != null

                        if (!fromPaused && !alreadyVisible) {
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
                    }.onFailure {
                        log("Failed to arm rear camera for InstantCameraView: ${it.message}", "DEBUG")
                    }
                }
            }
        )
    }.onFailure {
        log("Failed to hook InstantCameraView.showCamera: ${it.message}", "ERROR")
    }

    // initCamera is also called after the user taps the switch-camera button.
    // Consume the marker so only the initial camera selection is overridden.
    runCatching {
        XposedBridge.hookAllMethods(
            instantCameraClass,
            "initCamera",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val shouldForceRear = XposedHelpers.getAdditionalInstanceField(
                            param.thisObject,
                            FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT
                        ) == true
                        if (!shouldForceRear) {
                            return@runCatching
                        }

                        XposedHelpers.removeAdditionalInstanceField(
                            param.thisObject,
                            FORCE_REAR_ON_NEXT_INSTANT_CAMERA_INIT
                        )
                        XposedHelpers.setBooleanField(param.thisObject, "isFrontface", false)
                    }.onFailure {
                        log("Failed to set InstantCameraView.isFrontface: ${it.message}", "DEBUG")
                    }
                }
            }
        )
    }.onFailure {
        log("Failed to hook InstantCameraView.initCamera: ${it.message}", "ERROR")
    }
}
