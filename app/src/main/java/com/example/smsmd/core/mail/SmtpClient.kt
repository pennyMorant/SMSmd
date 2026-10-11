package com.example.smsmd.core.mail

import com.example.smsmd.core.settings.SmtpProfile
import com.example.smsmd.core.settings.SmtpSecurity
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.text.DateFormat
import java.util.Date
import java.util.Properties

class SmtpClient {
    fun sendSms(
        profile: SmtpProfile,
        password: String,
        sender: String,
        body: String,
        receivedAt: Long,
    ) {
        val content = smsContent(sender, body, receivedAt)
        send(
            profile = profile,
            password = password,
            content = content,
        )
    }

    fun sendTest(profile: SmtpProfile, password: String) {
        send(
            profile = profile,
            password = password,
            content = EmailContent(
                subject = "SMSmd is ready",
                plainText = "SMTP configuration verified. SMS forwarding is ready to use.",
                html = """
                    <!doctype html>
                    <html lang="en">
                      <body style="margin:0;padding:24px;background:#f4f7f5;font-family:Arial,sans-serif;color:#1a1c1b;">
                        <div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #dce5df;border-radius:12px;padding:28px;">
                          <div style="font-size:12px;font-weight:700;letter-spacing:1px;color:#356859;">SMSmd</div>
                          <h1 style="margin:12px 0 8px;font-size:24px;line-height:1.3;">SMTP verified</h1>
                          <p style="margin:0;color:#4b5550;font-size:16px;line-height:1.6;">Your SMTP profile is working. SMS forwarding is ready to use.</p>
                        </div>
                      </body>
                    </html>
                """.trimIndent(),
            ),
        )
    }

    private fun send(profile: SmtpProfile, password: String, content: EmailContent) {
        require(profile.isConfigured) { "SMTP configuration is incomplete" }
        require(password.isNotBlank()) { "SMTP password is missing" }

        val properties = Properties().apply {
            put("mail.smtp.host", profile.smtpHost)
            put("mail.smtp.port", profile.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", TIMEOUT_MS)
            put("mail.smtp.timeout", TIMEOUT_MS)
            put("mail.smtp.writetimeout", TIMEOUT_MS)
            put("mail.smtp.ssl.checkserveridentity", "true")
            if (profile.smtpSecurity == SmtpSecurity.STARTTLS) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            } else {
                put("mail.smtp.ssl.enable", "true")
            }
        }
        val session = Session.getInstance(properties, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(profile.smtpUsername, password)
        }).apply { debug = false }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(profile.fromAddress))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(profile.recipientAddresses))
            setSubject(content.subject, Charsets.UTF_8.name())
            setContent(
                MimeMultipart("alternative").apply {
                    addBodyPart(MimeBodyPart().apply {
                        setText(content.plainText, Charsets.UTF_8.name())
                    })
                    addBodyPart(MimeBodyPart().apply {
                        setContent(content.html, "text/html; charset=UTF-8")
                    })
                },
            )
            sentDate = Date()
        }
        Transport.send(message)
    }

    companion object {
        private const val TIMEOUT_MS = "20000"

        internal fun smsContent(sender: String, body: String, receivedAt: Long): EmailContent {
            val received = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(receivedAt))
            val safeSender = escapeHtml(sender)
            val safeBody = escapeHtml(body)
            val safeReceived = escapeHtml(received)
            return EmailContent(
                subject = "SMS from ${maskSender(sender)}",
                plainText = """
                    New SMS message

                    From: $sender
                    Received: $received

                    $body

                    Forwarded by SMSmd
                """.trimIndent(),
                html = """
                    <!doctype html>
                    <html lang="en">
                      <body style="margin:0;padding:24px;background:#f4f7f5;font-family:Arial,sans-serif;color:#1a1c1b;">
                        <div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #dce5df;border-radius:12px;overflow:hidden;">
                          <div style="padding:24px 28px 20px;border-bottom:1px solid #e3e9e5;">
                            <div style="font-size:12px;font-weight:700;letter-spacing:1px;color:#356859;">SMSmd</div>
                            <h1 style="margin:10px 0 0;font-size:24px;line-height:1.3;">New message</h1>
                          </div>
                          <div style="padding:22px 28px;">
                            <table role="presentation" style="width:100%;border-collapse:collapse;font-size:14px;line-height:1.5;">
                              <tr>
                                <td style="width:84px;padding:0 0 10px;color:#66716b;vertical-align:top;">From</td>
                                <td style="padding:0 0 10px;font-weight:600;word-break:break-word;">$safeSender</td>
                              </tr>
                              <tr>
                                <td style="width:84px;padding:0;color:#66716b;vertical-align:top;">Received</td>
                                <td style="padding:0;">$safeReceived</td>
                              </tr>
                            </table>
                            <div style="margin-top:22px;padding:18px;background:#f0f5f2;border-radius:8px;font-size:17px;line-height:1.6;white-space:pre-wrap;word-break:break-word;">$safeBody</div>
                          </div>
                          <div style="padding:14px 28px;background:#f8faf9;color:#758079;font-size:12px;">Forwarded by SMSmd</div>
                        </div>
                      </body>
                    </html>
                """.trimIndent(),
            )
        }

        internal fun escapeHtml(value: String): String = buildString(value.length) {
            value.forEach { character ->
                append(
                    when (character) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> character
                    },
                )
            }
        }

        fun maskSender(sender: String): String {
            if (sender.length <= 4) return sender
            return sender.take(2) + "*".repeat((sender.length - 4).coerceAtMost(6)) + sender.takeLast(2)
        }

        fun safeError(error: Throwable): String = when (error) {
            is MessagingException -> error.message?.take(160) ?: "SMTP error"
            is IllegalArgumentException -> error.message?.take(160) ?: "Invalid configuration"
            else -> "Network or mail server error"
        }
    }
}

internal data class EmailContent(
    val subject: String,
    val plainText: String,
    val html: String,
)
