package io.github.pennymorant.smsmd

import io.github.pennymorant.smsmd.core.settings.SmtpProvider
import io.github.pennymorant.smsmd.core.settings.SmtpSecurity
import io.github.pennymorant.smsmd.core.settings.DeliveryEngine
import io.github.pennymorant.smsmd.core.settings.defaults
import io.github.pennymorant.smsmd.core.settings.legacyDeliveryEngine
import io.github.pennymorant.smsmd.forwarding.DeliverySnapshot
import io.github.pennymorant.smsmd.forwarding.DeliverySnapshotCodec
import io.github.pennymorant.smsmd.core.database.ChannelState
import io.github.pennymorant.smsmd.forwarding.resolveDeliveryChannels
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun gmailProviderUsesOfficialSmtpDefaults() {
        val defaults = SmtpProvider.GMAIL.defaults()

        assertEquals("smtp.gmail.com", defaults.host)
        assertEquals(587, defaults.port)
        assertEquals(SmtpSecurity.STARTTLS, defaults.security)
    }

    @Test
    fun outlookProviderUsesOfficialSmtpDefaults() {
        val defaults = SmtpProvider.OUTLOOK.defaults()

        assertEquals("smtp-mail.outlook.com", defaults.host)
        assertEquals(587, defaults.port)
        assertEquals(SmtpSecurity.STARTTLS, defaults.security)
    }

    @Test
    fun emailSelectionNeverSchedulesTelegram() {
        val channels = resolveDeliveryChannels(
            activeEngine = DeliveryEngine.EMAIL,
            smtpVerified = true,
            telegramReady = true,
        )

        assertEquals(ChannelState.PENDING, channels.smtp)
        assertEquals(ChannelState.SKIPPED, channels.telegram)
    }

    @Test
    fun telegramSelectionNeverSchedulesEmail() {
        val channels = resolveDeliveryChannels(
            activeEngine = DeliveryEngine.TELEGRAM,
            smtpVerified = true,
            telegramReady = true,
        )

        assertEquals(ChannelState.SKIPPED, channels.smtp)
        assertEquals(ChannelState.PENDING, channels.telegram)
    }

    @Test
    fun unverifiedLegacyTelegramFallsBackToEmail() {
        assertEquals(DeliveryEngine.EMAIL, legacyDeliveryEngine(telegramEnabled = true, telegramVerified = false))
        assertEquals(DeliveryEngine.TELEGRAM, legacyDeliveryEngine(telegramEnabled = true, telegramVerified = true))
    }

    @Test
    fun emailDeliverySnapshotRoundTripsCredentialsAndTarget() {
        val snapshot = DeliverySnapshot.Email(
            profile = io.github.pennymorant.smsmd.core.settings.SmtpProfile(
                id = "profile-1",
                name = "Primary",
                provider = SmtpProvider.GMAIL,
                smtpHost = "smtp.gmail.com",
                smtpPort = 587,
                smtpSecurity = SmtpSecurity.STARTTLS,
                smtpUsername = "sender@example.com",
                fromAddress = "sender@example.com",
                recipientAddresses = "one@example.com,two@example.com",
                smtpVerified = true,
            ),
            password = "line=one:#two!",
        )

        assertEquals(snapshot, DeliverySnapshotCodec.decode(DeliverySnapshotCodec.encode(snapshot)))
    }

    @Test
    fun telegramDeliverySnapshotRoundTripsCredentialsAndTarget() {
        val snapshot = DeliverySnapshot.Telegram(
            token = "12345:token=value",
            chatId = "-1001234567890",
        )

        assertEquals(snapshot, DeliverySnapshotCodec.decode(DeliverySnapshotCodec.encode(snapshot)))
    }
}
