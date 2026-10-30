package io.github.pennymorant.smsmd.forwarding

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.pennymorant.smsmd.MainActivity
import io.github.pennymorant.smsmd.R

class ForwardingService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SMS forwarding",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps SMS forwarding ready in the background"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("SMS forwarding is active")
        .setContentText("Waiting for incoming messages")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()

    companion object {
        private const val TAG = "ForwardingService"
        private const val CHANNEL_ID = "forwarding_active"
        private const val NOTIFICATION_ID = 1001

        fun update(context: Context, enabled: Boolean) {
            if (!enabled) {
                context.stopService(Intent(context, ForwardingService::class.java))
                return
            }
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ForwardingService::class.java),
                )
            } catch (error: RuntimeException) {
                Log.e(TAG, "The forwarding foreground service could not be started", error)
            }
        }
    }
}
