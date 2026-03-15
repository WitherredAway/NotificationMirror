package com.notifmirror.mobile

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        val mirrorPersistentSwitch = findViewById<SwitchMaterial>(R.id.mirrorPersistentSwitch)
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

        // Load current values - Vibration
        defaultVibrationInput.setText(settings.getDefaultVibrationPattern())

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
            settings.setBigTextThreshold(bigTextThreshold)
            settings.setMuteDurationMinutes(duration)

            if (defaultVib.isNotEmpty()) {
                settings.setDefaultVibrationPattern(defaultVib)
            }

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
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
