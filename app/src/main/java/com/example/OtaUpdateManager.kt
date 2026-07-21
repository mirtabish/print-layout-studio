package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// Update Info Data Model
data class OtaUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val updateUrl: String,
    val releaseNotes: String,
    val isForceUpdate: Boolean
)

// States representing the update check and download process
sealed class OtaUpdateState {
    object Idle : OtaUpdateState()
    object Checking : OtaUpdateState()
    data class UpdateAvailable(val info: OtaUpdateInfo) : OtaUpdateState()
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val info: OtaUpdateInfo
    ) : OtaUpdateState()
    data class ReadyToInstall(val apkFile: File, val info: OtaUpdateInfo) : OtaUpdateState()
    object UpToDate : OtaUpdateState()
    data class Error(val message: String) : OtaUpdateState()
}

class OtaUpdateManager(private val context: Context) {
    
    private val _updateState = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
    val updateState: StateFlow<OtaUpdateState> = _updateState

    // SharedPreferences to persist custom update URL or settings
    private val prefs = context.getSharedPreferences("ota_update_prefs", Context.MODE_PRIVATE)

    var updateUrl: String
        get() = prefs.getString("update_url", "https://raw.githubusercontent.com/mirtabish/print-layout-studio/main/update.json") ?: ""
        set(value) {
            prefs.edit().putString("update_url", value).apply()
        }

    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", false) // Default false for real live downloads
        set(value) {
            prefs.edit().putBoolean("demo_mode", value).apply()
        }

    // Get current app version code and name
    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    // Main update checking mechanism
    suspend fun checkForUpdates(forceNotifyUpToDate: Boolean = false) {
        _updateState.value = OtaUpdateState.Checking
        
        if (demoMode) {
            withContext(Dispatchers.IO) {
                delay(1000)
            }
            val currentCode = getCurrentVersionCode()
            val mockUpdate = OtaUpdateInfo(
                latestVersionCode = currentCode + 1,
                latestVersionName = "1.2.0-ota",
                updateUrl = "https://github.com/mirtabish/print-layout-studio/releases/download/v1.1/app-debug.apk",
                releaseNotes = "• Completely dynamic grid layout logic mapping margins exactly down to 0.1cm!\n• Added beautiful credit badges to creator Mir Zulkifal.\n• In-app seamless OTA download and package installer integration.",
                isForceUpdate = false
            )
            _updateState.value = OtaUpdateState.UpdateAvailable(mockUpdate)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val urlObj = URL(updateUrl)
                val connection = urlObj.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doInput = true

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val latestCode = json.getInt("latestVersionCode")
                    val latestName = json.getString("latestVersionName")
                    val downloadUrl = json.getString("updateUrl")
                    val notes = json.optString("releaseNotes", "No release notes available.")
                    val force = json.optBoolean("isForceUpdate", false)

                    val info = OtaUpdateInfo(latestCode, latestName, downloadUrl, notes, force)
                    val currentCode = getCurrentVersionCode()

                    if (latestCode > currentCode) {
                        _updateState.value = OtaUpdateState.UpdateAvailable(info)
                    } else {
                        _updateState.value = if (forceNotifyUpToDate) OtaUpdateState.UpToDate else OtaUpdateState.Idle
                    }
                } else {
                    _updateState.value = OtaUpdateState.Error("HTTP Error: $responseCode when checking $updateUrl")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("OtaUpdateManager", "Failed to check updates", e)
                _updateState.value = OtaUpdateState.Error(e.localizedMessage ?: "Unknown network error")
            }
        }
    }

    // Helper to unpack a nested .apk inside a downloaded .zip artifact if needed
    private fun extractApkIfZip(tempFile: File, targetApkFile: File): Boolean {
        try {
            var foundApk = false
            ZipInputStream(tempFile.inputStream()).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        FileOutputStream(targetApkFile).use { out ->
                            zipInput.copyTo(out)
                        }
                        foundApk = true
                        break
                    }
                    entry = zipInput.nextEntry
                }
            }
            if (foundApk && isValidApkHeader(targetApkFile)) return true
        } catch (e: Exception) {
            Log.d("OtaUpdateManager", "Not a zip artifact with nested apk: ${e.message}")
        }
        return false
    }

    // Validate that the file starts with ZIP/APK magic bytes ('P' 'K' 0x03 0x04) and is > 100KB
    private fun isValidApkHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 100 * 1024) return false
        try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read == 4) {
                    return header[0] == 'P'.toByte() &&
                           header[1] == 'K'.toByte() &&
                           header[2] == 0x03.toByte() &&
                           header[3] == 0x04.toByte()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    // Download APK directly inside the app with live progress & validation
    suspend fun downloadAndInstallApk(info: OtaUpdateInfo) {
        val downloadDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        val tempDownloadFile = File(downloadDir, "download_temp_${info.latestVersionCode}.tmp")
        val apkFile = File(downloadDir, "update_v${info.latestVersionCode}.apk")

        withContext(Dispatchers.IO) {
            try {
                var currentUrl = info.updateUrl
                
                // If updateUrl is a web page release link, open browser directly
                if (currentUrl.endsWith("/releases") || currentUrl.endsWith("/releases/latest") || !currentUrl.contains(".")) {
                    withContext(Dispatchers.Main) {
                        openInBrowser(currentUrl)
                        _updateState.value = OtaUpdateState.Idle
                    }
                    return@withContext
                }

                var connection: HttpURLConnection
                var redirectCount = 0

                // Follow HTTP redirects (e.g., GitHub releases redirect to AWS S3 CDN)
                while (true) {
                    val urlObj = URL(currentUrl)
                    connection = urlObj.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "PrintLayoutStudio-OTA")

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 || code == 308) {
                        currentUrl = connection.getHeaderField("Location")
                        redirectCount++
                        if (redirectCount > 5) {
                            throw Exception("Too many redirects")
                        }
                        continue
                    }
                    break
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    _updateState.value = OtaUpdateState.Error("Failed to download file. Server returned HTTP ${connection.responseCode}")
                    return@withContext
                }

                val totalBytes = connection.contentLengthLong
                val inputStream: InputStream = connection.inputStream
                val outputStream = FileOutputStream(tempDownloadFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloadedBytes = 0L
                var lastProgressReportTime = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressReportTime > 150 || downloadedBytes == totalBytes) {
                        lastProgressReportTime = now
                        val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                        _updateState.value = OtaUpdateState.Downloading(
                            progressPercent = percent,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            info = info
                        )
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                connection.disconnect()

                // Process downloaded file
                var validApkReady = false
                if (extractApkIfZip(tempDownloadFile, apkFile)) {
                    validApkReady = true
                } else {
                    tempDownloadFile.copyTo(apkFile, overwrite = true)
                    validApkReady = isValidApkHeader(apkFile)
                }

                tempDownloadFile.delete()

                if (validApkReady) {
                    _updateState.value = OtaUpdateState.ReadyToInstall(apkFile, info)
                    withContext(Dispatchers.Main) {
                        installApk(apkFile)
                    }
                } else {
                    _updateState.value = OtaUpdateState.Error(
                        "Downloaded file is not a valid Android APK binary.\n\nThe update link (${info.updateUrl}) may point to an HTML webpage or unsupported format. Click 'Open Release Page' to download directly."
                    )
                }

            } catch (e: Exception) {
                Log.e("OtaUpdateManager", "Error downloading update", e)
                _updateState.value = OtaUpdateState.Error("Download failed: ${e.localizedMessage}")
            }
        }
    }

    // Check if app has permission to install packages on Android 8.0+
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    // Open Unknown App Sources Settings
    fun openUnknownAppSourcesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("OtaUpdateManager", "Could not open unknown app sources settings", e)
            }
        }
    }

    // Trigger Android System Package Installer
    fun installApk(apkFile: File) {
        try {
            if (!canRequestPackageInstalls()) {
                openUnknownAppSourcesSettings()
                return
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("OtaUpdateManager", "Failed to launch package installer", e)
            _updateState.value = OtaUpdateState.Error("Could not launch package installer: ${e.localizedMessage}")
        }
    }

    fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("OtaUpdateManager", "Could not open URL", e)
        }
    }

    fun resetState() {
        _updateState.value = OtaUpdateState.Idle
    }
}

@Composable
fun OtaUpdateDialog(
    state: OtaUpdateState,
    manager: OtaUpdateManager,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    when (state) {
        is OtaUpdateState.UpdateAvailable -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = { if (!info.isForceUpdate) onDismiss() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Update Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (info.isForceUpdate) "Critical Update Required" else "New Update Available!",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Version ${info.latestVersionName} (Build ${info.latestVersionCode}) is ready.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Release Notes:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = info.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.2
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                manager.downloadAndInstallApk(info)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Download & Install", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { manager.openInBrowser(info.updateUrl) }) {
                            Text("Web Link", color = MaterialTheme.colorScheme.primary)
                        }
                        if (!info.isForceUpdate) {
                            TextButton(onClick = onDismiss) {
                                Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            )
        }

        is OtaUpdateState.Downloading -> {
            val info = state.info
            AlertDialog(
                onDismissRequest = { /* Prevent dismiss during download */ },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Downloading OTA Update...", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Version ${info.latestVersionName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${state.progressPercent}%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            if (state.totalBytes > 0) {
                                val downloadedMb = String.format("%.1f", state.downloadedBytes / (1024f * 1024f))
                                val totalMb = String.format("%.1f", state.totalBytes / (1024f * 1024f))
                                Text("$downloadedMb MB / $totalMb MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }

        is OtaUpdateState.ReadyToInstall -> {
            val hasPermission = manager.canRequestPackageInstalls()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = Color(0xFF4E9F3D),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Ready to Install!", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Download complete! Click below to install the new version ${state.info.latestVersionName}.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!hasPermission) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Permission Needed",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Please allow 'Install unknown apps' permission for Print Layout Studio to complete installation.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (!hasPermission) {
                                manager.openUnknownAppSourcesSettings()
                            } else {
                                manager.installApk(state.apkFile)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (!hasPermission) "Grant Permission" else "Install Update Now", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }

        is OtaUpdateState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Update Issue", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { manager.openInBrowser(manager.updateUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Open Release Page", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }

        is OtaUpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Up to date",
                            tint = Color(0xFF4E9F3D),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("App is Up to Date", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    }
                },
                text = {
                    Text(
                        text = "You are running the latest version (${manager.getCurrentVersionName()} / Build ${manager.getCurrentVersionCode()}).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = onDismiss) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        else -> {}
    }
}
