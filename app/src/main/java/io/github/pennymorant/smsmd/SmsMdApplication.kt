package io.github.pennymorant.smsmd

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import io.github.pennymorant.smsmd.core.crypto.CryptoManager
import io.github.pennymorant.smsmd.core.crypto.SecretStore
import io.github.pennymorant.smsmd.core.database.AppDatabase
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import io.github.pennymorant.smsmd.core.notification.FailureNotifier
import io.github.pennymorant.smsmd.core.settings.SettingsRepository
import io.github.pennymorant.smsmd.core.telegram.TelegramClient
import io.github.pennymorant.smsmd.forwarding.ForwardRepository
import io.github.pennymorant.smsmd.update.AppUpdateRepository

class SmsMdApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        FailureNotifier(this).createChannel()
    }
}

class AppContainer(application: Application) {
    val crypto by lazy { CryptoManager() }
    val secrets by lazy { SecretStore(application, crypto) }
    val settings by lazy { SettingsRepository(application) }
    val database: AppDatabase by lazy {
        Room.databaseBuilder<AppDatabase>(application, "smsmd.db")
            .setDriver(AndroidSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    val smtpClient by lazy { SmtpClient() }
    val telegramClient by lazy { TelegramClient() }
    val failureNotifier by lazy { FailureNotifier(application) }
    val updateRepository by lazy { AppUpdateRepository() }
    val forwardRepository by lazy {
        ForwardRepository(
            application,
            database.forwardDao(),
            settings,
            crypto,
            secrets,
        )
    }
}
