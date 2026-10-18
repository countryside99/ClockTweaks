package io.github.clocktweaks.hook.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.clocktweaks.hook.Main
import io.github.clocktweaks.hook.alarm.AlarmAccess
import io.github.clocktweaks.hook.alarm.BatchAlarmEngine
import io.github.clocktweaks.hook.alarm.BatchPreview
import io.github.clocktweaks.hook.alarm.BatchRequest
import io.github.clocktweaks.hook.alarm.BatchResult
import io.github.clocktweaks.hook.store.BatchSet
import io.github.clocktweaks.hook.store.BatchStore
import io.github.libxposed.api.XposedModule
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.util.Calendar

/** Dialogs shown from the Clock app's overflow menu. Built with framework widgets only. */
object ClockUi {

    private const val TAG = Main.TAG
    private const val LABEL_MAX_LENGTH = 40

    private val STEP_MINUTES = intArrayOf(1, 2, 5, 10, 15, 30, 60)
    private val STEP_LABELS = arrayOf(
        "1 minute",
        "2 minutes",
        "5 minutes",
        "10 minutes",
        "15 minutes",
        "30 minutes",
        "60 minutes",
    )

    // ------------------------------------------------------------------ create dialog

    fun showCreateDialog(module: XposedModule, activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        module.log(Log.DEBUG, TAG, "showing create dialog")

        var startMin = defaultStart()
        var endMin = (startMin + 30) % (24 * 60)
        var stepIndex = 0
        var updatePreview: () -> Unit = {}

        val weekStart = AlarmAccess.weekStartIndex(activity)
        val chosen = BooleanArray(8) // index = Calendar.DAY_OF_WEEK, 1=Sunday..7=Saturday
        fun currentDaysMask(): Int {
            var mask = 0
            for (calDay in 1..7) if (chosen[calDay]) mask = mask or AlarmAccess.dayFlag(calDay)
            return mask
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 4))
        }

        var startText: TextView? = null
        val startValue = valueView(activity) {
            TimePickerDialog(
                activity,
                { _, hour, minute ->
                    startMin = hour * 60 + minute
                    startText?.text = formatTime(activity, startMin)
                    updatePreview()
                },
                startMin / 60,
                startMin % 60,
                android.text.format.DateFormat.is24HourFormat(activity),
            ).show()
        }.apply { text = formatTime(activity, startMin) }

        var endText: TextView? = null
        val endValue = valueView(activity) {
            TimePickerDialog(
                activity,
                { _, hour, minute ->
                    endMin = hour * 60 + minute
                    endText?.text = formatTime(activity, endMin)
                    updatePreview()
                },
                endMin / 60,
                endMin % 60,
                android.text.format.DateFormat.is24HourFormat(activity),
            ).show()
        }.apply { text = formatTime(activity, endMin) }

        startText = startValue
        endText = endValue
        root.addView(labelRow(activity, "Start", startValue))
        root.addView(labelRow(activity, "End", endValue))

        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                STEP_LABELS,
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (stepIndex != position) {
                        stepIndex = position
                        updatePreview()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        root.addView(labelRow(activity, "Every", spinner))

        root.addView(
            TextView(activity).apply {
                text = "Repeat on"
                textSize = 16f
                setPadding(0, dp(activity, 8), 0, dp(activity, 4))
            },
        )

        val symbols = DateFormatSymbols.getInstance()
        val chipViews = LinkedHashMap<Int, TextView>() // Calendar.DAY_OF_WEEK -> chip
        val chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (position in 0 until 7) {
            val calDay = ((weekStart + position) % 7) + 1
            val chip = TextView(activity).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                text = symbols.shortWeekdays[calDay].take(1).uppercase()
                contentDescription = symbols.weekdays[calDay]
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    chosen[calDay] = !chosen[calDay]
                    applyChipStyle(this, chosen[calDay])
                    updatePreview()
                }
            }
            applyChipStyle(chip, false)
            chipViews[calDay] = chip
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(activity, 40), 1f).apply {
                    if (position < 6) marginEnd = dp(activity, 4)
                },
            )
        }
        root.addView(chipRow)

        val presetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 6), 0, 0)
        }
        fun applyPreset(days: List<Int>) {
            for (calDay in 1..7) chosen[calDay] = calDay in days
            for ((calDay, chip) in chipViews) applyChipStyle(chip, chosen[calDay])
            updatePreview()
        }
        val presets = listOf(
            "Every day" to (1..7).toList(),
            "Weekdays" to listOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
            ),
            "Weekend" to listOf(Calendar.SATURDAY, Calendar.SUNDAY),
        )
        for ((index, preset) in presets.withIndex()) {
            presetRow.addView(
                TextView(activity).apply {
                    text = preset.first
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(accentColor(activity))
                    setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { applyPreset(preset.second) }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(activity, 6)
                },
            )
        }
        root.addView(presetRow)

        val nameInput = EditText(activity).apply {
            hint = "Label (optional)"
            filters = arrayOf(InputFilter.LengthFilter(LABEL_MAX_LENGTH))
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(activity, 8) }
        }
        root.addView(nameInput)

        val skipExisting = CheckBox(activity).apply {
            text = "Skip times that already have an alarm"
            isChecked = true
            setPadding(0, dp(activity, 8), 0, 0)
        }
        root.addView(skipExisting)

        val replaceSame = CheckBox(activity).apply {
            text = "Delete the previous set with the same label first"
            isChecked = false
            setPadding(0, dp(activity, 6), 0, 0)
        }
        root.addView(replaceSame)

        val previewView = TextView(activity).apply {
            textSize = 14f
            setPadding(0, dp(activity, 12), 0, 0)
        }
        root.addView(previewView)

        fun buildRequest() = BatchRequest(
            name = nameInput.text.toString().trim(),
            startMin = startMin,
            endMin = endMin,
            stepMin = STEP_MINUTES[stepIndex],
            skipExisting = skipExisting.isChecked,
            replaceSameName = replaceSame.isChecked,
            daysMask = currentDaysMask(),
        )

        fun refreshPreview() {
            val preview = try {
                BatchAlarmEngine.preview(activity, buildRequest())
            } catch (t: Throwable) {
                AlarmAccess.logError("preview failed", t)
                null
            }
            val days = describeDays(activity, currentDaysMask())
            previewView.text = buildString {
                append(describePreview(preview))
                if (days != null) append(" \u00b7 repeats ").append(days)
            }
        }

        updatePreview = { refreshPreview() }

        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreview()
        })
        skipExisting.setOnCheckedChangeListener { _, _ -> updatePreview() }
        replaceSame.setOnCheckedChangeListener { _, _ -> updatePreview() }
        refreshPreview()

        val scroll = ScrollView(activity).apply { addView(root) }
        AlertDialog.Builder(activity)
            .setTitle("Create wake-up range")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                startCreate(activity, buildRequest())
            }
            .show()
    }

    private fun startCreate(activity: Activity, request: BatchRequest) {
        val appContext = activity.applicationContext
        val estimated = BatchAlarmEngine.timesInRange(
            request.startMin,
            request.endMin,
            request.stepMin,
        ).size
        Toast.makeText(appContext, "Creating up to $estimated alarms\u2026", Toast.LENGTH_SHORT).show()

        Thread {
            val result = try {
                BatchAlarmEngine.create(appContext, request, BatchStore(appContext))
            } catch (t: Throwable) {
                AlarmAccess.logError("batch creation failed", t)
                BatchResult(0, 0, 0, 0, 0, "Batch creation failed: ${t.message}")
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, describeResult(result), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun describePreview(preview: BatchPreview?): String {
        if (preview == null) return "Preview unavailable"
        preview.error?.let { return it }
        val builder = StringBuilder("Creates ${preview.toCreate} alarm(s)")
        if (preview.skipped > 0) builder.append(" \u00b7 skips ${preview.skipped} existing")
        if (preview.limited > 0) builder.append(" \u00b7 ${preview.limited} over the 100 alarm limit")
        builder.append(" \u00b7 ${preview.capacity} free slots")
        return builder.toString()
    }

    private fun describeResult(result: BatchResult): String {
        result.error?.let { return it }
        if (result.created == 0 && result.replaced == 0) return "Nothing to create"
        val builder = StringBuilder("Created ${result.created} alarm(s)")
        if (result.skipped > 0) builder.append(", skipped ${result.skipped}")
        if (result.failed > 0) builder.append(", ${result.failed} failed")
        if (result.limited > 0) builder.append(", ${result.limited} over the limit")
        if (result.replaced > 0) builder.append(", replaced ${result.replaced}")
        return builder.append(".").toString()
    }

    // ------------------------------------------------------------------ manage dialog

    fun showManageDialog(module: XposedModule, activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        module.log(Log.DEBUG, TAG, "showing manage dialog")

        val store = BatchStore(activity)
        val sets = store.load()

        if (sets.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("Manage wake-up sets")
                .setMessage("No wake-up sets created yet.")
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val labels = sets.map { describeSet(activity, it) }.toTypedArray()
        val checked = BooleanArray(sets.size)

        AlertDialog.Builder(activity)
            .setTitle("Manage wake-up sets")
            .setMultiChoiceItems(
                labels,
                checked,
                { _: DialogInterface, which: Int, isChecked: Boolean -> checked[which] = isChecked },
            )
            .setNegativeButton("Close", null)
            .setPositiveButton("Delete selected") { _, _ ->
                val selected = sets.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(activity, "No set selected", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                startDelete(activity, store, selected) {
                    showManageDialog(module, activity)
                }
            }
            .show()
    }

    private fun startDelete(
        activity: Activity,
        store: BatchStore,
        selected: List<BatchSet>,
        onDone: () -> Unit,
    ) {
        val appContext = activity.applicationContext
        val ids = selected.flatMap { it.ids }
        Thread {
            var deleted = 0
            if (ids.isNotEmpty()) {
                deleted = AlarmAccess.deleteAlarms(appContext, ids)
                if (deleted > 0) AlarmAccess.enableNextAlert(appContext)
            }
            if (deleted >= 0) store.remove(selected)
            Handler(Looper.getMainLooper()).post {
                val message = if (deleted >= 0) {
                    "Deleted ${deleted} alarm(s) from ${selected.size} set(s)"
                } else {
                    "Deleting alarms failed"
                }
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                if (deleted >= 0) onDone()
            }
        }.start()
    }

    private fun describeSet(context: Context, set: BatchSet): String {
        val name = set.name.ifEmpty { "(no label)" }
        val range =
            "${formatTime(context, set.startMin)} \u2013 ${formatTime(context, set.endMin)}" +
                " \u00b7 every ${set.stepMin} min"
        val days = describeDays(context, set.daysMask)?.let { " \u00b7 repeats $it" } ?: ""
        val count =
            if (set.ids.isEmpty()) "\u00b7 no alarms left" else "\u00b7 ${set.ids.size} alarms"
        return "$name\n$range$days $count"
    }

    // ------------------------------------------------------------------ shared helpers

    /** One circular day chip in the create dialog. */
    private fun applyChipStyle(chip: TextView, selected: Boolean) {
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (selected) {
                setColor(accentColor(chip.context))
            } else {
                setColor(0x00000000)
                setStroke(dp(chip.context, 2), 0xFFBDBDBD.toInt())
            }
        }
        chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF757575.toInt())
    }

    /**
     * Human readable summary of an un-shifted day mask in the week's display order:
     * null when empty, "every day" for all seven, otherwise short letters like "Mon, Wed".
     */
    private fun describeDays(context: Context, mask: Int): String? {
        if (mask == 0) return null
        if (mask == AlarmAccess.allDaysMask()) return "every day"
        val symbols = DateFormatSymbols.getInstance()
        val weekStart = AlarmAccess.weekStartIndex(context)
        val letters = mutableListOf<String>()
        for (position in 0 until 7) {
            val calDay = ((weekStart + position) % 7) + 1
            if (mask and AlarmAccess.dayFlag(calDay) != 0) {
                letters += symbols.shortWeekdays[calDay].take(1).uppercase()
            }
        }
        return if (letters.isEmpty()) null else letters.joinToString(", ")
    }

    private fun defaultStart(): Int {
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return (((nowMinutes / 30) + 1) * 30) % (24 * 60)
    }

    private fun formatTime(context: Context, minutesOfDay: Int): String {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
        calendar.set(Calendar.MINUTE, minutesOfDay % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val format: DateFormat = android.text.format.DateFormat.getTimeFormat(context)
        return format.format(calendar.time)
    }

    private fun labelRow(context: Context, label: String, value: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(value)
        }

    private fun valueView(context: Context, onClick: () -> Unit): TextView =
        TextView(context).apply {
            textSize = 16f
            gravity = Gravity.END
            setTextColor(accentColor(context))
            setPadding(dp(context, 8), dp(context, 6), dp(context, 4), dp(context, 6))
            val attributes = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    attributes,
                    true,
                ) && attributes.resourceId != 0
            ) {
                setBackgroundResource(attributes.resourceId)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun accentColor(context: Context): Int {
        val attributes = TypedValue()
        return if (
            context.theme.resolveAttribute(android.R.attr.colorAccent, attributes, true) &&
            attributes.resourceId != 0
        ) {
            context.getColor(attributes.resourceId)
        } else {
            0xFF2196F3.toInt()
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
