package aether.killergram.neo.hooks

import aether.killergram.neo.log
import android.os.PowerManager
import aether.killergram.neo.xposed.XC_MethodHook
import aether.killergram.neo.xposed.XposedBridge
import aether.killergram.neo.xposed.XposedHelpers

fun Hooks.disableNotificationDelay() {
    log("Disabling stock notification delay...")

    val notificationsControllerClass = loadClass("org.telegram.messenger.NotificationsController") ?: return
    if (notificationsControllerClass.declaredMethods.none { it.name == "scheduleNotificationDelay" }) {
        disableObfuscatedNotificationDelay(notificationsControllerClass)
        return
    }

    XposedBridge.hookAllMethods(
        notificationsControllerClass,
        "scheduleNotificationDelay",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                cancelPendingDelay(notificationsControllerClass, param.thisObject)
                flushDelayedNotifications(param.thisObject)
                releaseDelayWakelock(param.thisObject)
                param.result = null
            }
        }
    )
}

private fun cancelPendingDelay(notificationsControllerClass: Class<*>, controller: Any) {
    runCatching {
        val notificationsQueue = XposedHelpers.getStaticObjectField(
            notificationsControllerClass,
            "notificationsQueue"
        )
        val notificationDelayRunnable = XposedHelpers.getObjectField(
            controller,
            "notificationDelayRunnable"
        ) as? Runnable

        if (notificationsQueue != null && notificationDelayRunnable != null) {
            XposedHelpers.callMethod(notificationsQueue, "cancelRunnable", notificationDelayRunnable)
        }
    }.onFailure {
        log("Failed cancelling pending notification delay: ${it.message}", "DEBUG")
    }
}

private fun flushDelayedNotifications(controller: Any) {
    runCatching {
        val delayedPushMessages = XposedHelpers.getObjectField(
            controller,
            "delayedPushMessages"
        ) as? MutableCollection<*>

        if (!delayedPushMessages.isNullOrEmpty()) {
            XposedHelpers.callMethod(controller, "showOrUpdateNotification", true)
            delayedPushMessages.clear()
        }
    }.onFailure {
        log("Failed flushing delayed notifications: ${it.message}", "DEBUG")
    }
}

private fun releaseDelayWakelock(controller: Any) {
    runCatching {
        val wakelock = XposedHelpers.getObjectField(
            controller,
            "notificationDelayWakelock"
        ) as? PowerManager.WakeLock

        if (wakelock?.isHeld == true) {
            wakelock.release()
        }
    }.onFailure {
        log("Failed releasing notification delay wakelock: ${it.message}", "DEBUG")
    }
}

/**
 * Telegram 13.2+: scheduleNotificationDelay(onlineReason) is obfuscated. It is the
 * (boolean) method that logs "delay notification start"; it posts the controller's delay
 * runnable to the notifications queue 1–3 s later. Running that runnable right away,
 * on the same queue thread, shows the notification without the delay.
 */
private fun Hooks.disableObfuscatedNotificationDelay(controllerClass: Class<*>) {
    val schedule = method(controllerClass, "scheduleNotificationDelay", 1) {
        returnType("void")
        paramTypes("boolean")
        usingStrings("delay notification start, onlineReason = ")
    } ?: run {
        log("NotificationsController.scheduleNotificationDelay not found", "ERROR")
        return
    }
    val delayRunnable = aether.killergram.neo.core.TgDeobf.withMethodData(lpparam, schedule) { data ->
        data.usingFields
            .filter { use -> use.usingType.isRead() && use.field.declaredClassName == controllerClass.name }
            .map { it.field.getFieldInstance(lpparam.classLoader) }
            .firstOrNull { Runnable::class.java.isAssignableFrom(it.type) }
    }?.apply { isAccessible = true } ?: run {
        log("Notification delay runnable not found", "ERROR")
        return
    }

    XposedBridge.hookMethod(schedule, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val runnable = delayRunnable.get(param.thisObject) as? Runnable ?: return
            param.result = null
            runCatching { runnable.run() }.onFailure {
                log("Failed to show the delayed notification: ${it.message}", "DEBUG")
            }
        }
    })
}

