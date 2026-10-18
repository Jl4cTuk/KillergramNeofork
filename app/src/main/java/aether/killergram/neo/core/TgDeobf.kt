package aether.killergram.neo.core

import aether.killergram.neo.MainHook
import aether.killergram.neo.log
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
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

    /**
     * Resolves one method by a DexKit query, caches it and logs the outcome. The query
     * must match exactly one method; anything else counts as unresolved.
     */
    private fun cachedMethod(
        lpparam: LoadPackageParam,
        key: String,
        label: String,
        query: (DexKitBridge) -> List<MethodData>,
    ): Method? {
        if (cache.containsKey(key)) return cache[key] as Method?

        val resolved = runCatching {
            withBridge(lpparam) { bridge ->
                val matches = query(bridge)
                if (matches.size != 1) {
                    log("$label: expected 1 match, found ${matches.size}", "ERROR")
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
            log("Failed to resolve $label", "ERROR")
        }
        return resolved
    }

    /**
     * ChatMessageCell's measureTime(MessageObject): builds the time string beside the
     * bubble, including the "edited" label. It is the only cell method taking a
     * MessageObject that reads R.string.EditedMessage.
     */
    fun measureTime(lpparam: LoadPackageParam): Method? =
        cachedMethod(lpparam, KEY_MEASURE_TIME, "ChatMessageCell.measureTime") { bridge ->
            bridge.findMethod {
                matcher {
                    returnType("void")
                    paramTypes("org.telegram.messenger.MessageObject")
                    addUsingField("Lorg/telegram/messenger/R\$string;->EditedMessage:I", UsingType.Read)
                }
            }.filter { method ->
                runCatching {
                    android.view.View::class.java.isAssignableFrom(
                        XposedHelpers.findClass(method.className, lpparam.classLoader)
                    )
                }.getOrDefault(false)
            }
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
                    addUsingField("Lorg/telegram/messenger/R\$string;->MainTabsContacts:I", UsingType.Read)
                }
            }
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
     * no-arg boolean method dispatchKeyEvent calls that also calls
     * SharedConfig.setNoSoundHintShowed.
     */
    fun volumeKeyVideoPlayback(lpparam: LoadPackageParam): Method? =
        cachedMethod(lpparam, KEY_VOLUME_VIDEO, "ChatActivity.maybePlayVisibleVideo") { bridge ->
            bridge.findMethod {
                matcher {
                    returnType("boolean")
                    paramCount(0)
                    invokeMethods {
                        add {
                            declaredClass("org.telegram.messenger.SharedConfig")
                            name("setNoSoundHintShowed")
                        }
                    }
                    callerMethods {
                        add {
                            declaredClass("org.telegram.ui.LaunchActivity")
                            name("dispatchKeyEvent")
                        }
                    }
                }
            }
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
                    addUsingField("Lorg/telegram/messenger/R\$drawable;->outline_fab_story_24:I", UsingType.Read)
                }
            }
        }

    /**
     * StoriesController.hasStories(long dialogId), which drives the story rings around
     * avatars. StoriesController is the return type of the kept
     * MessagesController.getStoriesController(); hasStories is the (long) -> boolean
     * method that the kept ChatObject.hasStories(Chat) delegates to.
     */
    fun hasStories(lpparam: LoadPackageParam, storiesController: Class<*>): Method? =
        cachedMethod(lpparam, KEY_HAS_STORIES, "StoriesController.hasStories") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass(storiesController.name)
                    returnType("boolean")
                    paramTypes("long")
                    callerMethods {
                        add {
                            declaredClass("org.telegram.messenger.ChatObject")
                            name("hasStories")
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
     * ItemOptions, Telegram's generic popup menu. The class itself is found without
     * DexKit: it is the parameter type of the kept `ApplicationLoader.addItemOptions`,
     * an empty extension point Telegram calls while building the chat list "⋮" menu.
     * [addItem] is `void add(int iconRes, CharSequence text, Runnable onClick, boolean red)`.
     */
    data class ItemOptions(
        val clazz: Class<*>,
        val addItem: Method,
    )

    fun itemOptions(lpparam: LoadPackageParam): ItemOptions? {
        if (cache.containsKey(KEY_ITEM_OPTIONS)) return cache[KEY_ITEM_OPTIONS] as ItemOptions?

        val resolved = runCatching {
            val appLoader = XposedHelpers.findClass(APPLICATION_LOADER, lpparam.classLoader)
            val hookPoint = appLoader.declaredMethods.single {
                it.name == "addItemOptions" && it.parameterTypes.size == 1
            }
            val clazz = hookPoint.parameterTypes[0]
            val addSignature = arrayOf(
                Int::class.javaPrimitiveType,
                CharSequence::class.java,
                Runnable::class.java,
                Boolean::class.javaPrimitiveType,
            )
            val candidates = clazz.declaredMethods.filter {
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(addSignature)
            }

            // Two methods share this signature: the unconditional add and an
            // "add only if <flag>" variant. The chat list menu builder (the method that
            // calls addItemOptions) only uses the unconditional one, so pick that.
            val addName = when (candidates.size) {
                0 -> null
                1 -> candidates[0].name
                else -> withBridge(lpparam) { bridge ->
                    bridge.findMethod {
                        matcher {
                            declaredClass(clazz.name)
                            returnType("void")
                            paramTypes("int", "java.lang.CharSequence", "java.lang.Runnable", "boolean")
                            callerMethods {
                                add {
                                    invokeMethods {
                                        add {
                                            declaredClass(APPLICATION_LOADER)
                                            name("addItemOptions")
                                        }
                                    }
                                }
                            }
                        }
                    }.singleOrNull()?.name
                }
            } ?: return@runCatching null

            ItemOptions(clazz, candidates.first { it.name == addName }.apply { isAccessible = true })
        }.onFailure {
            log("ItemOptions resolution failed: ${it.message}", "ERROR")
        }.getOrNull()

        cache[KEY_ITEM_OPTIONS] = resolved
        if (resolved != null) {
            log("Resolved ItemOptions: ${resolved.clazz.name} add=${resolved.addItem.name}", "DEBUG")
        } else {
            log("Failed to resolve ItemOptions", "ERROR")
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
                        icons.forEach { icon ->
                            addUsingField("Lorg/telegram/messenger/R\$drawable;->$icon:I", UsingType.Read)
                        }
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
    private const val KEY_INSTANT_CAMERA = "instantCamera"
    private const val KEY_ITEM_OPTIONS = "itemOptions"
    private const val KEY_TOPICS_LIST = "topicsList"
    private const val KEY_MEASURE_TIME = "measureTime"
    private const val KEY_MAIN_TABS = "mainTabs"
    private const val KEY_HAS_STORIES = "hasStories"
    private const val KEY_DIALOGS_CREATE_VIEW = "dialogsCreateView"
    private const val KEY_VOLUME_VIDEO = "volumeVideo"
    private const val KEY_ROUND_FRAME_TIMESTAMP = "roundFrameTimestamp"
    private const val KEY_ROUND_SURFACE_INDEX = "roundSurfaceIndex"
    private const val ANCHOR_SHOW_ROUND = "InstantCamera show round camera "
    private const val APPLICATION_LOADER = "org.telegram.messenger.ApplicationLoader"
}
