package io.github.pennymorant.smsmd.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pennymorant.smsmd.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val version: String) : AppUpdateState
    data class Downloading(val version: String, val progress: Int?, val detail: String?) : AppUpdateState
    data class Verifying(val version: String) : AppUpdateState
    data class ReadyToInstall(val version: String) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

data class ReleaseAsset(
    val name: String,
    val contentType: String,
    val downloadUrl: String,
    val size: Long,
    val digest: String,
)

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val asset: ReleaseAsset,
)

data class SavedUpdate(
    val downloadId: Long,
    val release: ReleaseInfo,
    val verified: Boolean,
    val failureMessage: String?,
)

data class DownloadSnapshot(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val progress: Int?
        get() = if (totalBytes > 0L) {
            ((downloadedBytes.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }
}

internal data class ApkIdentity(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signerDigests: Set<String>,
)

sealed interface VerificationResult {
    data class Success(val uri: Uri) : VerificationResult
    data class Failure(val message: String) : VerificationResult
}

class AppUpdateRepository(private val context: Context) {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

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
            val tag = release.getString("tag_name")
            val assetsJson = release.getJSONArray("assets")
            val assets = (0 until assetsJson.length()).map { index ->
                val asset = assetsJson.getJSONObject(index)
                ReleaseAsset(
                    name = asset.getString("name"),
                    contentType = asset.optString("content_type"),
                    downloadUrl = asset.getString("browser_download_url"),
                    size = asset.getLong("size"),
                    digest = asset.optString("digest"),
                )
            }
            selectRelease(tag, assets)
        } finally {
            connection.disconnect()
        }
    }

    fun enqueueDownload(release: ReleaseInfo): Long {
        val file = updateFile(release)
        file.parentFile?.mkdirs()
        if (file.exists() && !file.delete()) throw IOException("Could not replace the previous download")

        val request = DownloadManager.Request(release.asset.downloadUrl.toUri())
            .setTitle("SMSmd ${release.version}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "$UPDATE_DIRECTORY/${release.asset.name}",
            )
        val downloadId = downloadManager.enqueue(request)
        saveUpdate(SavedUpdate(downloadId, release, verified = false, failureMessage = null))
        val monitorRequest = OneTimeWorkRequestBuilder<UpdateMonitorWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPDATE_MONITOR_WORK,
            ExistingWorkPolicy.REPLACE,
            monitorRequest,
        )
        return downloadId
    }

    fun loadSavedUpdate(): SavedUpdate? {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val tag = preferences.getString(KEY_TAG, null)
        val version = preferences.getString(KEY_VERSION, null)
        val name = preferences.getString(KEY_ASSET_NAME, null)
        val contentType = preferences.getString(KEY_CONTENT_TYPE, null)
        val url = preferences.getString(KEY_DOWNLOAD_URL, null)
        val digest = preferences.getString(KEY_DIGEST, null)
        val size = preferences.getLong(KEY_SIZE, -1L)
        if (
            downloadId < 0L || tag.isNullOrBlank() || version.isNullOrBlank() ||
            name.isNullOrBlank() || contentType.isNullOrBlank() || url.isNullOrBlank() ||
            digest.isNullOrBlank() || size <= 0L
        ) return null
        return SavedUpdate(
            downloadId = downloadId,
            release = ReleaseInfo(tag, version, ReleaseAsset(name, contentType, url, size, digest)),
            verified = preferences.getBoolean(KEY_VERIFIED, false),
            failureMessage = preferences.getString(KEY_FAILURE_MESSAGE, null),
        )
    }

    fun downloadSnapshot(downloadId: Long): DownloadSnapshot? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DownloadSnapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                downloadedBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                ),
                totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    suspend fun verify(savedUpdate: SavedUpdate): VerificationResult = withContext(Dispatchers.IO) {
        val file = updateFile(savedUpdate.release)
        val failure = validateDownloadedApk(file, savedUpdate.release)
        if (failure != null) {
            markFailure(savedUpdate.downloadId, failure)
            VerificationResult.Failure(failure)
        } else {
            val uri = downloadManager.getUriForDownloadedFile(savedUpdate.downloadId)
            if (uri == null) {
                val message = "The downloaded APK is unavailable"
                markFailure(savedUpdate.downloadId, message)
                VerificationResult.Failure(message)
            } else {
                if (markVerified(savedUpdate.downloadId)) {
                    VerificationResult.Success(uri)
                } else {
                    VerificationResult.Failure("The update was replaced by a newer download")
                }
            }
        }
    }

    fun installationUri(): Uri? {
        val saved = loadSavedUpdate() ?: return null
        if (!saved.verified) return null
        return downloadManager.getUriForDownloadedFile(saved.downloadId)
    }

    fun markFailure(downloadId: Long, message: String): Boolean {
        if (preferences.getLong(KEY_DOWNLOAD_ID, -1L) != downloadId) return false
        preferences.edit {
            putBoolean(KEY_VERIFIED, false)
            putString(KEY_FAILURE_MESSAGE, message)
        }
        return true
    }

    fun clearSavedUpdate(removeDownload: Boolean = true) {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (removeDownload && downloadId >= 0L) downloadManager.remove(downloadId)
        WorkManager.getInstance(context).cancelUniqueWork(UPDATE_MONITOR_WORK)
        preferences.edit { clear() }
    }

    fun downloadFailureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage for the update"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Update storage is unavailable"
        DownloadManager.ERROR_HTTP_DATA_ERROR,
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
        DownloadManager.ERROR_TOO_MANY_REDIRECTS,
        -> "GitHub could not complete the download"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "A previous update file could not be replaced"
        else -> "Update download failed (code $reason)"
    }

    fun downloadWaitingMessage(snapshot: DownloadSnapshot): String? = when (snapshot.status) {
        DownloadManager.STATUS_PENDING -> "Waiting to start"
        DownloadManager.STATUS_PAUSED -> when (snapshot.reason) {
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Retrying download"
            else -> "Download paused"
        }
        else -> null
    }

    private fun validateDownloadedApk(file: File, release: ReleaseInfo): String? {
        if (!file.isFile) return "The downloaded APK file is missing"
        if (file.length() != release.asset.size) return "The downloaded APK size does not match the release"
        val expectedDigest = release.asset.digest.removePrefix("sha256:").lowercase()
        if (file.sha256() != expectedDigest) return "The downloaded APK failed its SHA-256 check"

        val candidate = packageArchiveInfo(file) ?: return "Android could not read the downloaded APK"
        val current = installedPackageInfo()
        return validateApkIdentity(
            candidate = candidate.identity(),
            current = current.identity(),
            expectedPackageName = context.packageName,
            expectedVersionName = release.version,
        )
    }

    private fun packageArchiveInfo(file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(
        file.absolutePath,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    )

    private fun installedPackageInfo(): PackageInfo = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    )

    private fun PackageInfo.signerDigests(): Set<String> =
        signingInfo?.apkContentsSigners.orEmpty().map { certificate ->
            certificate.toByteArray().sha256()
        }.toSet()

    private fun PackageInfo.identity() = ApkIdentity(
        packageName = packageName,
        versionName = versionName,
        versionCode = longVersionCode,
        signerDigests = signerDigests(),
    )

    private fun updateFile(release: ReleaseInfo): File {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Update storage is unavailable")
        return File(File(downloads, UPDATE_DIRECTORY), release.asset.name)
    }

    private fun markVerified(downloadId: Long): Boolean {
        if (preferences.getLong(KEY_DOWNLOAD_ID, -1L) != downloadId) return false
        preferences.edit {
            putBoolean(KEY_VERIFIED, true)
            remove(KEY_FAILURE_MESSAGE)
        }
        return true
    }

    private fun saveUpdate(update: SavedUpdate) {
        preferences.edit {
            putLong(KEY_DOWNLOAD_ID, update.downloadId)
            putString(KEY_TAG, update.release.tag)
            putString(KEY_VERSION, update.release.version)
            putString(KEY_ASSET_NAME, update.release.asset.name)
            putString(KEY_CONTENT_TYPE, update.release.asset.contentType)
            putString(KEY_DOWNLOAD_URL, update.release.asset.downloadUrl)
            putLong(KEY_SIZE, update.release.asset.size)
            putString(KEY_DIGEST, update.release.asset.digest)
            putBoolean(KEY_VERIFIED, update.verified)
            remove(KEY_FAILURE_MESSAGE)
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/pennyMorant/SMSmd/releases/latest"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val UPDATE_DIRECTORY = "updates"
        const val UPDATE_MONITOR_WORK = "smsmd-update-monitor"
        const val PREFERENCES_NAME = "app_updates"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_TAG = "tag"
        const val KEY_VERSION = "version"
        const val KEY_ASSET_NAME = "asset_name"
        const val KEY_CONTENT_TYPE = "content_type"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_SIZE = "size"
        const val KEY_DIGEST = "digest"
        const val KEY_VERIFIED = "verified"
        const val KEY_FAILURE_MESSAGE = "failure_message"
    }
}

internal fun selectRelease(tag: String, assets: List<ReleaseAsset>): ReleaseInfo {
    val version = normalizedVersion(tag)
    val expectedName = "SMSmd-$version.apk"
    val asset = assets.singleOrNull { candidate ->
        candidate.name == expectedName &&
            candidate.contentType == "application/vnd.android.package-archive" &&
            candidate.size > 0L &&
            candidate.digest.matches(Regex("sha256:[0-9a-fA-F]{64}")) &&
            runCatching { URI(candidate.downloadUrl) }.getOrNull()?.let { uri ->
                uri.scheme == "https" &&
                    uri.host == "github.com" &&
                    uri.path.startsWith("/pennyMorant/SMSmd/releases/download/")
            } == true
    } ?: throw IOException("The release does not contain the expected signed APK")
    return ReleaseInfo(tag, version, asset)
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
    repeat(maxOf(leftPre.size, requireNotNull(rightPre).size)) { index ->
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

internal fun validateApkIdentity(
    candidate: ApkIdentity,
    current: ApkIdentity,
    expectedPackageName: String,
    expectedVersionName: String,
): String? = when {
    candidate.packageName != expectedPackageName -> "The downloaded APK has the wrong package name"
    candidate.versionName != expectedVersionName -> "The downloaded APK version does not match the release"
    candidate.versionCode <= current.versionCode -> "The downloaded APK is not newer than the installed app"
    candidate.signerDigests.isEmpty() || candidate.signerDigests != current.signerDigests ->
        "The downloaded APK signing certificate does not match SMSmd"
    else -> null
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
