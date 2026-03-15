package com.notifmirror.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.wearable.Wearable
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NotifMirror"
        private const val PATH_NOTIFICATION = "/notification"
    }

    private lateinit var statusCard: LinearLayout
    private lateinit var statusIcon: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var statAppsCount: TextView
    private lateinit var statFiltersCount: TextView
    private lateinit var statLogCount: TextView
    private lateinit var settingsManager: SettingsManager
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateSubtitle: TextView
    private lateinit var updateButton: com.google.android.material.button.MaterialButton
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkAndRequestPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        statusCard = findViewById(R.id.statusCard)
        statusIcon = findViewById(R.id.statusIcon)
        statusTitle = findViewById(R.id.statusTitle)
        statusSubtitle = findViewById(R.id.statusSubtitle)
        statAppsCount = findViewById(R.id.statAppsCount)
        statFiltersCount = findViewById(R.id.statFiltersCount)
        statLogCount = findViewById(R.id.statLogCount)

        findViewById<LinearLayout>(R.id.enableButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<LinearLayout>(R.id.appWhitelistButton).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.keywordFilterButton).setOnClickListener {
            startActivity(Intent(this, FilterSettingsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.viewLogButton).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        // Stat cards navigate to their respective pages
        findViewById<LinearLayout>(R.id.statAppsCard).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.statFiltersCard).setOnClickListener {
            startActivity(Intent(this, FilterSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.statLogCard).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.testNotifButton).setOnClickListener {
            showTestNotificationDialog()
        }

        val versionText = findViewById<TextView>(R.id.versionText)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "v${pInfo.versionName}"
        } catch (_: Exception) {}

        findViewById<TextView>(R.id.githubLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WitherredAway/NotificationMirror")))
        }

        // Update banner
        updateBanner = findViewById(R.id.updateBanner)
        updateTitle = findViewById(R.id.updateTitle)
        updateSubtitle = findViewById(R.id.updateSubtitle)
        updateButton = findViewById(R.id.updateButton)

        checkForUpdates()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        checkForUpdates()
        // Re-check battery optimization every time the app is opened
        if (isNotificationListenerEnabled() && !isBatteryOptimizationExempt()) {
            AlertDialog.Builder(this)
                .setTitle("Unrestricted Battery")
                .setMessage("To keep notification mirroring running reliably in the background, please allow unrestricted battery usage for this app.")
                .setPositiveButton("Allow") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun checkForUpdates() {
        val checker = UpdateChecker(this)
        checker.checkForUpdate { info ->
            runOnUiThread {
                if (info != null && info.isUpdateAvailable) {
                    updateBanner.visibility = View.VISIBLE
                    updateTitle.text = "Update available"
                    updateSubtitle.text = "v${info.currentVersion} → v${info.latestVersion}"
                    updateButton.setOnClickListener {
                        if (info.downloadUrl.isNotEmpty()) {
                            checker.downloadAndInstall(info.downloadUrl)
                            updateButton.isEnabled = false
                            updateButton.text = "Downloading..."
                        }
                    }

                    // Auto-update if enabled
                    if (settingsManager.isAutoUpdateEnabled() && info.downloadUrl.isNotEmpty()) {
                        checker.downloadAndInstall(info.downloadUrl)
                        updateButton.isEnabled = false
                        updateButton.text = "Downloading..."
                    }
                } else {
                    updateBanner.visibility = View.GONE
                }
            }
        }
    }

    private fun showTestNotificationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_test_notification, null)
        val appIconView = dialogView.findViewById<ImageView>(R.id.testAppIcon)
        val appNameView = dialogView.findViewById<TextView>(R.id.testAppName)
        val titleInput = dialogView.findViewById<EditText>(R.id.testTitleInput)
        val textInput = dialogView.findViewById<EditText>(R.id.testTextInput)
        val selectAppButton = dialogView.findViewById<Button>(R.id.selectAppButton)

        var selectedPackage = packageName
        appNameView.text = "Notification Mirror"
        try {
            appIconView.setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {}

        selectAppButton.setOnClickListener {
            showAppSelectionDialog { pkg, label, icon ->
                selectedPackage = pkg
                appNameView.text = label
                if (icon != null) appIconView.setImageDrawable(icon)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Send Test Notification")
            .setView(dialogView)
            .setPositiveButton("Send") { _, _ ->
                val title = titleInput.text.toString().ifEmpty { "Test Notification" }
                val text = textInput.text.toString().ifEmpty { "This is a test notification from Notification Mirror" }
                sendTestNotification(selectedPackage, title, text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppSelectionDialog(onSelected: (String, String, android.graphics.drawable.Drawable?) -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_select, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.appSelectList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val searchInput = dialogView.findViewById<EditText>(R.id.dialogSearchInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select App")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        // Show cached apps instantly
        val cached = AppListCache.getCachedApps(this)
        var allApps = if (cached.isNotEmpty()) {
            cached.map { AppPickerActivity.AppInfo(it.packageName, it.label, null) }
                .sortedBy { it.label.lowercase() }
        } else {
            emptyList()
        }

        val adapter = SimpleAppAdapter(allApps) { app ->
            onSelected(app.packageName, app.label, app.icon)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.label.lowercase().contains(query) ||
                            it.packageName.lowercase().contains(query)
                    }
                }
                adapter.updateList(filtered)
            }
        })

        dialog.show()

        // Refresh in background with icons
        Thread {
            val freshCached = AppListCache.refreshCache(this)
            val freshApps = AppListCache.toAppInfoList(this, freshCached)
                .sortedBy { it.label.lowercase() }

            runOnUiThread {
                allApps = freshApps
                val query = searchInput.text?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.label.lowercase().contains(query) ||
                            it.packageName.lowercase().contains(query)
                    }
                }
                adapter.updateList(filtered)
            }
        }.start()
    }

    private fun sendTestNotification(packageName: String, title: String, text: String) {
        val iconBase64 = getAppIconBase64(packageName)

        val json = JSONObject().apply {
            put("key", "test_${System.currentTimeMillis()}")
            put("package", packageName)
            put("title", title)
            put("text", text)
            put("subText", "")
            put("postTime", System.currentTimeMillis())
            put("actions", JSONArray())
            if (iconBase64 != null) {
                put("icon", iconBase64)
            }
            put("muteDuration", settingsManager.getMuteDurationMinutes())
            put("notifPriority", settingsManager.getNotificationPriority())
            put("bigTextThreshold", settingsManager.getBigTextThreshold())
            put("autoCancel", settingsManager.isAutoCancelEnabled())
            put("autoDismissSync", settingsManager.isAutoDismissSyncEnabled())
            put("showOpenButton", settingsManager.isShowOpenButtonEnabled())
            put("showMuteButton", settingsManager.isShowMuteButtonEnabled())
            put("showSnoozeButton", settingsManager.isShowSnoozeButtonEnabled())
            put("snoozeDuration", settingsManager.getSnoozeDurationMinutes())
            put("keepHistory", settingsManager.isKeepNotificationHistoryEnabled())
            put("muteContinuation", settingsManager.isMuteContinuationEnabled())
            put("batterySaverEnabled", settingsManager.isBatterySaverEnabled())
            put("batterySaverThreshold", settingsManager.getBatterySaverThreshold())
            put("defaultVibration", settingsManager.getDefaultVibrationPattern())
            val customVib = settingsManager.getVibrationPattern(packageName)
            if (customVib.isNotEmpty()) {
                put("vibrationPattern", customVib)
            }
            val customSound = settingsManager.getSoundUri(packageName)
            if (customSound.isNotEmpty()) {
                put("soundUri", customSound)
            }
        }

        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val nodes = nodeClient.connectedNodes.await()

                if (nodes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "No connected watch found", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Encrypt test notification data just like real notifications
                val messageBytes = try {
                    val plainBytes = json.toString().toByteArray(Charsets.UTF_8)
                    val key = CryptoHelper.getOrCreateKey(this@MainActivity)
                    CryptoHelper.encrypt(plainBytes, key)
                } catch (e: Exception) {
                    Log.w(TAG, "Encryption failed for test notification, sending plaintext", e)
                    json.toString().toByteArray(Charsets.UTF_8)
                }

                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, PATH_NOTIFICATION, messageBytes)
                        .await()
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Test notification sent to ${nodes.size} watch(es)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send test notification", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getAppIconBase64(pkg: String): String? {
        return try {
            val iconSize = 48
            val iconQuality = 80
            val drawable = packageManager.getApplicationIcon(pkg)
            val bitmap = if (drawable is BitmapDrawable) {
                Bitmap.createScaledBitmap(drawable.bitmap, iconSize, iconSize, true)
            } else {
                val bmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, iconSize, iconSize)
                drawable.draw(canvas)
                bmp
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, iconQuality, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPerm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(notifPerm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(notifPerm)
                return
            }
        }

        if (!isNotificationListenerEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Notification Access Required")
                .setMessage("This app needs notification access to mirror notifications to your watch. Please enable it in the next screen.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }

        if (!isBatteryOptimizationExempt()) {
            AlertDialog.Builder(this)
                .setTitle("Unrestricted Battery")
                .setMessage("To keep notification mirroring running reliably in the background, please allow unrestricted battery usage for this app.")
                .setPositiveButton("Allow") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateStatus() {
        val enabled = isNotificationListenerEnabled()
        val batteryExempt = isBatteryOptimizationExempt()
        val settings = SettingsManager(this)
        val whitelistedApps = settings.getWhitelistedApps()
        val whitelistKeywords = settings.getKeywordWhitelist()
        val blacklistKeywords = settings.getKeywordBlacklist()

        // Update status card
        if (enabled) {
            statusCard.setBackgroundResource(R.drawable.bg_status_active)
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusTitle.text = "Mirroring Active"
            if (batteryExempt) {
                statusSubtitle.text = "All permissions granted"
            } else {
                statusSubtitle.text = "Battery restricted \u2014 may stop in background"
            }
        } else {
            statusCard.setBackgroundResource(R.drawable.bg_status_inactive)
            statusIcon.setImageResource(R.drawable.ic_error_circle)
            statusTitle.text = "Mirroring Inactive"
            statusSubtitle.text = "Tap Notification Access to enable"
        }

        // Update stats
        if (whitelistedApps.isEmpty()) {
            statAppsCount.text = "All"
        } else {
            statAppsCount.text = whitelistedApps.size.toString()
        }

        val totalFilters = whitelistKeywords.size + blacklistKeywords.size
        statFiltersCount.text = totalFilters.toString()

        val logCount = NotificationLog(this).getEntries().size
        statLogCount.text = logCount.toString()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == packageName) {
                    return true
                }
            }
        }
        return false
    }

    // Simple adapter for app selection dialogs (used by test notification and vibration picker)
    inner class SimpleAppAdapter(
        private var apps: List<AppPickerActivity.AppInfo>,
        private val onClick: (AppPickerActivity.AppInfo) -> Unit
    ) : RecyclerView.Adapter<SimpleAppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val pkg: TextView = view.findViewById(R.id.appPackage)
        }

        fun updateList(newApps: List<AppPickerActivity.AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_simple, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.name.text = app.label
            holder.pkg.text = app.packageName
            if (app.icon != null) {
                holder.icon.setImageDrawable(app.icon)
            }
            holder.itemView.setOnClickListener { onClick(app) }
        }

        override fun getItemCount() = apps.size
    }
}
