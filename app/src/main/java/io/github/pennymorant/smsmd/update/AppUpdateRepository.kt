package io.github.pennymorant.smsmd.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val version: String) : AppUpdateState
    data class UpdateAvailable(val version: String, val releaseUrl: String) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val pageUrl: String,
)

class AppUpdateRepository {
    suspend fun latestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.setRequestProperty("User-Agent", "SMSmd update check")

            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub returned ${connection.responseCode}")
            }

            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            releaseInfo(
                tag = release.getString("tag_name"),
                pageUrl = release.getString("html_url"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/pennyMorant/SMSmd/releases/latest"
    }
}

internal fun releaseInfo(tag: String, pageUrl: String): ReleaseInfo {
    val version = normalizedVersion(tag)
    val uri = runCatching { URI(pageUrl) }.getOrNull()
    if (
        uri?.scheme != "https" || uri.host != "github.com" ||
        uri.path != "/pennyMorant/SMSmd/releases/tag/$tag"
    ) {
        throw IOException("GitHub returned an invalid release page")
    }
    return ReleaseInfo(tag, version, pageUrl)
}

internal fun normalizedVersion(raw: String): String {
    val match = Regex("^v?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)$").matchEntire(raw.trim())
        ?: throw IOException("The release tag is not a supported semantic version")
    return match.groupValues[1]
}

internal fun isNewerVersion(latest: String, current: String): Boolean =
    compareVersions(latest, current) > 0

internal fun compareVersions(left: String, right: String): Int {
    fun parse(raw: String): Pair<List<Int>, List<String>?> {
        val normalized = normalizedVersion(raw)
        val core = normalized.substringBefore('-').split('.').map(String::toInt)
        val prerelease = normalized.substringAfter('-', missingDelimiterValue = "")
            .takeIf(String::isNotEmpty)
            ?.split('.')
        return core to prerelease
    }

    val (leftCore, leftPre) = parse(left)
    val (rightCore, rightPre) = parse(right)
    leftCore.indices.forEach { index ->
        if (leftCore[index] != rightCore[index]) return leftCore[index].compareTo(rightCore[index])
    }
    if (leftPre == null && rightPre != null) return 1
    if (leftPre != null && rightPre == null) return -1
    if (leftPre == null) return 0
    checkNotNull(rightPre)
    val length = maxOf(leftPre.size, rightPre.size)
    repeat(length) { index ->
        val leftPart = leftPre.getOrNull(index) ?: return -1
        val rightPart = rightPre.getOrNull(index) ?: return 1
        val leftNumber = leftPart.toIntOrNull()
        val rightNumber = rightPart.toIntOrNull()
        val comparison = when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> leftPart.compareTo(rightPart)
        }
        if (comparison != 0) return comparison
    }
    return 0
}
