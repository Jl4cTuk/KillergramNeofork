package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

// Main tab positions (Telegram 12.2+): Chats, Contacts, Settings or Calls, Profile.
private const val POSITION_CHATS = 0
private const val POSITION_AFTER_CONTACTS = 2

/**
 * A horizontal swipe on the main screen moves between bottom tabs once the chat list has
 * no folder left to scroll to, so swiping past the last folder opens Contacts, and so does
 * swiping back from Settings. Both swipes are refused; tapping the tab still works when it
 * is shown.
 */
fun Hooks.blockContactsSwipe() {
    log("Blocking swipes into the Contacts tab...")

    val pager = TgDeobf.mainTabsPager(lpparam) ?: return

    fun refuseFrom(position: Int) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.result != true) return
            val current = runCatching {
                XposedHelpers.callMethod(param.thisObject, "getCurrentPosition") as Int
            }.getOrNull() ?: return
            if (current == position) param.result = false
        }
    }

    XposedBridge.hookMethod(pager.canScrollForward, refuseFrom(POSITION_CHATS))
    XposedBridge.hookMethod(pager.canScrollBackward, refuseFrom(POSITION_AFTER_CONTACTS))
}
