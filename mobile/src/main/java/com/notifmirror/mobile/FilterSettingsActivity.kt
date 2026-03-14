package com.notifmirror.mobile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilterSettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var notifLog: NotificationLog
    private lateinit var whitelistInput: EditText
    private lateinit var blacklistInput: EditText
    private lateinit var previewContainer: LinearLayout
    private lateinit var previewHeader: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_filter_settings)

        settings = SettingsManager(this)
        notifLog = NotificationLog(this)

        whitelistInput = findViewById(R.id.whitelistInput)
        blacklistInput = findViewById(R.id.blacklistInput)
        previewContainer = findViewById(R.id.previewContainer)
        previewHeader = findViewById(R.id.previewHeader)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val whitelistHelp = findViewById<TextView>(R.id.whitelistHelp)
        val blacklistHelp = findViewById<TextView>(R.id.blacklistHelp)

        whitelistHelp.text = "Only mirror notifications matching at least one pattern. Leave empty to allow all."
        blacklistHelp.text = "Never mirror notifications matching any of these patterns."

        val whitelistPatterns = settings.getKeywordWhitelist()
        val blacklistPatterns = settings.getKeywordBlacklist()

        if (whitelistPatterns.isNotEmpty()) {
            whitelistInput.setText(whitelistPatterns.joinToString("\n"))
        }
        if (blacklistPatterns.isNotEmpty()) {
            blacklistInput.setText(blacklistPatterns.joinToString("\n"))
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
            }
        }
        whitelistInput.addTextChangedListener(textWatcher)
        blacklistInput.addTextChangedListener(textWatcher)

        saveButton.setOnClickListener {
            if (saveFilters()) {
                Toast.makeText(this, "Filters saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        updatePreview()
    }

    private fun getAppDisplayName(packageName: String): String {
        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
        }
    }

    private fun updatePreview() {
        val whitelistLines = whitelistInput.text.toString().trim()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val blacklistLines = blacklistInput.text.toString().trim()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val whitelistRegexes = mutableListOf<Regex>()
        for (pattern in whitelistLines) {
            try {
                whitelistRegexes.add(Regex(pattern, RegexOption.IGNORE_CASE))
            } catch (_: Exception) { }
        }

        val blacklistRegexes = mutableListOf<Regex>()
        for (pattern in blacklistLines) {
            try {
                blacklistRegexes.add(Regex(pattern, RegexOption.IGNORE_CASE))
            } catch (_: Exception) { }
        }

        val entries = notifLog.getEntries().take(50)
        previewContainer.removeAllViews()

        if (entries.isEmpty()) {
            previewHeader.text = "No recent notifications to preview against."
            return
        }

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        var matchCount = 0
        var filterCount = 0

        for (entry in entries) {
            val content = "${entry.title} ${entry.text}"

            val passesWhitelist = if (whitelistRegexes.isEmpty()) true
                else whitelistRegexes.any { it.containsMatchIn(content) }
            val passesBlacklist = !blacklistRegexes.any { it.containsMatchIn(content) }
            val passes = passesWhitelist && passesBlacklist

            if (passes) matchCount++ else filterCount++

            val itemView = LayoutInflater.from(this)
                .inflate(R.layout.item_filter_preview, previewContainer, false)

            val card = itemView as MaterialCardView
            val statusView = itemView.findViewById<TextView>(R.id.previewStatus)
            val appView = itemView.findViewById<TextView>(R.id.previewApp)
            val timeView = itemView.findViewById<TextView>(R.id.previewTime)
            val titleView = itemView.findViewById<TextView>(R.id.previewTitle)
            val reasonView = itemView.findViewById<TextView>(R.id.previewReason)

            appView.text = getAppDisplayName(entry.packageName)
            timeView.text = sdf.format(Date(entry.time))
            val truncTitle = if (entry.title.length > 60) entry.title.take(60) + "..." else entry.title
            titleView.text = truncTitle

            if (passes) {
                statusView.text = "PASS"
                statusView.setTextColor(getColor(android.R.color.holo_green_dark))
                card.setCardBackgroundColor(getColor(android.R.color.transparent))
            } else {
                statusView.text = "BLOCK"
                statusView.setTextColor(getColor(android.R.color.holo_red_light))
                card.alpha = 0.6f
                reasonView.visibility = android.view.View.VISIBLE
                if (!passesWhitelist) {
                    reasonView.text = "No whitelist match"
                    reasonView.setTextColor(getColor(android.R.color.holo_orange_dark))
                } else {
                    reasonView.text = "Blacklist match"
                    reasonView.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }

            previewContainer.addView(itemView)
        }

        previewHeader.text = "$matchCount would pass, $filterCount would be blocked (last ${entries.size} notifications)"
    }

    private fun saveFilters(): Boolean {
        val whitelistLines = whitelistInput.text.toString().trim()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val blacklistLines = blacklistInput.text.toString().trim()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        for (pattern in whitelistLines + blacklistLines) {
            try {
                Regex(pattern)
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid regex: $pattern", Toast.LENGTH_LONG).show()
                return false
            }
        }

        settings.setKeywordWhitelist(whitelistLines)
        settings.setKeywordBlacklist(blacklistLines)
        return true
    }
}
