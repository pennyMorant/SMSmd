package com.example.smsmd.core.settings

enum class SmtpSecurity { STARTTLS, TLS }

enum class SmtpProvider { GMAIL, OUTLOOK, CUSTOM }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

const val DEFAULT_PROFILE_ID = "default-gmail"

data class SmtpProviderDefaults(
    val name: String,
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
)

fun SmtpProvider.defaults(): SmtpProviderDefaults = when (this) {
    SmtpProvider.GMAIL -> SmtpProviderDefaults("Gmail", "smtp.gmail.com", 587, SmtpSecurity.STARTTLS)
    SmtpProvider.OUTLOOK -> SmtpProviderDefaults(
        "Outlook",
        "smtp-mail.outlook.com",
        587,
        SmtpSecurity.STARTTLS,
    )
    SmtpProvider.CUSTOM -> SmtpProviderDefaults("Custom SMTP", "", 587, SmtpSecurity.STARTTLS)
}

data class SmtpProfile(
    val id: String,
    val name: String,
    val provider: SmtpProvider,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: SmtpSecurity,
    val smtpUsername: String,
    val fromAddress: String,
    val recipientAddresses: String,
    val smtpVerified: Boolean = false,
) {
    val isConfigured: Boolean
        get() = name.isNotBlank() && smtpHost.isNotBlank() && smtpPort in 1..65535 &&
            smtpUsername.isNotBlank() && fromAddress.contains('@') && recipientAddresses.contains('@')
}

data class AppSettings(
    val forwardingEnabled: Boolean = false,
    val activeProfileId: String = DEFAULT_PROFILE_ID,
    val failureNotifications: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

fun defaultGmailProfile() = SmtpProfile(
    id = DEFAULT_PROFILE_ID,
    name = SmtpProvider.GMAIL.defaults().name,
    provider = SmtpProvider.GMAIL,
    smtpHost = SmtpProvider.GMAIL.defaults().host,
    smtpPort = SmtpProvider.GMAIL.defaults().port,
    smtpSecurity = SmtpProvider.GMAIL.defaults().security,
    smtpUsername = "",
    fromAddress = "",
    recipientAddresses = "",
)
