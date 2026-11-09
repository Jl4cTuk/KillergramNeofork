package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XC_MethodReplacement
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers

fun Hooks.killStories() {
    log("Disabling stories...")

    // Account-wide gate: storiesEnabled() hides story posting. Only builds that keep the
    // name have it hooked; in obfuscated ones it can't be told from storyEntitiesAllowed(),
    // and emptying the story lists below is enough to hide stories.
    loadClass("org.telegram.messenger.MessagesController")?.let { messagesController ->
        XposedBridge.hookAllMethods(
            messagesController,
            "storiesEnabled",
            XC_MethodReplacement.returnConstant(false)
        )
    }

    val storiesControllerClass = TgDeobf.storiesController(lpparam)
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
