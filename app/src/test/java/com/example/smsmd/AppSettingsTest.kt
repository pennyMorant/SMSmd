package com.example.smsmd

import com.example.smsmd.core.settings.SmtpProvider
import com.example.smsmd.core.settings.SmtpSecurity
import com.example.smsmd.core.settings.defaults
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
}
