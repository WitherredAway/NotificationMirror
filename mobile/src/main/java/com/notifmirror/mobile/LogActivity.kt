package com.notifmirror.mobile

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NotifMirrorLog"
        private const val PAGE_SIZE = 50
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var notifLog: NotificationLog
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private lateinit var filterButton: ImageButton
    private lateinit var groupToggleButton: ImageButton
    private var allEntries: List<NotificationLog.LogEntry> = emptyList()
    private var filteredEntries: List<NotificationLog.LogEntry> = emptyList()
    private var displayedCount = 0
    private val displayedItems = mutableListOf<NotificationLog.LogEntry>()
    private var logAdapter: LogEntryAdapter? = null
    private var groupAdapter: GroupedLogAdapter? = null
    private var appList: List<String> = emptyList()
    private var selectedApp: String = "All Apps"
    private var searchQuery: String = ""
    private var isGrouped: Boolean = true

    /** A conversation group: entries sharing the same conversationKey */
    data class ConversationGroup(
        val conversationKey: String,
        val packageName: String,
        val conversationTitle: String,
        val entries: List<NotificationLog.LogEntry>,
        val latestTime: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_log)

        recyclerView = findViewById(R.id.logRecyclerView)
        searchInput = findViewById(R.id.searchInput)
        countText = findViewById(R.id.countText)
        emptyText = findViewById(R.id.emptyText)
        filterButton = findViewById(R.id.filterButton)
        groupToggleButton = findViewById(R.id.groupToggleButton)
        val exportButton = findViewById<ImageButton>(R.id.exportLogButton)
        val clearButton = findViewById<ImageButton>(R.id.clearLogButton)

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
            AlertDialog.Builder(this)
                .setTitle("Clear Log")
                .setMessage("Clear all notification log entries?")
                .setPositiveButton("Clear") { _, _ ->
                    notifLog.clear()
                    loadEntries()
                    displayFiltered()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        exportButton.setOnClickListener {
            exportLog()
        }

        // Live search filtering as user types
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                displayFiltered()
            }
        })

        filterButton.setOnClickListener {
            showFilterDialog()
        }

        groupToggleButton.setOnClickListener {
            isGrouped = !isGrouped
            updateGroupIcon()
            displayFiltered()
        }
        updateGroupIcon()

        loadEntries()
        displayFiltered()
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
        displayFiltered()
    }

    private fun updateGroupIcon() {
        if (isGrouped) {
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            groupToggleButton.setColorFilter(typedValue.data)
        } else {
            groupToggleButton.clearColorFilter()
        }
    }

    private fun showFilterDialog() {
        val displayNames = appList.map { pkg ->
            if (pkg == "All Apps") pkg
            else getAppDisplayName(pkg)
        }.toTypedArray()

        val currentIndex = appList.indexOf(selectedApp).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Filter by App")
            .setSingleChoiceItems(displayNames, currentIndex) { dialog, which ->
                selectedApp = appList.getOrElse(which) { "All Apps" }
                updateFilterIcon()
                displayFiltered()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFilterIcon() {
        if (selectedApp == "All Apps") {
            filterButton.clearColorFilter()
        } else {
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            filterButton.setColorFilter(typedValue.data)
        }
    }

    private fun loadEntries() {
        allEntries = notifLog.getEntries()
        val uniqueApps = allEntries.map { it.packageName }.distinct().sorted()
        appList = listOf("All Apps") + uniqueApps
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
        val searchRegex = if (searchQuery.isNotEmpty()) {
            try {
                Regex(searchQuery, RegexOption.IGNORE_CASE)
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
            } else if (searchQuery.isNotEmpty()) {
                false
            } else {
                true
            }
            passesApp && passesSearch
        }

        if (searchQuery.isNotEmpty() && searchRegex == null) {
            countText.text = "Invalid regex pattern"
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.GONE
            return
        }

        countText.text = "${filtered.size} of ${allEntries.size}"

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

        if (isGrouped) {
            displayGrouped(filtered)
        } else {
            displayFlat(filtered)
        }
    }

    private fun displayFlat(filtered: List<NotificationLog.LogEntry>) {
        displayedCount = minOf(PAGE_SIZE, filtered.size)
        displayedItems.clear()
        displayedItems.addAll(filtered.subList(0, displayedCount))
        logAdapter = LogEntryAdapter(displayedItems)
        groupAdapter = null
        recyclerView.adapter = logAdapter
    }

    private fun displayGrouped(filtered: List<NotificationLog.LogEntry>) {
        val groups = buildConversationGroups(filtered)
        groupAdapter = GroupedLogAdapter(groups)
        logAdapter = null
        recyclerView.adapter = groupAdapter
    }

    private fun buildConversationGroups(entries: List<NotificationLog.LogEntry>): List<ConversationGroup> {
        val groupMap = linkedMapOf<String, MutableList<NotificationLog.LogEntry>>()
        for (entry in entries) {
            val key = deriveGroupKey(entry)
            groupMap.getOrPut(key) { mutableListOf() }.add(entry)
        }
        return groupMap.map { (key, groupEntries) ->
            val latest = groupEntries.first()
            val convTitle = if (key.contains(":")) key.substringAfter(":") else ""
            ConversationGroup(
                conversationKey = key,
                packageName = latest.packageName,
                conversationTitle = convTitle,
                entries = groupEntries,
                latestTime = latest.time
            )
        }.sortedByDescending { it.latestTime }
    }

    private fun deriveGroupKey(entry: NotificationLog.LogEntry): String {
        if (entry.conversationKey.isNotEmpty()) return entry.conversationKey
        return "${entry.packageName}:${entry.title}"
    }

    private fun loadMore() {
        if (isGrouped) return
        if (displayedCount >= filteredEntries.size) return
        val oldCount = displayedCount
        val newCount = minOf(displayedCount + PAGE_SIZE, filteredEntries.size)
        displayedCount = newCount
        displayedItems.addAll(filteredEntries.subList(oldCount, newCount))
        logAdapter?.notifyItemRangeInserted(oldCount, newCount - oldCount)
    }

    private inner class LogEntryAdapter(private val entries: MutableList<NotificationLog.LogEntry>) :
        RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {

        private val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appName: TextView = view.findViewById(R.id.logAppName)
            val time: TextView = view.findViewById(R.id.logTime)
            val title: TextView = view.findViewById(R.id.logTitle)
            val text: TextView = view.findViewById(R.id.logText)
            val actionsContainer: ChipGroup = view.findViewById(R.id.logActionsContainer)
            val repushButton: ImageButton = view.findViewById(R.id.logRepushButton)
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

            // Re-push button — re-sends notification to watch
            holder.repushButton.setOnClickListener {
                repushNotification(entry)
            }

            // Add action buttons as chips
            holder.actionsContainer.removeAllViews()
            if (entry.actionsJson.isNotEmpty() && entry.notifKey.isNotEmpty()) {
                try {
                    val actions = JSONArray(entry.actionsJson)
                    if (actions.length() > 0) {
                        holder.actionsContainer.visibility = View.VISIBLE
                        for (i in 0 until actions.length()) {
                            val actionObj = actions.getJSONObject(i)
                            val actionTitle = actionObj.getString("title")
                            val actionIndex = actionObj.getInt("index")
                            val hasRemoteInput = actionObj.getBoolean("hasRemoteInput")

                            val chip = Chip(holder.itemView.context).apply {
                                text = actionTitle
                                isCheckable = false
                                isClickable = true
                            }

                            chip.setOnClickListener {
                                triggerAction(entry.notifKey, actionIndex, hasRemoteInput)
                            }

                            holder.actionsContainer.addView(chip)
                        }
                    } else {
                        holder.actionsContainer.visibility = View.GONE
                    }
                } catch (_: Exception) {
                    holder.actionsContainer.visibility = View.GONE
                }
            } else {
                holder.actionsContainer.visibility = View.GONE
            }
        }

        override fun getItemCount() = entries.size
    }

    // ── Grouped view adapter ───────────────────────────────────────────

    private inner class GroupedLogAdapter(private val groups: List<ConversationGroup>) :
        RecyclerView.Adapter<GroupedLogAdapter.ViewHolder>() {

        private val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())
        private val expandedPositions = mutableSetOf<Int>()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appName: TextView = view.findViewById(R.id.logAppName)
            val groupCount: TextView = view.findViewById(R.id.logGroupCount)
            val time: TextView = view.findViewById(R.id.logTime)
            val conversationTitle: TextView = view.findViewById(R.id.logConversationTitle)
            val messagesContainer: LinearLayout = view.findViewById(R.id.logMessagesContainer)
            val actionsContainer: ChipGroup = view.findViewById(R.id.logActionsContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groups[position]
            val latestEntry = group.entries.first()

            holder.appName.text = getAppDisplayName(group.packageName)
            holder.time.text = sdf.format(Date(group.latestTime))
            holder.groupCount.text = "(${group.entries.size})"

            if (group.conversationTitle.isNotEmpty()) {
                holder.conversationTitle.text = group.conversationTitle
                holder.conversationTitle.visibility = View.VISIBLE
            } else {
                holder.conversationTitle.visibility = View.GONE
            }

            holder.messagesContainer.removeAllViews()
            val isExpanded = expandedPositions.contains(position)
            val entriesToShow = if (isExpanded) {
                group.entries.reversed()
            } else {
                group.entries.take(3).reversed()
            }

            for (entry in entriesToShow) {
                val msgView = TextView(holder.itemView.context).apply {
                    val prefix = if (entry.title.isNotEmpty() && group.conversationTitle.isNotEmpty()) {
                        "${entry.title}: "
                    } else if (entry.title.isNotEmpty()) {
                        "${entry.title}: "
                    } else {
                        ""
                    }
                    text = "$prefix${entry.text}"
                    textSize = 13f
                    val tv = TypedValue()
                    context.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true
                    )
                    setTextColor(tv.data)
                    setPadding(0, 2, 0, 2)
                    maxLines = 3
                }
                holder.messagesContainer.addView(msgView)
            }

            if (!isExpanded && group.entries.size > 3) {
                val moreView = TextView(holder.itemView.context).apply {
                    text = "\u25BE ${group.entries.size - 3} more messages"
                    textSize = 12f
                    val tv = TypedValue()
                    context.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorPrimary, tv, true
                    )
                    setTextColor(tv.data)
                    setPadding(0, 4, 0, 0)
                }
                holder.messagesContainer.addView(moreView)
            } else if (isExpanded && group.entries.size > 3) {
                val lessView = TextView(holder.itemView.context).apply {
                    text = "\u25B4 Show less"
                    textSize = 12f
                    val tv = TypedValue()
                    context.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorPrimary, tv, true
                    )
                    setTextColor(tv.data)
                    setPadding(0, 4, 0, 0)
                }
                holder.messagesContainer.addView(lessView)
            }

            holder.itemView.setOnClickListener {
                val adapterPosition = holder.adapterPosition
                if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                if (expandedPositions.contains(adapterPosition)) {
                    expandedPositions.remove(adapterPosition)
                } else {
                    expandedPositions.add(adapterPosition)
                }
                notifyItemChanged(adapterPosition)
            }

            holder.actionsContainer.removeAllViews()
            if (latestEntry.actionsJson.isNotEmpty() && latestEntry.notifKey.isNotEmpty()) {
                try {
                    val actions = JSONArray(latestEntry.actionsJson)
                    if (actions.length() > 0) {
                        holder.actionsContainer.visibility = View.VISIBLE
                        for (i in 0 until actions.length()) {
                            val actionObj = actions.getJSONObject(i)
                            val actionTitle = actionObj.getString("title")
                            val actionIndex = actionObj.getInt("index")
                            val hasRemoteInput = actionObj.getBoolean("hasRemoteInput")

                            val chip = Chip(holder.itemView.context).apply {
                                text = actionTitle
                                isCheckable = false
                                isClickable = true
                            }

                            chip.setOnClickListener {
                                triggerAction(latestEntry.notifKey, actionIndex, hasRemoteInput)
                            }

                            holder.actionsContainer.addView(chip)
                        }
                    } else {
                        holder.actionsContainer.visibility = View.GONE
                    }
                } catch (_: Exception) {
                    holder.actionsContainer.visibility = View.GONE
                }
            } else {
                holder.actionsContainer.visibility = View.GONE
            }
        }

        override fun getItemCount() = groups.size
    }

    /**
     * Re-push a notification from history back to the watch.
     * Builds a minimal JSON payload and sends it via MessageClient.
     */
    private fun repushNotification(entry: NotificationLog.LogEntry) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("key", "repush_${System.currentTimeMillis()}")
                    put("package", entry.packageName)
                    put("title", entry.title)
                    put("text", entry.text)
                    put("isRepush", true)
                }

                // Encrypt before sending (use raw binary, not Base64 string)
                val key = CryptoHelper.getOrCreateKey(this@LogActivity)
                val encrypted = CryptoHelper.encrypt(json.toString().toByteArray(Charsets.UTF_8), key)

                val nodes = Wearable.getNodeClient(this@LogActivity).connectedNodes.await()
                if (nodes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this@LogActivity, "Watch not connected", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                for (node in nodes) {
                    Wearable.getMessageClient(this@LogActivity)
                        .sendMessage(node.id, "/notification", encrypted)
                        .await()
                }
                runOnUiThread {
                    Toast.makeText(this@LogActivity, "Notification re-pushed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-push notification", e)
                runOnUiThread {
                    Toast.makeText(this@LogActivity, "Failed to re-push", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun triggerAction(notifKey: String, actionIndex: Int, hasRemoteInput: Boolean) {
        if (hasRemoteInput) {
            val input = EditText(this).apply {
                hint = "Type your reply..."
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            AlertDialog.Builder(this)
                .setTitle("Reply")
                .setView(input)
                .setPositiveButton("Send") { _, _ ->
                    val replyText = input.text.toString().trim()
                    if (replyText.isNotEmpty()) {
                        sendAction(notifKey, actionIndex, replyText)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            sendAction(notifKey, actionIndex, null)
        }
    }

    private fun sendAction(notifKey: String, actionIndex: Int, replyText: String?) {
        val actionKey = "$notifKey:$actionIndex"
        val action = NotificationListener.pendingActions[actionKey]
        if (action == null) {
            Toast.makeText(this, "Action no longer available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (replyText != null && action.remoteInputs != null) {
                val intent = Intent()
                val remoteInput = action.remoteInputs[0]
                val bundle = android.os.Bundle()
                bundle.putCharSequence(remoteInput.resultKey, replyText)
                android.app.RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
                action.actionIntent.send(this, 0, intent)
            } else {
                action.actionIntent.send()
            }
            Toast.makeText(this, "Action sent", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send action", Toast.LENGTH_SHORT).show()
        }
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
