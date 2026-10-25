package io.github.pennymorant.smsmd.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.pennymorant.smsmd.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppUpdateViewModel(private val repository: AppUpdateRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    fun checkForUpdate() {
        if (state.value is AppUpdateState.Checking) return
        mutableState.value = AppUpdateState.Checking
        viewModelScope.launch {
            runCatching { repository.latestRelease() }
                .onSuccess { release ->
                    mutableState.value = if (isNewerVersion(release.version, BuildConfig.VERSION_NAME)) {
                        AppUpdateState.UpdateAvailable(release.version, release.pageUrl)
                    } else {
                        AppUpdateState.UpToDate(BuildConfig.VERSION_NAME)
                    }
                }
                .onFailure { mutableState.value = AppUpdateState.Failed(it.safeMessage()) }
        }
    }

    class Factory(private val repository: AppUpdateRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppUpdateViewModel(repository) as T
    }
}

private fun Throwable.safeMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Could not check for updates"
