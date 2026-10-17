package io.github.pennymorant.smsmd.update

import android.app.DownloadManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.pennymorant.smsmd.SmsMdApplication

class UpdateMonitorWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SmsMdApplication).container
        val repository = container.updateRepository
        val saved = repository.loadSavedUpdate() ?: return Result.success()
        if (saved.verified || saved.failureMessage != null) return Result.success()

        val snapshot = repository.downloadSnapshot(saved.downloadId) ?: run {
            repository.markFailure(saved.downloadId, "The update download could not be found")
            return Result.failure()
        }
        return when (snapshot.status) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED,
            -> Result.retry()
            DownloadManager.STATUS_SUCCESSFUL -> when (val result = repository.verify(saved)) {
                is VerificationResult.Success -> {
                    container.updateNotifier.showReady(saved.release.version)
                    Result.success()
                }
                is VerificationResult.Failure -> Result.failure()
            }
            DownloadManager.STATUS_FAILED -> {
                repository.markFailure(saved.downloadId, repository.downloadFailureMessage(snapshot.reason))
                Result.failure()
            }
            else -> Result.retry()
        }
    }
}
