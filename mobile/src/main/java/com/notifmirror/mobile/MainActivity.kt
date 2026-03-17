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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
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
    private lateinit var mirroringSwitch: SwitchCompat
    private lateinit var watchConnectionCard: LinearLayout
    private lateinit var watchStatusIcon: ImageView
    private lateinit var watchStatusText: TextView
    private lateinit var updateBanner: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateSubtitle: TextView
    private lateinit var updateButton: com.google.android.material.button.MaterialButton
    private lateinit var updateWatchButton: com.google.android.material.button.MaterialButton
    private lateinit var updateButtonsRow: LinearLayout
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

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

        // Mirroring toggle
        mirroringSwitch = findViewById(R.id.mirroringSwitch)
        mirroringSwitch.isChecked = settingsManager.isMirroringEnabled()
        mirroringSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setMirroringEnabled(isChecked)
            updateStatus()
            // Sync to watch via DataClient
            syncMirroringToWatch(isChecked)
        }

        // Watch connection status card
        watchConnectionCard = findViewById(R.id.watchConnectionCard)
        watchStatusIcon = findViewById(R.id.watchStatusIcon)
        watchStatusText = findViewById(R.id.watchStatusText)
        checkWatchConnection()

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

        findViewById<LinearLayout>(R.id.syncNotifsButton).setOnClickListener {
            syncCurrentNotifications()
        }

        findViewById<LinearLayout>(R.id.exportLogsButton).setOnClickListener {
            exportDebugLogs()
        }

        findViewById<TextView>(R.id.githubLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/WitherredAway/NotificationMirror")))
        }

        // Update banner
        updateBanner = findViewById(R.id.updateBanner)
        updateTitle = findViewById(R.id.updateTitle)
        updateSubtitle = findViewById(R.id.updateSubtitle)
        updateButton = findViewById(R.id.updateButton)
        updateWatchButton = findViewById(R.id.updateWatchButton)
        updateButtonsRow = findViewById(R.id.updateButtonsRow)

        // Show current version in the banner immediately
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            updateTitle.text = "v${pInfo.versionName} — Checking for updates..."
        } catch (_: Exception) {
            updateTitle.text = "Checking for updates..."
        }

        checkForUpdates()
        checkAndRequestPermissions()

        // Sync encryption key to watch on app launch
        syncEncryptionKeyFromMainActivity()

        // Listen for mirroring state changes from watch (via ReplyReceiverService → SharedPreferences)
        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mirroring_enabled") {
                runOnUiThread {
                    val enabled = settingsManager.isMirroringEnabled()
                    if (mirroringSwitch.isChecked != enabled) {
                        mirroringSwitch.isChecked = enabled
                    }
                }
            }
        }
        settingsManager.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        prefsListener?.let { settingsManager.prefs.unregisterOnSharedPreferenceChangeListener(it) }
    }

    private fun checkWatchConnection() {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                val nodeName = nodes.first().displayName
                watchStatusIcon.setImageResource(R.drawable.ic_check_circle)
                watchStatusText.text = "Watch connected: $nodeName"
            } else {
                watchStatusIcon.setImageResource(R.drawable.ic_error_circle)
                watchStatusText.text = "No watch connected"
            }
        }.addOnFailureListener {
            watchStatusIcon.setImageResource(R.drawable.ic_error_circle)
            watchStatusText.text = "Connection check failed"
        }
    }

    private fun syncMirroringToWatch(enabled: Boolean) {
        scope.launch {
            WearSyncHelper.syncMirroringToWatch(this@MainActivity, enabled)
        }
    }

    private fun syncEncryptionKeyFromMainActivity() {
        scope.launch {
            try {
                val keyBytes = CryptoHelper.getKeyBytes(this@MainActivity)
                val putReq = com.google.android.gms.wearable.PutDataMapRequest.create("/crypto_key").apply {
                    dataMap.putByteArray("aes_key", keyBytes)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }
                Wearable.getDataClient(this@MainActivity)
                    .putDataItem(putReq.asPutDataRequest().setUrgent())
                    .await()
                Log.d(TAG, "Encryption key synced to watch from MainActivity")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync encryption key from MainActivity", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        checkForUpdates()
        checkWatchConnection()
        mirroringSwitch.isChecked = settingsManager.isMirroringEnabled()
        // Prompt for battery optimization (at most once per day after user dismisses)
        if (isNotificationListenerEnabled() && !isBatteryOptimizationExempt()) {
            val batteryPrefs = getSharedPreferences("notif_mirror_settings", Context.MODE_PRIVATE)
            val lastDismissed = batteryPrefs.getLong("battery_dialog_dismissed_at", 0)
            val oneDayMs = 24 * 60 * 60 * 1000L
            if (System.currentTimeMillis() - lastDismissed > oneDayMs) {
                AlertDialog.Builder(this)
                    .setTitle("Unrestricted Battery")
                    .setMessage("To keep notification mirroring running reliably in the background, please allow unrestricted battery usage for this app.")
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Later") { _, _ ->
                        batteryPrefs.edit().putLong("battery_dialog_dismissed_at", System.currentTimeMillis()).apply()
                    }
                    .show()
            }
        }
    }

    private fun checkForUpdates() {
        val checker = UpdateChecker(this)
        checker.checkForUpdate { info ->
            runOnUiThread {
                if (info != null && info.isUpdateAvailable) {
                    updateTitle.text = "Update available"
                    updateTitle.setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
                    updateSubtitle.visibility = View.VISIBLE
                    updateSubtitle.text = "v${info.currentVersion} \u2192 v${info.latestVersion}"
                    updateButtonsRow.visibility = View.VISIBLE

                    // Phone APK manual download button
                    updateButton.setOnClickListener {
                        if (info.downloadUrl.isNotEmpty()) {
                            checker.downloadAndInstall(info.downloadUrl)
                            updateButton.isEnabled = false
                            updateButton.text = "Downloading..."
                        }
                    }

                    // Watch APK manual download button
                    if (info.watchDownloadUrl.isNotEmpty()) {
                        updateWatchButton.visibility = View.VISIBLE
                        updateWatchButton.setOnClickListener {
                            updateWatchButton.isEnabled = false
                            updateWatchButton.text = "Downloading..."
                            checker.downloadWatchApk(info.watchDownloadUrl) { file ->
                                runOnUiThread {
                                    if (file != null) {
                                        updateWatchButton.text = "Downloaded"
                                        Toast.makeText(
                                            this,
                                            "Watch APK saved to Downloads/${file.name}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        updateWatchButton.isEnabled = true
                                        updateWatchButton.text = "Watch APK"
                                        Toast.makeText(
                                            this,
                                            "Failed to download watch APK",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    } else {
                        updateWatchButton.visibility = View.GONE
                    }

                    // Auto-update: automatically download and install phone APK only
                    if (settingsManager.isAutoUpdateEnabled() && info.downloadUrl.isNotEmpty()) {
                        checker.downloadAndInstall(info.downloadUrl)
                        updateButton.isEnabled = false
                        updateButton.text = "Downloading..."
                    }
                } else if (info != null) {
                    // Up to date — show version
                    val versionName = info.currentVersion.ifEmpty {
                        try {
                            packageManager.getPackageInfo(packageName, 0).versionName
                        } catch (_: Exception) { "?" }
                    }
                    updateTitle.text = "Up to date \u2014 v$versionName"
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true)
                    updateTitle.setTextColor(tv.data)
                    updateSubtitle.visibility = View.GONE
                    updateButtonsRow.visibility = View.GONE
                } else {
                    // Check failed (no internet, API error, etc.)
                    val versionName = try {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    } catch (_: Exception) { "?" }
                    updateTitle.text = "v$versionName \u2014 could not check for updates"
                    val tv = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true)
                    updateTitle.setTextColor(tv.data)
                    updateSubtitle.visibility = View.GONE
                    updateButtonsRow.visibility = View.GONE
                }
            }
        }
    }

    private fun exportDebugLogs() {
        Toast.makeText(this, "Collecting logcat logs...", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val sb = StringBuilder()
                sb.appendLine("=== NotificationMirror Debug Logs ===")
                sb.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                sb.appendLine()

                // Device info
                sb.appendLine("=== DEVICE INFO ===")
                sb.appendLine("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                try {
                    val pInfo = packageManager.getPackageInfo(packageName, 0)
                    sb.appendLine("App version: ${pInfo.versionName} (code ${pInfo.longVersionCode})")
                } catch (_: Exception) {}
                sb.appendLine("Mirroring enabled: ${settingsManager.isMirroringEnabled()}")
                sb.appendLine("Whitelisted apps: ${settingsManager.getWhitelistedApps().size}")
                sb.appendLine()

                // Phone logcat — capture logs from this app's process
                sb.appendLine("=== PHONE LOGCAT ===")
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "2000", "--pid=${android.os.Process.myPid()}"))
                    val logcatOutput = process.inputStream.bufferedReader().readText()
                    sb.appendLine(logcatOutput)
                } catch (e: Exception) {
                    sb.appendLine("(Failed to capture phone logcat: ${e.message})")
                }
                sb.appendLine()

                // Request watch logcat via MessageClient
                sb.appendLine("=== WATCH LOGCAT ===")
                try {
                    val nodeClient = Wearable.getNodeClient(this@MainActivity)
                    val nodes = nodeClient.connectedNodes.await()
                    if (nodes.isNotEmpty()) {
                        // Register listener BEFORE sending the request to avoid race condition
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var watchLogcat = "(Watch did not respond within timeout)"
                        val listener = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { messageEvent ->
                            if (messageEvent.path == "/logcat_response") {
                                watchLogcat = String(messageEvent.data, Charsets.UTF_8)
                                latch.countDown()
                            }
                        }
                        Wearable.getMessageClient(this@MainActivity).addListener(listener).await()
                        // Now send the request
                        for (node in nodes) {
                            Wearable.getMessageClient(this@MainActivity)
                                .sendMessage(node.id, "/request_logcat", ByteArray(0))
                                .await()
                        }
                        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                        Wearable.getMessageClient(this@MainActivity).removeListener(listener)
                        sb.appendLine(watchLogcat)
                    } else {
                        sb.appendLine("(No watch connected)")
                    }
                } catch (e: Exception) {
                    sb.appendLine("(Failed to request watch logcat: ${e.message})")
                }

                // Save to Downloads
                val fileName = "NotifMirror-Logcat-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.txt"
                val resolver = contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Logcat saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to create log file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export debug logs", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncCurrentNotifications() {
        val listener = NotificationListener.instance
        if (listener == null) {
            Toast.makeText(this, "Notification listener not active", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Syncing notifications...", Toast.LENGTH_SHORT).show()
        listener.syncAllActiveNotifications { count ->
            runOnUiThread {
                Toast.makeText(this, "Synced $count notification${if (count != 1) "s" else ""} to watch", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private enum class TestNotifType(val label: String, val description: String) {
        SIMPLE("Simple", "Basic notification with title and message"),
        CONVERSATION("Conversation (Group)", "Multi-message conversation like WhatsApp group chat"),
        DUPLICATE("Duplicate", "Same notification sent twice to test content-hash dedup"),
        PERSISTENT("Persistent (Ongoing)", "Ongoing notification like music player — stays after dismiss"),
        WITH_ACTIONS("With Actions", "Notification with Reply and custom action buttons"),
        SILENT("Silent", "Notification with no sound or vibration"),
        HIGH_PRIORITY("High Priority", "High-importance notification that demands attention"),
        LOW_PRIORITY("Low Priority", "Low-importance notification, minimal interruption"),
        BURST("Burst (Rapid)", "Multiple notifications sent rapidly in sequence"),
        LONG_TEXT("Long Text", "Notification with very long text content (BigTextStyle)"),
        PROGRESS("Progress", "Notification simulating a download/progress update"),
        CONVERSATION_UPDATE("Conversation Update", "Same conversation with new messages (tests stacking)")
    }

    private fun showTestNotificationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_test_notification, null)
        val appIconView = dialogView.findViewById<ImageView>(R.id.testAppIcon)
        val appNameView = dialogView.findViewById<TextView>(R.id.testAppName)
        val titleInput = dialogView.findViewById<EditText>(R.id.testTitleInput)
        val textInput = dialogView.findViewById<EditText>(R.id.testTextInput)
        val selectAppButton = dialogView.findViewById<Button>(R.id.selectAppButton)
        val typeSpinner = dialogView.findViewById<Spinner>(R.id.testTypeSpinner)
        val typeDescription = dialogView.findViewById<TextView>(R.id.testTypeDescription)
        val countRow = dialogView.findViewById<LinearLayout>(R.id.testCountRow)
        val countInput = dialogView.findViewById<EditText>(R.id.testCountInput)
        val countLabel = dialogView.findViewById<TextView>(R.id.testCountLabel)
        val delayRow = dialogView.findViewById<LinearLayout>(R.id.testDelayRow)
        val delayInput = dialogView.findViewById<EditText>(R.id.testDelayInput)

        var selectedPackage = packageName
        var selectedType = TestNotifType.SIMPLE
        appNameView.text = "Notification Mirror"
        try {
            appIconView.setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {}

        // Setup type spinner
        val typeLabels = TestNotifType.values().map { it.label }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeLabels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        typeSpinner.adapter = spinnerAdapter
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedType = TestNotifType.values()[position]
                typeDescription.text = selectedType.description

                // Show/hide count and delay rows based on type
                when (selectedType) {
                    TestNotifType.CONVERSATION, TestNotifType.CONVERSATION_UPDATE -> {
                        countRow.visibility = View.VISIBLE
                        countLabel.text = "Messages:"
                        countInput.setText("4")
                        delayRow.visibility = View.GONE
                        titleInput.setText("Family Group")
                        textInput.setText("Hey everyone!")
                    }
                    TestNotifType.BURST -> {
                        countRow.visibility = View.VISIBLE
                        countLabel.text = "Count:"
                        countInput.setText("5")
                        delayRow.visibility = View.VISIBLE
                        titleInput.setText("Burst Test")
                        textInput.setText("Rapid notification")
                    }
                    TestNotifType.PROGRESS -> {
                        countRow.visibility = View.VISIBLE
                        countLabel.text = "Steps:"
                        countInput.setText("5")
                        delayRow.visibility = View.VISIBLE
                        delayInput.setText("1000")
                        titleInput.setText("Downloading file.zip")
                        textInput.setText("Download in progress...")
                    }
                    TestNotifType.PERSISTENT -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("Now Playing")
                        textInput.setText("Artist - Song Title")
                    }
                    TestNotifType.WITH_ACTIONS -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("New Message")
                        textInput.setText("Hey, are you free tonight?")
                    }
                    TestNotifType.SILENT -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("Background Sync")
                        textInput.setText("3 items synced successfully")
                    }
                    TestNotifType.HIGH_PRIORITY -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("URGENT")
                        textInput.setText("Incoming call from John")
                    }
                    TestNotifType.LOW_PRIORITY -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("Weather")
                        textInput.setText("Sunny, 72\u00b0F today")
                    }
                    TestNotifType.DUPLICATE -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("Test Notification")
                        textInput.setText("This exact message will be sent twice")
                    }
                    TestNotifType.LONG_TEXT -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("Long Article")
                        textInput.setText("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.")
                    }
                    else -> {
                        countRow.visibility = View.GONE
                        delayRow.visibility = View.GONE
                        titleInput.setText("Test Notification")
                        textInput.setText("This is a test notification from Notification Mirror")
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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
                val text = textInput.text.toString().ifEmpty { "This is a test" }
                val count = countInput.text.toString().toIntOrNull() ?: 5
                val delay = delayInput.text.toString().toLongOrNull() ?: 500L
                sendTestNotification(selectedPackage, title, text, selectedType, count, delay)
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

        // Show cached apps instantly with placeholder icons, whitelisted apps pinned at top
        val cached = AppListCache.getCachedApps(this)
        val whitelistedApps = settingsManager.getWhitelistedApps()
        var allApps = if (cached.isNotEmpty()) {
            cached.map { AppPickerActivity.AppInfo(it.packageName, it.label, null) }
                .sortedWith(compareByDescending<AppPickerActivity.AppInfo> { whitelistedApps.contains(it.packageName) }
                    .thenBy { it.label.lowercase() })
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

        // Refresh in background with icons, whitelisted apps pinned at top
        Thread {
            val freshCached = AppListCache.refreshCache(this)
            val freshApps = AppListCache.toAppInfoList(this, freshCached)
                .sortedWith(compareByDescending<AppPickerActivity.AppInfo> { whitelistedApps.contains(it.packageName) }
                    .thenBy { it.label.lowercase() })

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

    private fun buildBaseTestJson(packageName: String, title: String, text: String, keySuffix: String = ""): JSONObject {
        val iconBase64 = getAppIconBase64(packageName)
        return JSONObject().apply {
            put("key", "test_${System.currentTimeMillis()}$keySuffix")
            put("package", packageName)
            put("title", title)
            put("text", text)
            put("subText", "")
            put("postTime", System.currentTimeMillis())
            put("actions", JSONArray())
            if (iconBase64 != null) {
                put("icon", iconBase64)
            }
            put("muteDuration", settingsManager.getEffectiveMuteDuration(packageName))
            put("notifPriority", settingsManager.getEffectivePriority(packageName))
            put("bigTextThreshold", settingsManager.getEffectiveBigTextThreshold(packageName))
            put("autoCancel", settingsManager.getEffectiveAutoCancel(packageName))
            put("autoDismissSync", settingsManager.getEffectiveAutoDismissSync(packageName))
            put("showOpenButton", settingsManager.getEffectiveShowOpenButton(packageName))
            put("showMuteButton", settingsManager.getEffectiveShowMuteButton(packageName))
            put("showSnoozeButton", settingsManager.getEffectiveShowSnoozeButton(packageName))
            put("snoozeDuration", settingsManager.getEffectiveSnoozeDuration(packageName))
            put("keepHistory", settingsManager.isKeepNotificationHistoryEnabled())
            put("muteContinuation", settingsManager.getEffectiveMuteContinuation(packageName))
            put("batterySaverEnabled", settingsManager.isBatterySaverEnabled())
            put("batterySaverThreshold", settingsManager.getBatterySaverThreshold())
            put("defaultVibration", settingsManager.getDefaultVibrationPattern())
            val effectiveVib = settingsManager.getEffectiveVibrationPattern(packageName)
            if (effectiveVib.isNotEmpty()) {
                put("vibrationPattern", effectiveVib)
            }
            val appLabel = try {
                val ai = this@MainActivity.packageManager.getApplicationInfo(packageName, 0)
                this@MainActivity.packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) { packageName }
            put("appLabel", appLabel)
        }
    }

    private fun sendTestNotification(
        packageName: String,
        title: String,
        text: String,
        type: TestNotifType = TestNotifType.SIMPLE,
        count: Int = 5,
        delayMs: Long = 500L
    ) {
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

                val cryptoKey = CryptoHelper.getOrCreateKey(this@MainActivity)
                val testMessages = buildTestMessages(packageName, title, text, type, count)

                runOnUiThread {
                    val typeLabel = type.label
                    val msgCount = testMessages.size
                    Toast.makeText(this@MainActivity, "Sending $msgCount $typeLabel test notification(s)...", Toast.LENGTH_SHORT).show()
                }

                for ((index, json) in testMessages.withIndex()) {
                    val plainBytes = json.toString().toByteArray(Charsets.UTF_8)
                    val messageBytes = CryptoHelper.encrypt(plainBytes, cryptoKey)

                    for (node in nodes) {
                        Wearable.getMessageClient(this@MainActivity)
                            .sendMessage(node.id, PATH_NOTIFICATION, messageBytes)
                            .await()
                    }

                    // Delay between messages for burst/progress/conversation types
                    if (index < testMessages.size - 1 && testMessages.size > 1) {
                        kotlinx.coroutines.delay(delayMs)
                    }
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "${testMessages.size} test notification(s) sent to ${nodes.size} watch(es)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send test notification", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildTestMessages(
        packageName: String,
        title: String,
        text: String,
        type: TestNotifType,
        count: Int
    ): List<JSONObject> {
        val senders = listOf("Alice", "Bob", "Charlie", "Diana", "Eve")
        val conversationMsgs = listOf(
            "Hey everyone!", "What's the plan for tonight?",
            "Let's meet at 7pm", "Sounds good!",
            "I'll bring snacks", "See you there!",
            "Running 5 mins late", "No worries!"
        )

        return when (type) {
            TestNotifType.SIMPLE -> {
                listOf(buildBaseTestJson(packageName, title, text))
            }

            TestNotifType.CONVERSATION -> {
                // Single notification with conversationMessages array (simulates WhatsApp group)
                val convKey = "test_conv_${System.currentTimeMillis()}"
                val msgCount = count.coerceIn(2, 8)
                val json = buildBaseTestJson(packageName, title, conversationMsgs[0], "_conv").apply {
                    put("key", convKey)
                    put("isMessagingStyle", true)
                    put("conversationTitle", title)
                    val msgsArray = JSONArray()
                    for (i in 0 until msgCount) {
                        val msgObj = JSONObject()
                        msgObj.put("sender", senders[i % senders.size])
                        msgObj.put("text", if (i == 0) text else conversationMsgs[i % conversationMsgs.size])
                        msgsArray.put(msgObj)
                    }
                    put("conversationMessages", msgsArray)
                }
                listOf(json)
            }

            TestNotifType.CONVERSATION_UPDATE -> {
                // Send conversation messages one at a time to test stacking
                val convKey = "test_conv_stack_${System.currentTimeMillis()}"
                val msgCount = count.coerceIn(2, 8)
                val messages = mutableListOf<JSONObject>()
                for (i in 0 until msgCount) {
                    val msgsArray = JSONArray()
                    // Each update includes all messages up to this point
                    for (j in 0..i) {
                        val msgObj = JSONObject()
                        msgObj.put("sender", senders[j % senders.size])
                        msgObj.put("text", if (j == 0) text else conversationMsgs[j % conversationMsgs.size])
                        msgsArray.put(msgObj)
                    }
                    val json = buildBaseTestJson(packageName, senders[i % senders.size], conversationMsgs[i % conversationMsgs.size], "_convupd_$i").apply {
                        put("key", convKey)
                        put("isMessagingStyle", true)
                        put("conversationTitle", title)
                        put("conversationMessages", msgsArray)
                        if (i > 0) put("muteContinuation", true)
                    }
                    messages.add(json)
                }
                messages
            }

            TestNotifType.DUPLICATE -> {
                // Same key + same content twice to test dedup
                val dupKey = "test_dup_${System.currentTimeMillis()}"
                val json1 = buildBaseTestJson(packageName, title, text, "_dup1").apply {
                    put("key", dupKey)
                }
                val json2 = buildBaseTestJson(packageName, title, text, "_dup2").apply {
                    put("key", dupKey)
                }
                listOf(json1, json2)
            }

            TestNotifType.PERSISTENT -> {
                listOf(buildBaseTestJson(packageName, title, text, "_ongoing").apply {
                    put("isOngoing", true)
                    put("autoCancel", false)
                })
            }

            TestNotifType.WITH_ACTIONS -> {
                val actions = JSONArray().apply {
                    put(JSONObject().apply {
                        put("title", "Reply")
                        put("hasRemoteInput", true)
                    })
                    put(JSONObject().apply {
                        put("title", "Mark as Read")
                        put("hasRemoteInput", false)
                    })
                    put(JSONObject().apply {
                        put("title", "Snooze")
                        put("hasRemoteInput", false)
                    })
                }
                listOf(buildBaseTestJson(packageName, title, text, "_actions").apply {
                    put("actions", actions)
                })
            }

            TestNotifType.SILENT -> {
                listOf(buildBaseTestJson(packageName, title, text, "_silent").apply {
                    put("silent", true)
                    put("notifPriority", -1)
                })
            }

            TestNotifType.HIGH_PRIORITY -> {
                listOf(buildBaseTestJson(packageName, title, text, "_high").apply {
                    put("notifPriority", 2)
                })
            }

            TestNotifType.LOW_PRIORITY -> {
                listOf(buildBaseTestJson(packageName, title, text, "_low").apply {
                    put("notifPriority", -1)
                })
            }

            TestNotifType.BURST -> {
                val messages = mutableListOf<JSONObject>()
                val burstCount = count.coerceIn(2, 20)
                for (i in 1..burstCount) {
                    messages.add(buildBaseTestJson(packageName, "$title #$i", "$text ($i of $burstCount)", "_burst_$i"))
                }
                messages
            }

            TestNotifType.LONG_TEXT -> {
                listOf(buildBaseTestJson(packageName, title, text, "_long").apply {
                    put("bigTextThreshold", 0) // Force BigTextStyle
                })
            }

            TestNotifType.PROGRESS -> {
                val messages = mutableListOf<JSONObject>()
                val steps = count.coerceIn(2, 10)
                val progressKey = "test_progress_${System.currentTimeMillis()}"
                for (i in 1..steps) {
                    val pct = (i * 100) / steps
                    messages.add(buildBaseTestJson(packageName, title, "$pct% complete", "_prog_$i").apply {
                        put("key", progressKey)
                        if (i > 1) put("muteContinuation", true)
                        if (i < steps) put("isOngoing", true)
                    })
                }
                messages
            }
        }
    }

    private fun getAppIconBase64(pkg: String): String? {
        return WearSyncHelper.getAppIconBase64(this, pkg)
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
        val mirroringEnabled = settingsManager.isMirroringEnabled()
        if (enabled && mirroringEnabled) {
            statusCard.setBackgroundResource(R.drawable.bg_status_active)
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusTitle.text = "Mirroring Active"
            if (batteryExempt) {
                statusSubtitle.text = "All permissions granted"
            } else {
                statusSubtitle.text = "Battery restricted \u2014 may stop in background"
            }
        } else if (enabled && !mirroringEnabled) {
            statusCard.setBackgroundResource(R.drawable.bg_status_inactive)
            statusIcon.setImageResource(R.drawable.ic_error_circle)
            statusTitle.text = "Mirroring Paused"
            statusSubtitle.text = "Toggle switch to resume"
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

        val notifLog = NotificationLog(this)
        val cachedCount = notifLog.getCount()
        statLogCount.text = if (cachedCount >= 0) cachedCount.toString() else notifLog.getEntries().size.toString()
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
            } else {
                holder.icon.setImageResource(R.drawable.ic_app_placeholder)
            }
            holder.itemView.setOnClickListener { onClick(app) }
        }

        override fun getItemCount() = apps.size
    }
}
