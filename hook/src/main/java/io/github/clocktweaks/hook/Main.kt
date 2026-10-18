package io.github.clocktweaks.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class Main : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "ClockTweaks loaded in ${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != CLOCK_PACKAGE) return
        if (!param.isFirstPackage) return
        log(Log.INFO, TAG, "installing hooks in ${param.packageName}")
        try {
            ClockHooks(this, param.classLoader).install()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "failed to install hooks", t)
        }
    }

    companion object {
        const val TAG = "ClockTweaks"
        const val CLOCK_PACKAGE = "com.sec.android.app.clockpackage"
    }
}
