package com.notifmirror.wear

import android.content.Context
import android.util.Log

/**
 * Queues encrypted notification data on the watch when decryption fails
 * (e.g. key not yet synced). When a new key arrives, queued items are
 * retried and delivered.
 */
object PendingNotificationQueue {

    private const val TAG = "NotifMirrorPendingQ"
    private const val MAX_PENDING = 20

    private val pendingMessages = mutableListOf<ByteArray>()

    fun enqueue(encryptedData: ByteArray) {
        synchronized(pendingMessages) {
            pendingMessages.add(encryptedData.copyOf())
            while (pendingMessages.size > MAX_PENDING) {
                pendingMessages.removeAt(0)
            }
            Log.d(TAG, "Queued encrypted notification for retry (${pendingMessages.size} pending)")
        }
    }

    fun retryAll(context: Context) {
        val toRetry: List<ByteArray>
        synchronized(pendingMessages) {
            if (pendingMessages.isEmpty()) return
            toRetry = pendingMessages.toList()
            pendingMessages.clear()
        }

        Log.d(TAG, "Retrying ${toRetry.size} pending notifications with new key")
        val key = CryptoHelper.getKey(context) ?: run {
            Log.w(TAG, "Still no key available after import — re-queuing")
            synchronized(pendingMessages) {
                pendingMessages.addAll(toRetry)
            }
            return
        }

        for (data in toRetry) {
            try {
                val decrypted = CryptoHelper.decrypt(data, key)
                val fakeEvent = NotificationReceiverService.DecryptedMessageEvent("/notification", decrypted)
                NotificationHandler.handleNotification(context, fakeEvent)
                Log.d(TAG, "Successfully delivered queued notification")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt queued notification even with new key", e)
            }
        }
    }

    fun size(): Int = synchronized(pendingMessages) { pendingMessages.size }
}
