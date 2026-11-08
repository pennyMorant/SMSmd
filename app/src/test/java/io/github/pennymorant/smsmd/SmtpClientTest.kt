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
        assertTrue(content.html.contains("Use &lt;"))
        assertTrue(content.html.contains("1234"))
        assertTrue(content.html.contains("&gt; &amp; confirm"))
        assertFalse(content.html.contains("Use <1234>"))
    }

    @Test
    fun smsTemplateSeparatesNumbersForQuickSelection() {
        val body = "Your verification code is 123456. Call +1 (555) 123-4567 if this was not you."
        val content = SmtpClient.smsContent(
            "Bank",
            body,
            0L,
        )

        assertEquals(listOf("123456", "+1 (555) 123-4567"), SmtpClient.numericTokens(body))
        assertTrue(content.plainText.contains("Numbers: 123456 | +1 (555) 123-4567"))
        assertTrue(content.html.contains(">Numbers</div>"))
        assertTrue(content.html.contains("user-select:all"))
        assertTrue(content.html.contains("letter-spacing:1.5px"))
        assertTrue(content.html.contains(">123456</code>"))
    }
}
