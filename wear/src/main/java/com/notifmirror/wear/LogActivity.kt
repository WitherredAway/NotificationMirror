package com.notifmirror.wear

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.wearable.Wearable
import com.google.android.material.button.MaterialButton
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
    }

    private lateinit var notifLog: NotificationLog
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var appFilter: Spinner
    private lateinit var countText: TextView
    private lateinit var emptyText: TextView
    private var allEntries: List<NotificationLog.LogEntry> = emptyList()
    private var appList: List<String> = emptyList()
    private var selectedApp: String = "All Apps"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_log)

        recyclerView = findViewById(R.id.logRecyclerView)
        searchInput = findViewById(R.id.searchInput)
        appFilter = findViewById(R.id.appFilter)
        countText = findViewById(R.id.countText)
        emptyText = findViewById(R.id.emptyText)
        val clearButton = findViewById<View>(R.id.clearLogButton)

        recyclerView.layoutManager = LinearLayoutManager(this)

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
            else getAppLabel(pkg)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        appFilter.adapter = adapter

        val prevIndex = appList.indexOf(selectedApp)
        if (prevIndex >= 0) {
            appFilter.setSelection(prevIndex)
        }
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
        recyclerView.adapter = LogEntryAdapter(filtered)
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
                runOnUiThread {
                    Toast.makeText(this@LogActivity, "Action sent", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send action", e)
                runOnUiThread {
                    Toast.makeText(this@LogActivity, "Failed to send", Toast.LENGTH_SHORT).show()
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

    private inner class LogEntryAdapter(private val entries: List<NotificationLog.LogEntry>) :
        RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {

        private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appName: TextView = view.findViewById(R.id.logAppName)
            val time: TextView = view.findViewById(R.id.logTime)
            val title: TextView = view.findViewById(R.id.logTitle)
            val text: TextView = view.findViewById(R.id.logText)
            val actionsContainer: LinearLayout = view.findViewById(R.id.logActionsContainer)
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

            // Add action buttons
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

                            val button = MaterialButton(
                                holder.itemView.context,
                                null,
                                com.google.android.material.R.attr.materialButtonOutlinedStyle
                            ).apply {
                                text = actionTitle
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                                isAllCaps = false
                                minimumWidth = 0
                                minWidth = 0
                                minimumHeight = 0
                                minHeight = 0
                                setPadding(
                                    dpToPx(6),
                                    dpToPx(2),
                                    dpToPx(6),
                                    dpToPx(2)
                                )
                                val lp = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    dpToPx(24)
                                )
                                lp.marginEnd = dpToPx(4)
                                layoutParams = lp
                            }

                            button.setOnClickListener {
                                if (hasRemoteInput) {
                                    openReplyForEntry(entry.notifKey, actionIndex)
                                } else {
                                    triggerAction(entry.notifKey, actionIndex)
                                }
                            }

                            holder.actionsContainer.addView(button)
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

        private fun dpToPx(dp: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                resources.displayMetrics
            ).toInt()
        }
    }
}
