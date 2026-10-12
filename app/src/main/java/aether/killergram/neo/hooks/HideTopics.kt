package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import android.app.Activity
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

    // 2. Topics list screen: long-press selects topics and opens an action mode.
    //    Add "hide selected" / "show hidden" buttons to that action mode.
    TgDeobf.topicsList(lpparam)?.let { topicsList ->
        XposedBridge.hookMethod(topicsList.toggleSelection, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                val cell = param.args.getOrNull(0) ?: return
                val topic = findTopicIn(cell, topicClass) ?: return
                val chatId = topicChats[topic] ?: chatIdFromTopMessage(topic) ?: return
                if (!ensureStore()) return
                runCatching {
                    updateSelectionButtons(fragment, chatId, loader)
                }.onFailure {
                    log("Failed to add topic selection buttons: ${it.message}", "ERROR")
                }
            }
        })
    }

    // 3. Topic tabs inside a forum chat: long-press opens an ItemOptions menu.
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
                ?: return
            val id = topicId(topic) ?: return
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

private const val TAG_HIDE_BUTTON = "killergramneo_hide_topics"
private const val TAG_SHOW_BUTTON = "killergramneo_show_hidden_topics"

/**
 * Adds (once) and refreshes the two buttons in the topics list selection action mode.
 * The action mode menu is found through the selection counter: a kept
 * org.telegram.ui.Components.NumberTextView that Telegram adds to that menu. The buttons
 * copy the look of a neighbouring action item, so no obfuscated menu API is needed.
 */
private fun updateSelectionButtons(fragment: Any, chatId: Long, loader: ClassLoader) {
    val counter = fragment.javaClass.declaredFields
        .firstOrNull { it.type.name == "org.telegram.ui.Components.NumberTextView" }
        ?.apply { isAccessible = true }
        ?.get(fragment) as? View ?: return
    val menu = counter.parent as? ViewGroup ?: return
    val ru = isRussian(menu.context)

    val hideButton = menu.findViewWithTag(TAG_HIDE_BUTTON)
        ?: addActionButton(menu, TAG_HIDE_BUTTON, "msg_archive_hide",
            if (ru) "Скрыть топики" else "Hide topics") ?: return
    val showButton = menu.findViewWithTag(TAG_SHOW_BUTTON)
        ?: addActionButton(menu, TAG_SHOW_BUTTON, "msg_archive_show",
            if (ru) "Показать скрытые топики" else "Show hidden topics") ?: return

    hideButton.setOnClickListener { view ->
        val selected = selectedTopicIds(fragment)
        selected.forEach { HiddenTopics.hide(chatId, it) }
        notifyTopicsChanged(loader, chatId)
        leaveSelectionMode(view)
    }
    showButton.visibility = if (HiddenTopics.countHidden(chatId) > 0) View.VISIBLE else View.GONE
    showButton.setOnClickListener { view ->
        HiddenTopics.unhideAll(chatId)
        notifyTopicsChanged(loader, chatId)
        leaveSelectionMode(view)
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

@Suppress("UNCHECKED_CAST")
private fun selectedTopicIds(fragment: Any): List<Int> {
    for (field in fragment.javaClass.declaredFields) {
        if (!Set::class.java.isAssignableFrom(field.type)) continue
        field.isAccessible = true
        val set = field.get(fragment) as? Set<Any?> ?: continue
        if (set.isNotEmpty() && set.all { it is Int }) return set.map { it as Int }
    }
    return emptyList()
}

private fun leaveSelectionMode(view: View) {
    runCatching { (view.context as? Activity)?.onBackPressed() }
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
