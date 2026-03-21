package com.viswa2k.syncpad.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.viswa2k.syncpad.BuildConfig
import com.viswa2k.syncpad.repository.SettingsRepository
import com.viswa2k.syncpad.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles app update checks, APK downloads, and installation.
 *
 * The app queries the same Supabase project configured for sync using the
 * public REST endpoint. Releases are expected in an `app_versions` table and
 * APK files in a public `app-releases` storage bucket.
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val TABLE_NAME = "app_versions"
        private const val APK_FILE_NAME = "syncpad_update.apk"
        private val CHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)
    }

    private val baseUrl: String = BuildConfig.SYNC_BASE_URL.trimEnd('/')
    private val apiKey: String = BuildConfig.SYNC_API_KEY
    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            AppLogger.d(TAG, message)
        }.apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private var downloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null

    suspend fun checkForUpdateIfNeeded(): UpdateCheckResult {
        val autoCheckEnabled = settingsRepository.getAutoCheckUpdatesFlow().first()

        if (!autoCheckEnabled) {
            return UpdateCheckResult.NoUpdate
        }

        val lastCheckTime = settingsRepository.getLastUpdateCheckTime()
        val now = System.currentTimeMillis()
        if ((now - lastCheckTime) < CHECK_INTERVAL_MS) {
            AppLogger.d(TAG, "Skipping update check - checked recently")
            return UpdateCheckResult.NoUpdate
        }

        return checkForUpdate(showErrors = false)
    }

    suspend fun forceCheckForUpdate(): UpdateCheckResult {
        return checkForUpdate(showErrors = true)
    }

    private suspend fun checkForUpdate(showErrors: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        settingsRepository.setLastUpdateCheckTime(System.currentTimeMillis())

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            AppLogger.w(TAG, "Update check skipped - SYNC_BASE_URL or SYNC_API_KEY missing")
            _updateState.value = UpdateState.Idle
            return@withContext if (showErrors) {
                UpdateCheckResult.Error("Update backend is not configured")
            } else {
                UpdateCheckResult.NoUpdate
            }
        }

        return@withContext try {
            val currentVersionCode = BuildConfig.VERSION_CODE
            val url = "$baseUrl/rest/v1/$TABLE_NAME?select=version_code,version_name,apk_url,release_notes,is_force_update,min_supported_version,file_size_bytes,is_active&is_active=eq.true&version_code=gt.$currentVersionCode&order=version_code.desc&limit=1"

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    AppLogger.e(TAG, "Update check failed: ${response.code} - $responseBody")
                    _updateState.value = UpdateState.Idle
                    return@use if (showErrors) {
                        UpdateCheckResult.Error("Server error: ${response.code}")
                    } else {
                        UpdateCheckResult.NoUpdate
                    }
                }

                val versions = gson.fromJson(responseBody, Array<AppVersionDto>::class.java)?.toList().orEmpty()
                val latestVersion = versions.firstOrNull()

                if (latestVersion == null) {
                    AppLogger.d(TAG, "No updates available")
                    _updateState.value = UpdateState.Idle
                    return@use UpdateCheckResult.NoUpdate
                }

                val updateInfo = UpdateInfo(
                    versionCode = latestVersion.versionCode,
                    versionName = latestVersion.versionName,
                    apkUrl = latestVersion.apkUrl,
                    releaseNotes = latestVersion.releaseNotes.orEmpty(),
                    isForceUpdate = latestVersion.isForceUpdate ||
                        currentVersionCode < (latestVersion.minSupportedVersion ?: 1),
                    fileSizeBytes = latestVersion.fileSizeBytes
                )

                AppLogger.i(TAG, "Update available: ${updateInfo.versionName} (${updateInfo.versionCode})")
                _updateState.value = UpdateState.UpdateAvailable(updateInfo)
                UpdateCheckResult.UpdateAvailable(updateInfo)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking for updates", e)
            _updateState.value = UpdateState.Idle
            if (showErrors) {
                UpdateCheckResult.Error(e.message ?: "Unknown error")
            } else {
                UpdateCheckResult.NoUpdate
            }
        }
    }

    fun downloadUpdate(updateInfo: UpdateInfo) {
        AppLogger.i(TAG, "Starting update download: ${updateInfo.versionName}")
        _downloadProgress.value = 0
        _updateState.value = UpdateState.Downloading(0)

        val destinationFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(updateInfo.apkUrl)).apply {
            setTitle("SyncPad Update v${updateInfo.versionName}")
            setDescription("Downloading app update...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        downloadId = downloadManager.enqueue(request)
        registerDownloadReceiver()
        trackDownloadProgress(downloadManager)
    }

    fun installUpdate() {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )

        if (!apkFile.exists()) {
            AppLogger.e(TAG, "Downloaded APK not found: ${apkFile.absolutePath}")
            _updateState.value = UpdateState.Error("Update file not found")
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(installIntent)
    }

    fun cancelDownload() {
        if (downloadId != -1L) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.remove(downloadId)
            downloadId = -1L
        }
        unregisterDownloadReceiver()
        _downloadProgress.value = 0
        _updateState.value = UpdateState.Idle
    }

    fun dismiss() {
        _updateState.value = UpdateState.Idle
    }

    private fun registerDownloadReceiver() {
        unregisterDownloadReceiver()
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId == downloadId) {
                    AppLogger.i(TAG, "Update download completed")
                    _downloadProgress.value = 100
                    _updateState.value = UpdateState.ReadyToInstall
                    unregisterDownloadReceiver()
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, filter)
        }
    }

    private fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to unregister download receiver", e)
            }
            downloadReceiver = null
        }
    }

    private fun trackDownloadProgress(downloadManager: DownloadManager) {
        Thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val bytesDownloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val bytesTotal = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        val status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                downloading = false
                                _downloadProgress.value = 100
                            }

                            DownloadManager.STATUS_FAILED -> {
                                downloading = false
                                _updateState.value = UpdateState.Error("Download failed")
                            }

                            DownloadManager.STATUS_RUNNING -> {
                                if (bytesTotal > 0) {
                                    val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                                    _downloadProgress.value = progress
                                    _updateState.value = UpdateState.Downloading(progress)
                                }
                            }
                        }
                    }
                }

                if (downloading) {
                    Thread.sleep(500)
                }
            }
        }.start()
    }
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    val isForceUpdate: Boolean,
    val fileSizeBytes: Long? = null
) {
    val fileSizeMB: String
        get() = fileSizeBytes?.let {
            String.format("%.1f MB", it / (1024.0 * 1024.0))
        } ?: "Unknown size"
}

sealed class UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}

data class AppVersionDto(
    @SerializedName("version_code") val versionCode: Int,
    @SerializedName("version_name") val versionName: String,
    @SerializedName("apk_url") val apkUrl: String,
    @SerializedName("release_notes") val releaseNotes: String? = null,
    @SerializedName("is_force_update") val isForceUpdate: Boolean = false,
    @SerializedName("min_supported_version") val minSupportedVersion: Int? = null,
    @SerializedName("file_size_bytes") val fileSizeBytes: Long? = null,
    @SerializedName("is_active") val isActive: Boolean = true
)
