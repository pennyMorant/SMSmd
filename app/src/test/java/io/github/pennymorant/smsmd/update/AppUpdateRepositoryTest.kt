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
    fun selectsOnlyExactSignedApkAsset() {
        val expected = asset("SMSmd-1.2.3.apk")
        val release = selectRelease(
            "v1.2.3",
            listOf(
                asset("SMSmd-1.2.3-debug.apk"),
                expected,
                asset("other.apk"),
            ),
        )

        assertEquals("1.2.3", release.version)
        assertEquals(expected, release.asset)
    }

    @Test
    fun rejectsWrongHostMimeDigestAndDuplicateAssets() {
        assertInvalid(asset("SMSmd-1.2.3.apk", host = "example.com"))
        assertInvalid(asset("SMSmd-1.2.3.apk", contentType = "application/octet-stream"))
        assertInvalid(asset("SMSmd-1.2.3.apk", digest = "sha256:bad"))
        assertThrows(IOException::class.java) {
            selectRelease("v1.2.3", listOf(asset("SMSmd-1.2.3.apk"), asset("SMSmd-1.2.3.apk")))
        }
    }

    @Test
    fun rejectsMalformedReleaseTags() {
        assertThrows(IOException::class.java) { normalizedVersion("release-1.2.3") }
        assertThrows(IOException::class.java) { normalizedVersion("v1.2") }
    }

    @Test
    fun validatesPackageVersionAndSigningIdentity() {
        val current = identity(versionName = "1.2.3", versionCode = 12)
        val valid = identity(versionName = "1.2.4", versionCode = 13)
        assertEquals(null, validateApkIdentity(valid, current, PACKAGE_NAME, "1.2.4"))

        assertEquals(
            "The downloaded APK has the wrong package name",
            validateApkIdentity(valid.copy(packageName = "other.app"), current, PACKAGE_NAME, "1.2.4"),
        )
        assertEquals(
            "The downloaded APK version does not match the release",
            validateApkIdentity(valid, current, PACKAGE_NAME, "1.2.5"),
        )
        assertEquals(
            "The downloaded APK is not newer than the installed app",
            validateApkIdentity(valid.copy(versionCode = 12), current, PACKAGE_NAME, "1.2.4"),
        )
        assertEquals(
            "The downloaded APK signing certificate does not match SMSmd",
            validateApkIdentity(valid.copy(signerDigests = setOf("different")), current, PACKAGE_NAME, "1.2.4"),
        )
    }

    @Test
    fun calculatesBoundedDownloadProgress() {
        assertEquals(50, DownloadSnapshot(2, 0, 500, 1_000).progress)
        assertEquals(100, DownloadSnapshot(2, 0, 1_500, 1_000).progress)
        assertEquals(null, DownloadSnapshot(1, 0, 0, -1).progress)
    }

    private fun assertInvalid(asset: ReleaseAsset) {
        assertThrows(IOException::class.java) { selectRelease("v1.2.3", listOf(asset)) }
    }

    private fun asset(
        name: String,
        host: String = "github.com",
        contentType: String = "application/vnd.android.package-archive",
        digest: String = "sha256:${"a".repeat(64)}",
    ) = ReleaseAsset(
        name = name,
        contentType = contentType,
        downloadUrl = "https://$host/pennyMorant/SMSmd/releases/download/v1.2.3/$name",
        size = 1024L,
        digest = digest,
    )

    private fun identity(
        versionName: String,
        versionCode: Long,
    ) = ApkIdentity(PACKAGE_NAME, versionName, versionCode, setOf("release-certificate"))

    private companion object {
        const val PACKAGE_NAME = "io.github.pennymorant.smsmd"
    }
}
