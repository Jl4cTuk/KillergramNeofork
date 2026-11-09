package aether.killergram.neo

import aether.killergram.neo.core.Feature
import aether.killergram.neo.core.Features
import aether.killergram.neo.core.PreferenceKeys
import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.hooks.Hooks
import aether.killergram.neo.hooks.cameraDefaultBack
import aether.killergram.neo.hooks.cameraHigherBitrate
import aether.killergram.neo.hooks.cameraHigherResolution
import aether.killergram.neo.hooks.cameraKeepZoom
import aether.killergram.neo.hooks.fixRoundVideoRecording
import aether.killergram.neo.hooks.forceAllowForwards
import aether.killergram.neo.hooks.forceSystemTypeface
import aether.killergram.neo.hooks.addSettingsEntry
import aether.killergram.neo.hooks.hideTopics
import aether.killergram.neo.hooks.hideChannelBottomBar
import aether.killergram.neo.hooks.hideAppUpdates
import aether.killergram.neo.hooks.hideDialogsFloatingButton
import aether.killergram.neo.hooks.hideContactsTab
import aether.killergram.neo.hooks.blockContactsSwipe
import aether.killergram.neo.hooks.hideFolderDuplicates
import aether.killergram.neo.hooks.hidePaidStarReactions
import aether.killergram.neo.hooks.hidePostShareButton
import aether.killergram.neo.hooks.showProfileUserId
import aether.killergram.neo.hooks.replaceEditedLabelWithIcon
import aether.killergram.neo.hooks.keepDeletedMessages
import aether.killergram.neo.hooks.killAutoAudio
import aether.killergram.neo.hooks.killSponsoredMessages
import aether.killergram.neo.hooks.killStories
import aether.killergram.neo.hooks.localPremium
import aether.killergram.neo.hooks.noRounding
import aether.killergram.neo.hooks.defaultHdMediaSending
import aether.killergram.neo.hooks.disableNotificationDelay
import aether.killergram.neo.hooks.disableAttachCameraPreview
import aether.killergram.neo.hooks.replaceAppTitle
import aether.killergram.neo.hooks.unlimitedRecents
import aether.killergram.neo.hooks.showTimestampSeconds
import aether.killergram.neo.hooks.videoNoteSizeGuard
import aether.killergram.neo.xposed.LoadPackageParam
import aether.killergram.neo.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam


/**
 * The module entry (libxposed API 102, listed in META-INF/xposed/java_init.list). Hooks
 * are installed once the target app's class loader is ready, before its Application is
 * created. Each tweak is a [Feature] that follows its setting while the app runs.
 */
class MainHook : XposedModule() {
    private val packageBlocklist = arrayOf(  // Apparently sometimes LSPosed will just hook us into random crap like GMS or ourselves
            BuildConfig.APPLICATION_ID,      // This is supposed to solve this problem
            "com.google.android.gms"
        )

    companion object {
        /** Path to this module's APK; used to load native libs. */
        @JvmStatic
        var modulePath: String? = null
            private set
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedBridge.framework = this
        modulePath = moduleApplicationInfo.sourceDir
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || packageBlocklist.contains(param.packageName)) {
            aether.killergram.neo.log("Aborting load for ${param.packageName}", "DEBUG")
            return
        }
        val lpparam = LoadPackageParam(param.packageName, param.classLoader, param.applicationInfo)
        val prefs = PreferencesUtils().getPrefsInstance()

        aether.killergram.neo.log("Injecting for ${lpparam.packageName}", "DEBUG")
        val hooks = Hooks(lpparam)

        fun intValue(key: String, default: Int) = { prefs.getInt(key, default) }
        fun choice(key: String, default: String) = {
            prefs.getStringSet(key, setOf(default))?.firstOrNull() ?: default
        }
        fun switch(key: String, install: () -> Unit) = Feature(key, install)

        val stickersLimit = intValue(PreferenceKeys.RECENT_STICKERS_LIMIT, 120)
        val emojiLimit = intValue(PreferenceKeys.RECENT_EMOJI_LIMIT, 120)
        val roundBitrate = { choice(PreferenceKeys.CAMERA_BITRATE_VALUE, "1200")().toIntOrNull() ?: 1200 }
        val roundResolution = { choice(PreferenceKeys.CAMERA_RESOLUTION_VALUE, "512")().toIntOrNull() ?: 512 }
        val titleMode = choice(PreferenceKeys.APP_TITLE_MODE, "firstname")
        val titleText = { prefs.getString(PreferenceKeys.APP_TITLE_CUSTOM_TEXT, "") ?: "" }
        val centerTitle = { prefs.getBoolean(PreferenceKeys.APP_TITLE_CENTER, false) }

        val features = listOf(
            switch(PreferenceKeys.FORCE_LOCAL_PREMIUM) { hooks.localPremium() },
            switch(PreferenceKeys.REMOVE_SPONSORED) { hooks.killSponsoredMessages() },
            switch(PreferenceKeys.FORCE_FORWARD) { hooks.forceAllowForwards() },
            switch(PreferenceKeys.HIDE_STORIES) { hooks.killStories() },
            switch(PreferenceKeys.HIDE_TOPICS) { hooks.hideTopics() },
            switch(PreferenceKeys.EDITED_ICON) { hooks.replaceEditedLabelWithIcon() },
            switch(PreferenceKeys.SHOW_SECONDS) { hooks.showTimestampSeconds() },
            switch(PreferenceKeys.SHOW_USER_ID) { hooks.showProfileUserId() },
            switch(PreferenceKeys.DEFAULT_HD_MEDIA) { hooks.defaultHdMediaSending() },
            switch(PreferenceKeys.DISABLE_ATTACH_CAMERA_PREVIEW) { hooks.disableAttachCameraPreview() },
            switch(PreferenceKeys.HIDE_DIALOGS_FAB) { hooks.hideDialogsFloatingButton() },
            switch(PreferenceKeys.HIDE_PAID_STAR_REACTIONS) { hooks.hidePaidStarReactions() },
            switch(PreferenceKeys.HIDE_CHANNEL_BOTTOM_BAR) { hooks.hideChannelBottomBar() },
            switch(PreferenceKeys.HIDE_POST_SHARE_BUTTON) { hooks.hidePostShareButton() },
            switch(PreferenceKeys.DISABLE_NOTIFICATION_DELAY) { hooks.disableNotificationDelay() },
            switch(PreferenceKeys.DISABLE_AUTO_AUDIO) { hooks.killAutoAudio() },
            switch(PreferenceKeys.HIDE_APP_UPDATES) { hooks.hideAppUpdates() },
            switch(PreferenceKeys.HIDE_CONTACTS_TAB) { hooks.hideContactsTab() },
            switch(PreferenceKeys.BLOCK_CONTACTS_SWIPE) { hooks.blockContactsSwipe() },
            switch(PreferenceKeys.HIDE_FOLDER_DUPLICATES) { hooks.hideFolderDuplicates() },
            switch(PreferenceKeys.KEEP_DELETED_MESSAGES) { hooks.keepDeletedMessages() },
            switch(PreferenceKeys.DISABLE_ROUNDING) { hooks.noRounding() },
            switch(PreferenceKeys.FORCE_SYSTEM_TYPEFACE) { hooks.forceSystemTypeface() },
            switch(PreferenceKeys.CAMERA_DEFAULT_BACK) { hooks.cameraDefaultBack() },
            switch(PreferenceKeys.CAMERA_KEEP_ZOOM) { hooks.cameraKeepZoom() },
            switch(PreferenceKeys.ROUND_VIDEO_FIX) { hooks.fixRoundVideoRecording() },
            Feature(
                PreferenceKeys.UNLIMITED_RECENTS,
                setOf(PreferenceKeys.UNLIMITED_RECENTS, PreferenceKeys.RECENT_STICKERS_LIMIT, PreferenceKeys.RECENT_EMOJI_LIMIT),
                { it.getBoolean(PreferenceKeys.UNLIMITED_RECENTS, false) },
            ) { hooks.unlimitedRecents(stickersLimit, emojiLimit) },
            Feature(
                PreferenceKeys.CAMERA_HIGHER_BITRATE,
                setOf(PreferenceKeys.CAMERA_HIGHER_BITRATE, PreferenceKeys.CAMERA_BITRATE_VALUE),
                { it.getBoolean(PreferenceKeys.CAMERA_HIGHER_BITRATE, false) },
            ) { hooks.cameraHigherBitrate(roundBitrate) },
            Feature(
                PreferenceKeys.CAMERA_HIGHER_RESOLUTION,
                setOf(PreferenceKeys.CAMERA_HIGHER_RESOLUTION, PreferenceKeys.CAMERA_RESOLUTION_VALUE),
                { it.getBoolean(PreferenceKeys.CAMERA_HIGHER_RESOLUTION, false) },
            ) { hooks.cameraHigherResolution(roundResolution) },
            // Telegram's encoder must fit the larger video notes either of them makes.
            Feature(
                "videonotesizeguard",
                setOf(PreferenceKeys.CAMERA_HIGHER_BITRATE, PreferenceKeys.CAMERA_HIGHER_RESOLUTION),
                {
                    it.getBoolean(PreferenceKeys.CAMERA_HIGHER_BITRATE, false) ||
                        it.getBoolean(PreferenceKeys.CAMERA_HIGHER_RESOLUTION, false)
                },
            ) { hooks.videoNoteSizeGuard() },
            Feature(
                PreferenceKeys.REPLACE_APP_TITLE,
                setOf(
                    PreferenceKeys.REPLACE_APP_TITLE, PreferenceKeys.APP_TITLE_MODE,
                    PreferenceKeys.APP_TITLE_CUSTOM_TEXT, PreferenceKeys.APP_TITLE_CENTER,
                ),
                { it.getBoolean(PreferenceKeys.REPLACE_APP_TITLE, false) },
            ) { hooks.replaceAppTitle(titleMode, titleText, centerTitle) },
        )

        // Always-on entry point to the module settings from inside Telegram, so the
        // launcher icon can be hidden.
        hooks.addSettingsEntry()

        // Tweaks that are on get their hooks now; the rest when they are first turned on.
        Features.start(prefs, features) { TgDeobf.release() }
    }
}
