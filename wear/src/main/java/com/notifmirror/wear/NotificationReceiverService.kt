package com.notifmirror.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class NotificationReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "NotifMirrorWear"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "WearableListenerService received message on path: ${messageEvent.path}")
        when (messageEvent.path) {
            "/notification" -> NotificationHandler.handleNotification(this, messageEvent)
            "/notification_dismiss" -> NotificationHandler.handleDismissal(this, messageEvent)
        }
    }
}
