package com.example.smsmd

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smsmd.core.database.DeliveryLogEntity
import com.example.smsmd.core.mail.SmtpClient
import com.example.smsmd.core.settings.AppSettings
import com.example.smsmd.core.settings.DEFAULT_PROFILE_ID
import com.example.smsmd.core.settings.SmtpProfile
import com.example.smsmd.core.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SmtpTestPhase { IDLE, TESTING, SUCCESS, FAILED }

data class SmtpTestStatus(
    val profileId: String? = null,
    val phase: SmtpTestPhase = SmtpTestPhase.IDLE,
    val message: String = "",
)

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val profiles: List<SmtpProfile> = emptyList(),
    val pendingCount: Int = 0,
    val lastDelivery: DeliveryLogEntity? = null,
    val testStatus: SmtpTestStatus = SmtpTestStatus(),
) {
    val activeProfile: SmtpProfile?
        get() = profiles.firstOrNull { it.id == settings.activeProfileId }
}

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val testStatus = MutableStateFlow(SmtpTestStatus())

    val uiState: StateFlow<MainUiState> = combine(
        container.settings.settings,
        container.settings.profiles,
        container.database.forwardDao().pendingCount(),
        container.database.forwardDao().observeLastDelivery(),
        testStatus,
    ) { settings, profiles, pending, lastDelivery, currentTest ->
        MainUiState(settings, profiles, pending, lastDelivery, currentTest)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun saveProfile(
        profile: SmtpProfile,
        newPassword: String,
        callback: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                validateProfile(profile, requireCredentials = false)
                persistSynthesizedDefaultBefore(profile)
                val old = uiState.value.profiles.firstOrNull { it.id == profile.id }
                val savedPassword = container.secrets.smtpPassword(profile.id)
                val passwordChanged = newPassword.isNotBlank() && newPassword != savedPassword
                val changed = old == null || !old.sameConnection(profile) || passwordChanged
                if (passwordChanged) container.secrets.saveSmtpPassword(profile.id, newPassword)
                container.settings.saveProfile(profile.copy(smtpVerified = !changed && old.smtpVerified))
            }
            delay((MIN_SAVE_ANIMATION_MS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L))
            result.onSuccess { callback(true, "Profile saved") }
                .onFailure { callback(false, it.message ?: "Could not save profile") }
        }
    }

    fun testProfile(profile: SmtpProfile, newPassword: String, callback: (String) -> Unit) {
        viewModelScope.launch {
            testStatus.value = SmtpTestStatus(profile.id, SmtpTestPhase.TESTING)
            val result = runCatching {
                validateProfile(profile, requireCredentials = true)
                persistSynthesizedDefaultBefore(profile)
                if (newPassword.isNotBlank()) container.secrets.saveSmtpPassword(profile.id, newPassword)
                val password = container.secrets.smtpPassword(profile.id)
                require(password.isNotBlank()) { "App password is required" }
                val unverified = profile.copy(smtpVerified = false)
                container.settings.saveProfile(unverified)
                withContext(Dispatchers.IO) { container.smtpClient.sendTest(unverified, password) }
                container.settings.saveProfile(unverified.copy(smtpVerified = true))
            }
            result.onSuccess {
                testStatus.value = SmtpTestStatus(profile.id, SmtpTestPhase.SUCCESS, "Verified")
                callback("Test email sent")
            }.onFailure { error ->
                val message = SmtpClient.safeError(error)
                testStatus.value = SmtpTestStatus(profile.id, SmtpTestPhase.FAILED, message)
                callback(message)
            }
        }
    }

    fun selectProfile(profile: SmtpProfile, callback: (String) -> Unit) {
        viewModelScope.launch {
            if (!profile.smtpVerified) {
                callback("Test this profile before selecting it")
            } else if (!container.secrets.hasSmtpPassword(profile.id)) {
                callback("This profile has no saved app password")
            } else {
                container.settings.setActiveProfile(profile.id)
                callback("${profile.name} is now active")
            }
        }
    }

    fun deleteProfile(profile: SmtpProfile, callback: (String) -> Unit) {
        viewModelScope.launch {
            container.settings.deleteProfile(profile.id)
            container.secrets.deleteSmtpPassword(profile.id)
            callback("Profile deleted")
        }
    }

    fun setEnabled(enabled: Boolean, callback: (String) -> Unit) {
        viewModelScope.launch {
            val profile = uiState.value.activeProfile
            if (enabled) {
                if (profile == null) {
                    callback("Select an SMTP profile first")
                    return@launch
                }
                if (!profile.smtpVerified) {
                    callback("Test the active SMTP profile first")
                    return@launch
                }
                if (!container.secrets.hasSmtpPassword(profile.id)) {
                    callback("The active profile has no saved app password")
                    return@launch
                }
            }
            container.settings.setEnabled(enabled)
        }
    }

    fun setFailureNotifications(enabled: Boolean) {
        viewModelScope.launch { container.settings.setFailureNotifications(enabled) }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch { container.settings.setThemeMode(themeMode) }
    }

    fun loadSmtpPassword(profileId: String, callback: (String) -> Unit) {
        viewModelScope.launch {
            callback(withContext(Dispatchers.IO) { container.secrets.smtpPassword(profileId) })
        }
    }

    private suspend fun persistSynthesizedDefaultBefore(profile: SmtpProfile) {
        val current = uiState.value.profiles
        if (profile.id != DEFAULT_PROFILE_ID && current.size == 1 && current.first().id == DEFAULT_PROFILE_ID) {
            container.settings.saveProfile(current.first())
        }
    }

    private fun validateProfile(profile: SmtpProfile, requireCredentials: Boolean) {
        require(profile.name.isNotBlank()) { "Profile name is required" }
        require(profile.smtpHost.isNotBlank()) { "SMTP host is required" }
        require(profile.smtpPort in 1..65535) { "Port must be between 1 and 65535" }
        if (requireCredentials) require(profile.isConfigured) { "Complete all SMTP fields first" }
    }

    private fun SmtpProfile.sameConnection(other: SmtpProfile): Boolean =
        provider == other.provider &&
            smtpHost.trim() == other.smtpHost.trim() &&
            smtpPort == other.smtpPort &&
            smtpSecurity == other.smtpSecurity &&
            smtpUsername.trim() == other.smtpUsername.trim() &&
            fromAddress.trim() == other.fromAddress.trim() &&
            recipientAddresses.trim() == other.recipientAddresses.trim()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
    }

    private companion object {
        const val MIN_SAVE_ANIMATION_MS = 450L
    }
}
