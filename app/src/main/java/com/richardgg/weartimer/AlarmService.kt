package com.richardgg.weartimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.mutableStateListOf

private const val ALARM_CHANNEL = "timer_alarm"
private const val ALARM_NOTIFICATION_ID = 1000
private const val RING_TIMEOUT_MS = 60_000L

/**
 * Rings like an alarm clock: loops the alarm sound and vibration, and shows a full-screen
 * "Time's up" screen over the lock screen until dismissed (or a minute passes).
 */
class AlarmService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_NAME = "name"

        /** Names of the timers currently ringing; read by [AlarmActivity]. */
        val ringing = mutableStateListOf<String>()

        fun start(ctx: Context, name: String) {
            ctx.startForegroundService(
                Intent(ctx, AlarmService::class.java).setAction(ACTION_START).putExtra(EXTRA_NAME, name),
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ringing.add(intent?.getStringExtra(EXTRA_NAME) ?: "Timer")
        startForeground(ALARM_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // Open the alarm screen directly. Android only allows this from the background if the app
        // has the "display over other apps" permission; otherwise the full-screen notification
        // above is the fallback.
        try {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
        if (player == null && vibrator == null) startRinging()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, RING_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Timer alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                // The service plays its own sound and vibration so they can loop.
                setSound(null, null)
                enableVibration(false)
            },
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val alarmScreen = PendingIntent.getActivity(this, 1, Intent(this, AlarmActivity::class.java), flags)
        val dismiss = PendingIntent.getService(
            this, 2, Intent(this, AlarmService::class.java).setAction(ACTION_STOP), flags,
        )
        return Notification.Builder(this, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time's up")
            .setContentText(ringing.joinToString(", "))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(alarmScreen)
            .setFullScreenIntent(alarmScreen, true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dismiss",
                    dismiss,
                ).build(),
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun startRinging() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        vibrator = getSystemService(Vibrator::class.java)?.also {
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0), attrs)
        }
        // Watches without a speaker (or without an alarm tone) still get the vibration.
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(this@AlarmService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        ringing.clear()
        super.onDestroy()
    }
}
