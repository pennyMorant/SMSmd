package io.github.pennymorant.smsmd.update

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun detectsNewerStableAndPrereleaseVersions() {
        assertTrue(isNewerVersion("v1.0.3", "1.0.2"))
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        assertTrue(isNewerVersion("1.1.0", "1.1.0-beta.2"))
        assertTrue(isNewerVersion("1.1.0-beta.2", "1.1.0-beta.1"))
    }

    @Test
    fun rejectsSameOlderAndPrereleaseDowngrades() {
        assertFalse(isNewerVersion("v1.0.2", "1.0.2"))
        assertFalse(isNewerVersion("1.0.1", "1.0.2"))
        assertFalse(isNewerVersion("1.1.0-beta.1", "1.1.0"))
    }

    @Test
    fun acceptsOnlyTheExpectedGitHubReleasePage() {
        val release = releaseInfo(
            "v1.2.3",
            "https://github.com/pennyMorant/SMSmd/releases/tag/v1.2.3",
        )

        assertEquals("1.2.3", release.version)
        assertEquals("v1.2.3", release.tag)
    }

    @Test
    fun rejectsUnexpectedReleasePages() {
        assertThrows(IOException::class.java) {
            releaseInfo("v1.2.3", "https://example.com/pennyMorant/SMSmd/releases/tag/v1.2.3")
        }
        assertThrows(IOException::class.java) {
            releaseInfo("v1.2.3", "https://github.com/other/repository/releases/tag/v1.2.3")
        }
        assertThrows(IOException::class.java) {
            releaseInfo("v1.2.3", "not-a-url")
        }
    }

    @Test
    fun rejectsMalformedReleaseTags() {
        assertThrows(IOException::class.java) { normalizedVersion("release-1.2.3") }
        assertThrows(IOException::class.java) { normalizedVersion("v1.2") }
    }
}
