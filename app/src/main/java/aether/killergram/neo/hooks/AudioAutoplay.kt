package aether.killergram.neo.hooks

import aether.killergram.neo.core.TgDeobf
import aether.killergram.neo.log
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

/**
 * A volume key press in a chat makes Telegram start the visible video with sound
 * (ChatActivity.maybePlayVisibleVideo, called from LaunchActivity.dispatchKeyEvent).
 * Report "nothing played" so the key only changes the volume.
 */
fun Hooks.killAutoAudio() {
    log("Disabling auto-enable audio on vol+/-...")

    val playVisibleVideo = TgDeobf.volumeKeyVideoPlayback(lpparam) ?: return
    XposedBridge.hookMethod(playVisibleVideo, XC_MethodReplacement.returnConstant(false))
}
