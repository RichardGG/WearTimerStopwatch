package com.richardgg.weartimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

private const val CHANNEL = "timer_alerts"
private const val EXTRA_ID = "id"
private const val EXTRA_NAME = "name"

/** Schedules and shows "time's up" alerts, so they work while the app is in the background. */
object Alerts {
    // Timers that already alerted, so the alarm and the in-app ticker don't both fire.
    private val fired = ConcurrentHashMap.newKeySet<Int>()

    private fun alarmIntent(ctx: Context, id: Int, name: String? = null): PendingIntent {
        val intent = Intent(ctx, TimerAlarmReceiver::class.java)
        if (name != null) intent.putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)
        return PendingIntent.getBroadcast(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Wake the watch at [triggerAtMs] (wall clock) to alert for timer [id]. */
    fun schedule(ctx: Context, id: Int, name: String, triggerAtMs: Long) {
        fired.remove(id)
        val am = ctx.getSystemService(AlarmManager::class.java)
        // setAlarmClock is exact, survives Doze and needs no special permission.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMs, openApp(ctx)), alarmIntent(ctx, id, name))
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx, id))
    }

    fun clearNotifications(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
    }

    /** Post the vibrating "time's up" notification (once per run of a timer). */
    fun fire(ctx: Context, id: Int, name: String) {
        if (!fired.add(id)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Timer alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500, 250, 500)
            },
        )
        nm.notify(
            id,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(name)
                .setContentText("Time's up")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build(),
        )
    }
}

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alerts.fire(context, intent.getIntExtra(EXTRA_ID, 0), intent.getStringExtra(EXTRA_NAME) ?: "Timer")
    }
}
