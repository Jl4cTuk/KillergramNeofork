package aether.killergram.neo.hooks

import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

fun Hooks.killSponsoredMessages() {
    log("Killing sponsored messages...")

    // Telegram 12.10.2+ R8-obfuscates org.telegram.ui.* (ChatActivity -> e.g. "a0"),
    // but keeps org.telegram.messenger.* class AND member names. So we neutralise
    // sponsored messages at the controller level, which is obfuscation-proof and
    // enough on its own: the UI reads them from here and renders nothing on null.
    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return

    // Never hand any sponsored payload to the UI.
    XposedBridge.hookAllMethods(
        messagesControllerClass,
        "getSponsoredMessages",
        XC_MethodReplacement.returnConstant(null)
    )

    // Belt-and-suspenders: make Telegram believe sponsored messages are disabled
    // account-wide, so the fetch path is skipped before it even starts.
    XposedBridge.hookAllMethods(
        messagesControllerClass,
        "isSponsoredDisabled",
        XC_MethodReplacement.returnConstant(true)
    )

    log("Sponsored messages killed at controller level.")
}
