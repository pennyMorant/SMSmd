package io.github.pennymorant.smsmd.forwarding

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pennymorant.smsmd.SmsMdApplication
import io.github.pennymorant.smsmd.core.database.ChannelState
import io.github.pennymorant.smsmd.core.database.JobState
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import io.github.pennymorant.smsmd.core.telegram.TelegramApiException
import io.github.pennymorant.smsmd.core.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SendMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = DeliveryExecutionGate.run {
        withContext(Dispatchers.IO) { deliver() }
    }

    private suspend fun deliver(): Result {
        val jobId = inputData.getLong(JOB_ID, -1)
        if (jobId <= 0) return Result.failure()
        val container = (applicationContext as SmsMdApplication).container
        val dao = container.database.forwardDao()
        val job = dao.getJob(jobId) ?: return Result.success()
        val attempt = runAttemptCount + 1
        Log.i(TAG, "Starting queued delivery attempt $attempt")
        suspend fun failQueuedMessage(message: String): Result {
            withContext(NonCancellable) {
                container.forwardRepository.markUndeliverable(job, attempt, message)
                if (container.settings.settings.first().failureNotifications) {
                    container.failureNotifier.show(job.id, message)
                }
            }
            return Result.failure()
        }
        val sender = try {
            container.crypto.decrypt(job.encryptedSender)
        } catch (_: Throwable) {
            return failQueuedMessage("Stored sender could not be decrypted")
        }
        val body = try {
            container.crypto.decrypt(job.encryptedBody)
        } catch (_: Throwable) {
            return failQueuedMessage("Stored message could not be decrypted")
        }
        val snapshot = try {
            job.encryptedDeliveryConfig?.let {
                DeliverySnapshotCodec.decode(container.crypto.decrypt(it))
            }
        } catch (_: Throwable) {
            return failQueuedMessage("Stored delivery configuration could not be decrypted")
        }
        val errors = linkedSetOf<String>()
        job.lastError?.takeIf(String::isNotBlank)?.let(errors::add)
        var smtpState = enumValueOrDefault(job.smtpState, ChannelState.PENDING)
        var telegramState = enumValueOrDefault(job.telegramState, ChannelState.SKIPPED)
        var telegramRetryAfter: Int? = null
        dao.updateJob(job.id, JobState.SENDING.name, attempt)

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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                errors += "Email: ${SmtpClient.safeError(error)}"
                if (isPermanentSmtpFailure(error) || attempt >= MAX_ATTEMPTS) {
                    smtpState = ChannelState.FAILED
                }
            }
            withContext(NonCancellable) {
                dao.updateChannelStates(job.id, smtpState.name, telegramState.name)
            }
            currentCoroutineContext().ensureActive()
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                errors += "Telegram: ${TelegramClient.safeError(error)}"
                if ((error as? TelegramApiException)?.permanent == true ||
                    error is IllegalArgumentException || attempt >= MAX_ATTEMPTS
                ) {
                    telegramState = ChannelState.FAILED
                } else {
                    telegramRetryAfter = (error as? TelegramApiException)?.retryAfterSeconds
                }
            }
            withContext(NonCancellable) {
                dao.updateChannelStates(job.id, smtpState.name, telegramState.name)
            }
            currentCoroutineContext().ensureActive()
        }

        val errorSummary = errors.joinToString(" · ").take(360).ifBlank { null }
        if (smtpState == ChannelState.PENDING || telegramState == ChannelState.PENDING) {
            telegramRetryAfter?.let { seconds ->
                delay(seconds.toLong() * 1000)
                currentCoroutineContext().ensureActive()
            }
            dao.updateJob(
                job.id,
                JobState.RETRYING.name,
                attempt,
                errorSummary,
            )
            return Result.retry()
        }

        val requested = listOf(smtpState, telegramState).filter { it != ChannelState.SKIPPED }
        val sentCount = requested.count { it == ChannelState.SENT }
        val result = when {
            sentCount == requested.size -> JobState.SENT.name
            sentCount == 0 -> JobState.FAILED.name
            else -> PARTIAL_RESULT
        }
        val finalErrorSummary = errorSummary.takeUnless { result == JobState.SENT.name }
        val channelSummary = buildList {
            if (smtpState != ChannelState.SKIPPED) add("Email ${smtpState.label()}")
            if (telegramState != ChannelState.SKIPPED) add("Telegram ${telegramState.label()}")
        }.joinToString(" · ")
        withContext(NonCancellable) {
            container.forwardRepository.markCompleted(
                job,
                attempt,
                sender,
                result,
                channelSummary,
                finalErrorSummary,
            )
            if (result != JobState.SENT.name && container.settings.settings.first().failureNotifications) {
                container.failureNotifier.show(job.id, finalErrorSummary ?: channelSummary)
            }
        }
        Log.i(TAG, "Queued delivery finished with $result")
        return if (result == JobState.FAILED.name) Result.failure() else Result.success()
    }

    private fun ChannelState.label() = when (this) {
        ChannelState.SENT -> "sent"
        ChannelState.FAILED -> "failed"
        ChannelState.PENDING -> "pending"
        ChannelState.SKIPPED -> "off"
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, default: T): T =
        runCatching { enumValueOf<T>(raw) }.getOrDefault(default)

    companion object {
        private const val TAG = "SendMailWorker"
        const val JOB_ID = "job_id"
        const val PARTIAL_RESULT = "PARTIAL"
        private const val MAX_ATTEMPTS = 8
    }
}
