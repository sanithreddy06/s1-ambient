package com.s1ambient

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

internal data class AmbientAlarm(val id: String, val time: LocalTime, var enabled: Boolean, var next: Long = 0)

/** All access is on the main thread, including commands marshalled from the HTTP worker. */
internal class AmbientState(val context: Context) {
    private val prefs = context.getSharedPreferences("ambient", Context.MODE_PRIVATE)
    val listeners = mutableSetOf<() -> Unit>()
    val tasks = TaskStore(context)
    val time = TimeState()
    val alarms = mutableListOf<AmbientAlarm>()
    val scheduler = AlarmScheduler(context)
    var port = prefs.getInt("port", 8080); private set
    var remoteEnabled = prefs.getBoolean("remote", true); private set
    var pairingCode = prefs.getString("code", null) ?: secret(5); private set
    var accessToken = prefs.getString("token", null) ?: secret(32); private set
    var customSound = prefs.getBoolean("customSound", false); private set
    var serverInfo = if (remoteEnabled) "Waiting for Wi-Fi" else "Remote disabled"; private set
    var remoteUrl = ""; private set
    var ringing = prefs.getString("ringing", "") ?: ""; private set
    var audioIssue = ""; private set
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    init {
        prefs.edit().putString("code", pairingCode).putString("token", accessToken).apply()
        runCatching { JSONArray(prefs.getString("alarms", "[]")) }.getOrDefault(JSONArray()).let { array ->
            for (i in 0 until array.length()) runCatching {
                val item = array.getJSONObject(i)
                alarms.add(AmbientAlarm(item.getString("id"), LocalTime.parse(item.getString("time")), item.getBoolean("enabled"), item.optLong("next", 0)))
            }
        }
        runCatching {
            val saved = JSONObject(prefs.getString("timing", "{}") ?: "{}")
            time.duration = saved.optLong("duration", 300_000).coerceIn(1000, 86_400_000)
            time.timerRemaining = saved.optLong("remaining", time.duration).coerceIn(0, time.duration)
            time.timerStarted = saved.optLong("timerStarted", 0)
            time.timerMode = saved.optString("mode", "idle")
            time.watchAccumulated = saved.optLong("watchAccumulated", 0).coerceAtLeast(0)
            time.watchStarted = saved.optLong("watchStarted", 0)
            time.watchRunning = saved.optBoolean("watchRunning", false)
            if (saved.optInt("boot", -2) != boot) {
                // A monotonic clock resets at reboot. Recover timer deadline from wall time;
                // pause stopwatch at its last saved value rather than inventing elapsed time.
                if (time.timerMode == "running") {
                    time.timerRemaining = (saved.optLong("endWall", 0) - System.currentTimeMillis()).coerceIn(0, time.duration)
                    time.timerStarted = SystemClock.elapsedRealtime()
                }
                time.watchAccumulated = saved.optLong("watchSnapshot", time.watchAccumulated)
                time.watchRunning = false
            }
        }
        tasks.onChanged = { changed() }
    }

    fun changed() { listeners.toList().forEach { it() } }
    fun serverStatus(info: String, url: String = "") {
        if (serverInfo == info && remoteUrl == url) return
        serverInfo = info; remoteUrl = url; changed()
    }
    fun audioStatus(issue: String) { if (audioIssue != issue) { audioIssue = issue; changed() } }
    fun setPort(value: Int) {
        require(value in 1024..65535) { "Port must be 1024–65535" }
        port = value; prefs.edit().putInt("port", port).apply(); changed()
    }
    fun enableRemote(value: Boolean) { remoteEnabled = value; prefs.edit().putBoolean("remote", value).apply(); changed() }
    fun renewPairing() {
        pairingCode = secret(5); accessToken = secret(32)
        prefs.edit().putString("code", pairingCode).putString("token", accessToken).apply(); changed()
    }
    fun useCustom(value: Boolean) {
        require(!value || soundFile().isFile) { "Import a sound on the S1 first" }
        customSound = value; prefs.edit().putBoolean("customSound", value).apply(); changed()
    }
    fun soundFile() = File(context.filesDir, "alarm-audio")
    fun addTask(title: String, dueTime: String) {
        require(tasks.tasks.size < 200) { "Delete an old task before adding more (limit 200)" }
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 120) { "Title must be 1–120 characters" }
        tasks.add(clean, LocalDate.now().atTime(LocalTime.parse(dueTime).withSecond(0).withNano(0)))
    }
    fun taskChange(id: String, completed: Boolean? = null, delete: Boolean = false) {
        val task = tasks.tasks.find { it.id == id } ?: throw NoSuchElementException("Task not found")
        if (delete) tasks.tasks.remove(task) else task.completed = requireNotNull(completed)
        tasks.save()
    }
    fun timer(action: String, seconds: Long? = null) {
        if (action in listOf("start", "resume")) scheduler.requireExact()
        // Catch a deadline crossed just before an incoming pause/reset request.
        checkTimer()
        time.timer(action, SystemClock.elapsedRealtime(), seconds)
        if (action in listOf("set", "start", "reset", "stop") && ringing == "Timer complete") dismiss()
        saveTime(); scheduler.timer(time); changed()
    }
    fun stopwatch(action: String) { time.stopwatch(action, SystemClock.elapsedRealtime()); saveTime(); changed() }
    fun checkTimer() {
        if (time.finish(SystemClock.elapsedRealtime())) {
            saveTime(); ring("Timer complete"); scheduler.timer(time)
        }
    }
    fun addAlarm(value: String) {
        scheduler.requireExact()
        require(alarms.size < 32) { "Alarm limit is 32" }
        val alarm = AmbientAlarm(UUID.randomUUID().toString(), LocalTime.parse(value).withSecond(0).withNano(0), true)
        scheduler.schedule(alarm)
        alarms.add(alarm); saveAlarms(); changed()
    }
    fun alarmChange(id: String, enabled: Boolean? = null, delete: Boolean = false) {
        val alarm = alarms.find { it.id == id } ?: throw NoSuchElementException("Alarm not found")
        if (!delete && enabled == true) scheduler.requireExact()
        scheduler.cancel(alarm.id)
        if (delete) alarms.remove(alarm) else {
            alarm.enabled = requireNotNull(enabled)
            if (alarm.enabled) scheduler.schedule(alarm, force = true)
        }
        saveAlarms(); changed()
    }
    fun fireAlarm(id: String, scheduledAt: Long) {
        val alarm = alarms.find { it.id == id && it.enabled } ?: return
        // Reject already consumed/stale deliveries, including a resume at the due instant.
        if (scheduledAt != alarm.next || scheduledAt > System.currentTimeMillis() + 1000) return
        // Daily local-time alarms, rescheduled individually (no repeating polling loop).
        runCatching { scheduler.schedule(alarm, force = true) }
        saveAlarms()
        ring("Alarm · ${alarm.time}")
    }
    private fun ring(message: String) {
        ringing = message
        prefs.edit().putString("ringing", message).apply()
        context.startForegroundService(android.content.Intent(context, AmbientService::class.java))
        changed()
    }
    fun dismiss() { ringing = ""; prefs.edit().putString("ringing", "").apply(); changed() }
    fun reschedule(force: Boolean = false) {
        checkTimer()
        if (scheduler.canExact()) {
            alarms.filter { it.enabled }.forEach { scheduler.schedule(it, force) }
            saveAlarms()
            scheduler.timer(time)
        }
    }
    private fun saveAlarms() { prefs.edit().putString("alarms", alarmsJson().toString()).apply() }
    private fun saveTime() {
        val now = SystemClock.elapsedRealtime()
        val json = JSONObject().put("duration", time.duration).put("remaining", time.timerRemaining)
            .put("timerStarted", time.timerStarted).put("mode", time.timerMode)
            .put("watchAccumulated", time.watchAccumulated).put("watchStarted", time.watchStarted)
            .put("watchRunning", time.watchRunning).put("boot", boot).put("watchSnapshot", time.elapsed(now))
            .put("endWall", System.currentTimeMillis() + time.remaining(now))
        prefs.edit().putString("timing", json.toString()).apply()
    }
    fun tasksJson(): JSONArray = JSONArray().also { array ->
        val now = LocalDateTime.now()
        tasks.tasks.sortedWith(compareBy<LocalTask> { it.completed }.thenBy { it.due }).forEach {
            array.put(JSONObject().put("id", it.id).put("title", it.title).put("due", it.due.toString())
                .put("completed", it.completed).put("overdue", it.overdue(now)))
        }
    }
    fun alarmsJson(): JSONArray = JSONArray().also { array -> alarms.sortedBy { it.time }.forEach {
        array.put(JSONObject().put("id", it.id).put("time", it.time.toString()).put("enabled", it.enabled).put("next", it.next))
    } }
    fun timerJson() = JSONObject().put("mode", time.timerMode).put("durationMs", time.duration)
        .put("remainingMs", time.remaining(SystemClock.elapsedRealtime()))
    fun stopwatchJson() = JSONObject().put("running", time.watchRunning).put("elapsedMs", time.elapsed(SystemClock.elapsedRealtime()))
    fun status(): JSONObject {
        checkTimer()
        return JSONObject().put("tasks", tasksJson()).put("timer", timerJson()).put("stopwatch", stopwatchJson())
            .put("alarms", alarmsJson()).put("ringing", ringing).put("audioIssue", audioIssue)
            .put("customSound", customSound).put("hasCustomSound", soundFile().isFile)
            .put("exactAlarms", scheduler.canExact()).put("localDate", LocalDate.now().toString())
    }
    companion object {
        private fun secret(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
