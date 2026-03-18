package com.notifmirror.wear

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectedNodeName: String? = null
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result received, continue
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        // Rotary/bezel scrolling support
        val scrollView = findViewById<ScrollView>(R.id.mainScrollView)
        scrollView.requestFocus()
        scrollView.setOnGenericMotionListener { v, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                val scrollAmount = (delta * dpToPx(64)).toInt()
                (v as ScrollView).smoothScrollBy(0, scrollAmount)
                true
            } else {
                false
            }
        }

        val statusCard = findViewById<LinearLayout>(R.id.statusCard)
        val statusIcon = findViewById<ImageView>(R.id.statusIcon)
        val statusText = findViewById<TextView>(R.id.statusText)
        val mirroringSwitch = findViewById<SwitchCompat>(R.id.mirroringSwitch)

        // Mirroring toggle — synced to phone via MessageClient
        val prefs = getSharedPreferences("notif_mirror_settings", MODE_PRIVATE)
        mirroringSwitch.isChecked = prefs.getBoolean("mirroring_enabled", true)
        mirroringSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mirroring_enabled", isChecked).apply()
            // Update status text
            if (isChecked) {
                statusText.text = if (connectedNodeName != null) "Connected · Mirroring" else "Not connected"
                statusCard.setBackgroundResource(if (connectedNodeName != null) R.drawable.bg_status_active else R.drawable.bg_status_inactive)
                statusIcon.setImageResource(if (connectedNodeName != null) R.drawable.ic_check_circle else R.drawable.ic_error_circle)
            } else {
                statusText.text = "Mirroring paused"
                statusCard.setBackgroundResource(R.drawable.bg_status_inactive)
                statusIcon.setImageResource(R.drawable.ic_error_circle)
            }
            // Sync to phone
            syncMirroringToPhone(isChecked)
        }

        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                connectedNodeName = nodes.first().displayName
                val mirroringEnabled = mirroringSwitch.isChecked
                statusCard.setBackgroundResource(if (mirroringEnabled) R.drawable.bg_status_active else R.drawable.bg_status_inactive)
                statusIcon.setImageResource(if (mirroringEnabled) R.drawable.ic_check_circle else R.drawable.ic_error_circle)
                statusText.text = if (mirroringEnabled) "Connected · Mirroring" else "Mirroring paused"
            } else {
                connectedNodeName = null
                statusCard.setBackgroundResource(R.drawable.bg_status_inactive)
                statusIcon.setImageResource(R.drawable.ic_error_circle)
                statusText.text = "Not connected to phone"
            }
        }.addOnFailureListener {
            statusCard.setBackgroundResource(R.drawable.bg_status_inactive)
            statusIcon.setImageResource(R.drawable.ic_error_circle)
            statusText.text = "Connection check failed"
        }

        findViewById<LinearLayout>(R.id.notifSettingsButton).setOnClickListener {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.google.android.wearable.app",
                        "com.google.android.clockwork.home.notificationsettings.NotificationSettingsPreferenceActivity"
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open notification settings", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<LinearLayout>(R.id.phoneSettingsButton).setOnClickListener {
            scope.launch {
                try {
                    val nodeClient = Wearable.getNodeClient(this@MainActivity)
                    val nodes = nodeClient.connectedNodes.await()
                    if (nodes.isEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "No phone connected", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    for (node in nodes) {
                        Wearable.getMessageClient(this@MainActivity)
                            .sendMessage(node.id, "/open_settings", ByteArray(0))
                            .await()
                    }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Opening settings on phone", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("NotifMirrorWear", "Failed to open phone settings", e)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to reach phone", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<LinearLayout>(R.id.viewLogButton).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        // Sync button — clears all watch notifications, then requests re-sync from phone
        findViewById<LinearLayout>(R.id.syncButton).setOnClickListener {
            syncFromPhone()
        }

        val versionText = findViewById<TextView>(R.id.versionText)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "v${pInfo.versionName}"
        } catch (_: Exception) {}

        // Check for updates and show indicator
        checkForUpdates()

        findViewById<TextView>(R.id.githubLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/WitherredAway/NotificationMirror")))
        }

        // Keep History toggle
        val historyPrefs = getSharedPreferences("notif_mirror_settings", MODE_PRIVATE)
        val keepHistorySwitch = findViewById<SwitchCompat>(R.id.keepHistorySwitch)
        keepHistorySwitch.isChecked = historyPrefs.getBoolean("keep_notification_history", true)
        keepHistorySwitch.setOnCheckedChangeListener { _, isChecked ->
            historyPrefs.edit().putBoolean("keep_notification_history", isChecked).apply()
        }

        // Battery Saver toggle
        val batterySaverSwitch = findViewById<SwitchCompat>(R.id.batterySaverSwitch)
        val batterySaverThresholdLayout = findViewById<LinearLayout>(R.id.batterySaverThresholdLayout)
        val batterySaverThresholdInput = findViewById<EditText>(R.id.batterySaverThresholdInput)

        val batterySaverEnabled = historyPrefs.getBoolean("battery_saver_enabled", false)
        val batterySaverThreshold = historyPrefs.getInt("battery_saver_threshold", 15)
        batterySaverSwitch.isChecked = batterySaverEnabled
        batterySaverThresholdLayout.visibility = if (batterySaverEnabled) android.view.View.VISIBLE else android.view.View.GONE
        batterySaverThresholdInput.setText(batterySaverThreshold.toString())

        batterySaverSwitch.setOnCheckedChangeListener { _, isChecked ->
            historyPrefs.edit().putBoolean("battery_saver_enabled", isChecked).apply()
            batterySaverThresholdLayout.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        batterySaverThresholdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val threshold = batterySaverThresholdInput.text.toString().toIntOrNull()
                if (threshold != null && threshold in 1..100) {
                    historyPrefs.edit().putInt("battery_saver_threshold", threshold).apply()
                } else {
                    batterySaverThresholdInput.setText("15")
                    historyPrefs.edit().putInt("battery_saver_threshold", 15).apply()
                }
            }
        }

        checkAndRequestPermissions()

        // Proactively pull encryption key from DataClient on app launch
        pullEncryptionKeyFromDataClient()

        // Listen for mirroring state changes from phone (via DataClient → SharedPreferences)
        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mirroring_enabled") {
                runOnUiThread {
                    val enabled = prefs.getBoolean("mirroring_enabled", true)
                    mirroringSwitch.isChecked = enabled
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences("notif_mirror_settings", MODE_PRIVATE)
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
    }

    /**
     * Clear all watch notifications and request the phone to re-sync current notifications.
     */
    private fun syncFromPhone() {
        // Clear all mirrored notifications
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.cancelAll()
        Toast.makeText(this, "Syncing...", Toast.LENGTH_SHORT).show()

        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                if (nodes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "No phone connected", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, "/request_sync", ByteArray(0))
                        .await()
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Sync requested", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("NotifMirrorWear", "Failed to request sync", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Sync failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncMirroringToPhone(enabled: Boolean) {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                val json = org.json.JSONObject().apply { put("enabled", enabled) }
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, "/mirroring_toggle", json.toString().toByteArray())
                        .await()
                }
                Log.d("NotifMirrorWear", "Mirroring toggle synced to phone: enabled=$enabled")
            } catch (e: Exception) {
                Log.e("NotifMirrorWear", "Failed to sync mirroring toggle to phone", e)
            }
        }
    }

    private fun checkForUpdates() {
        val updateCard = findViewById<LinearLayout>(R.id.updateCard)
        val updateTitle = findViewById<TextView>(R.id.updateTitle)
        val updateSubtitle = findViewById<TextView>(R.id.updateSubtitle)
        scope.launch {
            try {
                val url = java.net.URL("https://api.github.com/repos/WitherredAway/NotificationMirror/releases/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(response)
                    val tagName = json.getString("tag_name").removePrefix("v")

                    val currentVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                    } catch (_: Exception) { "0.0.0" }

                    if (isVersionNewer(tagName, currentVersion)) {
                        val htmlUrl = json.optString("html_url", "")
                        runOnUiThread {
                            updateTitle.text = "Update available"
                            updateSubtitle.text = "v$currentVersion \u2192 v$tagName"
                            updateSubtitle.visibility = android.view.View.VISIBLE
                            updateCard.visibility = android.view.View.VISIBLE
                            // Tap to open GitHub release in browser
                            if (htmlUrl.isNotEmpty()) {
                                updateCard.setOnClickListener {
                                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(htmlUrl)))
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("NotifMirrorWear", "Failed to check for updates", e)
            }
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

    private fun pullEncryptionKeyFromDataClient() {
        scope.launch {
            try {
                val dataItems = Wearable.getDataClient(this@MainActivity)
                    .getDataItems(android.net.Uri.parse("wear://*/crypto_key"))
                    .await()
                for (item in dataItems) {
                    val dataMap = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).dataMap
                    val keyBytes = dataMap.getByteArray("aes_key")
                    if (keyBytes != null) {
                        CryptoHelper.importKey(this@MainActivity, keyBytes)
                        Log.d("NotifMirrorWear", "Encryption key pulled from DataClient on launch")
                        // Retry any queued notifications
                        PendingNotificationQueue.retryAll(this@MainActivity)
                        break
                    }
                }
                dataItems.release()
            } catch (e: Exception) {
                Log.w("NotifMirrorWear", "Failed to pull encryption key from DataClient", e)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPerm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(notifPerm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(notifPerm)) {
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission Required")
                        .setMessage("This app needs notification permission to display mirrored notifications from your phone.")
                        .setPositiveButton("Grant") { _, _ ->
                            notificationPermissionLauncher.launch(notifPerm)
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    notificationPermissionLauncher.launch(notifPerm)
                }
            }
        }
    }
}
