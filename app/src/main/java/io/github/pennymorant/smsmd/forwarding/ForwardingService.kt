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
import kotlinx.coroutines.launch

class ForwardingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(this, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            },
        )
        refreshForwardedCount()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
            val container = application.container
            val count = container.database.forwardDao().initializeForwardedCount()
            showNotification(count)
        }
    }

    private fun showNotification(count: Long) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(this, count),
        )
    }

    companion object {
        private const val TAG = "ForwardingService"
        private const val CHANNEL_ID = "forwarding_active"
        private const val NOTIFICATION_ID = 1001
        internal fun forwardedCountLabel(count: Long): String = "$count forwarded"

        private fun buildNotification(context: Context, count: Long) =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SMS forwarding is active")
                .setContentText("Waiting for incoming messages")
                .setSubText(forwardedCountLabel(count))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()

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
            context.getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(context, count),
            )
        }
    }
}
