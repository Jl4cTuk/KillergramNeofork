package aether.killergram.neo.hooks

import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers

fun Hooks.hidePostShareButton() {
    log("Hiding share button for channel posts and reposts in groups...")

    val chatMessageCellClass = loadClass("org.telegram.ui.Cells.ChatMessageCell", logOnFailure = false)
    if (chatMessageCellClass == null) {
        // Telegram 12.10.2+: ChatMessageCell.checkNeedDrawShareButton is obfuscated, but it
        // ends in MessageObject.needDrawShareButton(), which only it asks. Since 13.2 that
        // is obfuscated too: the no-arg boolean that names the review bots.
        val messageObjectClass = loadClass("org.telegram.messenger.MessageObject") ?: return
        val messageClass = loadClass("org.telegram.tgnet.TLRPC\$Message") ?: return
        messageOwnerField = messageObjectClass.declaredFields.singleOrNull {
            it.type == messageClass && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
        hook(
            method(messageObjectClass, "needDrawShareButton", 0) {
                returnType("boolean")
                paramCount(0)
                usingStrings("reviews_bot", "ReviewInsightsBot")
            },
            "MessageObject.needDrawShareButton",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == true && shouldHideShareForMessage(param.thisObject)) {
                        param.result = false
                    }
                }
            }
        )
        return
    }

    XposedBridge.hookAllMethods(
        chatMessageCellClass,
        "checkNeedDrawShareButton",
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val shouldDraw = (param.result as? Boolean) ?: return
                if (!shouldDraw) {
                    return
                }

                val messageObject = param.args.getOrNull(0) ?: return
                if (shouldHideShareForMessage(messageObject)) {
                    param.result = false
                }
            }
        }
    )
}

// MessageObject.messageOwner on obfuscated builds: its only TLRPC.Message field.
private var messageOwnerField: java.lang.reflect.Field? = null

private fun shouldHideShareForMessage(messageObject: Any): Boolean {
    val messageOwner = runCatching {
        messageOwnerField?.get(messageObject) ?: XposedHelpers.getObjectField(messageObject, "messageOwner")
    }.getOrNull() ?: return false

    val fromId = runCatching {
        XposedHelpers.getObjectField(messageOwner, "from_id")
    }.getOrNull()
    val isChannelPost = runCatching {
        XposedHelpers.getBooleanField(messageOwner, "post")
    }.getOrDefault(false) || isPeerChannel(fromId)

    val forwardHeader = runCatching {
        XposedHelpers.getObjectField(messageOwner, "fwd_from")
    }.getOrNull()
    val forwardFromId = runCatching {
        forwardHeader?.let { XposedHelpers.getObjectField(it, "from_id") }
    }.getOrNull()
    val isChannelRepost = isPeerChannel(forwardFromId)
    // MessageObject.isSupergroup() (obfuscated since 13.2): a message in a channel peer
    // that is not a channel post.
    val isGroupChat = runCatching {
        XposedHelpers.callMethod(messageObject, "isSupergroup") as? Boolean
    }.getOrNull() ?: runCatching {
        isPeerChannel(XposedHelpers.getObjectField(messageOwner, "peer_id")) &&
            !XposedHelpers.getBooleanField(messageOwner, "post")
    }.getOrDefault(false)

    return isChannelPost || (isChannelRepost && isGroupChat)
}

private fun isPeerChannel(peer: Any?): Boolean {
    return peer?.javaClass?.name?.endsWith("TL_peerChannel") == true
}
