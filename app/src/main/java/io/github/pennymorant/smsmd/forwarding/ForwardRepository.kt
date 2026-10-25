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
import io.github.pennymorant.smsmd.core.crypto.SecretStore
import io.github.pennymorant.smsmd.core.database.DeliveryLogEntity
import io.github.pennymorant.smsmd.core.database.ChannelState
import io.github.pennymorant.smsmd.core.database.ForwardDao
import io.github.pennymorant.smsmd.core.database.ForwardJobEntity
import io.github.pennymorant.smsmd.core.database.JobState
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import io.github.pennymorant.smsmd.core.settings.SettingsRepository
import io.github.pennymorant.smsmd.core.settings.DeliveryEngine
import io.github.pennymorant.smsmd.core.settings.telegramReady
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class DeliveryChannels(
    val smtp: ChannelState,
    val telegram: ChannelState,
)

internal fun resolveDeliveryChannels(
    activeEngine: DeliveryEngine,
    smtpVerified: Boolean,
    telegramReady: Boolean,
): DeliveryChannels = when (activeEngine) {
    DeliveryEngine.EMAIL -> DeliveryChannels(
        smtp = if (smtpVerified) ChannelState.PENDING else ChannelState.SKIPPED,
        telegram = ChannelState.SKIPPED,
    )
    DeliveryEngine.TELEGRAM -> DeliveryChannels(
        smtp = ChannelState.SKIPPED,
        telegram = if (telegramReady) ChannelState.PENDING else ChannelState.SKIPPED,
    )
}

class ForwardRepository(
    context: Context,
    private val dao: ForwardDao,
    private val settingsRepository: SettingsRepository,
    private val crypto: CryptoManager,
    private val secrets: SecretStore,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueIncoming(sender: String, body: String, receivedAt: Long, subscriptionId: Int) {
        val fingerprint = fingerprint(sender, body, receivedAt, subscriptionId)
        val request = OneTimeWorkRequestBuilder<SmsIntakeWorker>()
            .setInputData(
                workDataOf(
                    SmsIntakeWorker.ENCRYPTED_SENDER to crypto.encrypt(sender),
                    SmsIntakeWorker.ENCRYPTED_BODY to crypto.encrypt(body),
                    SmsIntakeWorker.RECEIVED_AT to receivedAt,
                    SmsIntakeWorker.SUBSCRIPTION_ID to subscriptionId,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("sms-intake")
            .build()
        workManager.enqueueUniqueWork("intake-sms-$fingerprint", ExistingWorkPolicy.KEEP, request)
            .result.get(INTAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    suspend fun accept(sender: String, body: String, receivedAt: Long, subscriptionId: Int): Long? {
        val settings = settingsRepository.settings.first()
        val profile = settingsRepository.activeProfile.first()
        val smtpPassword = profile?.let { secrets.smtpPassword(it.id) }.orEmpty()
        val telegramToken = secrets.telegramToken()
        val channels = resolveDeliveryChannels(
            activeEngine = settings.activeEngine,
            smtpVerified = profile?.smtpVerified == true && smtpPassword.isNotBlank(),
            telegramReady = settings.telegramReady && telegramToken.isNotBlank(),
        )
        val deliveryReady = channels.smtp == ChannelState.PENDING || channels.telegram == ChannelState.PENDING
        if (!settings.forwardingEnabled || !deliveryReady || body.isBlank()) return null
        val snapshot = when (settings.activeEngine) {
            DeliveryEngine.EMAIL -> DeliverySnapshot.Email(requireNotNull(profile), smtpPassword)
            DeliveryEngine.TELEGRAM -> DeliverySnapshot.Telegram(telegramToken, settings.telegramChatId)
        }

        val fingerprint = fingerprint(sender, body, receivedAt, subscriptionId)
        val id = dao.insertJob(
            ForwardJobEntity(
                fingerprint = fingerprint,
                encryptedSender = crypto.encrypt(sender),
                encryptedBody = crypto.encrypt(body),
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                smtpState = channels.smtp.name,
                telegramState = channels.telegram.name,
                encryptedDeliveryConfig = crypto.encrypt(DeliverySnapshotCodec.encode(snapshot)),
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

    suspend fun recoverPendingJobs() {
        dao.pendingJobs().forEach { enqueue(it.id) }
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

    suspend fun markUndeliverable(job: ForwardJobEntity, attempt: Int, error: String) {
        markCompleted(
            job = job,
            attempt = attempt,
            sender = "Unavailable",
            result = JobState.FAILED.name,
            channelSummary = "Delivery failed",
            error = error,
        )
    }

    private fun fingerprint(sender: String, body: String, receivedAt: Long, subscriptionId: Int): String {
        val timeBucket = receivedAt / 10_000L
        val raw = "$sender\u0000$body\u0000$timeBucket\u0000$subscriptionId"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val INTAKE_TIMEOUT_SECONDS = 8L
    }
}
