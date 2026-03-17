package com.notifmirror.wear

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.wearable.Wearable
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
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
        private const val TAG = "NotifMirrorWearLog"
        private const val PAGE_SIZE = 50
    }

    private lateinit var notifLog: NotificationLog
    private lateinit var recyclerView: RecyclerView
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

    data class ConversationGroup(
        val conversationKey: String,
        val packageName: String,
        val conversationTitle: String,
        val entries: List<NotificationLog.LogEntry>,
        val latestTime: Long
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_log)

        recyclerView = findViewById(R.id.logRecyclerView)
        countText = findViewById(R.id.countText)
        emptyText = findViewById(R.id.emptyText)
        val searchButton = findViewById<ImageButton>(R.id.searchButton)
        filterButton = findViewById(R.id.filterButton)
        groupToggleButton = findViewById(R.id.groupToggleButton)
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

        // Rotary/bezel scrolling support
        recyclerView.requestFocus()
        recyclerView.setOnGenericMotionListener { v, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                v.scrollBy(0, (delta * dpToPx(40)).toInt())
                true
            } else {
                false
            }
        }

        clearButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Log")
                .setMessage("Clear all entries?")
                .setPositiveButton("Clear") { _, _ ->
                    notifLog.clear()
                    loadEntries()
                    displayFiltered()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        searchButton.setOnClickListener {
            showSearchDialog()
        }

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

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "Search (regex)"
            setText(searchQuery)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            textSize = 14f
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                searchQuery = input.text.toString().trim()
                displayFiltered()
            }
            .setNeutralButton("Clear") { _, _ ->
                searchQuery = ""
                displayFiltered()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchQuery = input.text.toString().trim()
                displayFiltered()
                dialog.dismiss()
                true
            } else {
                false
            }
        }
    }

    private fun showFilterDialog() {
        val displayNames = appList.map { pkg ->
            if (pkg == "All Apps") pkg
            else getAppLabel(pkg)
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
            filterButton.setImageResource(R.drawable.ic_grid)
            filterButton.clearColorFilter()
        } else {
            filterButton.setImageResource(R.drawable.ic_grid)
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

    private fun getAppLabel(packageName: String): String {
        return when {
            packageName.contains("whatsapp") -> "WhatsApp"
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("instagram") -> "Instagram"
            packageName.contains("messenger") -> "Messenger"
            packageName.contains("twitter") || packageName.contains("x.android") -> "X"
            packageName.contains("gmail") -> "Gmail"
            packageName.contains("discord") -> "Discord"
            packageName.contains("slack") -> "Slack"
            packageName.contains("signal") -> "Signal"
            packageName.contains("sms") || packageName.contains("mms") -> "Messages"
            else -> packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
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
            countText.text = "Invalid regex"
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.GONE
            return
        }

        countText.text = "${filtered.size} of ${allEntries.size}"

        if (filtered.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = if (allEntries.isEmpty()) {
                "No notifications yet."
            } else {
                "No entries match."
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

    private fun triggerAction(notifKey: String, actionIndex: Int) {
        val json = JSONObject().apply {
            put("key", notifKey)
            put("actionIndex", actionIndex)
        }
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@LogActivity)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@LogActivity)
                        .sendMessage(node.id, "/action", json.toString().toByteArray())
                        .await()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send action", e)
                runOnUiThread {
                    Toast.makeText(this@LogActivity, "Failed to send action", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openReplyForEntry(notifKey: String, actionIndex: Int) {
        val intent = Intent(this, ReplyActivity::class.java).apply {
            putExtra(NotificationHandler.EXTRA_NOTIF_KEY, notifKey)
            putExtra(NotificationHandler.EXTRA_NOTIFICATION_ID, -1)
            putExtra(NotificationHandler.EXTRA_ACTION_INDEX, actionIndex)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private inner class LogEntryAdapter(private val entries: MutableList<NotificationLog.LogEntry>) :
        RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {

        private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

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

            holder.appName.text = getAppLabel(entry.packageName)
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

            // Re-push button — re-posts notification locally on watch
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
                                textSize = 10f
                                isCheckable = false
                                isClickable = true
                                chipMinHeight = dpToPx(28).toFloat()
                                ensureAccessibleTouchTarget(0)
                                setEnsureMinTouchTargetSize(false)
                                chipStartPadding = dpToPx(6).toFloat()
                                chipEndPadding = dpToPx(6).toFloat()
                            }

                            chip.setOnClickListener {
                                if (hasRemoteInput) {
                                    openReplyForEntry(entry.notifKey, actionIndex)
                                } else {
                                    triggerAction(entry.notifKey, actionIndex)
                                }
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

        private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
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

            holder.appName.text = getAppLabel(group.packageName)
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
                    textSize = 12f
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
                    text = "\u25BE ${group.entries.size - 3} more"
                    textSize = 10f
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
                    text = "\u25B4 Less"
                    textSize = 10f
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
                                textSize = 10f
                                isCheckable = false
                                isClickable = true
                                chipMinHeight = dpToPx(28).toFloat()
                                ensureAccessibleTouchTarget(0)
                                setEnsureMinTouchTargetSize(false)
                                chipStartPadding = dpToPx(6).toFloat()
                                chipEndPadding = dpToPx(6).toFloat()
                            }

                            chip.setOnClickListener {
                                if (hasRemoteInput) {
                                    openReplyForEntry(latestEntry.notifKey, actionIndex)
                                } else {
                                    triggerAction(latestEntry.notifKey, actionIndex)
                                }
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
     * Re-push a notification from history — re-posts it locally on the watch.
     */
    private fun repushNotification(entry: NotificationLog.LogEntry) {
        val nm = getSystemService(NotificationManager::class.java)

        // Ensure channel exists
        val channelId = "repush_${entry.packageName}"
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "Re-pushed: ${entry.packageName}",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(entry.title)
            .setContentText(entry.text)
            .setAutoCancel(true)
            .build()

        val notifId = (entry.packageName + entry.title + entry.text).hashCode()
        nm.notify(notifId, notification)
        Toast.makeText(this, "Notification re-pushed", Toast.LENGTH_SHORT).show()
    }
}
