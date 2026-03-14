package com.notifmirror.wear

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    private lateinit var notifLog: NotificationLog
    private lateinit var logText: TextView
    private lateinit var searchInput: EditText
    private lateinit var appFilter: Spinner
    private lateinit var countText: TextView
    private var allEntries: List<NotificationLog.LogEntry> = emptyList()
    private var appList: List<String> = emptyList()
    private var selectedApp: String = "All Apps"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_log)

        logText = findViewById(R.id.logText)
        searchInput = findViewById(R.id.searchInput)
        appFilter = findViewById(R.id.appFilter)
        countText = findViewById(R.id.countText)
        val clearButton = findViewById<Button>(R.id.clearLogButton)

        notifLog = NotificationLog(this)

        clearButton.setOnClickListener {
            notifLog.clear()
            loadEntries()
            displayFiltered()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                displayFiltered()
            }
        })

        appFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedApp = appList.getOrElse(position) { "All Apps" }
                displayFiltered()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadEntries()
        displayFiltered()
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
        displayFiltered()
    }

    private fun loadEntries() {
        allEntries = notifLog.getEntries()

        val uniqueApps = allEntries.map { it.packageName }.distinct().sorted()
        appList = listOf("All Apps") + uniqueApps

        val displayNames = appList.map { pkg ->
            if (pkg == "All Apps") pkg
            else pkg.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        appFilter.adapter = adapter

        val prevIndex = appList.indexOf(selectedApp)
        if (prevIndex >= 0) {
            appFilter.setSelection(prevIndex)
        }
    }

    private fun displayFiltered() {
        val searchPattern = searchInput.text.toString().trim()
        val searchRegex = if (searchPattern.isNotEmpty()) {
            try {
                Regex(searchPattern, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val filtered = allEntries.filter { entry ->
            val passesApp = selectedApp == "All Apps" || entry.packageName == selectedApp
            val passesSearch = if (searchRegex != null) {
                val content = "${entry.title} ${entry.text} ${entry.packageName} ${entry.status} ${entry.detail}"
                searchRegex.containsMatchIn(content)
            } else {
                true
            }
            passesApp && passesSearch
        }

        if (searchPattern.isNotEmpty() && searchRegex == null) {
            countText.text = "Invalid regex"
            logText.text = ""
            return
        }

        countText.text = "${filtered.size} of ${allEntries.size}"

        if (filtered.isEmpty()) {
            logText.text = if (allEntries.isEmpty()) {
                "No notifications received yet."
            } else {
                "No entries match."
            }
            return
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        for (entry in filtered) {
            val timeStr = sdf.format(Date(entry.time))
            val shortPkg = entry.packageName.split(".").lastOrNull() ?: entry.packageName

            sb.appendLine("[$timeStr] ${entry.status}")
            sb.appendLine("  $shortPkg: ${entry.title}")
            if (entry.text.isNotEmpty()) {
                val truncText = if (entry.text.length > 60) entry.text.take(60) + "..." else entry.text
                sb.appendLine("  $truncText")
            }
            if (entry.detail.isNotEmpty()) {
                sb.appendLine("  ${entry.detail}")
            }
            sb.appendLine()
        }

        logText.text = sb.toString()
    }
}
