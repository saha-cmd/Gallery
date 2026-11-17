package com.gallery.core.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gallery.core.data.updater.UpdateConfig
import com.gallery.core.domain.model.AppUpdateInfo
import com.gallery.core.domain.model.AppUpdateState
import com.gallery.core.domain.model.GallerySettings
import com.gallery.core.domain.repository.AppUpdateRepository
import com.gallery.core.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : AppUpdateRepository {

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private var currentUpdateInfo: AppUpdateInfo? = null

    override suspend fun checkForUpdates(isManual: Boolean) {
        val currentState = _updateState.value
        if (currentState is AppUpdateState.Checking || currentState is AppUpdateState.Downloading) {
            return
        }

        if (isManual) {
            _updateState.value = AppUpdateState.Checking
        }

        withContext(Dispatchers.IO) {
            try {
                val hasCustomManifest = UpdateConfig.CUSTOM_MANIFEST_URL.isNotBlank()
                val hasGitHubConfig = UpdateConfig.GITHUB_OWNER.isNotBlank() &&
                        UpdateConfig.GITHUB_OWNER != "YOUR_GITHUB_USERNAME" &&
                        UpdateConfig.GITHUB_REPO.isNotBlank()

                if (!hasCustomManifest && !hasGitHubConfig) {
                    if (isManual) {
                        _updateState.value = AppUpdateState.Error(
                            "Update source not configured. Please set GITHUB_OWNER / GITHUB_REPO or CUSTOM_MANIFEST_URL in UpdateConfig.kt."
                        )
                    }
                    return@withContext
                }

                val updateInfo = if (hasCustomManifest) {
                    fetchCustomManifest(UpdateConfig.CUSTOM_MANIFEST_URL.trim())
                } else {
                    fetchGitHubLatestRelease(
                        UpdateConfig.GITHUB_OWNER.trim(),
                        UpdateConfig.GITHUB_REPO.trim()
                    )
                }

                val now = System.currentTimeMillis()
                settingsRepository.setLastUpdateCheckMillis(now)

                if (updateInfo == null) {
                    if (isManual) {
                        _updateState.value = AppUpdateState.Error("No valid release or APK asset found.")
                    } else {
                        _updateState.value = AppUpdateState.Idle
                    }
                    return@withContext
                }

                currentUpdateInfo = updateInfo

                // Retrieve current app version name
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val currentVersionName = packageInfo.versionName ?: "1.0.0"

                val isNewer = isNewerVersion(
                    remote = updateInfo.versionName,
                    local = currentVersionName
                )

                if (isNewer) {
                    if (!isManual) {
                        val settings = settingsRepository.settings.first()
                        if (shouldSuppressUpdateOnBoot(updateInfo, settings, now)) {
                            _updateState.value = AppUpdateState.Idle
                            return@withContext
                        }
                    }
                    _updateState.value = AppUpdateState.UpdateAvailable(updateInfo)
                } else {
                    if (isManual) {
                        _updateState.value = AppUpdateState.UpToDate
                    } else {
                        _updateState.value = AppUpdateState.Idle
                    }
                }
            } catch (e: Exception) {
                if (isManual) {
                    _updateState.value = AppUpdateState.Error(
                        e.localizedMessage ?: "Failed to connect to update server."
                    )
                } else {
                    _updateState.value = AppUpdateState.Idle
                }
            }
        }
    }

    private fun shouldSuppressUpdateOnBoot(
        info: AppUpdateInfo,
        settings: GallerySettings,
        nowMillis: Long
    ): Boolean {
        // 1. Skipped version rule:
        // Suppress until a strictly newer version than skippedVersionName is published
        if (settings.skippedVersionName.isNotBlank()) {
            val isNewerThanSkipped = isNewerVersion(
                remote = info.versionName,
                local = settings.skippedVersionName
            )
            if (!isNewerThanSkipped) {
                return true
            }
        }

        // Backward compatibility check for numeric skippedVersionCode
        if (settings.skippedVersionCode > 0L && info.versionCode > 0L && info.versionCode <= settings.skippedVersionCode) {
            return true
        }

        // 2. Remind me later (24-hour snooze) rule:
        if (settings.remindLaterTimestampMillis > 0L) {
            val elapsed = nowMillis - settings.remindLaterTimestampMillis
            val twentyFourHours = 24 * 60 * 60 * 1000L
            if (elapsed in 0 until twentyFourHours) {
                // If a strictly newer release was published AFTER snooze was set, do not suppress
                val publishedMillis = runCatching {
                    if (info.publishedAt.isNotBlank()) {
                        Instant.parse(info.publishedAt).toEpochMilli()
                    } else 0L
                }.getOrDefault(0L)

                if (publishedMillis <= settings.remindLaterTimestampMillis) {
                    return true
                }
            }
        }

        return false
    }

    override suspend fun downloadUpdate(info: AppUpdateInfo) {
        cancelDownload()
        currentUpdateInfo = info

        downloadJob = repositoryScope.launch {
            val updatesDir = File(context.cacheDir, "updates")
            val targetFile = File(updatesDir, "Gallery-v${info.versionName}-signed.apk")
            val tempFile = File(updatesDir, "Gallery-v${info.versionName}-signed.apk.tmp")

            try {
                // Clean up stale files before beginning
                cleanStaleUpdateFiles(updatesDir)
                updatesDir.mkdirs()
                if (tempFile.exists()) tempFile.delete()
                if (targetFile.exists()) targetFile.delete()

                _updateState.value = AppUpdateState.Downloading(
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = info.fileSizeBytes
                )

                // Follow redirects up to 5 times (e.g., GitHub -> AWS S3)
                var currentUrl = info.apkDownloadUrl
                var connection: HttpURLConnection? = null
                var redirects = 0
                while (redirects < 5) {
                    val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 15000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Gallery-Android-Updater")
                        setRequestProperty("Accept", "*/*")
                    }
                    val status = conn.responseCode
                    if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_SEE_OTHER ||
                        status == 307 || status == 308
                    ) {
                        val newUrl = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (!newUrl.isNullOrBlank()) {
                            currentUrl = newUrl
                            redirects++
                            continue
                        }
                    }
                    connection = conn
                    break
                }

                val conn = connection ?: throw IOException("Could not connect to download URL")
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    throw IOException("Server returned HTTP $responseCode (${conn.responseMessage})")
                }

                val totalBytes = conn.contentLengthLong.takeIf { it > 0 }
                    ?: info.fileSizeBytes.takeIf { it > 0 }
                    ?: -1L

                var downloadedBytes = 0L
                var lastEmitMillis = 0L
                val buffer = ByteArray(8192)

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (isActive) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastEmitMillis >= 100 || downloadedBytes == totalBytes) {
                                lastEmitMillis = now
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else 0f
                                _updateState.value = AppUpdateState.Downloading(
                                    progress = progress,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes
                                )
                            }
                        }
                    }
                }

                if (!isActive) {
                    if (tempFile.exists()) tempFile.delete()
                    return@launch
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    throw IOException("Downloaded APK file is empty or missing.")
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                _updateState.value = AppUpdateState.ReadyToInstall(
                    apkFile = targetFile,
                    info = info
                )
            } catch (e: CancellationException) {
                if (tempFile.exists()) tempFile.delete()
                // Download was cancelled by user
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                _updateState.value = AppUpdateState.Error(
                    "Download failed: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val updatesDir = File(context.cacheDir, "updates")
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
        val info = currentUpdateInfo
        if (info != null) {
            _updateState.value = AppUpdateState.UpdateAvailable(info)
        } else {
            _updateState.value = AppUpdateState.Idle
        }
    }

    override suspend fun skipVersion(versionName: String) {
        settingsRepository.setSkippedVersion(versionName)
        _updateState.value = AppUpdateState.Idle
    }

    override suspend fun skipVersion(versionCode: Long) {
        settingsRepository.setSkippedVersionCode(versionCode)
        _updateState.value = AppUpdateState.Idle
    }

    override suspend fun remindMeLater() {
        settingsRepository.setRemindLaterTimestamp(System.currentTimeMillis())
        _updateState.value = AppUpdateState.Idle
    }

    override fun dismissUpdateState() {
        _updateState.value = AppUpdateState.Idle
    }

    private fun cleanStaleUpdateFiles(updatesDir: File) {
        if (!updatesDir.exists()) return
        updatesDir.listFiles()?.forEach { file ->
            try {
                file.delete()
            } catch (_: Exception) {}
        }
    }

    private fun fetchCustomManifest(manifestUrl: String): AppUpdateInfo? {
        val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Gallery-Android-Updater")
            instanceFollowRedirects = true
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Manifest server returned HTTP ${connection.responseCode}")
            }
            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)

            val apkUrl = root.optString("apkUrl", "").ifBlank {
                root.optString("apkDownloadUrl", "")
            }
            if (apkUrl.isBlank()) return null

            val versionName = root.optString("versionName", "1.0.0")
            val versionCode = root.optLong("versionCode", 0L)

            AppUpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                releaseNotes = root.optString("releaseNotes", "").ifBlank { "New version available." },
                apkDownloadUrl = apkUrl,
                fileSizeBytes = root.optLong("fileSizeBytes", 0L),
                publishedAt = root.optString("publishedAt", "")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchGitHubLatestRelease(owner: String, repo: String): AppUpdateInfo? {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Gallery-Android-Updater")
            instanceFollowRedirects = true
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub API returned HTTP ${connection.responseCode}")
            }
            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)

            val tagName = root.optString("tag_name", "")
            val rawVersionName = tagName.removePrefix("v").removePrefix("V").trim()
            val releaseNotes = root.optString("body", "Bug fixes and improvements.")
            val publishedAt = root.optString("published_at", "")

            // Locate APK asset in release (prioritize signed release APK, or any .apk)
            val assets = root.optJSONArray("assets") ?: return null
            var apkDownloadUrl = ""
            var fileSizeBytes = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    if (assetName.contains("signed", ignoreCase = true) || apkDownloadUrl.isBlank()) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        fileSizeBytes = asset.optLong("size", 0L)
                        if (assetName.contains("signed", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            if (apkDownloadUrl.isBlank()) return null

            val versionCodeRegex = Regex("""(?i)versionCode[\s:=]+(\d+)""")
            val matchedCode = versionCodeRegex.find(releaseNotes)?.groupValues?.get(1)?.toLongOrNull()
            val derivedCode = matchedCode ?: 0L

            AppUpdateInfo(
                versionCode = derivedCode,
                versionName = rawVersionName.ifBlank { "1.0.0" },
                releaseNotes = releaseNotes,
                apkDownloadUrl = apkDownloadUrl,
                fileSizeBytes = fileSizeBytes,
                publishedAt = publishedAt
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Semantic version comparator.
     * Strips leading 'v'/'V' and any metadata suffix after '-' (e.g., -beta, -signed).
     * Compares numeric components segment-by-segment.
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val rClean = remote.trim().removePrefix("v").removePrefix("V")
        val lClean = local.trim().removePrefix("v").removePrefix("V")

        val rParts = rClean.substringBefore("-").split(".").mapNotNull { part ->
            part.filter { it.isDigit() }.toIntOrNull()
        }
        val lParts = lClean.substringBefore("-").split(".").mapNotNull { part ->
            part.filter { it.isDigit() }.toIntOrNull()
        }

        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r != l) {
                return r > l
            }
        }
        return false
    }
}
