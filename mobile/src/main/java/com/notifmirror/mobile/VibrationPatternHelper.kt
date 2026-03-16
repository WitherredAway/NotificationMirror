package com.notifmirror.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

        val scrollView = ScrollView(context).apply {
            setPadding(0, dpToPx(context, 8), 0, dpToPx(context, 8))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(context, 16), 0, dpToPx(context, 16), 0)
        }
        scrollView.addView(container)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Vibration Pattern")
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()

        for (preset in displayPresets) {
            val isSelected = when {
                preset.name == "Use Default" && currentPattern.isEmpty() -> true
                preset.name == currentPresetName && currentPattern.isNotEmpty() -> true
                preset.name == "Custom" && currentPresetName == "Custom" && currentPattern.isNotEmpty() -> true
                else -> false
            }

            val card = createPresetCard(context, preset, isSelected, currentPattern)
            card.setOnClickListener {
                dialog.dismiss()
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
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(context, 6) })
        }

        dialog.show()
    }

    private fun createPresetCard(
        context: Context,
        preset: VibrationPreset,
        isSelected: Boolean,
        currentPattern: String
    ): LinearLayout {
        val primaryColor = resolveThemeColor(context, com.google.android.material.R.attr.colorPrimary)
        val surfaceColor = resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurfaceColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface)
        val outlineColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOutline)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(context, 14), dpToPx(context, 12), dpToPx(context, 14), dpToPx(context, 12))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpToPx(context, 12).toFloat()
                if (isSelected) {
                    setColor(surfaceColor)
                    setStroke(dpToPx(context, 2), primaryColor)
                } else {
                    setColor(surfaceColor and 0x40FFFFFF)
                    setStroke(dpToPx(context, 1), outlineColor and 0x30FFFFFF.toInt())
                }
            }
            background = bg
            isClickable = true
            isFocusable = true
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = context.obtainStyledAttributes(attrs)
            foreground = ta.getDrawable(0)
            ta.recycle()
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameText = TextView(context).apply {
            text = preset.name
            setTextColor(if (isSelected) primaryColor else onSurfaceColor)
            textSize = 15f
            setTypeface(typeface, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(nameText)
        if (isSelected) {
            val checkIcon = TextView(context).apply {
                text = "\u2714"
                setTextColor(primaryColor)
                textSize = 16f
            }
            topRow.addView(checkIcon)
        }
        card.addView(topRow)

        val descText = TextView(context).apply {
            text = preset.description
            setTextColor(outlineColor)
            textSize = 12f
            setPadding(0, dpToPx(context, 2), 0, 0)
        }
        card.addView(descText)

        // Visual pattern bar
        val patternToShow = when {
            preset.name == "Custom" && currentPattern.isNotEmpty() && findPresetForPattern(currentPattern) == null -> currentPattern
            preset.pattern.isNotEmpty() && preset.name != "Silent" -> preset.pattern
            else -> null
        }
        if (patternToShow != null) {
            val patternView = PatternBarView(context, patternToShow, primaryColor, outlineColor)
            card.addView(patternView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(context, 20)
            ).apply { topMargin = dpToPx(context, 6) })

            val durationText = TextView(context).apply {
                text = formatPatternDisplay(patternToShow)
                setTextColor(outlineColor)
                textSize = 11f
                setPadding(0, dpToPx(context, 2), 0, 0)
            }
            card.addView(durationText)
        }

        return card
    }

    /**
     * Custom View that draws a visual representation of a vibration pattern.
     * Filled blocks = vibration, gaps = silence.
     */
    class PatternBarView(
        context: Context,
        private val pattern: String,
        private val activeColor: Int,
        private val inactiveColor: Int
    ) : View(context) {

        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activeColor
            style = Paint.Style.FILL
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inactiveColor and 0x20FFFFFF.toInt()
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val longs = parsePattern(pattern) ?: return
            val totalMs = longs.sum().coerceAtLeast(1)
            val w = width.toFloat()
            val h = height.toFloat()
            val cornerRadius = h / 2f

            canvas.drawRoundRect(RectF(0f, 0f, w, h), cornerRadius, cornerRadius, bgPaint)

            var x = 0f
            for (i in longs.indices) {
                val segWidth = (longs[i].toFloat() / totalMs) * w
                if (segWidth < 1f) {
                    x += segWidth
                    continue
                }
                val isVibrating = i % 2 == 1
                if (isVibrating) {
                    val rect = RectF(x, 2f, x + segWidth, h - 2f)
                    canvas.drawRoundRect(rect, (h - 4f) / 2f, (h - 4f) / 2f, activePaint)
                }
                x += segWidth
            }
        }
    }

    private fun showCustomPatternDialog(
        context: Context,
        currentPattern: String,
        onSave: (String) -> Unit
    ) {
        val primaryColor = resolveThemeColor(context, com.google.android.material.R.attr.colorPrimary)
        val outlineColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOutline)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(context, 24), dpToPx(context, 16), dpToPx(context, 24), dpToPx(context, 8))
        }

        val helpText = TextView(context).apply {
            text = "Enter alternating wait,buzz durations in milliseconds.\n\nExample: 0,200,100,200\n= no delay, buzz 200ms, pause 100ms, buzz 200ms"
            setTextColor(outlineColor)
            textSize = 13f
        }

        val input = EditText(context).apply {
            hint = "0,200,100,200"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(if (currentPattern.isNotEmpty() && findPresetForPattern(currentPattern) == null) currentPattern else "")
            textSize = 16f
            setPadding(dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12))
        }

        val initialPattern = if (currentPattern.isNotEmpty()) currentPattern else "0,200,100,200"
        var previewBar = PatternBarView(context, initialPattern, primaryColor, outlineColor)
        val previewBarIndex = 2

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
        container.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(context, 12) })
        container.addView(previewBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(context, 24)
        ).apply { topMargin = dpToPx(context, 12) })
        container.addView(previewButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(context, 8) })

        // Update preview bar as user types
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isNotEmpty() && isValidPattern(text)) {
                    container.removeViewAt(previewBarIndex)
                    previewBar = PatternBarView(context, text, primaryColor, outlineColor)
                    container.addView(previewBar, previewBarIndex, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(context, 24)
                    ).apply { topMargin = dpToPx(context, 12) })
                }
            }
        })

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
        try {
            val longs = parsePattern(pattern) ?: return
            // Skip vibration if pattern is all zeros (silent)
            if (longs.all { it == 0L }) return
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
        } catch (e: Exception) {
            Toast.makeText(context, "Vibration failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
            showPresetPicker(context, activePattern, allowUseDefault) { _, pattern ->
                activePattern = pattern
                updateVibrationDisplay(presetButton, patternLabel, customInput, activePattern, allowUseDefault)
                onPatternChanged(activePattern)
            }
        }

        previewButton.setOnClickListener {
            if (activePattern.isNotEmpty() && isValidPattern(activePattern)) {
                vibratePattern(context, activePattern)
            } else if (activePattern.isEmpty() && allowUseDefault) {
                Toast.makeText(context, "Using default pattern \u2014 preview from global settings", Toast.LENGTH_SHORT).show()
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

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
