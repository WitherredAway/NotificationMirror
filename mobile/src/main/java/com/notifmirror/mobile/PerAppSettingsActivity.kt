package com.notifmirror.mobile

import android.os.Bundle
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
class PerAppSettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var packageName: String
    private var currentVibPattern = ""

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
        val overrideDndSync = findViewById<CheckBox>(R.id.overrideDndSync)
        val overrideHideWhenLocked = findViewById<CheckBox>(R.id.overrideHideWhenLocked)
        val overrideMuteContinuation = findViewById<CheckBox>(R.id.overrideMuteContinuation)
        val overrideAlertMode = findViewById<CheckBox>(R.id.overrideAlertMode)
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
        val showSnoozeSwitch = findViewById<SwitchMaterial>(R.id.perAppShowSnooze)
        val snoozeDurationInput = findViewById<EditText>(R.id.perAppSnoozeDuration)
        val keywordWhitelistInput = findViewById<EditText>(R.id.perAppKeywordWhitelist)
        val keywordBlacklistInput = findViewById<EditText>(R.id.perAppKeywordBlacklist)
        val perAppScreenModeGroup = findViewById<RadioGroup>(R.id.perAppScreenModeGroup)
        val dndSyncSwitch = findViewById<SwitchMaterial>(R.id.perAppDndSync)
        val hideWhenLockedSwitch = findViewById<SwitchMaterial>(R.id.perAppHideWhenLocked)
        val perAppAlertModeGroup = findViewById<RadioGroup>(R.id.perAppAlertModeGroup)
        // Floating save button — hidden until changes are made (matches global settings pattern)
        val saveButton = findViewById<MaterialButton>(R.id.perAppSaveButton)
        val showSave = { saveButton.visibility = View.VISIBLE }

        // Load existing per-app settings
        overrideOngoingMode.isChecked = settings.isPerAppIntCustomized("ongoing_mode", packageName)
        when (settings.getEffectiveOngoingMode(packageName)) {
            SettingsManager.ONGOING_NONE -> perAppOngoingModeGroup.check(R.id.perAppOngoingNone)
            SettingsManager.ONGOING_ONLY -> perAppOngoingModeGroup.check(R.id.perAppOngoingOnly)
            SettingsManager.ONGOING_ALL_PERSISTENT -> perAppOngoingModeGroup.check(R.id.perAppOngoingAll)
        }
        SettingsUIHelper.setRadioGroupEnabled(perAppOngoingModeGroup, overrideOngoingMode.isChecked)

        overrideMuteContinuation.isChecked = settings.isPerAppBooleanCustomized("mute_continuation", packageName)
        muteContinuationSwitch.isChecked = settings.getEffectiveMuteContinuation(packageName)
        muteContinuationSwitch.isEnabled = overrideMuteContinuation.isChecked

        overrideAutoDismiss.isChecked = settings.isPerAppBooleanCustomized("auto_dismiss", packageName)
        autoDismissSwitch.isChecked = settings.getEffectiveAutoDismissSync(packageName)
        autoDismissSwitch.isEnabled = overrideAutoDismiss.isChecked

        overridePriority.isChecked = settings.isPerAppIntCustomized("priority", packageName)
        when (settings.getEffectivePriority(packageName)) {
            SettingsManager.PRIORITY_HIGH -> priorityGroup.check(R.id.perAppPriorityHigh)
            SettingsManager.PRIORITY_DEFAULT -> priorityGroup.check(R.id.perAppPriorityDefault)
            SettingsManager.PRIORITY_LOW -> priorityGroup.check(R.id.perAppPriorityLow)
        }
        SettingsUIHelper.setRadioGroupEnabled(priorityGroup, overridePriority.isChecked)

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

        overrideDndSync.isChecked = settings.isPerAppBooleanCustomized("dnd_sync", packageName)
        dndSyncSwitch.isChecked = settings.getEffectiveDndSync(packageName)
        dndSyncSwitch.isEnabled = overrideDndSync.isChecked

        overrideHideWhenLocked.isChecked = settings.isPerAppBooleanCustomized("hide_when_locked", packageName)
        hideWhenLockedSwitch.isChecked = settings.getEffectiveHideWhenLocked(packageName)
        hideWhenLockedSwitch.isEnabled = overrideHideWhenLocked.isChecked

        overrideAlertMode.isChecked = settings.isPerAppIntCustomized("alert_mode", packageName)
        when (settings.getEffectiveAlertMode(packageName)) {
            SettingsManager.ALERT_SOUND -> perAppAlertModeGroup.check(R.id.perAppAlertSound)
            SettingsManager.ALERT_VIBRATE -> perAppAlertModeGroup.check(R.id.perAppAlertVibrate)
            SettingsManager.ALERT_MUTE -> perAppAlertModeGroup.check(R.id.perAppAlertMute)
        }
        SettingsUIHelper.setRadioGroupEnabled(perAppAlertModeGroup, overrideAlertMode.isChecked)

        overrideScreenOffMode.isChecked = settings.isPerAppIntCustomized("screen_off_mode", packageName)
        when (settings.getEffectiveScreenOffMode(packageName)) {
            SettingsManager.SCREEN_MODE_ALWAYS -> perAppScreenModeGroup.check(R.id.perAppRadioAlways)
            SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY -> perAppScreenModeGroup.check(R.id.perAppRadioScreenOff)
            SettingsManager.SCREEN_MODE_SILENT_WHEN_ON -> perAppScreenModeGroup.check(R.id.perAppRadioSilent)
            SettingsManager.SCREEN_MODE_VIBRATE_ONLY_WHEN_ON -> perAppScreenModeGroup.check(R.id.perAppRadioVibrateOnlyWhenOn)
        }
        SettingsUIHelper.setRadioGroupEnabled(perAppScreenModeGroup, overrideScreenOffMode.isChecked)

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
            showSave()
        }

        // Wire up override checkboxes — uses shared utility for consistency with global settings
        SettingsUIHelper.wireOverrideCheckboxForRadioGroup(overrideOngoingMode, perAppOngoingModeGroup, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideMuteContinuation, muteContinuationSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideAutoDismiss, autoDismissSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckboxForRadioGroup(overridePriority, priorityGroup, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideAutoCancel, autoCancelSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideShowOpen, showOpenSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideShowMute, showMuteSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideBigText, bigTextInput, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideMuteDuration, muteDurationInput, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideDndSync, dndSyncSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideHideWhenLocked, hideWhenLockedSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckboxForRadioGroup(overrideAlertMode, perAppAlertModeGroup, showSave)
        SettingsUIHelper.wireOverrideCheckboxForRadioGroup(overrideScreenOffMode, perAppScreenModeGroup, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideShowSnooze, showSnoozeSwitch, showSave)
        SettingsUIHelper.wireOverrideCheckbox(overrideSnoozeDuration, snoozeDurationInput, showSave)
        // Show save button when switches, radio groups, or text inputs change
        SettingsUIHelper.wireSwitchesToShowSave(
            listOf(muteContinuationSwitch, autoDismissSwitch, autoCancelSwitch,
                showOpenSwitch, showMuteSwitch, showSnoozeSwitch,
                dndSyncSwitch, hideWhenLockedSwitch),
            showSave
        )
        SettingsUIHelper.wireRadioGroupsToShowSave(
            listOf(perAppOngoingModeGroup, priorityGroup, perAppScreenModeGroup, perAppAlertModeGroup),
            showSave
        )
        SettingsUIHelper.wireEditTextsToShowSave(
            listOf(bigTextInput, muteDurationInput, snoozeDurationInput,
                keywordWhitelistInput, keywordBlacklistInput),
            showSave
        )

        // Save button
        saveButton.setOnClickListener {
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

            if (overrideDndSync.isChecked) {
                settings.setPerAppBoolean("dnd_sync", packageName, dndSyncSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("dnd_sync", packageName)
            }

            if (overrideHideWhenLocked.isChecked) {
                settings.setPerAppBoolean("hide_when_locked", packageName, hideWhenLockedSwitch.isChecked)
            } else {
                settings.clearPerAppBoolean("hide_when_locked", packageName)
            }

            if (overrideAlertMode.isChecked) {
                val alertMode = when (perAppAlertModeGroup.checkedRadioButtonId) {
                    R.id.perAppAlertVibrate -> SettingsManager.ALERT_VIBRATE
                    R.id.perAppAlertMute -> SettingsManager.ALERT_MUTE
                    else -> SettingsManager.ALERT_SOUND
                }
                settings.setPerAppInt("alert_mode", packageName, alertMode)
            } else {
                settings.clearPerAppInt("alert_mode", packageName)
            }

            if (overrideScreenOffMode.isChecked) {
                val mode = when (perAppScreenModeGroup.checkedRadioButtonId) {
                    R.id.perAppRadioScreenOff -> SettingsManager.SCREEN_MODE_SCREEN_OFF_ONLY
                    R.id.perAppRadioSilent -> SettingsManager.SCREEN_MODE_SILENT_WHEN_ON
                    R.id.perAppRadioVibrateOnlyWhenOn -> SettingsManager.SCREEN_MODE_VIBRATE_ONLY_WHEN_ON
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

            settings.setPerAppKeywordWhitelist(packageName, whitelistLines)
            settings.setPerAppKeywordBlacklist(packageName, blacklistLines)

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
}
