package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.os.Bundle
import android.os.SystemClock
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

// Folder membership can change without the dialog list changing (a chat gets read and
// leaves an "exclude read" folder), so a filtered list is rebuilt at least this often.
private const val CACHE_TTL_MS = 1000L

private const val ARCHIVE_FOLDER_ID = 1

private class FilteredList(
    val source: ArrayList<*>,
    val signature: Int,
    val builtAt: Long,
    val result: ArrayList<Any?>
)

/**
 * Chats that belong to any chat folder are shown only in their folders: they are left
 * out of "All" on the main chat list, out of the Archive and out of the Archive row's
 * preview of chat names. Folder membership is Telegram's own (DialogFilter.includesDialog):
 * explicitly added chats and chats matched by the folder's rules alike. Pickers
 * (forwarding, sharing) and search are untouched.
 *
 * getDialogsArray runs on every list update and scroll, so the filtered list is cached
 * and only rebuilt when Telegram's list changes or the cache expires.
 */
fun Hooks.hideFolderDuplicates() {
    log("Hiding folder chats from All and Archive...")

    val controllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val accountInstanceClass = loadClass("org.telegram.messenger.AccountInstance") ?: return
    val dialogClass = loadClass("org.telegram.tgnet.TLRPC\$Dialog") ?: return
    val dialogFolderClass = loadClass("org.telegram.tgnet.TLRPC\$TL_dialogFolder")
    val createView = TgDeobf.dialogsCreateView(lpparam) ?: return
    val dialogsArray = TgDeobf.dialogsArray(lpparam, createView.declaringClass) ?: return

    val access = runCatching {
        // DialogFilter.includesDialog(accountInstance, dialogId, dialog): the folder's own
        // membership test; DialogFilter is the class declaring it.
        val includesDialog = TgDeobf.method(
            lpparam, accountInstanceClass, "includesDialog:${dialogClass.name}"
        ) { bridge ->
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    paramTypes(accountInstanceClass.name, "long", dialogClass.name)
                }
            }
        } ?: error("DialogFilter.includesDialog not found")
        val filterClass = includesDialog.declaringClass

        // isDefault(): the no-arg boolean that only compares the folder id with 0.
        val isDefault = TgDeobf.method(lpparam, filterClass, "isDefault", 0) { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass(filterClass.name)
                    returnType("boolean")
                    paramCount(0)
                }
            }.filter { method ->
                method.invokes.isEmpty() && method.usingFields.size == 1 &&
                    method.usingFields.single().field.typeName == "int"
            }
        } ?: error("DialogFilter.isDefault not found")

        // getDialogs(folderId): the only (int) -> ArrayList method.
        val getDialogs = controllerClass.declaredMethods.singleOrNull {
            it.name == "getDialogs" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: controllerClass.declaredMethods.single {
            it.returnType == ArrayList::class.java &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }

        Access(
            controllerInstance = TgDeobf.getInstance(controllerClass) ?: error("MessagesController.getInstance"),
            getDialogs = getDialogs.apply { isAccessible = true },
            filterClass = filterClass,
            dialogFilters = runCatching { XposedHelpers.findField(controllerClass, "dialogFilters") }.getOrNull(),
            accountInstance = TgDeobf.getInstance(accountInstanceClass) ?: error("AccountInstance.getInstance"),
            isDefault = isDefault,
            includesDialog = includesDialog,
            dialogId = XposedHelpers.findField(dialogClass, "id"),
            dialogFolderClass = dialogFolderClass
        )
    }.getOrElse {
        log("Folder chat hiding unavailable: ${it.message}", "ERROR")
        return
    }

    // Per screen: whether it is the main list / archive.
    val screens = WeakHashMap<Any, Boolean>()
    // Per (account, folder): the last filtered list. It depends only on Telegram's list.
    val caches = HashMap<Long, FilteredList>()

    fun filtered(controller: Any?, account: Int, folderId: Int, source: ArrayList<*>): ArrayList<Any?>? {
        val key = (account.toLong() shl 8) or folderId.toLong()
        val signature = signatureOf(source)
        val now = SystemClock.uptimeMillis()
        synchronized(caches) {
            caches[key]?.let { cached ->
                if (cached.source === source && cached.signature == signature &&
                    now - cached.builtAt < CACHE_TTL_MS
                ) {
                    return cached.result
                }
            }
        }
        val result = access.filter(controller, account, source) ?: return null
        synchronized(caches) { caches[key] = FilteredList(source, signature, now, result) }
        return result
    }

    XposedBridge.hookMethod(dialogsArray, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val fragment = param.thisObject ?: return
            val result = param.result as? ArrayList<*> ?: return
            val account = param.args[0] as Int
            val folderId = param.args[2] as Int
            if (folderId != 0 && folderId != ARCHIVE_FOLDER_ID) return
            runCatching {
                val isListScreen = synchronized(screens) {
                    screens.getOrPut(fragment) { isChatListScreen(fragment, folderId) }
                }
                if (!isListScreen) return

                val controller = access.controllerInstance.invoke(null, account)
                // Only the plain folder list ("All" tab or the Archive), not a folder tab.
                if (access.getDialogs.invoke(controller, folderId) !== result) return

                filtered(controller, account, folderId, result)?.let { param.result = it }
            }.onFailure {
                log("Failed to hide folder chats: ${it.message}", "DEBUG")
            }
        }
    })

    // The Archive row's badge: the number of unread archived chats, counted by
    // MessagesStorage. Chats that belong to a folder are left out of it.
    // Only builds that keep the name: an obfuscated build has many no-arg int getters.
    loadClass("org.telegram.messenger.MessagesStorage")?.declaredMethods?.singleOrNull {
        it.name == "getArchiveUnreadCount" && it.parameterTypes.isEmpty()
    }?.let { getArchiveUnreadCount ->
        XposedBridge.hookMethod(getArchiveUnreadCount, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val count = param.result as? Int ?: return
                if (count <= 0) return
                runCatching {
                    val account = XposedHelpers.getIntField(param.thisObject, "currentAccount")
                    val controller = access.controllerInstance.invoke(null, account)
                    val archived = access.getDialogs.invoke(controller, ARCHIVE_FOLDER_ID) as? ArrayList<*> ?: return
                    val shown = filtered(controller, account, ARCHIVE_FOLDER_ID, archived) ?: return
                    if (shown === archived) return
                    val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any?, Boolean>())
                        .apply { addAll(shown) }
                    val hiddenUnread = archived.count { dialog ->
                        dialog != null && dialog !in kept && (
                            XposedHelpers.getIntField(dialog, "unread_count") > 0 ||
                                XposedHelpers.getBooleanField(dialog, "unread_mark")
                            )
                    }
                    param.result = (count - hiddenUnread).coerceAtLeast(0)
                }.onFailure {
                    log("Failed to adjust the Archive unread count: ${it.message}", "DEBUG")
                }
            }
        })
    }

    // The Archive row lists its chats' names, read straight from
    // MessagesController.getDialogs(1) while the row is laid out.
    val buildLayout = TgDeobf.dialogCellBuildLayout(lpparam) ?: return
    val layingOutCell = ThreadLocal.withInitial { false }

    XposedBridge.hookMethod(buildLayout, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            layingOutCell.set(true)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            layingOutCell.set(false)
        }
    })

    XposedBridge.hookMethod(access.getDialogs, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (layingOutCell.get() != true) return
            if (param.args[0] as Int != ARCHIVE_FOLDER_ID) return
            val result = param.result as? ArrayList<*> ?: return
            runCatching {
                val account = XposedHelpers.getIntField(param.thisObject, "currentAccount")
                filtered(param.thisObject, account, ARCHIVE_FOLDER_ID, result)?.let { param.result = it }
            }.onFailure {
                log("Failed to hide folder chats from the Archive row: ${it.message}", "DEBUG")
            }
        }
    })
}

private class Access(
    val controllerInstance: Method,
    val getDialogs: Method,
    val filterClass: Class<*>,
    var dialogFilters: Field?,
    val accountInstance: Method,
    val isDefault: Method,
    val includesDialog: Method,
    val dialogId: Field,
    val dialogFolderClass: Class<*>?
) {
    /**
     * MessagesController.dialogFilters: on obfuscated builds, the ArrayList field holding
     * DialogFilter objects (found once the user has a folder).
     */
    private fun filtersOf(controller: Any?): Any? {
        controller ?: return null
        dialogFilters?.let { return it.get(controller) }
        val field = controller.javaClass.declaredFields.firstOrNull { field ->
            field.type == ArrayList::class.java && !java.lang.reflect.Modifier.isStatic(field.modifiers) && run {
                field.isAccessible = true
                (field.get(controller) as? ArrayList<*>)?.any { filterClass.isInstance(it) } == true
            }
        } ?: return null
        dialogFilters = field
        return field.get(controller)
    }

    /** The dialogs that belong to no folder, or null when there are no folders. */
    fun filter(controller: Any?, account: Int, dialogs: ArrayList<*>): ArrayList<Any?>? {
        val filters = (filtersOf(controller) as? List<*>)
            ?.filter { it != null && isDefault.invoke(it) != true }
            .orEmpty()
        if (filters.isEmpty()) return null
        val instance = accountInstance.invoke(null, account)

        val result = ArrayList<Any?>(dialogs.size)
        for (dialog in dialogs) {
            if (dialog == null || dialogFolderClass?.isInstance(dialog) == true) {
                result.add(dialog)
                continue
            }
            val id = dialogId.getLong(dialog)
            if (filters.none { includesDialog.invoke(it, instance, id, dialog) == true }) {
                result.add(dialog)
            }
        }
        return result
    }
}

/** Cheap fingerprint of the list's contents and order. */
private fun signatureOf(list: ArrayList<*>): Int {
    var hash = list.size
    for (item in list) hash = hash * 31 + System.identityHashCode(item)
    return hash
}

/** The main chat list (All) or the Archive screen; not a chat picker. */
private fun isChatListScreen(fragment: Any, folderId: Int): Boolean {
    val arguments = runCatching {
        XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
    }.getOrNull()
    if (arguments?.getBoolean("onlySelect", false) == true) return false
    if ((arguments?.getInt("dialogsType", 0) ?: 0) != 0) return false
    return if (folderId == 0) isMainChatList(fragment) else true
}
