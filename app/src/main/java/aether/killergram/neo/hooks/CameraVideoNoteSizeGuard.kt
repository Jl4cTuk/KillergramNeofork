package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers
import java.io.File

private const val MAX_VIDEO_NOTE_SIZE = 10L * 1024 * 1024 // 10 MB

fun Hooks.videoNoteSizeGuard() {
    log("Enabling video note size guard (10MB)...")

    val sendHelperClass = loadClass("org.telegram.messenger.SendMessagesHelper") ?: return
    val roundVideo = roundVideoField() ?: run {
        log("VideoEditedInfo.roundVideo not found — size guard off", "ERROR")
        return
    }
    val videoInfoClass = roundVideo.declaringClass

    // prepareSendingVideo(accountInstance, path, videoEditedInfo, coverPath, coverPhoto, …):
    // the static methods taking a VideoEditedInfo and a TLRPC.Photo. Since Telegram 13.2
    // R8 reorders the parameters, so the arguments are taken by type: the video file is
    // the first String.
    val prepareSendingVideo = sendHelperClass.declaredMethods.filter { method ->
        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contains(videoInfoClass) &&
            method.parameterTypes.any { it.name == "org.telegram.tgnet.TLRPC\$Photo" }
    }
    if (prepareSendingVideo.isEmpty()) {
        log("SendMessagesHelper.prepareSendingVideo not found — size guard off", "ERROR")
        return
    }

    prepareSendingVideo.forEach { method ->
        val infoIndex = method.parameterTypes.indexOf(videoInfoClass)
        val pathIndex = method.parameterTypes.indexOf(String::class.java)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val videoEditedInfo = param.args.getOrNull(infoIndex) ?: return
                if (!roundVideo.getBoolean(videoEditedInfo)) return

                val videoPath = param.args.getOrNull(pathIndex) as? String ?: return
                val fileSize = runCatching { File(videoPath).length() }.getOrDefault(0L)

                if (fileSize > MAX_VIDEO_NOTE_SIZE) {
                    roundVideo.setBoolean(videoEditedInfo, false)
                    log("Video note ${fileSize / 1024 / 1024}MB exceeds 10MB, sending as regular video")
                }
            }
        })
    }
}

/**
 * VideoEditedInfo.roundVideo: by name, or on obfuscated builds:
 * VideoEditedInfo is the type the round video camera (InstantCameraView) keeps in a field
 * and SendMessagesHelper's video senders take; roundVideo is the field its only plain
 * boolean getter (canAutoPlaySourceVideo) returns.
 */
private fun Hooks.roundVideoField(): java.lang.reflect.Field? {
    loadClass("org.telegram.messenger.VideoEditedInfo", logOnFailure = false)?.let { info ->
        return runCatching { XposedHelpers.findField(info, "roundVideo") }.getOrNull()
    }
    val camera = TgDeobf.instantCamera(lpparam) ?: return null
    val sendHelper = loadClass("org.telegram.messenger.SendMessagesHelper") ?: return null
    val cameraTypes = XposedHelpers.findClass(camera.className, lpparam.classLoader)
        .declaredFields.map { it.type }.toSet()
    val senderTypes = sendHelper.declaredMethods
        .filter { method -> method.parameterTypes.any { it.name == "org.telegram.tgnet.TLRPC\$Photo" } }
        .flatMap { it.parameterTypes.asList() }.toSet()
    val infoClass = (cameraTypes intersect senderTypes)
        .singleOrNull { it.name.startsWith("org.telegram.messenger.") } ?: return null
    return TgDeobf.findField(lpparam, "roundVideo", "VideoEditedInfo.roundVideo") { bridge ->
        bridge.findMethod {
            matcher {
                declaredClass(infoClass.name)
                returnType("boolean")
                paramCount(0)
            }
        }.filter { it.invokes.isEmpty() && it.usingFields.size == 1 }
            .map { it.usingFields.single().field }
            .filter { it.declaredClassName == infoClass.name && it.typeName == "boolean" }
    }
}
