package com.gallery.core.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.gallery.core.data.updater.UpdateConfig
import com.gallery.core.domain.model.AppUpdateInfo
import com.gallery.core.domain.model.AppUpdateState
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

        // Silent background check rate-limiting: max once per 24 hours
        val now = System.currentTimeMillis()
        if (!isManual) {
            val settings = settingsRepository.settings.first()
            if (now - settings.lastUpdateCheckMillis < 24 * 60 * 60 * 1000L) {
                return
            }
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

                // Update last checked timestamp
                settingsRepository.setLastUpdateCheckMillis(System.currentTimeMillis())

                if (updateInfo == null) {
                    if (isManual) {
                        _updateState.value = AppUpdateState.Error("No valid release or APK asset found.")
                    } else {
                        _updateState.value = AppUpdateState.Idle
                    }
                    return@withContext
                }

                currentUpdateInfo = updateInfo

                // Retrieve current app version
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                val currentVersionName = packageInfo.versionName ?: "1.0.0"

                val isNewer = isNewerVersion(
                    remoteVersionCode = updateInfo.versionCode,
                    remoteVersionName = updateInfo.versionName,
                    installedVersionCode = currentVersionCode,
                    installedVersionName = currentVersionName
                )

                if (isNewer) {
                    val skippedCode = settingsRepository.settings.first().skippedVersionCode
                    if (!isManual && updateInfo.versionCode > 0 && updateInfo.versionCode <= skippedCode) {
                        _updateState.value = AppUpdateState.Idle
                    } else {
                        _updateState.value = AppUpdateState.UpdateAvailable(updateInfo)
                    }
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

    override suspend fun downloadUpdate(info: AppUpdateInfo) {
        cancelDownload()
        currentUpdateInfo = info

        downloadJob = repositoryScope.launch {
            val updatesDir = File(context.cacheDir, "updates")
            val tempFile = File(updatesDir, "gallery-v${info.versionName}.apk.tmp")
            val targetFile = File(updatesDir, "gallery-v${info.versionName}.apk")

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
                        setRequestProperty("User-Agent", "Gallery-Android-App")
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

    override suspend fun skipVersion(versionCode: Long) {
        settingsRepository.setSkippedVersionCode(versionCode)
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
            setRequestProperty("User-Agent", "Gallery-Android-App")
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
            val versionCode = root.optLong("versionCode", 0L).let { code ->
                if (code > 0L) code else parseVersionCodeFromVersionName(versionName)
            }

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
            setRequestProperty("User-Agent", "Gallery-Android-App")
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

            // Locate APK asset in release
            val assets = root.optJSONArray("assets") ?: return null
            var apkDownloadUrl = ""
            var fileSizeBytes = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    apkDownloadUrl = asset.optString("browser_download_url", "")
                    fileSizeBytes = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkDownloadUrl.isBlank()) return null

            // Derive versionCode: check release body for "versionCode: X", or calculate from tag
            val versionCodeRegex = Regex("""(?i)versionCode[\s:=]+(\d+)""")
            val matchedCode = versionCodeRegex.find(releaseNotes)?.groupValues?.get(1)?.toLongOrNull()
            val derivedCode = matchedCode
                ?: parseVersionCodeFromVersionName(rawVersionName)

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

    private fun parseVersionCodeFromVersionName(versionName: String): Long {
        return try {
            val parts = versionName.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return 1L
            val major = parts.getOrNull(0)?.toLongOrNull() ?: 1L
            val minor = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val patch = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            major * 10000L + minor * 100L + patch
        } catch (_: Exception) {
            1L
        }
    }

    private fun isNewerVersion(
        remoteVersionCode: Long,
        remoteVersionName: String,
        installedVersionCode: Long,
        installedVersionName: String
    ): Boolean {
        // First check versionCode if both are non-zero and differ
        if (remoteVersionCode > 0 && installedVersionCode > 0 && remoteVersionCode != installedVersionCode) {
            return remoteVersionCode > installedVersionCode
        }
        // Fallback to semantic version comparison
        return compareSemanticVersions(remoteVersionName, installedVersionName) > 0
    }

    private fun compareSemanticVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        val length = maxOf(parts1.size, parts2.size)

        for (i in 0 until length) {
            val num1 = parts1.getOrElse(i) { 0 }
            val num2 = parts2.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }
        return 0
    }
}
