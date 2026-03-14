package com.notifmirror.mobile

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class ReplyReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "NotifMirrorAction"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/reply" -> handleReply(messageEvent)
            "/action" -> handleAction(messageEvent)
        }
    }

    private fun handleReply(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val notifKey = json.getString("key")
            val actionIndex = json.getInt("actionIndex")
            val replyText = json.getString("reply")

            Log.d(TAG, "Received reply for $notifKey action $actionIndex: $replyText")

            val actionKey = "$notifKey:$actionIndex"
            val action = NotificationListener.pendingActions[actionKey]
            if (action == null) {
                Log.w(TAG, "No pending action found for key: $actionKey")
                return
            }

            val remoteInputs = action.remoteInputs
            if (remoteInputs == null || remoteInputs.isEmpty()) {
                Log.w(TAG, "No remote inputs found for action")
                return
            }

            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
            action.actionIntent.send(this, 0, intent)

            Log.d(TAG, "Reply sent successfully for $actionKey")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle reply", e)
        }
    }

    private fun handleAction(messageEvent: MessageEvent) {
        try {
            val json = JSONObject(String(messageEvent.data))
            val notifKey = json.getString("key")
            val actionIndex = json.getInt("actionIndex")

            Log.d(TAG, "Received action trigger for $notifKey action $actionIndex")

            val actionKey = "$notifKey:$actionIndex"
            val action = NotificationListener.pendingActions[actionKey]
            if (action == null) {
                Log.w(TAG, "No pending action found for key: $actionKey")
                return
            }

            action.actionIntent.send()

            Log.d(TAG, "Action executed successfully for $actionKey")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action", e)
        }
    }
}
