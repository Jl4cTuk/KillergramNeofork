package aether.killergram.neo.hooks

import aether.killergram.neo.log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import java.util.Locale


fun Hooks.forceSystemTypeface() {
    val androidUtilsClass = loadClass("org.telegram.messenger.AndroidUtilities") ?: return
    val localeControllerClass = loadClass("org.telegram.messenger.LocaleController") ?: return

    XposedBridge.hookAllMethods(
        androidUtilsClass,
        "getTypeface",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                var language: String? = null
                try {
                    val getInstanceMethod = localeControllerClass.getMethod("getInstance")
                    val localeControllerInstance = getInstanceMethod.invoke(null)

                    val getCurrentLocaleMethod = localeControllerInstance.javaClass.getMethod("getCurrentLocale")
                    val locale = getCurrentLocaleMethod.invoke(localeControllerInstance) as Locale

                    language = locale.language
                } catch (e: Exception) {
                    log("Failed to get current locale", "ERROR")
                    log(e.message.toString(), "DEBUG")
                }

                if (language == null) return

                val testText: String = if (listOf("zh", "ja", "ko").contains(language)) "好" else "R"

                val assetPath = param?.args?.get(0) as? String ?: return

                val TYPEFACE_ROBOTO_MONO = androidUtilsClass.getDeclaredField("TYPEFACE_ROBOTO_MONO").apply { isAccessible = true }.get(null) as String
                val TYPEFACE_ROBOTO_CONDENSED_BOLD = androidUtilsClass.getDeclaredField("TYPEFACE_ROBOTO_CONDENSED_BOLD").apply { isAccessible = true }.get(null) as String
                val TYPEFACE_ROBOTO_MEDIUM_ITALIC = androidUtilsClass.getDeclaredField("TYPEFACE_ROBOTO_MEDIUM_ITALIC").apply { isAccessible = true }.get(null) as String
                val TYPEFACE_ROBOTO_MEDIUM = androidUtilsClass.getDeclaredField("TYPEFACE_ROBOTO_MEDIUM").apply { isAccessible = true }.get(null) as String
                val TYPEFACE_ROBOTO_ITALIC = androidUtilsClass.getDeclaredField("TYPEFACE_ROBOTO_ITALIC").apply { isAccessible = true }.get(null) as String

                try {
                    val t = when (assetPath) {
                        TYPEFACE_ROBOTO_MONO -> Typeface.MONOSPACE
                        TYPEFACE_ROBOTO_CONDENSED_BOLD -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                        TYPEFACE_ROBOTO_MEDIUM_ITALIC -> {
                            if (isMediumWeightSupported(testText)) {
                                Typeface.create("sans-serif-medium", Typeface.ITALIC)
                            } else {
                                Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
                            }
                        }
                        TYPEFACE_ROBOTO_MEDIUM -> {
                            if (isMediumWeightSupported(testText)) {
                                Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            } else {
                                Typeface.create("sans-serif", Typeface.BOLD)
                            }
                        }
                        TYPEFACE_ROBOTO_ITALIC -> {
                            if (Build.VERSION.SDK_INT >= 28) {
                                Typeface.create(Typeface.SANS_SERIF, 400, true)
                            } else {
                                Typeface.create("sans-serif", Typeface.ITALIC)
                            }
                        }
                        else -> {
                            if (Build.VERSION.SDK_INT >= 28) {
                                Typeface.create(Typeface.SANS_SERIF, 400, false)
                            } else {
                                Typeface.create("sans-serif", Typeface.NORMAL)
                            }
                        }
                    }

                    param.result = t

                } catch (e: Exception) {
                    log("Failed to inject typeface!", "ERROR")
                    log(e.message.toString(), "DEBUG")
                    param.result = null
                }
            }
        }
    )
}

private const val canvasSize: Int = 20

private val paint = Paint().apply {
    textSize = canvasSize.toFloat()
    isAntiAlias = false
    isSubpixelText = false
    isFakeBoldText = false
}

private var mediumWeightSupported: Boolean? = null

private fun isMediumWeightSupported(testText: String): Boolean {
    if (mediumWeightSupported == null) {
        mediumWeightSupported = testTypeface(testText, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }
    return mediumWeightSupported!!
}

private fun testTypeface(testText: String, typeface: Typeface): Boolean {
    val canvas = Canvas()

    val bitmap1 = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ALPHA_8)
    canvas.setBitmap(bitmap1)
    paint.typeface = null
    canvas.drawText(testText, 0f, canvasSize.toFloat(), paint)

    val bitmap2 = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ALPHA_8)
    canvas.setBitmap(bitmap2)
    paint.typeface = typeface
    canvas.drawText(testText, 0f, canvasSize.toFloat(), paint)

    val supported = !bitmap1.sameAs(bitmap2)
    bitmap1.recycle()
    bitmap2.recycle()
    return supported
}