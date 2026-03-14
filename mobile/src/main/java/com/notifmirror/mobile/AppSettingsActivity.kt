package com.notifmirror.mobile

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_settings)

        settings = SettingsManager(this)

        // Behavior section
        val dndSwitch = findViewById<SwitchMaterial>(R.id.dndSyncSwitch)
        val mirrorOngoingSwitch = findViewById<SwitchMaterial>(R.id.mirrorOngoingSwitch)
        val autoDismissSwitch = findViewById<SwitchMaterial>(R.id.autoDismissSwitch)
        val screenModeGroup = findViewById<RadioGroup>(R.id.screenModeGroup)

        // Watch notifications section
        val priorityGroup = findViewById<RadioGroup>(R.id.priorityGroup)
        val autoCancelSwitch = findViewById<SwitchMaterial>(R.id.autoCancelSwitch)
        val showOpenButtonSwitch = findViewById<SwitchMaterial>(R.id.showOpenButtonSwitch)
        val showMuteButtonSwitch = findViewById<SwitchMaterial>(R.id.showMuteButtonSwitch)
        val bigTextThresholdInput = findViewById<EditText>(R.id.bigTextThresholdInput)

        // Mute section
        val muteDurationInput = findViewById<EditText>(R.id.muteDurationInput)

        // Vibration section
        val defaultVibrationInput = findViewById<EditText>(R.id.defaultVibrationInput)
        val selectVibAppButton = findViewById<MaterialButton>(R.id.selectVibAppButton)
        val vibPatternInput = findViewById<EditText>(R.id.vibPatternInput)
        val addVibButton = findViewById<MaterialButton>(R.id.addVibButton)
        val customVibrationsText = findViewById<TextView>(R.id.customVibrationsText)
        val saveButton = findViewById<MaterialButton>(R.id.saveSettingsButton)

        // Hidden field to store selected package
        var selectedVibPackage = ""
        val vibAppDisplay = findViewById<TextView>(R.id.vibAppDisplay)

        // Load current values - Behavior
        dndSwitch.isChecked = settings.isDndSyncEnabled()
        mirrorOngoingSwitch.isChecked = settings.isMirrorOngoingEnabled()
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

        // Load current values - Vibration
        defaultVibrationInput.setText(settings.getDefaultVibrationPattern())

        // Show custom vibrations
        updateCustomVibrationsDisplay(customVibrationsText)

        selectVibAppButton.setOnClickListener {
            showAppPickerDialog { pkg, label ->
                selectedVibPackage = pkg
                vibAppDisplay.text = label
                vibAppDisplay.visibility = View.VISIBLE
            }
        }

        addVibButton.setOnClickListener {
            val pkg = selectedVibPackage
            val pattern = vibPatternInput.text.toString().trim()

            if (pkg.isEmpty()) {
                Toast.makeText(this, "Select an app first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pattern.isEmpty()) {
                settings.removeVibrationPattern(pkg)
                Toast.makeText(this, "Removed custom pattern", Toast.LENGTH_SHORT).show()
            } else {
                if (!isValidVibrationPattern(pattern)) {
                    Toast.makeText(this, "Invalid pattern. Use comma-separated numbers (e.g. 0,200,100,200)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                settings.setVibrationPattern(pkg, pattern)
                Toast.makeText(this, "Vibration pattern set", Toast.LENGTH_SHORT).show()
            }

            selectedVibPackage = ""
            vibAppDisplay.visibility = View.GONE
            vibPatternInput.text.clear()
            updateCustomVibrationsDisplay(customVibrationsText)
        }

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

            // Save all settings
            settings.setDndSyncEnabled(dndSwitch.isChecked)
            settings.setMirrorOngoingEnabled(mirrorOngoingSwitch.isChecked)
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
            settings.setBigTextThreshold(bigTextThreshold)
            settings.setMuteDurationMinutes(duration)

            if (defaultVib.isNotEmpty()) {
                settings.setDefaultVibrationPattern(defaultVib)
            }

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showAppPickerDialog(onSelected: (String, String) -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_select, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.appSelectList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val searchInput = dialogView.findViewById<EditText>(R.id.dialogSearchInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select App")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        Thread {
            val pm = packageManager
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { appInfo ->
                    AppPickerActivity.AppInfo(
                        packageName = appInfo.packageName,
                        label = pm.getApplicationLabel(appInfo).toString(),
                        icon = try { pm.getApplicationIcon(appInfo.packageName) } catch (_: Exception) { null }
                    )
                }
                .sortedBy { it.label.lowercase() }

            runOnUiThread {
                val adapter = SimpleAppSelectAdapter(allApps) { app ->
                    onSelected(app.packageName, app.label)
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
            }
        }.start()

        dialog.show()
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

    private fun updateCustomVibrationsDisplay(textView: TextView) {
        val custom = settings.getAllCustomVibrations()
        if (custom.isEmpty()) {
            textView.text = "No custom vibration patterns set."
        } else {
            val sb = StringBuilder()
            for ((pkg, pattern) in custom.entries.sortedBy { it.key }) {
                val label = getAppDisplayName(pkg)
                sb.appendLine("$label: $pattern")
            }
            textView.text = sb.toString().trimEnd()
        }
    }

    private fun getAppDisplayName(packageName: String): String {
        return try {
            val ai = this.packageManager.getApplicationInfo(packageName, 0)
            this.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
        }
    }

    inner class SimpleAppSelectAdapter(
        private var apps: List<AppPickerActivity.AppInfo>,
        private val onClick: (AppPickerActivity.AppInfo) -> Unit
    ) : RecyclerView.Adapter<SimpleAppSelectAdapter.ViewHolder>() {

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
