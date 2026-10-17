package io.github.pennymorant.smsmd.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.pennymorant.smsmd.MainActivity
import io.github.pennymorant.smsmd.R

class UpdateNotifier(private val context: Context) {
    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Alerts when an SMSmd update is ready to install"
            },
        )
    }

    fun showReady(version: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_INSTALL_UPDATE
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMSmd $version is ready")
            .setContentText("Tap to review and install the update")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val UPDATE_NOTIFICATION_ID = 2204
    }
}
