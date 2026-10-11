package io.github.pennymorant.smsmd.forwarding

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pennymorant.smsmd.SmsMdApplication
import io.github.pennymorant.smsmd.core.database.JobState
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.SendFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException

class SendMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getLong(JOB_ID, -1)
        if (jobId <= 0) return@withContext Result.failure()
        val container = (applicationContext as SmsMdApplication).container
        val job = container.database.forwardDao().getJob(jobId) ?: return@withContext Result.success()
        val attempt = runAttemptCount + 1
        val sender = runCatching { container.crypto.decrypt(job.encryptedSender) }
            .getOrElse { return@withContext Result.failure() }
        val body = runCatching { container.crypto.decrypt(job.encryptedBody) }
            .getOrElse { return@withContext Result.failure() }
        container.database.forwardDao().updateJob(job.id, JobState.SENDING.name, attempt)

        try {
            val profile = container.settings.activeProfile.first()
                ?: throw IllegalStateException("No active SMTP profile")
            container.smtpClient.sendSms(
                profile,
                container.secrets.smtpPassword(profile.id),
                sender,
                body,
                job.receivedAt,
            )
            container.forwardRepository.markSuccess(job, attempt, sender)
            Result.success()
        } catch (error: Throwable) {
            val message = SmtpClient.safeError(error)
            val permanent = error is AuthenticationFailedException ||
                error is SendFailedException ||
                error is IllegalArgumentException ||
                error.smtpStatusIsPermanent() ||
                error.hasCause<SSLHandshakeException>() ||
                error.hasCause<CertificateException>()
            if (permanent || attempt >= MAX_ATTEMPTS) {
                container.forwardRepository.markFailed(job, attempt, sender, message)
                if (container.settings.settings.first().failureNotifications) {
                    container.failureNotifier.show(job.id, message)
                }
                Result.failure()
            } else {
                container.database.forwardDao()
                    .updateJob(job.id, JobState.RETRYING.name, attempt, message)
                Result.retry()
            }
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.smtpStatusIsPermanent(): Boolean {
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

    companion object {
        const val JOB_ID = "job_id"
        private const val MAX_ATTEMPTS = 8
    }
}
