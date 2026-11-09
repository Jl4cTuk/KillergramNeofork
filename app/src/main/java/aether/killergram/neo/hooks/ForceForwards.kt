package aether.killergram.neo.hooks

import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodReplacement
import org.luckypray.dexkit.query.enums.UsingType

fun Hooks.forceAllowForwards() {
    log("Enabling forwarding anywhere...")

    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val messageObjectClass = loadClass("org.telegram.messenger.MessageObject") ?: return

    // isChatNoForwards(Chat): the chat's (or its migrated supergroup's) noforwards flag.
    // The (long) overload delegates to it.
    hook(
        method(messagesControllerClass, "isChatNoForwards") {
            returnType("boolean")
            paramTypes("org.telegram.tgnet.TLRPC\$Chat")
            addUsingField("Lorg/telegram/tgnet/TLRPC\$Chat;->noforwards:Z", UsingType.Read)
        } ?: messagesControllerClass.declaredMethods.firstOrNull {
            it.name == "isChatNoForwards" && it.parameterTypes.firstOrNull()?.name == "org.telegram.tgnet.TLRPC\$Chat"
        },
        "MessagesController.isChatNoForwards",
        XC_MethodReplacement.returnConstant(false)
    )

    // canForwardMessage(): false for service, secret, protected and sponsored messages.
    hook(
        method(messageObjectClass, "canForwardMessage", 0) {
            returnType("boolean")
            paramCount(0)
            addUsingField("Lorg/telegram/tgnet/TLRPC\$Message;->noforwards:Z", UsingType.Read)
            usingNumbers(30, 31, 32, 33, 35, 37)
        },
        "MessageObject.canForwardMessage",
        XC_MethodReplacement.returnConstant(true)
    )
}
