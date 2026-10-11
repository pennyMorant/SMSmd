package io.github.pennymorant.smsmd

import io.github.pennymorant.smsmd.core.mail.SmtpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmtpClientTest {
    @Test
    fun masksLongSenderButKeepsEndpoints() {
        assertEquals("+1*****34", SmtpClient.maskSender("+15551234"))
    }

    @Test
    fun leavesShortSenderReadable() {
        assertEquals("Bank", SmtpClient.maskSender("Bank"))
    }

    @Test
    fun smsTemplateOmitsSubscriptionMetadata() {
        val content = SmtpClient.smsContent("Bank", "Your code is 1234", 0L)

        assertFalse(content.plainText.contains("subscription", ignoreCase = true))
        assertFalse(content.html.contains("subscription", ignoreCase = true))
        assertTrue(content.subject.startsWith("SMS from "))
    }

    @Test
    fun smsTemplateEscapesUntrustedHtml() {
        val content = SmtpClient.smsContent("<Bank>", "Use <1234> & confirm", 0L)

        assertTrue(content.html.contains("&lt;Bank&gt;"))
        assertTrue(content.html.contains("Use &lt;1234&gt; &amp; confirm"))
        assertFalse(content.html.contains("Use <1234>"))
    }
}
