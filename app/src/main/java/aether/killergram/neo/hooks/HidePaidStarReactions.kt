package aether.killergram.neo.hooks

import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun Hooks.hidePaidStarReactions() {
    log("Hiding paid star reactions...")

    val paidReactionClass = loadClass("org.telegram.tgnet.TLRPC\$TL_reactionPaid")
    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController")
    val messageObjectClass = loadClass("org.telegram.messenger.MessageObject")
    // Telegram 12.10.2+ obfuscates these; the data-level hooks below cover them there.
    val bubbleReactionsClass = loadClass("org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble", logOnFailure = false)
    val containerReactionsClass = loadClass("org.telegram.ui.Components.ReactionsContainerLayout", logOnFailure = false)
    val chatActivityClass = loadClass("org.telegram.ui.ChatActivity", logOnFailure = false)

    // Every message's reactions and every chat's full info, from the network and from
    // the local database alike, are read through these kept TLRPC deserializers. Paid
    // reactions are dropped there, so no message bubble shows a star, and chats report
    // paid reactions as unavailable, so the reaction picker offers none.
    loadClass("org.telegram.tgnet.TLRPC\$MessageReactions")?.let { reactionsClass ->
        XposedBridge.hookAllMethods(reactionsClass, "TLdeserialize", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                stripPaidReactions(param.result, paidReactionClass)
            }
        })
    }
    loadClass("org.telegram.tgnet.TLRPC\$ChatFull")?.let { chatFullClass ->
        XposedBridge.hookAllMethods(chatFullClass, "TLdeserialize", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                forceDisablePaidReactionsFlag(param.result)
            }
        })
    }

    if (messagesControllerClass != null) {
        XposedBridge.hookAllMethods(
            messagesControllerClass,
            "getChatFull",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    forceDisablePaidReactionsFlag(param.result)
                }
            }
        )
    }

    if (messageObjectClass != null) {
        XposedBridge.hookAllMethods(
            messageObjectClass,
            "addPaidReactions",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            }
        )
        XposedBridge.hookAllMethods(
            messageObjectClass,
            "ensurePaidReactionsExist",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )
        XposedBridge.hookAllMethods(
            messageObjectClass,
            "isPaidReactionChosen",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )
        XposedBridge.hookAllMethods(
            messageObjectClass,
            "doesPaidReactionExist",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            }
        )
    }

    if (bubbleReactionsClass != null) {
        XposedBridge.hookAllMethods(
            bubbleReactionsClass,
            "setMessage",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    removePaidReactionsFromMessage(param.args.getOrNull(0), paidReactionClass)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        XposedHelpers.setBooleanField(param.thisObject, "hasPaidReaction", false)
                    }
                }
            }
        )
    }

    if (containerReactionsClass != null) {
        XposedBridge.hookAllMethods(
            containerReactionsClass,
            "setMessage",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    removePaidReactionsFromMessage(param.args.getOrNull(0), paidReactionClass)
                    forceDisablePaidReactionsFlag(param.args.getOrNull(1))
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    stripStarsFromReactionContainer(param.thisObject)
                }
            }
        )

        XposedBridge.hookAllMethods(
            containerReactionsClass,
            "onReactionClicked",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val visibleReaction = param.args.getOrNull(1) ?: return
                    if (isVisibleReactionStar(visibleReaction)) {
                        param.result = null
                    }
                }
            }
        )
    }

    if (chatActivityClass != null) {
        XposedBridge.hookAllMethods(
            chatActivityClass,
            "selectReaction",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val visibleReaction = param.args.getOrNull(6) ?: return
                    if (isVisibleReactionStar(visibleReaction)) {
                        param.result = null
                    }
                }
            }
        )
    }
}

private fun forceDisablePaidReactionsFlag(chatFull: Any?) {
    if (chatFull == null) {
        return
    }
    runCatching {
        XposedHelpers.setBooleanField(chatFull, "paid_reactions_available", false)
    }
}

private fun removePaidReactionsFromMessage(messageObject: Any?, paidReactionClass: Class<*>?) {
    val messageOwner = runCatching {
        XposedHelpers.getObjectField(messageObject, "messageOwner")
    }.getOrNull() ?: return

    val reactions = runCatching {
        XposedHelpers.getObjectField(messageOwner, "reactions")
    }.getOrNull() ?: return
    stripPaidReactions(reactions, paidReactionClass)
}

/** Drops paid reactions, and the paid reactors' leaderboard, from a MessageReactions. */
private fun stripPaidReactions(reactions: Any?, paidReactionClass: Class<*>?) {
    if (reactions == null) {
        return
    }
    runCatching {
        (XposedHelpers.getObjectField(reactions, "top_reactors") as? MutableList<*>)?.clear()
    }

    val results = runCatching {
        @Suppress("UNCHECKED_CAST")
        XposedHelpers.getObjectField(reactions, "results") as? MutableList<Any?>
    }.getOrNull()
    if (results != null) {
        results.removeAll { reactionCount ->
            val reaction = runCatching {
                XposedHelpers.getObjectField(reactionCount, "reaction")
            }.getOrNull()
            isPaidReaction(reaction, paidReactionClass)
        }
    }

    val recentReactions = runCatching {
        @Suppress("UNCHECKED_CAST")
        XposedHelpers.getObjectField(reactions, "recent_reactions") as? MutableList<Any?>
    }.getOrNull()
    if (recentReactions != null) {
        recentReactions.removeAll { recent ->
            val reaction = runCatching {
                XposedHelpers.getObjectField(recent, "reaction")
            }.getOrNull()
            isPaidReaction(reaction, paidReactionClass)
        }
    }
}

private fun stripStarsFromReactionContainer(container: Any?) {
    if (container == null) {
        return
    }

    val original = runCatching {
        @Suppress("UNCHECKED_CAST")
        XposedHelpers.getObjectField(container, "visibleReactionsList") as? List<Any?>
    }.getOrNull() ?: return

    val filtered = original.filterNot { it != null && isVisibleReactionStar(it) }
    if (filtered.size == original.size) {
        runCatching { XposedHelpers.setBooleanField(container, "hasStar", false) }
        return
    }

    runCatching {
        XposedHelpers.callMethod(container, "setVisibleReactionsList", filtered, false)
    }
    runCatching { XposedHelpers.setBooleanField(container, "hasStar", false) }
    runCatching {
        @Suppress("UNCHECKED_CAST")
        val selected = XposedHelpers.getObjectField(container, "selectedReactions") as? MutableSet<Any?>
        selected?.removeAll { it != null && isVisibleReactionStar(it) }
    }
    runCatching {
        @Suppress("UNCHECKED_CAST")
        val selected = XposedHelpers.getObjectField(container, "alwaysSelectedReactions") as? MutableSet<Any?>
        selected?.removeAll { it != null && isVisibleReactionStar(it) }
    }
}

private fun isVisibleReactionStar(visibleReaction: Any): Boolean {
    return runCatching {
        XposedHelpers.getBooleanField(visibleReaction, "isStar")
    }.getOrDefault(false)
}

private fun isPaidReaction(reaction: Any?, paidReactionClass: Class<*>?): Boolean {
    if (reaction == null) {
        return false
    }
    if (paidReactionClass != null && paidReactionClass.isInstance(reaction)) {
        return true
    }
    return reaction.javaClass.name.endsWith("TL_reactionPaid")
}

