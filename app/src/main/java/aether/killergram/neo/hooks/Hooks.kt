package aether.killergram.neo.hooks

import aether.killergram.neo.log
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam


class Hooks(val lpparam: LoadPackageParam) {
    fun loadClass(className: String, logOnFailure: Boolean = true): Class<*>? {
        // This function is here purely to log any failed class loads
        val loadedClass = XposedHelpers.findClassIfExists(className, this.lpparam.classLoader)
        if (loadedClass == null && logOnFailure) {
            log("Unable to load class $className from ${lpparam.packageName}", "ERROR")
        }
        return loadedClass
    }
}
