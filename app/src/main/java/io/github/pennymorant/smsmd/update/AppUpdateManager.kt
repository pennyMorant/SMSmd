package io.github.pennymorant.smsmd.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import io.github.pennymorant.smsmd.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val version: String) : AppUpdateState
    data class Downloading(val version: String) : AppUpdateState
    data class ReadyToInstall(val version: String) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

data class ReleaseInfo(
    val version: String,
    val apkUrl: String,
    val fileName: String,
)

class AppUpdateManager(private val context: Context) {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    suspend fun latestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.setRequestProperty("User-Agent", "SMSmd/${BuildConfig.VERSION_NAME}")

            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub returned ${connection.responseCode}")
            }

            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.getString("tag_name")
            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
                ?: throw IOException("The latest release does not include an APK")

            ReleaseInfo(
                version = version,
                apkUrl = apk.getString("browser_download_url"),
                fileName = apk.getString("name"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun enqueueDownload(release: ReleaseInfo): Long {
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), release.fileName).delete()
        val request = DownloadManager.Request(release.apkUrl.toUri())
            .setTitle("SMSmd ${release.version}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                release.fileName,
            )
        return downloadManager.enqueue(request)
    }

    fun downloadedUri(downloadId: Long): Uri? {
        if (downloadStatus(downloadId) != DownloadManager.STATUS_SUCCESSFUL) return null
        return downloadManager.getUriForDownloadedFile(downloadId)
    }

    fun downloadStatus(downloadId: Long): Int? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            return cursor.getInt(statusColumn)
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/pennyMorant/SMSmd/releases/latest"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

internal fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(version: String): List<Int> = version
        .trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { part -> part.toIntOrNull() ?: 0 }

    val latestParts = parts(latest)
    val currentParts = parts(current)
    repeat(maxOf(latestParts.size, currentParts.size)) { index ->
        val latestPart = latestParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}
