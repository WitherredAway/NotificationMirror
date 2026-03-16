package com.notifmirror.wear

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class TileActionActivity : AppCompatActivity() {

    companion object {
        const val ACTION_MUTE_ALL = "mute_all"
        const val ACTION_CONNECTION_DETAILS = "connection_details"
        const val ACTION_TOGGLE_MIRRORING = "toggle_mirroring"
        const val EXTRA_ACTION = "tile_action"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_MUTE_ALL -> showMuteAllDialog()
            ACTION_CONNECTION_DETAILS -> showConnectionDetails()
            ACTION_TOGGLE_MIRRORING -> toggleMirroring()
            else -> finish()
        }
    }

    private fun showMuteAllDialog() {
        val muteManager = MuteManager(this)
        if (muteManager.isAllMuted()) {
            muteManager.unmuteAll()
            Toast.makeText(this, "Notifications unmuted", Toast.LENGTH_SHORT).show()
            requestTileUpdate()
            finish()
            return
        }

        val options = arrayOf("15 minutes", "30 minutes", "1 hour", "2 hours")
        val durations = intArrayOf(15, 30, 60, 120)
        AlertDialog.Builder(this)
            .setTitle("Mute all notifications")
            .setItems(options) { _, which ->
                muteManager.muteAll(durations[which])
                Toast.makeText(this, "All muted for ${options[which]}", Toast.LENGTH_SHORT).show()
                requestTileUpdate()
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showConnectionDetails() {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@TileActionActivity).connectedNodes.await()
                val details = if (nodes.isNotEmpty()) {
                    nodes.joinToString("\n\n") { node ->
                        "Name: ${node.displayName}\nID: ${node.id}\nNearby: ${node.isNearby}"
                    }
                } else {
                    "No phone connected"
                }
                runOnUiThread {
                    AlertDialog.Builder(this@TileActionActivity)
                        .setTitle("Connection Details")
                        .setMessage(details)
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this@TileActionActivity)
                        .setTitle("Connection Details")
                        .setMessage("Failed: ${e.message}")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }
    }

    private fun toggleMirroring() {
        val prefs = getSharedPreferences("notif_mirror_settings", MODE_PRIVATE)
        val current = prefs.getBoolean("mirroring_enabled", true)
        val newState = !current
        prefs.edit().putBoolean("mirroring_enabled", newState).apply()

        // Sync to phone
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@TileActionActivity).connectedNodes.await()
                val json = org.json.JSONObject().apply { put("enabled", newState) }
                for (node in nodes) {
                    Wearable.getMessageClient(this@TileActionActivity)
                        .sendMessage(node.id, "/mirroring_toggle", json.toString().toByteArray())
                        .await()
                }
            } catch (_: Exception) {}
        }

        val label = if (newState) "Mirroring enabled" else "Mirroring paused"
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        requestTileUpdate()
        finish()
    }

    private fun requestTileUpdate() {
        try {
            androidx.wear.tiles.TileService.getUpdater(this)
                .requestUpdate(NotificationTileService::class.java)
        } catch (_: Exception) {}
    }
}
