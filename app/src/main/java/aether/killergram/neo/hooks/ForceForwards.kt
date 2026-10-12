package aether.killergram.neo.hooks

import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

fun Hooks.forceAllowForwards() {
    log("Enabling forwarding anywhere...")

    // Data-level gates live in org.telegram.messenger.* (R8 keeps these names),
    // so forwarding is enabled regardless of ui.* obfuscation.
    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val messageObjectClass = loadClass("org.telegram.messenger.MessageObject") ?: return

    XposedBridge.hookAllMethods(
        messagesControllerClass,
        "isChatNoForwards",
        XC_MethodReplacement.returnConstant(false)
    )
    XposedBridge.hookAllMethods(
        messageObjectClass,
        "canForwardMessage",
        XC_MethodReplacement.returnConstant(true)
    )

    // ChatActivity.hasSelectedNoforwardsMessage is UI-only (controls the share button
    // state for a selection) and lives in the obfuscated org.telegram.ui package.
    // The data-level gates above already allow forwarding, so we skip it here; it can be
    // reinstated via the deobfuscator later if the selection UI needs it.
}
