package com.notifmirror.mobile

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class PerAppSettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var packageName: String
    private var selectedSoundUri: String = "default"
    private var selectedSoundName = "Default"
    private var currentVibPattern = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Watch sounds synced via DataClient — loaded dynamically
    private var watchSoundNames = mutableListOf<String>()
    private var watchSoundUris = mutableListOf<String>()

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val TAG = "PerAppSettings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_per_app_settings)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }

        settings = SettingsManager(this)

        // Set app header
        val appIcon = findViewById<ImageView>(R.id.perAppIcon)
        val appName = findViewById<TextView>(R.id.perAppName)
        val appPackage = findViewById<TextView>(R.id.perAppPackage)

        try {
            val ai = getPackageManager().getApplicationInfo(packageName, 0)
            appName.text = getPackageManager().getApplicationLabel(ai)
            appIcon.setImageDrawable(getPackageManager().getApplicationIcon(packageName))
        } catch (_: Exception) {
            appName.text = packageName.split(".").lastOrNull() ?: packageName
        }
        appPackage.text = packageName

        // Override checkboxes
        val overrideOngoingMode = findViewById<CheckBox>(R.id.overrideOngoingMode)
        val overrideAutoDismiss = findViewById<CheckBox>(R.id.overrideAutoDismiss)
        val overridePriority = findViewById<CheckBox>(R.id.overridePriority)
        val overrideAutoCancel = findViewById<CheckBox>(R.id.overrideAutoCancel)
        val overrideShowOpen = findViewById<CheckBox>(R.id.overrideShowOpen)
        val overrideShowMute = findViewById<CheckBox>(R.id.overrideShowMute)
        val overrideBigText = findViewById<CheckBox>(R.id.overrideBigText)
        val overrideMuteDuration = findViewById<CheckBox>(R.id.overrideMuteDuration)
        val overrideScreenOffMode = findViewById<CheckBox>(R.id.overrideScreenOffMode)
        val overrideMuteContinuation = findViewById<CheckBox>(R.id.overrideMuteContinuation)
        val overrideShowSnooze = findViewById<CheckBox>(R.id.overrideShowSnooze)
        val overrideSnoozeDuration = findViewById<CheckBox>(R.id.overrideSnoozeDuration)

        // Value controls
        val perAppOngoingModeGroup = findViewById<RadioGroup>(R.id.perAppOngoingModeGroup)
        val muteContinuationSwitch = findViewById<SwitchMaterial>(R.id.perAppMuteContinuation)
        val autoDismissSwitch = findViewById<SwitchMaterial>(R.id.perAppAutoDismiss)
        val priorityGroup = findViewById<RadioGroup>(R.id.perAppPriorityGroup)
        val autoCancelSwitch = findViewById<SwitchMaterial>(R.id.perAppAutoCancel)
        val showOpenSwitch = findViewById<SwitchMaterial>(R.id.perAppShowOpen)
        val showMuteSwitch = findViewById<SwitchMaterial>(R.id.perAppShowMute)
        val bigTextInput = findViewById<EditText>(R.id.perAppBigTextThreshold)
        val muteDurationInput = findViewById<EditText>(R.id.perAppMuteDuration)
        val vibPresetButton = findViewById<MaterialButton>(R.id.perAppVibPresetButton)
        val vibPreviewButton = findViewById<MaterialButton>(R.id.perAppVibPreviewButton)
        val vibPatternLabel = findViewById<TextView>(R.id.perAppVibPatternLabel)
        val vibPatternInput = findViewById<EditText>(R.id.perAppVibPattern)
        val soundNameDisplay = findViewById<TextView>(R.id.perAppSoundName)
        val showSnoozeSwitch = findViewById<SwitchMaterial>(R.id.perAppShowSnooze)
        val snoozeDurationInput = findViewById<EditText>(R.id.perAppSnoozeDuration)
        val keywordWhitelistInput = findViewById<EditText>(R.id.perAppKeywordWhitelist)
        val keywordBlacklistInput = findViewById<EditText>(R.id.perAppKeywordBlacklist)

        // Load existing per-app settings
        overrideOngoingMode.isChecked = settings.isPerAppIntCustomized("ongoing_mode", packageName)
        when (settings.getEffectiveOngoingMode(packageName)) {
            SettingsManager.ONGOING_NONE -> perAppOngoingModeGroup.check(R.id.perAppOngoingNone)
            SettingsManager.ONGOING_ONLY -> perAppOngoingModeGroup.check(R.id.perAppOngoingOnly)
            SettingsManager.ONGOING_ALL_PERSISTENT -> perAppOngoingModeGroup.check(R.id.perAppOngoingAll)
        }
        setRadioGroupEnabled(perAppOngoingModeGroup, overrideOngoingMode.isChecked)

        overrideMuteContinuation.isChecked = settings.isPerAppBooleanCustomized("mute_continuation", packageName)
        muteContinuationSwitch.isChecked = settings.getEffectiveMuteContinuation(packageName)
        muteContinuationSwitch.isEnabled = overrideMuteContinuation.isChecked

        overrideAutoDismiss.isChecked = settings.isPerAppBooleanCustomized("auto_dismiss", packageName)
        autoDismissSwitch.isChecked = settings.getEffectiveAutoDismissSync(packageName)
        autoDismissSwitch.isEnabled = overrideAutoDismiss.isChecked

        overridePriority.isChecked = settings.isPerAppIntCustomized("priority", packageName)
        val effectivePriority = settings.getEffectivePriority(packageName)
        when (effectivePriority) {
            SettingsManager.PRIORITY_HIGH -> priorityGroup.check(R.id.perAppPriorityHigh)
            SettingsManager.PRIORITY_DEFAULT -> priorityGroup.check(R.id.perAppPriorityDefault)
            SettingsManager.PRIORITY_LOW -> priorityGroup.check(R.id.perAppPriorityLow)
        }
        setRadioGroupEnabled(priorityGroup, overridePriority.isChecked)

        overrideAutoCancel.isChecked = settings.isPerAppBooleanCustomized("auto_cancel", packageName)
        autoCancelSwitch.isChecked = settings.getEffectiveAutoCancel(packageName)
        autoCancelSwitch.isEnabled = overrideAutoCancel.isChecked

        overrideShowOpen.isChecked = settings.isPerAppBooleanCustomized("show_open", packageName)
        showOpenSwitch.isChecked = settings.getEffectiveShowOpenButton(packageName)
        showOpenSwitch.isEnabled = overrideShowOpen.isChecked

        overrideShowMute.isChecked = settings.isPerAppBooleanCustomized("show_mute", packageName)
        showMuteSwitch.isChecked = settings.getEffectiveShowMuteButton(packageName)
        showMuteSwitch.isEnabled = overrideShowMute.isChecked

        overrideBigText.isChecked = settings.isPerAppIntCustomized("big_text_threshold", packageName)
        bigTextInput.setText(settings.getEffectiveBigTextThreshold(packageName).toString())
        bigTextInput.isEnabled = overrideBigText.isChecked

        overrideMuteDuration.isChecked = settings.isPerAppIntCustomized("mute_duration", packageName)
        muteDurationInput.setText(settings.getEffectiveMuteDuration(packageName).toString())
        muteDurationInput.isEnabled = overrideMuteDuration.isChecked

        overrideShowSnooze.isChecked = settings.isPerAppBooleanCustomized("show_snooze", packageName)
        showSnoozeSwitch.isChecked = settings.getEffectiveShowSnoozeButton(packageName)
        showSnoozeSwitch.isEnabled = overrideShowSnooze.isChecked

        overrideSnoozeDuration.isChecked = settings.isPerAppIntCustomized("snooze_duration", packageName)
        snoozeDurationInput.setText(settings.getEffectiveSnoozeDuration(packageName).toString())
        snoozeDurationInput.isEnabled = overrideSnoozeDuration.isChecked

        val perAppScreenModeGroup = findViewById<RadioGroup>(R.id.perAppScreenModeGroup)
        overrideScreenOffMode.isChecked = settings.isPerAppIntCustomized("screen_off_mode", packageName)
        val effectiveScreenMode = settings.getEffectiveScreenOffMode(packageName)
        when (effectiveScreenMode) {
            SettingsManager.SCREEN_MODE_ALWAYS -> perAppScreenModeGroup.check(R.id.perAppRadioAlways)
            SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY -> perAppScreenModeGroup.check(R.id.perAppRadioScreenOff)
            SettingsManager.SCREEN_MODE_SILENT_WHEN_ON -> perAppScreenModeGroup.check(R.id.perAppRadioSilent)
        }
        setRadioGroupEnabled(perAppScreenModeGroup, overrideScreenOffMode.isChecked)

        // Load keyword filters
        val perAppWhitelist = settings.getPerAppKeywordWhitelist(packageName)
        if (perAppWhitelist.isNotEmpty()) {
            keywordWhitelistInput.setText(perAppWhitelist.joinToString("\n"))
        }
        val perAppBlacklist = settings.getPerAppKeywordBlacklist(packageName)
        if (perAppBlacklist.isNotEmpty()) {
            keywordBlacklistInput.setText(perAppBlacklist.joinToString("\n"))
        }

        // Load vibration with preset picker
        currentVibPattern = settings.getVibrationPattern(packageName)
        VibrationPatternHelper.setupVibrationUI(
            context = this,
            presetButton = vibPresetButton,
            previewButton = vibPreviewButton,
            patternLabel = vibPatternLabel,
            customInput = vibPatternInput,
            currentPattern = currentVibPattern,
            allowUseDefault = true
        ) { pattern ->
            currentVibPattern = pattern
        }

        // Load saved sound selection
        val savedSoundUri = settings.getSoundUri(packageName)
        val savedSoundName = settings.getSoundDisplayName(packageName)
        if (savedSoundUri.isNotEmpty()) {
            selectedSoundUri = savedSoundUri
            selectedSoundName = savedSoundName.ifEmpty { savedSoundUri }
            soundNameDisplay.text = "Sound: $selectedSoundName"
            soundNameDisplay.visibility = View.VISIBLE
        }

        // Load watch sounds from DataClient
        loadWatchSounds(soundNameDisplay)

        // Wire up override checkboxes to enable/disable controls
        overrideOngoingMode.setOnCheckedChangeListener { _, checked -> setRadioGroupEnabled(perAppOngoingModeGroup, checked) }
        overrideMuteContinuation.setOnCheckedChangeListener { _, checked -> muteContinuationSwitch.isEnabled = checked }
        overrideAutoDismiss.setOnCheckedChangeListener { _, checked -> autoDismissSwitch.isEnabled = checked }
        overridePriority.setOnCheckedChangeListener { _, checked -> setRadioGroupEnabled(priorityGroup, checked) }
        overrideAutoCancel.setOnCheckedChangeListener { _, checked -> autoCancelSwitch.isEnabled = checked }
        overrideShowOpen.setOnCheckedChangeListener { _, checked -> showOpenSwitch.isEnabled = checked }
        overrideShowMute.setOnCheckedChangeListener { _, checked -> showMuteSwitch.isEnabled = checked }
        overrideBigText.setOnCheckedChangeListener { _, checked -> bigTextInput.isEnabled = checked }
        overrideMuteDuration.setOnCheckedChangeListener { _, checked -> muteDurationInput.isEnabled = checked }
        overrideScreenOffMode.setOnCheckedChangeListener { _, checked -> setRadioGroupEnabled(perAppScreenModeGroup, checked) }
        overrideShowSnooze.setOnCheckedChangeListener { _, checked -> showSnoozeSwitch.isEnabled = checked }
        overrideSnoozeDuration.setOnCheckedChangeListener { _, checked -> snoozeDurationInput.isEnabled = checked }

        // Sound buttons
        findViewById<MaterialButton>(R.id.perAppPickSound).setOnClickListener {
            if (watchSoundNames.isEmpty()) {
                Toast.makeText(this, "No watch sounds available. Open the watch app to sync.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val names = watchSoundNames.toTypedArray()
            val currentIndex = watchSoundUris.indexOf(selectedSoundUri).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Select Watch Sound")
                .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                    selectedSoundUri = watchSoundUris[which]
                    selectedSoundName = watchSoundNames[which]
                    soundNameDisplay.text = "Sound: $selectedSoundName"
                    soundNameDisplay.visibility = View.VISIBLE
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<MaterialButton>(R.id.perAppClearSound).setOnClickListener {
            selectedSoundUri = "default"
            selectedSoundName = "Default"
            soundNameDisplay.visibility = View.GONE
        }

        // Save button
        findViewById<MaterialButton>(R.id.perAppSaveButton).setOnClickListener {
            // Save boolean overrides
            if (overrideOngoingMode.isChecked) {
                val ongoingMode = when (perAppOngoingModeGroup.checkedRadioButtonId) {
                    R.id.perAppOngoingOnly -> SettingsManager.ONGOING_ONLY
                    R.id.perAppOngoingAll -> SettingsManager.ONGOING_ALL_PERSISTENT
                    else -> SettingsManager.ONGOING_NONE
                }
                settings.setPerAppInt("ongoing_mode", packageName, ongoingMode)
            } else {
                settings.clearPerAppInt("ongoing_mode", packageName)
            }

            if (overrideMuteContinuation.isChecked) {
                settings.setPerAppBoolean("mute_continuation", packageName, muteContinuationSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("mute_continuation", packageName)
            }

            if (overrideAutoDismiss.isChecked) {
                settings.setPerAppBoolean("auto_dismiss", packageName, autoDismissSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("auto_dismiss", packageName)
            }

            if (overridePriority.isChecked) {
                val priority = when (priorityGroup.checkedRadioButtonId) {
                    R.id.perAppPriorityDefault -> SettingsManager.PRIORITY_DEFAULT
                    R.id.perAppPriorityLow -> SettingsManager.PRIORITY_LOW
                    else -> SettingsManager.PRIORITY_HIGH
                }
                settings.setPerAppInt("priority", packageName, priority)
            } else {
                settings.clearPerAppInt("priority", packageName)
            }

            if (overrideAutoCancel.isChecked) {
                settings.setPerAppBoolean("auto_cancel", packageName, autoCancelSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("auto_cancel", packageName)
            }

            if (overrideShowOpen.isChecked) {
                settings.setPerAppBoolean("show_open", packageName, showOpenSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("show_open", packageName)
            }

            if (overrideShowMute.isChecked) {
                settings.setPerAppBoolean("show_mute", packageName, showMuteSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("show_mute", packageName)
            }

            if (overrideBigText.isChecked) {
                val threshold = bigTextInput.text.toString().trim().toIntOrNull()
                if (threshold == null || threshold < 1) {
                    Toast.makeText(this, "Text threshold must be at least 1", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settings.setPerAppInt("big_text_threshold", packageName, threshold)
            } else {
                settings.clearPerAppInt("big_text_threshold", packageName)
            }

            if (overrideMuteDuration.isChecked) {
                val duration = muteDurationInput.text.toString().trim().toIntOrNull()
                if (duration == null || duration < 1) {
                    Toast.makeText(this, "Mute duration must be at least 1 minute", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settings.setPerAppInt("mute_duration", packageName, duration)
            } else {
                settings.clearPerAppInt("mute_duration", packageName)
            }

            if (overrideShowSnooze.isChecked) {
                settings.setPerAppBoolean("show_snooze", packageName, showSnoozeSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("show_snooze", packageName)
            }

            if (overrideSnoozeDuration.isChecked) {
                val snoozeDur = snoozeDurationInput.text.toString().trim().toIntOrNull()
                if (snoozeDur == null || snoozeDur < 1) {
                    Toast.makeText(this, "Snooze duration must be at least 1 minute", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settings.setPerAppInt("snooze_duration", packageName, snoozeDur)
            } else {
                settings.clearPerAppInt("snooze_duration", packageName)
            }

            if (overrideScreenOffMode.isChecked) {
                val mode = when (perAppScreenModeGroup.checkedRadioButtonId) {
                    R.id.perAppRadioScreenOff -> SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY
                    R.id.perAppRadioSilent -> SettingsManager.SCREEN_MODE_SILENT_WHEN_ON
                    else -> SettingsManager.SCREEN_MODE_ALWAYS
                }
                settings.setPerAppInt("screen_off_mode", packageName, mode)
            } else {
                settings.clearPerAppInt("screen_off_mode", packageName)
            }

            // Save keyword filters
            val whitelistLines = keywordWhitelistInput.text.toString().trim()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val blacklistLines = keywordBlacklistInput.text.toString().trim()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

            // Validate regex patterns
            for (pattern in whitelistLines + blacklistLines) {
                try {
                    Regex(pattern)
                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid regex: $pattern", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            if (whitelistLines.isNotEmpty()) {
                settings.setPerAppKeywordWhitelist(packageName, whitelistLines)
            } else {
                settings.setPerAppKeywordWhitelist(packageName, emptyList())
            }
            if (blacklistLines.isNotEmpty()) {
                settings.setPerAppKeywordBlacklist(packageName, blacklistLines)
            } else {
                settings.setPerAppKeywordBlacklist(packageName, emptyList())
            }

            // Save vibration
            if (currentVibPattern.isNotEmpty()) {
                if (!VibrationPatternHelper.isValidPattern(currentVibPattern)) {
                    Toast.makeText(this, "Invalid vibration pattern.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                settings.setVibrationPattern(packageName, currentVibPattern)
            } else {
                settings.removeVibrationPattern(packageName)
            }

            // Save sound and send to watch
            if (selectedSoundUri != "default") {
                settings.setSoundUri(packageName, selectedSoundUri, selectedSoundName)
            } else {
                settings.removeSoundUri(packageName)
            }
            sendSoundSelectionToWatch(packageName, selectedSoundUri)

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Reset button
        findViewById<MaterialButton>(R.id.perAppResetButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset to Defaults")
                .setMessage("Remove all custom settings for this app?")
                .setPositiveButton("Reset") { _, _ ->
                    settings.clearAllPerAppSettings(packageName)
                    Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Load the list of available notification sounds from the watch via DataClient.
     * The watch syncs this list when its app is opened.
     */
    private fun loadWatchSounds(soundNameDisplay: TextView) {
        scope.launch {
            try {
                val dataItems = Wearable.getDataClient(this@PerAppSettingsActivity)
                    .getDataItems(android.net.Uri.parse("wear://*/watch_sounds"))
                    .await()

                for (item in dataItems) {
                    val dataMap = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).dataMap
                    val soundsJson = dataMap.getString("sounds_json") ?: continue
                    val arr = JSONArray(soundsJson)

                    watchSoundNames.clear()
                    watchSoundUris.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        watchSoundNames.add(obj.getString("name"))
                        watchSoundUris.add(obj.getString("uri"))
                    }
                    Log.d(TAG, "Loaded ${watchSoundNames.size} watch sounds")
                    break
                }
                dataItems.release()

                // Update display if saved sound matches a watch sound
                if (selectedSoundUri != "default") {
                    val idx = watchSoundUris.indexOf(selectedSoundUri)
                    if (idx >= 0) {
                        selectedSoundName = watchSoundNames[idx]
                        soundNameDisplay.text = "Sound: $selectedSoundName"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load watch sounds", e)
            }
        }
    }

    /**
     * Send the selected sound URI to the watch so it can store it
     * and apply it to the notification channel for this app.
     */
    private fun sendSoundSelectionToWatch(pkg: String, soundUri: String) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("package", pkg)
                    put("soundUri", soundUri)
                }
                val nodes = Wearable.getNodeClient(this@PerAppSettingsActivity)
                    .connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@PerAppSettingsActivity)
                        .sendMessage(node.id, "/set_app_sound", json.toString().toByteArray())
                        .await()
                }
                Log.d(TAG, "Sent sound selection to watch: $pkg -> $soundUri")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send sound selection to watch", e)
            }
        }
    }

    private fun setRadioGroupEnabled(group: RadioGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) {
            group.getChildAt(i).isEnabled = enabled
        }
    }

}
