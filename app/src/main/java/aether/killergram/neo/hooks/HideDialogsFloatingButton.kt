package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun Hooks.hideDialogsFloatingButton() {
    log("Hiding dialogs floating action button...")

    val dialogsActivityClass = loadClass("org.telegram.ui.DialogsActivity", logOnFailure = false)
    if (dialogsActivityClass == null) {
        hideObfuscatedDialogsFloatingButtons()
        return
    }

    val enforceHiddenStateHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            forceHideDialogsFloatingButtons(param.thisObject)
        }
    }

    XposedBridge.hookAllMethods(dialogsActivityClass, "createView", enforceHiddenStateHook)
    XposedBridge.hookAllMethods(dialogsActivityClass, "updateFloatingButtonVisibility", enforceHiddenStateHook)
    XposedBridge.hookAllMethods(dialogsActivityClass, "updateFloatingButtonOffset", enforceHiddenStateHook)
    XposedBridge.hookAllMethods(dialogsActivityClass, "updateStoriesPosting", enforceHiddenStateHook)
    XposedBridge.hookAllMethods(dialogsActivityClass, "hideFloatingButton", enforceHiddenStateHook)
    XposedBridge.hookAllMethods(dialogsActivityClass, "checkUi_floatingButton", enforceHiddenStateHook)
}

private fun forceHideDialogsFloatingButtons(dialogsActivity: Any?) {
    if (dialogsActivity == null) {
        return
    }
    if (!isDefaultDialogsScreen(dialogsActivity)) {
        return
    }

    // Telegram 12.4+
    hideFloatingButtonField(dialogsActivity, "floatingButton3")
    hideFloatingButtonField(dialogsActivity, "floatingButtonStories")

    // Telegram 12.1.x and earlier branch
    hideFloatingButtonField(dialogsActivity, "floatingButtonContainer")
    hideFloatingButtonField(dialogsActivity, "floatingButton2Container")
    hideFloatingButtonField(dialogsActivity, "floatingButton")
    hideFloatingButtonField(dialogsActivity, "floatingButton2")

    runCatching {
        val storyHint = XposedHelpers.getObjectField(dialogsActivity, "storyHint") as? View
        storyHint?.visibility = View.GONE
        storyHint?.alpha = 0f
    }
    runCatching {
        val storyPremiumHint = XposedHelpers.getObjectField(dialogsActivity, "storyPremiumHint") as? View
        storyPremiumHint?.visibility = View.GONE
        storyPremiumHint?.alpha = 0f
    }
}

private fun isDefaultDialogsScreen(dialogsActivity: Any): Boolean {
    val dialogsTypeDefault = runCatching {
        XposedHelpers.getStaticIntField(dialogsActivity.javaClass, "DIALOGS_TYPE_DEFAULT")
    }.getOrDefault(0)

    val initialDialogsType = runCatching {
        XposedHelpers.getIntField(dialogsActivity, "initialDialogsType")
    }.getOrNull() ?: return false

    return initialDialogsType == dialogsTypeDefault
}

private fun hideFloatingButtonField(instance: Any, fieldName: String) {
    val fieldValue = runCatching {
        XposedHelpers.getObjectField(instance, fieldName)
    }.getOrNull() ?: return

    runCatching {
        XposedHelpers.callMethod(fieldValue, "setButtonVisible", false, false)
    }

    val view = fieldValue as? View ?: return
    view.visibility = View.GONE
    view.alpha = 0f
    view.isClickable = false
    view.isEnabled = false
}

/**
 * Telegram 12.10.2+: DialogsActivity and its fields are obfuscated. Its createView is
 * resolved by fingerprint; afterwards the compose and story floating buttons (one
 * floating-button class) are removed from the layout on the main chat list. Telegram keeps
 * animating them through its own references, but detached views are never shown again.
 */
private fun Hooks.hideObfuscatedDialogsFloatingButtons() {
    val createView = TgDeobf.dialogsCreateView(lpparam) ?: return
    val storyButtonLabel = telegramString("StoryPrivacyButtonPost")

    XposedBridge.hookMethod(createView, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                val fragment = param.thisObject ?: return
                if (!isMainChatList(fragment)) return
                removeFloatingButtons(fragment, storyButtonLabel?.invoke())
            }.onFailure {
                log("Failed to hide chat list floating buttons: ${it.message}", "ERROR")
            }
        }
    })
}

/** The main chat list is the instance created by the bottom tabs screen. */
internal fun isMainChatList(fragment: Any): Boolean {
    val arguments = runCatching {
        XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
    }.getOrNull()
    return arguments?.getBoolean("hasMainTabs", false) == true
}

private fun removeFloatingButtons(fragment: Any, storyButtonLabel: String?) {
    val views = generateSequence<Class<*>>(fragment.javaClass) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .filter { View::class.java.isAssignableFrom(it.type) }
        .mapNotNull { field -> field.apply { isAccessible = true }.get(fragment) as? View }
        .toList()

    // The story camera button carries a known label; its class is the floating-button class.
    val buttonClass = views.firstOrNull { view ->
        storyButtonLabel != null && view.contentDescription?.toString() == storyButtonLabel
    }?.javaClass ?: run {
        log("Chat list floating button class not found", "ERROR")
        return
    }

    views.filter { it.javaClass == buttonClass }.forEach { button ->
        (button.parent as? ViewGroup)?.removeView(button)
    }
}

/** Resolves a Telegram string by resource name through the kept LocaleController. */
private fun Hooks.telegramString(name: String): (() -> String?)? {
    val localeController = loadClass("org.telegram.messenger.LocaleController") ?: return null
    val id = runCatching {
        XposedHelpers.getStaticIntField(
            XposedHelpers.findClass("org.telegram.messenger.R\$string", lpparam.classLoader),
            name
        )
    }.getOrNull() ?: return null
    return {
        runCatching {
            XposedHelpers.callStaticMethod(localeController, "getString", id) as? String
        }.getOrNull()
    }
}
