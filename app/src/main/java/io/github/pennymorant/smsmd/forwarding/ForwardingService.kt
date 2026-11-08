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
import io.github.pennymorant.smsmd.SmsMdApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ForwardingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            },
        )
        refreshForwardedCount()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_COUNT) {
            val count = intent.getLongExtra(EXTRA_FORWARDED_COUNT, -1L)
            if (count >= 0) showNotification(count) else refreshForwardedCount()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

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

    private fun refreshForwardedCount() {
        serviceScope.launch {
            val application = applicationContext as SmsMdApplication
            showNotification(application.container.settings.forwardedCount.first())
        }
    }

    private fun showNotification(count: Long) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(count),
        )
    }

    private fun buildNotification(count: Long) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("SMS forwarding is active")
        .setContentText("Waiting for incoming messages")
        .setSubText(forwardedCountLabel(count))
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
        private const val ACTION_UPDATE_COUNT = "io.github.pennymorant.smsmd.action.UPDATE_COUNT"
        private const val EXTRA_FORWARDED_COUNT = "forwarded_count"

        internal fun forwardedCountLabel(count: Long): String = "$count forwarded"

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

        fun updateCount(context: Context, count: Long) {
            runCatching {
                context.startService(
                    Intent(context, ForwardingService::class.java).apply {
                        action = ACTION_UPDATE_COUNT
                        putExtra(EXTRA_FORWARDED_COUNT, count)
                    },
                )
            }.onFailure { error ->
                Log.e(TAG, "The forwarding count notification could not be updated", error)
            }
        }
    }
}
