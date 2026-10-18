package io.github.clocktweaks.hook.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One batch of wake-up alarms created by the module. */
data class BatchSet(
    val name: String,
    val startMin: Int,
    val endMin: Int,
    val stepMin: Int,
    val daysMask: Int,
    val ids: List<Int>,
    val createdAt: Long,
)

/**
 * Remembers created batches in the Clock app's own preferences so they can be
 * replaced or deleted later. Stored in the Clock app process, never in system data.
 */
class BatchStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): List<BatchSet> {
        val raw = prefs.getString(KEY_SETS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<BatchSet>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val idsArray = obj.optJSONArray(KEY_IDS) ?: JSONArray()
                val ids = ArrayList<Int>(idsArray.length())
                for (j in 0 until idsArray.length()) ids.add(idsArray.getInt(j))
                result.add(
                    BatchSet(
                        name = obj.optString(KEY_NAME, ""),
                        startMin = obj.optInt(KEY_START, 0),
                        endMin = obj.optInt(KEY_END, 0),
                        stepMin = obj.optInt(KEY_STEP, 1),
                        daysMask = obj.optInt(KEY_DAYS, 0),
                        ids = ids,
                        createdAt = obj.optLong(KEY_CREATED, 0L),
                    ),
                )
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun find(name: String): BatchSet? = load().firstOrNull { it.name == name }

    /** Saves a batch, replacing any previous batch with the same label. */
    fun put(set: BatchSet) {
        val sets = load().filterNot { it.name == set.name && set.name.isNotEmpty() }.toMutableList()
        sets.add(set)
        write(sets)
    }

    fun remove(sets: Collection<BatchSet>) {
        val remaining = load().filterNot { it in sets }
        write(remaining)
    }

    private fun write(sets: List<BatchSet>) {
        val array = JSONArray()
        sets.forEach { set ->
            val obj = JSONObject()
            obj.put(KEY_NAME, set.name)
            obj.put(KEY_START, set.startMin)
            obj.put(KEY_END, set.endMin)
            obj.put(KEY_STEP, set.stepMin)
            obj.put(KEY_DAYS, set.daysMask)
            obj.put(KEY_IDS, JSONArray(set.ids))
            obj.put(KEY_CREATED, set.createdAt)
            array.put(obj)
        }
        prefs.edit().putString(KEY_SETS, array.toString()).apply()
    }

    private companion object {
        const val FILE_NAME = "clocktweaks"
        const val KEY_SETS = "sets"
        const val KEY_NAME = "name"
        const val KEY_START = "start"
        const val KEY_END = "end"
        const val KEY_STEP = "step"
        const val KEY_DAYS = "days"
        const val KEY_IDS = "ids"
        const val KEY_CREATED = "created"
    }
}
