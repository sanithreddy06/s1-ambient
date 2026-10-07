package com.s1ambient

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal class TaskPanel(private val activity: Activity, private val scale: Float, private val state: AmbientState) {
    private val store = state.tasks
    private var palette = AmbientAppearance.day
    private var signature = ""
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val heading = TextView(activity)
    private val add = TextView(activity)
    private var dialog: AlertDialog? = null
    private var picker: TimePickerDialog? = null
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    init {
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.text = activity.getString(R.string.tasks_title)
        heading.textSize = 11 * scale
        heading.letterSpacing = 0.16f
        header.addView(heading, LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.gravity = Gravity.CENTER_VERTICAL
        add.setText(R.string.add_task)
        add.textSize = 14 * scale
        add.gravity = Gravity.CENTER
        add.setPadding(dp(12), 0, dp(12), 0)
        add.setOnClickListener { addTask() }
        header.addView(add, LinearLayout.LayoutParams(-2, dp(48)))
        view.addView(header)
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                    minOf(dp((100 * scale).toInt()), View.MeasureSpec.getSize(heightMeasureSpec)), View.MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(rows)
        view.addView(scroll)
        refresh(force = true)
    }
    fun theme(value: AmbientAppearance.Palette) {
        palette = value
        heading.setTextColor(value.muted)
        add.setTextColor(value.secondary)
        refresh(force = true)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(value.background))
        picker?.window?.setBackgroundDrawable(ColorDrawable(value.background))
    }
    fun close() { picker?.dismiss(); dialog?.dismiss() }
    fun refresh(force: Boolean = false) {
        val now = LocalDateTime.now()
        val key = store.tasks.joinToString { "${it.id}:${it.completed}:${it.overdue(now)}" } + now.toLocalDate()
        if (!force && key == signature) return
        signature = key
        rows.removeAllViews()
        if (store.tasks.isEmpty()) {
            rows.addView(TextView(activity).apply {
                text = activity.getString(R.string.tasks_empty)
                textSize = 18 * scale
                gravity = Gravity.CENTER
                setTextColor(palette.secondary)
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        store.tasks.sortedWith(compareBy<LocalTask> { it.completed }.thenBy { it.due }).forEach { task ->
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val toggle = CheckBox(activity).apply {
                buttonDrawable = activity.getDrawable(R.drawable.task_check)
                isChecked = task.completed
                contentDescription = "Complete ${task.title}"
                buttonTintList = android.content.res.ColorStateList.valueOf(if (task.completed) palette.muted else palette.secondary)
                setOnCheckedChangeListener { _, checked -> task.completed = checked; store.save(); refresh() }
            }
            row.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(TextView(activity).apply {
                text = task.title
                textSize = 16 * scale
                setTextColor(if (task.completed) palette.muted else palette.secondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (task.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setOnClickListener { showDetails(task) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(activity).apply {
                val old = task.due.toLocalDate() != now.toLocalDate()
                val dueLabel = if (old) activity.getString(R.string.due_past_day,
                    task.due.format(timeFormat), task.due.format(DateTimeFormatter.ofPattern("d MMM")))
                    else task.due.format(timeFormat)
                text = if (task.overdue(now)) activity.getString(R.string.due_overdue, dueLabel) else dueLabel
                textSize = 11 * scale
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(if (task.overdue(now)) palette.primary else palette.muted)
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(dp((148 * scale).toInt()), -2))
            row.addView(TextView(activity).apply {
                text = "×"
                textSize = 24 * scale
                gravity = Gravity.CENTER
                setTextColor(palette.muted)
                contentDescription = "Delete ${task.title}"
                setOnClickListener { showDetails(task) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            rows.addView(row)
        }
    }
    private fun showDetails(task: LocalTask) {
        dialog = AlertDialog.Builder(activity, R.style.AmbientDialog).setTitle(task.title)
            .setMessage("Due ${task.due.format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))}")
            .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                store.tasks.remove(task); store.save(); refresh()
            }.create()
        showDialog()
    }
    private fun showDialog() {
        dialog?.show()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(palette.background))
        dialog?.window?.attributes = dialog?.window?.attributes?.apply {
            screenBrightness = if (AmbientAppearance.isNight(LocalTime.now().hour)) 0.04f else -1f
        }
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(palette.primary)
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.secondary)
    }
    private fun addTask() {
        var dueTime = LocalTime.now().plusHours(1).withSecond(0).withNano(0)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val title = EditText(activity).apply {
            hint = "Task title"
            setTextColor(palette.primary)
            setHintTextColor(palette.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 2
            filters = arrayOf(InputFilter.LengthFilter(120))
        }
        content.addView(title)
        val time = TextView(activity).apply {
            text = activity.getString(R.string.due_today, dueTime.format(timeFormat))
            textSize = 16f
            setTextColor(palette.secondary)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            setOnClickListener {
                picker = TimePickerDialog(activity, R.style.AmbientDialog, { _, hour, minute ->
                    dueTime = LocalTime.of(hour, minute)
                    text = activity.getString(R.string.due_today, dueTime.format(timeFormat))
                }, dueTime.hour, dueTime.minute, false)
                picker?.show()
                picker?.window?.setBackgroundDrawable(ColorDrawable(palette.background))
                picker?.window?.attributes = picker?.window?.attributes?.apply {
                    screenBrightness = if (AmbientAppearance.isNight(LocalTime.now().hour)) 0.04f else -1f
                }
            }
        }
        content.addView(time)
        dialog = AlertDialog.Builder(activity, R.style.AmbientDialog).setTitle("Add task").setView(content)
            .setNegativeButton("Cancel", null).setPositiveButton("Add", null).create()
        showDialog()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val value = title.text.toString().trim()
            if (value.isEmpty()) { title.error = "Enter a title" }
            else {
                try {
                    state.addTask(value, dueTime.toString())
                    refresh()
                    dialog?.dismiss()
                } catch (e: Exception) { title.error = e.message }
            }
        }
    }
}
