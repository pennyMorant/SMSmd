package io.github.pennymorant.smsmd.forwarding

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.pennymorant.smsmd.core.crypto.CryptoManager
import io.github.pennymorant.smsmd.core.database.DeliveryLogEntity
import io.github.pennymorant.smsmd.core.database.ChannelState
import io.github.pennymorant.smsmd.core.database.ForwardDao
import io.github.pennymorant.smsmd.core.database.ForwardJobEntity
import io.github.pennymorant.smsmd.core.database.JobState
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import io.github.pennymorant.smsmd.core.settings.SettingsRepository
import io.github.pennymorant.smsmd.core.settings.telegramReady
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ForwardRepository(
    context: Context,
    private val dao: ForwardDao,
    private val settingsRepository: SettingsRepository,
    private val crypto: CryptoManager,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun accept(sender: String, body: String, receivedAt: Long, subscriptionId: Int): Long? {
        val settings = settingsRepository.settings.first()
        val profile = settingsRepository.activeProfile.first()
        val smtpReady = profile?.smtpVerified == true
        val telegramReady = settings.telegramReady
        if (!settings.forwardingEnabled || (!smtpReady && !telegramReady) || body.isBlank()) return null

        val fingerprint = fingerprint(sender, body, receivedAt, subscriptionId)
        val id = dao.insertJob(
            ForwardJobEntity(
                fingerprint = fingerprint,
                encryptedSender = crypto.encrypt(sender),
                encryptedBody = crypto.encrypt(body),
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                smtpState = if (smtpReady) ChannelState.PENDING.name else ChannelState.SKIPPED.name,
                telegramState = if (telegramReady) ChannelState.PENDING.name else ChannelState.SKIPPED.name,
            ),
        )
        if (id > 0) enqueue(id)
        return id.takeIf { it > 0 }
    }

    fun enqueue(jobId: Long) {
        val request = OneTimeWorkRequestBuilder<SendMailWorker>()
            .setInputData(workDataOf(SendMailWorker.JOB_ID to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("sms-mail-send")
            .build()
        workManager.enqueueUniqueWork("send-sms-$jobId", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun markCompleted(
        job: ForwardJobEntity,
        attempt: Int,
        sender: String,
        result: String,
        channelSummary: String,
        error: String?,
    ) {
        dao.clearLogs()
        dao.insertLog(
            DeliveryLogEntity(
                jobId = job.id,
                maskedSender = SmtpClient.maskSender(sender),
                receivedAt = job.receivedAt,
                result = result,
                attemptCount = attempt,
                errorSummary = error,
                channelSummary = channelSummary,
            ),
        )
        dao.deleteJob(job.id)
    }

    private fun fingerprint(sender: String, body: String, receivedAt: Long, subscriptionId: Int): String {
        val timeBucket = receivedAt / 10_000L
        val raw = "$sender\u0000$body\u0000$timeBucket\u0000$subscriptionId"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
