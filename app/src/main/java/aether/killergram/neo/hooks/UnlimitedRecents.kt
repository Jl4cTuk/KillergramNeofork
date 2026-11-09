package aether.killergram.neo.hooks

import aether.killergram.neo.core.Features
import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers

/** The limits are read when used, so changing them in the module app applies right away. */
fun Hooks.unlimitedRecents(recentStickersLimit: () -> Int, recentEmojiLimit: () -> Int) {
    log("Overriding recent stickers limit to ${recentStickersLimit()}, recent emoji limit to ${recentEmojiLimit()}...")

    // MessagesController.maxRecentStickersCount: loaded from the preferences in the
    // constructor, then set from the server's TL_config by updateConfig (on Telegram 13.2+
    // a lambda that captures the controller).
    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val limit = runCatching {
        val field = XposedHelpers.findField(messagesControllerClass, "maxRecentStickersCount")
        RecentStickersLimit(field, messagesControllerClass.declaredMethods.filter {
            it.parameterTypes.singleOrNull()?.name == TL_CONFIG
        })
    }.getOrNull() ?: findRecentStickersLimit(messagesControllerClass)
    if (limit == null) {
        log("MessagesController.maxRecentStickersCount not found", "ERROR")
    } else {
        // Telegram's own value per controller, put back when the feature is turned off.
        val serverLimits = java.util.WeakHashMap<Any, Int>()
        val setLimit = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam?) {
                param ?: return
                val target = param.thisObject ?: return
                val controller = if (messagesControllerClass.isInstance(target)) target
                else target.javaClass.declaredFields.firstOrNull { it.type == messagesControllerClass }
                    ?.apply { isAccessible = true }?.get(target)
                controller ?: return
                val wanted = recentStickersLimit()
                val current = limit.field.getInt(controller)
                synchronized(serverLimits) { if (current != wanted) serverLimits[controller] = current }
                limit.field.setInt(controller, wanted)
            }
        }
        limit.updaters.forEach { XposedBridge.hookMethod(it, setLimit) }
        XposedBridge.hookAllConstructors(messagesControllerClass, setLimit)

        val feature = Features.current
        Features.onChange {
            val on = feature?.isOn ?: return@onChange
            synchronized(serverLimits) {
                serverLimits.forEach { (controller, serverLimit) ->
                    limit.field.setInt(controller, if (on) recentStickersLimit() else serverLimit)
                }
            }
        }
    }

    // Emoji: the recent emoji list keeps the 48 most used (MAX_RECENT_EMOJI_COUNT) in
    // addRecentEmoji and sortEmoji; both are replaced to keep [recentEmojiLimit].
    val emoji = recentEmojiApi() ?: run {
        log("Recent emoji methods not found", "ERROR")
        return
    }

    XposedBridge.hookMethod(
        emoji.addRecentEmoji,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                param ?: return
                val code = param.args[0] as String
                @Suppress("UNCHECKED_CAST")
                val emojiUseHistory = emoji.useHistory.get(null) as HashMap<String, Int>
                val count = emojiUseHistory[code] ?: 0

                if (count == 0 && emojiUseHistory.size >= recentEmojiLimit()) {
                    @Suppress("UNCHECKED_CAST")
                    val recentEmoji = emoji.recent.get(null) as ArrayList<String>
                    if (recentEmoji.isNotEmpty()) {
                        val last = recentEmoji[recentEmoji.size - 1]
                        emojiUseHistory.remove(last)
                        recentEmoji[recentEmoji.size - 1] = code
                    }
                }
                emojiUseHistory[code] = count + 1
                param.result = null
            }
        }
    )

    emoji.sortEmoji?.let { sortEmoji ->
        XposedBridge.hookMethod(
            sortEmoji,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    param ?: return
                    @Suppress("UNCHECKED_CAST")
                    val recentEmoji = emoji.recent.get(null) as ArrayList<String>
                    @Suppress("UNCHECKED_CAST")
                    val emojiUseHistory = emoji.useHistory.get(null) as HashMap<String, Int>

                    recentEmoji.clear()
                    recentEmoji.addAll(emojiUseHistory.keys)
                    recentEmoji.sortWith { lhs, rhs ->
                        val count1 = emojiUseHistory[lhs] ?: 0
                        val count2 = emojiUseHistory[rhs] ?: 0
                        count2.compareTo(count1)
                    }
                    val emojiLimit = recentEmojiLimit()
                    while (recentEmoji.size > emojiLimit) {
                        recentEmoji.removeAt(recentEmoji.size - 1)
                    }
                    param.result = null
                }
            }
        )
    } ?: log("Emoji.sortEmoji not found — recent emoji stay trimmed to 48 on reload", "ERROR")
}

private const val TL_CONFIG = "org.telegram.tgnet.TLRPC\$TL_config"
private const val MAX_RECENT_EMOJI = 48

private class RecentStickersLimit(
    val field: java.lang.reflect.Field,
    val updaters: List<java.lang.reflect.Method>,
)

/**
 * maxRecentStickersCount on obfuscated builds: the controller's int field written right
 * after reading TL_config.stickers_recent_limit, by the method that applies the config.
 */
private fun Hooks.findRecentStickersLimit(controller: Class<*>): RecentStickersLimit? {
    val updater = TgDeobf.findMethod(lpparam, "applyConfig", "MessagesController.updateConfig") { bridge ->
        bridge.findMethod {
            matcher {
                addUsingField(
                    "L${TL_CONFIG.replace('.', '/')};->stickers_recent_limit:I",
                    org.luckypray.dexkit.query.enums.UsingType.Read
                )
            }
        }.filter { !it.className.startsWith("org.telegram.tgnet.") }  // TL_config's own (de)serializer
    } ?: return null
    val field = TgDeobf.withMethodData(lpparam, updater) { data ->
        val fields = data.usingFields
        val read = fields.indexOfFirst {
            it.usingType.isRead() && it.field.name == "stickers_recent_limit"
        }
        if (read < 0) return@withMethodData null
        fields.drop(read + 1).firstOrNull {
            !it.usingType.isRead() && it.field.declaredClassName == controller.name &&
                it.field.typeName == "int"
        }?.field?.getFieldInstance(lpparam.classLoader)
    }?.apply { isAccessible = true } ?: return null
    return RecentStickersLimit(field, listOf(updater))
}

private class RecentEmoji(
    val addRecentEmoji: java.lang.reflect.Method,
    val sortEmoji: java.lang.reflect.Method?,
    val useHistory: java.lang.reflect.Field,
    val recent: java.lang.reflect.Field,
)

/**
 * Emoji.addRecentEmoji(String): the static (String) method that caps the use history at
 * 48; its static HashMap and ArrayList are emojiUseHistory and recentEmoji. sortEmoji()
 * is the class's static no-arg method that sorts and trims to 48.
 */
private fun Hooks.recentEmojiApi(): RecentEmoji? {
    loadClass("org.telegram.messenger.Emoji", logOnFailure = false)?.let { emojiClass ->
        return runCatching {
            RecentEmoji(
                emojiClass.getDeclaredMethod("addRecentEmoji", String::class.java),
                emojiClass.getDeclaredMethod("sortEmoji"),
                XposedHelpers.findField(emojiClass, "emojiUseHistory"),
                XposedHelpers.findField(emojiClass, "recentEmoji"),
            )
        }.getOrNull()
    }
    val add = TgDeobf.findMethod(lpparam, "addRecentEmoji", "Emoji.addRecentEmoji") { bridge ->
        bridge.findMethod {
            matcher {
                modifiers(java.lang.reflect.Modifier.STATIC)
                returnType("void")
                paramTypes("java.lang.String")
                usingNumbers(MAX_RECENT_EMOJI)
                invokeMethods { add { declaredClass("java.util.HashMap"); name("put") } }
            }
        }
    } ?: return null
    val emojiClass = add.declaringClass
    val fields = TgDeobf.withMethodData(lpparam, add) { data ->
        data.usingFields.map { it.field.getFieldInstance(lpparam.classLoader) }
    }.orEmpty().filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.declaringClass == emojiClass }
    val history = fields.firstOrNull { it.type == HashMap::class.java } ?: return null
    val recent = fields.firstOrNull { it.type == ArrayList::class.java } ?: return null
    val sort = TgDeobf.findMethod(lpparam, "sortEmoji", "Emoji.sortEmoji") { bridge ->
        bridge.findMethod {
            matcher {
                declaredClass(emojiClass.name)
                modifiers(java.lang.reflect.Modifier.STATIC)
                returnType("void")
                paramCount(0)
                usingNumbers(MAX_RECENT_EMOJI)
                invokeMethods { add { declaredClass("java.util.Collections"); name("sort") } }
            }
        }
    }
    return RecentEmoji(
        add,
        sort,
        history.apply { isAccessible = true },
        recent.apply { isAccessible = true },
    )
}
