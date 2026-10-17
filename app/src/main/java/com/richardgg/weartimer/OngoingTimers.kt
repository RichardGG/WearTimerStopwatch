package com.richardgg.weartimer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

private const val ONGOING_ID = 2000
private const val ONGOING_CHANNEL = "timer_ongoing"

/** One running timer or stopwatch, with its wall-clock end (timer) or start (stopwatch) time. */
class RunningInfo(val name: String, val isTimer: Boolean, val referenceMs: Long)

/**
 * Shows running timers on the watch face and in the launcher's recents as a Wear OS
 * "ongoing activity", so you can see the next one finishing and tap back into the app.
 */
object OngoingTimers {
    fun update(ctx: Context, running: List<RunningInfo>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (running.isEmpty()) {
            nm.cancel(ONGOING_ID)
            return
        }
        // Headline the countdown that finishes first; otherwise the oldest stopwatch.
        val timers = running.filter { it.isTimer }
        val lead = if (timers.isNotEmpty()) timers.minBy { it.referenceMs } else running.minBy { it.referenceMs }
        val label = if (running.size > 1) "${lead.name} +${running.size - 1}" else lead.name

        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "Running timers", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText(if (lead.isTimer) "Timer running" else "Stopwatch running")
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setContentIntent(open)

        val status = Status.Builder()
            .addTemplate("#name# #time#")
            .addPart("name", Status.TextPart(label))
            .addPart(
                "time",
                if (lead.isTimer) Status.TimerPart(lead.referenceMs) else Status.StopwatchPart(lead.referenceMs),
            )
            .build()

        OngoingActivity.Builder(ctx, ONGOING_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher)
            .setTouchIntent(open)
            .setStatus(status)
            .build()
            .apply(ctx)
        nm.notify(ONGOING_ID, builder.build())
    }
}
