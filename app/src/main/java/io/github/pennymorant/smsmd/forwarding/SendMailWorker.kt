package io.github.pennymorant.smsmd.forwarding

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pennymorant.smsmd.SmsMdApplication
import io.github.pennymorant.smsmd.core.database.ChannelState
import io.github.pennymorant.smsmd.core.database.JobState
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import io.github.pennymorant.smsmd.core.telegram.TelegramApiException
import io.github.pennymorant.smsmd.core.telegram.TelegramClient
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.SendFailedException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException

class SendMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getLong(JOB_ID, -1)
        if (jobId <= 0) return@withContext Result.failure()
        val container = (applicationContext as SmsMdApplication).container
        val job = container.database.forwardDao().getJob(jobId) ?: return@withContext Result.success()
        val attempt = runAttemptCount + 1
        suspend fun failQueuedMessage(message: String): Result {
            container.forwardRepository.markUndeliverable(job, attempt, message)
            if (container.settings.settings.first().failureNotifications) {
                container.failureNotifier.show(job.id, message)
            }
            return Result.failure()
        }
        val sender = try {
            container.crypto.decrypt(job.encryptedSender)
        } catch (_: Throwable) {
            return@withContext failQueuedMessage("Stored sender could not be decrypted")
        }
        val body = try {
            container.crypto.decrypt(job.encryptedBody)
        } catch (_: Throwable) {
            return@withContext failQueuedMessage("Stored message could not be decrypted")
        }
        val snapshot = try {
            job.encryptedDeliveryConfig?.let {
                DeliverySnapshotCodec.decode(container.crypto.decrypt(it))
            }
        } catch (_: Throwable) {
            return@withContext failQueuedMessage("Stored delivery configuration could not be decrypted")
        }
        val errors = linkedSetOf<String>()
        job.lastError?.takeIf(String::isNotBlank)?.let(errors::add)
        var smtpState = enumValueOrDefault(job.smtpState, ChannelState.PENDING)
        var telegramState = enumValueOrDefault(job.telegramState, ChannelState.SKIPPED)
        container.database.forwardDao().updateJob(job.id, JobState.SENDING.name, attempt)

        if (smtpState == ChannelState.PENDING) {
            try {
                val emailSnapshot = when (snapshot) {
                    null -> null
                    is DeliverySnapshot.Email -> snapshot
                    is DeliverySnapshot.Telegram -> throw IllegalArgumentException(
                        "Queued delivery configuration does not match email",
                    )
                }
                val profile = emailSnapshot?.profile ?: container.settings.activeProfile.first()
                    ?: throw IllegalStateException("No SMTP profile for queued message")
                val password = emailSnapshot?.password ?: container.secrets.smtpPassword(profile.id)
                container.smtpClient.sendSms(
                    profile,
                    password,
                    sender,
                    body,
                    job.receivedAt,
                )
                smtpState = ChannelState.SENT
            } catch (error: Throwable) {
                errors += "Email: ${SmtpClient.safeError(error)}"
                if (error.isPermanentSmtpFailure() || attempt >= MAX_ATTEMPTS) {
                    smtpState = ChannelState.FAILED
                }
            }
            container.database.forwardDao().updateChannelStates(job.id, smtpState.name, telegramState.name)
        }

        if (telegramState == ChannelState.PENDING) {
            try {
                val telegramSnapshot = when (snapshot) {
                    null -> null
                    is DeliverySnapshot.Telegram -> snapshot
                    is DeliverySnapshot.Email -> throw IllegalArgumentException(
                        "Queued delivery configuration does not match Telegram",
                    )
                }
                val settings = if (telegramSnapshot == null) container.settings.settings.first() else null
                container.telegramClient.sendSms(
                    telegramSnapshot?.token ?: container.secrets.telegramToken(),
                    telegramSnapshot?.chatId ?: requireNotNull(settings).telegramChatId,
                    sender,
                    body,
                    job.receivedAt,
                )
                telegramState = ChannelState.SENT
            } catch (error: Throwable) {
                errors += "Telegram: ${TelegramClient.safeError(error)}"
                if ((error as? TelegramApiException)?.permanent == true ||
                    error is IllegalArgumentException || attempt >= MAX_ATTEMPTS
                ) {
                    telegramState = ChannelState.FAILED
                }
            }
            container.database.forwardDao().updateChannelStates(job.id, smtpState.name, telegramState.name)
        }

        val errorSummary = errors.joinToString(" · ").take(360).ifBlank { null }
        if (smtpState == ChannelState.PENDING || telegramState == ChannelState.PENDING) {
            container.database.forwardDao().updateJob(
                job.id,
                JobState.RETRYING.name,
                attempt,
                errorSummary,
            )
            return@withContext Result.retry()
        }

        val requested = listOf(smtpState, telegramState).filter { it != ChannelState.SKIPPED }
        val sentCount = requested.count { it == ChannelState.SENT }
        val result = when {
            sentCount == requested.size -> JobState.SENT.name
            sentCount == 0 -> JobState.FAILED.name
            else -> PARTIAL_RESULT
        }
        val channelSummary = buildList {
            if (smtpState != ChannelState.SKIPPED) add("Email ${smtpState.label()}")
            if (telegramState != ChannelState.SKIPPED) add("Telegram ${telegramState.label()}")
        }.joinToString(" · ")
        container.forwardRepository.markCompleted(
            job,
            attempt,
            sender,
            result,
            channelSummary,
            errorSummary,
        )
        if (result != JobState.SENT.name && container.settings.settings.first().failureNotifications) {
            container.failureNotifier.show(job.id, errorSummary ?: channelSummary)
        }
        if (result == JobState.FAILED.name) Result.failure() else Result.success()
    }

    private fun ChannelState.label() = when (this) {
        ChannelState.SENT -> "sent"
        ChannelState.FAILED -> "failed"
        ChannelState.PENDING -> "pending"
        ChannelState.SKIPPED -> "off"
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.isPermanentSmtpFailure(): Boolean {
        if (this is AuthenticationFailedException || this is SendFailedException ||
            this is IllegalArgumentException || hasCause<SSLHandshakeException>() ||
            hasCause<CertificateException>()
        ) return true
        var current: Throwable? = this
        while (current != null) {
            val code = when (current) {
                is SMTPAddressFailedException -> current.returnCode
                is SMTPSendFailedException -> current.returnCode
                else -> null
            }
            if (code != null && code >= 500) return true
            current = current.cause
        }
        return false
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, default: T): T =
        runCatching { enumValueOf<T>(raw) }.getOrDefault(default)

    companion object {
        const val JOB_ID = "job_id"
        const val PARTIAL_RESULT = "PARTIAL"
        private const val MAX_ATTEMPTS = 8
    }
}
