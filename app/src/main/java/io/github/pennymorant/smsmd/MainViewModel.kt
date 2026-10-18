package io.github.pennymorant.smsmd

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.pennymorant.smsmd.core.database.DeliveryLogEntity
import io.github.pennymorant.smsmd.core.mail.SmtpClient
import io.github.pennymorant.smsmd.core.settings.AppSettings
import io.github.pennymorant.smsmd.core.settings.DEFAULT_PROFILE_ID
import io.github.pennymorant.smsmd.core.settings.SmtpProfile
import io.github.pennymorant.smsmd.core.settings.ThemeMode
import io.github.pennymorant.smsmd.core.settings.telegramReady
import io.github.pennymorant.smsmd.core.telegram.TelegramChat
import io.github.pennymorant.smsmd.core.telegram.TelegramClient
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

enum class TelegramTestPhase { IDLE, TESTING, SUCCESS, FAILED }

data class SmtpTestStatus(
    val profileId: String? = null,
    val phase: SmtpTestPhase = SmtpTestPhase.IDLE,
    val message: String = "",
)

data class TelegramTestStatus(
    val phase: TelegramTestPhase = TelegramTestPhase.IDLE,
    val message: String = "",
)

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val profiles: List<SmtpProfile> = emptyList(),
    val pendingCount: Int = 0,
    val lastDelivery: DeliveryLogEntity? = null,
    val testStatus: SmtpTestStatus = SmtpTestStatus(),
    val telegramTestStatus: TelegramTestStatus = TelegramTestStatus(),
) {
    val activeProfile: SmtpProfile?
        get() = profiles.firstOrNull { it.id == settings.activeProfileId }
}

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val testStatus = MutableStateFlow(SmtpTestStatus())
    private val telegramTestStatus = MutableStateFlow(TelegramTestStatus())

    private val baseUiState = combine(
        container.settings.settings,
        container.settings.profiles,
        container.database.forwardDao().pendingCount(),
        container.database.forwardDao().observeLastDelivery(),
        testStatus,
    ) { settings, profiles, pending, lastDelivery, currentTest ->
        MainUiState(settings, profiles, pending, lastDelivery, currentTest)
    }

    val uiState: StateFlow<MainUiState> = combine(baseUiState, telegramTestStatus) { state, telegramTest ->
        state.copy(telegramTestStatus = telegramTest)
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
                val smtpReady = profile?.smtpVerified == true && container.secrets.hasSmtpPassword(profile.id)
                val telegramReady = uiState.value.settings.telegramReady && container.secrets.hasTelegramToken()
                if (!smtpReady && !telegramReady) {
                    callback("Test an SMTP or Telegram configuration first")
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

    fun loadTelegramToken(callback: (String) -> Unit) {
        viewModelScope.launch {
            callback(withContext(Dispatchers.IO) { container.secrets.telegramToken() })
        }
    }

    fun saveTelegram(
        enabled: Boolean,
        token: String,
        chatId: String,
        callback: (success: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                if (enabled) validateTelegram(token, chatId)
                val settings = uiState.value.settings
                val savedToken = container.secrets.telegramToken()
                val tokenChanged = token.trim() != savedToken
                val chatChanged = chatId.trim() != settings.telegramChatId
                if (tokenChanged) {
                    if (token.isBlank()) container.secrets.deleteTelegramToken()
                    else container.secrets.saveTelegramToken(token.trim())
                }
                container.settings.saveTelegram(
                    enabled = enabled,
                    chatId = chatId,
                    verified = settings.telegramVerified && !tokenChanged && !chatChanged,
                )
            }
            delay((MIN_SAVE_ANIMATION_MS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L))
            result.onSuccess { callback(true, "Telegram saved") }
                .onFailure { callback(false, it.message ?: "Could not save Telegram") }
        }
    }

    fun testTelegram(enabled: Boolean, token: String, chatId: String, callback: (String) -> Unit) {
        viewModelScope.launch {
            telegramTestStatus.value = TelegramTestStatus(TelegramTestPhase.TESTING)
            val result = runCatching {
                validateTelegram(token, chatId)
                container.secrets.saveTelegramToken(token.trim())
                container.settings.saveTelegram(enabled, chatId, verified = false)
                withContext(Dispatchers.IO) {
                    container.telegramClient.sendTest(token.trim(), chatId.trim())
                }
                container.settings.saveTelegram(enabled, chatId, verified = true)
            }
            result.onSuccess {
                telegramTestStatus.value = TelegramTestStatus(TelegramTestPhase.SUCCESS, "Verified")
                callback("Telegram test sent")
            }.onFailure { error ->
                val message = TelegramClient.safeError(error)
                telegramTestStatus.value = TelegramTestStatus(TelegramTestPhase.FAILED, message)
                callback(message)
            }
        }
    }

    fun detectTelegramChats(token: String, callback: (Result<List<TelegramChat>>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                require(token.isNotBlank()) { "Bot token is required" }
                withContext(Dispatchers.IO) { container.telegramClient.recentChats(token.trim()) }
                    .also { require(it.isNotEmpty()) { "No chats found. Send /start to the bot first" } }
            }
            callback(result)
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

    private fun validateTelegram(token: String, chatId: String) {
        require(token.isNotBlank()) { "Bot token is required" }
        require(chatId.isNotBlank()) { "Chat ID is required" }
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
