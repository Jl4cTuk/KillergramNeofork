package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import aether.killergram.neo.xposed.LoadPackageParam
import android.graphics.drawable.Drawable
import android.view.View
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap

fun Hooks.replaceAppTitle(mode: String, customText: String, centerTitle: Boolean) {
    log("Setting up app title replacement (mode=$mode, center=$centerTitle)...")

    val dialogsActivityClass = loadClass("org.telegram.ui.DialogsActivity", logOnFailure = false)
    val actionBarClass = loadClass("org.telegram.ui.ActionBar.ActionBar", logOnFailure = false)
    if (dialogsActivityClass == null || actionBarClass == null) {
        replaceObfuscatedAppTitle(mode, customText)
        return
    }
    val trackedBars: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    var cachedTitle: String? = null

    XposedBridge.hookAllMethods(dialogsActivityClass, "createView", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val actionBar = runCatching {
                XposedHelpers.getObjectField(param.thisObject, "actionBar")
            }.getOrNull()
            if (actionBar == null) {
                log("replaceAppTitle: actionBar field not found", "ERROR")
                return
            }

            trackedBars.add(actionBar)
            cachedTitle = resolveTitle(mode, customText, lpparam)
            applyTitle(actionBar, cachedTitle)
        }
    })

    XposedBridge.hookAllMethods(actionBarClass, "setTitle", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.thisObject !in trackedBars) return
            val incoming = param.args.getOrNull(0)?.toString() ?: return
            val replacement = cachedTitle ?: return
            if (incoming == replacement) return
            param.args[0] = replacement
        }
    })

    if (centerTitle) {
        XposedBridge.hookAllMethods(actionBarClass, "onLayout", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject !in trackedBars) return
                val barWidth = (param.thisObject as View).width
                if (barWidth <= 0) return
                centerWithTranslation(param.thisObject, "titleTextView", 0, barWidth)
                centerWithTranslation(param.thisObject, "titleTextView", 1, barWidth)
                centerFieldWithTranslation(param.thisObject, "subtitleTextView", barWidth)
            }
        })
    }
}

private fun applyTitle(actionBar: Any, replacement: String?) {
    if (replacement == null) {
        log("replaceAppTitle: resolveTitle returned null", "ERROR")
        return
    }

    log("replaceAppTitle: applying title '$replacement'")

    runCatching {
        val titleTextViewArray = XposedHelpers.getObjectField(actionBar, "titleTextView")
        val tv = java.lang.reflect.Array.get(titleTextViewArray, 0)
        if (tv != null) {
            XposedHelpers.callMethod(tv, "setText", replacement as CharSequence)
        }
    }.onFailure {
        log("replaceAppTitle: failed to set text on titleTextView: ${it.message}", "ERROR")
    }

    runCatching {
        XposedHelpers.setObjectField(actionBar, "lastTitle", replacement as CharSequence)
    }.onFailure {
        log("replaceAppTitle: failed to set lastTitle: ${it.message}", "ERROR")
    }
}

private fun resolveTitle(mode: String, customText: String, lpparam: LoadPackageParam): String? {
    return when (mode) {
        "custom" -> customText.ifBlank { null }
        else -> runCatching {
            val user = TgDeobf.currentUser(lpparam) ?: error("current user unknown")
            XposedHelpers.getObjectField(user, "first_name") as? String
        }.getOrElse {
            log("replaceAppTitle: failed to get first name: ${it.message}", "ERROR")
            null
        }
    }
}

private fun centerWithTranslation(actionBar: Any, fieldName: String, index: Int, barWidth: Int) {
    val array = runCatching {
        XposedHelpers.getObjectField(actionBar, fieldName)
    }.getOrNull() ?: return
    val view = runCatching {
        java.lang.reflect.Array.get(array, index) as? View
    }.getOrNull() ?: return
    applyCenterTranslation(view, barWidth)
}

private fun centerFieldWithTranslation(actionBar: Any, fieldName: String, barWidth: Int) {
    val view = runCatching {
        XposedHelpers.getObjectField(actionBar, fieldName) as? View
    }.getOrNull() ?: return
    applyCenterTranslation(view, barWidth)
}

private fun applyCenterTranslation(view: View, barWidth: Int) {
    if (view.visibility != View.VISIBLE || view.width <= 0) return

    val textWidth = runCatching {
        XposedHelpers.getIntField(view, "textWidth")
    }.getOrDefault(view.width)

    val drawablePadding = runCatching {
        XposedHelpers.getIntField(view, "drawablePadding")
    }.getOrDefault(0)
    val rightDrawable = runCatching {
        XposedHelpers.getObjectField(view, "rightDrawable") as? android.graphics.drawable.Drawable
    }.getOrNull()
    val rightDrawableWidth = if (rightDrawable != null) {
        drawablePadding + rightDrawable.intrinsicWidth
    } else 0

    val totalWidth = textWidth + rightDrawableWidth
    val textCurrentX = view.left
    val textCenteredX = (barWidth - totalWidth) / 2
    view.translationX = (textCenteredX - textCurrentX).toFloat()
}

/**
 * Telegram 12.10.2+: the main chat list draws its title as the Telegram logo next to the
 * emoji status, set once in DialogsActivity.createView through ActionBar's
 * (CharSequence, emoji status drawable) title setter. Call that setter again with the
 * replacement, keeping the emoji status.
 */
private fun Hooks.replaceObfuscatedAppTitle(mode: String, customText: String) {
    val createView = TgDeobf.dialogsCreateView(lpparam) ?: return

    XposedBridge.hookMethod(createView, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                val fragment = param.thisObject ?: return
                if (!isMainChatList(fragment)) return
                val title = resolveTitle(mode, customText, lpparam) ?: return
                val actionBar = XposedHelpers.getObjectField(fragment, "actionBar") ?: return

                val setter = generateSequence<Class<*>>(actionBar.javaClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .firstOrNull { method ->
                        val params = method.parameterTypes
                        params.size == 2 && params[0] == CharSequence::class.java &&
                            Drawable::class.java.isAssignableFrom(params[1])
                    }
                    ?.apply { isAccessible = true }
                if (setter == null) {
                    XposedHelpers.callMethod(actionBar, "setTitle", title as CharSequence)
                    return
                }
                // Keep the emoji status the fragment created for the title.
                val emojiStatus = generateSequence<Class<*>>(fragment.javaClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filter { setter.parameterTypes[1].isAssignableFrom(it.type) }
                    .mapNotNull { it.apply { isAccessible = true }.get(fragment) }
                    .firstOrNull()
                setter.invoke(actionBar, title as CharSequence, emojiStatus)
            }.onFailure {
                log("replaceAppTitle: failed to set title: ${it.message}", "ERROR")
            }
        }
    })
}
