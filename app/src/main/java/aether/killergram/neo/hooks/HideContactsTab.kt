package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

// Name of the Contacts tab's enum constant (enum names survive R8).
private const val CONTACTS_TAB = "CONTACTS"

/**
 * Telegram 12.2+ shows a bottom tab bar (Chats, Contacts, Settings or Calls, Profile).
 * Hide the Contacts tab once the bar is built, through the bar's own show/hide method —
 * the one Telegram uses to swap Settings and Calls — so the remaining tabs are laid out
 * without a gap.
 */
fun Hooks.hideContactsTab() {
    log("Hiding the Contacts tab...")

    val createView = TgDeobf.mainTabsCreateView(lpparam) ?: return
    XposedBridge.hookMethod(createView, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                hideTab(param.thisObject, CONTACTS_TAB)
            }.onFailure {
                log("Failed to hide the Contacts tab: ${it.message}", "ERROR")
            }
        }
    })
}

private fun hideTab(fragment: Any, tabName: String) {
    // The tab buttons are kept in the fragment's only array of views.
    val tabs = fragment.javaClass.declaredFields
        .firstOrNull { it.type.isArray && View::class.java.isAssignableFrom(it.type.componentType!!) }
        ?.apply { isAccessible = true }
        ?.get(fragment) as? Array<*> ?: return

    tabs.filterIsInstance<View>().forEach { tab ->
        val kinds = tab.javaClass.declaredFields
            .filter { it.type.isEnum }
            .mapNotNull { field -> field.apply { isAccessible = true }.get(tab) as? Enum<*> }
        if (kinds.none { it.name == tabName }) return@forEach

        val container = tab.parent as? ViewGroup
        val setTabVisible = container?.let { findTabVisibilitySetter(it.javaClass) }
        if (setTabVisible != null) {
            setTabVisible.invoke(container, tab, false, false)
        } else {
            tab.visibility = View.GONE
        }
    }
}

/** void (View, boolean visible, boolean animated) on the tab bar or its superclasses. */
private fun findTabVisibilitySetter(containerClass: Class<*>): Method? {
    var current: Class<*>? = containerClass
    while (current != null && current != ViewGroup::class.java) {
        current.declaredMethods.firstOrNull { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(View::class.java, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
                )
        }?.let { return it.apply { isAccessible = true } }
        current = current.superclass
    }
    return null
}
