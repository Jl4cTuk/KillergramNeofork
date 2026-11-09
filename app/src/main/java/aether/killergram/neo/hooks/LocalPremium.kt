package aether.killergram.neo.hooks

import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodReplacement

fun Hooks.localPremium() {
    log("Spoofing local premium...")

    val userConfigClass = loadClass("org.telegram.messenger.UserConfig") ?: return

    // isPremium(): the no-arg getter of the current user's premium flag.
    val isPremium = method(userConfigClass, "isPremium", 0) {
        returnType("boolean")
        paramCount(0)
        addUsingField(
            "Lorg/telegram/tgnet/TLRPC\$User;->premium:Z",
            org.luckypray.dexkit.query.enums.UsingType.Read
        )
    }
    hook(isPremium, "UserConfig.isPremium", XC_MethodReplacement.returnConstant(true))

    // hasPremiumOnAccounts(): the static no-arg method asking every account's isPremium.
    val hasPremiumOnAccounts = isPremium?.let { premium ->
        method(userConfigClass, "hasPremiumOnAccounts", 0) {
            returnType("boolean")
            paramCount(0)
            modifiers(java.lang.reflect.Modifier.STATIC)
            invokeMethods {
                add {
                    declaredClass(userConfigClass.name)
                    name(premium.name)
                    paramCount(0)
                }
            }
        }
    }
    hook(hasPremiumOnAccounts, "UserConfig.hasPremiumOnAccounts", XC_MethodReplacement.returnConstant(true))
}
