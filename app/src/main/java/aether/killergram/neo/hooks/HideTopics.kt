package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/*
 * Hide individual forum topics.
 *
 * Everything that matters lives in kept classes: org.telegram.messenger.TopicsController
 * hands topic lists to every topic UI (topic tabs inside the chat, the topics list screen
 * and dialog-cell previews) through getTopics(chatId), so filtering its result hides a
 * topic everywhere. The hide/unhide actions are added to the topic long-press menu, which
 * Telegram builds with ItemOptions; a menu belongs to a topic when one of its item
 * callbacks captures a TLRPC.TL_forumTopic.
 *
 * Hidden topics are stored in Telegram's own SharedPreferences: the module's
 * XSharedPreferences are read-only from the Telegram process, and this list is managed
 * from inside Telegram anyway.
 */

private const val STORE_NAME = "killergram_hidden_topics"
private const val STORE_KEY = "hidden"
private const val MENU_MARKER = "killergramneo_topic_menu_extended"

private object HiddenTopics {
    private var prefs: SharedPreferences? = null
    private val hidden = Collections.synchronizedSet(HashSet<String>())

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE).also {
            hidden.addAll(it.getStringSet(STORE_KEY, emptySet()).orEmpty())
        }
    }

    private fun key(chatId: Long, topicId: Int) = "$chatId:$topicId"

    fun isHidden(chatId: Long, topicId: Int) = hidden.contains(key(chatId, topicId))

    fun hasHidden(chatId: Long): Boolean {
        val prefix = "$chatId:"
        return synchronized(hidden) { hidden.any { it.startsWith(prefix) } }
    }

    fun countHidden(chatId: Long): Int {
        val prefix = "$chatId:"
        return synchronized(hidden) { hidden.count { it.startsWith(prefix) } }
    }

    /** Hidden topic ids by forum chat id. */
    fun byChat(): Map<Long, List<Int>> = synchronized(hidden) {
        hidden.mapNotNull { key ->
            val (chat, topic) = key.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            (chat.toLongOrNull() ?: return@mapNotNull null) to (topic.toIntOrNull() ?: return@mapNotNull null)
        }
    }.groupBy({ it.first }, { it.second })

    fun hiddenIds(chatId: Long): List<Int> {
        val prefix = "$chatId:"
        return synchronized(hidden) {
            hidden.filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toIntOrNull() }
        }
    }

    fun hide(chatId: Long, topicId: Int) {
        hidden.add(key(chatId, topicId))
        save()
    }

    fun unhideAll(chatId: Long) {
        val prefix = "$chatId:"
        synchronized(hidden) { hidden.removeAll { it.startsWith(prefix) } }
        save()
    }

    private fun save() {
        val snapshot = synchronized(hidden) { HashSet(hidden) }
        prefs?.edit()?.putStringSet(STORE_KEY, snapshot)?.apply()
    }
}

// Topic object -> forum chat id (positive channel id, as TopicsController uses it).
// TL_forumTopic carries no chat id, so we learn it whenever TopicsController hands
// topics out. Identity semantics: TL objects do not override equals/hashCode.
private val topicChats = Collections.synchronizedMap(WeakHashMap<Any, Long>())

// Per callback class: the fields that may hold a TL_forumTopic.
private val topicFieldsByClass = Collections.synchronizedMap(HashMap<Class<*>, List<Field>>())

private val addingOwnItem = ThreadLocal.withInitial { false }

fun Hooks.hideTopics() {
    log("Enabling topic hiding...")

    val topicsControllerClass = loadClass("org.telegram.messenger.TopicsController") ?: return
    val topicClass = loadClass("org.telegram.tgnet.TLRPC\$TL_forumTopic") ?: return
    val appLoaderClass = loadClass("org.telegram.messenger.ApplicationLoader") ?: return
    val loader = lpparam.classLoader

    fun context(): Context? = runCatching {
        XposedHelpers.getStaticObjectField(appLoaderClass, "applicationContext") as? Context
    }.getOrNull()

    fun topicId(topic: Any): Int? = runCatching { XposedHelpers.getIntField(topic, "id") }.getOrNull()

    fun ensureStore(): Boolean {
        val ctx = context() ?: return false
        HiddenTopics.init(ctx)
        return true
    }

    // 1. Filter topic lists and remember which chat each topic belongs to.
    XposedBridge.hookAllMethods(topicsControllerClass, "getTopics", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val chatId = param.args.getOrNull(0) as? Long ?: return
            @Suppress("UNCHECKED_CAST")
            val topics = param.result as? ArrayList<Any?> ?: return
            topics.forEach { topic -> if (topic != null) topicChats[topic] = chatId }
            if (!ensureStore() || !HiddenTopics.hasHidden(chatId)) return
            // Return a filtered copy: the original list is TopicsController's own state.
            param.result = ArrayList(topics.filterNot { topic ->
                topic != null && topicId(topic)?.let { HiddenTopics.isHidden(chatId, it) } == true
            })
        }
    })

    // Unread counters of a forum (chat list badge, mentions, reactions): computed from
    // every topic, so the hidden topics' share is taken back out.
    XposedBridge.hookAllMethods(topicsControllerClass, "getForumUnreadCount", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val chatId = param.args.getOrNull(0) as? Long ?: return
            val counts = param.result as? IntArray ?: return
            if (!ensureStore() || !HiddenTopics.hasHidden(chatId)) return
            runCatching {
                val controller = XposedHelpers.callMethod(param.thisObject, "getMessagesController")
                for (id in HiddenTopics.hiddenIds(chatId)) {
                    val topic = findTopic(param.thisObject, chatId, id) ?: continue
                    val unread = XposedHelpers.getIntField(topic, "unread_count")
                    if (unread > 0) counts[0]--
                    if (XposedHelpers.getIntField(topic, "unread_mentions_count") > 0) counts[1]--
                    if (XposedHelpers.getIntField(topic, "unread_reactions_count") > 0) counts[2]--
                    if (counts.size > 3 && !isTopicMuted(controller, chatId, id)) counts[3] -= unread
                    if (counts.size > 4) {
                        runCatching { counts[4] -= XposedHelpers.getIntField(topic, "unread_poll_votes_count") }
                    }
                }
                for (i in counts.indices) counts[i] = counts[i].coerceAtLeast(0)
            }.onFailure {
                log("Failed to adjust forum unread counts: ${it.message}", "DEBUG")
            }
        }
    })

    // Folder tab badges and the main unread counter count a forum as unread when any of
    // its topics is. MessagesStorage recounts them from the topics table, and updates them
    // per dialog as new messages arrive; both leave out hidden topics.
    hideTopicsFromUnreadCounters(::ensureStore)

    XposedBridge.hookAllMethods(topicsControllerClass, "findTopic", object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val chatId = param.args.getOrNull(0) as? Long ?: return
            val topic = param.result ?: return
            topicChats[topic] = chatId
        }
    })

    XposedBridge.hookAllMethods(topicsControllerClass, "processTopics", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val chatId = param.args.getOrNull(0) as? Long ?: return
            (param.args.getOrNull(1) as? List<*>)?.forEach { topic ->
                if (topic != null) topicChats[topic] = chatId
            }
        }
    })

    // 2. Chat list row of a forum: it names the topic of the chat's last message first
    //    and shows that message, both taken from the row's MessageObject rather than from
    //    getTopics. When that message is in a hidden topic, the row shows the newest
    //    message of a visible topic instead.
    hideTopicsInChatListRows(topicsControllerClass, ::ensureStore)

    // 3. Topics list screen: long-press selects topics and opens an action mode.
    //    Add "hide selected" / "show hidden" buttons to that action mode.
    TgDeobf.topicsList(lpparam)?.let { topicsList ->
        XposedBridge.hookMethod(topicsList.toggleSelection, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                val cell = param.args.getOrNull(0) ?: return
                val topic = findTopicIn(cell, topicClass) ?: run {
                    log("Topic selection: no topic in ${cell.javaClass.name}", "DEBUG")
                    return
                }
                val chatId = topicChats[topic] ?: chatIdFromTopMessage(topic) ?: run {
                    log("Topic selection: chat id unknown for topic ${topicId(topic)}", "DEBUG")
                    return
                }
                if (!ensureStore()) return
                log("Topic selection: chat $chatId topic ${topicId(topic)}", "DEBUG")
                runCatching {
                    updateSelectionButtons(fragment, chatId, loader)
                }.onFailure {
                    log("Failed to add topic selection buttons: ${it.message}", "ERROR")
                }
            }
        })
    }

    // 4. Topic tabs inside a forum chat: long-press opens an ItemOptions menu.
    //    Add "Hide topic" / "Show hidden topics" to it.
    val itemOptions = TgDeobf.itemOptions(lpparam) ?: run {
        log("ItemOptions unresolved — topic menu entries unavailable", "ERROR")
        return
    }

    XposedBridge.hookMethod(itemOptions.addItem, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (addingOwnItem.get() == true) return
            val options = param.thisObject ?: return
            val callback = param.args.getOrNull(2) ?: return
            val topic = findTopicIn(callback, topicClass) ?: return
            if (XposedHelpers.getAdditionalInstanceField(options, MENU_MARKER) == true) return

            val chatId = topicChats[topic]
                ?: chatIdFromTopMessage(topic)
                ?: run {
                    log("Topic menu: chat id unknown for topic ${topicId(topic)}", "DEBUG")
                    return
                }
            val id = topicId(topic) ?: return
            log("Topic menu: chat $chatId topic $id", "DEBUG")
            if (!ensureStore()) return
            XposedHelpers.setAdditionalInstanceField(options, MENU_MARKER, true)

            val ctx = context() ?: return
            val icon = drawableId(ctx, "msg_archive_hide", "msg_hide", "msg_customize")
            val ru = isRussian(ctx)

            addingOwnItem.set(true)
            try {
                itemOptions.addItem.invoke(
                    options,
                    icon,
                    if (ru) "Скрыть топик" else "Hide topic",
                    Runnable {
                        HiddenTopics.hide(chatId, id)
                        notifyTopicsChanged(loader, chatId)
                    },
                    false
                )
                val hiddenCount = HiddenTopics.countHidden(chatId)
                if (hiddenCount > 0) {
                    itemOptions.addItem.invoke(
                        options,
                        drawableId(ctx, "msg_unarchive", "msg_view_file", "msg_customize"),
                        if (ru) "Показать скрытые топики ($hiddenCount)" else "Show hidden topics ($hiddenCount)",
                        Runnable {
                            HiddenTopics.unhideAll(chatId)
                            notifyTopicsChanged(loader, chatId)
                        },
                        false
                    )
                }
            } catch (t: Throwable) {
                log("Failed to add topic menu items: ${t.message}", "ERROR")
            } finally {
                addingOwnItem.set(false)
            }
        }
    })
}

// MessagesStorage.calcUnreadCounters: the forums that have an unread topic.
private const val UNREAD_FORUMS_SQL =
    "SELECT DISTINCT did FROM topics WHERE unread_count > 0 OR unread_mentions > 0"

private fun Hooks.hideTopicsFromUnreadCounters(ensureStore: () -> Boolean) {
    val databaseClass = loadClass("org.telegram.SQLite.SQLiteDatabase") ?: return
    val storageClass = loadClass("org.telegram.messenger.MessagesStorage") ?: return

    XposedHelpers.findAndHookMethod(
        databaseClass, "queryFinalized", String::class.java, Array<Any>::class.java,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] != UNREAD_FORUMS_SQL || !ensureStore()) return
                val hidden = HiddenTopics.byChat()
                if (hidden.isEmpty()) return
                val excluded = hidden.entries.joinToString(" OR ") { (chatId, topics) ->
                    "(did = ${-chatId} AND topic_id IN (${topics.joinToString(",")}))"
                }
                param.args[0] = "SELECT DISTINCT did FROM topics " +
                    "WHERE (unread_count > 0 OR unread_mentions > 0) AND NOT ($excluded)"
            }
        }
    )

    // updateFiltersReadCounter(dialogsToUpdate, dialogsToUpdateMentions, read): with
    // read == false it adds the dialogs that got new messages. A forum whose only unread
    // topics are hidden gets a zero count, which Telegram skips.
    XposedBridge.hookAllMethods(storageClass, "updateFiltersReadCounter", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (param.args.getOrNull(2) != false || !ensureStore()) return
            val hidden = HiddenTopics.byChat()
            if (hidden.isEmpty()) return
            runCatching {
                val database = XposedHelpers.getObjectField(param.thisObject, "database")
                for (counts in param.args.take(2)) {
                    if (counts == null) continue
                    val size = XposedHelpers.callMethod(counts, "size") as Int
                    val dialogIds = (0 until size).map { XposedHelpers.callMethod(counts, "keyAt", it) as Long }
                    for (did in dialogIds) {
                        val topics = hidden[-did] ?: continue
                        if (!hasVisibleUnread(database, did, topics)) {
                            XposedHelpers.callMethod(counts, "put", did, 0)
                        }
                    }
                }
            }.onFailure {
                log("Failed to leave hidden topics out of unread counters: ${it.message}", "DEBUG")
            }
        }
    })
}

private fun hasVisibleUnread(database: Any, did: Long, hiddenTopics: List<Int>): Boolean {
    val cursor = XposedHelpers.callMethod(
        database, "queryFinalized",
        "SELECT 1 FROM topics WHERE did = $did AND (unread_count > 0 OR unread_mentions > 0) " +
            "AND topic_id NOT IN (${hiddenTopics.joinToString(",")}) LIMIT 1",
        arrayOf<Any>()
    )
    return try {
        XposedHelpers.callMethod(cursor, "next") as Boolean
    } finally {
        XposedHelpers.callMethod(cursor, "dispose")
    }
}

/**
 * Recounts unread chats for folder tabs and the main list after topics are hidden or
 * shown, the way MessagesStorage.saveDialogFilter does after a folder changes.
 */
private fun recountUnreadChats(loader: ClassLoader, account: Int) {
    runCatching {
        val storageClass = XposedHelpers.findClass("org.telegram.messenger.MessagesStorage", loader)
        val storage = XposedHelpers.callStaticMethod(storageClass, "getInstance", account)
        val queue = XposedHelpers.callMethod(storage, "getStorageQueue")
        XposedHelpers.callMethod(queue, "postRunnable", Runnable {
            runCatching {
                XposedHelpers.callMethod(storage, "calcUnreadCounters", false)
                val utilities = XposedHelpers.findClass("org.telegram.messenger.AndroidUtilities", loader)
                XposedHelpers.callStaticMethod(utilities, "runOnUIThread", Runnable {
                    runCatching { applyRecountedUnreadChats(loader, storage, account) }.onFailure {
                        log("Failed to apply unread counters: ${it.message}", "DEBUG")
                    }
                })
            }.onFailure {
                log("Failed to recount unread chats: ${it.message}", "DEBUG")
            }
        })
    }.onFailure {
        log("Failed to schedule unread recount: ${it.message}", "DEBUG")
    }
}

private fun applyRecountedUnreadChats(loader: ClassLoader, storage: Any, account: Int) {
    val controllerClass = XposedHelpers.findClass("org.telegram.messenger.MessagesController", loader)
    val controller = XposedHelpers.callStaticMethod(controllerClass, "getInstance", account)
    (XposedHelpers.getObjectField(controller, "dialogFilters") as? List<*>)?.forEach { filter ->
        if (filter != null) {
            XposedHelpers.setIntField(filter, "unreadCount", XposedHelpers.getIntField(filter, "pendingUnreadCount"))
        }
    }
    XposedHelpers.setIntField(storage, "mainUnreadCount", XposedHelpers.getIntField(storage, "pendingMainUnreadCount"))
    XposedHelpers.setIntField(storage, "archiveUnreadCount", XposedHelpers.getIntField(storage, "pendingArchiveUnreadCount"))
    val nc = XposedHelpers.findClass("org.telegram.messenger.NotificationCenter", loader)
    val center = XposedHelpers.callStaticMethod(nc, "getInstance", account)
    XposedHelpers.callMethod(
        center, "postNotificationName",
        XposedHelpers.getStaticIntField(nc, "updateInterfaces"),
        arrayOf<Any>(XposedHelpers.getStaticIntField(controllerClass, "UPDATE_MASK_READ_DIALOG_MESSAGE"))
    )
}

private fun Hooks.hideTopicsInChatListRows(topicsControllerClass: Class<*>, ensureStore: () -> Boolean) {
    val buildLayout = TgDeobf.dialogCellBuildLayout(lpparam) ?: return
    val messageField = TgDeobf.dialogCellMessage(buildLayout) ?: run {
        log("Chat list row message field not found — forum previews keep hidden topics", "ERROR")
        return
    }
    val messageObjectClass = loadClass("org.telegram.messenger.MessageObject") ?: return
    val messageClass = loadClass("org.telegram.tgnet.TLRPC\$Message") ?: return
    val controllerClass = loadClass("org.telegram.messenger.MessagesController") ?: return
    val messageObjectConstructor = runCatching {
        messageObjectClass.getConstructor(
            Int::class.javaPrimitiveType, messageClass,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        )
    }.getOrElse {
        log("MessageObject constructor not found — forum previews keep hidden topics", "ERROR")
        return
    }

    // Topic top message (TLRPC.Message) -> MessageObject shown in its place.
    val messageObjects = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    // The row's album messages: an ArrayList field holding the shown message.
    var groupField: Field? = null

    fun visibleTopMessage(account: Int, chatId: Long): Any? {
        val controller = XposedHelpers.callStaticMethod(controllerClass, "getInstance", account)
        val topics = XposedHelpers.callMethod(controller, "getTopicsController")
            ?.let { XposedHelpers.callMethod(it, "getTopics", chatId) } as? List<*> ?: return null
        // getTopics is already filtered by the hook above.
        val topic = topics.filterNotNull()
            .filter { XposedHelpers.getObjectField(it, "topMessage") != null }
            .maxByOrNull { XposedHelpers.getIntField(XposedHelpers.getObjectField(it, "topMessage"), "date") }
            ?: return null
        val owner = XposedHelpers.getObjectField(topic, "topMessage")
        return messageObjects.getOrPut(owner) {
            messageObjectConstructor.newInstance(account, owner, false, false)
        }
    }

    XposedBridge.hookMethod(buildLayout, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val cell = param.thisObject ?: return
            val message = messageField.get(cell) ?: return
            runCatching {
                val dialogId = XposedHelpers.callMethod(message, "getDialogId") as Long
                if (dialogId >= 0) return
                val chatId = -dialogId
                if (!ensureStore() || !HiddenTopics.hasHidden(chatId)) return
                val account = XposedHelpers.getIntField(message, "currentAccount")
                val owner = XposedHelpers.getObjectField(message, "messageOwner") ?: return
                val topicId = (XposedHelpers.callStaticMethod(
                    messageObjectClass, "getTopicId", account, owner, true
                ) as Number).toInt()
                if (!HiddenTopics.isHidden(chatId, topicId)) return

                val replacement = visibleTopMessage(account, chatId) ?: return
                val albumField = groupField ?: buildLayout.declaringClass.declaredFields.firstOrNull { field ->
                    field.type == ArrayList::class.java && run {
                        field.isAccessible = true
                        (field.get(cell) as? ArrayList<*>)?.any { it === message } == true
                    }
                }?.also { groupField = it }
                messageField.set(cell, replacement)
                albumField?.set(cell, null)
            }.onFailure {
                log("Failed to hide topic in chat list row: ${it.message}", "DEBUG")
            }
        }
    })
}

private const val TAG_HIDE_BUTTON = "killergramneo_hide_topics"
private const val TAG_SHOW_BUTTON = "killergramneo_show_hidden_topics"

/**
 * Adds (once) and refreshes the two buttons in the topics list selection action mode.
 * The action mode menu is found through the selection counter: a kept
 * org.telegram.ui.Components.NumberTextView that Telegram adds to that menu. The buttons
 * copy the look of a neighbouring action item, so no obfuscated menu API is needed.
 */
private fun updateSelectionButtons(fragment: Any, chatId: Long, loader: ClassLoader) {
    val counter = fieldsOf(fragment.javaClass)
        .firstOrNull { it.type.name == "org.telegram.ui.Components.NumberTextView" }
        ?.apply { isAccessible = true }
        ?.get(fragment) as? View ?: run {
            log("Topic selection: no selection counter in ${fragment.javaClass.name}", "DEBUG")
            return
        }
    val menu = counter.parent as? ViewGroup ?: run {
        log("Topic selection: selection counter is not in a menu", "DEBUG")
        return
    }
    val ru = isRussian(menu.context)

    val hideButton = menu.findViewWithTag(TAG_HIDE_BUTTON)
        ?: addActionButton(menu, TAG_HIDE_BUTTON, "msg_archive_hide",
            if (ru) "Скрыть топики" else "Hide topics")
        ?: run {
            log("Topic selection: could not add hide button", "DEBUG")
            return
        }
    val showButton = menu.findViewWithTag(TAG_SHOW_BUTTON)
        ?: addActionButton(menu, TAG_SHOW_BUTTON, "msg_archive_show",
            if (ru) "Показать скрытые топики" else "Show hidden topics") ?: return

    // Leave the selection before reloading the list: the reload drops the selection on
    // its own, after which the screen's back handling would close it instead.
    hideButton.setOnClickListener {
        val selected = selectedTopicIds(fragment)
        selected.forEach { HiddenTopics.hide(chatId, it) }
        leaveSelectionMode(fragment)
        notifyTopicsChanged(loader, chatId)
    }
    showButton.visibility = if (HiddenTopics.countHidden(chatId) > 0) View.VISIBLE else View.GONE
    showButton.setOnClickListener {
        HiddenTopics.unhideAll(chatId)
        leaveSelectionMode(fragment)
        notifyTopicsChanged(loader, chatId)
    }
}

private fun addActionButton(menu: ViewGroup, tag: String, iconName: String, label: String): View? {
    val context = menu.context
    val iconId = context.resources.getIdentifier(iconName, "drawable", context.packageName)
    if (iconId == 0) return null

    // Template: the first action item that wraps an icon (an ActionBarMenuItem).
    val template = (0 until menu.childCount).map(menu::getChildAt).firstOrNull { child ->
        child is ViewGroup && (0 until child.childCount).any { child.getChildAt(it) is ImageView }
    } as? ViewGroup
    val templateIcon = template?.let { t ->
        (0 until t.childCount).map(t::getChildAt).firstOrNull { it is ImageView } as? ImageView
    }

    val button = ImageView(context).apply {
        this.tag = tag
        contentDescription = label
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(iconId)
        templateIcon?.colorFilter?.let { colorFilter = it }
        background = template?.background?.constantState?.newDrawable()?.mutate()
        setOnLongClickListener {
            android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show()
            true
        }
    }
    val params = (template?.layoutParams as? LinearLayout.LayoutParams)
        ?.let { LinearLayout.LayoutParams(it) }
        ?: LinearLayout.LayoutParams(
            (54 * context.resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    // Keep the overflow ("⋮") item last.
    menu.addView(button, (menu.childCount - 1).coerceAtLeast(0), params)
    return button
}

/**
 * Fields of [cls] and its superclasses. Telegram opens the topics list through subclasses
 * of the list screen (e.g. from the chat list), so its fields may be inherited.
 */
private fun fieldsOf(cls: Class<*>): List<java.lang.reflect.Field> =
    generateSequence(cls) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .toList()

@Suppress("UNCHECKED_CAST")
private fun selectedTopicIds(fragment: Any): List<Int> {
    for (field in fieldsOf(fragment.javaClass)) {
        if (!Set::class.java.isAssignableFrom(field.type)) continue
        field.isAccessible = true
        val set = field.get(fragment) as? Set<Any?> ?: continue
        if (set.isNotEmpty() && set.all { it is Int }) return set.map { it as Int }
    }
    return emptyList()
}

/**
 * Clears the selection through the topics screen's own back handling, which only clears
 * the selection while topics are selected. Once the selection is empty (Telegram drops it
 * when the topic list reloads), a back press would close the screen or the whole app, so
 * nothing is done then.
 */
private fun leaveSelectionMode(fragment: Any) {
    if (selectedTopicIds(fragment).isEmpty()) return
    runCatching { XposedHelpers.callMethod(fragment, "onBackPressed", true) }
        .onFailure { log("Failed to leave topic selection: ${it.message}", "DEBUG") }
}

private fun findTopicIn(callback: Any, topicClass: Class<*>): Any? {
    val fields = topicFieldsByClass.getOrPut(callback.javaClass) {
        generateSequence(callback.javaClass) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field ->
                !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    (topicClass.isAssignableFrom(field.type) || field.type == Any::class.java)
            }
            .onEach { it.isAccessible = true }
            .toList()
    }
    return fields.firstNotNullOfOrNull { field ->
        runCatching { field.get(callback) }.getOrNull()?.takeIf { topicClass.isInstance(it) }
    }
}

/** TopicsController.findTopic(chatId, topicId); the topic id is a long on newer builds. */
private fun findTopic(controller: Any, chatId: Long, topicId: Int): Any? =
    runCatching { XposedHelpers.callMethod(controller, "findTopic", chatId, topicId.toLong()) }
        .recoverCatching { XposedHelpers.callMethod(controller, "findTopic", chatId, topicId) }
        .getOrNull()

/** MessagesController.isDialogMuted(dialogId, topicId); the topic id is a long on newer builds. */
private fun isTopicMuted(controller: Any?, chatId: Long, topicId: Int): Boolean =
    runCatching { XposedHelpers.callMethod(controller, "isDialogMuted", -chatId, topicId.toLong()) as Boolean }
        .recoverCatching { XposedHelpers.callMethod(controller, "isDialogMuted", -chatId, topicId) as Boolean }
        .getOrDefault(false)

private fun chatIdFromTopMessage(topic: Any): Long? = runCatching {
    val message = XposedHelpers.getObjectField(topic, "topMessage") ?: return null
    val peer = XposedHelpers.getObjectField(message, "peer_id") ?: return null
    XposedHelpers.getLongField(peer, "channel_id").takeIf { it != 0L }
}.getOrNull()

private fun notifyTopicsChanged(loader: ClassLoader, chatId: Long) {
    runCatching {
        val userConfig = XposedHelpers.findClass("org.telegram.messenger.UserConfig", loader)
        val account = XposedHelpers.getStaticIntField(userConfig, "selectedAccount")
        val nc = XposedHelpers.findClass("org.telegram.messenger.NotificationCenter", loader)
        val center = XposedHelpers.callStaticMethod(nc, "getInstance", account)
        val event = XposedHelpers.getStaticIntField(nc, "topicsDidLoaded")
        XposedHelpers.callMethod(center, "postNotificationNameOnUIThread", event, arrayOf<Any>(chatId, true))
        recountUnreadChats(loader, account)
    }.onFailure {
        log("Failed to refresh topics UI: ${it.message}", "ERROR")
    }
}

private fun drawableId(context: Context, vararg names: String): Int =
    names.firstNotNullOfOrNull { name ->
        context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
    } ?: 0

private fun isRussian(context: Context): Boolean =
    context.resources.configuration.locales[0].language == "ru"
