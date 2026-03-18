package com.notifmirror.mobile

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "NotifMirrorUpdate"
        private const val GITHUB_API_URL = "https://api.github.com/repos/WitherredAway/NotificationMirror/releases/latest"
        private const val PREFS_NAME = "update_checker_prefs"
        private const val KEY_LAST_CHECK = "last_check_time"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_WATCH_DOWNLOAD_URL = "watch_download_url"
        private const val KEY_RELEASE_NOTES = "release_notes"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String,
        val watchDownloadUrl: String,
        val releaseNotes: String,
        val isUpdateAvailable: Boolean
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun checkForUpdate(forceCheck: Boolean = false, callback: (UpdateInfo?) -> Unit) {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val now = System.currentTimeMillis()

        // Return cached result if checked recently
        if (!forceCheck && now - lastCheck < CHECK_INTERVAL_MS) {
            val cached = getCachedUpdateInfo()
            if (cached != null) {
                callback(cached)
                return
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(GITHUB_API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)

                    val tagName = json.getString("tag_name").removePrefix("v")
                    val releaseNotes = json.optString("body", "")
                    val assets = json.getJSONArray("assets")

                    var phoneApkUrl = ""
                    var watchApkUrl = ""
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.getString("name").lowercase()
                        if (name.contains("phone") && name.endsWith(".apk")) {
                            phoneApkUrl = asset.getString("browser_download_url")
                        } else if (name.contains("watch") && name.endsWith(".apk")) {
                            watchApkUrl = asset.getString("browser_download_url")
                        }
                    }

                    // If no phone-specific APK, look for any non-watch APK
                    if (phoneApkUrl.isEmpty()) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name").lowercase()
                            if (name.endsWith(".apk") && !name.contains("watch")) {
                                phoneApkUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                    }

                    val currentVersion = getCurrentVersion()
                    val isNewer = isVersionNewer(tagName, currentVersion)

                    // Cache the result
                    prefs.edit()
                        .putLong(KEY_LAST_CHECK, now)
                        .putString(KEY_LATEST_VERSION, tagName)
                        .putString(KEY_DOWNLOAD_URL, phoneApkUrl)
                        .putString(KEY_WATCH_DOWNLOAD_URL, watchApkUrl)
                        .putString(KEY_RELEASE_NOTES, releaseNotes)
                        .apply()

                    val info = UpdateInfo(
                        latestVersion = tagName,
                        currentVersion = currentVersion,
                        downloadUrl = phoneApkUrl,
                        watchDownloadUrl = watchApkUrl,
                        releaseNotes = releaseNotes,
                        isUpdateAvailable = isNewer
                    )
                    callback(info)
                } else {
                    Log.w(TAG, "GitHub API returned ${conn.responseCode}")
                    callback(null)
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                callback(null)
            }
        }
    }

    fun downloadAndInstall(downloadUrl: String) {
        try {
            val fileName = "NotificationMirror-update.apk"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (file.exists()) file.delete()

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("Notification Mirror Update")
                .setDescription("Downloading update...")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)

            // Register receiver for download completion
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        installApk(file)
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update", e)
        }
    }

    /**
     * Download the watch APK to the Downloads folder.
     * Returns the destination file path via callback so the UI can tell the user.
     */
    fun downloadWatchApk(watchDownloadUrl: String, callback: (File?) -> Unit) {
        try {
            val fileName = "NotificationMirror-Watch-update.apk"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (file.exists()) file.delete()

            val request = DownloadManager.Request(Uri.parse(watchDownloadUrl))
                .setTitle("Notification Mirror Watch Update")
                .setDescription("Downloading watch APK...")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        Log.d(TAG, "Watch APK downloaded to: ${file.absolutePath}")
                        callback(file)
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download watch APK", e)
            callback(null)
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    private fun isVersionNewer(remote: String, local: String): Boolean {
        try {
            val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (_: Exception) {}
        return false
    }

    private fun getCachedUpdateInfo(): UpdateInfo? {
        val latestVersion = prefs.getString(KEY_LATEST_VERSION, null) ?: return null
        val downloadUrl = prefs.getString(KEY_DOWNLOAD_URL, "") ?: ""
        val watchDownloadUrl = prefs.getString(KEY_WATCH_DOWNLOAD_URL, "") ?: ""
        val releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "") ?: ""
        val currentVersion = getCurrentVersion()

        return UpdateInfo(
            latestVersion = latestVersion,
            currentVersion = currentVersion,
            downloadUrl = downloadUrl,
            watchDownloadUrl = watchDownloadUrl,
            releaseNotes = releaseNotes,
            isUpdateAvailable = isVersionNewer(latestVersion, currentVersion)
        )
    }
}
