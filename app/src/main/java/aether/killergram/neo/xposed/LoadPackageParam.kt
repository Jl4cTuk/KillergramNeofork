package aether.killergram.neo.xposed

import android.content.pm.ApplicationInfo

/** The hooked app: what the legacy LoadPackageParam carried and the hooks use. */
class LoadPackageParam(
    @JvmField val packageName: String,
    @JvmField val classLoader: ClassLoader,
    @JvmField val appInfo: ApplicationInfo,
)
