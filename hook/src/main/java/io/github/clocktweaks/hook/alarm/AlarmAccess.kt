package io.github.clocktweaks.hook.alarm

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import io.github.clocktweaks.hook.Main
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Calendar

/**
 * Reflection-based access to the Samsung Clock app alarm APIs.
 *
 * Everything here mirrors how the app itself creates, counts and deletes alarms:
 *  - AlarmEditActivity.checkAndSaveAlarmItem / BixbyAlarmDataHandler.manageAlarmTransactionResult
 *  - AlarmMainDbRepoHelper.getAlarmCount (the 100 alarm limit check)
 *  - ContentResolver updates on AlarmProvider so Room invalidation refreshes the list UI
 */
object AlarmAccess {

    const val MAX_ALARMS = 100

    /** Low nibble of `mRepeatType` for a repeating alarm (the app's `setWeeklyAlarm`). */
    const val WEEKLY_FLAG = 0x5

    private const val ALARM_ITEM =
        "com.sec.android.app.clockpackage.alarm.model.AlarmItem"
    private const val ALARM_PROVIDER =
        "com.sec.android.app.clockpackage.alarm.model.AlarmProvider"
    private const val ALARM_MANAGER_UTIL =
        "com.sec.android.app.clockpackage.alarm.repo.AlarmManagerUtil"
    private const val ALARM_HELPER =
        "com.sec.android.app.clockpackage.alarm.repo.AlarmMainDbRepoHelper"
    private const val BG_PREFERENCE =
        "com.sec.android.app.clockpackage.alarmbackground.model.SelectedAlarmBgPreferenceManager"

    private var module: XposedModule? = null
    private var loader: ClassLoader? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        this.module = module
        this.loader = classLoader
    }

    fun logDebug(msg: String) {
        module?.log(Log.DEBUG, Main.TAG, msg)
    }

    fun logError(msg: String, t: Throwable? = null) {
        if (t != null) module?.log(Log.ERROR, Main.TAG, msg, t)
        else module?.log(Log.ERROR, Main.TAG, msg)
    }

    // ---------------------------------------------------------------- reflection helpers

    private fun clazz(name: String): Class<*> = Class.forName(
        name,
        false,
        requireNotNull(loader) { "AlarmAccess.install() not called" },
    )

    private fun findMethod(type: Class<*>, name: String, vararg params: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(name, *params)
                @Suppress("DEPRECATION")
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            } catch (t: Throwable) {
                logError("findMethod($type.$name) failed", t)
                return null
            }
        }
        return null
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                @Suppress("DEPRECATION")
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (t: Throwable) {
                logError("findField($type.$name) failed", t)
                return null
            }
        }
        return null
    }

    // cached lookups (resolved lazily, after install())

    private val alarmItemClass: Class<*> by lazy { clazz(ALARM_ITEM) }

    private val createItemMethod: Method? by lazy {
        findMethod(
            alarmItemClass,
            "createItem",
            Context::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
    }

    private val contentValuesMethod: Method? by lazy {
        findMethod(alarmItemClass, "getContentValues")
    }

    private val labelField: Field? by lazy { findField(alarmItemClass, "mAlarmName") }
    private val activeField: Field? by lazy { findField(alarmItemClass, "mActivate") }
    private val bgCategoryField: Field? by lazy { findField(alarmItemClass, "mAlarmBgCategory") }
    private val bgIdField: Field? by lazy { findField(alarmItemClass, "mAlarmBgId") }
    private val bgLayoutField: Field? by lazy { findField(alarmItemClass, "mAlarmBgLayoutId") }
    private val generateUuidMethod: Method? by lazy {
        findMethod(alarmItemClass, "generateUUId", Boolean::class.javaPrimitiveType!!)
    }

    private val providerUri: Uri? by lazy {
        try {
            val field = findField(clazz(ALARM_PROVIDER), "CONTENT_URI") ?: return@lazy null
            field.get(null) as? Uri
        } catch (t: Throwable) {
            logError("resolving AlarmProvider.CONTENT_URI failed", t)
            null
        }
    }

    private val providerGetIdMethod: Method? by lazy {
        findMethod(clazz(ALARM_PROVIDER), "getId", Uri::class.java)
    }

    private val enableNextAlertMethod: Method? by lazy {
        findMethod(clazz(ALARM_MANAGER_UTIL), "enableNextAlert", Context::class.java)
    }

    private val alarmCountMethod: Method? by lazy {
        findMethod(clazz(ALARM_HELPER), "getAlarmCount", Context::class.java)
    }

    private val bgCategoryMethod: Method? by lazy {
        findMethod(clazz(BG_PREFERENCE), "getAlarmBgCategory", Context::class.java)
    }

    private val bgIdMethod: Method? by lazy {
        findMethod(clazz(BG_PREFERENCE), "getAlarmBgId", Context::class.java, Boolean::class.javaPrimitiveType!!)
    }

    private val bgLayoutMethod: Method? by lazy {
        findMethod(clazz(BG_PREFERENCE), "getAlarmBgLayoutId", Context::class.java)
    }

    private val repeatTypeField: Field? by lazy { findField(alarmItemClass, "mRepeatType") }
    private val setSpecificDateMethod: Method? by lazy {
        findMethod(alarmItemClass, "setSpecificDate", Boolean::class.javaPrimitiveType!!)
    }
    private val calculateOriginalAlertTimeMethod: Method? by lazy {
        findMethod(alarmItemClass, "calculateOriginalAlertTime")
    }
    private val calculateFirstAlertTimeMethod: Method? by lazy {
        findMethod(alarmItemClass, "calculateFirstAlertTime", Context::class.java)
    }

    // ---------------------------------------------------------------- public API

    /**
     * Un-shifted repeat flag for a `Calendar.DAY_OF_WEEK` value (SUNDAY=1..SATURDAY=7).
     * Matches the Clock app's `Alarm.REPEAT_FLAG_*` constants: SUN=0x1000000 ... SAT=0x1.
     * A stored weekly alarm's `mRepeatType` is `(flags shl 4) or WEEKLY_FLAG`.
     */
    fun dayFlag(calDay: Int): Int = 1 shl ((7 - calDay) * 4)

    /** All seven days as an un-shifted flag mask (0x1111111). */
    fun allDaysMask(): Int {
        var mask = 0
        for (calDay in 1..7) mask = mask or dayFlag(calDay)
        return mask
    }

    /** First displayed day of week, 0=Sunday..6=Saturday, same order the Clock app uses. */
    fun weekStartIndex(context: Context): Int {
        val fromApp = try {
            val cls = clazz("com.sec.android.app.clockpackage.common.util.ClockUtilsBase")
            val companion = findField(cls, "Companion")?.get(null)
            val method = companion?.let { findMethod(it.javaClass, "getStartDayOfWeek") }
            (method?.invoke(companion) as? Int)?.minus(1)
        } catch (t: Throwable) {
            logDebug("ClockUtilsBase.getStartDayOfWeek unavailable: $t")
            null
        }
        val start = fromApp ?: Calendar.getInstance().firstDayOfWeek // SUNDAY=1..SATURDAY=7
        return ((start - 1) % 7 + 7) % 7
    }

    /** Number of alarms counted the same way the app counts them for its 100 alarm limit. */
    fun alarmCount(context: Context): Int {
        val method = alarmCountMethod
            ?: run {
                logError("getAlarmCount method not found")
                return -1
            }
        return try {
            (method.invoke(null, context) as? Int) ?: -1
        } catch (t: Throwable) {
            logError("getAlarmCount failed", t)
            -1
        }
    }

    /**
     * Existing alarm times as keys of `hour * 100 + minute` (the "alarmtime" column).
     * Returns null if the provider query failed.
     */
    fun existingTimes(context: Context): MutableSet<Int>? {
        val uri = providerUri ?: run {
            logError("AlarmProvider.CONTENT_URI not found")
            return null
        }
        return try {
            val result = mutableSetOf<Int>()
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf("alarmtime"),
                null,
                null,
                null,
            )
            cursor?.use {
                val index = it.getColumnIndexOrThrow("alarmtime")
                while (it.moveToNext()) {
                    result.add(it.getInt(index))
                }
            }
            result
        } catch (t: Throwable) {
            logError("querying existing alarm times failed", t)
            null
        }
    }

    /**
     * Creates one alarm exactly like the app does and returns its new id, or -1 on failure.
     * [daysMask] is an un-shifted day flag mask (see [dayFlag]); 0 keeps the alarm one-time.
     */
    fun createAlarm(context: Context, hour: Int, minute: Int, label: String, daysMask: Int = 0): Long {
        try {
            val createItem = createItemMethod
                ?: run {
                    logError("AlarmItem.createItem not found")
                    return -1L
                }
            val item = createItem.invoke(null, context, hour, minute)
                ?: run {
                    logError("AlarmItem.createItem returned null")
                    return -1L
                }

            if (label.isNotEmpty()) labelField?.set(item, label)
            activeField?.set(item, 1)

            if (daysMask != 0) applyRepeatDays(context, item, daysMask)

            try {
                generateUuidMethod?.invoke(item, false)
            } catch (t: Throwable) {
                logDebug("generateUUId skipped: $t")
            }

            applySavedBackground(context, item)

            val values = contentValuesMethod?.invoke(item) as? ContentValues
                ?: run {
                    logError("getContentValues failed")
                    return -1L
                }

            val uri = providerUri ?: return -1L
            val inserted = context.contentResolver.insert(uri, values)
                ?: run {
                    logError("contentResolver.insert returned null (hour=$hour minute=$minute)")
                    return -1L
                }

            val id = alarmIdOf(inserted)
            if (id <= 0L) {
                logError("could not resolve new alarm id from $inserted")
                return -1L
            }
            return id
        } catch (t: Throwable) {
            logError("createAlarm($hour:$minute) failed", t)
            return -1L
        }
    }

    /**
     * Turns the fresh one-time item into a weekly alarm and recomputes the alert time,
     * mirroring AlarmEditActivity's save order: set mRepeatType, calculateOriginalAlertTime(),
     * setSpecificDate(false), calculateFirstAlertTime(context).
     */
    private fun applyRepeatDays(context: Context, item: Any, daysMask: Int) {
        val field = repeatTypeField
            ?: run {
                logError("AlarmItemBase.mRepeatType field not found")
                return
            }
        field.set(item, (daysMask shl 4) or WEEKLY_FLAG)
        try {
            setSpecificDateMethod?.invoke(item, false)
        } catch (t: Throwable) {
            logDebug("setSpecificDate skipped: $t")
        }
        try {
            calculateOriginalAlertTimeMethod?.invoke(item)
        } catch (t: Throwable) {
            logError("calculateOriginalAlertTime failed", t)
        }
        try {
            calculateFirstAlertTimeMethod?.invoke(item, context)
        } catch (t: Throwable) {
            logError("calculateFirstAlertTime failed", t)
        }
        logDebug("weekly alarm prepared: mask=0x${Integer.toHexString(daysMask)} " +
            "repeatType=0x${Integer.toHexString((daysMask shl 4) or WEEKLY_FLAG)}")
    }

    /** Mirrors BixbyAlarmDataHandler: new alarms inherit the user's saved alarm background. */
    private fun applySavedBackground(context: Context, item: Any) {
        try {
            val category = bgCategoryMethod?.invoke(null, context) as? Int
            val bgId = bgIdMethod?.invoke(null, context, false) as? Int
            val layout = bgLayoutMethod?.invoke(null, context) as? Int
            if (category != null) bgCategoryField?.set(item, category)
            if (bgId != null) bgIdField?.set(item, bgId)
            if (layout != null) bgLayoutField?.set(item, layout)
        } catch (t: Throwable) {
            logDebug("alarm background defaults skipped: $t")
        }
    }

    private fun alarmIdOf(uri: Uri): Long {
        try {
            providerGetIdMethod?.let { method ->
                val id = method.invoke(null, uri) as? Long
                if (id != null) return id
            }
        } catch (t: Throwable) {
            logDebug("AlarmProvider.getId static lookup failed: $t")
        }
        try {
            val companion = findField(clazz(ALARM_PROVIDER), "Companion")?.get(null)
            val method = companion?.let { findMethod(it.javaClass, "getId", Uri::class.java) }
            if (companion != null && method != null) {
                return (method.invoke(companion, uri) as? Long) ?: -1L
            }
        } catch (t: Throwable) {
            logDebug("AlarmProvider.Companion.getId lookup failed: $t")
        }
        logError("no working AlarmProvider.getId found")
        return -1L
    }

    /** Schedules the next alert after alarms changed. Same call the app makes after every save. */
    fun enableNextAlert(context: Context): Boolean {
        val method = enableNextAlertMethod
            ?: run {
                logError("enableNextAlert not found")
                return false
            }
        return try {
            method.invoke(null, context)
            true
        } catch (t: Throwable) {
            logError("enableNextAlert failed", t)
            false
        }
    }

    /** Deletes alarms by id through the provider (list UI refreshes via Room invalidation). */
    fun deleteAlarms(context: Context, ids: List<Int>): Int {
        val uri = providerUri ?: return -1
        if (ids.isEmpty()) return 0
        return try {
            val selection = ids.joinToString(separator = ",", prefix = "_id IN (", postfix = ")") { "?" }
            val args = ids.map { it.toString() }.toTypedArray()
            context.contentResolver.delete(uri, selection, args)
        } catch (t: Throwable) {
            logError("deleteAlarms failed", t)
            -1
        }
    }
}
