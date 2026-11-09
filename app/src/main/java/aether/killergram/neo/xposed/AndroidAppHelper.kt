package aether.killergram.neo.xposed

/** The current Application, as legacy AndroidAppHelper.currentApplication(). */
object AndroidAppHelper {
    @JvmStatic
    fun currentApplication(): android.app.Application? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.app.Application
    }.getOrNull()
}
