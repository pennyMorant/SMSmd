package io.github.pennymorant.smsmd.forwarding

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pennymorant.smsmd.SmsMdApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsIntakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val encryptedSender = inputData.getString(ENCRYPTED_SENDER) ?: return@withContext Result.failure()
        val encryptedBody = inputData.getString(ENCRYPTED_BODY) ?: return@withContext Result.failure()
        val receivedAt = inputData.getLong(RECEIVED_AT, -1)
        if (receivedAt <= 0) return@withContext Result.failure()

        val container = (applicationContext as SmsMdApplication).container
        val sender = runCatching { container.crypto.decrypt(encryptedSender) }
            .getOrElse { return@withContext Result.failure() }
        val body = runCatching { container.crypto.decrypt(encryptedBody) }
            .getOrElse { return@withContext Result.failure() }

        runCatching {
            container.forwardRepository.accept(
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = inputData.getInt(SUBSCRIPTION_ID, -1),
            )
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure() },
        )
    }

    companion object {
        const val ENCRYPTED_SENDER = "encrypted_sender"
        const val ENCRYPTED_BODY = "encrypted_body"
        const val RECEIVED_AT = "received_at"
        const val SUBSCRIPTION_ID = "subscription_id"
        private const val MAX_ATTEMPTS = 5
    }
}
