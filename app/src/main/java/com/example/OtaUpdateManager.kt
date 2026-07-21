package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

// Update Info Data Model
data class OtaUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val updateUrl: String,
    val releaseNotes: String,
    val isForceUpdate: Boolean
)

// States representing the update check process
sealed class OtaUpdateState {
    object Idle : OtaUpdateState()
    object Checking : OtaUpdateState()
    data class UpdateAvailable(val info: OtaUpdateInfo) : OtaUpdateState()
    object UpToDate : OtaUpdateState()
    data class Error(val message: String) : OtaUpdateState()
}

class OtaUpdateManager(private val context: Context) {
    
    private val _updateState = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
    val updateState: StateFlow<OtaUpdateState> = _updateState

    // SharedPreferences to persist custom update URL or settings
    private val prefs = context.getSharedPreferences("ota_update_prefs", Context.MODE_PRIVATE)

    var updateUrl: String
        get() = prefs.getString("update_url", "https://raw.githubusercontent.com/mirtabish/hello-everyone/main/update.json") ?: ""
        set(value) {
            prefs.edit().putString("update_url", value).apply()
        }

    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", true) // Default true so user can test the OTA flows instantly!
        set(value) {
            prefs.edit().putBoolean("demo_mode", value).apply()
        }

    // Get current app version code and name
    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
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
        
        // Simulate or execute real request based on settings
        if (demoMode) {
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.delay(1200) // Aesthetic delay for realist checking experience
            }
            // Generate mock update info that is higher than current version code
            val currentCode = getCurrentVersionCode()
            val mockUpdate = OtaUpdateInfo(
                latestVersionCode = currentCode + 1,
                latestVersionName = "1.2.0-beta",
                updateUrl = "https://github.com/mirtabish/hello-everyone",
                releaseNotes = "• Completely dynamic grid layout logic mapping margins exactly down to 0.1cm!\n• Added beautiful credit badges to creator Mir Zulkifal.\n• Clean vector layout studio adaptive icon package.",
                isForceUpdate = false // Change to true to preview Force Update blocking state
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
                    _updateState.value = OtaUpdateState.Error("HTTP Error: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("OtaUpdateManager", "Failed to check updates", e)
                _updateState.value = OtaUpdateState.Error(e.localizedMessage ?: "Unknown network error")
            }
        }
    }

    fun resetState() {
        _updateState.value = OtaUpdateState.Idle
    }

    fun startUpdateDownload(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("OtaUpdateManager", "Could not start update action", e)
        }
    }
}

@Composable
fun OtaUpdateDialog(
    state: OtaUpdateState.UpdateAvailable,
    onDismiss: () -> Unit,
    onUpdateClicked: (String) -> Unit
) {
    val info = state.info
    AlertDialog(
        onDismissRequest = { if (!info.isForceUpdate) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Update Available",
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
                    text = "Version ${info.latestVersionName} is now ready.",
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
                if (info.isForceUpdate) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This is a mandatory update containing security patches and essential feature corrections.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onUpdateClicked(info.updateUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Update Now", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (!info.isForceUpdate) {
                TextButton(onClick = onDismiss) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}
