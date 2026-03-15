package com.notifmirror.mobile

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.switchmaterial.SwitchMaterial

class AppSettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private var downloadProgressHandler: Handler? = null
    private var downloadProgressRunnable: Runnable? = null
    private var selectedComplicationPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_settings)

        settings = SettingsManager(this)

        // Behavior section
        val dndSwitch = findViewById<SwitchMaterial>(R.id.dndSyncSwitch)
        val mirrorOngoingSwitch = findViewById<SwitchMaterial>(R.id.mirrorOngoingSwitch)
        val mirrorPersistentSwitch = findViewById<SwitchMaterial>(R.id.mirrorPersistentSwitch)
        val autoDismissSwitch = findViewById<SwitchMaterial>(R.id.autoDismissSwitch)
        val screenModeGroup = findViewById<RadioGroup>(R.id.screenModeGroup)

        // Watch notifications section
        val priorityGroup = findViewById<RadioGroup>(R.id.priorityGroup)
        val autoCancelSwitch = findViewById<SwitchMaterial>(R.id.autoCancelSwitch)
        val showOpenButtonSwitch = findViewById<SwitchMaterial>(R.id.showOpenButtonSwitch)
        val showMuteButtonSwitch = findViewById<SwitchMaterial>(R.id.showMuteButtonSwitch)
        val bigTextThresholdInput = findViewById<EditText>(R.id.bigTextThresholdInput)

        // Snooze section
        val showSnoozeButtonSwitch = findViewById<SwitchMaterial>(R.id.showSnoozeButtonSwitch)
        val snoozeDurationInput = findViewById<EditText>(R.id.snoozeDurationInput)

        // Battery saver section
        val batterySaverSwitch = findViewById<SwitchMaterial>(R.id.batterySaverSwitch)
        val batterySaverThresholdInput = findViewById<EditText>(R.id.batterySaverThresholdInput)

        // Complication section
        val complicationSourceGroup = findViewById<RadioGroup>(R.id.complicationSourceGroup)
        val complicationAppButton = findViewById<MaterialButton>(R.id.complicationAppButton)

        // Mute section
        val muteDurationInput = findViewById<EditText>(R.id.muteDurationInput)

        // Vibration section
        val defaultVibrationInput = findViewById<EditText>(R.id.defaultVibrationInput)
        val saveButton = findViewById<MaterialButton>(R.id.saveSettingsButton)

        // Load current values - Behavior
        dndSwitch.isChecked = settings.isDndSyncEnabled()
        mirrorOngoingSwitch.isChecked = settings.isMirrorOngoingEnabled()
        mirrorPersistentSwitch.isChecked = settings.isMirrorPersistentEnabled()
        autoDismissSwitch.isChecked = settings.isAutoDismissSyncEnabled()

        when (settings.getScreenOffMode()) {
            SettingsManager.SCREEN_MODE_ALWAYS -> screenModeGroup.check(R.id.radioAlways)
            SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY -> screenModeGroup.check(R.id.radioScreenOff)
            SettingsManager.SCREEN_MODE_SILENT_WHEN_ON -> screenModeGroup.check(R.id.radioSilent)
        }

        // Load current values - Watch notifications
        when (settings.getNotificationPriority()) {
            SettingsManager.PRIORITY_HIGH -> priorityGroup.check(R.id.radioPriorityHigh)
            SettingsManager.PRIORITY_DEFAULT -> priorityGroup.check(R.id.radioPriorityDefault)
            SettingsManager.PRIORITY_LOW -> priorityGroup.check(R.id.radioPriorityLow)
        }

        autoCancelSwitch.isChecked = settings.isAutoCancelEnabled()
        showOpenButtonSwitch.isChecked = settings.isShowOpenButtonEnabled()
        showMuteButtonSwitch.isChecked = settings.isShowMuteButtonEnabled()
        bigTextThresholdInput.setText(settings.getBigTextThreshold().toString())

        // Load current values - Mute
        muteDurationInput.setText(settings.getMuteDurationMinutes().toString())

        // Load current values - Snooze
        showSnoozeButtonSwitch.isChecked = settings.isShowSnoozeButtonEnabled()
        snoozeDurationInput.setText(settings.getSnoozeDurationMinutes().toString())

        // Load current values - Battery saver
        batterySaverSwitch.isChecked = settings.isBatterySaverEnabled()
        batterySaverThresholdInput.setText(settings.getBatterySaverThreshold().toString())

        // Load current values - Complication
        selectedComplicationPackage = settings.getComplicationApp()
        when (settings.getComplicationSource()) {
            "most_recent" -> complicationSourceGroup.check(R.id.radioComplicationMostRecent)
            "specific_app" -> {
                complicationSourceGroup.check(R.id.radioComplicationSpecificApp)
                complicationAppButton.visibility = View.VISIBLE
            }
        }
        updateComplicationAppButtonText(complicationAppButton)

        complicationAppButton.setOnClickListener {
            showComplicationAppPicker(complicationAppButton)
        }

        // Note: complicationSourceGroup listener is set below with showSave integration

        // Load current values - Vibration
        defaultVibrationInput.setText(settings.getDefaultVibrationPattern())

        // Hide when locked
        val hideWhenLockedSwitch = findViewById<SwitchMaterial>(R.id.hideWhenLockedSwitch)
        hideWhenLockedSwitch.isChecked = settings.isHideWhenLockedEnabled()

        // Mute continuation
        val muteContinuationSwitch = findViewById<SwitchMaterial>(R.id.muteContinuationSwitch)
        muteContinuationSwitch.isChecked = settings.isMuteContinuationEnabled()

        // History section
        val keepHistorySwitch = findViewById<SwitchMaterial>(R.id.keepHistorySwitch)
        keepHistorySwitch.isChecked = settings.isKeepNotificationHistoryEnabled()

        // Auto-update section
        val autoUpdateSwitch = findViewById<SwitchMaterial>(R.id.autoUpdateSwitch)
        val updateProgressBar = findViewById<ProgressBar>(R.id.updateProgressBar)
        val updateStatusText = findViewById<TextView>(R.id.updateStatusText)

        autoUpdateSwitch.isChecked = settings.isAutoUpdateEnabled()

        autoUpdateSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.setAutoUpdateEnabled(isChecked)
            if (isChecked) {
                // Immediately check for update and download if available
                updateStatusText.visibility = View.VISIBLE
                updateStatusText.text = "Checking for updates..."
                updateProgressBar.visibility = View.VISIBLE
                updateProgressBar.isIndeterminate = true

                val checker = UpdateChecker(this)
                checker.checkForUpdate(forceCheck = true) { info ->
                    runOnUiThread {
                        if (info != null && info.isUpdateAvailable && info.downloadUrl.isNotEmpty()) {
                            updateStatusText.text = "Downloading v${info.latestVersion}..."
                            updateProgressBar.isIndeterminate = false
                            updateProgressBar.progress = 0
                            startDownloadWithProgress(info.downloadUrl, updateProgressBar, updateStatusText)
                        } else if (info != null && !info.isUpdateAvailable) {
                            updateStatusText.text = "You're on the latest version (v${info.currentVersion})"
                            updateProgressBar.visibility = View.GONE
                        } else {
                            updateStatusText.text = "Could not check for updates"
                            updateProgressBar.visibility = View.GONE
                        }
                    }
                }
            } else {
                updateProgressBar.visibility = View.GONE
                updateStatusText.visibility = View.GONE
                stopDownloadProgress()
            }
        }

        // Show save button when any setting changes
        val showSave = { saveButton.visibility = View.VISIBLE }

        val switches = listOf(dndSwitch, mirrorOngoingSwitch, mirrorPersistentSwitch,
            autoDismissSwitch, autoCancelSwitch, showOpenButtonSwitch, showMuteButtonSwitch,
            hideWhenLockedSwitch, muteContinuationSwitch, keepHistorySwitch,
            showSnoozeButtonSwitch, batterySaverSwitch)
        for (sw in switches) {
            sw.setOnCheckedChangeListener { _, _ -> showSave() }
        }

        screenModeGroup.setOnCheckedChangeListener { _, _ -> showSave() }
        priorityGroup.setOnCheckedChangeListener { _, _ -> showSave() }
        complicationSourceGroup.setOnCheckedChangeListener { _, checkedId ->
            complicationAppButton.visibility = if (checkedId == R.id.radioComplicationSpecificApp) View.VISIBLE else View.GONE
            showSave()
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { showSave() }
        }
        bigTextThresholdInput.addTextChangedListener(textWatcher)
        muteDurationInput.addTextChangedListener(textWatcher)
        defaultVibrationInput.addTextChangedListener(textWatcher)
        snoozeDurationInput.addTextChangedListener(textWatcher)
        batterySaverThresholdInput.addTextChangedListener(textWatcher)

        saveButton.setOnClickListener {
            val durationStr = muteDurationInput.text.toString().trim()
            val duration = durationStr.toIntOrNull()
            if (duration == null || duration < 1) {
                Toast.makeText(this, "Mute duration must be at least 1 minute", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val bigTextThreshold = bigTextThresholdInput.text.toString().trim().toIntOrNull()
            if (bigTextThreshold == null || bigTextThreshold < 1) {
                Toast.makeText(this, "Text threshold must be at least 1", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val defaultVib = defaultVibrationInput.text.toString().trim()
            if (defaultVib.isNotEmpty() && !isValidVibrationPattern(defaultVib)) {
                Toast.makeText(this, "Invalid default vibration pattern", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val snoozeDur = snoozeDurationInput.text.toString().trim().toIntOrNull()
            if (snoozeDur == null || snoozeDur < 1) {
                Toast.makeText(this, "Snooze duration must be at least 1 minute", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val batteryThreshold = batterySaverThresholdInput.text.toString().trim().toIntOrNull()
            if (batteryThreshold == null || batteryThreshold !in 1..100) {
                Toast.makeText(this, "Battery threshold must be between 1 and 100", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // All validation passed — save all settings
            settings.setDndSyncEnabled(dndSwitch.isChecked)
            settings.setMirrorOngoingEnabled(mirrorOngoingSwitch.isChecked)
            settings.setMirrorPersistentEnabled(mirrorPersistentSwitch.isChecked)
            settings.setAutoDismissSyncEnabled(autoDismissSwitch.isChecked)

            val mode = when (screenModeGroup.checkedRadioButtonId) {
                R.id.radioScreenOff -> SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY
                R.id.radioSilent -> SettingsManager.SCREEN_MODE_SILENT_WHEN_ON
                else -> SettingsManager.SCREEN_MODE_ALWAYS
            }
            settings.setScreenOffMode(mode)

            val priority = when (priorityGroup.checkedRadioButtonId) {
                R.id.radioPriorityDefault -> SettingsManager.PRIORITY_DEFAULT
                R.id.radioPriorityLow -> SettingsManager.PRIORITY_LOW
                else -> SettingsManager.PRIORITY_HIGH
            }
            settings.setNotificationPriority(priority)

            settings.setAutoCancelEnabled(autoCancelSwitch.isChecked)
            settings.setShowOpenButtonEnabled(showOpenButtonSwitch.isChecked)
            settings.setShowMuteButtonEnabled(showMuteButtonSwitch.isChecked)
            settings.setKeepNotificationHistoryEnabled(keepHistorySwitch.isChecked)
            settings.setHideWhenLockedEnabled(hideWhenLockedSwitch.isChecked)
            settings.setMuteContinuationEnabled(muteContinuationSwitch.isChecked)
            settings.setShowSnoozeButtonEnabled(showSnoozeButtonSwitch.isChecked)
            settings.setBatterySaverEnabled(batterySaverSwitch.isChecked)
            settings.setSnoozeDurationMinutes(snoozeDur)
            settings.setBatterySaverThreshold(batteryThreshold)

            val complicationSource = when (complicationSourceGroup.checkedRadioButtonId) {
                R.id.radioComplicationSpecificApp -> "specific_app"
                else -> "most_recent"
            }
            settings.setComplicationSource(complicationSource)
            settings.setComplicationApp(selectedComplicationPackage)

            settings.setBigTextThreshold(bigTextThreshold)
            settings.setMuteDurationMinutes(duration)

            if (defaultVib.isNotEmpty()) {
                settings.setDefaultVibrationPattern(defaultVib)
            }

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateComplicationAppButtonText(button: MaterialButton) {
        if (selectedComplicationPackage.isEmpty()) {
            button.text = "Select App"
        } else {
            val appName = try {
                val appInfo = packageManager.getApplicationInfo(selectedComplicationPackage, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                selectedComplicationPackage
            }
            button.text = appName
        }
    }

    private fun showComplicationAppPicker(button: MaterialButton) {
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
        val whitelistedApps = settings.getWhitelistedApps()
        var allApps = if (cached.isNotEmpty()) {
            cached.map { AppPickerActivity.AppInfo(it.packageName, it.label, null) }
                .sortedWith(compareByDescending<AppPickerActivity.AppInfo> { whitelistedApps.contains(it.packageName) }
                    .thenBy { it.label.lowercase() })
        } else {
            emptyList()
        }

        val adapter = ComplicationAppAdapter(allApps) { app ->
            selectedComplicationPackage = app.packageName
            updateComplicationAppButtonText(button)
            findViewById<MaterialButton>(R.id.saveSettingsButton).visibility = View.VISIBLE
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

    private inner class ComplicationAppAdapter(
        private var apps: List<AppPickerActivity.AppInfo>,
        private val onClick: (AppPickerActivity.AppInfo) -> Unit
    ) : RecyclerView.Adapter<ComplicationAppAdapter.ViewHolder>() {

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

    private fun startDownloadWithProgress(downloadUrl: String, progressBar: ProgressBar, statusText: TextView) {
        val checker = UpdateChecker(this)
        checker.downloadAndInstall(downloadUrl)

        // Poll download progress
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val handler = Handler(Looper.getMainLooper())
        downloadProgressHandler = handler

        val runnable = object : Runnable {
            override fun run() {
                val query = DownloadManager.Query()
                val cursor: Cursor? = dm.query(query)
                if (cursor != null && cursor.moveToLast()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    if (statusIdx >= 0 && bytesIdx >= 0 && totalIdx >= 0) {
                        val status = cursor.getInt(statusIdx)
                        val bytesDownloaded = cursor.getLong(bytesIdx)
                        val totalBytes = cursor.getLong(totalIdx)

                        when (status) {
                            DownloadManager.STATUS_RUNNING -> {
                                if (totalBytes > 0) {
                                    val percent = (bytesDownloaded * 100 / totalBytes).toInt()
                                    progressBar.isIndeterminate = false
                                    progressBar.max = 100
                                    progressBar.progress = percent
                                    statusText.text = "Downloading... $percent%"
                                }
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                progressBar.progress = 100
                                statusText.text = "Download complete — installing..."
                                cursor.close()
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                statusText.text = "Download failed"
                                progressBar.visibility = View.GONE
                                cursor.close()
                                return
                            }
                            DownloadManager.STATUS_PENDING -> {
                                statusText.text = "Waiting to download..."
                            }
                        }
                    }
                    cursor.close()
                }
                handler.postDelayed(this, 500)
            }
        }
        downloadProgressRunnable = runnable
        handler.postDelayed(runnable, 500)
    }

    private fun stopDownloadProgress() {
        downloadProgressRunnable?.let { downloadProgressHandler?.removeCallbacks(it) }
        downloadProgressHandler = null
        downloadProgressRunnable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDownloadProgress()
    }

    private fun isValidVibrationPattern(pattern: String): Boolean {
        val parts = pattern.split(",").map { it.trim() }
        if (parts.size < 2) return false
        return parts.all { part ->
            try {
                part.toLong()
                true
            } catch (_: NumberFormatException) {
                false
            }
        }
    }
}
