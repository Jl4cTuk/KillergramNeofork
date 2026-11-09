package aether.killergram.neo.core

import aether.killergram.neo.MainHook
import aether.killergram.neo.log
import aether.killergram.neo.xposed.XposedHelpers
import aether.killergram.neo.xposed.LoadPackageParam
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Runtime deobfuscation helper for Telegram 12.10.2+, whose `org.telegram.ui.*`
 * classes (and their members) are R8-obfuscated. The `org.telegram.messenger.*`
 * and `org.telegram.tgnet.*` packages keep their names, so hooks that can anchor
 * there do not need this. For UI classes we locate them by stable fingerprints
 * (string literals, signatures, cross-references) via DexKit and cache the result.
 *
 * Resolved names are the real (obfuscated) names for the installed build; they are
 * cached per process and must never be hard-coded, since R8 re-randomises them on
 * every Telegram release.
 */
object TgDeobf {

    private val cache = HashMap<String, Any?>()

    @Volatile private var nativeLoaded = false

    /**
     * Load libdexkit.so. In an LSPosed module the module's classloader native-lib
     * path is not on the host process's search path, so System.loadLibrary("dexkit")
     * usually fails. Fall back to loading the extracted .so by absolute path, found
     * under the module APK's sibling lib/<abi>/ directory.
     */
    private fun ensureNativeLoaded(): Boolean {
        if (nativeLoaded) return true
        runCatching {
            System.loadLibrary("dexkit")
            nativeLoaded = true
        }
        if (nativeLoaded) return true
        runCatching {
            val apk = MainHook.modulePath ?: return@runCatching
            val libRoot = File(File(apk).parentFile, "lib")
            val so = libRoot.walkTopDown().firstOrNull { it.name == "libdexkit.so" }
                ?: File(apk).parentFile?.let { parent ->
                    android.os.Build.SUPPORTED_ABIS.asSequence()
                        .map { File(parent, "lib/$it/libdexkit.so") }
                        .firstOrNull { it.exists() }
                }
            if (so != null && so.exists()) {
                System.load(so.absolutePath)
                nativeLoaded = true
                log("Loaded libdexkit.so from ${so.absolutePath}", "DEBUG")
            } else {
                log("libdexkit.so not found under module lib dir", "ERROR")
            }
        }.onFailure {
            log("Failed to load libdexkit.so: ${it.message}", "ERROR")
        }
        return nativeLoaded
    }

    /** InstantCameraView (round video-note camera). */
    data class InstantCamera(
        val className: String,   // e.g. org.telegram.ui.Components.o60
        val showCamera: String,  // void h(boolean) — opens/prepares the camera
        val initCamera: String,  // boolean o() — selects front/back and inits
        val frontface: String,   // boolean field (true == front camera)
    )

    // Opening a bridge indexes the whole APK, so one bridge is shared by every resolver
    // that runs while hooks are installed; MainHook closes it with release().
    private var sharedBridge: DexKitBridge? = null

    private inline fun <T> withBridge(lpparam: LoadPackageParam, block: (DexKitBridge) -> T): T? {
        if (!ensureNativeLoaded()) return null
        return try {
            val bridge = sharedBridge
                ?: DexKitBridge.create(lpparam.appInfo.sourceDir).also { sharedBridge = it }
            block(bridge)
        } catch (t: Throwable) {
            log("DexKit bridge failed: ${t.message}", "ERROR")
            null
        }
    }

    /** Closes the shared DexKit bridge once all hooks are installed. */
    fun release() {
        runCatching { sharedBridge?.close() }
        sharedBridge = null
    }

    // ---------------------------------------------------------------------------------
    // Resources. Up to Telegram 13.1 code read ids from the kept org.telegram.messenger.R
    // classes (sget, the fields are not final). Since 13.2 R8 inlines them as constants,
    // so a fingerprint matches the id number, resolved here by name.
    // ---------------------------------------------------------------------------------

    private var stringIds: Map<Int, Int>? = null
    private var packageResources: android.content.res.Resources? = null

    /** Whether code reads resource ids from R fields (Telegram 13.1 and older). */
    private fun hasRField(lpparam: LoadPackageParam, type: String, name: String): Boolean {
        val key = "rfield:$type/$name"
        if (cache.containsKey(key)) return cache[key] as Boolean
        val found = runCatching {
            XposedHelpers.findClassIfExists("org.telegram.messenger.R\$$type", lpparam.classLoader)
                ?.getDeclaredField(name) != null
        }.getOrDefault(false)
        cache[key] = found
        return found
    }

    /**
     * Resource id by name. Drawables and other packaged resources come from Telegram's
     * own Resources. Since 13.2 strings are not packaged resources: Telegram maps their
     * ids to the hash of the string key in assets/string_resource_ids.bin
     * (count, then id/key.hashCode() pairs, little endian int32).
     */
    fun resId(lpparam: LoadPackageParam, type: String, name: String): Int {
        val key = "res:$type/$name"
        if (cache.containsKey(key)) return cache[key] as Int
        val id = runCatching {
            if (type == "string") {
                stringResourceIds(lpparam)[name.hashCode()]
                    ?: resources(lpparam)?.getIdentifier(name, type, lpparam.packageName)
            } else {
                resources(lpparam)?.getIdentifier(name, type, lpparam.packageName)
            }
        }.onFailure {
            log("Resource $type/$name lookup failed: ${it.message}", "ERROR")
        }.getOrNull() ?: 0
        cache[key] = id
        if (id == 0) log("Resource $type/$name not found", "ERROR")
        return id
    }

    private fun stringResourceIds(lpparam: LoadPackageParam): Map<Int, Int> {
        stringIds?.let { return it }
        val result = HashMap<Int, Int>()
        runCatching {
            java.util.zip.ZipFile(lpparam.appInfo.sourceDir).use { zip ->
                val entry = zip.getEntry("assets/string_resource_ids.bin") ?: return@use
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val count = buffer.int
                repeat(count) {
                    val id = buffer.int
                    val hash = buffer.int
                    // Keyed by hash; a collision drops the name rather than guess.
                    if (result.containsKey(hash)) result[hash] = 0 else result[hash] = id
                }
            }
        }.onFailure {
            log("string_resource_ids.bin unreadable: ${it.message}", "DEBUG")
        }
        result.values.removeAll { it == 0 }
        stringIds = result
        return result
    }

    private fun resources(lpparam: LoadPackageParam): android.content.res.Resources? {
        packageResources?.let { return it }
        return runCatching {
            val thread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val system = thread.javaClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
            system.createPackageContext(lpparam.packageName, android.content.Context.CONTEXT_IGNORE_SECURITY)
                .resources
        }.onFailure {
            log("Telegram resources unavailable: ${it.message}", "ERROR")
        }.getOrNull()?.also { packageResources = it }
    }

    /** Matches code that reads resource [type]/[name], as an R field read or an inlined id. */
    fun org.luckypray.dexkit.query.matchers.MethodMatcher.readsRes(
        lpparam: LoadPackageParam,
        type: String,
        name: String,
    ) {
        if (hasRField(lpparam, type, name)) {
            addUsingField("Lorg/telegram/messenger/R\$$type;->$name:I", UsingType.Read)
        } else {
            addUsingNumber(resId(lpparam, type, name))
        }
    }

    // ---------------------------------------------------------------------------------
    // Since Telegram 13.2 org.telegram.messenger.* is R8-obfuscated too (classes and
    // members). Only a few names survive: BaseController's getters (getMessagesController,
    // getUserConfig, …) and its currentAccount field, ApplicationLoaderImpl (the manifest
    // Application), and the TL classes in org.telegram.tgnet with their fields.
    // A class is looked up by its real name first, so older builds keep working.
    // ---------------------------------------------------------------------------------

    private fun cachedClass(key: String, resolve: () -> Class<*>?): Class<*>? {
        if (cache.containsKey(key)) return cache[key] as Class<*>?
        val resolved = runCatching(resolve).onFailure {
            log("$key resolution failed: ${it.message}", "ERROR")
        }.getOrNull()
        cache[key] = resolved
        if (resolved != null) {
            log("Resolved $key: ${resolved.name}", "DEBUG")
        } else {
            log("Failed to resolve $key", "ERROR")
        }
        return resolved
    }

    private fun byName(lpparam: LoadPackageParam, name: String): Class<*>? =
        XposedHelpers.findClassIfExists(name, lpparam.classLoader)

    /** BaseController: the account-bound base of every messenger controller. */
    fun baseController(lpparam: LoadPackageParam): Class<*>? =
        cachedClass("BaseController") {
            byName(lpparam, "org.telegram.messenger.BaseController") ?: withBridge(lpparam) { bridge ->
                bridge.findClass {
                    searchPackages("org.telegram.messenger")
                    matcher {
                        fields { add { name("currentAccount") } }
                        methods {
                            add { name("getMessagesController"); paramCount(0) }
                            add { name("getUserConfig"); paramCount(0) }
                            add { name("getMessagesStorage"); paramCount(0) }
                        }
                    }
                }.singleOrNull()?.getInstance(lpparam.classLoader)
            }
        }

    /** A controller class by its BaseController getter, e.g. "getMessagesController". */
    private fun controller(lpparam: LoadPackageParam, getter: String, name: String): Class<*>? =
        cachedClass(name.substringAfterLast('.')) {
            byName(lpparam, name) ?: baseController(lpparam)?.getMethod(getter)?.returnType
        }

    fun messagesController(lpparam: LoadPackageParam) =
        controller(lpparam, "getMessagesController", "org.telegram.messenger.MessagesController")

    fun messagesStorage(lpparam: LoadPackageParam) =
        controller(lpparam, "getMessagesStorage", "org.telegram.messenger.MessagesStorage")

    fun userConfig(lpparam: LoadPackageParam) =
        controller(lpparam, "getUserConfig", "org.telegram.messenger.UserConfig")

    fun notificationCenter(lpparam: LoadPackageParam) =
        controller(lpparam, "getNotificationCenter", "org.telegram.messenger.NotificationCenter")

    fun notificationsController(lpparam: LoadPackageParam) =
        controller(lpparam, "getNotificationsController", "org.telegram.messenger.NotificationsController")

    fun sendMessagesHelper(lpparam: LoadPackageParam) =
        controller(lpparam, "getSendMessagesHelper", "org.telegram.messenger.SendMessagesHelper")

    fun mediaDataController(lpparam: LoadPackageParam) =
        controller(lpparam, "getMediaDataController", "org.telegram.messenger.MediaDataController")

    fun accountInstance(lpparam: LoadPackageParam) =
        controller(lpparam, "getAccountInstance", "org.telegram.messenger.AccountInstance")

    /** ApplicationLoader: the superclass of the manifest's ApplicationLoaderImpl. */
    fun applicationLoader(lpparam: LoadPackageParam): Class<*>? =
        cachedClass("ApplicationLoader") {
            byName(lpparam, "org.telegram.messenger.ApplicationLoader")
                ?: byName(lpparam, "org.telegram.messenger.ApplicationLoaderImpl")?.superclass
        }

    /** ApplicationLoader.applicationContext: its only static Context field. */
    fun applicationContext(lpparam: LoadPackageParam): android.content.Context? {
        val loader = applicationLoader(lpparam) ?: return null
        val field = runCatching { loader.getDeclaredField("applicationContext") }.getOrNull()
            ?: loader.declaredFields.singleOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.type == android.content.Context::class.java
            }
        return runCatching {
            field?.apply { isAccessible = true }?.get(null) as? android.content.Context
        }.getOrNull()
    }

    /**
     * A class that once had a kept name: [name] first, then the single class whose code
     * uses all of [strings].
     */
    fun classByStrings(lpparam: LoadPackageParam, name: String, vararg strings: String): Class<*>? =
        cachedClass(name.substringAfterLast('.')) {
            byName(lpparam, name) ?: withBridge(lpparam) { bridge ->
                bridge.findClass {
                    matcher { usingStrings(*strings) }
                }.singleOrNull()?.getInstance(lpparam.classLoader)
            }
        }

    fun messageObject(lpparam: LoadPackageParam) = classByStrings(
        lpparam, "org.telegram.messenger.MessageObject",
        "BoostingStarsGiveawayServiceUndistributedGroup",
    )

    fun localeController(lpparam: LoadPackageParam) = classByStrings(
        lpparam, "org.telegram.messenger.LocaleController",
        "getLocaleFileStrings: error, reloadLastFile = true;",
    )

    fun sharedConfig(lpparam: LoadPackageParam) = classByStrings(
        lpparam, "org.telegram.messenger.SharedConfig",
        "overrideDevicePerformanceClass",
    )

    fun topicsController(lpparam: LoadPackageParam) = classByStrings(
        lpparam, "org.telegram.messenger.TopicsController",
        "[TopicsController]: cache desynchronization",
    )

    /** A messenger class's getInstance(account): its only static (int) -> itself method. */
    fun getInstance(cls: Class<*>): Method? {
        val key = "getInstance:${cls.name}"
        if (cache.containsKey(key)) return cache[key] as Method?
        val method = cls.declaredMethods.singleOrNull {
            it.name == "getInstance" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: cls.declaredMethods.singleOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == cls &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        method?.isAccessible = true
        cache[key] = method
        if (method == null) log("${cls.name}.getInstance not found", "ERROR")
        return method
    }

    /** cls.getInstance(account). */
    fun instance(cls: Class<*>?, account: Int): Any? =
        cls?.let { getInstance(it) }?.invoke(null, account)

    /**
     * UserConfig.selectedAccount: the static int saved under the "selectedAccount" key.
     */
    fun selectedAccount(lpparam: LoadPackageParam): Int {
        val field = cache.getOrPut("selectedAccount") {
            val userConfig = userConfig(lpparam) ?: return@getOrPut null
            runCatching { userConfig.getDeclaredField("selectedAccount") }.getOrNull()
                ?: withBridge(lpparam) { bridge ->
                    bridge.findField {
                        matcher {
                            declaredClass(userConfig.name)
                            type("int")
                            modifiers(java.lang.reflect.Modifier.STATIC)
                            readMethods { add { usingStrings("selectedAccount") } }
                        }
                    }.singleOrNull()?.getFieldInstance(lpparam.classLoader)
                }.also { if (it == null) log("UserConfig.selectedAccount not found", "ERROR") }
        } as Field? ?: return 0
        field.isAccessible = true
        return field.getInt(null)
    }

    /**
     * The TopicsController members the topic hiding needs. Since Telegram 13.2
     * getForumUnreadCount fills a static int[5] instead of returning it
     * ([forumUnreadCounts]).
     */
    data class TopicsApi(
        val controller: Class<*>,
        val getTopics: Method,
        val forumUnread: Method,
        val forumUnreadCounts: Field?,
        val findTopic: Method,
        val processTopics: Method,
        val isDialogMuted: Method?,
        val getTopicsController: Method,
        val topicsDidLoaded: Field?,
        val postNotification: Method?,
    )

    fun topicsApi(lpparam: LoadPackageParam): TopicsApi? {
        if (cache.containsKey(KEY_TOPICS_API)) return cache[KEY_TOPICS_API] as TopicsApi?
        val resolved = runCatching { resolveTopicsApi(lpparam) }.onFailure {
            log("TopicsController resolution failed: ${it.message}", "ERROR")
        }.getOrNull()
        cache[KEY_TOPICS_API] = resolved
        if (resolved != null) {
            log(
                "Resolved TopicsController: getTopics=${resolved.getTopics.name} " +
                    "unread=${resolved.forumUnread.name} find=${resolved.findTopic.name} " +
                    "process=${resolved.processTopics.name} muted=${resolved.isDialogMuted?.name} " +
                    "event=${resolved.topicsDidLoaded?.name} post=${resolved.postNotification?.name}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve TopicsController members", "ERROR")
        }
        return resolved
    }

    private fun resolveTopicsApi(lpparam: LoadPackageParam): TopicsApi? {
        val tc = topicsController(lpparam) ?: return null
        val mc = messagesController(lpparam) ?: return null
        val nc = notificationCenter(lpparam)
        val topicClass = XposedHelpers.findClass("org.telegram.tgnet.TLRPC\$TL_forumTopic", lpparam.classLoader)
        val methods = tc.declaredMethods.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        fun named(name: String) = methods.singleOrNull { it.name == name }

        return withBridge(lpparam) { bridge ->
            // getTopics(chatId): the cached list; the other (long) -> ArrayList methods
            // build new lists from it.
            val listGetters = methods.filter {
                it.returnType == ArrayList::class.java &&
                    it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            }
            val getTopics = named("getTopics") ?: listGetters.singleOrNull { candidate ->
                listGetters.any { other ->
                    other != candidate && bridge.getMethodData(other)?.invokes
                        ?.any { it.name == candidate.name && it.className == tc.name } == true
                }
            } ?: return@withBridge null

            val forumUnreadData = bridge.findMethod {
                matcher {
                    declaredClass(tc.name)
                    paramTypes("long")
                    addUsingField(
                        "Lorg/telegram/tgnet/TLRPC\$TL_forumTopic;->unread_poll_votes_count:I",
                        UsingType.Read
                    )
                    invokeMethods { add { declaredClass("java.util.Arrays"); name("fill") } }
                }
            }.singleOrNull() ?: return@withBridge null
            val forumUnread = forumUnreadData.getMethodInstance(lpparam.classLoader)
            val counts = if (forumUnread.returnType == Void.TYPE) {
                tc.declaredFields.singleOrNull {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == IntArray::class.java
                } ?: return@withBridge null
            } else null
            val isDialogMuted = forumUnreadData.invokes.firstOrNull {
                it.className == mc.name && it.returnTypeName == "boolean" &&
                    it.paramTypeNames.firstOrNull() == "long"
            }?.getMethodInstance(lpparam.classLoader)

            val findTopic = named("findTopic") ?: methods.singleOrNull {
                it.returnType == topicClass && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType
            } ?: return@withBridge null

            // processTopics(chatId, topics, …, loadType): the 6-argument loaded topics handler.
            val processTopics = named("processTopics") ?: methods.singleOrNull {
                it.returnType == Void.TYPE && it.parameterTypes.size == 6 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[1] == ArrayList::class.java
            } ?: return@withBridge null

            val getTopicsController = mc.declaredMethods.singleOrNull {
                it.returnType == tc && it.parameterTypes.isEmpty()
            } ?: return@withBridge null

            // The topics loaded event: processTopics posts it with (chatId, true).
            val processData = bridge.getMethodData(processTopics)
            val event = nc?.let { center ->
                processData?.usingFields?.firstOrNull {
                    it.usingType.isRead() && it.field.declaredClassName == center.name &&
                        it.field.typeName == "int"
                }?.field?.getFieldInstance(lpparam.classLoader)
            }
            val post = nc?.let { center ->
                processData?.invokes?.firstOrNull {
                    it.className == center.name && it.paramTypeNames == listOf("int", "java.lang.Object[]")
                }?.getMethodInstance(lpparam.classLoader)
            }

            TopicsApi(
                tc,
                getTopics.apply { isAccessible = true },
                forumUnread.apply { isAccessible = true },
                counts?.apply { isAccessible = true },
                findTopic.apply { isAccessible = true },
                processTopics.apply { isAccessible = true },
                isDialogMuted?.apply { isAccessible = true },
                getTopicsController.apply { isAccessible = true },
                event?.apply { isAccessible = true },
                post?.apply { isAccessible = true },
            )
        }
    }

    /**
     * One method, by its kept [name] (older builds) and otherwise by a DexKit query that
     * must match exactly one method.
     */
    fun method(
        lpparam: LoadPackageParam,
        cls: Class<*>,
        name: String,
        paramCount: Int? = null,
        optional: Boolean = false,
        query: (DexKitBridge) -> List<MethodData>,
    ): Method? {
        val named = cls.declaredMethods.filter {
            it.name == name && (paramCount == null || it.parameterTypes.size == paramCount)
        }
        if (named.size == 1) return named.single().apply { isAccessible = true }
        return cachedMethod(lpparam, "${cls.name}.$name", "${cls.simpleName}.$name", optional, query)
    }

    /**
     * Resolves one method by a DexKit query, caches it and logs the outcome. The query
     * must match exactly one method; anything else counts as unresolved.
     */
    private fun cachedMethod(
        lpparam: LoadPackageParam,
        key: String,
        label: String,
        optional: Boolean = false,
        query: (DexKitBridge) -> List<MethodData>,
    ): Method? {
        val failure = if (optional) "DEBUG" else "ERROR"
        if (cache.containsKey(key)) return cache[key] as Method?

        val resolved = runCatching {
            withBridge(lpparam) { bridge ->
                val matches = query(bridge)
                if (matches.size != 1) {
                    log("$label: expected 1 match, found ${matches.size}", failure)
                    return@withBridge null
                }
                matches.single().getMethodInstance(lpparam.classLoader)
            }?.apply { isAccessible = true }
        }.onFailure {
            log("$label resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[key] = resolved
        if (resolved != null) {
            log("Resolved $label: ${resolved.declaringClass.name}.${resolved.name}", "DEBUG")
        } else {
            log("Failed to resolve $label", failure)
        }
        return resolved
    }

    /**
     * ChatMessageCell's measureTime(MessageObject): builds the time string beside the
     * bubble, including the "edited" label. Up to Telegram 13.1 it reads
     * R.string.EditedMessage itself; since 13.2 a static helper of the cell builds the
     * string and measureTime is the cell's (MessageObject) method that calls it.
     * [getString] are the LocaleController (int[, …]) -> String methods the label is
     * read with.
     */
    data class EditedTime(
        val measureTime: Method,
        val getString: List<Method>,
        val editedMessage: Int,
    )

    fun editedTime(lpparam: LoadPackageParam): EditedTime? {
        if (cache.containsKey(KEY_MEASURE_TIME)) return cache[KEY_MEASURE_TIME] as EditedTime?
        val messageObject = messageObject(lpparam)
        val localeController = localeController(lpparam)

        val resolved = if (messageObject == null || localeController == null) null else runCatching {
            withBridge(lpparam) { bridge ->
                fun isView(className: String) = runCatching {
                    android.view.View::class.java.isAssignableFrom(
                        XposedHelpers.findClass(className, lpparam.classLoader)
                    )
                }.getOrDefault(false)

                val readers = bridge.findMethod {
                    matcher { readsRes(lpparam, "string", "EditedMessage") }
                }.filter { messageObject.name in it.paramTypeNames }
                if (readers.isEmpty()) return@withBridge null

                val measureTime = readers.singleOrNull {
                    it.returnTypeName == "void" && it.paramTypeNames == listOf(messageObject.name) &&
                        isView(it.className)
                } ?: readers.flatMap { reader ->
                    reader.callers.filter {
                        it.returnTypeName == "void" && it.paramTypeNames == listOf(messageObject.name) &&
                            it.className == reader.className && isView(it.className)
                    }
                }.distinctBy { it.descriptor }.singleOrNull() ?: return@withBridge null

                val getString = readers.flatMap { it.invokes }
                    .filter {
                        it.className == localeController.name &&
                            it.returnTypeName == "java.lang.String" &&
                            it.paramTypeNames.firstOrNull() == "int"
                    }
                    .distinctBy { it.descriptor }
                    .map { it.getMethodInstance(lpparam.classLoader).apply { isAccessible = true } }
                if (getString.isEmpty()) return@withBridge null

                EditedTime(
                    measureTime.getMethodInstance(lpparam.classLoader).apply { isAccessible = true },
                    getString,
                    resId(lpparam, "string", "EditedMessage"),
                )
            }
        }.onFailure {
            log("ChatMessageCell.measureTime resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_MEASURE_TIME] = resolved
        if (resolved != null) {
            log(
                "Resolved ChatMessageCell.measureTime: ${resolved.measureTime.declaringClass.name}." +
                    "${resolved.measureTime.name} getString=${resolved.getString.joinToString { it.name }}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve ChatMessageCell.measureTime", "ERROR")
        }
        return resolved
    }

    /**
     * createView(Context) of the main tabs screen (Chats / Contacts / Settings / Calls /
     * Profile bottom bar, Telegram 12.2+), found by its read of R.string.MainTabsContacts.
     */
    fun mainTabsCreateView(lpparam: LoadPackageParam): Method? =
        cachedMethod(lpparam, KEY_MAIN_TABS, "main tabs createView") { bridge ->
            bridge.findMethod {
                matcher {
                    paramTypes("android.content.Context")
                    returnType("android.view.View")
                    readsRes(lpparam, "string", "MainTabsContacts")
                }
            }
        }

    /**
     * The main tabs pager (ViewPagerActivity's pager, made in its createView from
     * (ViewPagerActivity, Context)). [canScrollForward] / [canScrollBackward] decide whether
     * a horizontal swipe may leave the current tab; they ask the visible tab with
     * canParentTabsSlide(event, forward), so the forward one passes the literal true.
     */
    data class MainTabsPager(
        val canScrollForward: Method,
        val canScrollBackward: Method,
    )

    fun mainTabsPager(lpparam: LoadPackageParam): MainTabsPager? {
        if (cache.containsKey(KEY_MAIN_TABS_PAGER)) return cache[KEY_MAIN_TABS_PAGER] as MainTabsPager?

        val pagerActivity = mainTabsCreateView(lpparam)?.declaringClass?.superclass
        val resolved = pagerActivity?.let { activity ->
            runCatching {
                withBridge(lpparam) { bridge ->
                    val pagerName = bridge.findClass {
                        matcher {
                            methods {
                                add {
                                    name("<init>")
                                    paramTypes(activity.name, "android.content.Context")
                                }
                                add {
                                    returnType("boolean")
                                    paramTypes("android.view.MotionEvent")
                                }
                            }
                        }
                    }.singleOrNull()?.name ?: return@withBridge null
                    val pager = XposedHelpers.findClass(pagerName, lpparam.classLoader)
                    val canScroll = pager.declaredMethods.filter {
                        it.returnType == Boolean::class.javaPrimitiveType &&
                            it.parameterTypes.contentEquals(arrayOf(android.view.MotionEvent::class.java))
                    }
                    if (canScroll.size != 2) return@withBridge null
                    val forwardName = bridge.findMethod {
                        matcher {
                            declaredClass(pagerName)
                            returnType("boolean")
                            paramTypes("android.view.MotionEvent")
                            usingNumbers(1)
                        }
                    }.singleOrNull()?.name ?: return@withBridge null
                    MainTabsPager(
                        canScroll.single { it.name == forwardName }.apply { isAccessible = true },
                        canScroll.single { it.name != forwardName }.apply { isAccessible = true },
                    )
                }
            }.onFailure {
                log("Main tabs pager resolution failed: ${it.message}", "ERROR")
            }.getOrNull()
        }

        cache[KEY_MAIN_TABS_PAGER] = resolved
        if (resolved != null) {
            log(
                "Resolved main tabs pager: ${resolved.canScrollForward.declaringClass.name} " +
                    "forward=${resolved.canScrollForward.name} backward=${resolved.canScrollBackward.name}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve main tabs pager", "ERROR")
        }
        return resolved
    }

    /**
     * InstantCameraView's current GL surface index (0 = front session, 1 = back session
     * when both lenses are opened at once). The GL renderer flips it on a camera switch,
     * in the method that logs "GL surface flip applied: surface=".
     */
    fun roundCameraSurfaceIndex(lpparam: LoadPackageParam, cameraClass: Class<*>): Field? {
        if (cache.containsKey(KEY_ROUND_SURFACE_INDEX)) return cache[KEY_ROUND_SURFACE_INDEX] as Field?

        val resolved = runCatching {
            withBridge(lpparam) { bridge ->
                bridge.findField {
                    matcher {
                        declaredClass(cameraClass.name)
                        type("int")
                        writeMethods {
                            add { usingStrings("GL surface flip applied: surface=") }
                        }
                    }
                }.singleOrNull()?.getFieldInstance(lpparam.classLoader)
            }?.apply { isAccessible = true }
        }.onFailure {
            log("Round camera surface index resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_ROUND_SURFACE_INDEX] = resolved
        if (resolved != null) {
            log("Resolved round camera surface index: ${resolved.name}", "DEBUG")
        } else {
            log("Failed to resolve round camera surface index", "ERROR")
        }
        return resolved
    }

    /**
     * Frame-available handlers of Telegram's camera video encoders, (SurfaceTexture,
     * Integer, long), which stamp each camera frame with SurfaceTexture.getTimestamp():
     * the round-video one (obfuscated) and CameraView's video recorder (kept).
     */
    @Suppress("UNCHECKED_CAST")
    fun cameraFrameHandlers(lpparam: LoadPackageParam): List<Method> {
        if (cache.containsKey(KEY_ROUND_FRAME_TIMESTAMP)) {
            return cache[KEY_ROUND_FRAME_TIMESTAMP] as List<Method>
        }

        val resolved = runCatching {
            withBridge(lpparam) { bridge ->
                bridge.findMethod {
                    matcher {
                        returnType("void")
                        paramTypes("android.graphics.SurfaceTexture", "java.lang.Integer", "long")
                        invokeMethods {
                            add {
                                declaredClass("android.graphics.SurfaceTexture")
                                name("getTimestamp")
                            }
                        }
                    }
                }.map { it.getMethodInstance(lpparam.classLoader).apply { isAccessible = true } }
            }
        }.onFailure {
            log("Camera frame handler resolution failed: ${it.message}", "ERROR")
        }.getOrNull().orEmpty()

        cache[KEY_ROUND_FRAME_TIMESTAMP] = resolved
        if (resolved.isNotEmpty()) {
            log(
                "Resolved camera frame handlers: " +
                    resolved.joinToString { "${it.declaringClass.name}.${it.name}" },
                "DEBUG"
            )
        } else {
            log("Failed to resolve camera frame handlers", "ERROR")
        }
        return resolved
    }

    /**
     * ChatActivity.maybePlayVisibleVideo(): on a volume key press LaunchActivity's
     * dispatchKeyEvent asks the open chat to start the visible video with sound. It is the
     * only instance no-arg boolean method dispatchKeyEvent calls on a screen (the others
     * are PhotoViewer's and static helpers).
     */
    fun volumeKeyVideoPlayback(lpparam: LoadPackageParam): Method? =
        cachedMethod(lpparam, KEY_VOLUME_VIDEO, "ChatActivity.maybePlayVisibleVideo") { bridge ->
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    paramCount(0)
                    callerMethods {
                        add {
                            declaredClass("org.telegram.ui.LaunchActivity")
                            name("dispatchKeyEvent")
                        }
                    }
                }
            }.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    !it.className.startsWith("org.telegram.ui.PhotoViewer")
            }
        }

    /**
     * InstantCameraView.finishZoom(): animates the pinch zoom back to none when the pinch
     * ends. The no-arg method of the camera view that animates (ValueAnimator.ofFloat)
     * from the session's zoom range (Camera2Session.getMinZoom).
     */
    fun instantCameraFinishZoom(lpparam: LoadPackageParam, cameraClassName: String): Method? =
        cachedMethod(lpparam, KEY_FINISH_ZOOM, "InstantCameraView.finishZoom") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass(cameraClassName)
                    returnType("void")
                    paramCount(0)
                    invokeMethods {
                        add {
                            declaredClass("org.telegram.messenger.camera.Camera2Session")
                            name("getMinZoom")
                        }
                        add {
                            declaredClass("android.animation.ValueAnimator")
                            name("ofFloat")
                        }
                    }
                }
            }
        }

    /** ChatActivity, the chat screen: the class that declares maybePlayVisibleVideo. */
    fun chatActivityClass(lpparam: LoadPackageParam): Class<*>? =
        volumeKeyVideoPlayback(lpparam)?.declaringClass

    /**
     * DialogsActivity.getDialogsArray(account, dialogsType, folderId, frozen): the list a
     * chat list tab shows. Found next to createView by its signature.
     */
    fun dialogsArray(lpparam: LoadPackageParam, dialogsClass: Class<*>): Method? =
        cachedMethod(lpparam, KEY_DIALOGS_ARRAY, "DialogsActivity.getDialogsArray") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass(dialogsClass.name)
                    returnType("java.util.ArrayList")
                    paramTypes("int", "int", "int", "boolean")
                }
            }
        }

    /**
     * DialogCell.buildLayout(): lays out a chat list row from the cell's MessageObject,
     * including the Archive row's chat names and a forum row's topic names. It is the
     * no-arg method of a View holding a MessageObject that reads
     * R.drawable.msg_mini_forumarrow (the arrow between sender and topic).
     */
    fun dialogCellBuildLayout(lpparam: LoadPackageParam): Method? {
        val messageObject = messageObject(lpparam) ?: return null
        return cachedMethod(lpparam, KEY_DIALOG_CELL_BUILD_LAYOUT, "DialogCell.buildLayout") { bridge ->
            bridge.findMethod {
                matcher {
                    returnType("void")
                    paramCount(0)
                    readsRes(lpparam, "drawable", "msg_mini_forumarrow")
                }
            }.filter { method ->
                // A View with the row's MessageObject: other views draw the arrow too.
                runCatching {
                    val cls = XposedHelpers.findClass(method.className, lpparam.classLoader)
                    android.view.View::class.java.isAssignableFrom(cls) &&
                        cls.declaredFields.any {
                            it.type == messageObject && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                        }
                }.getOrDefault(false)
            }
        }
    }

    /** DialogCell's MessageObject field: the message a chat list row shows. */
    fun dialogCellMessage(lpparam: LoadPackageParam, buildLayout: Method): Field? =
        buildLayout.declaringClass.declaredFields.singleOrNull {
            it.type == messageObject(lpparam) &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }

    /**
     * ChatActivity's bottom bar. [updateBottomOverlay] is updateBottomOverlay(boolean), the
     * only (boolean) method that reads R.string.AccountFrozenBottomTitle; [inputIslandHeight]
     * is calculateInputIslandHeight(boolean), the float method that lerps between the
     * island heights; [currentChat] is the TLRPC.Chat field updateBottomOverlay reads most.
     */
    data class ChatBottomBar(
        val updateBottomOverlay: Method,
        val inputIslandHeight: Method?,
        val currentChat: Field,
    )

    fun chatBottomBar(lpparam: LoadPackageParam, chatClass: Class<*>): ChatBottomBar? {
        if (cache.containsKey(KEY_CHAT_BOTTOM_BAR)) return cache[KEY_CHAT_BOTTOM_BAR] as ChatBottomBar?

        val resolved = runCatching {
            withBridge(lpparam) { bridge ->
                val update = bridge.findMethod {
                    matcher {
                        declaredClass(chatClass.name)
                        returnType("void")
                        paramTypes("boolean")
                        readsRes(lpparam, "string", "AccountFrozenBottomTitle")
                    }
                }.singleOrNull() ?: return@withBridge null
                val currentChat = update.usingFields
                    .filter {
                        it.usingType.isRead() && it.field.declaredClassName == chatClass.name &&
                            it.field.typeName == "org.telegram.tgnet.TLRPC\$Chat"
                    }
                    .groupingBy { it.field.name }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key ?: return@withBridge null
                // The chat screen's only (boolean) -> float method.
                val islandHeight = bridge.findMethod {
                    matcher {
                        declaredClass(chatClass.name)
                        returnType("float")
                        paramTypes("boolean")
                    }
                }.singleOrNull()
                ChatBottomBar(
                    update.getMethodInstance(lpparam.classLoader).apply { isAccessible = true },
                    islandHeight?.getMethodInstance(lpparam.classLoader)?.apply { isAccessible = true },
                    XposedHelpers.findField(chatClass, currentChat),
                )
            }
        }.onFailure {
            log("Chat bottom bar resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_CHAT_BOTTOM_BAR] = resolved
        if (resolved != null) {
            log(
                "Resolved chat bottom bar: update=${resolved.updateBottomOverlay.name} " +
                    "island=${resolved.inputIslandHeight?.name} chat=${resolved.currentChat.name}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve chat bottom bar", "ERROR")
        }
        return resolved
    }

    /**
     * The profile screen's list. ProfileActivity keeps its class name but not its members;
     * its list adapter (formerly ProfileActivity.ListAdapter) is the class constructed from
     * (ProfileActivity, Context) that creates view holders from (ViewGroup, int).
     *
     * Row positions are the ProfileActivity int fields the adapter's getItemViewType reads,
     * the row count is the one its getItemCount reads, and updateRowsIds is the no-arg
     * method that writes the row count.
     */
    data class ProfileRows(
        val adapterClass: Class<*>,
        val getItemViewType: Method,
        val onBindViewHolder: Method,
        val isEnabled: Method?,
        val rowCount: Field,
        val rowFields: List<Field>,
        val updateRowsIds: Method,
    )

    fun profileRows(lpparam: LoadPackageParam, profileClass: Class<*>): ProfileRows? {
        if (cache.containsKey(KEY_PROFILE_ROWS)) return cache[KEY_PROFILE_ROWS] as ProfileRows?

        val resolved = runCatching {
            withBridge(lpparam) { bridge ->
                val adapterName = bridge.findClass {
                    matcher {
                        methods {
                            add {
                                name("<init>")
                                paramTypes(profileClass.name, "android.content.Context")
                            }
                            add { paramTypes("android.view.ViewGroup", "int") }
                        }
                    }
                }.singleOrNull()?.name ?: return@withBridge null
                val adapterClass = XposedHelpers.findClass(adapterName, lpparam.classLoader)
                val methods = adapterClass.declaredMethods.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                val getItemViewType = methods.singleOrNull {
                    it.returnType == Int::class.javaPrimitiveType &&
                        it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                } ?: return@withBridge null
                val getItemCount = methods.singleOrNull {
                    it.returnType == Int::class.javaPrimitiveType && it.parameterTypes.isEmpty()
                } ?: return@withBridge null
                val holderClass = methods.singleOrNull {
                    it.parameterTypes.contentEquals(arrayOf(android.view.ViewGroup::class.java, Int::class.javaPrimitiveType))
                }?.returnType ?: return@withBridge null
                val onBind = methods.singleOrNull {
                    it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(holderClass, Int::class.javaPrimitiveType))
                } ?: return@withBridge null
                val isEnabled = methods.singleOrNull {
                    it.returnType == Boolean::class.javaPrimitiveType &&
                        it.parameterTypes.contentEquals(arrayOf(holderClass))
                }

                fun intFieldsReadBy(method: Method) = bridge.findField {
                    matcher {
                        declaredClass(profileClass.name)
                        type("int")
                        readMethods {
                            add {
                                declaredClass(adapterName)
                                name(method.name)
                                paramCount(method.parameterTypes.size)
                                returnType(method.returnType.name)
                            }
                        }
                    }
                }.map { it.getFieldInstance(lpparam.classLoader).apply { isAccessible = true } }

                val rowCount = intFieldsReadBy(getItemCount).singleOrNull() ?: return@withBridge null
                val rowFields = intFieldsReadBy(getItemViewType).filter { it != rowCount }
                val updateRowsIds = bridge.findMethod {
                    matcher {
                        declaredClass(profileClass.name)
                        returnType("void")
                        paramCount(0)
                        addUsingField(
                            "L${profileClass.name.replace('.', '/')};->${rowCount.name}:I",
                            UsingType.Write
                        )
                    }
                }.singleOrNull()?.getMethodInstance(lpparam.classLoader) ?: return@withBridge null

                ProfileRows(
                    adapterClass,
                    getItemViewType.apply { isAccessible = true },
                    onBind.apply { isAccessible = true },
                    isEnabled?.apply { isAccessible = true },
                    rowCount,
                    rowFields,
                    updateRowsIds.apply { isAccessible = true },
                )
            }
        }.onFailure {
            log("Profile rows resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_PROFILE_ROWS] = resolved
        if (resolved != null) {
            log(
                "Resolved profile rows: adapter=${resolved.adapterClass.name} " +
                    "type=${resolved.getItemViewType.name} bind=${resolved.onBindViewHolder.name} " +
                    "rows=${resolved.rowFields.size} count=${resolved.rowCount.name} " +
                    "update=${resolved.updateRowsIds.name}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve profile rows", "ERROR")
        }
        return resolved
    }

    /**
     * DialogsActivity.createView(Context): the chat list screen, the only createView that
     * reads R.drawable.outline_fab_story_24 (the story camera floating button).
     */
    fun dialogsCreateView(lpparam: LoadPackageParam): Method? =
        cachedMethod(lpparam, KEY_DIALOGS_CREATE_VIEW, "DialogsActivity.createView") { bridge ->
            bridge.findMethod {
                matcher {
                    paramTypes("android.content.Context")
                    returnType("android.view.View")
                    readsRes(lpparam, "drawable", "outline_fab_story_24")
                }
            }
        }

    /** StoriesController, found by its log strings. */
    fun storiesController(lpparam: LoadPackageParam) = classByStrings(
        lpparam, "org.telegram.ui.Stories.StoriesController",
        "StoriesController can't apply story user == null",
    )

    /**
     * StoriesController.hasStories(long dialogId), which drives the story rings around
     * avatars: the (long) -> boolean method that checks the peer's story list for
     * emptiness (the others look for live streams, unread or uploading stories).
     */
    fun hasStories(lpparam: LoadPackageParam, storiesController: Class<*>): Method? =
        cachedMethod(lpparam, KEY_HAS_STORIES, "StoriesController.hasStories") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass(storiesController.name)
                    returnType("boolean")
                    paramTypes("long")
                    addUsingField(
                        "Lorg/telegram/tgnet/tl/TL_stories\$PeerStories;->stories:Ljava/util/ArrayList;",
                        UsingType.Read
                    )
                    invokeMethods {
                        add {
                            declaredClass("java.util.ArrayList")
                            name("isEmpty")
                        }
                    }
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    fun instantCamera(lpparam: LoadPackageParam): InstantCamera? {
        if (cache.containsKey(KEY_INSTANT_CAMERA)) return cache[KEY_INSTANT_CAMERA] as InstantCamera?

        val resolved = withBridge(lpparam) { bridge ->
            val cls = bridge.findClass {
                matcher { usingStrings(ANCHOR_SHOW_ROUND) }
            }.firstOrNull() ?: return@withBridge null

            // void h(boolean): the fresh-open path that forces front and then calls o().
            val show = bridge.findMethod {
                matcher {
                    declaredClass(cls.name)
                    returnType("void")
                    paramTypes("boolean")
                    usingStrings(ANCHOR_SHOW_ROUND)
                }
            }.firstOrNull() ?: return@withBridge null

            // boolean o(): the camera selector; iterates CameraController.getCameras().
            val init = bridge.findMethod {
                matcher {
                    declaredClass(cls.name)
                    returnType("boolean")
                    paramCount(0)
                    invokeMethods { add { name("getCameras") } }
                }
            }.firstOrNull() ?: return@withBridge null

            // The frontface flag: the boolean field written by h() and read by o().
            val field = bridge.findField {
                matcher {
                    declaredClass(cls.name)
                    type("boolean")
                    writeMethods {
                        add {
                            declaredClass(cls.name)
                            returnType("void")
                            paramTypes("boolean")
                            usingStrings(ANCHOR_SHOW_ROUND)
                        }
                    }
                    readMethods {
                        add {
                            declaredClass(cls.name)
                            returnType("boolean")
                            paramCount(0)
                            invokeMethods { add { name("getCameras") } }
                        }
                    }
                }
            }.firstOrNull() ?: return@withBridge null

            InstantCamera(
                className = cls.name,
                showCamera = show.name,
                initCamera = init.name,
                frontface = field.name,
            )
        }

        cache[KEY_INSTANT_CAMERA] = resolved
        if (resolved != null) {
            log(
                "Resolved InstantCameraView: ${resolved.className} " +
                    "show=${resolved.showCamera} init=${resolved.initCamera} " +
                    "frontface=${resolved.frontface}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve InstantCameraView via DexKit", "ERROR")
        }
        return resolved
    }

    /**
     * The chat list "⋮" menu. [builder] is the DialogsActivity method that fills it, the
     * only one reading the theme switch, New Group and Saved Messages icons. [addItem] is
     * ItemOptions' `void add(int iconRes, CharSequence text, Runnable onClick, boolean red)`,
     * the method the builder calls for those items. [savedIcon] is the Saved Messages icon,
     * the item the module entry goes after.
     */
    data class ItemOptions(
        val builder: Method,
        val addItem: Method,
        val savedIcon: Int,
    )

    fun itemOptions(lpparam: LoadPackageParam): ItemOptions? {
        if (cache.containsKey(KEY_ITEM_OPTIONS)) return cache[KEY_ITEM_OPTIONS] as ItemOptions?

        val resolved = runCatching {
            withBridge(lpparam) { bridge ->
                val builder = bridge.findMethod {
                    matcher {
                        readsRes(lpparam, "drawable", "menu_day_mode_24")
                        readsRes(lpparam, "drawable", "outline_groups_24")
                        readsRes(lpparam, "drawable", "outline_saved_24")
                    }
                }.singleOrNull() ?: return@withBridge null
                val add = builder.invokes.filter {
                    it.returnTypeName == "void" && it.paramTypeNames == listOf(
                        "int", "java.lang.CharSequence", "java.lang.Runnable", "boolean"
                    )
                }.groupingBy { it.descriptor }.eachCount().maxByOrNull { it.value }?.key
                    ?: return@withBridge null
                ItemOptions(
                    builder.getMethodInstance(lpparam.classLoader).apply { isAccessible = true },
                    bridge.getMethodData(add)!!.getMethodInstance(lpparam.classLoader)
                        .apply { isAccessible = true },
                    resId(lpparam, "drawable", "outline_saved_24"),
                )
            }
        }.onFailure {
            log("ItemOptions resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_ITEM_OPTIONS] = resolved
        if (resolved != null) {
            log(
                "Resolved chat list menu: ${resolved.builder.declaringClass.name}.${resolved.builder.name} " +
                    "add=${resolved.addItem.declaringClass.name}.${resolved.addItem.name}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve the chat list menu", "ERROR")
        }
        return resolved
    }

    /**
     * The forum topics list screen (TopicsFragment). [toggleSelection] is the method
     * that toggles a topic cell's selection and creates the selection action mode on
     * first use; it takes the tapped cell View.
     *
     * It is the only method that reads all of these drawable ids (close/restart/mark
     * read/archive hide/show action-mode items) from the kept
     * org.telegram.messenger.R$drawable, so it is found by those field reads.
     */
    data class TopicsList(
        val fragmentClass: Class<*>,
        val toggleSelection: Method,
    )

    fun topicsList(lpparam: LoadPackageParam): TopicsList? {
        if (cache.containsKey(KEY_TOPICS_LIST)) return cache[KEY_TOPICS_LIST] as TopicsList?

        val resolved = runCatching {
            // R$drawable fields are not final in Telegram's build, so code reads them
            // with sget instead of inlining constants: match on those field reads.
            val icons = listOf(
                "msg_topic_close",
                "msg_topic_restart",
                "msg_markread",
                "msg_archive_hide",
                "msg_archive_show",
            )

            val found = withBridge(lpparam) { bridge ->
                bridge.findMethod {
                    matcher {
                        paramCount(1)
                        icons.forEach { icon -> readsRes(lpparam, "drawable", icon) }
                    }
                }.singleOrNull()?.let { it.className to it.name }
            } ?: return@runCatching null

            val fragmentClass = XposedHelpers.findClass(found.first, lpparam.classLoader)
            val method = fragmentClass.declaredMethods.single {
                it.name == found.second && it.parameterTypes.size == 1
            }.apply { isAccessible = true }
            TopicsList(fragmentClass, method)
        }.onFailure {
            log("Topics list resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_TOPICS_LIST] = resolved
        if (resolved != null) {
            log(
                "Resolved topics list: ${resolved.fragmentClass.name} " +
                    "toggleSelection=${resolved.toggleSelection.name}",
                "DEBUG"
            )
        } else {
            log("Failed to resolve topics list screen", "ERROR")
        }
        return resolved
    }

    /**
     * Getter of Telegram 13's typed enum settings (the class behind
     * `round_video_last_camera`, `round_video_frame_rate`, …): `Enum get()`. The new
     * round-video recorder reads its initial lens from `round_video_last_camera` through
     * it. The settings holder is found by that key string; the setting class is the type
     * of its static fields that exposes a no-arg Enum getter.
     */
    fun enumSettingGetter(lpparam: LoadPackageParam): Method? {
        if (cache.containsKey(KEY_ENUM_SETTING)) return cache[KEY_ENUM_SETTING] as Method?

        val resolved = runCatching {
            val holderName = withBridge(lpparam) { bridge ->
                bridge.findClass {
                    matcher { usingStrings(ROUND_VIDEO_LAST_CAMERA) }
                }.singleOrNull()?.name
            } ?: return@runCatching null
            val holder = XposedHelpers.findClass(holderName, lpparam.classLoader)
            holder.declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.type }
                .distinct()
                .firstNotNullOfOrNull { type ->
                    type.declaredMethods.singleOrNull {
                        it.parameterTypes.isEmpty() && it.returnType == Enum::class.java
                    }
                }
                ?.apply { isAccessible = true }
        }.onFailure {
            log("Enum setting resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_ENUM_SETTING] = resolved
        if (resolved != null) {
            log("Resolved enum setting getter: ${resolved.declaringClass.name}.${resolved.name}", "DEBUG")
        } else {
            log("Failed to resolve enum setting getter", "ERROR")
        }
        return resolved
    }

    const val ROUND_VIDEO_LAST_CAMERA = "round_video_last_camera"

    private const val KEY_ENUM_SETTING = "enumSetting"
    private const val KEY_TOPICS_API = "topicsApi"
    private const val KEY_INSTANT_CAMERA = "instantCamera"
    private const val KEY_ITEM_OPTIONS = "itemOptions"
    private const val KEY_TOPICS_LIST = "topicsList"
    private const val KEY_MEASURE_TIME = "measureTime"
    private const val KEY_MAIN_TABS = "mainTabs"
    private const val KEY_HAS_STORIES = "hasStories"
    private const val KEY_DIALOGS_CREATE_VIEW = "dialogsCreateView"
    private const val KEY_VOLUME_VIDEO = "volumeVideo"
    private const val KEY_DIALOGS_ARRAY = "dialogsArray"
    private const val KEY_DIALOG_CELL_BUILD_LAYOUT = "dialogCellBuildLayout"
    private const val KEY_PROFILE_ROWS = "profileRows"
    private const val KEY_CHAT_BOTTOM_BAR = "chatBottomBar"
    private const val KEY_FINISH_ZOOM = "finishZoom"
    private const val KEY_MAIN_TABS_PAGER = "mainTabsPager"
    private const val KEY_ROUND_FRAME_TIMESTAMP = "roundFrameTimestamp"
    private const val KEY_ROUND_SURFACE_INDEX = "roundSurfaceIndex"
    private const val ANCHOR_SHOW_ROUND = "InstantCamera show round camera "
}
