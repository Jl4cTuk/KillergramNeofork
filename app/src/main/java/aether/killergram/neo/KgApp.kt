package aether.killergram.neo

import aether.killergram.neo.data.ModuleService
import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Receives the Xposed framework's service, which carries the module settings to the hooks. */
class KgApp : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        ModuleService.init(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        ModuleService.onBind(service)
    }

    override fun onServiceDied(service: XposedService) {
        ModuleService.onDied(service)
    }
}
