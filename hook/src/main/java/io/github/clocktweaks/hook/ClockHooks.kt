package io.github.clocktweaks.hook

import android.app.Activity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import io.github.clocktweaks.hook.alarm.AlarmAccess
import io.github.clocktweaks.hook.ui.ClockUi
import io.github.libxposed.api.XposedModule

class ClockHooks(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {

    fun install() {
        AlarmAccess.install(module, classLoader)

        val clockClass = Class.forName(CLOCK_ACTIVITY, false, classLoader)

        val onCreateMenu = clockClass.getDeclaredMethod("onCreateOptionsMenu", Menu::class.java)
        module.hook(onCreateMenu).intercept { chain ->
            val result = chain.proceed()
            (chain.args[0] as? Menu)?.let { addMenuItems(it) }
            result
        }

        val onOptionsSelected = clockClass.getDeclaredMethod(
            "onOptionsItemSelected",
            MenuItem::class.java,
        )
        module.hook(onOptionsSelected).intercept { chain ->
            val item = chain.args[0] as? MenuItem
            when (item?.itemId) {
                MENU_CREATE -> {
                    (chain.thisObject as? Activity)?.let { ClockUi.showCreateDialog(module, it) }
                    true
                }
                MENU_MANAGE -> {
                    (chain.thisObject as? Activity)?.let { ClockUi.showManageDialog(module, it) }
                    true
                }
                else -> chain.proceed()
            }
        }

        module.log(Log.INFO, TAG, "menu hooks installed on $CLOCK_ACTIVITY")
    }

    private fun addMenuItems(menu: Menu) {
        if (menu.findItem(MENU_CREATE) == null) {
            menu.add(GROUP_ID, MENU_CREATE, MENU_ORDER, "Create wake-up range")
                .setIcon(android.R.drawable.ic_menu_add)
        }
        if (menu.findItem(MENU_MANAGE) == null) {
            menu.add(GROUP_ID, MENU_MANAGE, MENU_ORDER + 1, "Manage wake-up sets")
                .setIcon(android.R.drawable.ic_menu_manage)
        }
    }

    companion object {
        private const val CLOCK_ACTIVITY = "com.sec.android.app.clockpackage.ClockPackage"
        private const val TAG = Main.TAG

        private const val GROUP_ID = 0x0C10C
        const val MENU_CREATE = 0x0C10C01
        const val MENU_MANAGE = 0x0C10C02
        private const val MENU_ORDER = 100
    }
}
