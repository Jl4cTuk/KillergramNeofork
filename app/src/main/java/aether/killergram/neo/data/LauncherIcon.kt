package aether.killergram.neo.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Shows or hides the launcher icon by toggling the LauncherAlias activity-alias.
 * MainActivity stays reachable from Telegram's "Killergram" menu entry and from the
 * module list in Vector/LSPosed (MODULE_SETTINGS), so hiding the icon is safe.
 */
object LauncherIcon {
    private fun alias(context: Context) =
        ComponentName(context.packageName, "aether.killergram.neo.LauncherAlias")

    fun isHidden(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            },
            PackageManager.DONT_KILL_APP
        )
    }
}
