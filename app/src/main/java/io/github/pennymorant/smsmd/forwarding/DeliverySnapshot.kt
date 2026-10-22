package io.github.pennymorant.smsmd.forwarding

import io.github.pennymorant.smsmd.core.settings.SmtpProfile
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

internal sealed interface DeliverySnapshot {
    data class Email(val profile: SmtpProfile, val password: String) : DeliverySnapshot
    data class Telegram(val token: String, val chatId: String) : DeliverySnapshot
}

internal object DeliverySnapshotCodec {
    fun encode(snapshot: DeliverySnapshot): String {
        val values = Properties().apply {
            setProperty("version", "1")
            when (snapshot) {
                is DeliverySnapshot.Email -> {
                    setProperty("engine", "EMAIL")
                    setProperty("profile.id", snapshot.profile.id)
                    setProperty("profile.name", snapshot.profile.name)
                    setProperty("profile.provider", snapshot.profile.provider.name)
                    setProperty("profile.host", snapshot.profile.smtpHost)
                    setProperty("profile.port", snapshot.profile.smtpPort.toString())
                    setProperty("profile.security", snapshot.profile.smtpSecurity.name)
                    setProperty("profile.username", snapshot.profile.smtpUsername)
                    setProperty("profile.from", snapshot.profile.fromAddress)
                    setProperty("profile.recipients", snapshot.profile.recipientAddresses)
                    setProperty("secret", snapshot.password)
                }
                is DeliverySnapshot.Telegram -> {
                    setProperty("engine", "TELEGRAM")
                    setProperty("secret", snapshot.token)
                    setProperty("chatId", snapshot.chatId)
                }
            }
        }
        return StringWriter().also { values.store(it, null) }.toString()
    }

    fun decode(raw: String): DeliverySnapshot {
        val values = Properties().apply { load(StringReader(raw)) }
        require(values.required("version") == "1") { "Unsupported delivery snapshot" }
        return when (values.required("engine")) {
            "EMAIL" -> DeliverySnapshot.Email(
                profile = SmtpProfile(
                    id = values.required("profile.id"),
                    name = values.required("profile.name"),
                    provider = enumValueOf(values.required("profile.provider")),
                    smtpHost = values.required("profile.host"),
                    smtpPort = values.required("profile.port").toInt(),
                    smtpSecurity = enumValueOf(values.required("profile.security")),
                    smtpUsername = values.required("profile.username"),
                    fromAddress = values.required("profile.from"),
                    recipientAddresses = values.required("profile.recipients"),
                    smtpVerified = true,
                ),
                password = values.required("secret"),
            )
            "TELEGRAM" -> DeliverySnapshot.Telegram(
                token = values.required("secret"),
                chatId = values.required("chatId"),
            )
            else -> error("Unsupported delivery engine")
        }
    }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() } ?: error("Missing delivery snapshot field: $key")
}
