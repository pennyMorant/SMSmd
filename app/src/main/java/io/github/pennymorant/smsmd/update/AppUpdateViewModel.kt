package io.github.pennymorant.smsmd.update

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.pennymorant.smsmd.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppUpdateViewModel(private val repository: AppUpdateRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private var monitorJob: Job? = null

    init {
        refresh()
    }

    fun checkForUpdate() {
        if (state.value is AppUpdateState.Checking || state.value is AppUpdateState.Downloading) return
        monitorJob?.cancel()
        mutableState.value = AppUpdateState.Checking
        viewModelScope.launch {
            runCatching { repository.latestRelease() }
                .onSuccess { release ->
                    if (!isNewerVersion(release.version, BuildConfig.VERSION_NAME)) {
                        repository.clearSavedUpdate()
                        mutableState.value = AppUpdateState.UpToDate(BuildConfig.VERSION_NAME)
                    } else {
                        repository.clearSavedUpdate()
                        runCatching { repository.enqueueDownload(release) }
                            .onSuccess { monitor(repository.loadSavedUpdate() ?: return@onSuccess) }
                            .onFailure { mutableState.value = AppUpdateState.Failed(it.safeMessage()) }
                    }
                }
                .onFailure { mutableState.value = AppUpdateState.Failed(it.safeMessage()) }
        }
    }

    fun refresh() {
        monitorJob?.cancel()
        val saved = repository.loadSavedUpdate()
        if (saved == null) {
            if (state.value !is AppUpdateState.UpToDate) mutableState.value = AppUpdateState.Idle
            return
        }
        if (!isNewerVersion(saved.release.version, BuildConfig.VERSION_NAME)) {
            repository.clearSavedUpdate()
            mutableState.value = AppUpdateState.Idle
            return
        }
        if (saved.failureMessage != null) {
            mutableState.value = AppUpdateState.Failed(saved.failureMessage)
        } else if (saved.verified) {
            mutableState.value = AppUpdateState.ReadyToInstall(saved.release.version)
        } else {
            monitor(saved)
        }
    }

    fun installationUri() = repository.installationUri()

    fun reportFailure(message: String) {
        repository.loadSavedUpdate()?.let { repository.markFailure(it.downloadId, message) }
        mutableState.value = AppUpdateState.Failed(message)
    }

    private fun monitor(saved: SavedUpdate) {
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            while (true) {
                val snapshot = repository.downloadSnapshot(saved.downloadId)
                when (snapshot?.status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    -> {
                        mutableState.value = AppUpdateState.Downloading(
                            saved.release.version,
                            snapshot.progress,
                            repository.downloadWaitingMessage(snapshot),
                        )
                        delay(DOWNLOAD_POLL_INTERVAL_MS)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        mutableState.value = AppUpdateState.Verifying(saved.release.version)
                        when (val result = repository.verify(saved)) {
                            is VerificationResult.Success -> {
                                mutableState.value = AppUpdateState.ReadyToInstall(saved.release.version)
                            }
                            is VerificationResult.Failure -> {
                                mutableState.value = AppUpdateState.Failed(result.message)
                            }
                        }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val message = repository.downloadFailureMessage(snapshot.reason)
                        repository.markFailure(saved.downloadId, message)
                        mutableState.value = AppUpdateState.Failed(message)
                        return@launch
                    }
                    else -> {
                        val message = "The update download could not be found"
                        repository.markFailure(saved.downloadId, message)
                        mutableState.value = AppUpdateState.Failed(message)
                        return@launch
                    }
                }
            }
        }
    }

    class Factory(private val repository: AppUpdateRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppUpdateViewModel(repository) as T
    }

    private companion object {
        const val DOWNLOAD_POLL_INTERVAL_MS = 750L
    }
}

private fun Throwable.safeMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Could not check for updates"
