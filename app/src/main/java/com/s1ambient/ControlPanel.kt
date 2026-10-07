package com.s1ambient

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import java.time.LocalTime

/** Compact native controls. Dialogs share the same state and commands as the remote. */
internal class ControlPanel(private val activity: Activity, private val state: AmbientState,
    private val importSound: () -> Unit) {
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var palette = AmbientAppearance.day
    private val readout = TextView(activity)
    private val buttons = mutableListOf<TextView>()
    private var dialog: AlertDialog? = null
    private var picker: TimePickerDialog? = null
    private var dialogKind = ""
    private var dialogReadout: TextView? = null
    private var alarmRows: LinearLayout? = null
    private var alarmSignature = ""
    private var info: TextView? = null
    private var soundLabel: TextView? = null
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    init {
        readout.textSize = 15f
        readout.gravity = Gravity.CENTER
        readout.fontFeatureSettings = "tnum"
        readout.setPadding(0, dp(6), 0, dp(2))
        readout.setOnClickListener { if (state.ringing.isNotEmpty()) state.dismiss() else timerDialog() }
        view.addView(readout)
        val bar = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        listOf("Timer" to ::timerDialog, "Stopwatch" to ::watchDialog, "Alarms" to ::alarmsDialog,
            "S1 / Settings" to ::settingsDialog).forEach { (title, action) ->
            bar.addView(TextView(activity).apply {
                text = title; textSize = 12f; gravity = Gravity.CENTER; minHeight = dp(44)
                setPadding(dp(14), 0, dp(14), 0); setOnClickListener { action() }; buttons.add(this)
            })
        }
        view.addView(bar)
        theme(palette)
    }
    fun theme(value: AmbientAppearance.Palette) {
        palette = value
        readout.setTextColor(value.secondary)
        buttons.forEach { it.setTextColor(value.muted) }
        dialog?.window?.setBackgroundDrawable(ColorDrawable(value.background))
        dialog?.window?.attributes = dialog?.window?.attributes?.apply {
            screenBrightness = if (AmbientAppearance.isNight(LocalTime.now().hour)) 0.04f else -1f
        }
    }
    fun close() { picker?.dismiss(); dialog?.dismiss() }
    fun refresh() {
        tick()
        if (dialogKind == "alarms") renderAlarms()
        info?.text = activity.getString(R.string.remote_info, state.serverInfo, state.remoteUrl,
            state.pairingCode.chunked(5).joinToString("-"),
            if (state.scheduler.canExact()) "Exact alarm scheduling available" else "Allow Alarms & reminders below to use timers and alarms")
        soundLabel?.text = if (state.customSound) "Alarm sound: imported audio" else "Alarm sound: S1 chime"
    }
    fun tick() {
        val now = android.os.SystemClock.elapsedRealtime()
        val timer = "Timer ${format(state.time.remaining(now))} · ${state.time.timerMode}"
        val watch = "Stopwatch ${format(state.time.elapsed(now) / 1000 * 1000)}"
        val text = if (state.ringing.isNotEmpty()) "${state.ringing} · Tap to dismiss" else
            if (state.time.timerMode != "idle" || state.time.watchRunning || state.time.elapsed(now) > 0) "$timer     $watch" else ""
        if (readout.text.toString() != text) readout.text = text
        // Keep a fixed small line, so starting a timer does not move the clock.
        val detail = if (dialogKind == "timer") timer else if (dialogKind == "watch") watch else null
        if (detail != null && dialogReadout?.text.toString() != detail) dialogReadout?.text = detail
    }
    private fun content(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(12))
    }
    private fun label(parent: LinearLayout, text: String): TextView = TextView(activity).apply {
        this.text = text; textSize = 16f; setTextColor(palette.secondary)
        setPadding(0, dp(8), 0, dp(8)); parent.addView(this)
    }
    private fun action(parent: LinearLayout, text: String, click: () -> Unit): TextView = label(parent, text).apply {
        minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { attempt(click) }
    }
    private fun attempt(action: () -> Unit) {
        try { action() } catch (e: Exception) { Toast.makeText(activity, e.message ?: "Unable to apply change", Toast.LENGTH_LONG).show() }
    }
    private fun show(title: String, content: LinearLayout, kind: String) {
        dialog?.dismiss()
        dialogKind = kind
        val scroll = ScrollView(activity).apply { addView(content) }
        dialog = AlertDialog.Builder(activity, R.style.AmbientDialog).setTitle(title)
            .setView(scroll).setNegativeButton("Close", null).create().apply {
                setOnDismissListener { dialogKind = ""; dialogReadout = null; alarmRows = null; info = null; soundLabel = null }
                show()
                window?.setBackgroundDrawable(ColorDrawable(palette.background))
                window?.attributes = window?.attributes?.apply {
                    screenBrightness = if (AmbientAppearance.isNight(LocalTime.now().hour)) 0.04f else -1f
                }
            }
    }
    private fun timerDialog() {
        val body = content()
        val output = label(body, "")
        output.textSize = 28f
        val duration = EditText(activity).apply {
            hint = "Duration in seconds (1–86400)"
            setText(String.format(java.util.Locale.ROOT, "%d", state.time.duration / 1000))
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(palette.primary); setHintTextColor(palette.muted)
            contentDescription = "Timer duration in seconds"
        }
        body.addView(duration)
        action(body, "Set duration") { state.timer("set", duration.text.toString().toLongOrNull() ?: 0) }
        action(body, "Start") { state.scheduler.requireExact(); state.timer("set", duration.text.toString().toLongOrNull() ?: 0); state.timer("start") }
        action(body, "Pause / Resume") { state.timer(if (state.time.timerMode == "paused") "resume" else "pause") }
        action(body, "Reset") { state.timer("reset") }
        action(body, "Stop") { state.timer("stop") }
        action(body, "Dismiss alert") { state.dismiss() }
        show("Timer", body, "timer"); dialogReadout = output; tick()
    }
    private fun watchDialog() {
        val body = content()
        val output = label(body, "").apply { textSize = 28f }
        action(body, "Start / Resume") { state.stopwatch("start") }
        action(body, "Pause") { state.stopwatch("pause") }
        action(body, "Reset") { state.stopwatch("reset") }
        show("Stopwatch", body, "watch"); dialogReadout = output; tick()
    }
    private fun alarmsDialog() {
        val body = content()
        label(body, "Daily alarms · device local time")
        action(body, "+ Add alarm") {
            val now = LocalTime.now()
            picker = TimePickerDialog(activity, R.style.AmbientDialog, { _, hour, minute ->
                attempt { state.addAlarm(LocalTime.of(hour, minute).toString()) }
            }, now.hour, now.minute, false).apply {
                show()
                window?.attributes = window?.attributes?.apply {
                    screenBrightness = if (AmbientAppearance.isNight(LocalTime.now().hour)) 0.04f else -1f
                }
            }
        }
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rows)
        action(body, "Dismiss ringing alert") { state.dismiss() }
        show("Alarms", body, "alarms"); alarmRows = rows; alarmSignature = ""; renderAlarms()
    }
    private fun renderAlarms() {
        val rows = alarmRows ?: return
        val signature = state.alarmsJson().toString()
        if (alarmSignature == signature) return
        alarmSignature = signature; rows.removeAllViews()
        if (state.alarms.isEmpty()) label(rows, "No alarms yet")
        state.alarms.sortedBy { it.time }.forEach { alarm ->
            val line = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val toggle = Switch(activity).apply {
                text = alarm.time.toString(); setTextColor(palette.primary); textSize = 22f
                isChecked = alarm.enabled
                setOnCheckedChangeListener { _, value ->
                    attempt { state.alarmChange(alarm.id, value) }
                    isChecked = alarm.enabled
                }
            }
            line.addView(toggle, LinearLayout.LayoutParams(0, dp(56), 1f))
            val delete = TextView(activity).apply {
                setText(R.string.delete_label); setTextColor(palette.muted); gravity = Gravity.CENTER; setPadding(dp(20), 0, 0, 0)
                minHeight = dp(48); setOnClickListener { attempt { state.alarmChange(alarm.id, delete = true) } }
            }
            line.addView(delete); rows.addView(line)
        }
    }
    private fun settingsDialog() {
        val body = content()
        val information = label(body, "")
        information.setTextIsSelectable(true)
        val toggle = Switch(activity).apply {
            setText(R.string.remote_label); setTextColor(palette.secondary); isChecked = state.remoteEnabled
            setOnCheckedChangeListener { _, checked ->
                state.enableRemote(checked)
                if (checked) activity.startForegroundService(Intent(activity, AmbientService::class.java))
            }
        }
        body.addView(toggle)
        val port = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(String.format(java.util.Locale.ROOT, "%d", state.port))
            hint = "HTTP port"; setTextColor(palette.primary); contentDescription = "HTTP port"
        }
        body.addView(port)
        action(body, "Save port") { state.setPort(port.text.toString().toIntOrNull() ?: 0) }
        action(body, "Reset pairing · disconnect all phones") { state.renewPairing() }
        val sound = label(body, "")
        action(body, "Import alarm sound…") { importSound() }
        action(body, "Use S1 chime") { state.useCustom(false) }
        action(body, "Use imported audio") { state.useCustom(true) }
        action(body, "Alarm volume") {
            activity.getSystemService(android.media.AudioManager::class.java).adjustStreamVolume(
                android.media.AudioManager.STREAM_ALARM, android.media.AudioManager.ADJUST_SAME,
                android.media.AudioManager.FLAG_SHOW_UI)
        }
        if (Build.VERSION.SDK_INT >= 31) action(body, "Allow Alarms & reminders") {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
        }
        show("S1 Ambient · Settings", body, "settings"); info = information; soundLabel = sound; refresh()
    }
    companion object {
        fun format(ms: Long): String {
            val seconds = (ms.coerceAtLeast(0) + 999) / 1000
            return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        }
    }
}
