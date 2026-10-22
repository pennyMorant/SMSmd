package io.github.pennymorant.smsmd

import io.github.pennymorant.smsmd.core.settings.SmtpProvider
import io.github.pennymorant.smsmd.core.settings.SmtpSecurity
import io.github.pennymorant.smsmd.core.settings.DeliveryEngine
import io.github.pennymorant.smsmd.core.settings.defaults
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
}
