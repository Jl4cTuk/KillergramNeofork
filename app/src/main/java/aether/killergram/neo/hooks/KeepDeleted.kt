package aether.killergram.neo.hooks

import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodReplacement
import aether.killergram.neo.xposed.XposedBridge

fun Hooks.keepDeletedMessages() {
    log("Forcing TG to keep deleted messages...")

    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val messagesStorageClass = loadClass("org.telegram.messenger.MessagesStorage") ?: return

    XposedBridge.hookAllMethods(
        messagesStorageClass,
        "markMessagesAsDeleted",
        XC_MethodReplacement.returnConstant(null)
    )
    XposedBridge.hookAllMethods(
        messagesStorageClass,
        "markMessagesAsDeletedInternal",
        XC_MethodReplacement.returnConstant(null)
    )
    XposedBridge.hookAllMethods(
        messagesControllerClass,
        "markDialogMessageAsDeleted",
        XC_MethodReplacement.returnConstant(null)
    )
    XposedBridge.hookAllMethods(
        messagesControllerClass,
        "deleteMessages",
        XC_MethodReplacement.returnConstant(null)
    )
}