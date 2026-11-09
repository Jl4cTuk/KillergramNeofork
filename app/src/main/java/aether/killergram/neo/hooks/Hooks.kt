package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import aether.killergram.neo.xposed.XposedHelpers
import aether.killergram.neo.xposed.LoadPackageParam


class Hooks(val lpparam: LoadPackageParam) {
    // Classes that kept their names up to Telegram 13.1 and are resolved by fingerprint
    // since 13.2 (see TgDeobf).
    private val renamed: Map<String, () -> Class<*>?> = mapOf(
        "org.telegram.messenger.ApplicationLoader" to { TgDeobf.applicationLoader(lpparam) },
        "org.telegram.messenger.MessagesController" to { TgDeobf.messagesController(lpparam) },
        "org.telegram.messenger.MessagesStorage" to { TgDeobf.messagesStorage(lpparam) },
        "org.telegram.messenger.UserConfig" to { TgDeobf.userConfig(lpparam) },
        "org.telegram.messenger.NotificationCenter" to { TgDeobf.notificationCenter(lpparam) },
        "org.telegram.messenger.NotificationsController" to { TgDeobf.notificationsController(lpparam) },
        "org.telegram.messenger.SendMessagesHelper" to { TgDeobf.sendMessagesHelper(lpparam) },
        "org.telegram.messenger.AccountInstance" to { TgDeobf.accountInstance(lpparam) },
        "org.telegram.messenger.MessageObject" to { TgDeobf.messageObject(lpparam) },
        "org.telegram.messenger.LocaleController" to { TgDeobf.localeController(lpparam) },
        "org.telegram.messenger.SharedConfig" to { TgDeobf.sharedConfig(lpparam) },
        "org.telegram.messenger.TopicsController" to { TgDeobf.topicsController(lpparam) },
    )

    /** Hooks [method], or logs that [label] was not found. */
    fun hook(method: java.lang.reflect.Method?, label: String, callback: aether.killergram.neo.xposed.XC_MethodHook): Boolean {
        if (method == null) {
            log("$label not found", "ERROR")
            return false
        }
        aether.killergram.neo.xposed.XposedBridge.hookMethod(method, callback)
        return true
    }

    /**
     * A method of [cls]: by its kept [name] on older builds, otherwise by a DexKit query
     * inside [cls] that must match exactly one method.
     */
    fun method(
        cls: Class<*>,
        name: String,
        paramCount: Int? = null,
        optional: Boolean = false,
        matcher: org.luckypray.dexkit.query.matchers.MethodMatcher.() -> Unit,
    ): java.lang.reflect.Method? = TgDeobf.method(lpparam, cls, name, paramCount, optional) { bridge ->
        bridge.findMethod {
            matcher {
                declaredClass(cls.name)
                matcher()
            }
        }
    }

    fun loadClass(className: String, logOnFailure: Boolean = true): Class<*>? {
        // This function is here purely to log any failed class loads
        val loadedClass = XposedHelpers.findClassIfExists(className, this.lpparam.classLoader)
            ?: renamed[className]?.invoke()
        if (loadedClass == null && logOnFailure) {
            log("Unable to load class $className from ${lpparam.packageName}", "ERROR")
        }
        return loadedClass
    }
}
