package com.notifmirror.mobile

import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.switchmaterial.SwitchMaterial

class PerAppSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private lateinit var settings: SettingsManager
    private lateinit var packageName: String
    private var selectedSoundUri: Uri? = null
    private var selectedSoundName = ""

    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                selectedSoundUri = uri
                val ringtone = RingtoneManager.getRingtone(this, uri)
                selectedSoundName = ringtone?.getTitle(this) ?: uri.toString()
                findViewById<TextView>(R.id.perAppSoundName).apply {
                    text = "Sound: $selectedSoundName"
                    visibility = View.VISIBLE
                }
            }
        }
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
        val overrideMirrorOngoing = findViewById<CheckBox>(R.id.overrideMirrorOngoing)
        val overrideMirrorPersistent = findViewById<CheckBox>(R.id.overrideMirrorPersistent)
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
        val mirrorOngoingSwitch = findViewById<SwitchMaterial>(R.id.perAppMirrorOngoing)
        val mirrorPersistentSwitch = findViewById<SwitchMaterial>(R.id.perAppMirrorPersistent)
        val muteContinuationSwitch = findViewById<SwitchMaterial>(R.id.perAppMuteContinuation)
        val autoDismissSwitch = findViewById<SwitchMaterial>(R.id.perAppAutoDismiss)
        val priorityGroup = findViewById<RadioGroup>(R.id.perAppPriorityGroup)
        val autoCancelSwitch = findViewById<SwitchMaterial>(R.id.perAppAutoCancel)
        val showOpenSwitch = findViewById<SwitchMaterial>(R.id.perAppShowOpen)
        val showMuteSwitch = findViewById<SwitchMaterial>(R.id.perAppShowMute)
        val bigTextInput = findViewById<EditText>(R.id.perAppBigTextThreshold)
        val muteDurationInput = findViewById<EditText>(R.id.perAppMuteDuration)
        val vibPatternInput = findViewById<EditText>(R.id.perAppVibPattern)
        val soundNameDisplay = findViewById<TextView>(R.id.perAppSoundName)
        val showSnoozeSwitch = findViewById<SwitchMaterial>(R.id.perAppShowSnooze)
        val snoozeDurationInput = findViewById<EditText>(R.id.perAppSnoozeDuration)

        // Load existing per-app settings
        overrideMirrorOngoing.isChecked = settings.isPerAppBooleanCustomized("mirror_ongoing", packageName)
        mirrorOngoingSwitch.isChecked = settings.getEffectiveMirrorOngoing(packageName)
        mirrorOngoingSwitch.isEnabled = overrideMirrorOngoing.isChecked

        overrideMirrorPersistent.isChecked = settings.isPerAppBooleanCustomized("mirror_persistent", packageName)
        mirrorPersistentSwitch.isChecked = settings.getEffectiveMirrorPersistent(packageName)
        mirrorPersistentSwitch.isEnabled = overrideMirrorPersistent.isChecked

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

        // Load vibration
        val customVib = settings.getVibrationPattern(packageName)
        if (customVib.isNotEmpty()) {
            vibPatternInput.setText(customVib)
        }

        // Load sound
        val customSoundUri = settings.getSoundUri(packageName)
        if (customSoundUri.isNotEmpty()) {
            selectedSoundUri = Uri.parse(customSoundUri)
            selectedSoundName = settings.getSoundDisplayName(packageName)
            soundNameDisplay.text = "Sound: $selectedSoundName"
            soundNameDisplay.visibility = View.VISIBLE
        }

        // Wire up override checkboxes to enable/disable controls
        overrideMirrorOngoing.setOnCheckedChangeListener { _, checked -> mirrorOngoingSwitch.isEnabled = checked }
        overrideMirrorPersistent.setOnCheckedChangeListener { _, checked -> mirrorPersistentSwitch.isEnabled = checked }
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
            val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Notification Sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                if (selectedSoundUri != null) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedSoundUri)
                }
            }
            ringtonePicker.launch(intent)
        }

        findViewById<MaterialButton>(R.id.perAppClearSound).setOnClickListener {
            selectedSoundUri = null
            selectedSoundName = ""
            soundNameDisplay.visibility = View.GONE
        }

        // Save button
        findViewById<MaterialButton>(R.id.perAppSaveButton).setOnClickListener {
            // Save boolean overrides
            if (overrideMirrorOngoing.isChecked) {
                settings.setPerAppBoolean("mirror_ongoing", packageName, mirrorOngoingSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("mirror_ongoing", packageName)
            }

            if (overrideMirrorPersistent.isChecked) {
                settings.setPerAppBoolean("mirror_persistent", packageName, mirrorPersistentSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("mirror_persistent", packageName)
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

            // Save vibration
            val vibPattern = vibPatternInput.text.toString().trim()
            if (vibPattern.isNotEmpty()) {
                if (!isValidVibrationPattern(vibPattern)) {
                    Toast.makeText(this, "Invalid vibration pattern. Use comma-separated numbers.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                settings.setVibrationPattern(packageName, vibPattern)
            } else {
                settings.removeVibrationPattern(packageName)
            }

            // Save sound
            if (selectedSoundUri != null) {
                settings.setSoundUri(packageName, selectedSoundUri.toString(), selectedSoundName)
            } else {
                settings.removeSoundUri(packageName)
            }

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

    private fun setRadioGroupEnabled(group: RadioGroup, enabled: Boolean) {
        for (i in 0 until group.childCount) {
            group.getChildAt(i).isEnabled = enabled
        }
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
