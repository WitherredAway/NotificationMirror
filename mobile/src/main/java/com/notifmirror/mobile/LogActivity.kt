package com.notifmirror.mobile

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_SIZE = 50
    }

    private lateinit var notifLog: NotificationLog
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var appFilter: Spinner
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private var allEntries: List<NotificationLog.LogEntry> = emptyList()
    private var filteredEntries: List<NotificationLog.LogEntry> = emptyList()
    private var displayedCount = 0
    private var appList: List<String> = emptyList()
    private var selectedApp: String = "All Apps"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_log)

        recyclerView = findViewById(R.id.logRecyclerView)
        searchInput = findViewById(R.id.searchInput)
        appFilter = findViewById(R.id.appFilter)
        countText = findViewById(R.id.countText)
        emptyText = findViewById(R.id.emptyText)
        val clearButton = findViewById<TextView>(R.id.clearLogButton)
        val exportButton = findViewById<TextView>(R.id.exportLogButton)

        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager

        // Auto-load more when scrolling to bottom
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy > 0) {
                    val totalItemCount = layoutManager.itemCount
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= totalItemCount - 5) {
                        loadMore()
                    }
                }
            }
        })

        notifLog = NotificationLog(this)

        clearButton.setOnClickListener {
            notifLog.clear()
            loadEntries()
            displayFiltered()
        }

        exportButton.setOnClickListener {
            exportLog()
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
            else getAppDisplayName(pkg)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        appFilter.adapter = adapter

        val prevIndex = appList.indexOf(selectedApp)
        if (prevIndex >= 0) {
            appFilter.setSelection(prevIndex)
        }
    }

    private fun getAppDisplayName(packageName: String): String {
        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
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
            countText.text = "Invalid regex pattern"
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.GONE
            return
        }

        countText.text = "${filtered.size} of ${allEntries.size} entries"

        if (filtered.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = if (allEntries.isEmpty()) {
                "No notifications logged yet.\n\nNotifications will appear here as they are sent to your watch."
            } else {
                "No entries match your filters."
            }
            return
        }

        emptyText.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        filteredEntries = filtered
        displayedCount = minOf(PAGE_SIZE, filtered.size)
        recyclerView.adapter = LogEntryAdapter(filtered.subList(0, displayedCount))
    }

    private fun loadMore() {
        if (displayedCount >= filteredEntries.size) return
        val newCount = minOf(displayedCount + PAGE_SIZE, filteredEntries.size)
        displayedCount = newCount
        recyclerView.adapter = LogEntryAdapter(filteredEntries.subList(0, displayedCount))
    }

    private inner class LogEntryAdapter(private val entries: List<NotificationLog.LogEntry>) :
        RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {

        private val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appName: TextView = view.findViewById(R.id.logAppName)
            val time: TextView = view.findViewById(R.id.logTime)
            val title: TextView = view.findViewById(R.id.logTitle)
            val text: TextView = view.findViewById(R.id.logText)
            val detail: TextView = view.findViewById(R.id.logDetail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]

            holder.appName.text = getAppDisplayName(entry.packageName)
            holder.time.text = sdf.format(Date(entry.time))

            if (entry.title.isNotEmpty()) {
                holder.title.text = entry.title
                holder.title.visibility = View.VISIBLE
            } else {
                holder.title.visibility = View.GONE
            }

            if (entry.text.isNotEmpty()) {
                holder.text.text = entry.text
                holder.text.visibility = View.VISIBLE
            } else {
                holder.text.visibility = View.GONE
            }

            if (entry.detail.isNotEmpty()) {
                holder.detail.text = entry.detail
                holder.detail.visibility = View.VISIBLE
            } else {
                holder.detail.visibility = View.GONE
            }
        }

        override fun getItemCount() = entries.size
    }

    private fun exportLog() {
        val entries = allEntries
        if (entries.isEmpty()) {
            Toast.makeText(this, "No entries to export", Toast.LENGTH_SHORT).show()
            return
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dq = "\""

        val csv = StringBuilder()
        csv.appendLine("timestamp,package,title,text,status,detail")
        for (entry in entries) {
            val timeStr = sdf.format(Date(entry.time))
            val escapedTitle = entry.title.replace("\"", "\"\"") 
            val escapedText = entry.text.replace("\"", "\"\"") 
            val escapedDetail = entry.detail.replace("\"", "\"\"") 
            csv.append(dq).append(timeStr).append(dq).append(',')
            csv.append(dq).append(entry.packageName).append(dq).append(',')
            csv.append(dq).append(escapedTitle).append(dq).append(',')
            csv.append(dq).append(escapedText).append(dq).append(',')
            csv.append(dq).append(entry.status).append(dq).append(',')
            csv.append(dq).append(escapedDetail).append(dq)
            csv.appendLine()
        }

        val json = StringBuilder()
        json.appendLine("[")
        for ((i, entry) in entries.withIndex()) {
            val timeStr = sdf.format(Date(entry.time))
            val escapedTitle = entry.title.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedText = entry.text.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedDetail = entry.detail.replace("\\", "\\\\").replace("\"", "\\\"")
            json.append("  {")
            json.append(dq).append("time").append(dq).append(':').append(dq).append(timeStr).append(dq).append(',')
            json.append(dq).append("package").append(dq).append(':').append(dq).append(entry.packageName).append(dq).append(',')
            json.append(dq).append("title").append(dq).append(':').append(dq).append(escapedTitle).append(dq).append(',')
            json.append(dq).append("text").append(dq).append(':').append(dq).append(escapedText).append(dq).append(',')
            json.append(dq).append("status").append(dq).append(':').append(dq).append(entry.status).append(dq).append(',')
            json.append(dq).append("detail").append(dq).append(':').append(dq).append(escapedDetail).append(dq)
            json.append("}")
            if (i < entries.size - 1) json.appendLine(",") else json.appendLine()
        }
        json.appendLine("]")

        val exportText = "=== CSV FORMAT ===\n\n${csv}\n\n=== JSON FORMAT ===\n\n${json}"

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Notification Mirror Log Export")
            putExtra(Intent.EXTRA_TEXT, exportText)
        }
        startActivity(Intent.createChooser(shareIntent, "Export Log"))
    }
}
