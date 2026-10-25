package io.github.pennymorant.smsmd

import android.app.Application
import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import io.github.pennymorant.smsmd.core.crypto.CryptoManager
import io.github.pennymorant.smsmd.core.crypto.SecretStore
import io.github.pennymorant.smsmd.core.database.AppDatabase
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import io.github.pennymorant.smsmd.core.notification.FailureNotifier
import io.github.pennymorant.smsmd.core.settings.SettingsRepository
import io.github.pennymorant.smsmd.core.telegram.TelegramClient
import io.github.pennymorant.smsmd.forwarding.ForwardRepository
import io.github.pennymorant.smsmd.update.AppUpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsMdApplication : Application() {
    val container by lazy { AppContainer(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container.failureNotifier.createChannel()
        applicationScope.launch {
            container.database.forwardDao().trimLogsToLatest()
            container.database.forwardDao().deleteFailedJobs()
        }
    }
}

class AppContainer(application: Application) {
    val crypto = CryptoManager()
    val secrets = SecretStore(application, crypto)
    val settings = SettingsRepository(application)
    val database: AppDatabase = Room.databaseBuilder<AppDatabase>(application, "smsmd.db")
        .setDriver(AndroidSQLiteDriver())
        .addMigrations(
            Migration(1, 2) { connection ->
                connection.execSQL(
                    "ALTER TABLE forward_jobs ADD COLUMN smtpState TEXT NOT NULL DEFAULT 'PENDING'",
                )
                connection.execSQL(
                    "ALTER TABLE forward_jobs ADD COLUMN telegramState TEXT NOT NULL DEFAULT 'SKIPPED'",
                )
                connection.execSQL(
                    "ALTER TABLE delivery_logs ADD COLUMN channelSummary TEXT NOT NULL DEFAULT 'Email sent'",
                )
            },
            Migration(2, 3) { connection ->
                connection.execSQL(
                    "ALTER TABLE forward_jobs ADD COLUMN encryptedDeliveryConfig TEXT",
                )
            },
        )
        .build()
    val smtpClient = SmtpClient()
    val telegramClient = TelegramClient()
    val failureNotifier = FailureNotifier(application)
    val updateRepository = AppUpdateRepository()
    val forwardRepository = ForwardRepository(
        application,
        database.forwardDao(),
        settings,
        crypto,
        secrets,
    )
}
