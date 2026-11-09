package aether.killergram.neo.hooks

import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodReplacement

fun Hooks.killSponsoredMessages() {
    log("Killing sponsored messages...")

    // Sponsored messages are neutralised at the controller level: the UI reads them
    // from MessagesController and renders nothing on null.
    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return

    // getSponsoredMessages(dialogId): never hand any sponsored payload to the UI. The
    // (long) method that reuses a cached result for five minutes.
    hook(
        method(messagesControllerClass, "getSponsoredMessages", 1) {
            paramTypes("long")
            invokeMethods {
                add {
                    declaredClass("android.os.SystemClock")
                    name("elapsedRealtime")
                }
            }
            usingNumbers(300000L)
        },
        "MessagesController.getSponsoredMessages",
        XC_MethodReplacement.returnConstant(null)
    )

    // isSponsoredDisabled(): make Telegram believe sponsored messages are disabled
    // account-wide, so the fetch path is skipped before it even starts.
    hook(
        method(messagesControllerClass, "isSponsoredDisabled", 0) {
            returnType("boolean")
            paramCount(0)
            addUsingField(
                "Lorg/telegram/tgnet/TLRPC\$UserFull;->sponsored_enabled:Z",
                org.luckypray.dexkit.query.enums.UsingType.Read
            )
        },
        "MessagesController.isSponsoredDisabled",
        XC_MethodReplacement.returnConstant(true)
    )
}
