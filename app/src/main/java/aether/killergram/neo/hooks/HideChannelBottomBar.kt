package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun Hooks.hideChannelBottomBar() {
    log("Hiding channel bottom action bar...")

    val chatObjectClass = loadClass("org.telegram.messenger.ChatObject")
    val chatActivityClass = loadClass("org.telegram.ui.ChatActivity", logOnFailure = false)
    if (chatActivityClass == null) {
        hideObfuscatedChannelBottomBar(chatObjectClass)
        return
    }
    val androidUtilitiesClass = loadClass("org.telegram.messenger.AndroidUtilities")

    XposedBridge.hookAllMethods(
        chatActivityClass,
        "updateBottomOverlay",
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                applyBottomBarState(param.thisObject, chatObjectClass)
            }
        }
    )

    // Telegram 12.4+ enforces minimum bubble height from calculateInputIslandHeight().
    XposedBridge.hookAllMethods(
        chatActivityClass,
        "calculateInputIslandHeight",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (shouldHideForThisChat(param.thisObject, chatObjectClass)) {
                    param.result = 0f
                }
            }
        }
    )

    // Some builds re-apply bubble height/alpha after overlay updates.
    XposedBridge.hookAllMethods(
        chatActivityClass,
        "checkUi_inputIslandHeight",
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                applyBottomBarState(param.thisObject, chatObjectClass)
            }
        }
    )
    XposedBridge.hookAllMethods(
        chatActivityClass,
        "onBottomItemsVisibilityChanged",
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                applyBottomBarState(param.thisObject, chatObjectClass)
            }
        }
    )

    // Telegram 12.1.x keeps floating controls (page-down/mentions) anchored above the old
    // bottom bar. Move them into freed space after base positioning logic runs.
    XposedBridge.hookAllMethods(
        chatActivityClass,
        "updatePagedownButtonsPosition",
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!shouldHideForThisChat(param.thisObject, chatObjectClass)) {
                    return
                }
                shiftLegacyFloatingControls(param.thisObject, androidUtilitiesClass)
            }
        }
    )

    // Older builds may not use calculateInputIslandHeight(), but still compute list padding
    // from cached inputIslandHeight* fields.
    XposedBridge.hookAllMethods(
        chatActivityClass,
        "checkUi_chatListViewPaddings",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!shouldHideForThisChat(param.thisObject, chatObjectClass)) {
                    return
                }
                runCatching {
                    XposedHelpers.setFloatField(param.thisObject, "inputIslandHeightCurrent", 0f)
                    XposedHelpers.setFloatField(param.thisObject, "inputIslandHeightTarget", 0f)
                    collapseLegacyBottomOffset(param.thisObject, androidUtilitiesClass)
                }
            }
        }
    )

    // Telegram 12.1.x path: bottom space is driven by blurredViewBottomOffset in ChatActivityFragmentView.
    val fragmentViewClass = loadClass("org.telegram.ui.ChatActivity\$ChatActivityFragmentView")
    if (fragmentViewClass != null) {
        XposedBridge.hookAllMethods(
            fragmentViewClass,
            "onMeasure",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val chatActivity = runCatching {
                        XposedHelpers.getObjectField(param.thisObject, "this$0")
                    }.getOrNull() ?: return

                    if (!shouldHideForThisChat(chatActivity, chatObjectClass)) {
                        return
                    }

                    runCatching {
                        collapseLegacyBottomOffset(chatActivity, androidUtilitiesClass)
                    }
                }
            }
        )
    }
}

/*
 * Telegram 12.10.2+: ChatActivity's members are obfuscated. The bottom bar of a channel
 * one cannot post in is the "bottom overlay" view (the one with a getContainer() that
 * holds the mute and gift buttons) inside the input island; hiding it and collapsing the
 * island (calculateInputIslandHeight -> 0) gives the space back to the message list. The
 * input island container keeps its setter names.
 */
private fun Hooks.hideObfuscatedChannelBottomBar(chatObjectClass: Class<*>?) {
    val chatClass = TgDeobf.chatActivityClass(lpparam) ?: return
    val bar = TgDeobf.chatBottomBar(lpparam, chatClass) ?: return
    val inputContainerField = chatClass.declaredFields.firstOrNull { field ->
        field.type.declaredMethods.any { it.name == "setInputBubbleHeight" }
    }?.apply { isAccessible = true }
    val overlayFields = chatClass.declaredFields.filter { field ->
        View::class.java.isAssignableFrom(field.type) &&
            generateSequence(field.type as Class<*>) { it.superclass }
                .any { type -> type.declaredMethods.any { it.name == "getContainer" && it.parameterTypes.isEmpty() } }
    }.onEach { it.isAccessible = true }
    if (inputContainerField == null || overlayFields.isEmpty()) {
        log("Channel bottom bar views not found (input=${inputContainerField != null}, overlays=${overlayFields.size})", "ERROR")
    }

    fun shouldHide(chatActivity: Any): Boolean =
        isReadOnlyChannel(runCatching { bar.currentChat.get(chatActivity) }.getOrNull(), chatObjectClass)

    XposedBridge.hookMethod(bar.updateBottomOverlay, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val chatActivity = param.thisObject ?: return
            if (!shouldHide(chatActivity)) return
            overlayFields.forEach { field ->
                val overlay = runCatching { field.get(chatActivity) }.getOrNull() ?: return@forEach
                (overlay as? View)?.visibility = View.GONE
                runCatching { (XposedHelpers.callMethod(overlay, "getContainer") as? View)?.visibility = View.GONE }
            }
            val container = inputContainerField?.get(chatActivity) ?: return
            runCatching {
                XposedHelpers.callMethod(container, "setInputBubbleHeight", 0f)
                XposedHelpers.callMethod(container, "setInputBubbleAlpha", 0)
                XposedHelpers.callMethod(container, "setInputBubbleTranslationY", 0f)
                XposedHelpers.callMethod(container, "setBlurredBottomHeight", 0f)
                (XposedHelpers.callMethod(container, "getFadeView") as? View)?.apply {
                    visibility = View.GONE
                    alpha = 0f
                }
            }.onFailure {
                log("Failed to collapse channel input island: ${it.message}", "DEBUG")
            }
        }
    })

    bar.inputIslandHeight?.let { islandHeight ->
        XposedBridge.hookMethod(islandHeight, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val chatActivity = param.thisObject ?: return
                if (shouldHide(chatActivity)) param.result = 0f
            }
        })
    } ?: log("calculateInputIslandHeight not found — channel bottom space stays", "ERROR")
}

private fun shouldHideForThisChat(chatActivityInstance: Any, chatObjectClass: Class<*>?): Boolean {
    val currentChat = runCatching {
        XposedHelpers.getObjectField(chatActivityInstance, "currentChat")
    }.getOrNull() ?: return false
    return isReadOnlyChannel(currentChat, chatObjectClass)
}

/** A broadcast channel (not a megagroup) one cannot post in. */
private fun isReadOnlyChannel(currentChat: Any?, chatObjectClass: Class<*>?): Boolean {
    currentChat ?: return false

    val isChannelAndNotMegagroup = when {
        chatObjectClass != null -> runCatching {
            XposedHelpers.callStaticMethod(
                chatObjectClass,
                "isChannelAndNotMegaGroup",
                currentChat
            ) as Boolean
        }.getOrDefault(false)

        else -> runCatching {
            !XposedHelpers.getBooleanField(currentChat, "megagroup")
        }.getOrDefault(false)
    }

    if (!isChannelAndNotMegagroup) {
        return false
    }

    // Do not collapse input island for channel admins who can post.
    val canPost = runCatching {
        chatObjectClass?.let {
            XposedHelpers.callStaticMethod(it, "canPost", currentChat) as Boolean
        } ?: false
    }.getOrDefault(false)

    return !canPost
}

private fun applyBottomBarState(chatActivityInstance: Any, chatObjectClass: Class<*>?) {
    val shouldHide = shouldHideForThisChat(chatActivityInstance, chatObjectClass)

    if (shouldHide) {
        hideViewField(chatActivityInstance, "bottomOverlayChat")
        hideViewField(chatActivityInstance, "bottomChannelButtonsLayout")
        hideViewField(chatActivityInstance, "bottomGiftButton")
        hideViewField(chatActivityInstance, "bottomOverlay")
    }

    runCatching {
        val inputContainer = XposedHelpers.getObjectField(chatActivityInstance, "chatInputViewsContainer")
        (inputContainer as? View)?.visibility = View.VISIBLE

        if (shouldHide) {
            runCatching {
                XposedHelpers.setFloatField(chatActivityInstance, "inputIslandHeightCurrent", 0f)
                XposedHelpers.setFloatField(chatActivityInstance, "inputIslandHeightTarget", 0f)
            }
            XposedHelpers.callMethod(inputContainer, "setInputBubbleHeight", 0f)
            XposedHelpers.callMethod(inputContainer, "setInputBubbleAlpha", 0)
            XposedHelpers.callMethod(inputContainer, "setInputBubbleTranslationY", 0f)
            XposedHelpers.callMethod(inputContainer, "setBlurredBottomHeight", 0f)
        } else {
            XposedHelpers.callMethod(inputContainer, "setInputBubbleAlpha", 255)
        }

        val fadeView = runCatching {
            XposedHelpers.callMethod(inputContainer, "getFadeView") as? View
        }.getOrNull()
        fadeView?.visibility = if (shouldHide) View.GONE else View.VISIBLE
        fadeView?.alpha = if (shouldHide) 0f else 1f
    }.onFailure {
        log("Failed to apply channel bottom bar state: ${it.message}", "DEBUG")
    }
}

private fun collapseLegacyBottomOffset(chatActivityInstance: Any, androidUtilitiesClass: Class<*>?) {
    if (androidUtilitiesClass == null) {
        return
    }

    runCatching {
        val current = XposedHelpers.getIntField(chatActivityInstance, "blurredViewBottomOffset")
        if (current <= 0) {
            return
        }

        // 12.1.x keeps ~51dp for channel bottom overlay. Remove only that reserved panel height.
        val panelHeight = (XposedHelpers.callStaticMethod(androidUtilitiesClass, "dp", 51f) as? Int) ?: 0
        if (panelHeight <= 0) {
            return
        }

        val adjusted = (current - panelHeight).coerceAtLeast(0)
        XposedHelpers.setIntField(chatActivityInstance, "blurredViewBottomOffset", adjusted)
    }
}

private fun shiftLegacyFloatingControls(chatActivityInstance: Any, androidUtilitiesClass: Class<*>?) {
    if (androidUtilitiesClass == null) {
        return
    }

    val offset = runCatching {
        XposedHelpers.callStaticMethod(androidUtilitiesClass, "dp", 51f) as? Int
    }.getOrNull() ?: return

    if (offset <= 0) {
        return
    }

    val fields = listOf(
        "pagedownButton",
        "searchUpButton",
        "searchDownButton",
        "mentiondownButton",
        "reactionsMentiondownButton"
    )
    fields.forEach { fieldName ->
        runCatching {
            val view = XposedHelpers.getObjectField(chatActivityInstance, fieldName) as? View ?: return@forEach
            view.translationY = view.translationY + offset
        }
    }
}

private fun hideViewField(instance: Any, fieldName: String) {
    runCatching {
        val fieldValue = XposedHelpers.getObjectField(instance, fieldName)
        val directView = fieldValue as? View
        directView?.visibility = View.GONE

        // Telegram 12.4+ wraps channel buttons in an internal container.
        val containerView = runCatching {
            XposedHelpers.callMethod(fieldValue, "getContainer") as? View
        }.getOrNull()
        containerView?.visibility = View.GONE
    }.onFailure {
        if (it.message?.contains("NoSuchFieldError") != true &&
            it.message?.contains("NoSuchFieldException") != true) {
            log("Failed to hide $fieldName: ${it.message}", "DEBUG")
        }
    }
}
