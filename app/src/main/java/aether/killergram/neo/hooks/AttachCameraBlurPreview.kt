package aether.killergram.neo.hooks

import aether.killergram.neo.log
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

private val hookedAttachCameraDrawClasses = HashSet<String>()
private val drawInDecorationFieldSupport = HashMap<String, Boolean>()

fun Hooks.disableAttachCameraPreview() {
    log("Disabling live camera preview in attach tile...")

    val chatAttachClass = loadClass("org.telegram.ui.Components.ChatAttachAlertPhotoLayout") ?: return
    val cameraViewClass = loadClass("org.telegram.messenger.camera.CameraView") ?: return

    // Telegram 12.10.2+ keeps the ChatAttachAlertPhotoLayout name but obfuscates its
    // members; its camera is an anonymous CameraView subclass.
    val attachCameraClass = chatAttachClass.declaredFields
        .map { it.type }
        .firstOrNull { it != cameraViewClass && cameraViewClass.isAssignableFrom(it) }
    if (attachCameraClass != null) {
        hookObfuscatedAttachCamera(attachCameraClass)
        return
    }

    disableLegacyAttachCameraPreview(chatAttachClass, cameraViewClass)
}

/**
 * The attach-menu tile is drawn by an item decoration that raises the camera
 * subclass's only boolean flag and calls its draw() into the tile's bounds. The opened
 * camera draws itself with the flag down. While the flag is up, draw Telegram's own
 * blurred snapshot of the last camera frame (cthumb.jpg) instead of the live preview.
 */
private fun Hooks.hookObfuscatedAttachCamera(attachCameraClass: Class<*>) {
    val tileFlag = attachCameraClass.declaredFields
        .filter { it.type == java.lang.Boolean.TYPE }
        .singleOrNull()
        ?.apply { isAccessible = true }
    if (tileFlag == null) {
        log("Attach camera tile flag not found in ${attachCameraClass.name}", "ERROR")
        return
    }
    val appLoaderClass = loadClass("org.telegram.messenger.ApplicationLoader")

    XposedBridge.hookAllMethods(
        attachCameraClass,
        "dispatchDraw",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val camera = param.thisObject as? View ?: return
                if (!tileFlag.getBoolean(camera)) return
                val canvas = param.args[0] as? Canvas ?: return
                drawTilePlaceholder(camera, canvas, appLoaderClass)
                param.result = null
            }
        }
    )
    log("Attach camera tile hooked: ${attachCameraClass.name}.${tileFlag.name}", "DEBUG")
}

private var tileThumb: Bitmap? = null
private var tileThumbModified = 0L
private val tileThumbPaint = Paint(Paint.FILTER_BITMAP_FLAG)
private val tileThumbRect = RectF()

private fun drawTilePlaceholder(camera: View, canvas: Canvas, appLoaderClass: Class<*>?) {
    val bounds = canvas.clipBounds
    if (bounds.isEmpty) return

    val thumb = loadTileThumb(appLoaderClass)
    if (thumb != null) {
        // Center-crop, like Telegram's own tile placeholder.
        val scale = maxOf(
            bounds.width().toFloat() / thumb.width,
            bounds.height().toFloat() / thumb.height
        )
        val w = thumb.width * scale
        val h = thumb.height * scale
        val left = bounds.exactCenterX() - w / 2f
        val top = bounds.exactCenterY() - h / 2f
        tileThumbRect.set(left, top, left + w, top + h)
        canvas.drawBitmap(thumb, null, tileThumbRect, tileThumbPaint)
        return
    }

    val placeholderId = camera.resources.getIdentifier(
        "icplaceholder",
        "drawable",
        camera.context.packageName
    )
    if (placeholderId != 0) {
        runCatching {
            camera.resources.getDrawable(placeholderId, null).apply {
                setBounds(bounds)
                draw(canvas)
            }
        }
    }
}

private fun loadTileThumb(appLoaderClass: Class<*>?): Bitmap? {
    val filesDir = runCatching {
        XposedHelpers.callStaticMethod(appLoaderClass, "getFilesDirFixed") as? File
    }.getOrNull() ?: return null
    val file = File(filesDir, "cthumb.jpg")
    val modified = file.lastModified()
    if (modified == 0L) return null
    if (tileThumb == null || modified != tileThumbModified) {
        tileThumb = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        tileThumbModified = modified
    }
    return tileThumb
}

/** Non-obfuscated Telegram builds and forks. */
private fun disableLegacyAttachCameraPreview(chatAttachClass: Class<*>, cameraViewClass: Class<*>) {
    run {
        runCatching {
            XposedHelpers.findAndHookConstructor(
                cameraViewClass,
                Context::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val lazyArg = param.args.getOrNull(2) as? Boolean ?: return
                        if (lazyArg || !isCalledFromAttachShowCamera()) {
                            return
                        }
                        // Keep attach camera lazy so it doesn't initialize until user taps.
                        param.args[2] = true
                    }
                }
            )
        }.onFailure {
            log("Failed to hook CameraView constructor: ${it.message}", "DEBUG")
        }
    }

    XposedBridge.hookAllMethods(
        chatAttachClass,
        "showCamera",
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val cameraView = runCatching {
                    XposedHelpers.getObjectField(param.thisObject, "cameraView")
                }.getOrNull() ?: return

                hookAttachCameraDispatchDraw(cameraView.javaClass)
            }
        }
    )
}

private fun hookAttachCameraDispatchDraw(cameraViewClass: Class<*>) {
    val className = cameraViewClass.name
    synchronized(hookedAttachCameraDrawClasses) {
        if (!hookedAttachCameraDrawClasses.add(className)) {
            return
        }
    }

    XposedBridge.hookAllMethods(
        cameraViewClass,
        "dispatchDraw",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val instance = param.thisObject ?: return
                val instanceClass = instance.javaClass

                val hasDrawInDecoration = synchronized(drawInDecorationFieldSupport) {
                    drawInDecorationFieldSupport.getOrPut(instanceClass.name) {
                        runCatching {
                            XposedHelpers.findField(instanceClass, "drawInDecoration")
                        }.isSuccess
                    }
                }

                if (hasDrawInDecoration) {
                    val inDecoration = runCatching {
                        XposedHelpers.getBooleanField(instance, "drawInDecoration")
                    }.getOrDefault(false)
                    if (inDecoration) {
                        param.result = null
                    }
                    return
                }

                if (isAttachDecorationDrawCall()) {
                    param.result = null
                }
            }
        }
    )
}

private fun isCalledFromAttachShowCamera(): Boolean {
    return Throwable().stackTrace.any {
        it.className == "org.telegram.ui.Components.ChatAttachAlertPhotoLayout" &&
            it.methodName == "showCamera"
    }
}

private fun isAttachDecorationDrawCall(): Boolean {
    return Throwable().stackTrace.any {
        it.className.contains("org.telegram.ui.Components.ChatAttachAlertPhotoLayout\$CameraViewItemDecoration")
    }
}

