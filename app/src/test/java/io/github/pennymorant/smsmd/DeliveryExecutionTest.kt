package io.github.pennymorant.smsmd

import io.github.pennymorant.smsmd.forwarding.DeliveryExecutionGate
import io.github.pennymorant.smsmd.forwarding.isPermanentSmtpFailure
import io.github.pennymorant.smsmd.forwarding.nextDeliveryAttempt
import io.github.pennymorant.smsmd.forwarding.telegramPartStartIndex
import io.github.pennymorant.smsmd.forwarding.telegramRetryDelaySeconds
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.internet.InternetAddress
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryExecutionTest {
    @Test
    fun smtp4xxFailureIsRetryable() {
        val error = SMTPAddressFailedException(
            InternetAddress("target@example.com"),
            "RCPT TO",
            451,
            "Temporary local problem",
        )

        assertFalse(isPermanentSmtpFailure(error))
    }

    @Test
    fun nestedSmtp4xxFailureIsRetryable() {
        val nested = SMTPSendFailedException(
            "DATA",
            421,
            "Service unavailable",
            null,
            emptyArray(),
            emptyArray(),
            emptyArray(),
        )
        val error = jakarta.mail.SendFailedException("Delivery deferred", nested)

        assertFalse(isPermanentSmtpFailure(error))
    }

    @Test
    fun smtp5xxAndAuthenticationFailuresArePermanent() {
        val rejected = SMTPAddressFailedException(
            InternetAddress("target@example.com"),
            "RCPT TO",
            550,
            "Mailbox unavailable",
        )

        assertTrue(isPermanentSmtpFailure(rejected))
        assertTrue(isPermanentSmtpFailure(AuthenticationFailedException("Invalid credentials")))
        assertTrue(isPermanentSmtpFailure(SSLHandshakeException("Certificate rejected")))
        assertFalse(isPermanentSmtpFailure(IOException("Connection reset")))
    }

    @Test
    fun deliveryGateSerializesConcurrentSends() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        coroutineScope {
            repeat(4) {
                launch(Dispatchers.Default) {
                    DeliveryExecutionGate.run {
                        val current = active.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, current) }
                        delay(40)
                        active.decrementAndGet()
                    }
                }
            }
        }

        assertEquals(1, maximum.get())
    }

    @Test
    fun delayedTelegramRetriesKeepPersistentAttemptCount() {
        assertEquals(1, nextDeliveryAttempt(storedAttemptCount = 0, workManagerRunAttemptCount = 0))
        assertEquals(4, nextDeliveryAttempt(storedAttemptCount = 3, workManagerRunAttemptCount = 0))
        assertEquals(5, nextDeliveryAttempt(storedAttemptCount = 1, workManagerRunAttemptCount = 4))
    }

    @Test
    fun telegramRetryAfterUsesOnlyPositiveServerDelay() {
        assertEquals(45L, telegramRetryDelaySeconds(45))
        assertEquals(null, telegramRetryDelaySeconds(0))
        assertEquals(null, telegramRetryDelaySeconds(-1))
        assertEquals(null, telegramRetryDelaySeconds(null))
    }

    @Test
    fun telegramMultipartRetryResumesAtSavedPart() {
        assertEquals(0, telegramPartStartIndex(savedPartIndex = 0, partCount = 3))
        assertEquals(2, telegramPartStartIndex(savedPartIndex = 2, partCount = 3))
        assertEquals(3, telegramPartStartIndex(savedPartIndex = 3, partCount = 3))
        assertEquals(0, telegramPartStartIndex(savedPartIndex = 4, partCount = 3))
        assertEquals(0, telegramPartStartIndex(savedPartIndex = -1, partCount = 3))
    }
}
