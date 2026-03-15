package com.notifmirror.mobile

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton

object VibrationPatternHelper {

    data class VibrationPreset(
        val name: String,
        val pattern: String,
        val description: String
    )

    val PRESETS = listOf(
        VibrationPreset("Default", "0,200,100,200", "Two short buzzes"),
        VibrationPreset("Short Buzz", "0,150", "Single quick tap"),
        VibrationPreset("Long Buzz", "0,500", "Single long vibration"),
        VibrationPreset("Double Tap", "0,100,80,100", "Two quick taps"),
        VibrationPreset("Triple Tap", "0,80,60,80,60,80", "Three quick taps"),
        VibrationPreset("Gentle", "0,50,40,50", "Subtle double tap"),
        VibrationPreset("Strong", "0,300,100,300", "Two strong buzzes"),
        VibrationPreset("Heartbeat", "0,100,100,300", "Short then long"),
        VibrationPreset("Pulse", "0,150,100,150,100,150", "Three even pulses"),
        VibrationPreset("Ramp Up", "0,50,50,100,50,200", "Getting stronger"),
        VibrationPreset("SOS", "0,100,50,100,50,100,150,300,50,300,50,300,150,100,50,100,50,100", "Morse code SOS"),
        VibrationPreset("Silent", "0,0", "No vibration"),
        VibrationPreset("Custom", "", "Enter your own pattern")
    )

    fun findPresetForPattern(pattern: String): VibrationPreset? {
        if (pattern.isEmpty()) return null
        return PRESETS.find { it.pattern == pattern && it.name != "Custom" }
    }

    fun getPresetNameForPattern(pattern: String): String {
        if (pattern.isEmpty()) return "Default"
        val preset = findPresetForPattern(pattern)
        return preset?.name ?: "Custom"
    }

    fun showPresetPicker(
        context: Context,
        currentPattern: String,
        allowUseDefault: Boolean = false,
        onSelected: (preset: VibrationPreset, pattern: String) -> Unit
    ) {
        val currentPresetName = getPresetNameForPattern(currentPattern)
        val displayPresets = if (allowUseDefault) {
            listOf(VibrationPreset("Use Default", "", "Uses the global vibration setting")) + PRESETS
        } else {
            PRESETS
        }

        val items = displayPresets.map { preset ->
            val isSelected = when {
                preset.name == "Use Default" && currentPattern.isEmpty() -> true
                preset.name == currentPresetName && currentPattern.isNotEmpty() -> true
                preset.name == "Custom" && currentPresetName == "Custom" && currentPattern.isNotEmpty() -> true
                else -> false
            }
            val check = if (isSelected) " \u2714" else ""
            "${preset.name}${check}\n${preset.description}"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Vibration Pattern")
            .setItems(items) { _, which ->
                val preset = displayPresets[which]
                if (preset.name == "Use Default") {
                    onSelected(preset, "")
                } else if (preset.name == "Custom") {
                    showCustomPatternDialog(context, currentPattern) { customPattern ->
                        onSelected(preset, customPattern)
                    }
                } else {
                    onSelected(preset, preset.pattern)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomPatternDialog(
        context: Context,
        currentPattern: String,
        onSave: (String) -> Unit
    ) {
        // Build a proper custom layout
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        val helpText = TextView(context).apply {
            text = "Enter alternating wait,buzz durations in milliseconds.\n\nExample: 0,200,100,200\n= no delay, buzz 200ms, pause 100ms, buzz 200ms"
            setTextColor(context.getColor(android.R.color.darker_gray))
            textSize = 13f
        }

        val input = EditText(context).apply {
            hint = "0,200,100,200"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(if (currentPattern.isNotEmpty() && findPresetForPattern(currentPattern) == null) currentPattern else "")
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        val previewButton = MaterialButton(
            context, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Preview"
            isAllCaps = false
            setOnClickListener {
                val pattern = input.text.toString().trim()
                if (pattern.isNotEmpty() && isValidPattern(pattern)) {
                    vibratePattern(context, pattern)
                } else {
                    Toast.makeText(context, "Enter a valid pattern first", Toast.LENGTH_SHORT).show()
                }
            }
        }

        container.addView(helpText)
        container.addView(input, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })
        container.addView(previewButton, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        AlertDialog.Builder(context)
            .setTitle("Custom Vibration Pattern")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val pattern = input.text.toString().trim()
                if (pattern.isEmpty()) {
                    Toast.makeText(context, "Pattern is empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!isValidPattern(pattern)) {
                    Toast.makeText(context, "Invalid pattern. Use comma-separated numbers.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onSave(pattern)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun vibratePattern(context: Context, pattern: String) {
        val longs = parsePattern(pattern) ?: return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longs, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longs, -1)
        }
    }

    fun parsePattern(pattern: String): LongArray? {
        return try {
            val parts = pattern.split(",").map { it.trim().toLong() }
            if (parts.size < 2) null else parts.toLongArray()
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun isValidPattern(pattern: String): Boolean {
        return parsePattern(pattern) != null
    }

    fun formatPatternDisplay(pattern: String): String {
        if (pattern.isEmpty()) return ""
        val longs = parsePattern(pattern) ?: return pattern
        // Calculate total duration
        val totalMs = longs.sum()
        return if (totalMs < 1000) {
            "${totalMs}ms total"
        } else {
            "${"%.1f".format(totalMs / 1000.0)}s total"
        }
    }

    /**
     * Sets up the vibration preset button, preview button, and pattern label
     * for either global settings or per-app settings.
     */
    fun setupVibrationUI(
        context: Context,
        presetButton: MaterialButton,
        previewButton: MaterialButton,
        patternLabel: TextView,
        customInput: EditText?,
        currentPattern: String,
        allowUseDefault: Boolean = false,
        onPatternChanged: (String) -> Unit
    ) {
        var activePattern = if (allowUseDefault && currentPattern.isEmpty()) "" else currentPattern.ifEmpty { PRESETS[0].pattern }
        updateVibrationDisplay(presetButton, patternLabel, customInput, activePattern, allowUseDefault)

        presetButton.setOnClickListener {
            showPresetPicker(context, activePattern, allowUseDefault) { preset, pattern ->
                activePattern = pattern
                updateVibrationDisplay(presetButton, patternLabel, customInput, activePattern, allowUseDefault)
                onPatternChanged(activePattern)
            }
        }

        previewButton.setOnClickListener {
            if (activePattern.isNotEmpty() && isValidPattern(activePattern)) {
                vibratePattern(context, activePattern)
            } else {
                Toast.makeText(context, "No vibration pattern set", Toast.LENGTH_SHORT).show()
            }
        }

        // If custom input exists, watch for changes
        customInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isNotEmpty() && isValidPattern(text)) {
                    activePattern = text
                    patternLabel.text = formatPatternDisplay(activePattern)
                    onPatternChanged(activePattern)
                }
            }
        })
    }

    private fun updateVibrationDisplay(
        presetButton: MaterialButton,
        patternLabel: TextView,
        customInput: EditText?,
        pattern: String,
        allowUseDefault: Boolean = false
    ) {
        if (allowUseDefault && pattern.isEmpty()) {
            presetButton.text = "Use Default"
            patternLabel.text = "Uses the global vibration setting"
            customInput?.visibility = View.GONE
            return
        }

        val presetName = getPresetNameForPattern(pattern)
        presetButton.text = presetName

        if (presetName == "Custom") {
            patternLabel.text = "$pattern  \u2022  ${formatPatternDisplay(pattern)}"
            customInput?.apply {
                visibility = View.VISIBLE
                if (text.toString().trim() != pattern) {
                    setText(pattern)
                }
            }
        } else {
            val preset = findPresetForPattern(pattern)
            patternLabel.text = "${preset?.description ?: ""}  \u2022  ${formatPatternDisplay(pattern)}"
            customInput?.visibility = View.GONE
        }
    }
}
