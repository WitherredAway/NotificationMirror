package com.notifmirror.wear

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class NotificationReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "NotifMirrorWear"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "WearableListenerService received message on path: ${messageEvent.path}")
        when (messageEvent.path) {
            "/notification" -> NotificationHandler.handleNotification(this, messageEvent)
            "/notification_dismiss" -> NotificationHandler.handleDismissal(this, messageEvent)
            "/action_result" -> handleActionResult(messageEvent)
        }
    }

    private fun handleActionResult(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val success = json.getBoolean("success")
            val message = json.getString("message")

            Log.d(TAG, "Action result: success=$success message=$message")

            // Clear the timeout flag so the timeout toast doesn't fire
            ActionBroadcastReceiver.awaitingResult = false

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action result", e)
        }
    }
}
