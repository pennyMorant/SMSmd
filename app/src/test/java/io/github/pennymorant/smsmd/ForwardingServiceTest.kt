package io.github.pennymorant.smsmd

import io.github.pennymorant.smsmd.forwarding.ForwardingService
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardingServiceTest {
    @Test
    fun formatsPersistentForwardingCountForNotification() {
        assertEquals("0 forwarded", ForwardingService.forwardedCountLabel(0))
        assertEquals("12 forwarded", ForwardingService.forwardedCountLabel(12))
    }
}
