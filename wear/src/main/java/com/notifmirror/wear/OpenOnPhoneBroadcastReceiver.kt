package com.notifmirror.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Sends an /open_app message to the phone so it can fire the stored
 * contentIntent — opening the specific conversation / screen that
 * triggered the notification.
 */
class OpenOnPhoneBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifMirrorOpen"
        const val EXTRA_NOTIF_KEY = "notif_key"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(EXTRA_NOTIF_KEY) ?: return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""

        Log.d(TAG, "Open on Phone pressed for $notifKey ($packageName)")

        val json = JSONObject().apply {
            put("key", notifKey)
            put("package", packageName)
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()

                if (nodes.isEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Phone not connected", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                for (node in nodes) {
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/open_app", json.toString().toByteArray())
                        .await()
                    Log.d(TAG, "Open app request sent to phone via node: ${node.displayName}")
                }

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Opening on phone...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send open app request", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Failed to reach phone", Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
