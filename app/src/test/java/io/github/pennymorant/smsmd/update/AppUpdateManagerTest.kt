package io.github.pennymorant.smsmd.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun detectsNewerVersion() {
        assertTrue(isNewerVersion("v1.0.3", "1.0.2"))
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertTrue(isNewerVersion("2.0", "1.99.99"))
    }

    @Test
    fun rejectsSameOrOlderVersion() {
        assertFalse(isNewerVersion("v1.0.2", "1.0.2"))
        assertFalse(isNewerVersion("1.0.1", "1.0.2"))
        assertFalse(isNewerVersion("1.0", "1.0.0"))
    }
}
