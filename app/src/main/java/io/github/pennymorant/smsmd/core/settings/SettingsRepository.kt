package io.github.pennymorant.smsmd.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { values ->
        AppSettings(
            forwardingEnabled = values[Keys.enabled] ?: false,
            activeProfileId = values[Keys.activeProfileId] ?: DEFAULT_PROFILE_ID,
            failureNotifications = values[Keys.failureNotifications] ?: false,
            themeMode = enumValueOrDefault(values[Keys.themeMode], ThemeMode.SYSTEM),
            activeEngine = enumValueOrDefault(
                values[Keys.activeEngine],
                if (values[Keys.telegramEnabled] == true) DeliveryEngine.TELEGRAM else DeliveryEngine.EMAIL,
            ),
            telegramChatId = values[Keys.telegramChatId].orEmpty(),
            telegramVerified = values[Keys.telegramVerified] ?: false,
        )
    }

    val profiles: Flow<List<SmtpProfile>> = context.settingsDataStore.data.map { values ->
        val ids = values[Keys.profileIds].orEmpty()
        if (values[Keys.profilesInitialized] != true) {
            listOf(legacyProfile(values))
        } else {
            ids.map { id -> readProfile(values, id) }.sortedBy { it.name.lowercase() }
        }
    }

    val activeProfile: Flow<SmtpProfile?> = combine(settings, profiles) { appSettings, allProfiles ->
        allProfiles.firstOrNull { it.id == appSettings.activeProfileId }
    }

    suspend fun saveProfile(profile: SmtpProfile) {
        context.settingsDataStore.edit { values ->
            values[Keys.profilesInitialized] = true
            values[Keys.profileIds] = values[Keys.profileIds].orEmpty() + profile.id
            values[stringKey(profile.id, "name")] = profile.name.trim()
            values[stringKey(profile.id, "provider")] = profile.provider.name
            values[stringKey(profile.id, "host")] = profile.smtpHost.trim()
            values[intKey(profile.id, "port")] = profile.smtpPort
            values[stringKey(profile.id, "security")] = profile.smtpSecurity.name
            values[stringKey(profile.id, "username")] = profile.smtpUsername.trim()
            values[stringKey(profile.id, "from")] = profile.fromAddress.trim()
            values[stringKey(profile.id, "recipients")] = profile.recipientAddresses.trim()
            values[booleanKey(profile.id, "verified")] = profile.smtpVerified
            if (values[Keys.activeProfileId].isNullOrBlank()) values[Keys.activeProfileId] = profile.id
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.settingsDataStore.edit { values ->
            values[Keys.profilesInitialized] = true
            val remaining = values[Keys.profileIds].orEmpty() - profileId
            values[Keys.profileIds] = remaining
            PROFILE_STRING_FIELDS.forEach { values.remove(stringKey(profileId, it)) }
            values.remove(intKey(profileId, "port"))
            values.remove(booleanKey(profileId, "verified"))
            if (values[Keys.activeProfileId] == profileId) {
                values[Keys.activeProfileId] = remaining.firstOrNull().orEmpty()
                val telegramActive = values[Keys.activeEngine] == DeliveryEngine.TELEGRAM.name ||
                    (values[Keys.activeEngine] == null && values[Keys.telegramEnabled] == true)
                if (!telegramActive) values[Keys.enabled] = false
            }
        }
    }

    suspend fun setActiveProfile(profileId: String) {
        context.settingsDataStore.edit {
            it[Keys.activeProfileId] = profileId
            it[Keys.activeEngine] = DeliveryEngine.EMAIL.name
        }
    }

    suspend fun setActiveEngine(engine: DeliveryEngine) {
        context.settingsDataStore.edit { it[Keys.activeEngine] = engine.name }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.enabled] = enabled }
    }

    suspend fun setFailureNotifications(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.failureNotifications] = enabled }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.themeMode] = themeMode.name }
    }

    suspend fun saveTelegram(chatId: String, verified: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.telegramChatId] = chatId.trim()
            it[Keys.telegramVerified] = verified
        }
    }

    private fun readProfile(values: Preferences, id: String): SmtpProfile {
        val provider = enumValueOrDefault(values[stringKey(id, "provider")], SmtpProvider.CUSTOM)
        val defaults = provider.defaults()
        return SmtpProfile(
            id = id,
            name = values[stringKey(id, "name")] ?: defaults.name,
            provider = provider,
            smtpHost = values[stringKey(id, "host")] ?: defaults.host,
            smtpPort = values[intKey(id, "port")] ?: defaults.port,
            smtpSecurity = enumValueOrDefault(values[stringKey(id, "security")], defaults.security),
            smtpUsername = values[stringKey(id, "username")].orEmpty(),
            fromAddress = values[stringKey(id, "from")].orEmpty(),
            recipientAddresses = values[stringKey(id, "recipients")].orEmpty(),
            smtpVerified = values[booleanKey(id, "verified")] ?: false,
        )
    }

    private fun legacyProfile(values: Preferences): SmtpProfile {
        val defaults = SmtpProvider.GMAIL.defaults()
        return SmtpProfile(
            id = DEFAULT_PROFILE_ID,
            name = defaults.name,
            provider = SmtpProvider.GMAIL,
            smtpHost = values[Keys.legacyHost].orEmpty().ifBlank { defaults.host },
            smtpPort = values[Keys.legacyPort] ?: defaults.port,
            smtpSecurity = enumValueOrDefault(values[Keys.legacySecurity], defaults.security),
            smtpUsername = values[Keys.legacyUsername].orEmpty(),
            fromAddress = values[Keys.legacyFrom].orEmpty(),
            recipientAddresses = values[Keys.legacyRecipients].orEmpty(),
            smtpVerified = values[Keys.legacyVerified] ?: false,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private fun stringKey(id: String, field: String) = stringPreferencesKey("profile_${id}_$field")
    private fun intKey(id: String, field: String) = intPreferencesKey("profile_${id}_$field")
    private fun booleanKey(id: String, field: String) = booleanPreferencesKey("profile_${id}_$field")

    private object Keys {
        val enabled = booleanPreferencesKey("forwarding_enabled")
        val failureNotifications = booleanPreferencesKey("failure_notifications")
        val themeMode = stringPreferencesKey("theme_mode")
        val activeProfileId = stringPreferencesKey("active_profile_id")
        val profileIds = stringSetPreferencesKey("profile_ids")
        val profilesInitialized = booleanPreferencesKey("profiles_initialized")
        val telegramEnabled = booleanPreferencesKey("telegram_enabled")
        val activeEngine = stringPreferencesKey("active_delivery_engine")
        val telegramChatId = stringPreferencesKey("telegram_chat_id")
        val telegramVerified = booleanPreferencesKey("telegram_verified")
        val legacyHost = stringPreferencesKey("smtp_host")
        val legacyPort = intPreferencesKey("smtp_port")
        val legacySecurity = stringPreferencesKey("smtp_security")
        val legacyUsername = stringPreferencesKey("smtp_username")
        val legacyFrom = stringPreferencesKey("from_address")
        val legacyRecipients = stringPreferencesKey("recipient_addresses")
        val legacyVerified = booleanPreferencesKey("smtp_verified")
    }

    private companion object {
        val PROFILE_STRING_FIELDS = listOf(
            "name",
            "provider",
            "host",
            "security",
            "username",
            "from",
            "recipients",
        )
    }
}
