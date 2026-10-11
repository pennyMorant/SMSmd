package com.example.smsmd

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.example.smsmd.core.crypto.CryptoManager
import com.example.smsmd.core.crypto.SecretStore
import com.example.smsmd.core.database.AppDatabase
import com.example.smsmd.core.mail.SmtpClient
import com.example.smsmd.core.notification.FailureNotifier
import com.example.smsmd.core.settings.SettingsRepository
import com.example.smsmd.forwarding.ForwardRepository
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
        .build()
    val smtpClient = SmtpClient()
    val failureNotifier = FailureNotifier(application)
    val forwardRepository = ForwardRepository(
        application,
        database.forwardDao(),
        settings,
        crypto,
    )
}
