package com.s1ambient

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.time.ZonedDateTime

internal class AlarmScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun canExact() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    fun requireExact() { check(canExact()) { "Allow Alarms & reminders in S1 settings first" } }
    private fun pending(id: String, scheduledAt: Long = 0): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, AlertReceiver::class.java).setAction("com.s1ambient.ALERT")
            .setData(Uri.parse("s1ambient://alert/$id")).putExtra("id", id).putExtra("scheduledAt", scheduledAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(id: String) { manager.cancel(pending(id)) }
    fun schedule(alarm: AmbientAlarm, force: Boolean = false) {
        requireExact()
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(alarm.time).atZone(now.zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(alarm.time).atZone(now.zone)
        if (force || alarm.next == 0L) alarm.next = next.toInstant().toEpochMilli()
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.next, show), pending(alarm.id, alarm.next))
    }
    fun timer(time: TimeState) {
        cancel("timer")
        if (time.timerMode == "running") {
            requireExact()
            val now = SystemClock.elapsedRealtime()
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + time.remaining(now), pending("timer"))
        }
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.s1ambient.ALERT") return
        context.startForegroundService(Intent(context, AmbientService::class.java)
            .setAction("alert").putExtra("id", intent.getStringExtra("id"))
            .putExtra("scheduledAt", intent.getLongExtra("scheduledAt", 0)))
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = (context.applicationContext as AmbientApp).state
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            state.reschedule(force = intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                context.startForegroundService(Intent(context, AmbientService::class.java))
            }
        }
    }
}
