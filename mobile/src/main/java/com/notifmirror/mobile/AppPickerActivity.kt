package com.notifmirror.mobile

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppPickerActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var adapter: AppListAdapter
    private val selectedApps = mutableSetOf<String>()
    private var allApps = listOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_app_picker)

        settings = SettingsManager(this)
        selectedApps.addAll(settings.getWhitelistedApps())

        val recyclerView = findViewById<RecyclerView>(R.id.appList)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val searchInput = findViewById<EditText>(R.id.searchInput)

        val clearButton = findViewById<TextView>(R.id.clearButton)
        clearButton.setOnClickListener {
            selectedApps.clear()
            settings.setWhitelistedApps(emptySet())
            adapter.notifyDataSetChanged()
        }

        // Show cached apps instantly, then refresh in background
        val cached = AppListCache.getCachedApps(this)
        allApps = if (cached.isNotEmpty()) {
            cached.map { AppInfo(it.packageName, it.label, null) }
                .sortedWith(compareByDescending<AppInfo> { selectedApps.contains(it.packageName) }
                    .thenBy { it.label.lowercase() })
        } else {
            emptyList()
        }
        adapter = AppListAdapter(allApps)
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.label.lowercase().contains(query) ||
                            it.packageName.lowercase().contains(query)
                    }
                }
                adapter.updateList(filtered)
            }
        })

        // Refresh from PackageManager in background (loads icons + updates cache)
        Thread {
            val freshCached = AppListCache.refreshCache(this)
            val freshApps = AppListCache.toAppInfoList(this, freshCached)
                .sortedWith(compareByDescending<AppInfo> { selectedApps.contains(it.packageName) }
                    .thenBy { it.label.lowercase() })

            runOnUiThread {
                allApps = freshApps
                adapter.updateList(allApps)
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        settings.setWhitelistedApps(selectedApps)
    }

    data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?
    )

    inner class AppListAdapter(private var apps: List<AppInfo>) :
        RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val pkg: TextView = view.findViewById(R.id.appPackage)
            val checkbox: CheckBox = view.findViewById(R.id.appCheckbox)
            val settingsIcon: ImageView = view.findViewById(R.id.appSettings)
        }

        fun updateList(newApps: List<AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.name.text = app.label
            holder.pkg.text = app.packageName
            if (app.icon != null) {
                holder.icon.setImageDrawable(app.icon)
            }
            val isSelected = selectedApps.contains(app.packageName)
            holder.checkbox.isChecked = isSelected
            holder.settingsIcon.visibility = if (isSelected) View.VISIBLE else View.GONE

            val clickListener = View.OnClickListener {
                if (selectedApps.contains(app.packageName)) {
                    selectedApps.remove(app.packageName)
                    holder.checkbox.isChecked = false
                    holder.settingsIcon.visibility = View.GONE
                } else {
                    selectedApps.add(app.packageName)
                    holder.checkbox.isChecked = true
                    holder.settingsIcon.visibility = View.VISIBLE
                }
            }

            holder.itemView.setOnClickListener(clickListener)
            holder.checkbox.setOnClickListener(clickListener)

            holder.settingsIcon.setOnClickListener {
                val intent = Intent(this@AppPickerActivity, PerAppSettingsActivity::class.java)
                intent.putExtra(PerAppSettingsActivity.EXTRA_PACKAGE_NAME, app.packageName)
                startActivity(intent)
            }
        }

        override fun getItemCount() = apps.size
    }
}
