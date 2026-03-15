package com.notifmirror.mobile

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilterSettingsActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_SIZE = 50
    }

    private lateinit var settings: SettingsManager
    private lateinit var notifLog: NotificationLog
    private lateinit var whitelistInput: EditText
    private lateinit var blacklistInput: EditText
    private lateinit var previewHeader: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadMoreButton: Button

    private var allEntries: List<NotificationLog.LogEntry> = emptyList()
    private var evaluatedEntries: List<PreviewEntry> = emptyList()
    private var displayedCount = 0

    data class PreviewEntry(
        val entry: NotificationLog.LogEntry,
        val passes: Boolean,
        val reason: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_filter_settings)

        settings = SettingsManager(this)
        notifLog = NotificationLog(this)

        whitelistInput = findViewById(R.id.whitelistInput)
        blacklistInput = findViewById(R.id.blacklistInput)
        previewHeader = findViewById(R.id.previewHeader)
        recyclerView = findViewById(R.id.previewRecyclerView)
        loadMoreButton = findViewById(R.id.loadMoreButton)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val whitelistHelp = findViewById<TextView>(R.id.whitelistHelp)
        val blacklistHelp = findViewById<TextView>(R.id.blacklistHelp)

        whitelistHelp.text = "Only mirror notifications matching at least one pattern. Leave empty to allow all."
        blacklistHelp.text = "Never mirror notifications matching any of these patterns."

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.isNestedScrollingEnabled = false

        val whitelistPatterns = settings.getKeywordWhitelist()
        val blacklistPatterns = settings.getKeywordBlacklist()

        if (whitelistPatterns.isNotEmpty()) {
            whitelistInput.setText(whitelistPatterns.joinToString("\n"))
        }
        if (blacklistPatterns.isNotEmpty()) {
            blacklistInput.setText(blacklistPatterns.joinToString("\n"))
        }

        val initialWhitelist = whitelistInput.text.toString()
        val initialBlacklist = blacklistInput.text.toString()

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
                val changed = whitelistInput.text.toString() != initialWhitelist ||
                    blacklistInput.text.toString() != initialBlacklist
                saveButton.visibility = if (changed) View.VISIBLE else View.GONE
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

        loadMoreButton.setOnClickListener {
            loadMorePreview()
        }

        allEntries = notifLog.getEntries()
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

        if (allEntries.isEmpty()) {
            previewHeader.text = "No recent notifications to preview against."
            recyclerView.adapter = null
            loadMoreButton.visibility = View.GONE
            return
        }

        var matchCount = 0
        var filterCount = 0
        evaluatedEntries = allEntries.map { entry ->
            val content = "${entry.title} ${entry.text}"
            val passesWhitelist = if (whitelistRegexes.isEmpty()) true
                else whitelistRegexes.any { it.containsMatchIn(content) }
            val passesBlacklist = !blacklistRegexes.any { it.containsMatchIn(content) }
            val passes = passesWhitelist && passesBlacklist

            if (passes) matchCount++ else filterCount++

            val reason = when {
                !passesWhitelist -> "No whitelist match"
                !passesBlacklist -> "Blacklist match"
                else -> ""
            }
            PreviewEntry(entry, passes, reason)
        }

        previewHeader.text = "$matchCount would pass, $filterCount would be blocked (${allEntries.size} notifications)"

        displayedCount = minOf(PAGE_SIZE, evaluatedEntries.size)
        recyclerView.adapter = PreviewAdapter(evaluatedEntries.subList(0, displayedCount))
        loadMoreButton.visibility = if (displayedCount < evaluatedEntries.size) View.VISIBLE else View.GONE
        if (displayedCount < evaluatedEntries.size) {
            loadMoreButton.text = "Load More (${evaluatedEntries.size - displayedCount} remaining)"
        }
    }

    private fun loadMorePreview() {
        val newCount = minOf(displayedCount + PAGE_SIZE, evaluatedEntries.size)
        displayedCount = newCount
        recyclerView.adapter = PreviewAdapter(evaluatedEntries.subList(0, displayedCount))
        loadMoreButton.visibility = if (displayedCount < evaluatedEntries.size) View.VISIBLE else View.GONE
        if (displayedCount < evaluatedEntries.size) {
            loadMoreButton.text = "Load More (${evaluatedEntries.size - displayedCount} remaining)"
        }
    }

    private inner class PreviewAdapter(private val entries: List<PreviewEntry>) :
        RecyclerView.Adapter<PreviewAdapter.ViewHolder>() {

        private val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view as MaterialCardView
            val statusView: TextView = view.findViewById(R.id.previewStatus)
            val appView: TextView = view.findViewById(R.id.previewApp)
            val timeView: TextView = view.findViewById(R.id.previewTime)
            val titleView: TextView = view.findViewById(R.id.previewTitle)
            val textView: TextView = view.findViewById(R.id.previewText)
            val reasonView: TextView = view.findViewById(R.id.previewReason)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_filter_preview, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pe = entries[position]
            val entry = pe.entry

            holder.appView.text = getAppDisplayName(entry.packageName)
            holder.timeView.text = sdf.format(Date(entry.time))

            if (entry.title.isNotEmpty()) {
                holder.titleView.text = entry.title
                holder.titleView.visibility = View.VISIBLE
            } else {
                holder.titleView.visibility = View.GONE
            }

            if (entry.text.isNotEmpty()) {
                holder.textView.text = entry.text
                holder.textView.visibility = View.VISIBLE
            } else {
                holder.textView.visibility = View.GONE
            }

            if (pe.passes) {
                holder.statusView.text = "PASS"
                holder.statusView.setTextColor(getColor(android.R.color.holo_green_dark))
                holder.card.alpha = 1.0f
                holder.reasonView.visibility = View.GONE
            } else {
                holder.statusView.text = "BLOCK"
                holder.statusView.setTextColor(getColor(android.R.color.holo_red_light))
                holder.card.alpha = 0.6f
                holder.reasonView.visibility = View.VISIBLE
                if (pe.reason == "No whitelist match") {
                    holder.reasonView.text = pe.reason
                    holder.reasonView.setTextColor(getColor(android.R.color.holo_orange_dark))
                } else {
                    holder.reasonView.text = pe.reason
                    holder.reasonView.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }

        override fun getItemCount() = entries.size
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
