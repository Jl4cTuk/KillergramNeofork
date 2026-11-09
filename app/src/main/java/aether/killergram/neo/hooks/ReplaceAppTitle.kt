package aether.killergram.neo.hooks

import aether.killergram.neo.core.Features
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

/**
 * The parameters are read when the title is set, and a change in the module app applies to
 * the chat list on screen right away; turning the feature off puts Telegram's title back.
 */
fun Hooks.replaceAppTitle(mode: () -> String, customText: () -> String, centerTitle: () -> Boolean) {
    log("Setting up app title replacement (mode=${mode()}, center=${centerTitle()})...")

    val dialogsActivityClass = loadClass("org.telegram.ui.DialogsActivity", logOnFailure = false)
    val actionBarClass = loadClass("org.telegram.ui.ActionBar.ActionBar", logOnFailure = false)
    if (dialogsActivityClass == null || actionBarClass == null) {
        replaceObfuscatedAppTitle(mode, customText)
        return
    }
    val trackedBars: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    // Telegram's own title per action bar.
    val originalTitles = Collections.synchronizedMap(WeakHashMap<Any, CharSequence>())
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
            runCatching { XposedHelpers.getObjectField(actionBar, "lastTitle") as? CharSequence }
                .getOrNull()?.let { originalTitles[actionBar] = it }
            cachedTitle = resolveTitle(mode(), customText(), lpparam)
            applyTitle(actionBar, cachedTitle)
        }
    })

    XposedBridge.hookAllMethods(actionBarClass, "setTitle", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.thisObject !in trackedBars) return
            val incoming = param.args.getOrNull(0) as? CharSequence ?: return
            val replacement = cachedTitle ?: return
            if (incoming.toString() == replacement) return
            originalTitles[param.thisObject] = incoming
            param.args[0] = replacement
        }
    })

    XposedBridge.hookAllMethods(actionBarClass, "onLayout", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.thisObject !in trackedBars || !centerTitle()) return
            val barWidth = (param.thisObject as View).width
            if (barWidth <= 0) return
            centerWithTranslation(param.thisObject, "titleTextView", 0, barWidth)
            centerWithTranslation(param.thisObject, "titleTextView", 1, barWidth)
            centerFieldWithTranslation(param.thisObject, "subtitleTextView", barWidth)
        }
    })

    val feature = Features.current
    Features.onChange {
        val on = feature?.isOn ?: return@onChange
        cachedTitle = if (on) resolveTitle(mode(), customText(), lpparam) else null
        trackedBars.toList().forEach { actionBar ->
            if (on) {
                applyTitle(actionBar, cachedTitle)
            } else {
                originalTitles[actionBar]?.let { title ->
                    runCatching { XposedHelpers.callMethod(actionBar, "setTitle", title) }
                }
            }
            if (!on || !centerTitle()) resetCentering(actionBar)
            (actionBar as? View)?.requestLayout()
        }
    }
}

private fun resetCentering(actionBar: Any) {
    runCatching {
        val titles = XposedHelpers.getObjectField(actionBar, "titleTextView")
        for (index in 0 until java.lang.reflect.Array.getLength(titles)) {
            (java.lang.reflect.Array.get(titles, index) as? View)?.translationX = 0f
        }
    }
    runCatching { (XposedHelpers.getObjectField(actionBar, "subtitleTextView") as? View)?.translationX = 0f }
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
private fun Hooks.replaceObfuscatedAppTitle(mode: () -> String, customText: () -> String) {
    val createView = TgDeobf.dialogsCreateView(lpparam) ?: return
    val fragments: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    val title = ObfuscatedTitle()

    XposedBridge.hookMethod(createView, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                val fragment = param.thisObject ?: return
                if (!isMainChatList(fragment)) return
                fragments.add(fragment)
                val replacement = resolveTitle(mode(), customText(), lpparam) ?: return
                title.set(fragment, replacement)
            }.onFailure {
                log("replaceAppTitle: failed to set title: ${it.message}", "ERROR")
            }
        }
    })

    val feature = Features.current
    Features.onChange {
        val on = feature?.isOn ?: return@onChange
        val replacement = if (on) resolveTitle(mode(), customText(), lpparam) else null
        log("replaceAppTitle: ${fragments.size} chat list(s), title ${replacement ?: "restored"}", "DEBUG")
        fragments.toList().forEach { fragment ->
            runCatching {
                if (replacement != null) title.set(fragment, replacement) else title.restore(fragment)
            }.onFailure {
                log("replaceAppTitle: failed to update title: ${it.message}", "ERROR")
            }
        }
    }
}

/**
 * The main chat list's ActionBar (CharSequence, emoji status drawable) title setter.
 * Telegram's own title is recorded as it passes through the setter, so it can be put back.
 */
private class ObfuscatedTitle {
    private var setter: java.lang.reflect.Method? = null
    private val originals = Collections.synchronizedMap(WeakHashMap<Any, CharSequence>())
    private val ours = Collections.synchronizedMap(WeakHashMap<Any, CharSequence>())

    fun set(fragment: Any, replacement: String) {
        val actionBar = XposedHelpers.getObjectField(fragment, "actionBar") ?: return
        val setter = setter(actionBar)
        if (setter == null) {
            XposedHelpers.callMethod(actionBar, "setTitle", replacement as CharSequence)
            return
        }
        // The title createView set before this runs (createView can run again with a new
        // action bar). Its text keeps the spans (the Telegram logo), unlike
        // ActionBar.getTitle(), which returns a String.
        if (actionBar !in ours) {
            runCatching { currentTitle(actionBar) }
                .onFailure { log("replaceAppTitle: title text unreadable: ${it.message}", "DEBUG") }
                .getOrNull()?.let {
                    log("replaceAppTitle: Telegram's title '$it'", "DEBUG")
                    originals[actionBar] = it
                }
        }
        ours[actionBar] = replacement
        // Not through the hook: that would take ours for Telegram's title.
        XposedBridge.invokeOriginalMethod(setter, actionBar, arrayOf(replacement, emojiStatus(fragment, setter)))
    }

    fun restore(fragment: Any) {
        val actionBar = XposedHelpers.getObjectField(fragment, "actionBar") ?: return
        val setter = setter ?: return log("replaceAppTitle: no title setter to restore with", "DEBUG")
        val original = originals[actionBar] ?: return log("replaceAppTitle: Telegram's title unknown", "DEBUG")
        log("replaceAppTitle: restoring '$original'", "DEBUG")
        ours.remove(actionBar)
        XposedBridge.invokeOriginalMethod(setter, actionBar, arrayOf(original, emojiStatus(fragment, setter)))
    }

    private fun setter(actionBar: Any): java.lang.reflect.Method? {
        setter?.let { return it }
        val found = generateSequence<Class<*>>(actionBar.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                val params = method.parameterTypes
                params.size == 2 && params[0] == CharSequence::class.java &&
                    Drawable::class.java.isAssignableFrom(params[1])
            }
            ?.apply { isAccessible = true }
            ?: return null
        setter = found
        // Telegram sets the title in createView before this runs, and again on some
        // updates: keep the replacement and remember the title Telegram wanted.
        XposedBridge.hookMethod(found, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val incoming = param.args[0] as? CharSequence ?: return
                val replacement = ours[param.thisObject] ?: return
                originals[param.thisObject] = incoming
                param.args[0] = replacement
            }
        })
        return found
    }

    /** The text of the first title view (ActionBar's array of SimpleTextViews). */
    private fun currentTitle(actionBar: Any): CharSequence? {
        val titles = generateSequence<Class<*>>(actionBar.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { field ->
                // Not the plain View[] of menu items: the title views have getText().
                val type = field.type.componentType ?: return@firstOrNull false
                View::class.java.isAssignableFrom(type) && type != View::class.java &&
                    runCatching { CharSequence::class.java.isAssignableFrom(type.getMethod("getText").returnType) }
                        .getOrDefault(false)
            }
            ?.apply { isAccessible = true }
            ?.get(actionBar) as? Array<*> ?: return null
        val view = titles.firstOrNull() ?: return null
        return XposedHelpers.callMethod(view, "getText") as? CharSequence
    }

    /** Keeps the emoji status the fragment created for the title. */
    private fun emojiStatus(fragment: Any, setter: java.lang.reflect.Method): Any? =
        generateSequence<Class<*>>(fragment.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { setter.parameterTypes[1].isAssignableFrom(it.type) }
            .mapNotNull { it.apply { isAccessible = true }.get(fragment) }
            .firstOrNull()
}
