package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun Hooks.killStories() {
    log("Disabling stories...")

    // Account-wide gate in org.telegram.messenger.* (R8 keeps the name) — obfuscation-proof.
    val messagesControllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    XposedBridge.hookAllMethods(
        messagesControllerClass,
        "storiesEnabled",
        XC_MethodReplacement.returnConstant(false)
    )

    // StoriesController lives in an obfuscated package in Telegram 12.10.2+, but the kept
    // MessagesController.getStoriesController() returns it, so its class is always known.
    val storiesControllerClass = runCatching {
        messagesControllerClass.getDeclaredMethod("getStoriesController").returnType
    }.getOrNull()
    if (storiesControllerClass == null) {
        log("StoriesController not found — story bar stays visible", "ERROR")
        return
    }

    val allStoriesClass = loadClass("org.telegram.tgnet.tl.TL_stories\$TL_stories_allStories")
    val updateStoryClass = loadClass("org.telegram.tgnet.tl.TL_stories\$TL_updateStory")

    storiesControllerClass.declaredMethods.forEach { method ->
        val params = method.parameterTypes
        when {
            // processAllStoriesResponse(allStories, hidden, fromCache, isNext): the dialogs
            // story bar (and the archived one) is filled from here, from the network and from
            // the local cache. Drop the peers so both lists stay empty.
            allStoriesClass != null && params.firstOrNull() == allStoriesClass ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            (XposedHelpers.getObjectField(param.args[0], "peer_stories") as? MutableList<*>)
                                ?.clear()
                        }
                    }
                })

            // processUpdate(TL_updateStory): live updates would add stories back.
            updateStoryClass != null && params.size == 1 && params[0] == updateStoryClass ->
                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(null))
        }
    }

    // hasStories(dialogId): story rings around avatars in chats, profiles and the chat list.
    TgDeobf.hasStories(lpparam, storiesControllerClass)?.let {
        XposedBridge.hookMethod(it, XC_MethodReplacement.returnConstant(false))
    }

    // Non-obfuscated forks still ship the original names.
    loadClass("org.telegram.ui.Stories.StoriesController", logOnFailure = false)?.let { legacy ->
        listOf("hasStories", "hasSelfStories", "hasHiddenStories").forEach { method ->
            XposedBridge.hookAllMethods(legacy, method, XC_MethodReplacement.returnConstant(false))
        }
        listOf("loadStories", "loadHiddenStories", "processUpdate").forEach { method ->
            XposedBridge.hookAllMethods(legacy, method, XC_MethodReplacement.returnConstant(null))
        }
    }
}
