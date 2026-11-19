package com.larp.launcher.feature.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.larp.launcher.MainActivity
import com.larp.launcher.R

class VoiceCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundWithNotification()
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        createChannel()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val openPending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.voice_channel_name))
            .setContentText(getString(R.string.voice_channel_desc))
            .setOngoing(true)
            .setContentIntent(openPending)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.voice_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "larp_voice"
        private const val NOTIFICATION_ID = 0x1a
        const val ACTION_START = "com.larp.launcher.action.VOICE_START"
        const val ACTION_STOP = "com.larp.launcher.action.VOICE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, VoiceCallService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceCallService::class.java).setAction(ACTION_STOP))
        }
    }
}
