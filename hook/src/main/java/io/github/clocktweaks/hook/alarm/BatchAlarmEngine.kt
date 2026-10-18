package io.github.clocktweaks.hook.alarm

import android.content.Context
import io.github.clocktweaks.hook.store.BatchSet
import io.github.clocktweaks.hook.store.BatchStore

data class BatchRequest(
    val name: String,
    val startMin: Int,
    val endMin: Int,
    val stepMin: Int,
    val skipExisting: Boolean,
    val replaceSameName: Boolean,
    /** Un-shifted repeat day flags (AlarmAccess.dayFlag); 0 = one-time alarms. */
    val daysMask: Int = 0,
)

data class BatchResult(
    val created: Int,
    val skipped: Int,
    val failed: Int,
    val limited: Int,
    val replaced: Int,
    val error: String? = null,
)

/** Live estimate shown in the create dialog before anything is written. */
data class BatchPreview(
    val times: Int,
    val toCreate: Int,
    val skipped: Int,
    val limited: Int,
    val capacity: Int,
    val error: String? = null,
)

object BatchAlarmEngine {

    /**
     * Every minute from [startMin] to [endMin] inclusive, walking forward by
     * [stepMin] minutes and wrapping over midnight.
     */
    fun timesInRange(startMin: Int, endMin: Int, stepMin: Int): List<Int> {
        val start = ((startMin % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
        val end = ((endMin % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
        val step = stepMin.coerceAtLeast(1)
        val result = ArrayList<Int>()
        var current = start
        while (true) {
            result.add(current)
            if (current == end || result.size > DAY_MINUTES) break
            current = (current + step) % DAY_MINUTES
            if (current == start) break
        }
        return result
    }

    /** The "alarmtime" column value for a minute of day. */
    fun timeKey(minOfday: Int): Int = (minOfday / 60) * 100 + (minOfday % 60)

    /** Non destructive estimate for the dialog preview. */
    fun preview(context: Context, request: BatchRequest): BatchPreview {
        val times = timesInRange(request.startMin, request.endMin, request.stepMin)

        val count = AlarmAccess.alarmCount(context)
        if (count < 0) {
            return BatchPreview(times.size, 0, 0, 0, 0, "Could not read the current alarm count")
        }

        var freedByReplace = 0
        if (request.replaceSameName && request.name.isNotEmpty()) {
            freedByReplace = storeIdsOf(context, request.name)
        }
        val capacity = (AlarmAccess.MAX_ALARMS - count + freedByReplace).coerceAtLeast(0)

        val existing = AlarmAccess.existingTimes(context)
            ?: return BatchPreview(times.size, 0, 0, 0, capacity, "Could not read existing alarms")

        var toCreate = 0
        var skipped = 0
        for (min in times) {
            if (request.skipExisting && existing.contains(timeKey(min))) skipped++
            else toCreate++
        }
        val limited = (toCreate - capacity).coerceAtLeast(0)
        return BatchPreview(times.size, (toCreate - limited).coerceAtLeast(0), skipped, limited, capacity)
    }

    private fun storeIdsOf(context: Context, name: String): Int =
        try {
            BatchStore(context).find(name)?.ids?.size ?: 0
        } catch (_: Throwable) {
            0
        }

    /** Runs on a background thread. Creates the alarms and records the batch. */
    fun create(context: Context, request: BatchRequest, store: BatchStore): BatchResult {
        val times = timesInRange(request.startMin, request.endMin, request.stepMin)

        var replaced = 0
        if (request.replaceSameName && request.name.isNotEmpty()) {
            val old = store.find(request.name)
            if (old != null && old.ids.isNotEmpty()) {
                replaced = AlarmAccess.deleteAlarms(context, old.ids)
                store.remove(listOf(old))
                if (replaced > 0) {
                    AlarmAccess.enableNextAlert(context)
                    AlarmAccess.logDebug("replaced set '${request.name}': removed $replaced alarms")
                }
            }
        }

        val count = AlarmAccess.alarmCount(context)
        if (count < 0) {
            return BatchResult(0, 0, 0, times.size, replaced, "Could not read the current alarm count")
        }
        val capacity = (AlarmAccess.MAX_ALARMS - count).coerceAtLeast(0)
        if (capacity == 0) {
            return BatchResult(0, 0, 0, times.size, replaced, "The 100 alarm limit was reached")
        }

        val existing = AlarmAccess.existingTimes(context)
            ?: return BatchResult(0, 0, 0, times.size, replaced, "Could not read existing alarms")

        var created = 0
        var skipped = 0
        var failed = 0
        var limited = 0
        val newIds = ArrayList<Int>()

        for (min in times) {
            if (created >= capacity) {
                limited++
                continue
            }
            val key = timeKey(min)
            if (request.skipExisting && existing.contains(key)) {
                skipped++
                continue
            }
            val id = AlarmAccess.createAlarm(context, min / 60, min % 60, request.name, request.daysMask)
            if (id > 0) {
                created++
                newIds.add(id.toInt())
                existing.add(key)
            } else {
                failed++
            }
        }

        if (created > 0) {
            AlarmAccess.enableNextAlert(context)
            store.put(
                BatchSet(
                    name = request.name,
                    startMin = request.startMin,
                    endMin = request.endMin,
                    stepMin = request.stepMin,
                    daysMask = request.daysMask,
                    ids = newIds,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        return BatchResult(created, skipped, failed, limited, replaced)
    }

    private const val DAY_MINUTES = 24 * 60
}
